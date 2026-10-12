package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test

class OcrRulesTest {
    @Test fun headingsClassifyMultilineVerbatimTextAndStopAtUnknownSections() {
        val c = extractCandidates("""
            【药品名称】
            通用名称：示例胶囊
            【规 格】每粒装0.35克
            【包装】2X12粒/板/盒。
            【用法用量】口服。一次4粒，一日3次。
            【禁忌】对本品过敏者忌服。
            【注意事项】
            1.儿童请咨询医师。
            2.不要擅自增加剂量。
            【这是一个超过二十个字符的未识别章节标题也应当终止上一段】不应并入注意事项
            【贮藏】不超过0°℃（OCR原文）
            【有效期】30个月
            【说明书修订日期】2020年12月30日
            【生产企业】
            企业名称：示例药业有限公司
            生产地址：某街道
        """.trimIndent())
        assertEquals("示例胶囊", c.name)
        assertEquals("每粒装0.35克；2X12粒/板/盒。", c.information.specification)
        assertEquals("口服。一次4粒，一日3次。", c.information.usageAndDosage)
        assertEquals("对本品过敏者忌服。", c.information.contraindications)
        assertEquals("1.儿童请咨询医师。\n2.不要擅自增加剂量。", c.information.precautions)
        assertEquals("不超过0°℃（OCR原文）", c.information.storageConditions) // Never silently correct dose/temperature.
        assertEquals("示例药业有限公司", c.information.manufacturer)
        assertEquals("", c.expiry) // Duration and revision date aren't this inventory's expiry.
        assertTrue(c.warnings.isNotEmpty())
        assertEquals("", extractCandidates("【功能主治】" + "字".repeat(5001)).efficacy)
        assertEquals("", regionCandidates("字".repeat(5001), OCR_TARGET_EFFICACY).efficacy)
        val inline = extractCandidates("【注意事项】请咨询医师。【贮藏】阴凉处保存")
        assertEquals("请咨询医师。", inline.information.precautions)
        assertEquals("阴凉处保存", inline.information.storageConditions)
        val mixedWrappers = extractCandidates("[禁忌]对本品过敏者禁用。[有效期至]2028-06【注意事项】请核对包装")
        assertEquals("对本品过敏者禁用。", mixedWrappers.information.contraindications)
        assertEquals("2028-06", mixedWrappers.expiry)
        assertEquals("请核对包装", mixedWrappers.information.precautions)
    }

    @Test fun unlabelledNamesAreOptionsAndConflictingDatesAreNotSelected() {
        val c = extractCandidates("商标\n连花清瘟胶囊\n连花清瘟颗粒\n有效期至：2028-06\n有效期至：2029-06")
        assertEquals("", c.name)
        assertEquals(listOf("连花清瘟胶囊", "连花清瘟颗粒"), c.nameOptions)
        assertEquals("", c.expiry)
        assertEquals("", extractCandidates("生产日期：2026-01-01\n有效期至：见盒底\n说明书修订日期：2028-01-01").expiry)
        assertEquals("", extractCandidates("有效期至：2028-06-01 生产日期：2026-06-01").expiry)
        assertEquals("2028-06", extractCandidates("有效期至：2028年6月").expiry)
        listOf("有效期至：", "【有效期至】", "[有效期至]", "EXP ").forEach { label ->
            assertEquals("2028-06", extractCandidates("${label}2028-06\n包装文字").expiry)
        }
        assertEquals("2028-06-30", extractCandidates("有效期至：\n2028.6.30\n包装文字").expiry)
        assertTrue(extractCandidates("口服一次两片\n每日两片").nameOptions.isEmpty())
        val mixed = extractCandidates("【规格】10mg\n【规格】20mg\n【包装】6片")
        assertEquals("", mixed.information.specification)
        assertTrue(mixed.warnings.any { it.contains("规格") })
    }

