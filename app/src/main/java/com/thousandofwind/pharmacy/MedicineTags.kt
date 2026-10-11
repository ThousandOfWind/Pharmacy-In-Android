package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicineTags(tags: List<String>, limit: Int = MAX_MEDICINE_TAGS) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val labels = tags.take(limit) + if (tags.size > limit) listOf("＋${tags.size - limit}") else emptyList()
        labels.forEach { tag ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(tag, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicineTagEditor(draft: MedicineDraft, availableTags: List<String>, enabled: Boolean, update: (MedicineDraft.() -> MedicineDraft) -> Unit) {
    val inputError = medicineTagsError(draft.tags + draft.tagInput)
    fun addInput() {
        if (enabled && draft.tagInput.isNotBlank() && inputError == null) {
            update { copy(tags = normalizeMedicineTags(tags + tagInput), tagInput = "") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("标签（可选，${draft.tags.size}/$MAX_MEDICINE_TAGS）", style = MaterialTheme.typography.titleSmall)
        Text("用于整理和查找，不作为用药建议；用途、使用方式与保存条件请核对包装。", style = MaterialTheme.typography.bodySmall)
        if (draft.tags.isNotEmpty()) {
            Text("已选 · 点击移除", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.tags.forEach { tag ->
                    InputChip(selected = true, onClick = { update { copy(tags = tags - tag) } }, enabled = enabled,
                        label = { Text("$tag ×") })
                }
            }
        }
        val commonTags = commonMedicineTagGroups.flatMap { it.tags }
        val customTags = availableTags.filter { tag -> commonTags.none { it.equals(tag, ignoreCase = true) } }
        val groups = commonMedicineTagGroups + if (customTags.isNotEmpty()) listOf(MedicineTagGroup("已有自定义标签", customTags)) else emptyList()
        groups.forEach { group ->
            Text(group.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.tags.forEach { tag ->
                    val selected = draft.tags.any { it.equals(tag, ignoreCase = true) }
                    FilterChip(selected = selected, onClick = {
                        update { copy(tags = if (selected) tags.filterNot { it.equals(tag, ignoreCase = true) } else normalizeMedicineTags(tags + tag)) }
                    }, enabled = enabled && (selected || draft.tags.size < MAX_MEDICINE_TAGS), label = { Text(tag) })
                }
            }
        }
        OutlinedTextField(
            value = draft.tagInput, onValueChange = { value -> update { copy(tagInput = value) } },
            label = { Text("自定义标签") }, singleLine = true, enabled = enabled, isError = inputError != null,
            supportingText = { Text(inputError ?: "每个标签最多 $MAX_TAG_LENGTH 个字符；保存时也会加入尚未添加的标签。") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addInput() }),
            trailingIcon = { TextButton(onClick = { addInput() }, enabled = enabled && draft.tagInput.isNotBlank() && inputError == null) { Text("添加") } },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
