package com.thousandofwind.aipoc

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

const val BASE_URL = "https://poc.invalid/custom/v1/"
const val MODEL = "poc-tool-model"
const val MAX_REQUESTS = 4
const val SYSTEM = "只查询用户记录，不提供用药建议。缺失资料明确为未知。"

@Serializable data class SearchArgs(val query: String)
@Serializable data class Turn(val user: String, val answer: String)
@Serializable data class Session(val turns: List<Turn> = emptyList()) {
    fun encode(): String = Json.encodeToString(this)
    companion object { fun decode(value: String): Session = Json.decodeFromString(value) }
}
// Minimal text-turn storage for this PoC: complete answers only, not Pi's session tree.
interface Engine : AutoCloseable {
    suspend fun reply(question: String, session: Session, tools: Cabinet, onText: (String) -> Unit): String
}

class Cabinet {
    var reads = 0; private set
    suspend fun search(args: SearchArgs): String {
        coroutineContext.ensureActive()
        require(args.query.isNotBlank() && args.query.length <= 80) { "Invalid search query" }
        reads++
        return if ("布洛芬" in args.query) "[{\"id\":\"synthetic-1\",\"name\":\"布洛芬\",\"remaining\":8,\"unit\":\"片\",\"location\":\"卧室抽屉\"}]" else "[]"
    }
}

fun searchArguments(raw: String): SearchArgs {
    val objectValue = Json.parseToJsonElement(raw).jsonObject
    require(objectValue.keys == setOf("query")) { "Only query is accepted" }
    val query = objectValue.getValue("query").jsonPrimitive
    require(query.isString) { "query must be a string" }
    return SearchArgs(query.content)
}

val SEARCH_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { putJsonObject("query") { put("type", "string"); put("maxLength", 80) } }
    putJsonArray("required") { add("query") }
    put("additionalProperties", false)
}
