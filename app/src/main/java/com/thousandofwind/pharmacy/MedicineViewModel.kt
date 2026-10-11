package com.thousandofwind.pharmacy

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Screen { CABINET, EDITOR, DETAIL }
enum class CabinetFilter(val label: String) { ALL("全部"), SOON("临期"), EXPIRED("已过期"), UNKNOWN("日期待补充"), ATTENTION("需要留意") }

data class MedicineDraft(
    val id: Long = 0,
    val name: String = "",
    val expiry: String = "",
    val quantity: String = "",
    val unit: String = "",
    val efficacy: String = "",
    val notes: String = "",
    val photos: List<String> = emptyList(),
    val rawText: String = "",
    val candidates: OcrCandidates = OcrCandidates(),
    val reviewed: Boolean = false,
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val information: MedicineInformation = MedicineInformation()
) {
    fun toBundle() = Bundle().apply {
        putLong("id", id); putString("name", name); putString("expiry", expiry)
        putString("quantity", quantity); putString("unit", unit)
        putString("efficacy", efficacy); putString("notes", notes)
        putStringArrayList("photos", ArrayList(photos)); putString("rawText", rawText)
        putString("candidateName", candidates.name); putString("candidateExpiry", candidates.expiry)
        putString("candidateEfficacy", candidates.efficacy); putBoolean("reviewed", reviewed)
        putStringArrayList("candidateNames", ArrayList(candidates.nameOptions))
        putStringArrayList("candidateWarnings", ArrayList(candidates.warnings))
        putBundle("candidateInformation", Bundle().apply {
            MedicineInformationField.entries.forEach { putString(it.column, candidates.information[it]) }
        })
        putStringArrayList("tags", ArrayList(tags)); putString("tagInput", tagInput)
        putBundle("information", Bundle().apply {
            MedicineInformationField.entries.forEach { field -> putString(field.column, information[field]) }
        })
    }

    companion object {
        fun fromBundle(bundle: Bundle?) = bundle?.let {
            MedicineDraft(
                it.getLong("id"), it.getString("name").orEmpty(), it.getString("expiry").orEmpty(),
                it.getString("quantity").orEmpty(), it.getString("unit").orEmpty(),
                it.getString("efficacy").orEmpty(), it.getString("notes").orEmpty(),
                it.getStringArrayList("photos")?.toList().orEmpty(), it.getString("rawText").orEmpty(),
                OcrCandidates(it.getString("candidateName").orEmpty(), it.getString("candidateExpiry").orEmpty(), it.getString("candidateEfficacy").orEmpty(),
                    MedicineInformationField.entries.fold(MedicineInformation()) { info, field ->
                        info.withValue(field, it.getBundle("candidateInformation")?.getString(field.column).orEmpty())
                    }, it.getStringArrayList("candidateNames")?.toList().orEmpty(), it.getStringArrayList("candidateWarnings")?.toList().orEmpty()),
                it.getBoolean("reviewed"),
                normalizeMedicineTags(it.getStringArrayList("tags")?.toList().orEmpty()), it.getString("tagInput").orEmpty(),
                MedicineInformationField.entries.fold(MedicineInformation()) { info, field ->
                    info.withValue(field, it.getBundle("information")?.getString(field.column).orEmpty())
                }
            )
        } ?: MedicineDraft()
    }
}

data class Session(
    val screen: Screen = Screen.CABINET,
    val selectedId: Long = 0,
    val search: String = "",
    val filter: CabinetFilter = CabinetFilter.ALL,
    val draft: MedicineDraft = MedicineDraft(),
    val baseline: MedicineDraft = MedicineDraft(),
    val error: String? = null,
    val highlightId: Long = 0,
    val tag: String? = null
)

