package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test

class MedicineDeletionTest {
    private fun record(id: Long, group: String = "") = MedicineEntity(
        id, "同名药", "", "", "", 1, "", "", id, groupKey = group, groupName = "药品组"
    )

    @Test fun selectionOnlyIncludesVisibleIndividualRecordsNotHiddenGroupMembers() {
        val visible = listOf(record(1, "same-group"), record(3))
        assertEquals(listOf(1L), selectedVisibleMedicines(visible, setOf(1, 2, 99)).map { it.id })
        assertEquals(visible, selectedVisibleMedicines(visible, visible.map { it.id }.toSet()))
        assertTrue(selectedVisibleMedicines(visible, emptySet()).isEmpty())
        assertTrue(selectedVisibleMedicines(emptyList(), setOf(1)).isEmpty())
    }

    @Test fun deletionRequiresAllConfirmedIdsAndUsesFreshCompleteRecords() {
        val first = record(1, "same-group").copy(notes = "新备注", remainingQuantity = 8)
        val second = record(2, "same-group")
        val third = record(3)
        val records = listOf(first, second, third)
        assertEquals(listOf(first, third), deletionTargets(records, setOf(1, 3)))
        assertEquals(listOf(first), deletionTargets(records, setOf(1)))
        for (invalid in listOf(emptySet(), setOf(0L), setOf(-1L), setOf(1L, 99L))) {
            try {
                deletionTargets(records, invalid)
                fail("Empty, invalid or stale batches must fail, never silently delete a subset")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
