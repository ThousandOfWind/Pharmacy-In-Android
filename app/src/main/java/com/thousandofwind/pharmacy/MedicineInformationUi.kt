package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MedicineInformationFields(
    information: MedicineInformation, group: MedicineInformationGroup, enabled: Boolean,
    exclude: Set<MedicineInformationField> = emptySet(), update: (MedicineInformationField, String) -> Unit
) {
    MedicineInformationField.entries.filter { it.group == group && it !in exclude }.forEach { field ->
        val value = information[field]
        val error = medicineInformationFieldError(field, value)
        PaperField(
            value = value, onValueChange = { update(field, it) }, label = { Text(field.label) },
            singleLine = field.singleLine, minLines = if (field.singleLine) 1 else 2,
            maxLines = if (field.singleLine) 1 else 5, enabled = enabled, isError = error != null,
            supportingText = {
                Text(error ?: listOf(field.hint, "可留空，最多 ${field.maxLength} 个字符。").filter(String::isNotBlank).joinToString(" "))
            }, modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun MedicineInformationDetails(information: MedicineInformation, group: MedicineInformationGroup) {
    val fields = MedicineInformationField.entries.filter { it.group == group && information[it].isNotBlank() }
    if (fields.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEach { field ->
                Text(field.label, style = MaterialTheme.typography.titleSmall)
                // Do not truncate safety-related text in details.
                Text(information[field])
            }
        }
    }
}

@Composable
fun LeafletDisclaimer() {
    Text(
        "仅记录当前药品包装或说明书原文，不作为个体用药建议。未填写不代表没有禁忌、不良反应或相互作用；请核对同厂家、同规格的实际说明书，有疑问咨询医生或药师。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
