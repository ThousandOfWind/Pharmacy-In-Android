package com.thousandofwind.pharmacy.ai

import kotlinx.serialization.Serializable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.net.URI

@Serializable enum class AzureApi { V1, DEPLOYMENT }
@Serializable data class AzureConnection(
    val endpoint: String = "", val deployment: String = "",
    val api: AzureApi = AzureApi.V1, val apiVersion: String = "2024-10-21"
) {
    fun baseUrl(): String = endpoint.trim().trimEnd('/') + if (api == AzureApi.V1) "/openai/v1/" else "/openai/deployments/$deployment/"
}

fun connectionError(value: AzureConnection): String? {
    val uri = runCatching { URI(value.endpoint.trim()) }.getOrNull()
    return when {
        uri == null || uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/") -> "填写 Azure 资源的 HTTPS 根地址，不含路径、参数或端口"
        !listOf(".openai.azure.com", ".cognitiveservices.azure.com", ".services.ai.azure.com").any { suffix ->
            uri.host.endsWith(suffix) && uri.host.removeSuffix(suffix).matches(Regex("[A-Za-z0-9-]+"))
        } -> "仅支持 Azure 公有云资源地址（如 https://资源名.openai.azure.com）"
        value.deployment in setOf(".", "..") || !value.deployment.matches(Regex("[A-Za-z0-9_.-]{1,100}")) -> "deployment 名称为 1–100 个字母、数字或 ._-，不是模型显示名称"
        value.api == AzureApi.DEPLOYMENT && !value.apiVersion.matches(Regex("20\\d{2}-\\d{2}-\\d{2}(-preview)?")) -> "API version 请填 YYYY-MM-DD 或 YYYY-MM-DD-preview"
        else -> null
    }
}

@Serializable data class ChatTurn(val question: String, val answer: String, val references: List<MedicineReference> = emptyList())
@Serializable data class MedicineReference(val id: Long, val name: String, val summary: String)
data class ToolCall(val id: String, val name: String, val arguments: String)
data class ModelReply(val text: String, val calls: List<ToolCall>, val finish: String)
sealed interface AiMessage {
    data class User(val text: String) : AiMessage
    data class Assistant(val text: String, val calls: List<ToolCall> = emptyList()) : AiMessage
    data class Result(val id: String, val text: String) : AiMessage
}
interface ModelAdapter : AutoCloseable {
    suspend fun reply(messages: List<AiMessage>, tools: Boolean, onDelta: (String) -> Unit): ModelReply
}
data class ToolResult(val text: String, val references: List<MedicineReference> = emptyList())
interface CabinetTools { suspend fun execute(call: ToolCall): ToolResult }
data class AssistantResult(val text: String, val references: List<MedicineReference>)

const val MAX_QUESTION = 2000
const val MAX_OUTPUT = 12000
const val MAX_HISTORY_CHARS = 24000
const val MAX_TURNS = 8
const val MAX_MODEL_REQUESTS = 4

fun queryArguments(value: String): String {
    val obj = Json.parseToJsonElement(value).jsonObject
    require(obj.keys == setOf("query"))
    val query = obj.getValue("query").jsonPrimitive
    require(query.isString && query.content.isNotBlank() && query.content.length <= 80)
    return query.content
}

class AssistantEngine {
    suspend fun run(question: String, history: List<ChatTurn>, adapter: ModelAdapter,
                    cabinet: CabinetTools?, onDelta: (String) -> Unit, onStatus: (String) -> Unit): AssistantResult {
        require(question.isNotBlank() && question.length <= MAX_QUESTION)
        val retained = mutableListOf<ChatTurn>()
        var size = question.length
        for (turn in history.takeLast(MAX_TURNS).asReversed()) {
            val addition = turn.question.length + turn.answer.length
            if (size + addition > MAX_HISTORY_CHARS) break
            retained.add(0, turn); size += addition
        }
        val messages = mutableListOf<AiMessage>()
        retained.forEach { messages += AiMessage.User(it.question); messages += AiMessage.Assistant(
            if (it.references.isEmpty()) it.answer else "[历史库存查询，可能已变化，不是当前库存] ${it.answer}") }
        messages += AiMessage.User(question)
        val references = linkedMapOf<Long, MedicineReference>()
        val seenIds = mutableSetOf<String>()
        repeat(MAX_MODEL_REQUESTS) { request ->
            onStatus(if (request == 0) "正在连接 Azure…" else "正在整理查询结果…")
            val response = adapter.reply(messages.toList(), cabinet != null, onDelta)
            currentCoroutineContext().ensureActive()
            require(response.text.length <= MAX_OUTPUT && response.calls.size <= 4)
            if (response.calls.isEmpty()) {
                require(response.finish == "stop") { "响应未完整完成" }
                return AssistantResult(response.text.ifBlank { "模型没有返回文字，请重新提问。" }, references.values.toList())
            }
            require(response.finish == "tool_calls") { "工具响应未完整完成" }
            check(request < MAX_MODEL_REQUESTS - 1) { "达到工具调用上限，已停止" }
            messages += AiMessage.Assistant(response.text, response.calls)
            for (call in response.calls) {
                currentCoroutineContext().ensureActive()
                require(call.id.isNotBlank() && seenIds.add(call.id)) { "重复或缺失的工具 ID" }
                onStatus("正在查询本地药箱…")
                val result = if (cabinet == null) ToolResult("{\"error\":\"Cabinet access is disabled\"}") else cabinet.execute(call)
                require(result.text.length <= 16000)
                result.references.forEach { if (references.size < 20) references[it.id] = it }
                messages += AiMessage.Result(call.id, result.text)
            }
        }
        error("达到工具调用上限")
    }
}
