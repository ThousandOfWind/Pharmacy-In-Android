package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDialog(state: BackupUiState, dismiss: () -> Unit, export: () -> Unit, choose: () -> Unit, restore: () -> Unit, cancel: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !state.busy })
    ModalBottomSheet(onDismissRequest = { if (!state.busy) dismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
            Text("药品资料、标签、备注、识别原文与照片一起保存。备份由你选择位置，恢复只添加缺少的记录，不覆盖原药箱。", color = PaperPalette.Muted)
            Text("备份文件未加密，可能包含个人健康信息。请妥善保存，不要公开上传。选择云盘时，文件会由该服务商处理。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Error)
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请勿关闭…") }
            state.message?.let { Text(it) }
            val preview = state.preview
            if (preview != null) {
                val date = runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(preview.createdAt)) }.getOrDefault("日期未知")
                Text("备份已校验", style = MaterialTheme.typography.titleMedium)
                Text("备份时间：$date\n${preview.records} 条记录 · ${preview.photos} 张照片\n预计新增 ${preview.newRecords} 条，跳过 ${preview.duplicates} 条相同记录。")
                Text("资料或余量不同的记录会作为独立库存保留，不按药名覆盖。确认时会重新核对重复项。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                PenButton("确认合并恢复", restore, Modifier.fillMaxWidth(), enabled = !state.busy && preview.newRecords > 0)
                TextButton(onClick = cancel, enabled = !state.busy) { Text("取消本次恢复") }
            } else {
                PenButton("保存完整备份", export, Modifier.fillMaxWidth(), enabled = !state.busy)
                TextButton(onClick = choose, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).paperBorder()) { Text("选择备份文件恢复") }
            }
            TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        }
    }
}
