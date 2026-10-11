package com.thousandofwind.pharmacy.ai

import androidx.room.ColumnInfo

/** Room reads only fields the user can explicitly share; no notes/OCR/leaflet/photo materialization. */
data class AiMedicineRecord(
    val id: Long, val name: String,
    @ColumnInfo(name = "expiry_date") val expiryDate: String,
    @ColumnInfo(name = "remaining_quantity") val remainingQuantity: Int?,
    @ColumnInfo(name = "quantity_unit") val quantityUnit: String,
    @ColumnInfo(name = "info_brand_name") val brandName: String,
    @ColumnInfo(name = "info_generic_name") val genericName: String,
    @ColumnInfo(name = "info_specification") val specification: String,
    @ColumnInfo(name = "info_storage_location") val storageLocation: String
)
