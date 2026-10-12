package com.thousandofwind.pharmacy

const val MAX_OCR_TEXT = 50000

/** Null means the scan cannot be retained; caller reports it instead of creating orphan candidates. */
fun appendOcrText(previous: String, current: String): String? {
    val combined = listOf(previous, current).filter(String::isNotBlank).joinToString("\n\n")
    return combined.takeIf { it.length <= MAX_OCR_TEXT }
}

/** Additional photos fill missing suggestions. Conflicts retain the earlier suggestion with a warning. */
fun mergePhotoCandidates(previous: OcrCandidates, incoming: OcrCandidates): OcrCandidates {
    val warnings = (previous.warnings + incoming.warnings).toMutableList()
    fun merge(label: String, old: String, next: String): String {
        if (old.isNotBlank() && next.isNotBlank() && old != next) warnings += "${label}在不同照片中不一致，保留先前候选，请对照当前包装核对"
        return old.ifBlank { next }
    }
    val name = merge("药名", previous.name, incoming.name)
    val expiry = merge("有效期", previous.expiry, incoming.expiry)
    val efficacy = merge("适应症", previous.efficacy, incoming.efficacy)
    val info = MedicineInformationField.entries.fold(previous.information) { value, field ->
        value.withValue(field, merge(field.label, previous.information[field], incoming.information[field]))
    }
    val names = (previous.nameOptions + incoming.nameOptions + listOf(previous.name, incoming.name)).filter(String::isNotBlank).distinct().filterNot { it == name }
    return OcrCandidates(name, expiry, efficacy, info, names, warnings.distinct(),
        (previous.tagSuggestions + incoming.tagSuggestions).distinctBy { it.tag })
}

/** Without per-photo provenance, a whole-photo retry replaces the complete unadopted candidate
 * set. It must not silently retain a wrong pre-rotation value or obsolete tag evidence. */
fun replacePhotoCandidates(incoming: OcrCandidates): OcrCandidates = incoming.copy(
    warnings = (incoming.warnings + "本次整图结果已替换全部待采用候选；其他照片可重新识别，已填写资料和历史原文保留").distinct()
)

/** Region retries are field-specific. Empty/unusable results never erase earlier candidates. */
fun mergeRegionCandidates(previous: OcrCandidates, incoming: OcrCandidates, target: String): OcrCandidates {
    val merged = when (target) {
        OCR_TARGET_NAME -> if (incoming.name.isNotBlank() || incoming.nameOptions.isNotEmpty()) previous.copy(
            name = incoming.name, nameOptions = incoming.nameOptions
        ) else previous
        OCR_TARGET_EXPIRY -> if (incoming.expiry.isNotBlank()) previous.copy(expiry = incoming.expiry) else previous
        OCR_TARGET_EFFICACY -> if (incoming.efficacy.isNotBlank()) previous.copy(efficacy = incoming.efficacy) else previous
        else -> {
            val field = MedicineInformationField.entries.firstOrNull { it.column == target }
            if (field != null && incoming.information[field].isNotBlank()) previous.copy(
                information = previous.information.withValue(field, incoming.information[field])
            ) else previous
        }
    }
    return merged.copy(warnings = (previous.warnings + incoming.warnings).distinct(), tagSuggestions = suggestOcrTags(merged))
}
