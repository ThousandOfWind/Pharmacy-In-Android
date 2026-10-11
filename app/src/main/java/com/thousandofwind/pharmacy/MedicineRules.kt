package com.thousandofwind.pharmacy

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

/** Month-only labels expire at the end of that month; unknown dates are never guessed. */
fun expiryDay(value: String): LocalDate? = try {
    when {
        Regex("\\d{4}-\\d{2}-\\d{2}").matches(value.trim()) -> LocalDate.parse(value.trim())
        Regex("\\d{4}-\\d{2}").matches(value.trim()) -> YearMonth.parse(value.trim()).atEndOfMonth()
        else -> null
    }
} catch (_: DateTimeParseException) {
    null
}

enum class ExpiryStatus(val label: String) {
    VALID("有效"), SOON("90 天内到期"), EXPIRED("已过期"), UNKNOWN("日期待补充")
}

fun expiryStatus(value: String, today: LocalDate = LocalDate.now()): ExpiryStatus {
    val day = expiryDay(value) ?: return ExpiryStatus.UNKNOWN
    return when {
        day.isBefore(today) -> ExpiryStatus.EXPIRED
        !day.isAfter(today.plusDays(90)) -> ExpiryStatus.SOON
        else -> ExpiryStatus.VALID
    }
}

const val MAX_MEDICINE_TAGS = 8
const val MAX_TAG_LENGTH = 12

data class MedicineTagGroup(val label: String, val tags: List<String>)

// Optional organizing labels. OCR suggestions require explicit section evidence and user adoption.
val commonMedicineTagGroups = listOf(
    MedicineTagGroup("用途整理", listOf("感冒相关", "退热", "止痛", "过敏相关", "肠胃相关", "咽喉相关", "眼部", "皮肤相关")),
    MedicineTagGroup("使用方式", listOf("口服", "外用")),
    MedicineTagGroup("备用场景", listOf("家庭常备", "出行备用")),
    MedicineTagGroup("保存提示 · 请核对包装", listOf("需冷藏", "避光保存"))
)

fun normalizeMedicineTags(tags: List<String>): List<String> = buildList {
    tags.forEach { value ->
        val tag = value.trim()
        // Use exactly the same Unicode case comparison as chip selection and filtering.
        if (tag.isNotBlank() && none { it.equals(tag, ignoreCase = true) }) add(tag)
    }
}

fun medicineTagsError(tags: List<String>): String? {
    val normalized = normalizeMedicineTags(tags)
    return when {
        normalized.any { tag ->
            tag.length > MAX_TAG_LENGTH || tag.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }
        } -> "每个标签最多 $MAX_TAG_LENGTH 个字符，不能包含换行或控制字符"
        normalized.size > MAX_MEDICINE_TAGS -> "每条药品最多 $MAX_MEDICINE_TAGS 个标签"
        else -> null
    }
}

/** Tag filtering is exact (not substring); search also includes tag text. */
fun matchesMedicineTextAndTag(
    name: String, efficacy: String, notes: String, tags: List<String>, search: String, tag: String?,
    extraText: List<String> = emptyList()
): Boolean {
    val query = search.trim()
    return (tag == null || tags.any { it.equals(tag, ignoreCase = true) }) &&
        (query.isEmpty() || (listOf(name, efficacy, notes) + tags + extraText).any { it.contains(query, ignoreCase = true) })
}

val quantityUnits = listOf("片", "粒", "盒", "瓶", "支", "袋", "板", "包", "毫升")

fun quantityError(quantity: String, unit: String): String? = when {
    quantity.isBlank() -> null
    quantity.trim().toIntOrNull() == null || quantity.trim().toInt() < 0 -> "请输入 0 或正整数，且不超过 2147483647"
    unit !in quantityUnits -> "请选择余量单位"
    else -> null
}

fun formError(name: String, expiry: String, quantity: String, unit: String): String? = when {
    name.isBlank() -> "请填写药品名称"
    expiry.isNotBlank() && expiryDay(expiry) == null -> "有效期请填 YYYY-MM 或 YYYY-MM-DD；无法确认时请留空"
    else -> quantityError(quantity, unit)
}

data class OcrCandidates(
    val name: String = "", val expiry: String = "", val efficacy: String = "",
    val information: MedicineInformation = MedicineInformation(),
    val nameOptions: List<String> = emptyList(), val warnings: List<String> = emptyList(),
    val tagSuggestions: List<OcrTagSuggestion> = emptyList()
)

fun remainingLabel(quantity: Int?, unit: String): String = when {
    quantity == null -> "余量未记录"
    unit.isBlank() -> "剩余 $quantity · 单位待补充"
    else -> "剩余 $quantity $unit"
}
