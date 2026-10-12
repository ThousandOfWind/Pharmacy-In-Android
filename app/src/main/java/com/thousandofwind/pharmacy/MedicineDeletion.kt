package com.thousandofwind.pharmacy

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
