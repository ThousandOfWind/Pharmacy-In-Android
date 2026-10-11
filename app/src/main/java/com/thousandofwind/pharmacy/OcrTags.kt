package com.thousandofwind.pharmacy

/** Keep the exact evidence visible. These are suggestions, not clinical classifications. */
data class OcrTagSuggestion(val tag: String, val evidence: String)

fun suggestOcrTags(candidates: OcrCandidates): List<OcrTagSuggestion> {
    val output = mutableListOf<OcrTagSuggestion>()
    fun addFrom(text: String, rules: List<Pair<String, Regex>>) {
        // Prefer missing a suggestion over turning a prohibition or negative indication into a tag.
        val affirmative = text.split(Regex("[。；;！？!?\\n]"))
            .map(String::trim).filter { it.isNotBlank() && !Regex("不|未|无|無|非|否|免|禁|忌|勿|避免").containsMatchIn(it) }
        rules.forEach { (tag, pattern) ->
            affirmative.firstOrNull { pattern.containsMatchIn(it) }?.let { evidence ->
                if (output.none { it.tag == tag }) output += OcrTagSuggestion(tag, evidence)
            }
        }
    }
    addFrom(candidates.efficacy, listOf(
        "感冒相关" to Regex("感冒"), "退热" to Regex("退热|解热|发热|發熱"),
        "止痛" to Regex("止痛|镇痛|頭痛|头痛|牙痛|关节痛"), "过敏相关" to Regex("过敏性|過敏性|抗过敏|抗過敏"),
        "肠胃相关" to Regex("胃炎|胃痛|腹泻|腹瀉|消化不良|便秘"), "咽喉相关" to Regex("咽炎|咽喉|扁桃体炎"),
        "眼部" to Regex("结膜炎|結膜炎|角膜炎|干眼|乾眼"), "皮肤相关" to Regex("皮肤|皮膚|湿疹|濕疹|皮炎")
    ))
    addFrom(candidates.information.usageAndDosage, listOf("口服" to Regex("口服"), "外用" to Regex("外用")))
    addFrom(candidates.information.storageConditions, listOf("需冷藏" to Regex("冷藏"), "避光保存" to Regex("避光|遮光")))
    return output
}

fun applyOcrTag(draft: MedicineDraft, tag: String): MedicineDraft {
    if (draft.candidates.tagSuggestions.none { it.tag == tag }) return draft
    val tags = normalizeMedicineTags(draft.tags + tag)
    if (tags == draft.tags || medicineTagsError(tags + draft.tagInput) != null) return draft
    return draft.copy(tags = tags, reviewed = false)
}
