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
    "品牌" to MedicineInformationField.BRAND_NAME, "商标" to MedicineInformationField.BRAND_NAME,
    "注册商标" to MedicineInformationField.BRAND_NAME, "品牌名称" to MedicineInformationField.BRAND_NAME,
    "规格" to MedicineInformationField.SPECIFICATION, "包装" to MedicineInformationField.SPECIFICATION,
    "生产厂家" to MedicineInformationField.MANUFACTURER, "生产企业" to MedicineInformationField.MANUFACTURER,
    "企业名称" to MedicineInformationField.MANUFACTURER, "厂家" to MedicineInformationField.MANUFACTURER,
    "制造商" to MedicineInformationField.MANUFACTURER, "生产商" to MedicineInformationField.MANUFACTURER,
    "药品上市许可持有人/生产企业" to MedicineInformationField.MANUFACTURER,
    "药品上市许可持有人／生产企业" to MedicineInformationField.MANUFACTURER,
    "上市许可持有人/生产企业" to MedicineInformationField.MANUFACTURER,
    "上市许可持有人／生产企业" to MedicineInformationField.MANUFACTURER,
    "批准文号" to MedicineInformationField.APPROVAL_NUMBER,
    "生产批号" to MedicineInformationField.BATCH_NUMBER, "产品批号" to MedicineInformationField.BATCH_NUMBER,
    "批号" to MedicineInformationField.BATCH_NUMBER,
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
private val stopLabels = setOf("药品名称", "性状", "汉语拼音", "英文名称", "有效期", "执行标准", "生产日期", "上市许可持有人", "药品上市许可持有人", "生产地址", "地址", "邮政编码", "电话号码", "传真号码", "网址")
private val indicationLabels = setOf("适应症", "功能主治", "功效", "主治")
private val expiryLabels = setOf("有效期至", "有效日期", "失效日期", "失效期", "保质期至", "EXP")
private val knownLabels = fieldsByLabel.keys + stopLabels + indicationLabels + expiryLabels
private data class Section(val label: String, val text: String)

/** A literal date only: compact printing and spaces are common, but batch/MFG context is not a date. */
fun parseOcrDate(text: String): String? {
    val value = text.replace(Regex("\\s+"), "")
    val compact = Regex("^(20\\d{2})(\\d{2})(\\d{2})$").matchEntire(value)
    val chinese = Regex("^(20\\d{2})年(\\d{1,2})月(?:(\\d{1,2})日)?$").matchEntire(value)
    val separated = Regex("^(20\\d{2})[./-](\\d{1,2})(?:[./-](\\d{1,2}))?$").matchEntire(value)
    val match = compact ?: chinese ?: separated ?: return null
    val date = "${match.groupValues[1]}-${match.groupValues[2].padStart(2, '0')}" +
        match.groupValues[3].takeIf(String::isNotBlank)?.let { "-${it.padStart(2, '0')}" }.orEmpty()
    return date.takeIf { expiryDay(it) != null }
}

/** Some OCR blocks read the entire label column before the value column. Only pair a complete,
 * typed batch/MFG/expiry group; an incomplete group must not bind its first value to expiry. */
private fun alignDateColumns(text: String): String {
    val columnLabels = setOf("产品批号", "生产批号", "批号", "生产日期") + expiryLabels
    fun label(line: String): String {
        val normalized = key(line.trim().replace(Regex("^[|｜I]"), "").removeSuffix(":").removeSuffix("："))
        return if (normalized.equals("EXP", true)) "EXP" else normalized
    }
    val input = text.lines()
    val output = mutableListOf<String>()
    var i = 0
    while (i < input.size) {
        val labels = input.drop(i).takeWhile { label(it) in columnLabels }.map(::label)
        if (labels.size >= 2 && labels.any { it in expiryLabels }) {
            val values = input.drop(i + labels.size).take(labels.size).map(String::trim)
            val complete = labels.distinct().size == labels.size && values.size == labels.size &&
                labels.zip(values).all { (heading, value) ->
                    if (heading == "生产日期" || heading in expiryLabels) parseOcrDate(value) != null
                    else Regex("[A-Za-z0-9][A-Za-z0-9 ./_-]{0,39}").matches(value)
                } && parseOcrDate(input.getOrNull(i + labels.size * 2).orEmpty()) == null
            if (complete) {
                output += labels.zip(values).map { (heading, value) -> "$heading：$value" }
                i += labels.size * 2
            } else {
                output += input.subList(i, i + labels.size)
                output += "【分列日期待核对】" // Stop expiry from taking the batch or manufacturing value.
                i += labels.size
            }
        } else {
            output += input[i++]
        }
    }
    return output.joinToString("\n")
}

