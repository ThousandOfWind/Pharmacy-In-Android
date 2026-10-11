package com.thousandofwind.pharmacy

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val MANIFEST_ENTRY = "cabinet.bin"

data class CheckedBackup(val document: BackupDocument, val photos: Map<String, File>)

/** Streams only exact named entries into a private staging directory, never ZIP paths. */
fun readBackupArchive(input: InputStream, staging: File): CheckedBackup {
    require(staging.isDirectory && staging.listFiles().orEmpty().isEmpty()) { "备份暂存目录不可用" }
    // ZipInputStream alone accepts local entries with a missing ZIP footer. Validate the directory too.
    val archive = File(staging, "archive.zip")
    input.use { source ->
        archive.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024); var compressed = 0L
            while (true) {
                val count = source.read(buffer); if (count == -1) break
                compressed += count; require(compressed <= MAX_BACKUP_BYTES) { "备份文件超过大小限制" }
                out.write(buffer, 0, count)
            }
        }
    }
    val directoryNames = ZipFile(archive).use { zip ->
        val names = mutableSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            require(!entry.isDirectory && names.add(entry.name)) { "备份目录重复或无效" }
            require(entry.name == MANIFEST_ENTRY || Regex("photos/[0-9a-f]{64}\\.bin").matches(entry.name)) { "备份目录含不支持的条目" }
            require(names.size <= MAX_BACKUP_PHOTOS + 1) { "备份目录条目过多" }
        }
        names
    }
    var total = 0L
    var manifest: ByteArray? = null
    val photos = linkedMapOf<String, File>()
    val names = hashSetOf<String>()
    ZipInputStream(archive.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory && names.add(entry.name)) { "备份含有重复或不支持的条目" }
            val isManifest = entry.name == MANIFEST_ENTRY
            val hash = if (isManifest) null else Regex("photos/([0-9a-f]{64})\\.bin").matchEntire(entry.name)?.groupValues?.get(1)
                ?: error("备份含有不支持的文件")
            val limit = if (isManifest) MAX_BACKUP_MANIFEST_BYTES.toLong() else MAX_BACKUP_PHOTO_BYTES
            require(entry.size == -1L || entry.size in 0..limit) { "备份条目超过大小限制" }
            require(names.size <= MAX_BACKUP_PHOTOS + 1) { "备份条目数量过多" }
            val file = File(staging, if (isManifest) "manifest" else requireNotNull(hash))
            var size = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = zip.read(buffer); if (count == -1) break
                    size += count; total += count
                    require(size <= limit && total <= MAX_BACKUP_BYTES) { "备份解压大小超过限制" }
                    out.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
            }
            if (isManifest) manifest = file.readBytes()
            else {
                require(size > 0 && digest.digest().joinToString("") { "%02x".format(it) } == hash) { "备份照片校验失败" }
                photos[requireNotNull(hash)] = file
            }
            zip.closeEntry() // Also validates the ZIP CRC; truncation is rejected.
        }
    }
    require(names == directoryNames) { "备份条目与ZIP目录不一致" }
    val document = decodeBackup(requireNotNull(manifest) { "备份缺少药品清单" })
    val referenced = document.records.flatMap { it.photos }.toSet()
    require(referenced == photos.keys) { "备份照片与清单不一致" }
    return CheckedBackup(document, photos)
}

fun writeBackupArchive(output: OutputStream, document: BackupDocument, photos: Map<String, File>) {
    val manifest = encodeBackup(document)
    require(document.records.flatMap { it.photos }.toSet() == photos.keys) { "备份照片与清单不一致" }
    var total = manifest.size.toLong()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY)); zip.write(manifest); zip.closeEntry()
        photos.forEach { (hash, file) ->
            require(file.length() in 1..MAX_BACKUP_PHOTO_BYTES) { "照片超过备份上限" }
            zip.putNextEntry(ZipEntry(photoEntry(hash)))
            file.inputStream().use { input ->
                var size = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count == -1) break
                    size += count; total += count
                    require(size <= MAX_BACKUP_PHOTO_BYTES && total <= MAX_BACKUP_BYTES) { "备份内容超过大小限制" }
                    zip.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
                require(size > 0 && digest.digest().joinToString("") { "%02x".format(it) } == hash) { "备份期间照片发生变化" }
            }
            zip.closeEntry()
        }
    }
}
