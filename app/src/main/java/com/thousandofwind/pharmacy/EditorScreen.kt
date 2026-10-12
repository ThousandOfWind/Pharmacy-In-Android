package com.thousandofwind.pharmacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset

/** Historical raw text is not a new scan. Confirmation visibility never depends on its checked state. */
fun hasCurrentOcrResults(draft: MedicineDraft, baseline: MedicineDraft): Boolean =
    draft.candidates != OcrCandidates() || draft.rawText.isNotBlank() && draft.rawText != baseline.rawText

fun showOcrConfirmation(draft: MedicineDraft, baseline: MedicineDraft): Boolean =
    draft.rawText.isNotBlank() || draft.photos != baseline.photos || draft.coverPhoto != baseline.coverPhoto || draft.keepExistingPhotos != baseline.keepExistingPhotos

fun requiresOcrConfirmation(draft: MedicineDraft, baseline: MedicineDraft): Boolean =
    !draft.reviewed && showOcrConfirmation(draft, baseline)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: UiState, update: (MedicineDraft.() -> MedicineDraft) -> Unit,
    camera: () -> Unit, picker: () -> Unit,
    scanRegion: (String, OcrRegion, String, (Boolean) -> Unit) -> Unit,
    rotatePhoto: (String, (String) -> Unit) -> Unit, deletePhoto: (String) -> Unit, rescanPhoto: (String) -> Unit
) {
    val draft = state.session.draft
    val baseline = state.session.baseline
    val editing = draft.id != 0L
    val currentOcr = hasCurrentOcrResults(draft, baseline)
    val cover = draft.coverPhoto.takeIf { it in draft.photos } ?: draft.photos.firstOrNull()
    var tab by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var warnings by rememberSaveable(draft.id) { mutableStateOf(false) }
    var cropPhoto by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var raw by rememberSaveable(draft.id) { mutableStateOf(false) }
    var assign by rememberSaveable(draft.id) { mutableStateOf(false) }
    var reducePhotos by rememberSaveable(draft.id) { mutableStateOf(false) }
    var datePicker by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    datePicker?.let { target ->
        val value = if (target == "expiry") draft.expiry else draft.information.productionDate
        val initial = expiryDay(value)?.takeIf { it.year in 1900..2100 }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { datePicker = null },
            confirmButton = { TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    update { if (target == "expiry") copy(expiry = date, reviewed = if (editing && !currentOcr) reviewed else false)
                        else copy(information = information.withValue(MedicineInformationField.PRODUCTION_DATE, date), reviewed = if (editing && !currentOcr) reviewed else false) }
                }
                datePicker = null
            }, enabled = pickerState.selectedDateMillis != null) { Text("确认日期") } },
            dismissButton = { TextButton(onClick = { datePicker = null }) { Text("取消") } }) {
            DatePicker(pickerState, title = { Text(if (target == "expiry") "选择包装上的有效期至" else "选择包装上的生产日期", Modifier.padding(20.dp)) })
        }
    }
    cropPhoto?.let { photo ->
        OcrRegionDialog(photo, state.scanning || state.busy, { cropPhoto = null }) { region, target ->
            scanRegion(photo, region, target) { success -> if (success) cropPhoto = null }
        }
    }
    if (assign) OcrTextAssignmentDialog(draft, state.busy || state.scanning, { assign = false }, update)
    if (reducePhotos) AlertDialog(
        onDismissRequest = { if (!state.busy) reducePhotos = false }, title = { Text("保存时仅保留药品图？") },
        text = { Text("成功保存后清理其他旧照片。保存前可关闭此选项或更换药品图；取消编辑保留原记录。") },
        confirmButton = { TextButton(onClick = { update { copy(keepExistingPhotos = false, reviewed = false) }; reducePhotos = false }, enabled = !state.busy && !state.scanning) { Text("确认仅保留药品图") } },
        dismissButton = { TextButton(onClick = { reducePhotos = false }, enabled = !state.busy) { Text("取消") } })
    var lastRaw by rememberSaveable(draft.id) { mutableStateOf(draft.rawText) }
    LaunchedEffect(draft.rawText, state.scanning) {
        if (!state.scanning && draft.rawText != lastRaw) {
            if (currentOcr) tab = 0
            lastRaw = draft.rawText
        }
    }
    if (warnings) AlertDialog(onDismissRequest = { warnings = false }, title = { Text("识别提示 · 请对照原图") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            draft.candidates.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = { warnings = false }) { Text("知道了") } })
    LaunchedEffect(state.session.error) {
        if (state.session.error != null) {
            val leafletError = MedicineInformationField.entries.any { it.group == MedicineInformationGroup.LEAFLET && medicineInformationFieldError(it, draft.information[it]) != null }
            tab = if (leafletError) 1 else if (requiresOcrConfirmation(draft, baseline) && formError(draft.name, draft.expiry, draft.quantity, draft.unit) == null && medicineTagsError(draft.tags + draft.tagInput) == null && medicineInformationError(draft.information) == null) 2 else 0
        }
    }
    Column(Modifier.fillMaxSize()) {
        // Always visible, outside the long scrolling candidate/form area and in every tab.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { tab = 2; camera() }, enabled = !state.scanning && !state.busy, modifier = Modifier.weight(1f)) {
                Text(if (draft.photos.isEmpty()) "拍照添加" else "补拍照片")
            }
            OutlinedButton(onClick = { tab = 2; picker() }, enabled = !state.scanning && !state.busy, modifier = Modifier.weight(1f)) { Text("相册添加") }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("基本资料", "说明书", "照片 / 原文").forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) }) }
        }
        if (currentOcr) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("识别建议已放到对应栏位，采用只填空白。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (draft.candidates.warnings.isNotEmpty()) TextButton(onClick = { warnings = true }) { Text("提示 ${draft.candidates.warnings.size}") }
        }
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        key(draft.id, tab) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (draft.groupKey.isNotBlank()) Text("关联药品组：${draft.groupName}；本条库存独立保存。", style = MaterialTheme.typography.bodySmall)
                when (tab) {
                    0 -> {
                        Text(if (editing) "修改已保存的资料" else "核对并填写基本资料", style = MaterialTheme.typography.titleMedium)
                        if (currentOcr && fillEmptyOcrFields(draft) != draft) TextButton(onClick = { update { fillEmptyOcrFields(this) } }, enabled = !state.busy && !state.scanning) { Text("采用空白基本资料（不含说明书安全条目）") }
                        PaperField(draft.name, { value -> update { copy(name = value, ocrSelectedName = "", reviewed = if (editing && !currentOcr) reviewed else false) } },
                            label = "药品名称（必填）", enabled = !state.busy, modifier = Modifier.fillMaxWidth(), isError = state.session.error != null && draft.name.isBlank())
                        OcrNameSuggestions(draft, !state.busy && !state.scanning, update)
                        PaperField(draft.expiry, { value -> update { copy(expiry = value, reviewed = if (editing && !currentOcr) reviewed else false) } },
                            label = "有效期至（可选）", enabled = !state.busy, isError = draft.expiry.isNotBlank() && expiryDay(draft.expiry) == null,
                            trailingIcon = { TextButton(onClick = { datePicker = "expiry" }, enabled = !state.busy) { Text("选日期") } },
                            supportingText = { Text("YYYY-MM 或 YYYY-MM-DD；月份按月末计算，生产日期不代填有效期。", style = MaterialTheme.typography.bodySmall) }, modifier = Modifier.fillMaxWidth())
                        OcrFieldSuggestion("有效期至", draft.candidates.expiry, draft.expiry, !state.busy && !state.scanning) { update { adoptOcrField(this, OCR_TARGET_EXPIRY) } }
                        PaperField(draft.information.productionDate, { value ->
                            update { copy(information = information.withValue(MedicineInformationField.PRODUCTION_DATE, value), reviewed = if (editing && !currentOcr) reviewed else false) }
                        }, label = "生产日期（可选）", enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                            isError = medicineInformationFieldError(MedicineInformationField.PRODUCTION_DATE, draft.information.productionDate) != null,
                            trailingIcon = { TextButton(onClick = { datePicker = "production" }, enabled = !state.busy) { Text("选日期") } },
                            supportingText = { Text("YYYY-MM 或 YYYY-MM-DD；独立记录，不用于推算有效期。", style = MaterialTheme.typography.bodySmall) })
                        OcrFieldSuggestion("生产日期", draft.candidates.information.productionDate, draft.information.productionDate, !state.busy && !state.scanning) { update { adoptOcrField(this, MedicineInformationField.PRODUCTION_DATE.column) } }
                        if (!editing && draft.groupKey.isBlank()) {
                            val related = state.medicines.firstOrNull { sameMedicineHint(draft, it) && it.groupKey.isNotBlank() }
                            if (related != null) OutlinedButton(onClick = { update { copy(groupKey = related.groupKey, groupName = related.groupName) } }, enabled = !state.busy) { Text("关联到已有组：${related.groupName}") }
                        }
                        QuantityFields(draft.quantity, draft.unit, !state.busy, { value -> update { copy(quantity = value) } }, { value -> update { copy(unit = value) } })
                        Text("余量留空为未知，0为已用完；不同单位不相加。", style = MaterialTheme.typography.bodySmall)
                        MedicineInformationInput(MedicineInformationField.STORAGE_LOCATION, draft.information.storageLocation, !state.busy) { value -> update { copy(information = information.withValue(MedicineInformationField.STORAGE_LOCATION, value)) } }
                        Text("品牌、规格与生产资料", style = MaterialTheme.typography.titleMedium)
                        MedicineInformationField.entries.filter { it.group == MedicineInformationGroup.IDENTITY && it !in setOf(MedicineInformationField.STORAGE_LOCATION, MedicineInformationField.PRODUCTION_DATE) }.forEach { field ->
                            DraftInformationWithOcr(field, draft, !state.busy && !state.scanning, update)
                        }
                        MedicineTagEditor(draft, state.availableTags, !state.busy && !state.scanning, update, ocrSuggestions = true)
                        PaperField(draft.notes, { value -> update { copy(notes = value) } }, label = "备注", singleLine = false, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    }
                    1 -> {
                        LeafletDisclaimer()
                        PaperField(draft.efficacy, { value -> update { copy(efficacy = value) } }, label = "适应症 / 功能主治（原文）", singleLine = false, minLines = 2, maxLines = 5, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                        OcrFieldSuggestion("适应症 / 功能主治", draft.candidates.efficacy, draft.efficacy, !state.busy && !state.scanning) { update { adoptOcrField(this, OCR_TARGET_EFFICACY) } }
                        MedicineInformationField.entries.filter { it.group == MedicineInformationGroup.LEAFLET }.forEach { field ->
                            DraftInformationWithOcr(field, draft, !state.busy && !state.scanning, update)
                        }
                    }
                    2 -> {
                        Text("照片与识别原文", style = MaterialTheme.typography.titleMedium)
                        if (draft.photos.isEmpty()) Text("点击顶部“拍照添加”或“相册添加”；也可直接填写基本资料。", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("点击放大；旋转按钮直接显示在照片下方。", style = MaterialTheme.typography.bodySmall)
                            PhotoStrip(draft.photos, enabled = !state.scanning && !state.busy, onCrop = { cropPhoto = it }, cover = cover,
                                onSetCover = { photo -> update { copy(coverPhoto = photo, reviewed = false) } },
                                onRotate = rotatePhoto, onDelete = deletePhoto, onRescan = rescanPhoto)
                            if (baseline.photos.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = !draft.keepExistingPhotos, onCheckedChange = { selected ->
                                    if (selected) reducePhotos = true else update { copy(keepExistingPhotos = true, reviewed = false) }
                                }, enabled = !state.busy && !state.scanning)
                                Text("保存时仅保留药品图（可取消）", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (draft.keepExistingPhotos && baseline.photos.isNotEmpty()) "保存时保留未移除的旧照片和选中的药品图，其他本次识别照片清理。"
                                else "保存时仅保留选中的药品图。保存失败保留草稿；取消编辑保留旧照片。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (draft.rawText.isNotBlank()) {
                            Text("识别建议已放在基本资料/说明书对应栏位。原文可能包含历史照片结果，长按可选择复制。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { raw = !raw }, modifier = Modifier.fillMaxWidth()) { Text(if (raw) "收起识别原文" else "查看 / 复制识别原文") }
                            if (raw) SelectableOcrText(draft.rawText)
                            OutlinedButton(onClick = { assign = true }, enabled = !state.busy && !state.scanning, modifier = Modifier.fillMaxWidth()) { Text("粘贴原文 → 指定资料栏") }
                        }
                        OutlinedButton(onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth()) { Text("前往基本资料核对 / 补填") }
                    }
                }
                Text("各分页的修改一起保存；返回可放弃本次编辑。", style = MaterialTheme.typography.bodySmall)
            }
        }
        // Fixed above Save, stays visible after checking so it is always reversible.
        if (showOcrConfirmation(draft, baseline)) Surface(color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = draft.reviewed, role = Role.Checkbox, enabled = !state.busy && !state.scanning,
                onValueChange = { value -> update { copy(reviewed = value) } }).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.reviewed, onCheckedChange = null, enabled = !state.busy && !state.scanning)
                Spacer(Modifier.width(8.dp))
                Text(if (draft.reviewed) "已核对 · 再点可取消确认" else "我已对照照片核对日期及采用资料", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DraftInformationWithOcr(field: MedicineInformationField, draft: MedicineDraft, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MedicineInformationInput(field, draft.information[field], enabled) { value -> update { copy(information = information.withValue(field, value)) } }
        OcrFieldSuggestion(field.label, draft.candidates.information[field], draft.information[field], enabled) { update { adoptOcrField(this, field.column) } }
    }
}

@Composable
fun QuantityFields(quantity: String, unit: String, enabled: Boolean, onQuantity: (String) -> Unit, onUnit: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperField(value = quantity, onValueChange = onQuantity, label = "当前余量（可选）", enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) { Text(if (unit.isBlank()) "选择单位" else "$unit ▾") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                quantityUnits.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onUnit(option); menu = false }) }
            }
        }
    }
}
