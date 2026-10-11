package com.thousandofwind.pharmacy

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: UiState, update: (MedicineDraft.() -> MedicineDraft) -> Unit,
    camera: () -> Unit, picker: () -> Unit
) {
    val draft = state.session.draft
    var identity by rememberSaveable(draft.id) { mutableStateOf(false) }
    var leaflet by rememberSaveable(draft.id) { mutableStateOf(false) }
    var notes by rememberSaveable(draft.id) { mutableStateOf(false) }
    var optional by rememberSaveable(draft.id) { mutableStateOf(false) }
    var tags by rememberSaveable(draft.id) { mutableStateOf(false) }
    LaunchedEffect(state.session.error) {
        if (state.session.error != null && quantityError(draft.quantity, draft.unit) != null) optional = true
        if (state.session.error != null && medicineTagsError(draft.tags + draft.tagInput) != null) { optional = true; tags = true }
        if (state.session.error != null && medicineInformationError(draft.information) != null) {
            optional = true
            if (MedicineInformationField.entries.any { it.group == MedicineInformationGroup.IDENTITY && it != MedicineInformationField.STORAGE_LOCATION && medicineInformationFieldError(it, draft.information[it]) != null }) identity = true
            if (MedicineInformationField.entries.any { it.group == MedicineInformationGroup.LEAFLET && medicineInformationFieldError(it, draft.information[it]) != null }) leaflet = true
        }
    }
    var raw by rememberSaveable(draft.id) { mutableStateOf(false) }
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
                        update { copy(expiry = value, reviewed = false) }
                    }
                    datePicker = false
                }, enabled = pickerState.selectedDateMillis != null) { Text("确认日期") }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState, title = { Text("选择包装上的有效期至", Modifier.padding(20.dp)) }) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("先记下名字，其他慢慢补充。", style = PaperHeading, color = PaperPalette.Muted)
        if (draft.photos.isNotEmpty()) PhotoStrip(draft.photos)
        if (state.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在本地识别，已有填写不会被覆盖…", style = MaterialTheme.typography.bodySmall)
        }
        if (draft.rawText.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("识别候选 · 待你核对", style = MaterialTheme.typography.titleSmall)
                    Text("只提取带明确标签的信息，不猜药名或日期。采用候选不会覆盖已有填写。", style = MaterialTheme.typography.bodySmall)
                    CandidateRow("药名", draft.candidates.name, draft.name.isBlank() && !state.busy) {
                        update { copy(name = candidates.name, reviewed = false) }
                    }
                    CandidateRow("有效期至", draft.candidates.expiry, draft.expiry.isBlank() && !state.busy) {
                        update { copy(expiry = candidates.expiry, reviewed = false) }
                    }
                    CandidateRow("包装功效", draft.candidates.efficacy, draft.efficacy.isBlank() && !state.busy) {
                        update { copy(efficacy = candidates.efficacy) }; optional = true; leaflet = true
                    }
                    TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起识别原文" else "查看识别原文") }
                    if (raw) Text(draft.rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        PaperField(
            value = draft.name, onValueChange = { value -> update { copy(name = value, reviewed = false) } },
            label = "药品名称（必填）", enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            isError = state.session.error != null && draft.name.isBlank()
        )
        PaperField(
            value = draft.expiry, onValueChange = { value -> update { copy(expiry = value, reviewed = false) } },
            label = "有效期至（可选）", enabled = !state.busy,
            isError = draft.expiry.isNotBlank() && expiryDay(draft.expiry) == null,
            trailingIcon = { TextButton(onClick = { datePicker = true }, enabled = !state.busy) { Text("选日期") } },
            supportingText = { Text("填 YYYY-MM 或 YYYY-MM-DD。只有月份按月末计算；生产日期不是有效期。无法确认可留空。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = camera, enabled = !state.scanning && !state.busy, modifier = Modifier.weight(1f)) {
                InkIcon(InkSymbol.CAMERA); Spacer(Modifier.width(8.dp)); Text(if (draft.photos.isEmpty()) "拍药盒" else "补拍包装 / 说明书")
            }
            TextButton(onClick = picker, enabled = !state.scanning && !state.busy) { Text("从相册选择") }
        }
        Text("有效期通常在盒底或侧面；点击照片可放大核对。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
        PaperDisclosure("余量、位置与更多资料", optional, { optional = !optional }) {
            QuantityFields(draft.quantity, draft.unit, !state.busy,
                { value -> update { copy(quantity = value) } }, { value -> update { copy(unit = value) } })
            Text("不填写代表未知，0 代表已用完。不同单位的余量不会相加。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
            MedicineInformationInput(MedicineInformationField.STORAGE_LOCATION, draft.information.storageLocation, !state.busy) { value ->
                update { copy(information = information.withValue(MedicineInformationField.STORAGE_LOCATION, value)) }
            }
            PaperDisclosure("品牌、规格、厂家等药品资料", identity, { identity = !identity }) {
                MedicineInformationFields(draft.information, MedicineInformationGroup.IDENTITY, !state.busy, exclude = setOf(MedicineInformationField.STORAGE_LOCATION)) { field, value ->
                    update { copy(information = information.withValue(field, value)) }
                }
            }
            PaperDisclosure("标签", tags, { tags = !tags }, subtitle = if (draft.tags.isEmpty()) "可选" else draft.tags.joinToString(" · ")) {
                MedicineTagEditor(draft, state.availableTags, !state.busy, update)
            }
            PaperDisclosure("说明书记录", leaflet, { leaflet = !leaflet }) {
                LeafletDisclaimer()
                PaperField(
                    value = draft.efficacy, onValueChange = { value -> update { copy(efficacy = value) } },
                    label = "适应症 / 功能主治（原文）", supportingText = { Text("沿用原包装功效记录，不自动推断疗效。", style = MaterialTheme.typography.bodySmall) },
                    singleLine = false, minLines = 2, maxLines = 5, enabled = !state.busy, modifier = Modifier.fillMaxWidth()
                )
                MedicineInformationFields(draft.information, MedicineInformationGroup.LEAFLET, !state.busy) { field, value ->
                    update { copy(information = information.withValue(field, value)) }
                }
            }
            PaperDisclosure("备注", notes, { notes = !notes }) {
                PaperField(value = draft.notes, onValueChange = { value -> update { copy(notes = value) } },
                    label = "备注", singleLine = false, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            }
        }
        if (draft.rawText.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.reviewed, onCheckedChange = { value -> update { copy(reviewed = value) } }, enabled = !state.busy)
            Text("我已对照包装核对名称和有效期", style = MaterialTheme.typography.bodyMedium)
        }
        Text("同名但有效期不同的药品，请分开添加为库存记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CandidateRow(label: String, value: String, canApply: Boolean, apply: () -> Unit) {
    if (value.isBlank()) {
        Text("$label：未提取到，请手动填写或查看原文", style = MaterialTheme.typography.bodySmall)
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label：$value", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = apply, enabled = canApply) { Text(if (canApply) "采用" else "保留填写") }
    }
}

@Composable
fun QuantityFields(quantity: String, unit: String, enabled: Boolean, onQuantity: (String) -> Unit, onUnit: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperField(
            value = quantity, onValueChange = onQuantity, label = "当前余量（可选）",
            enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (unit.isBlank()) "选择单位" else "$unit ▾")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                quantityUnits.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onUnit(option); menu = false })
                }
            }
        }
    }
}
