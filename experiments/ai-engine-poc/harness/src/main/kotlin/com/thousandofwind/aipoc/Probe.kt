package com.thousandofwind.aipoc

import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Probe {
    val scenarios = listOf("chat", "unknown", "invalid", "unauthorized", "broken", "slow", "loop")
    suspend fun run(scenario: String, create: (ScriptedGateway) -> Engine): String = withTimeout(15000) {
        val gateway = ScriptedGateway(scenario)
        val cabinet = Cabinet()
        val engine = create(gateway)
        try {
            when (scenario) {
                "chat" -> {
                    val deltas = mutableListOf<String>()
                    val answer = engine.reply("布洛芬还有多少？", Session(), cabinet, deltas::add)
                    check(answer == "布洛芬还剩8片，放在卧室抽屉。")
                    check(deltas.size > 1 && deltas.joinToString("") == answer)
                    check(cabinet.reads == 1 && gateway.requests.size == 2)
                    val restored = Session.decode(Session(listOf(Turn("布洛芬还有多少？", answer))).encode())
                    val followup = engine.reply("那盒放在哪里？", restored, cabinet) {}
                    check(followup == "这盒布洛芬放在卧室抽屉。")
                    check(gateway.requests[2].getValue("messages").jsonArray.any {
                        it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" &&
                            it.jsonObject["content"]?.toString()?.contains(answer) == true
                    })
                    check(cabinet.reads == 2)
                }
                "unknown", "invalid" -> {
                    // SDKs may return a tool-error response or reject the run. Both must perform zero reads.
                    val response = try { engine.reply("查询", Session(), cabinet) {} }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    check(gateway.requests.isNotEmpty() && cabinet.reads == 0)
                    if (response != null) {
                        check(gateway.requests.size == 2 && response == "工具请求被拒绝，未修改药箱。")
                        check(gateway.requests.last().getValue("messages").jsonArray.any {
                            it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
                        })
                    }
                }
                "unauthorized", "broken" -> {
                    var failed = false
                    try { engine.reply("查询", Session(), cabinet) {} } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
                    check(failed && cabinet.reads == 0 && gateway.requests.size == 1)
                }
                "slow" -> coroutineScope {
                    val started = CompletableDeferred<Unit>()
                    val run = launch { engine.reply("查询", Session(), cabinet) { started.complete(Unit) } }
                    started.await()
                    run.cancelAndJoin()
                    delay(100)
                    check(run.isCancelled && cabinet.reads == 0 && gateway.requests.size == 1)
                    check(gateway.transportCancelled) { "Underlying stream was not cancelled" }
                }
                "loop" -> {
                    var failed = false
                    try { engine.reply("查询", Session(), cabinet) {} } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
                    check(failed && gateway.requests.size == MAX_REQUESTS && cabinet.reads <= MAX_REQUESTS)
                }
                else -> error("Unknown scenario")
            }
            check(gateway.paths.all { it == "/custom/v1/chat/completions" })
            "PASS $scenario (requests=${gateway.requests.size}, reads=${cabinet.reads})".also(::println)
        } finally { engine.close(); gateway.close() }
    }
}
