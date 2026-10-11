package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test

class MedicineGroupsTest {
    private fun record(id: Long, group: String = "", name: String = "", expiry: String = "") = MedicineEntity(
        id, "示例药", expiry, "", "", 1, "", "", id, groupKey = group, groupName = name
    )

    @Test fun explicitGroupsKeepSameNamesAndEveryExpiryIndependent() {
        val key = "12345678-1234-1234-1234-123456789012"
        val items = listOf(record(1), record(2), record(3, key, "我的药组", "2028-06"), record(4, key, "我的药组", "2027-06"))
        val groups = cabinetGroups(items)
        assertEquals(3, groups.size)
        assertEquals(2, groups.last().records.size)
        assertEquals(listOf(4L, 3L), sortedGroupStocks(groups.last().records).map { it.id })
        assertEquals(listOf(4L, 3L, 5L), sortedGroupStocks(groups.last().records + record(5, key, "我的药组")).map { it.id })
        assertNull(groupNameError("氯雷他定片"))
        assertNotNull(groupNameError("\n"))
    }

    @Test fun backupGroupsPreserveIdentityAndRejectConflictingNames() {
        val key = "12345678-1234-1234-1234-123456789012"
        val other = "12345678-1234-1234-1234-123456789013"
        val input = listOf(BackupRecord("药A", groupKey = key, groupName = "药组A"), BackupRecord("药B", groupKey = other, groupName = "药组A"))
        assertEquals(input, resolveBackupGroups(input, emptyMap()))
        assertEquals(input, resolveBackupGroups(input, mapOf(key to "药组A")))
        assertNotEquals(input.first().groupKey, input.last().groupKey)
        assertEquals(BackupDocument(1, input), decodeBackup(encodeBackup(BackupDocument(1, input))))
        try {
            resolveBackupGroups(input, mapOf(key to "其他组"))
            fail("Conflicting group must be rejected, not remapped into another group")
        } catch (_: IllegalArgumentException) { }
    }
}
