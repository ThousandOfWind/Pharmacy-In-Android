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

data class OcrCandidates(val name: String = "", val expiry: String = "", val efficacy: String = "")

/** Only explicitly labelled fields become candidates. Unlabelled text remains available for review. */
fun extractCandidates(text: String): OcrCandidates {
    val lines = text.lines().map(String::trim).filter(String::isNotBlank)
    val name = lines.firstNotNullOfOrNull { line ->
        Regex("(?:通用名称|药品名称|商品名称|通用名)\\s*[:：]\\s*(.+)").find(line)?.groupValues?.get(1)?.trim()
    }.orEmpty()
    var expiry = ""
    for ((index, line) in lines.withIndex()) {
        // Never substitute a production date or a shelf-life duration for an expiry date.
        val label = Regex("(?:有效期至|有效日期|失效日期|失效期|保质期至|EXP\\b)", RegexOption.IGNORE_CASE).find(line)
            ?: continue
        val afterLabel = line.substring(label.range.last + 1).trim().trimStart(':', '：').trim()
        val nextLine = lines.getOrNull(index + 1).orEmpty().takeIf {
            !Regex("生产|制造|MFG", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }.orEmpty()
        val dateText = afterLabel.ifBlank { nextLine }
        if (Regex("生产|制造|MFG", RegexOption.IGNORE_CASE).containsMatchIn(dateText)) continue
        val date = Regex("(20\\d{2})[年./-](\\d{1,2})(?:[月./-](\\d{1,2}))?")
            .find(dateText) ?: continue
        val year = date.groupValues[1]
        val month = date.groupValues[2].padStart(2, '0')
        val day = date.groupValues[3]
        val candidate = "$year-$month" + if (day.isNotBlank()) "-${day.padStart(2, '0')}" else ""
        if (expiryDay(candidate) != null) {
            expiry = candidate
            break
        }
    }
    val efficacy = lines.firstOrNull { Regex("适应症|功能主治|功效").containsMatchIn(it) }.orEmpty()
    return OcrCandidates(name, expiry, efficacy)
}

fun remainingLabel(quantity: Int?, unit: String): String = when {
    quantity == null -> "余量未记录"
    unit.isBlank() -> "剩余 $quantity · 单位待补充"
    else -> "剩余 $quantity $unit"
}
