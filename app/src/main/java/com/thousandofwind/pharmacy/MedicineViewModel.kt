package com.thousandofwind.pharmacy

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import android.os.Bundle
import androidx.room.withTransaction
import java.util.UUID
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

enum class Screen { CABINET, EDITOR, DETAIL, GROUP }
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
    val information: MedicineInformation = MedicineInformation(),
    val groupKey: String = "", val groupName: String = "",
    val coverPhoto: String = "",
    val keepExistingPhotos: Boolean = true,
    val rotatedExistingPhotos: List<String> = emptyList(),
    val ocrSelectedName: String = "", val ocrAddedTags: List<String> = emptyList()
) {
    fun toBundle() = Bundle().apply {
        putLong("id", id); putString("name", name); putString("expiry", expiry)
        putString("groupKey", groupKey); putString("groupName", groupName)
        putString("quantity", quantity); putString("unit", unit)
        putString("efficacy", efficacy); putString("notes", notes)
        putStringArrayList("photos", ArrayList(photos)); putString("rawText", rawText)
        putString("coverPhoto", coverPhoto); putBoolean("keepExistingPhotos", keepExistingPhotos)
        putStringArrayList("rotatedExistingPhotos", ArrayList(rotatedExistingPhotos))
        putString("ocrSelectedName", ocrSelectedName); putStringArrayList("ocrAddedTags", ArrayList(ocrAddedTags))
        putString("candidateName", candidates.name); putString("candidateExpiry", candidates.expiry)
        putString("candidateEfficacy", candidates.efficacy); putBoolean("reviewed", reviewed)
        putStringArrayList("candidateNames", ArrayList(candidates.nameOptions))
        putStringArrayList("candidateWarnings", ArrayList(candidates.warnings))
        putStringArrayList("candidateTagNames", ArrayList(candidates.tagSuggestions.map { it.tag }))
        putStringArrayList("candidateTagEvidence", ArrayList(candidates.tagSuggestions.map { it.evidence }))
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
                    }, it.getStringArrayList("candidateNames")?.toList().orEmpty(), it.getStringArrayList("candidateWarnings")?.toList().orEmpty(),
                    it.getStringArrayList("candidateTagNames").orEmpty().zip(it.getStringArrayList("candidateTagEvidence").orEmpty()) { tag, evidence ->
                        OcrTagSuggestion(tag, evidence)
                    }),
                it.getBoolean("reviewed"),
                normalizeMedicineTags(it.getStringArrayList("tags")?.toList().orEmpty()), it.getString("tagInput").orEmpty(),
                MedicineInformationField.entries.fold(MedicineInformation()) { info, field ->
                    info.withValue(field, it.getBundle("information")?.getString(field.column).orEmpty())
                }, it.getString("groupKey").orEmpty(), it.getString("groupName").orEmpty(),
                it.getString("coverPhoto").orEmpty(), it.getBoolean("keepExistingPhotos", true),
                it.getStringArrayList("rotatedExistingPhotos")?.toList().orEmpty(),
                it.getString("ocrSelectedName").orEmpty(), it.getStringArrayList("ocrAddedTags")?.toList().orEmpty()
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
    val tag: String? = null, val groupKey: String = ""
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
    val selectedGroup get() = cabinetGroups(medicines).firstOrNull { it.key == session.groupKey }
    val availableTags get() = normalizeMedicineTags(medicines.flatMap { it.tags() }).sorted()
    val visible get() = medicines.filter { item ->
        matchesMedicineTextAndTag(item.name, item.efficacy, item.notes, item.tags(), session.search, session.tag,
            item.information.searchableValues() + item.groupName) &&
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
    private val db = AppDatabase.getInstance(application)
    private val dao = db.medicineDao()
    private val session = MutableStateFlow(Session(
        screen = runCatching { Screen.valueOf(savedState["screen"] ?: "CABINET") }.getOrDefault(Screen.CABINET),
        selectedId = savedState["selectedId"] ?: 0L,
        search = savedState["search"] ?: "",
        filter = runCatching { CabinetFilter.valueOf(savedState["filter"] ?: "ALL") }.getOrDefault(CabinetFilter.ALL),
        draft = MedicineDraft.fromBundle(savedState["draft"]),
        baseline = MedicineDraft.fromBundle(savedState["baseline"]),
        tag = savedState["tag"], groupKey = savedState["groupKey"] ?: ""
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
        savedState["tag"] = value.tag; savedState["groupKey"] = value.groupKey
        savedState["draft"] = value.draft.toBundle(); savedState["baseline"] = value.baseline.toBundle()
    }

    fun search(value: String) = change { copy(search = value, highlightId = 0) }
    fun filter(value: CabinetFilter) = change { copy(filter = value, highlightId = 0) }
    fun filterTag(value: String?) = change { copy(tag = value, highlightId = 0) }
    fun open(item: MedicineEntity) = change { copy(screen = Screen.DETAIL, selectedId = item.id, groupKey = "") }
    fun openGroup(group: CabinetGroup) = change { copy(screen = Screen.GROUP, groupKey = group.key) }
    fun openStock(item: MedicineEntity) = change { copy(screen = Screen.DETAIL, selectedId = item.id) }

    fun linkGroup(item: MedicineEntity, target: MedicineEntity?, name: String?) {
        if (busy.value || scanning.value) return
        name?.let { groupNameError(it)?.let { error -> report(error); return } }
        busy.value = true
        viewModelScope.launch {
            try {
                db.withTransaction {
                    val all = dao.snapshot()
                    require(all.any { it.id == item.id }) { "记录已不存在" }
                    val currentTarget = target?.let { wanted -> all.firstOrNull { it.id == wanted.id } ?: error("目标记录已不存在") }
                    val key = when {
                        currentTarget != null -> currentTarget.groupKey.ifBlank { UUID.randomUUID().toString() }
                        name != null -> UUID.randomUUID().toString()
                        else -> ""
                    }
                    val title = if (currentTarget != null && currentTarget.groupKey.isNotBlank()) currentTarget.groupName else name?.trim().orEmpty()
                    if (key.isNotBlank()) require(groupNameError(title) == null) { "请使用80字符以内的药品组名称" }
                    if (currentTarget != null) dao.associateGroup(currentTarget.id, key, title)
                    dao.associateGroup(item.id, key, title)
                }
                change { copy(groupKey = "") }
                report(if (target == null && name == null) "已移出药品组，库存资料保留" else "已关联药品组，各条库存仍独立")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                report("关联失败，原记录未改动")
            } finally { busy.value = false }
        }
    }

    fun addToGroup(group: CabinetGroup) {
        val source = group.records.firstOrNull() ?: return
        if (busy.value || scanning.value) return
        scanGeneration++
        val draft = MedicineDraft(name = group.name, groupKey = source.groupKey, groupName = source.groupName)
        change { copy(screen = Screen.EDITOR, selectedId = 0, draft = draft, baseline = draft, error = null) }
    }

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
            reviewed = true, tags = item.tags(), information = item.information, groupKey = item.groupKey, groupName = item.groupName,
            coverPhoto = item.photos().firstOrNull().orEmpty()
        )
        change { copy(screen = Screen.EDITOR, selectedId = item.id, draft = draft, baseline = draft, error = null) }
    }

    fun updateDraft(update: MedicineDraft.() -> MedicineDraft) {
        if (busy.value) return
        change {
            val next = draft.update()
            copy(draft = next.copy(
                ocrSelectedName = next.ocrSelectedName.takeIf { it == next.name }.orEmpty(),
                ocrAddedTags = next.ocrAddedTags.filter { tag -> next.tags.any { it.equals(tag, true) } }
            ), error = null)
        }
    }

    fun hasUnsavedChanges() = session.value.screen == Screen.EDITOR && session.value.draft != session.value.baseline

    fun back() {
        if (busy.value) return
        val discarded = unreferencedPhotos(session.value.draft.photos, session.value.baseline.photos)
        scanGeneration++
        scanning.value = false
        change {
            copy(screen = when {
                screen == Screen.EDITOR && draft.id != 0L -> Screen.DETAIL
                screen == Screen.EDITOR && draft.groupKey.isNotBlank() && groupKey == "group:${draft.groupKey}" -> Screen.GROUP
                screen == Screen.DETAIL && groupKey.isNotBlank() -> Screen.GROUP
                else -> Screen.CABINET
            },
                draft = MedicineDraft(), baseline = MedicineDraft(), error = null)
        }
        viewModelScope.launch {
            if (!cleanupPhotos(discarded)) report("已放弃修改；部分临时照片清理失败")
        }
    }

    /** Recheck DB references under the write lock; never sweep unknown files or undoable records. */
    private suspend fun cleanupPhotos(candidates: List<String>): Boolean {
        if (candidates.isEmpty()) return true
        return try {
            db.withTransaction {
                val protected = dao.snapshot().flatMap { it.photos() } + removed?.photos().orEmpty() +
                    session.value.draft.photos + session.value.baseline.photos
                withContext(Dispatchers.IO) {
                    PhotoStore.deletePhotos(getApplication(), unreferencedPhotos(candidates, protected))
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false // Cleanup failure must not turn a committed save into a reported save failure.
        }
    }

    fun save() {
        if (busy.value || scanning.value || !uiState.value.loaded || uiState.value.databaseError != null) return
        val draft = session.value.draft
        val baseline = session.value.baseline
        val retained = retainedMedicinePhotos(draft.photos, baseline.photos, draft.coverPhoto, draft.keepExistingPhotos, draft.rotatedExistingPhotos)
        // Include an unfinished custom entry so tapping Save never silently drops a typed tag.
        val tags = normalizeMedicineTags(draft.tags + draft.tagInput)
        val error = formError(draft.name, draft.expiry, draft.quantity, draft.unit)
            ?: medicineTagsError(tags)
            ?: medicineInformationError(draft.information)
            ?: if (requiresOcrConfirmation(draft, session.value.baseline)) "请先对照照片核对名称、有效期及已采用的资料" else null
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
                    photoUri = retained.firstOrNull().orEmpty(), photosJson = encodePhotos(retained),
                    ocrRawText = draft.rawText, createdAt = original?.createdAt ?: now, updatedAt = now,
                    tagsJson = encodeTags(tags), information = draft.information.trimmed(), groupKey = draft.groupKey, groupName = draft.groupName
                ))
                change { copy(screen = Screen.CABINET, search = "", filter = CabinetFilter.ALL, tag = null,
                    draft = MedicineDraft(), baseline = MedicineDraft(), error = null, highlightId = id) }
                val cleaned = cleanupPhotos(unreferencedPhotos(draft.photos + baseline.photos, retained))
                val message = if (draft.id == 0L) "已添加到药箱" else "药品资料已更新"
                notices.send(UiNotice(message + if (cleaned) "" else "；部分临时照片清理失败"))
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
        if (scanning.value || busy.value || session.value.screen != Screen.EDITOR || session.value.draft.photos.size >= 12) {
            if (session.value.draft.photos.size >= 12) report("一次录入最多使用 12 张照片，请先核对并保存")
            viewModelScope.launch(Dispatchers.IO) { PhotoStore.deleteCameraCapture(getApplication(), source) }
            return
        }
        scanning.value = true
        val generation = ++scanGeneration
        viewModelScope.launch {
            var imported: String? = null
            try {
                val context = getApplication<Application>()
                val localPhoto = withContext(Dispatchers.IO) { PhotoStore.importPhoto(context, source) }
                imported = localPhoto
                if (generation != scanGeneration) return@launch
                updateDraft { copy(photos = photos + localPhoto, coverPhoto = coverPhoto.ifBlank { photos.firstOrNull() ?: localPhoto }, reviewed = false) }
                val text = recognize(Uri.parse(localPhoto))
                if (generation != scanGeneration) return@launch
                if (text.isBlank()) { report("未识别到文字。照片留在本次草稿中，可选为药品图、补拍或手动填写。"); return@launch }
                val combined = appendOcrText(session.value.draft.rawText, text)
                if (combined == null) { report("累计识别原文超过 50000 字符，照片留在本次草稿中；本次不生成候选，避免丢失核对来源。"); return@launch }
                val candidates = extractCandidates(text)
                updateDraft { copy(rawText = combined, candidates = mergePhotoCandidates(this.candidates, candidates), reviewed = false) }
                report("识别完成，请对照照片核对；候选内容不会自动覆盖填写")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == scanGeneration) report("照片读取或识别失败，可补拍或手动填写")
            } finally {
                if (generation != scanGeneration) imported?.let {
                    if (!cleanupPhotos(listOf(it))) report("已取消识别；部分临时照片清理失败")
                }
                withContext(Dispatchers.IO) { PhotoStore.deleteCameraCapture(getApplication(), source) }
                if (generation == scanGeneration) scanning.value = false
            }
        }
    }

    fun deletePhoto(photo: String) {
        if (scanning.value || busy.value || session.value.screen != Screen.EDITOR || photo !in session.value.draft.photos) return
        // Only unlink in the draft. Original bytes remain available if editing is cancelled or undone.
        // Candidates currently have no per-photo provenance; clear all unadopted suggestions rather
        // than leave a removed image's suggestions applicable. Adopted/manual fields and raw text stay.
        val baselinePhotos = session.value.baseline.photos
        updateDraft { removeDraftPhoto(this, photo) }
        val temporary = photo !in baselinePhotos
        viewModelScope.launch { if (temporary && !cleanupPhotos(listOf(photo))) report("临时照片清理失败") }
        report("已从草稿移除照片；已填写资料和原文保留，待采用候选已清空。保存后清理未引用文件，取消编辑保留原记录")
    }

    fun rotatePhoto(photo: String, done: (String) -> Unit) {
        if (scanning.value || busy.value || session.value.screen != Screen.EDITOR || photo !in session.value.draft.photos) return
        scanning.value = true
        val generation = ++scanGeneration
        viewModelScope.launch {
            var rotated: String? = null
            try {
                val result = withContext(Dispatchers.IO) { PhotoStore.rotateClockwise(getApplication(), photo) }
                rotated = result
                if (generation != scanGeneration) return@launch
                val wasExisting = photo in session.value.baseline.photos || photo in session.value.draft.rotatedExistingPhotos
                updateDraft { replaceDraftPhoto(this, photo, result, wasExisting) }
                done(result)
                if (!cleanupPhotos(listOf(photo))) report("旋转完成；旧临时副本清理失败")
                report("照片已顺时针旋转90°；长边超过2400像素的照片会缩小，可重识别，保存后生效")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == scanGeneration) report("旋转失败，原照片未改动，请重试")
            } finally {
                if (generation != scanGeneration) rotated?.let { cleanupPhotos(listOf(it)) }
                if (generation == scanGeneration) scanning.value = false
            }
        }
    }

    fun rescanPhoto(photo: String) = scanRegion(photo, OcrRegion(0f, 0f, 1f, 1f), OCR_TARGET_ALL) {}

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
                if (text.isBlank()) {
                    report(if (target == OCR_TARGET_ALL) "整图未识别到文字，原候选已保留，可旋转、框选或补拍" else "框选中未识别到文字，原候选已保留，请重画或补拍")
                    done(false); return@launch
                }
                val combined = appendOcrText(session.value.draft.rawText, text)
                if (combined == null) { report("累计识别原文超过 50000 字符，本次结果未采用；原图和原候选保留。"); done(false); return@launch }
                val incoming = if (target == OCR_TARGET_ALL) extractCandidates(text) else regionCandidates(text, target)
                updateDraft { copy(rawText = combined, candidates = if (target == OCR_TARGET_ALL) replacePhotoCandidates(incoming)
                    else mergeRegionCandidates(candidates, incoming, target), reviewed = false) }
                report(if (target == OCR_TARGET_ALL) "整图识别完成，请对照照片核对后采用" else "框选识别完成，请对照原图核对后采用")
                done(true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == scanGeneration) {
                    report(if (target == OCR_TARGET_ALL) "整图识别失败，原候选已保留，请重试或补拍" else "框选识别失败，请重试或补拍")
                    done(false)
                }
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
