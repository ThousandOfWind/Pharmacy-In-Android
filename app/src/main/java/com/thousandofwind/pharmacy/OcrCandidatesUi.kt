package com.thousandofwind.pharmacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OcrCandidatesPanel(draft: MedicineDraft, busy: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    val c = draft.candidates
    var leaflet by rememberSaveable(draft.id) { mutableStateOf(false) }
    var raw by rememberSaveable(draft.id) { mutableStateOf(false) }
    var assign by rememberSaveable(draft.id) { mutableStateOf(false) }
    if (assign) OcrTextAssignmentDialog(draft, busy, { assign = false }, update)
    Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("① 核对识别结果", style = MaterialTheme.typography.titleMedium)
            Text("下面是待核对文字，不是按钮。请对照照片；识别结果不会自动写入资料栏。", style = MaterialTheme.typography.bodySmall)
            if (c.name.isNotBlank()) {
                CandidateText("药品名称", c.name)
                CandidateNameButton(draft, c.name, busy, update)
            }
            if (c.expiry.isNotBlank()) CandidateText("有效期至", c.expiry)
            if (c.nameOptions.isNotEmpty()) {
                Text("药名候选 · 点击按钮选择", style = MaterialTheme.typography.labelLarge)
                c.nameOptions.forEachIndexed { index, name ->
                    CandidateText("药名候选 ${index + 1}", name)
                    CandidateNameButton(draft, name, busy, update)
                }
            }
            if (c.name.isBlank() && c.nameOptions.isEmpty()) Text("药名未提取到，可补拍正面或手动填写。", style = MaterialTheme.typography.bodySmall)
            if (c.expiry.isBlank()) Text("尚无可采用的到期日期。如原文中已有日期，可复制并指定归入有效期；生产日期和有效期时长不会代填。", style = MaterialTheme.typography.bodySmall)
            // Key identity results are visible without another disclosure; users can see what will be adopted.
            MedicineInformationField.entries.filter { it.group == MedicineInformationGroup.IDENTITY && c.information[it].isNotBlank() }.forEach { field ->
                CandidateText(field.label, c.information[field])
            }
            c.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = PaperPalette.Error) }
            HorizontalDivider()
            Text("② 点击按钮填入资料栏", style = MaterialTheme.typography.titleMedium)
            Text("只填空白的名称、日期及品牌/厂家/规格等资料；已有填写保留。", style = MaterialTheme.typography.bodySmall)
            val canFill = fillEmptyOcrFields(draft) != draft
            Button(onClick = { update { fillEmptyOcrFields(this) } }, Modifier.fillMaxWidth(), enabled = !busy && canFill) {
                InkIcon(InkSymbol.CHECK, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text(if (canFill) "核对后采用空白资料" else "暂无可填入的空白资料")
            }
            if (!canFill && (c.name.isNotBlank() || c.expiry.isNotBlank() || c.information != MedicineInformation()))
                Text("已采用的资料可在下方栏位查看、修改；已有内容不会被覆盖。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { assign = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("粘贴原文 → 指定资料栏") }
            if (c.tagSuggestions.isNotEmpty()) {
                Text("自动识别的常用标签 · 对照依据后点击采用", style = MaterialTheme.typography.labelLarge)
                Text("仅用于整理，不作为用药建议；不会根据药名、禁忌推断，也不自动添加家庭常备/出行备用。", style = MaterialTheme.typography.bodySmall)
                c.tagSuggestions.forEach { suggestion ->
                    CandidateText("标签依据 · ${suggestion.tag}", suggestion.evidence)
                    val selected = draft.tags.any { it.equals(suggestion.tag, true) }
                    OutlinedButton(onClick = { update { toggleOcrTag(this, suggestion.tag) } },
                        enabled = !busy && toggleOcrTag(draft, suggestion.tag) != draft, modifier = Modifier.fillMaxWidth()) {
                        Text(if (draft.ocrAddedTags.any { it.equals(suggestion.tag, true) }) "取消本次标签：${suggestion.tag}"
                            else if (selected) "已有标签 · 不改动：${suggestion.tag}" else "采用标签：${suggestion.tag}")
                    }
                }
                if (medicineTagsError(draft.tags + draft.tagInput) != null || draft.tags.size >= MAX_MEDICINE_TAGS)
                    Text("标签已达上限或自定义输入待修正，可在下方标签栏调整。", style = MaterialTheme.typography.bodySmall)
            }
            val leafletCount = MedicineInformationField.entries.count { it.group == MedicineInformationGroup.LEAFLET && c.information[it].isNotBlank() } + if (c.efficacy.isNotBlank()) 1 else 0
            if (leafletCount > 0) {
                Text("说明书 $leafletCount 项：剂量、禁忌等安全条目需逐项对照同厂家、同规格照片，不批量写入。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { leaflet = !leaflet }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (leaflet) "收起说明书候选" else "展开并逐项核对说明书候选")
                    Spacer(Modifier.width(8.dp)); InkIcon(InkSymbol.DOWN)
                }
                if (leaflet) {
                    if (c.efficacy.isNotBlank()) CandidateValue("适应症 / 功能主治", c.efficacy, !busy && draft.efficacy.isBlank(), draft.efficacy.isNotBlank()) {
                        update { if (efficacy.isBlank()) copy(efficacy = candidates.efficacy, reviewed = false) else this }
                    }
                    MedicineInformationField.entries.filter { it.group == MedicineInformationGroup.LEAFLET && c.information[it].isNotBlank() }.forEach { field ->
                        CandidateValue(field.label, c.information[field], !busy && draft.information[field].isBlank(), draft.information[field].isNotBlank()) {
                            update { if (information[field].isBlank()) copy(information = information.withValue(field, candidates.information[field]), reviewed = false) else this }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { raw = !raw }, modifier = Modifier.fillMaxWidth()) { Text(if (raw) "收起识别原文" else "查看 / 复制识别原文") }
            if (raw) {
                Text("长按文字选择，可拖动选择范围并复制。复制后点击上面的“粘贴原文 → 指定资料栏”。历史原文保留，可能包含已删除照片或重识别前的结果。", style = MaterialTheme.typography.bodySmall)
                SelectableOcrText(draft.rawText)
            }
            Text("③ 前往基本资料检查，勾选底部确认框，再保存", style = MaterialTheme.typography.labelLarge, color = PaperPalette.Green)
        }
    }
}

@Composable
private fun CandidateNameButton(draft: MedicineDraft, name: String, busy: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    OutlinedButton(onClick = { update { toggleOcrName(this, name) } },
        enabled = toggleOcrName(draft, name) != draft && !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (draft.ocrSelectedName == name && draft.name == name) "已选此药名 · 点击取消"
            else if (draft.name.isNotBlank()) "名称已有填写 · 不覆盖" else "选择此药名")
    }
}

@Composable
private fun CandidateText(label: String, value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun CandidateValue(label: String, value: String, enabled: Boolean, filled: Boolean, apply: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CandidateText(label, value)
        OutlinedButton(onClick = apply, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(if (filled) "该栏已有填写 · 不覆盖" else "核对后采用这项")
        }
    }
}
