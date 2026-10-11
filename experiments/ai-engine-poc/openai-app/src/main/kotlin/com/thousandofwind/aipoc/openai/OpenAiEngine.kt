package com.thousandofwind.aipoc.openai

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.*
import com.thousandofwind.aipoc.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class OpenAiEngine(gateway: ScriptedGateway) : Engine {
    private val client = OpenAI(OpenAIConfig(
        token = "synthetic-not-a-key",
        host = OpenAIHost(BASE_URL),
        engine = gateway.engine,
        logging = LoggingConfig(logLevel = LogLevel.None),
        retry = RetryStrategy(maxRetries = 0)
    ))

    override suspend fun reply(question: String, session: Session, tools: Cabinet, onText: (String) -> Unit): String {
        val messages = mutableListOf(ChatMessage(ChatRole.System, SYSTEM))
        session.turns.forEach { messages += ChatMessage(ChatRole.User, it.user); messages += ChatMessage(ChatRole.Assistant, it.answer) }
        messages += ChatMessage(ChatRole.User, question)
        repeat(MAX_REQUESTS) {
            val text = StringBuilder()
            val calls = sortedMapOf<Int, PartialCall>()
            var finish: String? = null
            client.chatCompletions(chatCompletionRequest {
                model = ModelId(MODEL)
                this.messages = messages.toList()
                tools { function("search_medicines", "Search synthetic medicine records", Parameters(SEARCH_SCHEMA)) }
            }).collect { chunk ->
                val choice = chunk.choices.single()
                choice.delta?.content?.let { text.append(it); onText(it) }
                choice.delta?.toolCalls.orEmpty().forEach { delta ->
                    val pending = calls.getOrPut(delta.index) { PartialCall() }
                    delta.id?.let { pending.id = it }
                    delta.function?.nameOrNull?.let { pending.name.append(it) }
                    delta.function?.argumentsOrNull?.let { pending.args.append(it) }
                }
                choice.finishReason?.let { finish = it.value }
            }
            coroutineContext.ensureActive()
            check(finish in setOf("stop", "tool_calls")) { "Incomplete or truncated response" }
            if (calls.isEmpty()) { check(finish == "stop"); return text.toString() }
            check(finish == "tool_calls" && calls.size <= 4) { "Invalid tool batch" }
            val complete = calls.values.map { ToolCall.Function(requireNotNull(it.id), FunctionCall(it.name.toString(), it.args.toString())) }
            messages += ChatMessage(ChatRole.Assistant, text.toString(), toolCalls = complete)
            complete.forEach { call ->
                coroutineContext.ensureActive()
                val result = try {
                    require(call.function.name == "search_medicines") { "Unknown tool" }
                    tools.search(searchArguments(requireNotNull(call.function.arguments)))
                } catch (e: CancellationException) { throw e }
                  catch (_: IllegalArgumentException) { "{\"error\":\"Tool request rejected\"}" }
                messages += ChatMessage(ChatRole.Tool, result, toolCallId = call.id)
            }
        }
        error("Request limit reached")
    }

    override fun close() { client.close() }
    private class PartialCall {
        var id: ToolId? = null
        val name = StringBuilder()
        val args = StringBuilder()
    }
}
