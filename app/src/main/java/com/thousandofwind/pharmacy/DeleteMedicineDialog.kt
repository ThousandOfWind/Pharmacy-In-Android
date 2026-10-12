package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeleteMedicineDialog(records: List<MedicineEntity>, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(if (records.size == 1) "移出药箱？" else "移出 ${records.size} 条库存记录？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("仅移出以下库存记录，不会移出同组的其他记录。可在提示条消失前撤销；这不是长期回收站。")
                records.take(6).forEach { item ->
                    Column {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        val identity = listOf(item.information.brandName, item.information.specification, item.information.batchNumber)
                            .filter(String::isNotBlank).joinToString(" · ")
                        if (identity.isNotBlank()) Text(identity, style = MaterialTheme.typography.bodySmall)
                        Text("有效期：${item.expiryDate.ifBlank { "未填写" }} · ${remainingLabel(item.remainingQuantity, item.quantityUnit)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (records.size > 6) Text("另有 ${records.size - 6} 条已选记录。取消可返回列表核对。")
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = !busy && records.isNotEmpty()) {
                Text(if (busy) "正在移出…" else "确认移出", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } }
    )
}
