package com.thousandofwind.pharmacy

import com.thousandofwind.pharmacy.ai.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiRulesTest {
    @Test fun azureEndpointAndRealSdkStreaming() = runBlocking {
        assertNotNull(connectionError(AzureConnection("https://resource.openai.azure.com.evil.test", "deploy")))
        assertNotNull(connectionError(AzureConnection("https://resource.openai.azure.com@evil.test", "deploy")))
        assertNotNull(connectionError(AzureConnection("http://resource.openai.azure.com", "deploy")))
        assertNotNull(connectionError(AzureConnection("https://resource.openai.azure.com", "../deploy")))
        listOf(".", "..").forEach { assertNotNull(connectionError(AzureConnection("https://resource.openai.azure.com", it))) }
        for (api in AzureApi.entries) {
            val config = AzureConnection("https://test.openai.azure.com", "my-deployment", api)
            val mock = MockEngine { request ->
                assertEquals("test.openai.azure.com", request.url.host)
                assertEquals(if (api == AzureApi.V1) "/openai/v1/chat/completions" else "/openai/deployments/my-deployment/chat/completions", request.url.encodedPath)
                assertEquals(if (api == AzureApi.DEPLOYMENT) "2024-10-21" else null, request.url.parameters["api-version"])
                assertEquals(if (api == AzureApi.DEPLOYMENT) "synthetic-test-key" else null, request.headers["api-key"])
                assertEquals(if (api == AzureApi.V1) "Bearer synthetic-test-key" else null, request.headers["Authorization"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("my-deployment", body["model"]!!.jsonPrimitive.content)
                assertTrue(body["stream"]!!.jsonPrimitive.boolean)
                assertFalse(body["store"]!!.jsonPrimitive.boolean)
                val frames = if (body["tools"] != null) {
                    val deltas = listOf(
                        "{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"search_medicines\",\"arguments\":\"{\\\"query\\\":\"}}]}",
                        "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"测试\\\"}\"}}]}"
                    )
                    deltas.joinToString("") { "data: ${chunk(it)}\n\n" } + "data: ${chunk("{}", "tool_calls")}\n\ndata: [DONE]\n\n"
                } else listOf("{\"content\":\"连接\"}", "{\"content\":\"成功\"}").joinToString("") { "data: ${chunk(it)}\n\n" } +
                    "data: ${chunk("{}", "stop")}\n\ndata: [DONE]\n\n"
                respond(frames, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            AzureModelAdapter(config, "synthetic-test-key", mock).use { adapter ->
                val deltas = mutableListOf<String>()
                val reply = adapter.reply(listOf(AiMessage.User("测试")), false, deltas::add)
                assertEquals("连接成功", reply.text); assertEquals(listOf("连接", "成功"), deltas)
                val tools = adapter.reply(listOf(AiMessage.User("找药")), true) {}
                assertEquals("tool_calls", tools.finish)
                assertEquals("测试", queryArguments(tools.calls.single().arguments))
            }
            mock.close()
        }
    }
    @Test fun boundedLoopAndDisabledCabinetNeverRead() = runBlocking {
        var requests = 0
        var reads = 0
        val adapter = object : ModelAdapter {
            override suspend fun reply(messages: List<AiMessage>, tools: Boolean, onDelta: (String) -> Unit): ModelReply {
                requests++
                return ModelReply("", listOf(ToolCall("call-$requests", "search_medicines", "{\"query\":\"测试\"}")), "tool_calls")
            }
            override fun close() {}
        }
        val tool = object : CabinetTools { override suspend fun execute(call: ToolCall): ToolResult { reads++; return ToolResult("[]") } }
        try { AssistantEngine().run("测试", emptyList(), adapter, tool, {}, {}); fail("Must stop") } catch (_: IllegalStateException) {}
        assertEquals(MAX_MODEL_REQUESTS, requests); assertEquals(MAX_MODEL_REQUESTS - 1, reads)
        requests = 0; reads = 0
        try { AssistantEngine().run("测试", emptyList(), adapter, null, {}, {}); fail("Must stop") } catch (_: IllegalStateException) {}
        assertEquals(0, reads)
        val waiting = object : ModelAdapter {
            override suspend fun reply(messages: List<AiMessage>, tools: Boolean, onDelta: (String) -> Unit): ModelReply = awaitCancellation()
            override fun close() {}
        }
        coroutineScope {
            val job = launch { AssistantEngine().run("测试", emptyList(), waiting, tool, {}, {}) }
            yield(); job.cancelAndJoin(); assertTrue(job.isCancelled); assertEquals(0, reads)
        }
    }
    @Test fun readOnlyProjectionAndTextSessionRoundTrip() = runBlocking {
        var snapshots = 0
        val record = AiMedicineRecord(id = 9, name = "测试药", expiryDate = "", remainingQuantity = 0,
            quantityUnit = "片", brandName = "测试品牌", genericName = "", specification = "", storageLocation = "抽屉")
        val tool = MedicineTools { snapshots++; listOf(record) }
        assertTrue(tool.execute(ToolCall("1", "delete_medicine", "{}")).text.contains("error"))
        assertTrue(tool.execute(ToolCall("2", "search_medicines", "{\"query\":42}")).text.contains("error"))
        assertEquals(0, snapshots)
        val result = tool.execute(ToolCall("3", "search_medicines", "{\"query\":\"测试\"}"))
        assertEquals(1, snapshots); assertEquals(9L, result.references.single().id)
        listOf("private-notes", "private-photo", "private-ocr", "private-leaflet", "sensitive-efficacy").forEach { assertFalse(result.text.contains(it)) }
        assertTrue(result.text.contains("\"remaining\":0"))
        val turn = ChatTurn("余量？", "0 片", result.references)
        assertEquals(turn, Json.decodeFromString<ChatTurn>(Json.encodeToString(turn)))
    }
    private fun chunk(delta: String, finish: String? = null) = "{\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"my-deployment\",\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":${finish?.let { "\"$it\"" } ?: "null"}}]}"
}
