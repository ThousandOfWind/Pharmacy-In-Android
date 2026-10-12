package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A compact companion to the actual input, not a second form/candidate card. */
@Composable
fun OcrFieldSuggestion(label: String, value: String, current: String, enabled: Boolean, adopt: () -> Unit) {
    if (value.isBlank()) return
    if (value == current) {
        Text("识别与填写一致 · $label", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("识别建议 · $label", style = MaterialTheme.typography.labelMedium, color = PaperPalette.Green)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        if (current.isBlank()) FilledTonalButton(onClick = adopt, enabled = enabled) { Text("采用${label}") }
        else Text("已有填写保留。若需更换，可复制建议后编辑上方栏位。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
    }
}

@Composable
fun OcrNameSuggestions(draft: MedicineDraft, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    val names = (listOf(draft.candidates.name) + draft.candidates.nameOptions + draft.ocrSelectedName)
        .filter(String::isNotBlank).distinct()
    names.forEach { name ->
        val selected = draft.name == name && draft.ocrSelectedName == name
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (name != draft.name) {
                Text("识别药名建议", style = MaterialTheme.typography.labelMedium, color = PaperPalette.Green)
                SelectionContainer { Text(name, style = MaterialTheme.typography.bodyMedium) }
            }
            if (selected || draft.name.isBlank()) FilledTonalButton(onClick = { update { toggleOcrName(this, name) } }, enabled = enabled) {
                Text(if (selected) "取消本次药名采用" else "采用此药名")
            }
            else Text(if (name == draft.name) "识别与填写一致 · 药名" else "已有药名保留，可手工编辑。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
        }
    }
}

@Composable
fun OcrTagSuggestions(draft: MedicineDraft, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    if (draft.candidates.tagSuggestions.isEmpty()) return
    Text("识别标签建议 · 请核对依据", style = MaterialTheme.typography.labelMedium, color = PaperPalette.Green)
    draft.candidates.tagSuggestions.forEach { suggestion ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val added = draft.ocrAddedTags.any { it.equals(suggestion.tag, true) }
            val selected = draft.tags.any { it.equals(suggestion.tag, true) }
            OutlinedButton(onClick = { update { toggleOcrTag(this, suggestion.tag) } }, enabled = enabled && toggleOcrTag(draft, suggestion.tag) != draft) {
                Text(if (added) "取消 ${suggestion.tag}" else if (selected) "已有 ${suggestion.tag}" else "采用 ${suggestion.tag}")
            }
            SelectionContainer(Modifier.weight(1f)) { Text(suggestion.evidence, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
