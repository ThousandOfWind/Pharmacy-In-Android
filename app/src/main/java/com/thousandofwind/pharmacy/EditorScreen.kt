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
    var more by rememberSaveable(draft.id) { mutableStateOf(false) }
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
        Text("先确认是哪种药，再核对有效期。余量和其他信息可以稍后补充。", style = MaterialTheme.typography.bodyMedium)
        if (draft.photos.isNotEmpty()) PhotoStrip(draft.photos)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = camera, enabled = !state.scanning && !state.busy) { Text(if (draft.photos.isEmpty()) "拍药盒" else "补拍有效期") }
            TextButton(onClick = picker, enabled = !state.scanning && !state.busy) { Text("从相册选择") }
        }
        Text("有效期通常在盒底或侧面；点击照片可放大核对。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        update { copy(efficacy = candidates.efficacy) }; more = true
                    }
                    TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起识别原文" else "查看识别原文") }
                    if (raw) Text(draft.rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(
            value = draft.name, onValueChange = { value -> update { copy(name = value, reviewed = false) } },
            label = { Text("药品名称（必填）") }, singleLine = true, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.expiry, onValueChange = { value -> update { copy(expiry = value, reviewed = false) } },
            label = { Text("有效期至（可选）") }, singleLine = true, enabled = !state.busy,
            trailingIcon = { TextButton(onClick = { datePicker = true }, enabled = !state.busy) { Text("选日期") } },
            supportingText = { Text("填 YYYY-MM 或 YYYY-MM-DD。只有月份按月末计算；生产日期不是有效期。无法确认可留空。") },
            modifier = Modifier.fillMaxWidth()
        )
        QuantityFields(draft.quantity, draft.unit, !state.busy,
            { value -> update { copy(quantity = value) } }, { value -> update { copy(unit = value) } })
        Text("不填写代表未知，0 代表已用完。不同单位的余量不会相加。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MedicineTagEditor(draft, state.availableTags, !state.busy, update)
        TextButton(onClick = { more = !more }) { Text(if (more) "收起更多信息" else "＋ 包装功效、备注（可选）") }
        if (more) {
            OutlinedTextField(
                value = draft.efficacy, onValueChange = { value -> update { copy(efficacy = value) } },
                label = { Text("包装上的功效文字") }, supportingText = { Text("仅作包装信息记录，不作为用药建议。") },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(value = draft.notes, onValueChange = { value -> update { copy(notes = value) } },
                label = { Text("备注，例如存放位置") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
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
        OutlinedTextField(
            value = quantity, onValueChange = onQuantity, label = { Text("当前余量（可选）") },
            singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
