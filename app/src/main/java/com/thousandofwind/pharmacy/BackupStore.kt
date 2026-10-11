package com.thousandofwind.pharmacy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal fun MedicineEntity.backupRecord(photoHashes: List<String>) = BackupRecord(
    name, expiryDate, efficacy, notes, remainingQuantity, quantityUnit, ocrRawText, createdAt, updatedAt,
    tags(), BACKUP_INFORMATION_V1.associateWith { column ->
        information[MedicineInformationField.entries.first { it.column == column }]
    }, photoHashes, groupKey, groupName
)

private fun BackupRecord.entity(paths: List<String>, restoredGroup: Pair<String, String>): MedicineEntity {
    val info = MedicineInformationField.entries.fold(MedicineInformation()) { current, field ->
        current.withValue(field, information[field.column].orEmpty())
    }
    return MedicineEntity(
        id = 0, name = name, expiryDate = expiry, efficacy = efficacy, notes = notes,
        remainingQuantity = quantity, quantityUnit = unit, ocrRawText = ocrText,
        photoUri = paths.firstOrNull().orEmpty(), photosJson = encodePhotos(paths),
        createdAt = createdAt, updatedAt = updatedAt, tagsJson = org.json.JSONArray(tags).toString(), information = info, groupKey = restoredGroup.first, groupName = restoredGroup.second
    )
}

data class BackupPreview(val records: Int, val photos: Int, val newRecords: Int, val duplicates: Int, val createdAt: Long, val groups: Int, val existingRecords: Int)
data class BackupExportResult(val records: Int, val photos: Int, val bytes: Long, val fileName: String, val verified: Boolean)
data class BackupRestoreResult(val added: Int, val duplicates: Int, val photos: Int)
internal data class PendingBackup(val directory: File, val checked: CheckedBackup)

