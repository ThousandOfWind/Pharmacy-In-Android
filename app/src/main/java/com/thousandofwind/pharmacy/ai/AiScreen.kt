package com.thousandofwind.pharmacy.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.thousandofwind.pharmacy.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(viewModel: AiViewModel, dismiss: () -> Unit, openMedicine: (Long) -> Unit) {
    val state by viewModel.ui.collectAsState()
    var settings by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf<AiSessionEntity?>(null) }
    var rename by remember { mutableStateOf<AiSessionEntity?>(null) }
    var title by remember { mutableStateOf("") }
    fun back() { if (state.selected != null) viewModel.select(null) else { viewModel.stop(); dismiss() } }
    Dialog(onDismissRequest = { viewModel.stop(); dismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn
    )) {
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
            TopAppBar(title = { Text("药箱 AI 助手", style = PaperHeading) },
                navigationIcon = { TextButton(onClick = { if (state.busy) viewModel.stop() else back() }) { Text(if (state.busy) "停止" else "返回") } },
                actions = { TextButton(onClick = { settings = true }, enabled = !state.busy) { Text("连接设置") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Azure OpenAI · ${state.config.deployment.ifBlank { "未配置" }}", style = MaterialTheme.typography.labelLarge)
                Text("AI 仅辅助寻找库存和解释资料，不提供诊断或用药建议。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                if (state.error != null) Row {
                    Text(state.error!!, Modifier.weight(1f), color = PaperPalette.Error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::dismissError) { Text("关闭提示") }
                }
                if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                if (state.selected == null) {
                    PenButton("新建对话", viewModel::newSession, Modifier.fillMaxWidth(), enabled = !state.busy && state.hasKey)
                    if (!state.hasKey) TextButton(onClick = { settings = true }) { Text("配置 Azure 连接后开始") }
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.sessions, key = { it.id }) { session ->
                            Column {
                                TextButton(onClick = { viewModel.select(session) }, enabled = !state.busy) { Text(session.title) }
                                Row {
                                    TextButton(onClick = { rename = session; title = session.title }, enabled = !state.busy) { Text("重命名") }
                                    TextButton(onClick = { delete = session }, enabled = !state.busy) { Text("删除") }
                                }
                                InkDivider()
                            }
                        }
                    }
                } else {
                    val list = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(state.turns.size, state.partial) {
                        if (state.turns.isNotEmpty() || state.partial.isNotEmpty()) list.animateScrollToItem(state.turns.size)
                    }
                    LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(state.turns) { turn ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("你：${turn.question}", style = MaterialTheme.typography.titleSmall)
                                Text("AI：${turn.answer}")
                                turn.references.forEach { reference ->
                                    TextButton(onClick = { openMedicine(reference.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().paperBorder()) {
                                        Column(Modifier.weight(1f)) { Text(reference.name); Text(reference.summary, style = MaterialTheme.typography.bodySmall) }
                                        Text("查看记录")
                                    }
                                }
                                InkDivider()
                            }
                        }
                        item {
                            if (state.busy || state.partial.isNotBlank()) { Text(state.input); if (state.partial.isNotBlank()) Text(state.partial) }
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    Row {
                        Checkbox(state.allowCabinet, viewModel::allowCabinet, enabled = !state.busy)
                        Text("本次允许查询库存基本资料（只读；不含备注、照片或说明书）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!state.acknowledged) Consent(state.config, state.acknowledged, viewModel::acknowledge, !state.busy)
                    PaperField(state.input, viewModel::input, label = "输入问题", singleLine = false, maxLines = 4, enabled = !state.busy,
                        supportingText = { Text("${state.input.length}/$MAX_QUESTION", style = MaterialTheme.typography.labelSmall) })
                    PenButton(if (state.busy) "停止生成" else "发送到 Azure", if (state.busy) viewModel::stop else viewModel::send,
                        Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = state.busy || (state.hasKey && state.input.isNotBlank() && state.acknowledged))
                }
            }
        }
        if (settings) ConnectionDialog(state, viewModel, { settings = false })
        delete?.let { session -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除这段对话？") },
            text = { Text("仅删除本地会话，不能撤回已经发送到 Azure 的内容。") },
            confirmButton = { TextButton(onClick = { viewModel.delete(session); delete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
        rename?.let { session -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名对话") },
            text = { PaperField(title, { title = it.take(60) }, label = "对话名称") },
            confirmButton = { TextButton(onClick = { viewModel.rename(session, title); rename = null }, enabled = title.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
    }
}

@Composable
private fun Consent(config: AzureConnection, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(checked, change, enabled = enabled)
        Text("同意将问题、最近对话和获准的查询结果发送到 ${config.endpoint.ifBlank { "配置的 Azure 资源" }}。Azure 可能按量计费，数据处理遵守该资源政策；关闭或删除对话不能撤回已发送数据。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDialog(state: AiUiState, viewModel: AiViewModel, dismiss: () -> Unit) {
    var endpoint by remember { mutableStateOf(state.config.endpoint) }
    var deployment by remember { mutableStateOf(state.config.deployment) }
    var protocol by remember { mutableStateOf(state.config.api) }
    var version by remember { mutableStateOf(state.config.apiVersion) }
    // Deliberately not rememberSaveable: keys must never enter Bundle/session restoration.
    var key by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!state.busy) { key = ""; dismiss() } }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Azure 连接设置", style = PaperHeading)
                Text("使用你自己已有的 Azure 资源和 deployment；不创建资源、不申请权限，也不保存 Azure 账号密码。暂不支持 Entra 登录或其他云。", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = PaperPalette.Error) }
                PaperField(endpoint, { endpoint = it }, label = "资源根地址", placeholder = { Text("https://资源名.openai.azure.com") }, enabled = !state.busy)
                PaperField(deployment, { deployment = it }, label = "Deployment 名称", enabled = !state.busy)
                Row {
                    AzureApi.entries.forEach { api -> FilterChip(protocol == api, { protocol = api }, label = { Text(if (api == AzureApi.V1) "v1 兼容接口" else "传统部署接口") }, enabled = !state.busy) }
                }
                if (protocol == AzureApi.DEPLOYMENT) PaperField(version, { version = it }, label = "API version", enabled = !state.busy)
                Text("API Key${if (state.hasKey) "（已保存；留空保留，更换资源必须填写新 Key）" else ""}")
                OutlinedTextField(key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    label = { Text("API Key") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Text("Key 仅在 Android Keystore 加密后保存，不进入会话、药箱备份、日志或截图。", style = MaterialTheme.typography.bodySmall)
                PenButton("保存连接", { viewModel.save(AzureConnection(endpoint, deployment, protocol, version), key) { key = ""; dismiss() } }, Modifier.fillMaxWidth(), enabled = !state.busy)
                Consent(state.config, state.acknowledged, viewModel::acknowledge, !state.busy)
                TextButton(onClick = viewModel::test, enabled = !state.busy && state.hasKey && state.acknowledged) { Text("测试已保存的连接（可能计费）") }
                if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmRemove = true }, enabled = !state.busy && state.hasKey) { Text("移除连接和 Key") }
                TextButton(onClick = { key = ""; dismiss() }, enabled = !state.busy) { Text("返回助手") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("移除 Azure 连接？") },
            text = { Text("清除本地 Key 与连接设置；Azure Key 不会远端撤销，本地对话仍保留。") },
            confirmButton = { TextButton(onClick = { viewModel.clearConnection(); confirmRemove = false; key = ""; dismiss() }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } })
    }
}