    @Test fun boxColumnsAndIdentityAliasesRetainValuesWithoutGuessingDatesOrStock() {
        val c = extractCandidates("""
            商标：新红康
            规格：0.25g(25万单位)
            每盒20粒
            深圳万和制药有限公司
            药品上市许可持有人/生产企业:深圳万和制药有限公司
            产品批号:
            生产日期:
            有效期至:
            O 22603005
            20260303
            2029年02月
        """.trimIndent())
        assertEquals("新红康", c.information.brandName)
        assertEquals("0.25g(25万单位)；每盒20粒", c.information.specification)
        assertEquals("深圳万和制药有限公司", c.information.manufacturer)
        assertEquals("O 22603005", c.information.batchNumber)
        assertEquals("2029-02", c.expiry)
        assertEquals("2026-03-03", c.information.productionDate)
        assertEquals("2026-03-03", fillEmptyOcrFields(MedicineDraft(candidates = c)).information.productionDate)
        assertEquals("2025-01", fillEmptyOcrFields(MedicineDraft(information = MedicineInformation(productionDate = "2025-01"), candidates = c)).information.productionDate)
        assertEquals("2026-03-03", extractCandidates("MFG:20260303").information.productionDate)
        assertEquals("2026-03-03", regionCandidates("20260303", MedicineInformationField.PRODUCTION_DATE.column).information.productionDate)
        assertEquals("", regionCandidates("有效期至：2029年02月", MedicineInformationField.PRODUCTION_DATE.column).information.productionDate)
        assertEquals("", extractCandidates("生产日期：20260230").information.productionDate)
        assertEquals("", extractCandidates("生产日期：20260303\n生产日期：20260304").information.productionDate)
        assertEquals("2029-02", extractCandidates("生产日期：\n有效期至：\n20260303\n2029 年 02 月").expiry)
        assertEquals("2029-02", extractCandidates("|产品批号：\n｜生产日期：\nI有效期至：\nAB-2026/03\n20260303\n2029年02月").expiry)
        listOf("2029年02日", "2029年02", "2029年02月28", "2029-02日", "2029-02-28日").forEach { malformed ->
            assertEquals("", extractCandidates("有效期至：$malformed").expiry)
        }
        assertEquals("2029-02-28", extractCandidates("有效期至：2029年02月28日").expiry)
        assertEquals("2029-02-28", regionCandidates("20290228", OCR_TARGET_EXPIRY).expiry)
        assertEquals("2029-02", extractCandidates("产品批号：\n生产日期：\nexp:\nAB-2026/03\n20260303\n2029-02").expiry)
        assertEquals("2029-02", extractCandidates("产品批号：\n生产日期：\n有效期至：\nAB-2026/03\n20260303\n2029年02月").expiry)
        val holder = extractCandidates("药品上市许可持有人：不同持有人有限公司\n生产企业：实际药业有限公司")
        assertEquals("实际药业有限公司", holder.information.manufacturer)
        assertTrue(holder.warnings.any { it.contains("持有人不一定是生产厂家") })
        assertEquals("", extractCandidates("上市许可持有人：独立持有人药业有限公司").information.manufacturer)
        assertEquals("", extractCandidates("2029年02月\n生产日期：20260303").expiry)
        assertEquals("", extractCandidates("生产日期：\n有效期至：\n20260303").expiry)
        assertEquals("", extractCandidates("生产日期：\n有效期至：\n20260303\n20290230").expiry)
        assertEquals("", extractCandidates("生产日期：\n有效期至：\n20260303\n20290228\n20300101").expiry)
        assertEquals("", regionCandidates("产品批号：20290228", OCR_TARGET_EXPIRY).expiry)
        assertEquals("示例药业有限公司", extractCandidates("生产企业 示例药业有限公司").information.manufacturer)
        assertEquals("实际药业有限公司", extractCandidates("上市许可持有人／生产企业：实际药业有限公司").information.manufacturer)
        listOf("齐鲁制药有限公司", "扬子江药业集团有限公司").forEach { company ->
            assertEquals(company, extractCandidates(company).information.manufacturer)
        }
        assertEquals("示例品牌", extractCandidates("品牌名称：\n示例品牌").information.brandName)
        assertEquals("", extractCandidates("新红康\n每日20粒\n一次0.25g").information.brandName)
        assertEquals("", extractCandidates("每日20粒\n一次0.25g").information.specification)
        val draft = fillEmptyOcrFields(MedicineDraft(candidates = c))
        assertEquals(c.information, draft.information)
        assertEquals("", draft.quantity)
        assertEquals("", draft.unit)
        val assigned = assignOcrText(MedicineDraft(reviewed = true), "新红康", MedicineInformationField.BRAND_NAME.column)
        assertEquals("新红康", assigned.information.brandName)
        assertFalse(assigned.reviewed)
        assertEquals(assigned, assignOcrText(assigned, "其他品牌", MedicineInformationField.BRAND_NAME.column))
        assertEquals(MedicineDraft(), assignOcrText(MedicineDraft(), "一行\n另一行", MedicineInformationField.MANUFACTURER.column))
        assertEquals(MedicineDraft(), assignOcrText(MedicineDraft(), "生产日期：20260303", OCR_TARGET_EXPIRY))
        assertEquals("2029-02", assignOcrText(MedicineDraft(), "2029年02月", OCR_TARGET_EXPIRY).expiry)
    }

