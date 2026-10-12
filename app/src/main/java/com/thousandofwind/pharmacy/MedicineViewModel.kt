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
    val keepExistingPhotos: Boolean = true
) {
    fun toBundle() = Bundle().apply {
        putLong("id", id); putString("name", name); putString("expiry", expiry)
        putString("groupKey", groupKey); putString("groupName", groupName)
        putString("quantity", quantity); putString("unit", unit)
        putString("efficacy", efficacy); putString("notes", notes)
        putStringArrayList("photos", ArrayList(photos)); putString("rawText", rawText)
        putString("coverPhoto", coverPhoto); putBoolean("keepExistingPhotos", keepExistingPhotos)
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
                }, it.getString("groupKey").orEmpty(), it.getString("groupName").orEmpty(),
                it.getString("coverPhoto").orEmpty(), it.getBoolean("keepExistingPhotos", true)
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
    val tag: String? = null, val groupKey: String = "",
    val managing: Boolean = false, val deletionIds: Set<Long> = emptySet()
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
    val selectedForDeletion get() = selectedVisibleMedicines(visible, session.deletionIds)
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

data class UiNotice(val text: String, val undoToken: String? = null)
private data class DeletedBatch(val token: String, val records: List<MedicineEntity>)
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
        tag = savedState["tag"], groupKey = savedState["groupKey"] ?: "",
        managing = savedState["managing"] ?: false,
        deletionIds = savedState.get<LongArray>("deletionIds")?.toSet().orEmpty()
    ))
    private val busy = MutableStateFlow(false)
    private val scanning = MutableStateFlow(false)
    private val notices = Channel<UiNotice>(Channel.BUFFERED)
    val events = notices.receiveAsFlow()
    private var scanGeneration = 0
    private var removed: DeletedBatch? = null
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
        savedState["managing"] = value.managing; savedState["deletionIds"] = value.deletionIds.toLongArray()
        savedState["draft"] = value.draft.toBundle(); savedState["baseline"] = value.baseline.toBundle()
    }

    fun search(value: String) { if (!busy.value) change { copy(search = value, highlightId = 0, deletionIds = emptySet()) } }
    fun filter(value: CabinetFilter) { if (!busy.value) change { copy(filter = value, highlightId = 0, deletionIds = emptySet()) } }
    fun filterTag(value: String?) { if (!busy.value) change { copy(tag = value, highlightId = 0, deletionIds = emptySet()) } }

    fun manage() {
        if (busy.value || scanning.value || !uiState.value.loaded || uiState.value.databaseError != null) return
        change { copy(managing = true, deletionIds = emptySet(), highlightId = 0) }
    }

    fun finishManaging() { if (!busy.value) change { copy(managing = false, deletionIds = emptySet()) } }

    fun toggleDeletion(id: Long) {
        if (busy.value || !session.value.managing || uiState.value.visible.none { it.id == id }) return
        change { copy(deletionIds = if (id in deletionIds) deletionIds - id else deletionIds + id) }
    }

    fun selectVisibleForDeletion() {
        if (busy.value || !session.value.managing) return
        val ids = uiState.value.visible.map { it.id }.toSet()
        change { copy(deletionIds = if (ids.isNotEmpty() && deletionIds.containsAll(ids)) emptySet() else ids) }
    }
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
        change { copy(draft = draft.update(), error = null) }
    }

    fun hasUnsavedChanges() = session.value.screen == Screen.EDITOR && session.value.draft != session.value.baseline

    fun back() {
        if (busy.value) return
        if (session.value.screen == Screen.CABINET && session.value.managing) { finishManaging(); return }
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
                val protected = dao.snapshot().flatMap { it.photos() } + removed?.records.orEmpty().flatMap { it.photos() } +
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
        val retained = retainedMedicinePhotos(draft.photos, baseline.photos, draft.coverPhoto, draft.keepExistingPhotos)
        // Include an unfinished custom entry so tapping Save never silently drops a typed tag.
        val tags = normalizeMedicineTags(draft.tags + draft.tagInput)
        val error = formError(draft.name, draft.expiry, draft.quantity, draft.unit)
            ?: medicineTagsError(tags)
            ?: medicineInformationError(draft.information)
            ?: if (draft.rawText.isNotBlank() && !draft.reviewed) "请先对照照片核对名称、有效期及已采用的资料" else null
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

    fun remove(ids: Set<Long>, done: (Boolean) -> Unit) {
        val current = uiState.value
        if (busy.value || scanning.value || !current.loaded || current.databaseError != null) return
        if (session.value.managing && !current.selectedForDeletion.map { it.id }.toSet().containsAll(ids)) {
            report("列表已变化，请重新选择后确认"); done(false); return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val deleted = db.withTransaction {
                    val targets = deletionTargets(dao.snapshot(), ids)
                    // Keep bind parameters below SQLite's limit; the whole batch remains atomic.
                    targets.map { it.id }.chunked(900).forEach { dao.deleteRecords(it) }
                    targets
                }
                val previous = removed
                val batch = DeletedBatch(UUID.randomUUID().toString(), deleted)
                removed = batch
                change { copy(screen = Screen.CABINET, selectedId = 0, groupKey = "", managing = false,
                    deletionIds = emptySet(), highlightId = 0) }
                done(true)
                val cleaned = cleanupPhotos(previous?.records.orEmpty().flatMap { it.photos() })
                notices.send(UiNotice("已移出 ${deleted.size} 条库存记录" + if (cleaned) "" else "；部分旧照片清理失败", batch.token))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.send(UiNotice("移出失败，未删除任何记录，请重新核对后重试")); done(false)
            } finally { busy.value = false }
        }
    }

    fun undoRemove(token: String) {
        val batch = removed?.takeIf { it.token == token } ?: run { report("这次移出已无法撤销"); return }
        if (busy.value) {
            viewModelScope.launch { notices.send(UiNotice("正在处理其他操作，本次未撤销，请稍后重试", token)) }; return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                db.withTransaction { dao.restoreRecords(batch.records) }
                if (removed?.token == token) removed = null
                // Restoring inventory must not navigate away from or discard a newly opened editor.
                change { copy(search = "", filter = CabinetFilter.ALL, tag = null,
                    managing = false, deletionIds = emptySet(), highlightId = batch.records.first().id) }
                notices.send(UiNotice("已恢复 ${batch.records.size} 条库存记录"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notices.send(UiNotice("恢复失败，未覆盖现有记录，请重试", token))
            } finally { busy.value = false }
        }
    }

    /** A stale snackbar must never expire or undo a newer batch. */
    fun finishRemove(token: String) {
        val batch = removed?.takeIf { it.token == token } ?: return
        removed = null
        viewModelScope.launch {
            if (!cleanupPhotos(batch.records.flatMap { it.photos() })) report("已移出药箱；部分照片清理失败")
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
                updateDraft { copy(photos = photos + localPhoto, coverPhoto = coverPhoto.ifBlank { photos.firstOrNull() ?: localPhoto }) }
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
