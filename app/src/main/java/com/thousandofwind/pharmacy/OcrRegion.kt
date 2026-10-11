package com.thousandofwind.pharmacy

import kotlin.math.max
import kotlin.math.min

/** Fractions refer to the upright, displayed photo, never to the surrounding letterbox. */
data class OcrRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid(): Boolean = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        right - left >= .01f && bottom - top >= .01f

    companion object {
        fun between(x1: Float, y1: Float, x2: Float, y2: Float) = OcrRegion(
            min(x1, x2).coerceIn(0f, 1f), min(y1, y2).coerceIn(0f, 1f),
            max(x1, x2).coerceIn(0f, 1f), max(y1, y2).coerceIn(0f, 1f)
        )
    }
}

const val OCR_TARGET_NAME = "name"
const val OCR_TARGET_EXPIRY = "expiry"
const val OCR_TARGET_EFFICACY = "efficacy"

/** The user's explicit field choice disambiguates the crop, but never repairs its text. */
fun regionCandidates(text: String, target: String): OcrCandidates {
    val raw = text.trim()
    val parsed = extractCandidates(raw)
    val warnings = listOf("框选识别仍可能有错字或漏字，请对照原图核对")
    return when (target) {
        OCR_TARGET_NAME -> {
            val value = parsed.name.ifBlank {
                raw.takeIf { it.isNotBlank() && it.length <= 200 && !it.contains('\n') }.orEmpty()
            }
            OcrCandidates(name = value, nameOptions = if (value.isEmpty()) parsed.nameOptions else emptyList(), warnings = warnings)
        }
        OCR_TARGET_EXPIRY -> {
            // Only an unambiguous literal date, or explicitly labelled expiry. Never duration/MFG.
            val literal = extractCandidates("有效期至：$raw").expiry
            OcrCandidates(expiry = parsed.expiry.ifBlank { literal }, warnings = warnings)
        }
        OCR_TARGET_EFFICACY -> {
            val value = parsed.efficacy.ifBlank { raw }
            if (value.length > 5000) OcrCandidates(warnings = warnings + "适应症内容超过5000字符，请查看原文")
            else OcrCandidates(efficacy = value, warnings = warnings)
        }
        else -> {
            val field = MedicineInformationField.entries.firstOrNull { it.column == target && it != MedicineInformationField.STORAGE_LOCATION }
                ?: return OcrCandidates(warnings = listOf("无法归类此框选，请查看原文"))
            val value = parsed.information[field].ifBlank { raw }
            if (medicineInformationFieldError(field, value) != null) OcrCandidates(warnings = warnings + "${field.label}内容需手动核对")
            else OcrCandidates(information = MedicineInformation().withValue(field, value), warnings = warnings)
        }
    }
}
