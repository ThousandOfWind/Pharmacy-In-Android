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
    }

    @Test fun unlabelledNamesAreOptionsAndConflictingDatesAreNotSelected() {
        val c = extractCandidates("商标\n连花清瘟胶囊\n连花清瘟颗粒\n有效期至：2028-06\n有效期至：2029-06")
        assertEquals("", c.name)
        assertEquals(listOf("连花清瘟胶囊", "连花清瘟颗粒"), c.nameOptions)
        assertEquals("", c.expiry)
        assertEquals("", extractCandidates("生产日期：2026-01-01\n有效期至：见盒底\n说明书修订日期：2028-01-01").expiry)
        assertEquals("", extractCandidates("有效期至：2028-06-01 生产日期：2026-06-01").expiry)
        assertEquals("2028-06", extractCandidates("有效期至：2028年6月").expiry)
    }

    @Test fun regionSelectionClampsToPhotoAndExplicitFieldDoesNotInventDates() {
        val r = OcrRegion.between(.8f, .9f, -.1f, .2f)
        assertEquals(OcrRegion(0f, .2f, .8f, .9f), r)
        assertTrue(r.valid())
        assertFalse(OcrRegion(.2f, .2f, .2f, .5f).valid())
        assertEquals("示例品牌", regionCandidates("示例品牌", OCR_TARGET_NAME).name)
        assertEquals("2028-06", regionCandidates("2028/06", OCR_TARGET_EXPIRY).expiry)
        assertEquals("", regionCandidates("24个月", OCR_TARGET_EXPIRY).expiry)
        assertEquals("", regionCandidates("生产日期：2028-06", OCR_TARGET_EXPIRY).expiry)
        assertEquals("一次4粒", regionCandidates("一次4粒", MedicineInformationField.USAGE_AND_DOSAGE.column).information.usageAndDosage)
    }

    @Test fun applyingCandidatesOnlyFillsBlanksAndNeverSetsStockOrLocation() {
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
        val merged = mergeRegionCandidates(c, OcrCandidates(expiry = "2029-06"), OCR_TARGET_EXPIRY)
        assertEquals("候选名", merged.name)
        assertEquals("2029-06", merged.expiry)
        assertEquals(c.information, merged.information)
        assertEquals(merged, mergeRegionCandidates(merged, OcrCandidates(), OCR_TARGET_NAME))
        assertNull(appendOcrText("x".repeat(MAX_OCR_TEXT), "new"))
        assertEquals("old\n\nnew", appendOcrText("old", "new"))
    }
}
