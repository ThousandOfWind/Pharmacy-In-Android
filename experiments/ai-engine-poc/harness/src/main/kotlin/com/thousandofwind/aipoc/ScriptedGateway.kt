package com.thousandofwind.aipoc

import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.http.content.OutgoingContent
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** No sockets, external API, real credential or pharmacy database. Exercises actual SDK codecs. */
@OptIn(InternalAPI::class)
class ScriptedGateway(private val scenario: String) : AutoCloseable {
    private val writers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val requests = mutableListOf<JsonObject>()
    val paths = mutableListOf<String>()
    @Volatile var transportCancelled = false; private set
    private val handler: MockRequestHandler = { request ->
        check(request.url.host == "poc.invalid") { "Unexpected destination" }
        paths += request.url.encodedPath
        var content = request.body
        while (content is OutgoingContent.ContentWrapper) content = content.delegate()
        val body = (content as TextContent).text
        val payload = Json.parseToJsonElement(body).jsonObject
        requests += payload
        check(payload["model"]?.jsonPrimitive?.content == MODEL)
        check(payload["stream"]?.jsonPrimitive?.boolean == true)
        if (scenario == "unauthorized") {
            respond("{\"error\":{\"message\":\"Synthetic unauthorized\",\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}",
                HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            val channel = ByteChannel(autoFlush = true)
            val chunks = frames(payload, requests.size)
            val writer = writers.launch {
                try {
                    for (frame in chunks) {
                        // Separate writes ensure tests observe deltas before completion, not a preassembled answer.
                        delay(if (scenario == "slow") 200 else 5)
                        channel.writeStringUtf8("data: $frame\n\n")
                    }
                    if (scenario == "slow") awaitCancellation()
                    channel.writeStringUtf8("data: [DONE]\n\n")
                    channel.close()
                } catch (e: CancellationException) {
                    transportCancelled = true
                    channel.cancel(e)
                    throw e
                } catch (e: Throwable) {
                    channel.cancel(e)
                }
            }
            request.executionContext.invokeOnCompletion { cause ->
                // SSE's request job finishes after headers; its session owns the response stream.
                if (request.body !is io.ktor.client.plugins.sse.SSEClientContent || cause != null) writer.cancel()
            }
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
    }

    // Ktor MockEngine lacks SSECapability. Adapt the bytes using Ktor's own response adapter;
    // this adds no sockets and does not replace the provider's SSE/parser implementation.
    @OptIn(InternalAPI::class)
    val engine = object : MockEngine(MockEngineConfig().apply { addHandler(handler) }) {
        override val supportedCapabilities = super.supportedCapabilities + SSECapability
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val response = super.execute(data)
            val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(
                data, response.statusCode, response.headers, response.body as ByteReadChannel,
                data.body, response.callContext
            ) ?: response.body
            if (adapted is io.ktor.client.plugins.sse.DefaultClientSSESession) {
                adapted.coroutineContext[Job]?.invokeOnCompletion {
                    transportCancelled = true
                    (response.body as ByteReadChannel).cancel()
                }
            }
            return HttpResponseData(response.statusCode, response.requestTime, response.headers,
                response.version, adapted, response.callContext)
        }
    }

    override fun close() { writers.cancel(); engine.close() }

    private fun frames(payload: JsonObject, count: Int): List<String> {
        if (scenario == "broken") return listOf("{not-json}")
        val messages = payload.getValue("messages").jsonArray
        val hasResult = messages.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
        if (scenario == "slow") return listOf(chunk(buildJsonObject { put("role", "assistant"); put("content", "开始") }))
        if (hasResult && scenario !in setOf("unknown", "invalid")) {
            check(messages.any {
                val message = it.jsonObject
                message["role"]?.jsonPrimitive?.content == "tool" &&
                    message["content"]?.toString()?.contains("synthetic-1") == true &&
                    message["content"]?.toString()?.contains("remaining") == true
            }) { "Actual tool result was not sent back to the SDK" }
        }
        if (!hasResult || scenario == "loop") {
            val name = if (scenario == "unknown") "delete_medicine" else "search_medicines"
            val args = if (scenario == "invalid") "{\"query\":42}" else "{\"query\":\"布洛芬\"}"
            // Tool arguments intentionally fragmented; no tool may execute from a partial delta.
            return listOf(
                toolDelta(count, name, args.take(9), first = true),
                toolDelta(count, name, args.drop(9), first = false),
                chunk(buildJsonObject {}, "tool_calls")
            )
        }
        val answer = when (scenario) {
            "unknown", "invalid" -> "工具请求被拒绝，未修改药箱。"
            else -> if (messages.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" } > 1) "这盒布洛芬放在卧室抽屉。" else "布洛芬还剩8片，放在卧室抽屉。"
        }
        return answer.chunked(4).map { text -> chunk(buildJsonObject { put("content", text) }) } +
            chunk(buildJsonObject {}, "stop")
    }

    private fun toolDelta(count: Int, name: String, args: String, first: Boolean): String = chunk(buildJsonObject {
        if (first) put("role", "assistant")
        putJsonArray("tool_calls") {
            add(buildJsonObject {
                put("index", 0)
                if (first) { put("id", "poc-call-$count"); put("type", "function") }
                putJsonObject("function") { if (first) put("name", name); put("arguments", args) }
            })
        }
    })

    private fun chunk(delta: JsonObject, finish: String? = null): String = buildJsonObject {
        put("id", "poc-message"); put("object", "chat.completion.chunk"); put("created", 1700000000); put("model", MODEL)
        putJsonArray("choices") { add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull) }) }
    }.toString()
}
