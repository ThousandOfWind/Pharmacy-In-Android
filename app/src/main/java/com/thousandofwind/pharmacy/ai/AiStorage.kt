package com.thousandofwind.pharmacy.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed ciphertext in noBackupFilesDir, separate from settings, sessions and cabinet backups. */
class AiCredentialStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "azure-ai.credential")
    private val alias = "pharmacy.azure.ai.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(endpoint: String, value: String) {
        val payload = buildJsonObject { put("endpoint", endpoint); put("key", value) }.toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val target = android.util.AtomicFile(file)
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
    }
    @Synchronized fun read(expectedEndpoint: String): String? {
        if (!file.exists()) return null
        val bytes = android.util.AtomicFile(file).readFully()
        require(bytes.size in 29..8192)
        val payload = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            .doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        val objectValue = Json.parseToJsonElement(payload).jsonObject
        // A cancelled/failed settings write must never pair another resource with this credential.
        if (objectValue["endpoint"]?.jsonPrimitive?.content != expectedEndpoint) return null
        return objectValue["key"]?.jsonPrimitive?.content
    }
    @Synchronized fun clear() { android.util.AtomicFile(file).delete() }
}

@Entity(tableName = "ai_sessions")
data class AiSessionEntity(@PrimaryKey val id: String, val title: String, val configJson: String, val createdAt: Long)
@Entity(tableName = "ai_turns", foreignKeys = [ForeignKey(entity = AiSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId")])
data class AiTurnEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String, val turnJson: String)
@Dao interface AiSessionDao {
    @Query("SELECT * FROM ai_sessions ORDER BY createdAt DESC") fun observeSessions(): Flow<List<AiSessionEntity>>
    @Query("SELECT * FROM ai_turns WHERE sessionId = :id ORDER BY id") fun observeTurns(id: String): Flow<List<AiTurnEntity>>
    @Query("SELECT * FROM ai_turns WHERE sessionId = :id ORDER BY id DESC LIMIT 8") suspend fun recent(id: String): List<AiTurnEntity>
    @Insert suspend fun create(value: AiSessionEntity)
    @Insert suspend fun append(value: AiTurnEntity)
    @Query("DELETE FROM ai_sessions WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE ai_sessions SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)
}
@Database(entities = [AiSessionEntity::class, AiTurnEntity::class], version = 1, exportSchema = false)
abstract class AiDatabase : RoomDatabase() {
    abstract fun sessions(): AiSessionDao
    companion object {
        @Volatile private var instance: AiDatabase? = null
        fun get(context: Context): AiDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AiDatabase::class.java, "azure-ai.db")
                .build().also { instance = it }
        }
    }
}

class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("azure-ai-settings", Context.MODE_PRIVATE)
    fun load(): AzureConnection = prefs.getString("connection", null)?.let { Json.decodeFromString<AzureConnection>(it) } ?: AzureConnection()
    fun save(value: AzureConnection) { check(prefs.edit().putString("connection", Json.encodeToString(value)).commit()) }
    fun clear() { check(prefs.edit().clear().commit()) }
}