data class UiState(
    val session: Session = Session(),
    val medicines: List<MedicineEntity> = emptyList(),
    val loaded: Boolean = false,
    val databaseError: String? = null,
    val busy: Boolean = false,
    val scanning: Boolean = false
) {
    val selected get() = medicines.firstOrNull { it.id == session.selectedId }
    val availableTags get() = normalizeMedicineTags(medicines.flatMap { it.tags() }).sorted()
    val visible get() = medicines.filter { item ->
        matchesMedicineTextAndTag(item.name, item.efficacy, item.notes, item.tags(), session.search, session.tag,
            item.information.searchableValues()) &&
            when (session.filter) {
                CabinetFilter.ALL -> true
                CabinetFilter.SOON -> expiryStatus(item.expiryDate) == ExpiryStatus.SOON
                CabinetFilter.EXPIRED -> expiryStatus(item.expiryDate) == ExpiryStatus.EXPIRED
                CabinetFilter.UNKNOWN -> expiryStatus(item.expiryDate) == ExpiryStatus.UNKNOWN
                CabinetFilter.ATTENTION -> expiryStatus(item.expiryDate) in setOf(ExpiryStatus.EXPIRED, ExpiryStatus.SOON)
            }
    }
}

data class UiNotice(val text: String, val canUndo: Boolean = false)
private data class Records(val items: List<MedicineEntity> = emptyList(), val loaded: Boolean = false, val error: String? = null)

class MedicineViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).medicineDao()
    private val session = MutableStateFlow(Session(
        screen = runCatching { Screen.valueOf(savedState["screen"] ?: "CABINET") }.getOrDefault(Screen.CABINET),
        selectedId = savedState["selectedId"] ?: 0L,
        search = savedState["search"] ?: "",
        filter = runCatching { CabinetFilter.valueOf(savedState["filter"] ?: "ALL") }.getOrDefault(CabinetFilter.ALL),
        draft = MedicineDraft.fromBundle(savedState["draft"]),
        baseline = MedicineDraft.fromBundle(savedState["baseline"]),
        tag = savedState["tag"]
    ))
    private val busy = MutableStateFlow(false)
    private val scanning = MutableStateFlow(false)
    private val notices = Channel<UiNotice>(Channel.BUFFERED)
    val events = notices.receiveAsFlow()
    private var scanGeneration = 0
    private var removed: MedicineEntity? = null
    private val records = flow {
        // Promote readable legacy cache photos before exposing editable records. Missing files stay
        // linked rather than causing migration to erase data or invent a replacement image.
        val context = getApplication<Application>()
        for (item in dao.snapshot()) {
            val photos = item.photos().map { photo ->
                val uri = Uri.parse(photo)
                if (uri.authority == "${context.packageName}.fileprovider" && uri.path?.startsWith("/images/") == true) {
                    runCatching { withContext(Dispatchers.IO) { PhotoStore.importPhoto(context, uri) } }.getOrDefault(photo)
                } else photo
            }
            if (photos != item.photos()) dao.upsert(item.copy(photoUri = photos.firstOrNull().orEmpty(), photosJson = encodePhotos(photos)))
        }
        emitAll(dao.observeAll())
    }.map { Records(it, true) }.catch {
        emit(Records(loaded = true, error = "药箱读取失败。原记录未被清除，请关闭应用后重试。"))
    }
    val uiState = combine(session, records, busy, scanning) { current, rows, saving, recognizing ->
        UiState(current, rows.items, rows.loaded, rows.error, saving, recognizing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState(session.value))

    private fun change(update: Session.() -> Session) {
        session.value = session.value.update()
        val value = session.value
        savedState["screen"] = value.screen.name; savedState["selectedId"] = value.selectedId
        savedState["search"] = value.search; savedState["filter"] = value.filter.name
        savedState["tag"] = value.tag
        savedState["draft"] = value.draft.toBundle(); savedState["baseline"] = value.baseline.toBundle()
    }

    fun search(value: String) = change { copy(search = value, highlightId = 0) }
    fun filter(value: CabinetFilter) = change { copy(filter = value, highlightId = 0) }
    fun filterTag(value: String?) = change { copy(tag = value, highlightId = 0) }
    fun open(item: MedicineEntity) = change { copy(screen = Screen.DETAIL, selectedId = item.id) }

    fun beginNew() {
        if (busy.value || scanning.value) return
        scanGeneration++
        change { copy(screen = Screen.EDITOR, selectedId = 0, draft = MedicineDraft(), baseline = MedicineDraft(), error = null) }
    }

    fun edit(item: MedicineEntity) {
        val draft = MedicineDraft(
            id = item.id, name = item.name, expiry = item.expiryDate,
            quantity = item.remainingQuantity?.toString().orEmpty(), unit = item.quantityUnit,
            efficacy = item.efficacy, notes = item.notes, photos = item.photos(), rawText = item.ocrRawText,
            reviewed = true, tags = item.tags(), information = item.information
        )
        change { copy(screen = Screen.EDITOR, selectedId = item.id, draft = draft, baseline = draft, error = null) }
    }

    fun updateDraft(update: MedicineDraft.() -> MedicineDraft) {
        if (busy.value) return
        change { copy(draft = draft.update(), error = null) }
    }

    fun hasUnsavedChanges() = session.value.screen == Screen.EDITOR && session.value.draft != session.value.baseline

    fun back() {
        if (busy.value) return
        scanGeneration++
        scanning.value = false
        change {
            copy(screen = if (screen == Screen.EDITOR && draft.id != 0L) Screen.DETAIL else Screen.CABINET,
                draft = MedicineDraft(), baseline = MedicineDraft(), error = null)
        }
    }

    fun save() {
        if (busy.value || scanning.value || !uiState.value.loaded || uiState.value.databaseError != null) return
        val draft = session.value.draft
        // Include an unfinished custom entry so tapping Save never silently drops a typed tag.
        val tags = normalizeMedicineTags(draft.tags + draft.tagInput)
        val error = formError(draft.name, draft.expiry, draft.quantity, draft.unit)
            ?: medicineTagsError(tags)
            ?: medicineInformationError(draft.information)
            ?: if (draft.rawText.isNotBlank() && !draft.reviewed) "请先对照包装核对名称和有效期" else null
        if (error != null) { change { copy(error = error) }; return }
        busy.value = true
        viewModelScope.launch {
            try {
                val original = uiState.value.medicines.firstOrNull { it.id == draft.id }
                val now = System.currentTimeMillis()
                val id = dao.upsert(MedicineEntity(
                    id = draft.id, name = draft.name.trim(), expiryDate = draft.expiry.trim(),
                    efficacy = draft.efficacy.trim(), notes = draft.notes.trim(),
                    remainingQuantity = draft.quantity.trim().toIntOrNull(), quantityUnit = draft.unit,
                    photoUri = draft.photos.firstOrNull().orEmpty(), photosJson = encodePhotos(draft.photos),
                    ocrRawText = draft.rawText, createdAt = original?.createdAt ?: now, updatedAt = now,
                    tagsJson = encodeTags(tags), information = draft.information.trimmed()
                ))
                change { copy(screen = Screen.CABINET, search = "", filter = CabinetFilter.ALL, tag = null,
                    draft = MedicineDraft(), baseline = MedicineDraft(), error = null, highlightId = id) }
                notices.send(UiNotice(if (draft.id == 0L) "已添加到药箱" else "药品资料已更新"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                change { copy(error = "保存失败，填写内容已保留，请重试") }
            } finally { busy.value = false }
        }
    }

    fun updateStock(item: MedicineEntity, quantity: String, unit: String, done: (Boolean) -> Unit) {
        if (busy.value) return
        quantityError(quantity, unit)?.let { report(it); return }
        busy.value = true
        viewModelScope.launch {
            try {
                dao.upsert(item.copy(remainingQuantity = quantity.trim().toIntOrNull(), quantityUnit = unit, updatedAt = System.currentTimeMillis()))
                notices.send(UiNotice("余量已更新")); done(true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.send(UiNotice("更新失败，请重试")); done(false)
            } finally { busy.value = false }
        }
    }

    fun remove(item: MedicineEntity) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                dao.delete(item.id)
                removed = item
                change { copy(screen = Screen.CABINET, selectedId = 0) }
                notices.send(UiNotice("已移出药箱", canUndo = true))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.send(UiNotice("移除失败，请重试"))
            } finally { busy.value = false }
        }
    }

    fun undoRemove() {
        val item = removed ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                dao.upsert(item); removed = null
                change { copy(search = "", filter = CabinetFilter.ALL, tag = null, highlightId = item.id) }
                notices.send(UiNotice("已恢复到药箱"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.send(UiNotice("恢复失败，请重试"))
            } finally { busy.value = false }
        }
    }

    fun report(text: String) { viewModelScope.launch { notices.send(UiNotice(text)) } }

    fun scanPhoto(source: Uri) {
        if (scanning.value || busy.value || session.value.screen != Screen.EDITOR) return
        if (session.value.draft.photos.size >= 12) { report("每条记录最多保存 12 张照片"); return }
        scanning.value = true
        val generation = ++scanGeneration
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val localPhoto = withContext(Dispatchers.IO) { PhotoStore.importPhoto(context, source) }
                if (generation != scanGeneration) return@launch
                updateDraft { copy(photos = photos + localPhoto) }
                val text = recognize(Uri.parse(localPhoto))
                if (generation != scanGeneration) return@launch
                if (text.isBlank()) { report("未识别到文字。照片已保留，可补拍或手动填写。"); return@launch }
                val combined = appendOcrText(session.value.draft.rawText, text)
                if (combined == null) { report("累计识别原文超过 50000 字符，照片已保留；本次不生成候选，避免丢失核对来源。"); return@launch }
                val candidates = extractCandidates(text)
                updateDraft { copy(rawText = combined, candidates = candidates, reviewed = false) }
                report("识别完成，请对照照片核对；候选内容不会自动覆盖填写")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == scanGeneration) report("照片读取或识别失败，可补拍或手动填写")
            } finally {
                if (generation == scanGeneration) scanning.value = false
            }
        }
    }

    fun scanRegion(photo: String, region: OcrRegion, target: String, done: (Boolean) -> Unit) {
        if (scanning.value || busy.value || session.value.screen != Screen.EDITOR || photo !in session.value.draft.photos || !region.valid()) return
        scanning.value = true
        val generation = ++scanGeneration
        viewModelScope.launch {
            try {
                val cropped = withContext(Dispatchers.IO) {
                    val original = loadOcrBitmap(photo)
                    val left = (region.left * original.width).toInt().coerceIn(0, original.width - 1)
                    val top = (region.top * original.height).toInt().coerceIn(0, original.height - 1)
                    val right = (region.right * original.width).toInt().coerceIn(left + 1, original.width)
                    val bottom = (region.bottom * original.height).toInt().coerceIn(top + 1, original.height)
                    Bitmap.createBitmap(original, left, top, right - left, bottom - top)
                }
                val text = recognize(InputImage.fromBitmap(cropped, 0))
                if (generation != scanGeneration) return@launch
                if (text.isBlank()) { report("框选中未识别到文字，原候选已保留，请重画或补拍"); done(false); return@launch }
                val combined = appendOcrText(session.value.draft.rawText, text)
                if (combined == null) { report("累计识别原文超过 50000 字符，本次结果未采用；原图和原候选保留。"); done(false); return@launch }
                val incoming = regionCandidates(text, target)
                updateDraft { copy(rawText = combined, candidates = mergeRegionCandidates(candidates, incoming, target), reviewed = false) }
                report("框选识别完成，请对照原图核对后采用")
                done(true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == scanGeneration) { report("框选识别失败，请重试或补拍"); done(false) }
            } finally { if (generation == scanGeneration) scanning.value = false }
        }
    }

    private suspend fun recognize(uri: Uri): String = recognize(InputImage.fromFilePath(getApplication(), uri))

    private suspend fun recognize(image: InputImage): String = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            recognizer.process(image)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCompleteListener { recognizer.close() }
        } catch (error: Exception) {
            recognizer.close()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
