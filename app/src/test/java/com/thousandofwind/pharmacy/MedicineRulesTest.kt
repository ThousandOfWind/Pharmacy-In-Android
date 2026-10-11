package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Focused unit coverage for the rules most likely to corrupt a medicine record. */
class MedicineRulesTest {
    @Test fun expiryKeepsMonthPrecisionAndRejectsInvalidOrUnstructuredDates() {
        assertEquals(LocalDate.of(2028, 2, 29), expiryDay("2028-02"))
        assertEquals(LocalDate.of(2027, 6, 30), expiryDay("2027-06-30"))
        listOf("", "有效期24个月", "2027-02-29", "2028-13").forEach { assertNull(expiryDay(it)) }
    }

    @Test fun expiryStatusDistinguishesUnknownExpiredAndSoonBoundaries() {
        val today = LocalDate.of(2026, 10, 10)
        assertEquals(ExpiryStatus.UNKNOWN, expiryStatus("生产日期2026-01-01", today))
        assertEquals(ExpiryStatus.EXPIRED, expiryStatus(today.minusDays(1).toString(), today))
        assertEquals(ExpiryStatus.SOON, expiryStatus(today.toString(), today))
        assertEquals(ExpiryStatus.SOON, expiryStatus(today.plusDays(90).toString(), today))
        assertEquals(ExpiryStatus.VALID, expiryStatus(today.plusDays(91).toString(), today))
    }

    @Test fun formRequiresNameAndQuantityUnitButAllowsUnknownQuantityAndZero() {
        assertNotNull(formError(" ", "", "", ""))
        assertNotNull(formError("药品", "2027-02-29", "", ""))
        assertNull(formError("药品", "", "", ""))
        assertNull(formError("药品", "2028-06", "0", "片"))
        assertEquals("请选择余量单位", quantityError("12", ""))
        listOf("-1", "1.5", "2147483648").forEach { assertNotNull(quantityError(it, "片")) }
    }

    @Test fun tagsNormalizeAndEnforceLimitsWithoutDroppingValidEntries() {
        assertEquals(listOf("外用", "Travel"), normalizeMedicineTags(listOf(" 外用 ", "", "外用", "Travel", "travel")))
        assertNull(medicineTagsError(emptyList()))
        assertNull(medicineTagsError(List(MAX_MEDICINE_TAGS) { "标签$it" } + "标签0"))
        assertNotNull(medicineTagsError(List(MAX_MEDICINE_TAGS + 1) { "标签$it" }))
        assertNull(medicineTagsError(listOf("字".repeat(MAX_TAG_LENGTH))))
        assertNotNull(medicineTagsError(listOf("字".repeat(MAX_TAG_LENGTH + 1))))
        listOf("冷藏\n保存", "冷藏\u2028保存", "冷藏\u2029保存").forEach {
            assertNotNull(medicineTagsError(listOf(it)))
        }
    }

    @Test fun tagFilterIsExactAndIntersectsTextSearch() {
        fun matches(query: String, tag: String?, tags: List<String> = listOf("外用", "Travel")) =
            matchesMedicineTextAndTag("示例药", "包装文字", "抽屉", tags, query, tag)
        assertTrue(matches("", null, emptyList())) // Legacy/untagged records remain visible.
        assertTrue(matches("travel", "TRAVEL"))
        assertTrue(matches("示例", "外用"))
        assertTrue(matches("抽屉", "外用"))
        assertFalse(matches("无匹配", "外用"))
        assertFalse(matches("", "外"))
        assertFalse(matches("示例", "外用", emptyList()))
    }

    @Test fun ocrExtractsOnlyExplicitlyLabelledCandidates() {
        val candidates = extractCandidates("商标\n通用名称：示例药片\n生产日期：2025-01-01\n有效期至：2028年6月30日\n功能主治：包装文字")
        assertEquals("示例药片", candidates.name)
        assertEquals("2028-06-30", candidates.expiry)
        assertEquals("功能主治：包装文字", candidates.efficacy)
        assertEquals("2028-06", extractCandidates("EXP 2028/06").expiry)
        assertEquals("2028-06-30", extractCandidates("有效期至：\n2028.6.30").expiry)
    }

    @Test fun ocrDoesNotGuessNameOrMistakeProductionDateForExpiry() {
        val candidates = extractCandidates("品牌标志\n某某片\n生产日期：2025-04-01\n有效期：24个月")
        assertEquals("", candidates.name)
        assertEquals("", candidates.expiry)
        assertEquals("", extractCandidates("有效期至：\n生产日期：2026-04-01").expiry)
        assertEquals("", extractCandidates("有效期至：未标注 生产日期：2026-04-01").expiry)
    }
}