class BackupStore(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val dao = db.medicineDao()
    private val base = File(context.cacheDir, "cabinet_backup")
    private fun stage(): File = synchronized(stagingLock) {
        check(base.isDirectory || base.mkdirs()) { "无法创建备份暂存目录" }
        // Registry is process-wide: another instance's live preview must not be swept.
        base.listFiles().orEmpty().filter { it.isDirectory && validGroupKey(it.name) && it.canonicalFile.parentFile == base.canonicalFile && it.absolutePath !in activeStages }
            .forEach { check(it.deleteRecursively()) { "旧备份暂存目录无法清理" } }
        File(base, UUID.randomUUID().toString()).apply {
            check(mkdir()) { "无法创建备份暂存目录" }
            activeStages += absolutePath
        }
    }

    private fun cleanup(directory: File) = synchronized(stagingLock) {
        directory.deleteRecursively()
        activeStages -= directory.absolutePath
    }

    companion object {
        private val stagingLock = Any()
        private val activeStages = mutableSetOf<String>()
    }

    private fun readPhoto(source: String): java.io.InputStream {
        val uri = Uri.parse(source)
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path)).canonicalFile
            val root = File(context.filesDir, "medicine_photos").canonicalFile
            require(file.parentFile == root) { "照片不在本应用药箱目录中，请先重新导入" }
            return file.inputStream()
        }
        // Older app versions used a private FileProvider cache URI. Never follow arbitrary backup URIs.
        require(uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider" && uri.path?.startsWith("/images/") == true) { "不支持的药品照片来源" }
        return requireNotNull(context.contentResolver.openInputStream(uri)) { "照片已无法读取，未生成不完整备份" }
    }

    private fun hashPhoto(source: String, destination: File? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        readPhoto(source).use { input ->
            val out = destination?.outputStream()
            try {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count == -1) break
                    size += count; require(size <= MAX_BACKUP_PHOTO_BYTES) { "单张照片超过 20 MiB 备份上限" }
                    digest.update(buffer, 0, count); out?.write(buffer, 0, count)
                }
                require(size > 0) { "照片为空，未生成不完整备份" }
            } finally { out?.close() }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** First make and validate a local snapshot. Only then write the user-selected destination. */
    suspend fun export(destination: Uri): BackupExportResult = withContext(Dispatchers.IO) {
        val work = stage()
        try {
            val items = dao.snapshot()
            require(items.isNotEmpty()) { "药箱为空，没有已保存的药品可导出" }
            require(items.size <= MAX_BACKUP_RECORDS) { "记录超过 2000 条备份上限" }
            val files = linkedMapOf<String, File>()
            var total = 0L
            val sourceHashes = mutableMapOf<String, String>()
            val records = items.map { item ->
                val hashes = item.photos().map { source ->
                    sourceHashes.getOrPut(source) {
                        val temp = File(work, "photo-${UUID.randomUUID()}")
                        val hash = hashPhoto(source, temp)
                        if (hash !in files) {
                            total += temp.length(); require(total <= MAX_BACKUP_BYTES) { "照片总量超过 512 MiB 备份上限" }
                            files[hash] = temp
                        } else check(temp.delete())
                        hash
                    }
                }
                item.backupRecord(hashes)
            }
            val document = BackupDocument(System.currentTimeMillis(), records)
            val archive = File(work, "backup.zip")
            archive.outputStream().use { writeBackupArchive(it, document, files) }
            require(archive.length() <= MAX_BACKUP_BYTES) { "备份文件超过 512 MiB 上限" }
            val output = requireNotNull(context.contentResolver.openOutputStream(destination, "wt")) { "无法写入选择的位置" }
            output.use { out -> archive.inputStream().use { it.copyTo(out) } }
            // Some document providers can write but cannot immediately read back. Never call that verified.
            val verified = try {
                context.contentResolver.openInputStream(destination)?.use { verifyBackupCopy(it, archive); true } ?: false
            } catch (_: IOException) { false } catch (_: SecurityException) { false }
            val fileName = runCatching {
                context.contentResolver.query(destination, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { "文件名无法读取，请在所选位置查找 ZIP 备份" }
            BackupExportResult(records.size, files.size, archive.length(), fileName, verified)
        } finally { cleanup(work) }
    }

    internal suspend fun prepare(source: Uri): PendingBackup = withContext(Dispatchers.IO) {
        val work = stage()
        try {
            val content = File(work, "content").apply { check(mkdir()) }
            val checked = requireNotNull(context.contentResolver.openInputStream(source)) { "无法读取备份文件" }
                .use { readBackupArchive(it, content) }
            PendingBackup(work, checked)
        } catch (error: Exception) { cleanup(work); throw error }
    }

    private fun photoHashes(items: List<MedicineEntity>): Map<String, String?> = items.flatMap { it.photos() }.distinct()
        .associateWith { source -> runCatching { hashPhoto(source) }.getOrNull() }

    private fun fingerprints(items: List<MedicineEntity>, hashes: Map<String, String?>): MutableSet<String> {
        val known = mutableSetOf<String>()
        items.forEach { item ->
            val photos = item.photos().map { source ->
                require(hashes.containsKey(source)) { "恢复期间药箱照片发生变化，请重新选择备份" }
                hashes[source]
            }
            if (photos.all { it != null }) known += backupFingerprint(item.backupRecord(photos.filterNotNull()))
        }
        return known
    }

    private fun incomingRecords(pending: PendingBackup, items: List<MedicineEntity>): List<BackupRecord> {
        val existingGroups = items.filter { it.groupKey.isNotBlank() }.associate { it.groupKey to it.groupName }
        return resolveBackupGroups(pending.checked.document.records, existingGroups)
    }

    internal suspend fun preview(pending: PendingBackup): BackupPreview = withContext(Dispatchers.IO) {
        val items = dao.snapshot()
        val known = fingerprints(items, photoHashes(items))
        val incoming = incomingRecords(pending, items)
        val added = incoming.count { known.add(backupFingerprint(it)) }
        BackupPreview(incoming.size, pending.checked.photos.size, added, incoming.size - added, pending.checked.document.createdAt,
            incoming.map { it.groupKey }.filter(String::isNotBlank).distinct().size, items.size)
    }

    /** No existing row is updated/deleted. DB insertion is atomic; failed imported photos are removed. */
    internal suspend fun restore(pending: PendingBackup): BackupRestoreResult = withContext(Dispatchers.IO + NonCancellable) {
        val ownedPhotos = mutableMapOf<String, File>()
        val usedPhotos = mutableSetOf<String>()
        var committed = false
        try {
            // Heavy reads and photo copies stay outside the Room write transaction.
            val hashes = photoHashes(dao.snapshot())
            val root = File(context.filesDir, "medicine_photos").apply { check(isDirectory || mkdirs()) }
            pending.checked.photos.forEach { (hash, source) ->
                val target = File(root, "${UUID.randomUUID()}.jpg")
                ownedPhotos[hash] = target
                source.copyTo(target)
            }
            val count = db.withTransaction {
                val current = dao.snapshot()
                val known = fingerprints(current, hashes)
                val fresh = incomingRecords(pending, current).filter { known.add(backupFingerprint(it)) }
                fresh.forEach { record ->
                    val paths = record.photos.map { hash ->
                        usedPhotos += hash
                        Uri.fromFile(ownedPhotos.getValue(hash)).toString()
                    }
                    dao.upsert(record.entity(paths, record.groupKey to record.groupName))
                }
                fresh.size
            }
            committed = true
            BackupRestoreResult(count, pending.checked.document.records.size - count, usedPhotos.size)
        } finally {
            ownedPhotos.filterKeys { !committed || it !in usedPhotos }.values.forEach { it.delete() }
        }
    }

    internal fun discard(pending: PendingBackup) { cleanup(pending.directory) }
}
