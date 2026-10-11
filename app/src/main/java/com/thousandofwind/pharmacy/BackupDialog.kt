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
fun BackupDialog(
    state: BackupUiState, records: Int, photoReferences: Int, dismiss: () -> Unit, export: () -> Unit,
    choose: () -> Unit, restore: () -> Unit, cancel: () -> Unit, viewCabinet: () -> Unit
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !state.busy })
    ModalBottomSheet(onDismissRequest = { if (!state.busy) dismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("备份与换机", style = MaterialTheme.typography.titleLarge)
            Text("旧手机导出 → 自行传递文件 → 新手机导入。无需账号，不依赖我们维护的服务器。", color = PaperPalette.Muted)
            Text("ZIP 备份未加密，可能包含个人健康信息。不要公开上传；选择云盘或分享服务时，文件会由该服务处理。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Error)
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请勿关闭…") }
            state.message?.let { Text(it, color = PaperPalette.Error) }
            val preview = state.preview
            val exported = state.exported
            val restored = state.restored
            when {
                exported != null -> {
                    Text(if (exported.verified) "备份已保存并校验" else "已写入，尚未确认可读取", style = MaterialTheme.typography.titleMedium)
                    Text(exported.fileName)
                    Text("${exported.records} 条记录 · ${exported.photos} 张照片（去重后） · ${backupSizeLabel(exported.bytes)}")
                    if (!exported.verified) Text("所选文件服务未能完成回读校验。请重新选择本地位置保存，或在新手机成功导入后再确认备份可用。", color = PaperPalette.Error)
                    Text("下一步：在系统文件管理器中找到这个文件，通过附近分享、USB／电脑或你选择的云盘传到新手机，再打开「备份与换机」导入。")
                    Text("新手机核对药品、余量、有效期和照片前，不要卸载旧应用或清除数据。", color = PaperPalette.Error)
                    Text("回读校验仅确认当前可读取的文件内容，不代表云盘已完成上传。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                    TextButton(onClick = cancel, modifier = Modifier.fillMaxWidth()) { Text("返回备份与换机") }
                }
                restored != null -> {
                    Text("导入完成，请核对药箱", style = MaterialTheme.typography.titleMedium)
                    Text("新增 ${restored.added} 条记录 · 跳过 ${restored.duplicates} 条相同记录\n为新增记录导入 ${restored.photos} 张照片（去重后）；现有记录已保留。")
                    Text("请打开药品详情，抽查余量、单位、有效期、药品组和照片是否完整。核对成功后再处理旧手机；建议保留一份备份。")
                    PenButton("查看药箱并核对", viewCabinet, Modifier.fillMaxWidth())
                    TextButton(onClick = cancel, modifier = Modifier.fillMaxWidth()) { Text("返回备份与换机") }
                }
                preview != null -> {
                    val date = runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(preview.createdAt)) }.getOrDefault("日期未知")
                    Text("备份已校验，等待确认", style = MaterialTheme.typography.titleMedium)
                    Text("备份时间：$date\n${preview.records} 条记录 · ${preview.groups} 个药品组 · ${preview.photos} 张照片（去重后）\n本机已有 ${preview.existingRecords} 条记录\n预计新增 ${preview.newRecords} 条，跳过 ${preview.duplicates} 条相同记录。")
                    Text("只添加缺少的记录，不覆盖或删除本机药品。同名但资料、余量、日期、时间或照片不同的记录会单独保留；这不是同步或历史回滚。确认时会重新核对重复项。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                    if (preview.newRecords == 0) {
                        Text(if (preview.records == 0) "这是空备份，没有药品可导入。" else "所有记录已存在，无需再次导入。")
                        if (preview.records > 0) TextButton(onClick = viewCabinet, enabled = !state.busy) { Text("查看药箱并核对") }
                    }
                    PenButton("确认合并导入", restore, Modifier.fillMaxWidth(), enabled = !state.busy && preview.newRecords > 0)
                    TextButton(onClick = cancel, enabled = !state.busy) { Text("取消本次导入") }
                }
                else -> {
                    Text("旧手机：导出药箱", style = MaterialTheme.typography.titleMedium)
                    Text("本机已保存 $records 条记录 · $photoReferences 个照片引用（导出时去重）。完整备份包含药品资料、说明书、标签、备注、药品组、识别原文和照片，不包含未保存草稿、AI 会话或 API Key。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                    PenButton("导出完整药箱", export, Modifier.fillMaxWidth(), enabled = !state.busy && records > 0)
                    if (records == 0) Text("药箱为空，可从旧手机导入。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                    InkDivider()
                    Text("新手机：导入药箱", style = MaterialTheme.typography.titleMedium)
                    Text("选择从旧手机传来的 ZIP 文件，校验并预览后再确认。已有药箱也可以导入，原记录保留。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                    TextButton(onClick = choose, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).paperBorder()) { Text("选择备份文件导入") }
                    Text("请保留不同日期的备份。新手机核对成功前，不要卸载旧应用或清除数据。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Error)
                }
            }
            TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        }
    }
}

private fun backupSizeLabel(bytes: Long): String = if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KiB" else "${(bytes + 1024 * 1024 - 1) / (1024 * 1024)} MiB"
