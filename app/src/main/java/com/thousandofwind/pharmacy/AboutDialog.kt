package com.thousandofwind.pharmacy

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

@Composable
fun AboutDialog(viewModel: AppUpdateViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    fun install() {
        val apk = viewModel.state.value.readyApk
        if (apk == null || !apk.isFile) {
            viewModel.missingApk()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("APK", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { viewModel.report("无法打开系统安装器。现有应用和药箱数据未更改。") }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else viewModel.report("未允许安装更新。可再次点击“安装更新”进入系统设置，药箱仍可正常使用。")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("我的药箱", style = MaterialTheme.typography.titleLarge)
                Text("当前版本 ${viewModel.versionName}（${viewModel.versionCode}）")
                Text("记录家中药品、有效期、余量与位置。药箱数据保存在本地，日常管理可离线使用。")
                Text("OCR 帮助录入，仍需核对；AI 助手需自行配置并主动使用。不提供诊断或用药建议。",
                    style = MaterialTheme.typography.bodySmall)
                Text("源码可见，仅限非商业使用。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$UPDATE_REPOSITORY")))
                    }.onFailure { viewModel.report("无法打开项目主页，请使用浏览器访问 GitHub / $UPDATE_REPOSITORY。") }
                }) { Text("项目主页") }
                InkDivider()
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text(state.message)
                if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${state.progress}%")
                }
                state.update?.let { update ->
                    Text("新版本 ${update.versionName} · ${update.size / (1024 * 1024)} MB")
                    if (update.notes.isNotBlank()) Text(update.notes, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    // Use the checked tag when available; otherwise let the browser resolve latest.
                    val page = state.update?.let { "https://github.com/$UPDATE_REPOSITORY/releases/tag/${it.tag}" }
                        ?: LATEST_RELEASE_PAGE
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
                    }.onFailure { viewModel.report("无法打开浏览器。请手动访问 $LATEST_RELEASE_PAGE。") }
                }) { Text("浏览器下载 / 发布说明") }
                Text("浏览器下载不经过应用内校验，请只选官方 app-debug.apk，并由系统确认安装。不要卸载旧版或清除药箱。",
                    style = MaterialTheme.typography.bodySmall)
                Text("更新来源：GitHub / $UPDATE_REPOSITORY\n仅手动更新；首次安装可能需要允许本应用安装未知应用。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (state.readyApk != null) TextButton(onClick = {
                if (context.packageManager.canRequestPackageInstalls()) install()
                else runCatching {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                }.onFailure { viewModel.report("无法打开安装授权设置，请在系统设置中允许本应用安装未知应用。") }
            }) { Text("安装更新") }
            else if (state.update != null) TextButton(onClick = viewModel::download, enabled = !state.downloading) { Text("下载更新") }
            else TextButton(onClick = viewModel::check, enabled = !state.checking) { Text("检查更新") }
        },
        dismissButton = {
            Row {
                if (state.update != null) TextButton(onClick = viewModel::check,
                    enabled = !state.checking && !state.downloading) { Text("重新检查") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}
