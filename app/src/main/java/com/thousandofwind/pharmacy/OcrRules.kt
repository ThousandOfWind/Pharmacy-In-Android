package com.thousandofwind.pharmacy

/** Label normalization is for matching only. OCR values remain verbatim, including mistakes. */
private fun key(value: String): String = value.replace(Regex("\\s+"), "")
    .replace('藥', '药').replace('稱', '称').replace('規', '规').replace('劑', '剂')
    .replace('號', '号').replace('產', '产').replace('廠', '厂').replace('業', '业')
    .replace('貯', '贮').replace('儲', '储').replace('適', '适').replace('應', '应')
    .replace('與', '与').replace('備', '备').replace('註', '注')
    .replace('項', '项').replace('說', '说').replace('訂', '订')
    .replace('準', '准').replace('國', '国').replace('發', '发')

private val fieldsByLabel = mapOf(
    "通用名称" to MedicineInformationField.GENERIC_NAME, "通用名" to MedicineInformationField.GENERIC_NAME,
    "商品名称" to MedicineInformationField.BRAND_NAME, "商品名" to MedicineInformationField.BRAND_NAME,
    "品牌" to MedicineInformationField.BRAND_NAME,
    "规格" to MedicineInformationField.SPECIFICATION, "包装" to MedicineInformationField.SPECIFICATION,
    "生产厂家" to MedicineInformationField.MANUFACTURER, "生产企业" to MedicineInformationField.MANUFACTURER,
    "企业名称" to MedicineInformationField.MANUFACTURER,
    "批准文号" to MedicineInformationField.APPROVAL_NUMBER,
    "生产批号" to MedicineInformationField.BATCH_NUMBER, "批号" to MedicineInformationField.BATCH_NUMBER,
    "成分" to MedicineInformationField.INGREDIENTS, "成份" to MedicineInformationField.INGREDIENTS,
    "用法用量" to MedicineInformationField.USAGE_AND_DOSAGE,
    "禁忌" to MedicineInformationField.CONTRAINDICATIONS,
    "不良反应" to MedicineInformationField.ADVERSE_REACTIONS, "副作用" to MedicineInformationField.ADVERSE_REACTIONS,
    "注意事项" to MedicineInformationField.PRECAUTIONS,
    "药物相互作用" to MedicineInformationField.INTERACTIONS,
    "贮藏" to MedicineInformationField.STORAGE_CONDITIONS, "储藏" to MedicineInformationField.STORAGE_CONDITIONS,
    "贮藏条件" to MedicineInformationField.STORAGE_CONDITIONS,
    "说明书修订日期" to MedicineInformationField.LEAFLET_SOURCE, "修订日期" to MedicineInformationField.LEAFLET_SOURCE
)
private val stopLabels = setOf("药品名称", "性状", "汉语拼音", "英文名称", "有效期", "执行标准", "生产日期", "上市许可持有人", "生产地址", "地址", "邮政编码", "电话号码", "传真号码", "网址")
private val indicationLabels = setOf("适应症", "功能主治", "功效", "主治")
private val expiryLabels = setOf("有效期至", "有效日期", "失效日期", "失效期", "保质期至", "EXP")
private val knownLabels = fieldsByLabel.keys + stopLabels + indicationLabels + expiryLabels
private data class Section(val label: String, val text: String)

