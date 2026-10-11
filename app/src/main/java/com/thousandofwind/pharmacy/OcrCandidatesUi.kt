package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OcrCandidatesPanel(draft: MedicineDraft, busy: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    val c = draft.candidates
    var leaflet by rememberSaveable(draft.id) { mutableStateOf(false) }
    var inspect by rememberSaveable(draft.id) { mutableStateOf(false) }
    var raw by rememberSaveable(draft.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("识别候选 · 待你核对", style = MaterialTheme.typography.titleMedium)
            Text("识别可能有错字或漏字。对照照片后采用，已有填写不会覆盖。", style = MaterialTheme.typography.bodySmall)
            if (c.name.isNotBlank()) Text("药名：${c.name}")
            if (c.expiry.isNotBlank()) Text("有效期至：${c.expiry}")
            c.nameOptions.forEach { name ->
                TextButton(onClick = { update { if (this.name.isBlank()) copy(name = name, reviewed = false) else this } }, enabled = draft.name.isBlank() && !busy) { Text("选择药名：$name") }
            }
            if (c.name.isBlank() && c.nameOptions.isEmpty()) Text("药名未提取到，可补拍正面或手动填写。", style = MaterialTheme.typography.bodySmall)
            val identityCount = MedicineInformationField.entries.count { it.group == MedicineInformationGroup.IDENTITY && c.information[it].isNotBlank() }
            val leafletCount = MedicineInformationField.entries.count { it.group == MedicineInformationGroup.LEAFLET && c.information[it].isNotBlank() } + if (c.efficacy.isNotBlank()) 1 else 0
            Text("资料 $identityCount 项 · 说明书 $leafletCount 项", style = MaterialTheme.typography.bodySmall)
            if (c.expiry.isBlank()) Text("未识别到明确的到期日期；生产日期和有效期时长不会代填。", style = MaterialTheme.typography.bodySmall)
            PenButton("采用空白资料", { update { fillEmptyOcrFields(this) } }, Modifier.fillMaxWidth(), enabled = !busy &&
                (c.name.isNotBlank() || c.expiry.isNotBlank() || identityCount > 0))
            c.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = PaperPalette.Error) }
            if (leafletCount > 0) {
                Text("请确认照片对应当前药品的同厂家、同规格说明书。安全条目只逐项采用，不批量写入。", style = MaterialTheme.typography.bodySmall)
                PaperDisclosure("逐项核对说明书候选", leaflet, { leaflet = !leaflet }) {
                    if (c.efficacy.isNotBlank()) CandidateValue("适应症 / 功能主治", c.efficacy, !busy && draft.efficacy.isBlank()) {
                        update { if (efficacy.isBlank()) copy(efficacy = candidates.efficacy, reviewed = false) else this }
                    }
                    MedicineInformationField.entries.filter { it.group == MedicineInformationGroup.LEAFLET && c.information[it].isNotBlank() }.forEach { field ->
                        CandidateValue(field.label, c.information[field], !busy && draft.information[field].isBlank()) {
                            update { if (information[field].isBlank()) copy(information = information.withValue(field, candidates.information[field]), reviewed = false) else this }
                        }
                    }
                }
            }
            PaperDisclosure("核对资料候选", inspect, { inspect = !inspect }) {
                MedicineInformationField.entries.filter { it.group == MedicineInformationGroup.IDENTITY && c.information[it].isNotBlank() }.forEach { field ->
                    CandidateValue(field.label, c.information[field], !busy && draft.information[field].isBlank()) {
                        update { if (information[field].isBlank()) copy(information = information.withValue(field, candidates.information[field]), reviewed = false) else this }
                    }
                }
            }
            TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起识别原文" else "查看识别原文") }
            if (raw) Text(draft.rawText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CandidateValue(label: String, value: String, enabled: Boolean, apply: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = apply, enabled = enabled) { Text(if (enabled) "核对后采用" else "保留已有填写") }
    }
}