    @Test fun regionSelectionClampsToPhotoAndExplicitFieldDoesNotInventDates() {
        val r = OcrRegion.between(.8f, .9f, -.1f, .2f)
        assertEquals(OcrRegion(0f, .2f, .8f, .9f), r)
        assertTrue(r.valid())
        assertTrue(r.move(-.5f, 1f).valid())
        assertTrue(r.resize(2f).valid())
        assertTrue(r.resize(-2f).valid())
        assertFalse(OcrRegion(.2f, .2f, .2f, .5f).valid())
        assertEquals("示例品牌", regionCandidates("示例品牌", OCR_TARGET_NAME).name)
        assertEquals("2028-06", regionCandidates("2028/06", OCR_TARGET_EXPIRY).expiry)
        assertEquals("", regionCandidates("24个月", OCR_TARGET_EXPIRY).expiry)
        assertEquals("", regionCandidates("生产日期：2028-06", OCR_TARGET_EXPIRY).expiry)
        assertEquals("", regionCandidates("2028-06\n生产日期", OCR_TARGET_EXPIRY).expiry)
        assertEquals("", regionCandidates("有效期至：2028-06\n有效期至：2029-06", OCR_TARGET_EXPIRY).expiry)
        assertEquals("一次4粒", regionCandidates("一次4粒", MedicineInformationField.USAGE_AND_DOSAGE.column).information.usageAndDosage)
    }

    @Test fun tagSuggestionsRequireAffirmativeSectionEvidenceAndExplicitAdoption() {
        val c = extractCandidates("""
            药品名称：感冒止痛片
            【适应症】用于感冒引起的发热、头痛。
            【用法用量】口服。一次一片。
            【禁忌】过敏性鼻炎患者禁用，皮肤过敏者忌用。
            【贮藏】遮光保存。无需冷藏。
        """.trimIndent())
        assertEquals(listOf("感冒相关", "退热", "止痛", "口服", "避光保存"), c.tagSuggestions.map { it.tag })
        assertEquals("用于感冒引起的发热、头痛", c.tagSuggestions.first().evidence)
        assertTrue(extractCandidates("感冒止痛片\n【禁忌】过敏性鼻炎\n【注意事项】不得口服，需冷藏").tagSuggestions.isEmpty())
        assertTrue(extractCandidates("【适应症】不用于感冒。\n【用法用量】禁止外用。\n【贮藏】不需冷藏。").tagSuggestions.isEmpty())
        assertTrue(extractCandidates("【用法用量】非口服，外用。").tagSuggestions.isEmpty())
        assertTrue(extractCandidates("【贮藏】免冷藏。").tagSuggestions.isEmpty())
        val cold = extractCandidates("【贮藏】冷藏保存。\n【用法用量】外用。")
        assertEquals(listOf("外用", "需冷藏"), cold.tagSuggestions.map { it.tag })
        val draft = MedicineDraft(candidates = c, reviewed = true, tags = listOf("自定义"))
        assertEquals(listOf("自定义"), fillEmptyOcrFields(draft).tags) // Ordinary field adoption doesn't add tags.
        val tagged = applyOcrTag(draft, "口服")
        assertEquals(listOf("自定义", "口服"), tagged.tags)
        assertFalse(tagged.reviewed)
        assertEquals(tagged, applyOcrTag(tagged, "口服"))
        assertEquals(draft, applyOcrTag(draft, "家庭常备"))
        assertEquals(listOf("自定义"), toggleOcrTag(tagged, "口服").tags)
        val preexisting = draft.copy(tags = listOf("口服"))
        assertEquals(preexisting, toggleOcrTag(preexisting, "口服")) // Same text isn't proof of this control's adoption.
        val nameChoice = MedicineDraft(candidates = OcrCandidates(nameOptions = listOf("示例药")))
        val pickedName = toggleOcrName(nameChoice, "示例药")
        assertEquals("示例药", pickedName.name)
        assertEquals("", toggleOcrName(pickedName, "示例药").name)
        val primary = MedicineDraft(candidates = OcrCandidates(name = "主药名"))
        val filledPrimary = fillEmptyOcrFields(primary)
        assertEquals("主药名", filledPrimary.ocrSelectedName)
        assertEquals("", toggleOcrName(filledPrimary, "主药名").name)
        assertEquals("主药名", toggleOcrName(primary, "主药名").name)
        val existingPrimary = primary.copy(name = "主药名")
        assertEquals(existingPrimary, fillEmptyOcrFields(existingPrimary))
        assertEquals(existingPrimary, toggleOcrName(existingPrimary, "主药名"))
        val manualName = nameChoice.copy(name = "示例药")
        assertEquals(manualName, toggleOcrName(manualName, "示例药"))
        assertEquals(pickedName.copy(ocrSelectedName = ""), toggleOcrName(pickedName.copy(ocrSelectedName = ""), "示例药"))
        val full = draft.copy(tags = (1..8).map { "标签$it" })
        assertEquals(full, applyOcrTag(full, "口服"))
        assertTrue(mergeRegionCandidates(c, OcrCandidates(information = MedicineInformation(storageConditions = "常温保存")),
            MedicineInformationField.STORAGE_CONDITIONS.column).tagSuggestions.none { it.tag == "避光保存" })
        assertTrue(mergePhotoCandidates(c, cold).tagSuggestions.any { it.tag == "需冷藏" })
    }

