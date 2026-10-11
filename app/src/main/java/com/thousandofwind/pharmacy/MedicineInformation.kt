package com.thousandofwind.pharmacy

import androidx.room.ColumnInfo

enum class MedicineInformationGroup { IDENTITY, LEAFLET }

/** Stable column names also identify draft fields; these are user records, never inferred advice. */
enum class MedicineInformationField(
    val column: String, val label: String, val group: MedicineInformationGroup,
    val singleLine: Boolean = true, val maxLength: Int = 200, val hint: String = ""
) {
    GENERIC_NAME("generic_name", "通用名", MedicineInformationGroup.IDENTITY),
    BRAND_NAME("brand_name", "品牌 / 商品名", MedicineInformationGroup.IDENTITY),
    SPECIFICATION("specification", "规格 / 包装", MedicineInformationGroup.IDENTITY, hint = "例如 0.25 g × 24 粒 / 盒；不等于一次服用量。"),
    MANUFACTURER("manufacturer", "生产厂家", MedicineInformationGroup.IDENTITY),
    APPROVAL_NUMBER("approval_number", "批准文号", MedicineInformationGroup.IDENTITY),
    BATCH_NUMBER("batch_number", "生产批号", MedicineInformationGroup.IDENTITY),
    STORAGE_LOCATION("storage_location", "存放位置", MedicineInformationGroup.IDENTITY, hint = "例如卧室抽屉；与说明书要求的贮藏条件不同。"),
    INGREDIENTS("ingredients", "成分", MedicineInformationGroup.LEAFLET, false, 5000),
    USAGE_AND_DOSAGE("usage_and_dosage", "用法用量", MedicineInformationGroup.LEAFLET, false, 5000),
    CONTRAINDICATIONS("contraindications", "禁忌", MedicineInformationGroup.LEAFLET, false, 5000),
    ADVERSE_REACTIONS("adverse_reactions", "不良反应 / 可能的副作用", MedicineInformationGroup.LEAFLET, false, 5000),
    PRECAUTIONS("precautions", "注意事项", MedicineInformationGroup.LEAFLET, false, 5000),
    INTERACTIONS("interactions", "药物相互作用", MedicineInformationGroup.LEAFLET, false, 5000),
    STORAGE_CONDITIONS("storage_conditions", "贮藏条件", MedicineInformationGroup.LEAFLET, false, 5000),
    LEAFLET_SOURCE("leaflet_source", "说明书来源 / 修订日期", MedicineInformationGroup.LEAFLET, false, 500,
        "例如随盒说明书及其标注的修订日期；不同厂家、规格的说明书不要混用。")
}

/** Embedded Room columns keep each optional field explicit and preserve existing record data. */
data class MedicineInformation(
    @ColumnInfo(name = "generic_name", defaultValue = "''") val genericName: String = "",
    @ColumnInfo(name = "brand_name", defaultValue = "''") val brandName: String = "",
    @ColumnInfo(name = "specification", defaultValue = "''") val specification: String = "",
    @ColumnInfo(name = "manufacturer", defaultValue = "''") val manufacturer: String = "",
    @ColumnInfo(name = "approval_number", defaultValue = "''") val approvalNumber: String = "",
    @ColumnInfo(name = "batch_number", defaultValue = "''") val batchNumber: String = "",
    @ColumnInfo(name = "storage_location", defaultValue = "''") val storageLocation: String = "",
    @ColumnInfo(name = "ingredients", defaultValue = "''") val ingredients: String = "",
    @ColumnInfo(name = "usage_and_dosage", defaultValue = "''") val usageAndDosage: String = "",
    @ColumnInfo(name = "contraindications", defaultValue = "''") val contraindications: String = "",
    @ColumnInfo(name = "adverse_reactions", defaultValue = "''") val adverseReactions: String = "",
    @ColumnInfo(name = "precautions", defaultValue = "''") val precautions: String = "",
    @ColumnInfo(name = "interactions", defaultValue = "''") val interactions: String = "",
    @ColumnInfo(name = "storage_conditions", defaultValue = "''") val storageConditions: String = "",
    @ColumnInfo(name = "leaflet_source", defaultValue = "''") val leafletSource: String = ""
) {
    operator fun get(field: MedicineInformationField): String = when (field) {
        MedicineInformationField.GENERIC_NAME -> genericName
        MedicineInformationField.BRAND_NAME -> brandName
        MedicineInformationField.SPECIFICATION -> specification
        MedicineInformationField.MANUFACTURER -> manufacturer
        MedicineInformationField.APPROVAL_NUMBER -> approvalNumber
        MedicineInformationField.BATCH_NUMBER -> batchNumber
        MedicineInformationField.STORAGE_LOCATION -> storageLocation
        MedicineInformationField.INGREDIENTS -> ingredients
        MedicineInformationField.USAGE_AND_DOSAGE -> usageAndDosage
        MedicineInformationField.CONTRAINDICATIONS -> contraindications
        MedicineInformationField.ADVERSE_REACTIONS -> adverseReactions
        MedicineInformationField.PRECAUTIONS -> precautions
        MedicineInformationField.INTERACTIONS -> interactions
        MedicineInformationField.STORAGE_CONDITIONS -> storageConditions
        MedicineInformationField.LEAFLET_SOURCE -> leafletSource
    }

    fun withValue(field: MedicineInformationField, value: String): MedicineInformation = when (field) {
        MedicineInformationField.GENERIC_NAME -> copy(genericName = value)
        MedicineInformationField.BRAND_NAME -> copy(brandName = value)
        MedicineInformationField.SPECIFICATION -> copy(specification = value)
        MedicineInformationField.MANUFACTURER -> copy(manufacturer = value)
        MedicineInformationField.APPROVAL_NUMBER -> copy(approvalNumber = value)
        MedicineInformationField.BATCH_NUMBER -> copy(batchNumber = value)
        MedicineInformationField.STORAGE_LOCATION -> copy(storageLocation = value)
        MedicineInformationField.INGREDIENTS -> copy(ingredients = value)
        MedicineInformationField.USAGE_AND_DOSAGE -> copy(usageAndDosage = value)
        MedicineInformationField.CONTRAINDICATIONS -> copy(contraindications = value)
        MedicineInformationField.ADVERSE_REACTIONS -> copy(adverseReactions = value)
        MedicineInformationField.PRECAUTIONS -> copy(precautions = value)
        MedicineInformationField.INTERACTIONS -> copy(interactions = value)
        MedicineInformationField.STORAGE_CONDITIONS -> copy(storageConditions = value)
        MedicineInformationField.LEAFLET_SOURCE -> copy(leafletSource = value)
    }

    fun trimmed(): MedicineInformation = MedicineInformationField.entries.fold(this) { info, field ->
        info.withValue(field, info[field].trim())
    }

    fun searchableValues(): List<String> = MedicineInformationField.entries
        .filter { it.group == MedicineInformationGroup.IDENTITY }.map { this[it] }
}

fun medicineInformationFieldError(field: MedicineInformationField, value: String): String? = when {
    value.trim().length > field.maxLength -> "${field.label}最多 ${field.maxLength} 个字符，请精简填写并另行保管完整说明书"
    field.singleLine && value.trim().any { it.isISOControl() || it == '\u2028' || it == '\u2029' } -> "${field.label}不能包含换行或控制字符"
    else -> null
}

fun medicineInformationError(info: MedicineInformation): String? = MedicineInformationField.entries
    .firstNotNullOfOrNull { medicineInformationFieldError(it, info[it]) }
