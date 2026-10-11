package com.thousandofwind.pharmacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset

/** Historical raw text alone is not a new scan and must not recreate an empty candidate card. */
fun hasCurrentOcrResults(draft: MedicineDraft, baseline: MedicineDraft): Boolean =
    draft.candidates != OcrCandidates() || draft.rawText.isNotBlank() && draft.rawText != baseline.rawText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: UiState, update: (MedicineDraft.() -> MedicineDraft) -> Unit,
    camera: () -> Unit, picker: () -> Unit,
    scanRegion: (String, OcrRegion, String, (Boolean) -> Unit) -> Unit,
    rotatePhoto: (String, (String) -> Unit) -> Unit, deletePhoto: (String) -> Unit, rescanPhoto: (String) -> Unit
) {
    val draft = state.session.draft
    val editing = draft.id != 0L
    val currentOcr = hasCurrentOcrResults(draft, state.session.baseline)
    var tab by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var cropPhoto by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var raw by rememberSaveable(draft.id) { mutableStateOf(false) }
    var assign by rememberSaveable(draft.id) { mutableStateOf(false) }
    cropPhoto?.let { photo ->
        OcrRegionDialog(photo, state.scanning || state.busy, { cropPhoto = null }) { region, target ->
            scanRegion(photo, region, target) { success -> if (success) cropPhoto = null }
        }
    }
    if (assign) OcrTextAssignmentDialog(draft, state.busy || state.scanning, { assign = false }, update)
    var identity by rememberSaveable(draft.id) { mutableStateOf(false) }
    var leaflet by rememberSaveable(draft.id) { mutableStateOf(false) }
    var notes by rememberSaveable(draft.id) { mutableStateOf(false) }
    var optional by rememberSaveable(draft.id) { mutableStateOf(false) }
    var tags by rememberSaveable(draft.id) { mutableStateOf(false) }
    LaunchedEffect(state.session.error) {
        if (state.session.error != null) {
            val leafletError = MedicineInformationField.entries.any { it.group == MedicineInformationGroup.LEAFLET && medicineInformationFieldError(it, draft.information[it]) != null }
            if (editing) {
                tab = if (leafletError) 1 else if (draft.rawText.isNotBlank() && !draft.reviewed && formError(draft.name, draft.expiry, draft.quantity, draft.unit) == null && medicineTagsError(draft.tags + draft.tagInput) == null && medicineInformationError(draft.information) == null) 2 else 0
            } else {
                optional = true
                if (medicineTagsError(draft.tags + draft.tagInput) != null) tags = true
                if (leafletError) leaflet = true else identity = true
            }
        }
    }
    var datePicker by rememberSaveable(draft.id) { mutableStateOf(false) }
    if (datePicker) {
        val initial = expiryDay(draft.expiry)?.takeIf { it.year in 1900..2100 }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val value = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        update { copy(expiry = value, reviewed = if (editing) reviewed else false) }
                    }
                    datePicker = false
                }, enabled = pickerState.selectedDateMillis != null) { Text("确认日期") }
            }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState, title = { Text("选择包装上的有效期至", Modifier.padding(20.dp)) }) }
    }
    Column(Modifier.fillMaxSize()) {
        if (editing) {
            TabRow(selectedTabIndex = tab) {
                listOf("基本资料", "说明书", "照片 / 原文").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            if (currentOcr && tab != 2) OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("有本次识别结果 → 前往核对")
            }
            if (draft.rawText.isNotBlank() && !draft.reviewed && tab != 2)
                Text("照片或识别资料已变更，请到“照片 / 原文”核对确认后保存。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = PaperPalette.Error)
        }
        key(draft.id, if (editing) tab else -1) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (draft.groupKey.isNotBlank()) Text("关联药品组：${draft.groupName}。这条库存的资料仍独立保存。", style = MaterialTheme.typography.bodySmall)
                if (!editing || tab == 2) {
                    Text(if (editing) "照片与识别来源" else "拍照识别，对照包装后采用", style = MaterialTheme.typography.titleMedium)
                    if (draft.photos.isNotEmpty()) PhotoStrip(draft.photos, enabled = !state.scanning && !state.busy,
                        onCrop = { cropPhoto = it }, onRotate = rotatePhoto, onDelete = deletePhoto, onRescan = rescanPhoto)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = camera, enabled = !state.scanning && !state.busy, modifier = Modifier.weight(1f)) {
                            InkIcon(InkSymbol.CAMERA); Spacer(Modifier.width(8.dp)); Text(if (draft.photos.isEmpty()) "拍药盒" else "补拍照片")
                        }
                        OutlinedButton(onClick = picker, enabled = !state.scanning && !state.busy) { Text("从相册选择") }
                    }
                    if (state.scanning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在处理照片，已有填写不会被覆盖…", style = MaterialTheme.typography.bodySmall)
                    }
                    if (currentOcr) OcrCandidatesPanel(draft, state.busy || state.scanning, update)
                    else if (editing && draft.rawText.isNotBlank()) {
                        Text("历史识别原文仅供参考，不重复生成候选。需要补充资料时，可复制归入或重识别照片。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { raw = !raw }, modifier = Modifier.fillMaxWidth()) { Text(if (raw) "收起历史原文" else "查看 / 复制历史原文") }
                        if (raw) SelectableOcrText(draft.rawText)
                        OutlinedButton(onClick = { assign = true }, enabled = !state.busy && !state.scanning, modifier = Modifier.fillMaxWidth()) { Text("粘贴原文 → 指定资料栏") }
                    }
                }
                if (!editing || tab == 0) {
                    Text(if (editing) "修改已保存的资料" else "基本资料", style = MaterialTheme.typography.titleMedium)
                    PaperField(draft.name, { value -> update { copy(name = value, reviewed = if (editing) reviewed else false) } },
                        label = "药品名称（必填）", enabled = !state.busy, modifier = Modifier.fillMaxWidth(), isError = state.session.error != null && draft.name.isBlank())
                    PaperField(draft.expiry, { value -> update { copy(expiry = value, reviewed = if (editing) reviewed else false) } },
                        label = "有效期至（可选）", enabled = !state.busy, isError = draft.expiry.isNotBlank() && expiryDay(draft.expiry) == null,
                        trailingIcon = { TextButton(onClick = { datePicker = true }, enabled = !state.busy) { Text("选日期") } },
                        supportingText = { Text("YYYY-MM 或 YYYY-MM-DD；月份按月末计算。生产日期不是有效期。", style = MaterialTheme.typography.bodySmall) }, modifier = Modifier.fillMaxWidth())
                    if (!editing && draft.groupKey.isBlank()) {
                        val related = state.medicines.firstOrNull { sameMedicineHint(draft, it) && it.groupKey.isNotBlank() }
                        if (related != null) OutlinedButton(onClick = { update { copy(groupKey = related.groupKey, groupName = related.groupName) } }, enabled = !state.busy) { Text("关联到药箱已有组：${related.groupName}") }
                    }
                    if (editing) {
                        InventoryFields(draft, !state.busy, update)
                        Text("品牌、规格与生产资料", style = MaterialTheme.typography.titleMedium)
                        MedicineInformationFields(draft.information, MedicineInformationGroup.IDENTITY, !state.busy, exclude = setOf(MedicineInformationField.STORAGE_LOCATION)) { field, value -> update { copy(information = information.withValue(field, value)) } }
                        MedicineTagEditor(draft, state.availableTags, !state.busy, update)
                        DraftNotes(draft, !state.busy, update)
                    } else PaperDisclosure("余量、位置与更多资料", optional, { optional = !optional }) {
                        InventoryFields(draft, !state.busy, update)
                        PaperDisclosure("品牌、规格、厂家等药品资料", identity, { identity = !identity }) {
                            MedicineInformationFields(draft.information, MedicineInformationGroup.IDENTITY, !state.busy, exclude = setOf(MedicineInformationField.STORAGE_LOCATION)) { field, value -> update { copy(information = information.withValue(field, value)) } }
                        }
                        PaperDisclosure("标签", tags, { tags = !tags }, subtitle = if (draft.tags.isEmpty()) "可选" else draft.tags.joinToString(" · ")) { MedicineTagEditor(draft, state.availableTags, !state.busy, update) }
                        PaperDisclosure("说明书记录", leaflet, { leaflet = !leaflet }) { DraftLeaflet(draft, !state.busy, update) }
                        PaperDisclosure("备注", notes, { notes = !notes }) { DraftNotes(draft, !state.busy, update) }
                    }
                }
                if (editing && tab == 1) DraftLeaflet(draft, !state.busy, update)
                if (draft.rawText.isNotBlank() && (!editing || tab == 2 && !draft.reviewed)) OcrReviewConfirmation(draft, !state.busy && !state.scanning, update)
                Text(if (editing) "三个分页的改动一起保存；返回可放弃本次编辑。" else "同名但有效期不同的药品，请分开添加为库存记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InventoryFields(draft: MedicineDraft, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    QuantityFields(draft.quantity, draft.unit, enabled, { value -> update { copy(quantity = value) } }, { value -> update { copy(unit = value) } })
    Text("留空代表未知，0 代表已用完；不同单位不相加。", style = MaterialTheme.typography.bodySmall)
    MedicineInformationInput(MedicineInformationField.STORAGE_LOCATION, draft.information.storageLocation, enabled) { value -> update { copy(information = information.withValue(MedicineInformationField.STORAGE_LOCATION, value)) } }
}

@Composable
private fun DraftLeaflet(draft: MedicineDraft, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    Text("说明书记录", style = MaterialTheme.typography.titleMedium)
    LeafletDisclaimer()
    PaperField(draft.efficacy, { value -> update { copy(efficacy = value) } }, label = "适应症 / 功能主治（原文）", singleLine = false, minLines = 2, maxLines = 5, enabled = enabled, modifier = Modifier.fillMaxWidth())
    MedicineInformationFields(draft.information, MedicineInformationGroup.LEAFLET, enabled) { field, value -> update { copy(information = information.withValue(field, value)) } }
}

@Composable
private fun DraftNotes(draft: MedicineDraft, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    PaperField(draft.notes, { value -> update { copy(notes = value) } }, label = "备注", singleLine = false, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun OcrReviewConfirmation(draft: MedicineDraft, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("③ 保存前确认", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.reviewed, onCheckedChange = { value -> update { copy(reviewed = value) } }, enabled = enabled)
                Text("我已对照照片核对名称、有效期及已采用的资料", style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (draft.reviewed) "已确认，可点击底部保存按钮" else "请勾选上方确认框，再点击底部保存按钮", style = MaterialTheme.typography.bodySmall)
        }
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
