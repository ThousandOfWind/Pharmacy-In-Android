package com.thousandofwind.pharmacy

internal data class DeletedBatch(
    val token: String, val records: List<MedicineEntity>, val windowClosed: Boolean = false
) {
    // Expiry closes the logical window before deferred physical cleanup releases its snapshot.
    val undoToken: String? get() = token.takeUnless { windowClosed }
}

/** Selection is always scoped to visible individual inventory records, never an entire group. */
fun selectedVisibleMedicines(visible: List<MedicineEntity>, selectedIds: Set<Long>): List<MedicineEntity> =
    visible.filter { it.id in selectedIds }

/** Resolve the confirmed IDs from a fresh transaction snapshot; fail rather than partially delete. */
fun deletionTargets(records: List<MedicineEntity>, ids: Set<Long>): List<MedicineEntity> {
    require(ids.isNotEmpty() && ids.all { it > 0 }) { "请选择要删除的库存记录" }
    val targets = records.filter { it.id in ids }
    require(targets.size == ids.size) { "部分记录已变化，请重新选择后确认" }
    return targets
}

/** Expiry/dismissal closes a window; replacing its text with a same-batch warning does not. */
fun shouldFinishUndoWindow(closing: UiNotice, newest: UiNotice?, completed: Boolean = false): Boolean =
    closing.undoToken != null && (completed || newest === closing || newest?.undoToken != closing.undoToken)