    @Test fun applyingCandidatesOnlyFillsBlanksAndNeverSetsStockOrLocation() {
        val saved = MedicineDraft(id = 1, rawText = "历史原文", reviewed = true)
        assertFalse(hasCurrentOcrResults(saved, saved))
        assertFalse(requiresOcrConfirmation(saved, saved))
        assertTrue(showOcrConfirmation(saved, saved)) // Checking doesn't remove the control.
        assertTrue(showOcrConfirmation(saved.copy(reviewed = false), saved))
        assertTrue(requiresOcrConfirmation(saved.copy(reviewed = false), saved))
        val photoOnly = MedicineDraft(id = 1, photos = listOf("photo"), reviewed = true)
        assertTrue(requiresOcrConfirmation(photoOnly.copy(photos = listOf("photo", "new"), reviewed = false), photoOnly))
        assertTrue(requiresOcrConfirmation(photoOnly.copy(photos = listOf("rotated"), reviewed = false), photoOnly))
        assertTrue(requiresOcrConfirmation(photoOnly.copy(photos = emptyList(), reviewed = false), photoOnly))
        assertFalse(requiresOcrConfirmation(photoOnly.copy(photos = emptyList(), reviewed = true), photoOnly))
        assertTrue(hasCurrentOcrResults(saved.copy(rawText = "历史原文\n新识别"), saved))
        assertTrue(hasCurrentOcrResults(saved.copy(candidates = OcrCandidates(expiry = "2029-02")), saved))
        val c = OcrCandidates(name = "候选名", expiry = "2028-06", efficacy = "包装原文",
            information = MedicineInformation(brandName = "候选品牌", specification = "0.35g", usageAndDosage = "一次4粒", storageLocation = "不能推断位置"))
        val draft = MedicineDraft(name = "手工名称", quantity = "3", unit = "盒", reviewed = true,
            information = MedicineInformation(brandName = "手工品牌"), candidates = c)
        val filled = fillEmptyOcrFields(draft)
        assertEquals("手工名称", filled.name)
        assertEquals("手工品牌", filled.information.brandName)
        assertEquals("0.35g", filled.information.specification)
        assertEquals("", filled.information.usageAndDosage)
        assertEquals("", filled.information.storageLocation)
        assertEquals("3", filled.quantity)
        assertEquals("盒", filled.unit)
        assertFalse(filled.reviewed)
        val reviewed = filled.copy(reviewed = true)
        assertEquals(reviewed, fillEmptyOcrFields(reviewed))
        val merged = mergeRegionCandidates(c, OcrCandidates(expiry = "2029-06"), OCR_TARGET_EXPIRY)
        assertEquals("候选名", merged.name)
        assertEquals("2029-06", merged.expiry)
        val retried = replacePhotoCandidates(OcrCandidates(expiry = "2030-06"))
        assertEquals("2030-06", retried.expiry)
        assertEquals("", retried.name)
        assertTrue(retried.tagSuggestions.isEmpty())
        assertEquals("", replacePhotoCandidates(OcrCandidates()).expiry)
        assertEquals(c.information, merged.information)
        assertEquals(merged, mergeRegionCandidates(merged, OcrCandidates(), OCR_TARGET_NAME))
        assertNull(appendOcrText("x".repeat(MAX_OCR_TEXT), "new"))
        assertEquals("old\n\nnew", appendOcrText("old", "new"))
        val extraPhoto = mergePhotoCandidates(c, OcrCandidates(expiry = "2029-06", information = MedicineInformation(manufacturer = "示例厂家")))
        assertEquals("候选名", extraPhoto.name)
        assertEquals(c.information.usageAndDosage, extraPhoto.information.usageAndDosage)
        assertEquals("示例厂家", extraPhoto.information.manufacturer)
        assertTrue(extraPhoto.warnings.any { it.contains("有效期") })
    }
}
