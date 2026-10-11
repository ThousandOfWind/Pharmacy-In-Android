package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp

/** Explicit user-selected text assignment, not automatic inference. Never overwrite a filled field. */
fun assignOcrText(draft: MedicineDraft, text: String, target: String): MedicineDraft {
    val raw = text.trim()
    if (raw.isBlank()) return draft
    val filled = when (target) {
        OCR_TARGET_NAME -> if (draft.name.isBlank() && raw.length <= 200 && !raw.contains('\n')) draft.copy(name = raw) else draft
        OCR_TARGET_EXPIRY -> if (draft.expiry.isBlank()) {
            val date = regionCandidates(raw, target).expiry
            if (date.isBlank()) draft else draft.copy(expiry = date)
        } else draft
        else -> {
            val field = MedicineInformationField.entries.firstOrNull {
                it.column == target && it.group == MedicineInformationGroup.IDENTITY && it != MedicineInformationField.STORAGE_LOCATION
            } ?: return draft
            if (draft.information[field].isBlank() && medicineInformationFieldError(field, raw) == null)
                draft.copy(information = draft.information.withValue(field, raw)) else draft
        }
    }
    return if (filled == draft) draft else filled.copy(reviewed = false)
}

@Composable
fun OcrTextAssignmentDialog(draft: MedicineDraft, busy: Boolean, dismiss: () -> Unit, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf(MedicineInformationField.BRAND_NAME.column) }
    var menu by remember { mutableStateOf(false) }
    val choices = listOf(OCR_TARGET_NAME to "药品名称", OCR_TARGET_EXPIRY to "有效期至") +
        MedicineInformationField.entries.filter { it.group == MedicineInformationGroup.IDENTITY && it != MedicineInformationField.STORAGE_LOCATION }
            .map { it.column to it.label }
    val canApply = assignOcrText(draft, text, target) != draft
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("原文归入资料栏") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("先在识别原文中长按选择并复制所需内容，再粘贴到这里。只填空白栏位；请对照照片确认。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { text = clipboard.getText()?.text.orEmpty() }, enabled = !busy) { Text("粘贴已复制文字") }
                PaperField(text, { text = it }, label = "所选原文（可编辑）", singleLine = false,
                    minLines = 2, maxLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = !busy) { Text("归入：${choices.first { it.first == target }.second}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { target = id; menu = false }) }
                    }
                }
                if (text.isNotBlank() && !canApply) Text("目标栏已填写，或内容不符合格式。日期需明确，单行资料不能含换行；可编辑后再采用。", color = PaperPalette.Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { update { assignOcrText(this, text, target) }; dismiss() }, enabled = !busy && canApply) { Text("核对后填入空白栏") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

@Composable
fun SelectableOcrText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer { Text(text, modifier, style = MaterialTheme.typography.bodySmall) }
}
