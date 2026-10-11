package com.thousandofwind.pharmacy.ai

import com.thousandofwind.pharmacy.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Whitelisted read-only inventory projection; never sends notes, OCR, photographs or leaflet text. */
class MedicineTools(private val snapshot: suspend () -> List<MedicineEntity>) : CabinetTools {
    override suspend fun execute(call: ToolCall): ToolResult {
        currentCoroutineContext().ensureActive()
        val query = try {
            when (call.name) {
                "search_medicines" -> queryArguments(call.arguments)
                "query_expiry" -> { require(Json.parseToJsonElement(call.arguments).jsonObject.isEmpty()); null }
                else -> return ToolResult("{\"error\":\"Unknown or non-read-only tool\"}")
            }
        } catch (_: IllegalArgumentException) { return ToolResult("{\"error\":\"Invalid tool arguments\"}") }
        val matching = snapshot().filter { medicine ->
            if (query == null) expiryStatus(medicine.expiryDate) != ExpiryStatus.VALID
            else listOf(medicine.name, medicine.information.brandName, medicine.information.genericName,
                medicine.information.storageLocation).any { it.contains(query, ignoreCase = true) }
        }
        val rows = matching.take(10)
        val data = buildJsonObject {
            put("total_matches", matching.size); put("truncated", matching.size > rows.size)
            put("source", "user_inventory_not_medical_advice")
            putJsonArray("records") {
                rows.forEach { item -> add(buildJsonObject {
                    put("id", item.id); put("name", item.name.take(200))
                    put("brand", item.information.brandName.take(200)); put("generic_name", item.information.genericName.take(200))
                    put("specification", item.information.specification.take(200)); put("location", item.information.storageLocation.take(200))
                    put("expiry", item.expiryDate.take(30)); put("expiry_status", expiryStatus(item.expiryDate).name)
                    put("remaining", item.remainingQuantity?.let(::JsonPrimitive) ?: JsonNull); put("unit", item.quantityUnit.take(30))
                }) }
            }
        }
        return ToolResult(data.toString(), rows.map { item -> MedicineReference(item.id, item.name,
            "${remainingLabel(item.remainingQuantity, item.quantityUnit)} · ${item.information.storageLocation.ifBlank { "位置未记录" }}") })
    }
}
