package com.thousandofwind.pharmacy

const val MAX_OCR_TEXT = 50000

/** Null means the scan cannot be retained; caller reports it instead of creating orphan candidates. */
fun appendOcrText(previous: String, current: String): String? {
    val combined = listOf(previous, current).filter(String::isNotBlank).joinToString("\n\n")
    return combined.takeIf { it.length <= MAX_OCR_TEXT }
}

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
    return merged.copy(warnings = (previous.warnings + incoming.warnings).distinct())
}
