package com.thousandofwind.pharmacy.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thousandofwind.pharmacy.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.UUID

// UI state never includes an API key. Credential reads happen only inside an explicitly initiated task.
data class AiUiState(
    val config: AzureConnection = AzureConnection(), val hasKey: Boolean = false,
    val sessions: List<AiSessionEntity> = emptyList(), val selected: AiSessionEntity? = null,
    val turns: List<ChatTurn> = emptyList(), val input: String = "", val partial: String = "",
    val busy: Boolean = false, val status: String = "", val error: String? = null,
    val allowCabinet: Boolean = false, val acknowledged: Boolean = false
)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val credentials = AiCredentialStore(application)
    private val settings = AiSettings(application)
    private val sessions = AiDatabase.get(application).sessions()
    private val state = MutableStateFlow(AiUiState())
    val ui: StateFlow<AiUiState> = state.asStateFlow()
    private var task: Job? = null
    private var turnsJob: Job? = null
    init {
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { settings.load() }
                val hasKey = withContext(Dispatchers.IO) { credentials.read(config.endpoint)?.isNotBlank() == true }
                state.update { it.copy(config = config, hasKey = hasKey) }
                sessions.observeSessions().collect { rows -> state.update { it.copy(sessions = rows) } }
            } catch (_: Exception) { state.update { it.copy(error = "AI 本地存储无法读取，请检查连接设置；原药箱不受影响") } }
        }
    }
    fun input(value: String) { if (!state.value.busy) state.update { it.copy(input = value.take(MAX_QUESTION)) } }
    fun acknowledge(value: Boolean) { if (!state.value.busy) state.update { it.copy(acknowledged = value) } }
    fun allowCabinet(value: Boolean) { if (!state.value.busy) state.update { it.copy(allowCabinet = value) } }
    fun dismissError() { state.update { it.copy(error = null) } }
    fun stop() {
        task?.cancel()
        // Stay busy until the old coroutine settles, so its finally cannot clobber a new run.
        state.update { it.copy(status = "已停止；未完成回答不会保存", partial = "") }
    }
    fun save(config: AzureConnection, enteredKey: String, done: () -> Unit) {
        if (state.value.busy) return
        val clean = config.copy(endpoint = config.endpoint.trim().trimEnd('/'), deployment = config.deployment.trim(), apiVersion = config.apiVersion.trim())
        val error = connectionError(clean)
        if (error != null) { state.update { it.copy(error = error) }; return }
        if (enteredKey.isBlank() && (!state.value.hasKey || clean.endpoint != state.value.config.endpoint)) {
            state.update { it.copy(error = "首次连接或更换资源地址时，请输入该资源的 API Key") }; return
        }
        if (enteredKey.length > 4096 || enteredKey.any(Char::isISOControl)) { state.update { it.copy(error = "API Key 格式错误") }; return }
        state.update { it.copy(busy = true, error = null) }
        task = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (enteredKey.isNotBlank()) credentials.save(clean.endpoint, enteredKey.trim()); settings.save(clean) }
                state.update { it.copy(config = clean, hasKey = true, acknowledged = false, allowCabinet = false, status = "连接已保存，请测试连接。更换配置后请新建会话") }
                done()
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { state.update { it.copy(error = "连接保存失败，请重新填写；不会输出 Key") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun clearConnection() {
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        task = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { credentials.clear(); settings.clear() }
                state.update { it.copy(config = AzureConnection(), hasKey = false, acknowledged = false, allowCabinet = false, status = "已移除连接和 Key；会话仍保留在本地") }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { state.update { it.copy(error = "移除连接失败") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun select(session: AiSessionEntity?) {
        if (state.value.busy) return
        turnsJob?.cancel()
        state.update { it.copy(selected = session, turns = emptyList(), partial = "", input = "", error = null, status = "", allowCabinet = false) }
        if (session != null) turnsJob = viewModelScope.launch {
            try { sessions.observeTurns(session.id).collect { rows -> state.update { it.copy(turns = rows.map { row -> Json.decodeFromString<ChatTurn>(row.turnJson) }) } } }
            catch (_: Exception) { state.update { it.copy(error = "会话读取失败，请新建会话") } }
        }
    }
    fun newSession() {
        if (state.value.busy) return
        if (!state.value.hasKey || connectionError(state.value.config) != null) { state.update { it.copy(error = "先配置 Azure 连接") }; return }
        state.update { it.copy(busy = true) }
        task = viewModelScope.launch {
            try {
                val row = AiSessionEntity(UUID.randomUUID().toString(), "新对话", Json.encodeToString(state.value.config), System.currentTimeMillis())
                sessions.create(row); state.update { it.copy(busy = false) }; select(row)
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { state.update { it.copy(error = "新建会话失败") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun delete(session: AiSessionEntity) {
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        task = viewModelScope.launch {
            try { sessions.delete(session.id); state.update { it.copy(busy = false) }; if (state.value.selected?.id == session.id) select(null) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.update { it.copy(error = "删除会话失败") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun rename(session: AiSessionEntity, title: String) {
        if (state.value.busy || title.isBlank()) return
        state.update { it.copy(busy = true) }
        task = viewModelScope.launch {
            try { sessions.rename(session.id, title.trim().take(60)); state.update { if (it.selected?.id == session.id) it.copy(selected = session.copy(title = title.trim().take(60))) else it } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.update { it.copy(error = "重命名失败") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun test() {
        if (state.value.busy || !state.value.hasKey || connectionError(state.value.config) != null) return
        if (!state.value.acknowledged) { state.update { it.copy(error = "请先确认数据发送说明") }; return }
        val config = state.value.config
        state.update { it.copy(busy = true, error = null, status = "正在测试…") }
        task = viewModelScope.launch {
            try {
                withTimeout(60_000) {
                    val key = withContext(Dispatchers.IO) { credentials.read(config.endpoint) } ?: error("Missing key")
                    withContext(Dispatchers.IO) { AzureModelAdapter(config, key).use { adapter ->
                        val result = adapter.reply(listOf(AiMessage.User("只回复：连接成功")), false) {}
                        check(result.calls.isEmpty() && result.finish == "stop" && result.text.isNotBlank())
                    } }
                }
                state.update { it.copy(status = "连接测试通过：该 deployment 的流式文本可用，尚未验证工具调用") }
            } catch (e: CancellationException) { if (e is TimeoutCancellationException) state.update { it.copy(error = "连接超时，已停止") } else throw e }
              catch (e: Exception) { state.update { it.copy(error = safeError(e)) } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun send() {
        val current = state.value
        val selected = current.selected ?: return
        if (current.busy || current.input.isBlank()) return
        if (!current.acknowledged) { state.update { it.copy(error = "请先确认聊天内容会发送至 Azure") }; return }
        if (!current.hasKey || runCatching { Json.decodeFromString<AzureConnection>(selected.configJson) }.getOrNull() != current.config) {
            state.update { it.copy(error = "连接已变更或移除；请使用当前连接新建会话，旧对话不会发送到新连接") }; return
        }
        state.update { it.copy(busy = true, partial = "", error = null, status = "正在连接 Azure…") }
        task = viewModelScope.launch {
            try {
                val result = withTimeout(90_000) {
                    val key = withContext(Dispatchers.IO) { credentials.read(current.config.endpoint) } ?: error("Missing key")
                    val history = sessions.recent(selected.id).asReversed().map { Json.decodeFromString<ChatTurn>(it.turnJson) }
                    val cabinet = if (current.allowCabinet) MedicineTools { AppDatabase.getInstance(getApplication()).medicineDao().snapshot() } else null
                    withContext(Dispatchers.IO) { AzureModelAdapter(current.config, key).use { adapter ->
                        AssistantEngine().run(current.input.trim(), history, adapter, cabinet,
                            { delta -> state.update { it.copy(partial = (it.partial + delta).take(MAX_OUTPUT)) } },
                            { progress -> state.update { it.copy(status = progress) } })
                    } }
                }
                currentCoroutineContext().ensureActive()
                sessions.append(AiTurnEntity(sessionId = selected.id, turnJson = Json.encodeToString(ChatTurn(current.input.trim(), result.text, result.references))))
                if (current.turns.isEmpty()) sessions.rename(selected.id, current.input.trim().take(40))
                state.update { it.copy(input = "", partial = "", status = "AI 回答可能有误，请核对药品原文") }
            } catch (e: CancellationException) { if (e is TimeoutCancellationException) state.update { it.copy(error = "对话超时，已停止；未完成回答不保存", partial = "") } else throw e }
              catch (e: Exception) { state.update { it.copy(error = safeError(e), partial = "") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    private fun safeError(error: Exception): String = when (error) {
        is com.aallam.openai.api.exception.AuthenticationException -> "Azure 认证失败，请检查 API Key；不会换模型或重试"
        is com.aallam.openai.api.exception.PermissionException -> "Azure 权限或网络访问策略拒绝，请核对资源设置"
        is com.aallam.openai.api.exception.RateLimitException -> "Azure 额度或速率受限，请稍后手动重试"
        is com.aallam.openai.api.exception.InvalidRequestException -> "Azure 拒绝请求：请核对 endpoint、deployment、协议/API version 或工具支持"
        else -> "AI 请求未完成，请检查网络和模型配置（${error.javaClass.simpleName}）；未保存部分回答"
    }
}