/** Bracket headings always stop the previous section, including unknown headings. */
private fun sections(text: String): List<Section> {
    val output = mutableListOf<Section>()
    var active: String? = null
    var singleValue = false
    val lines = mutableListOf<String>()
    fun flush() {
        active?.let { output += Section(it, lines.joinToString("\n").trim()) }
        active = null; singleValue = false; lines.clear()
    }
    val heading = Regex("[【\\[][^】\\]]+[】\\]]")
    val sourceLines = alignDateColumns(text).lines().flatMap { original ->
        val starts = (listOf(0) + heading.findAll(original).map { it.range.first }.toList() + original.length).distinct().sorted()
        starts.zipWithNext { start, end -> original.substring(start, end) }
    }
    sourceLines.forEach { original ->
        val value = original.trim()
        val bracket = Regex("^[【\\[]([^】\\]]+)[】\\]]\\s*[:：]?\\s*(.*)$").find(value)
        val colon = Regex("^[|｜I]?([^:：]{1,40})[:：]\\s*(.*)$").find(value)
        val bare = key(value).takeIf { it in knownLabels }
        // Missing colons on short identity headings are common; keep the value verbatim.
        val inlineIdentity = fieldsByLabel.keys.filter { fieldsByLabel[it]?.group == MedicineInformationGroup.IDENTITY }
            .sortedByDescending(String::length).firstNotNullOfOrNull { heading ->
                Regex("^" + heading.map { Regex.escape(it.toString()) }.joinToString("\\s*") + "\\s+(.+)$")
                    .find(value)?.let { heading to it.groupValues[1] }
            }
        val expiryHeading = Regex("^(有效期至|有效日期|失效日期|失效期|保质期至|EXP\\b)\\s*[:：]?\\s*(.*)$", RegexOption.IGNORE_CASE).find(value)
        val label = expiryHeading?.groupValues?.get(1)?.let { if (it.equals("exp", true)) "EXP" else key(it) }
            ?: bracket?.groupValues?.get(1)?.let(::key)
            ?: colon?.groupValues?.get(1)?.let(::key)?.takeIf { it in knownLabels }
            ?: bare ?: inlineIdentity?.first
        if (label != null) {
            flush(); active = label
            singleValue = label in expiryLabels || fieldsByLabel[label]?.singleLine == true || colon != null && bracket == null
            val initial = expiryHeading?.groupValues?.get(2) ?: bracket?.groupValues?.get(2) ?: colon?.groupValues?.get(2) ?: inlineIdentity?.second ?: ""
            if (initial.isNotBlank()) lines += initial
            // Unbracketed inline labels have no trustworthy paragraph boundary on a box.
            if (singleValue && initial.isNotBlank()) flush()
        } else if (active == null && Regex("每[盒瓶板袋支]\\s*\\d+(?:\\.\\d+)?\\s*(?:粒|片|袋|支|毫升|ml|mL)(?:[。.]|[/／][盒瓶板袋支])?").matches(value)) {
            output += Section("包装", value)
        } else if (active == null && Regex("[\\p{IsHan}·（）()]{2,60}(?:制药|药业|医药)[\\p{IsHan}·（）()]{0,20}公司").matches(value)) {
            output += Section("生产厂家", value)
        } else if (active != null && value.isNotBlank()) {
            lines += original
            if (singleValue) flush()
        }
    }
    flush()
    return output
}

fun extractCandidates(text: String): OcrCandidates {
    val sections = sections(text)
    val warnings = mutableListOf<String>()
    var information = MedicineInformation()
    if (sections.any { it.label in setOf("上市许可持有人", "药品上市许可持有人") && it.text.isNotBlank() }) {
        warnings += "已识别上市许可持有人，但持有人不一定是生产厂家，请查看原文并核对生产企业后填写"
    }
    fieldsByLabel.values.toSet().forEach { field ->
        val matches = sections.filter { fieldsByLabel[it.label] == field && it.text.isNotBlank() }
        val values = matches.map { it.text }.distinct()
        val conflicting = if (field == MedicineInformationField.SPECIFICATION)
            matches.groupBy { it.label }.values.any { group -> group.map { it.text }.distinct().size > 1 }
        else values.size > 1
        val value = when {
            conflicting -> ""
            field == MedicineInformationField.SPECIFICATION -> values.joinToString("；")
            values.size == 1 -> values.single()
            else -> ""
        }
        if (conflicting) warnings += "${field.label}出现多个不同内容，请核对原图"
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
            !Regex("口服|服用|一次|每次|一日|每日|外用|吞服|嚼服").containsMatchIn(line) &&
            Regex("(?:胶囊|膠囊|片|颗粒|顆粒|錠|滴眼液|口服液|混悬液|混懸液|软膏|軟膏|喷雾|噴霧|散)$").containsMatchIn(line)
    }.distinct().take(6)
    val name = named.singleOrNull().orEmpty()
    if (named.size > 1) warnings += "药名有多个候选，可能包含不同药品，请选择当前包装"
    val expiryValues = sections.filter { it.label in expiryLabels }.mapNotNull { section ->
        parseOcrDate(section.text)
    }.distinct()
    val expiry = expiryValues.singleOrNull().orEmpty()
    if (expiryValues.size > 1) warnings += "有效期出现多个不同日期，请核对当前包装"
    val indications = sections.filter { it.label in indicationLabels && it.text.isNotBlank() }.map { it.text }.distinct()
    val indication = indications.singleOrNull().orEmpty()
    val efficacy = indication.takeIf { it.length <= 5000 }.orEmpty()
    if (indication.length > 5000) warnings += "适应症内容超过5000字符，未生成候选，请查看原文"
    if (indications.size > 1) warnings += "适应症有多个不同内容，请查看原图"
    if (MedicineInformationField.entries.any { it.group == MedicineInformationGroup.LEAFLET && information[it].isNotBlank() }) {
        warnings += "说明书可能有错字、漏字或断行；剂量、禁忌与贮藏条件必须逐项对照照片"
    }
    val candidates = OcrCandidates(name, expiry, efficacy, information, (named + hints).distinct().filterNot { it == name }, warnings.distinct())
    return candidates.copy(tagSuggestions = suggestOcrTags(candidates))
}
