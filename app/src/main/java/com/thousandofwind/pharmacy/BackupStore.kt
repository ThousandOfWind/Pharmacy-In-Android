package com.thousandofwind.pharmacy

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal fun MedicineEntity.backupRecord(photoHashes: List<String>) = BackupRecord(
    name, expiryDate, efficacy, notes, remainingQuantity, quantityUnit, ocrRawText, createdAt, updatedAt,
    tags(), BACKUP_INFORMATION_V1.associateWith { column ->
        information[MedicineInformationField.entries.first { it.column == column }]
    }, photoHashes
)

private fun BackupRecord.entity(paths: List<String>): MedicineEntity {
    val info = MedicineInformationField.entries.fold(MedicineInformation()) { current, field ->
        current.withValue(field, information[field.column].orEmpty())
    }
    return MedicineEntity(
        id = 0, name = name, expiryDate = expiry, efficacy = efficacy, notes = notes,
        remainingQuantity = quantity, quantityUnit = unit, ocrRawText = ocrText,
        photoUri = paths.firstOrNull().orEmpty(), photosJson = encodePhotos(paths),
        createdAt = createdAt, updatedAt = updatedAt, tagsJson = org.json.JSONArray(tags).toString(), information = info
    )
}

data class BackupPreview(val records: Int, val photos: Int, val newRecords: Int, val duplicates: Int, val createdAt: Long)
internal data class PendingBackup(val directory: File, val checked: CheckedBackup)

class BackupStore(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val dao = db.medicineDao()
    private val base = File(context.cacheDir, "cabinet_backup")
    private fun stage(): File = File(base, UUID.randomUUID().toString()).apply { check(mkdirs()) { "无法创建备份暂存目录" } }

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
    suspend fun export(destination: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val work = stage()
        try {
            val items = dao.snapshot()
            require(items.size <= MAX_BACKUP_RECORDS) { "记录超过 2000 条备份上限" }
            val files = linkedMapOf<String, File>()
            var total = 0L
            val records = items.map { item ->
                val hashes = item.photos().map { source ->
                    val temp = File(work, "photo-${UUID.randomUUID()}")
                    val hash = hashPhoto(source, temp)
                    if (hash !in files) {
                        total += temp.length(); require(total <= MAX_BACKUP_BYTES) { "照片总量超过 512 MiB 备份上限" }
                        files[hash] = temp
                    } else check(temp.delete())
                    hash
                }
                item.backupRecord(hashes)
            }
            val document = BackupDocument(System.currentTimeMillis(), records)
            val archive = File(work, "backup.zip")
            archive.outputStream().use { writeBackupArchive(it, document, files) }
            require(archive.length() <= MAX_BACKUP_BYTES) { "备份文件超过 512 MiB 上限" }
            val output = requireNotNull(context.contentResolver.openOutputStream(destination, "wt")) { "无法写入选择的位置" }
            output.use { out -> archive.inputStream().use { it.copyTo(out) } }
            records.size to files.size
        } finally { work.deleteRecursively() }
    }

    internal suspend fun prepare(source: Uri): PendingBackup = withContext(Dispatchers.IO) {
        val work = stage()
        try {
            val archive = File(work, "input.zip")
            requireNotNull(context.contentResolver.openInputStream(source)) { "无法读取备份文件" }.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count == -1) break
                        total += count; require(total <= MAX_BACKUP_BYTES) { "备份文件超过 512 MiB 上限" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val content = File(work, "content").apply { check(mkdir()) }
            val checked = archive.inputStream().use { readBackupArchive(it, content) }
            PendingBackup(work, checked)
        } catch (error: Exception) { work.deleteRecursively(); throw error }
    }

    private suspend fun existingFingerprints(): MutableSet<String> = dao.snapshot().mapTo(hashSetOf()) { item ->
        val hashes = item.photos().map { hashPhoto(it) }
        backupFingerprint(item.backupRecord(hashes))
    }

    internal suspend fun preview(pending: PendingBackup): BackupPreview = withContext(Dispatchers.IO) {
        val known = existingFingerprints()
        val incoming = pending.checked.document.records
        val added = incoming.count { known.add(backupFingerprint(it)) }
        BackupPreview(incoming.size, pending.checked.photos.size, added, incoming.size - added, pending.checked.document.createdAt)
    }

    /** No existing row is updated/deleted. DB insertion is atomic; failed imported photos are removed. */
    internal suspend fun restore(pending: PendingBackup): Int = withContext(Dispatchers.IO + NonCancellable) {
        val ownedPhotos = mutableListOf<File>()
        var committed = false
        try {
            val count = db.withTransaction {
                val known = existingFingerprints()
                val fresh = pending.checked.document.records.filter { known.add(backupFingerprint(it)) }
                val paths = mutableMapOf<String, String>()
                val root = File(context.filesDir, "medicine_photos").apply { check(isDirectory || mkdirs()) }
                fresh.flatMap { it.photos }.distinct().forEach { hash ->
                    val target = File(root, "${UUID.randomUUID()}.jpg")
                    ownedPhotos += target
                    pending.checked.photos.getValue(hash).copyTo(target)
                    paths[hash] = Uri.fromFile(target).toString()
                }
                fresh.forEach { record -> dao.upsert(record.entity(record.photos.map { paths.getValue(it) })) }
                fresh.size
            }
            committed = true
            count
        } finally {
            if (!committed) ownedPhotos.forEach { it.delete() }
        }
    }

    internal fun discard(pending: PendingBackup) { pending.directory.deleteRecursively() }
}