/** Bracket headings always stop the previous section, including unknown headings. */
private fun sections(text: String): List<Section> {
    val output = mutableListOf<Section>()
    var active: String? = null
    val lines = mutableListOf<String>()
    fun flush() {
        active?.let { output += Section(it, lines.joinToString("\n").trim()) }
        active = null; lines.clear()
    }
    text.lines().forEach { original ->
        val value = original.trim()
        val bracket = Regex("^[【\\[]([^】\\]]+)[】\\]]\\s*[:：]?\\s*(.*)$").find(value)
        val colon = Regex("^([^:：]{1,20})[:：]\\s*(.*)$").find(value)
        val bare = key(value).takeIf { it in knownLabels }
        val expiryHeading = Regex("^(有效期至|有效日期|失效日期|失效期|保质期至|EXP\\b)\\s*[:：]?\\s*(.*)$", RegexOption.IGNORE_CASE).find(value)
        val label = expiryHeading?.groupValues?.get(1)?.let { if (it.equals("exp", true)) "EXP" else key(it) }
            ?: bracket?.groupValues?.get(1)?.let(::key)
            ?: colon?.groupValues?.get(1)?.let(::key)?.takeIf { it in knownLabels }
            ?: bare
        if (label != null) {
            flush(); active = label
            val initial = expiryHeading?.groupValues?.get(2) ?: bracket?.groupValues?.get(2) ?: colon?.groupValues?.get(2) ?: ""
            if (initial.isNotBlank()) lines += initial
            // Unbracketed inline labels have no trustworthy paragraph boundary on a box.
            if (colon != null && bracket == null && expiryHeading == null && initial.isNotBlank()) flush()
        } else if (active != null) lines += original
    }
    flush()
    return output
}

fun extractCandidates(text: String): OcrCandidates {
    val sections = sections(text)
    val warnings = mutableListOf<String>()
    var information = MedicineInformation()
    fieldsByLabel.values.toSet().forEach { field ->
        val values = sections.filter { fieldsByLabel[it.label] == field && it.text.isNotBlank() }.map { it.text }.distinct()
        val value = when {
            field == MedicineInformationField.SPECIFICATION && values.size > 1 -> values.joinToString("；")
            values.size == 1 -> values.single()
            else -> ""
        }
        if (values.size > 1 && field != MedicineInformationField.SPECIFICATION) warnings += "${field.label}出现多个不同内容，请核对原图"
        if (value.isNotBlank()) {
            if (medicineInformationFieldError(field, value) == null) information = information.withValue(field, value)
            else warnings += "${field.label}内容需核对，未放入候选，请查看原文"
        }
    }
    // Drug-name headings may contain the common name on the following line without a colon.
    val named = sections.filter { it.label in setOf("通用名称", "通用名", "商品名称", "商品名", "药品名称") }
        .map { it.text }.filter { it.isNotBlank() && it.length <= 200 && !it.contains('\n') }.distinct()
    val hints = text.lines().map(String::trim).filter { line ->
        line.length in 3..30 && !Regex("[:：【】\\[\\]。！？,，;；0-9]").containsMatchIn(line) &&
            Regex("(?:胶囊|膠囊|片|颗粒|顆粒|錠|滴眼液|口服液|混悬液|混懸液|软膏|軟膏|喷雾|噴霧|散)$").containsMatchIn(line)
    }.distinct().take(6)
    val name = named.singleOrNull().orEmpty()
    if (named.size > 1) warnings += "药名有多个候选，可能包含不同药品，请选择当前包装"
    val expiryValues = sections.filter { it.label in expiryLabels }.mapNotNull { section ->
        val match = Regex("^(20\\d{2})[年./-](\\d{1,2})(?:[月./-](\\d{1,2}))?月?日?$", RegexOption.IGNORE_CASE).matchEntire(section.text.trim())
        match?.let { m -> "${m.groupValues[1]}-${m.groupValues[2].padStart(2, '0')}" + m.groupValues[3].takeIf(String::isNotBlank)?.let { "-${it.padStart(2, '0')}" }.orEmpty() }?.takeIf { expiryDay(it) != null }
    }.distinct()
    val expiry = expiryValues.singleOrNull().orEmpty()
    if (expiryValues.size > 1) warnings += "有效期出现多个不同日期，请核对当前包装"
    val indications = sections.filter { it.label in indicationLabels && it.text.isNotBlank() }.map { it.text }.distinct()
    val efficacy = indications.singleOrNull().orEmpty()
    if (indications.size > 1) warnings += "适应症有多个不同内容，请查看原图"
    if (MedicineInformationField.entries.any { it.group == MedicineInformationGroup.LEAFLET && information[it].isNotBlank() }) {
        warnings += "说明书可能有错字、漏字或断行；剂量、禁忌与贮藏条件必须逐项对照照片"
    }
    return OcrCandidates(name, expiry, efficacy, information, (named + hints).distinct().filterNot { it == name }, warnings.distinct())
}
