package com.thousandofwind.pharmacy

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "medicines")
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "expiry_date") val expiryDate: String,
    val efficacy: String,
    val notes: String,
    @ColumnInfo(name = "remaining_quantity") val remainingQuantity: Int?,
    @ColumnInfo(name = "photo_uri") val photoUri: String,
    @ColumnInfo(name = "ocr_raw_text") val ocrRawText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "quantity_unit", defaultValue = "''") val quantityUnit: String = "",
    @ColumnInfo(name = "photos_json", defaultValue = "'[]'") val photosJson: String = "[]",
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(name = "tags_json", defaultValue = "'[]'") val tagsJson: String = "[]",
    @Embedded(prefix = "info_") val information: MedicineInformation = MedicineInformation(),
    @ColumnInfo(name = "group_key", defaultValue = "''") val groupKey: String = "",
    @ColumnInfo(name = "group_name", defaultValue = "''") val groupName: String = ""
) {
    fun tags(): List<String> = decodeTags(tagsJson)

    fun photos(): List<String> = decodePhotos(photosJson).ifEmpty {
        listOf(photoUri).filter(String::isNotBlank)
    }
}

fun encodePhotos(photos: List<String>): String = JSONArray(photos).toString()
fun decodePhotos(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).map { array.getString(it) }.filter(String::isNotBlank)
}.getOrDefault(emptyList())

fun encodeTags(tags: List<String>): String = JSONArray(normalizeMedicineTags(tags)).toString()
fun decodeTags(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    normalizeMedicineTags((0 until array.length()).mapNotNull { array.opt(it) as? String })
}.getOrDefault(emptyList())

@Dao
interface MedicineDao {
    @Query("SELECT * FROM medicines ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicines")
    suspend fun snapshot(): List<MedicineEntity>

    @Query("SELECT id, name, expiry_date, remaining_quantity, quantity_unit, info_brand_name, info_generic_name, info_specification, info_storage_location FROM medicines ORDER BY created_at DESC, id DESC")
    suspend fun aiSnapshot(): List<com.thousandofwind.pharmacy.ai.AiMedicineRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MedicineEntity): Long

    @Query("UPDATE medicines SET group_key = :key, group_name = :name WHERE id = :id")
    suspend fun associateGroup(id: Long, key: String, name: String)

    @Query("DELETE FROM medicines WHERE id IN (:ids)")
    suspend fun deleteRecords(ids: List<Long>)

    // Undo must not overwrite a record independently restored or edited since deletion.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreRecords(records: List<MedicineEntity>)
}

@Database(entities = [MedicineEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN quantity_unit TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE medicines ADD COLUMN photos_json TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE medicines ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE medicines SET updated_at = created_at")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN tags_json TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep this historical column list fixed even if future versions add more fields.
                listOf(
                    "generic_name", "brand_name", "specification", "manufacturer", "approval_number",
                    "batch_number", "storage_location", "ingredients", "usage_and_dosage", "contraindications",
                    "adverse_reactions", "precautions", "interactions", "storage_conditions", "leaflet_source"
                ).forEach { column ->
                    db.execSQL("ALTER TABLE medicines ADD COLUMN info_$column TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN group_key TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE medicines ADD COLUMN group_name TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN info_production_date TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pharmacy.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                // Intentionally no destructive fallback: an unsupported migration must not erase the cabinet.
                .build().also { instance = it }
        }
    }
}
