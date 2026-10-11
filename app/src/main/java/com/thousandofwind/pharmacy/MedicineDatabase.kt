package com.thousandofwind.pharmacy

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
    @ColumnInfo(name = "tags_json", defaultValue = "'[]'") val tagsJson: String = "[]"
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MedicineEntity): Long

    @Query("DELETE FROM medicines WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [MedicineEntity::class], version = 4, exportSchema = false)
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

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pharmacy.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                // Intentionally no destructive fallback: an unsupported migration must not erase the cabinet.
                .build().also { instance = it }
        }
    }
}
