package com.thousandofwind.aipoc.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.serialization.typeToken
import com.thousandofwind.aipoc.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.coroutineContext

class KoogEngine(gateway: ScriptedGateway) : Engine {
    private val transport = HttpClient(gateway.engine)
    private val client = OpenAILLMClient(
        apiKey = "synthetic-not-a-key",
        settings = OpenAIClientSettings(baseUrl = BASE_URL, chatCompletionsPath = "chat/completions"),
        httpClientFactory = KtorKoogHttpClient.Factory(transport)
    )
    private val executor = MultiLLMPromptExecutor(listOf(client))
    private val model = LLModel(LLMProvider.OpenAI, MODEL,
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Completions), contextLength = 8192)

    override suspend fun reply(question: String, session: Session, tools: Cabinet, onText: (String) -> Unit): String {
        val search = object : Tool<SearchArgs, String>(
            argsType = typeToken<SearchArgs>(), resultType = typeToken<String>(),
            name = "search_medicines", description = "Search synthetic medicine records"
        ) { override suspend fun execute(args: SearchArgs): String = tools.search(args) }
        var requests = 0
        val graph = strategy<String, String>("bounded-stream-chat") {
            val first by nodeLLMRequestStreaming()
            val collect by node<Flow<StreamFrame>, Message.Assistant> { stream ->
                check(++requests <= MAX_REQUESTS) { "Request limit reached" }
                val frames = mutableListOf<StreamFrame>()
                stream.collect { frame ->
                    frames += frame
                    if (frame is StreamFrame.TextDelta) onText(frame.text)
                }
                coroutineContext.ensureActive()
                check(frames.lastOrNull() is StreamFrame.End) { "Incomplete response" }
                val answer = frames.toMessageResponse()
                check(answer.finishReason in setOf("stop", "tool_calls")) { "Truncated response" }
                llm.writeSession { appendPrompt { message(answer) } }
                answer
            }
            val execute by nodeExecuteTools()
            val next by nodeLLMSendToolResultsStreaming()
            edge(nodeStart forwardTo first)
            edge(first forwardTo collect)
            edge(collect forwardTo nodeFinish onTextMessage { true })
            edge(collect forwardTo execute onToolCalls { true })
            edge(execute forwardTo next)
            edge(next forwardTo collect)
        }
        val agent = AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(prompt = prompt("poc") {
                system(SYSTEM)
                session.turns.forEach { user(it.user); assistant(it.answer) }
            }, model = model, maxAgentIterations = 20),
            strategy = graph,
            toolRegistry = ToolRegistry { tool(search) }
        )
        return try { agent.run(question) } finally { agent.close() }
    }
    override fun close() { executor.close(); transport.close() }
}
