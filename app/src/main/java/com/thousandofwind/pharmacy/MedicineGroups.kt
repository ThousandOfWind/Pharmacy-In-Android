package com.thousandofwind.pharmacy

import java.util.Locale

data class CabinetGroup(val key: String, val name: String, val records: List<MedicineEntity>)

/** Group only explicit associations. Identical medicine names do not imply interchangeable products. */
fun cabinetGroups(items: List<MedicineEntity>): List<CabinetGroup> = items.groupBy {
    if (it.groupKey.isBlank()) "record:${it.id}" else "group:${it.groupKey}"
}.map { (key, rows) -> CabinetGroup(key, rows.first().groupName.takeIf { rows.first().groupKey.isNotBlank() }.orEmpty().ifBlank { rows.first().name }, rows) }

fun validGroupKey(value: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)

fun groupNameError(value: String): String? = when {
    value.isBlank() -> "请填写药品组名称"
    value.trim().length > 80 || value.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } -> "药品组名称最多80字符，不能换行"
    else -> null
}

fun sameMedicineHint(draft: MedicineDraft, item: MedicineEntity): Boolean {
    val generic = draft.information.genericName.trim()
    return generic.isNotBlank() && generic.equals(item.information.genericName.trim(), true) &&
        draft.information.brandName.isNotBlank() && draft.information.specification.isNotBlank() &&
        draft.information.brandName.trim().equals(item.information.brandName.trim(), true) &&
        draft.information.specification.trim().equals(item.information.specification.trim(), true)
}

fun sortedGroupStocks(items: List<MedicineEntity>): List<MedicineEntity> = items.sortedWith(
    compareBy<MedicineEntity> { expiryDay(it.expiryDate) == null }.thenBy { expiryDay(it.expiryDate) }.thenBy { it.id }
)

fun groupBrandCount(items: List<MedicineEntity>): Int = items.map { it.information.brandName.trim().lowercase(Locale.ROOT) }
    .filter(String::isNotBlank).distinct().size
