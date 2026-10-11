package com.thousandofwind.pharmacy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

const val MAX_BACKUP_BYTES = 512L * 1024 * 1024
const val MAX_BACKUP_PHOTO_BYTES = 20L * 1024 * 1024
const val MAX_BACKUP_MANIFEST_BYTES = 16 * 1024 * 1024
const val MAX_BACKUP_RECORDS = 2000
const val MAX_BACKUP_PHOTOS = MAX_BACKUP_RECORDS * 12
private const val MAX_TEXT_BYTES = 1024 * 1024
private const val BACKUP_VERSION = 2
private const val BACKUP_MAGIC = "PharmacyCabinetBackup"

// Fixed v1 wire schema, independent of future additions to the database field enum.
val BACKUP_INFORMATION_V1 = listOf(
    "generic_name", "brand_name", "specification", "manufacturer", "approval_number", "batch_number",
    "storage_location", "ingredients", "usage_and_dosage", "contraindications", "adverse_reactions",
    "precautions", "interactions", "storage_conditions", "leaflet_source"
)

data class BackupRecord(
    val name: String, val expiry: String = "", val efficacy: String = "", val notes: String = "",
    val quantity: Int? = null, val unit: String = "", val ocrText: String = "",
    val createdAt: Long = 0, val updatedAt: Long = 0,
    val tags: List<String> = emptyList(), val information: Map<String, String> = BACKUP_INFORMATION_V1.associateWith { "" },
    val photos: List<String> = emptyList(), val groupKey: String = "", val groupName: String = ""
)

data class BackupDocument(val createdAt: Long, val records: List<BackupRecord>)

fun photoEntry(hash: String): String {
    require(Regex("[0-9a-f]{64}").matches(hash)) { "照片标识无效" }
    return "photos/$hash.bin"
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

/** Includes all stored values and ordered photo hashes, but never device paths or local database IDs. */
fun backupFingerprint(record: BackupRecord): String = sha256(ByteArrayOutputStream().also { buffer ->
    DataOutputStream(buffer).use { writeRecord(it, record) }
}.toByteArray())

private fun text(out: DataOutputStream, value: String) {
    require(value.length <= MAX_TEXT_BYTES) { "单项文字过长，无法备份" }
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_TEXT_BYTES) { "单项文字过长，无法备份" }
    out.writeInt(bytes.size); out.write(bytes)
}

private fun text(input: DataInputStream): String {
    val size = input.readInt()
    require(size in 0..MAX_TEXT_BYTES && size <= input.available()) { "备份文字长度无效" }
    val bytes = ByteArray(size); input.readFully(bytes)
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

private fun writeRecord(out: DataOutputStream, r: BackupRecord) {
    require(r.name.isNotBlank() && (r.quantity == null || r.quantity >= 0)) { "药品记录无效" }
    require(r.tags.size <= 100 && r.photos.size <= 12 && r.information.keys == BACKUP_INFORMATION_V1.toSet()) { "记录字段不受支持" }
    listOf(r.name, r.expiry, r.efficacy, r.notes, r.unit, r.ocrText).forEach { text(out, it) }
    out.writeBoolean(r.quantity != null); r.quantity?.let(out::writeInt)
    out.writeLong(r.createdAt); out.writeLong(r.updatedAt)
    out.writeInt(r.tags.size); r.tags.forEach { text(out, it) }
    BACKUP_INFORMATION_V1.forEach { text(out, r.information.getValue(it)) }
    out.writeInt(r.photos.size); r.photos.forEach { photoEntry(it); text(out, it) }
    require((r.groupKey.isEmpty() && r.groupName.isEmpty()) || (validGroupKey(r.groupKey) && groupNameError(r.groupName) == null)) { "备份分组无效" }
    text(out, r.groupKey); text(out, r.groupName)
}

fun encodeBackup(document: BackupDocument): ByteArray {
    require(document.records.size <= MAX_BACKUP_RECORDS) { "药品记录超过备份上限" }
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { out ->
        text(out, BACKUP_MAGIC); out.writeInt(BACKUP_VERSION); out.writeLong(document.createdAt)
        out.writeInt(document.records.size)
        document.records.forEach {
            writeRecord(out, it)
            require(output.size() <= MAX_BACKUP_MANIFEST_BYTES) { "备份文字总量过大" }
        }
    }
    return output.toByteArray().also { require(it.size <= MAX_BACKUP_MANIFEST_BYTES) { "备份文字总量过大" } }
}

fun decodeBackup(bytes: ByteArray): BackupDocument {
    require(bytes.size <= MAX_BACKUP_MANIFEST_BYTES) { "备份清单过大" }
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(text(input) == BACKUP_MAGIC) { "不是本应用的备份" }
        val version = input.readInt()
        require(version in 1..BACKUP_VERSION) { "不是本应用支持的备份版本" }
        val time = input.readLong()
        val count = input.readInt(); require(count in 0..MAX_BACKUP_RECORDS) { "备份记录数量无效" }
        val rows = List(count) {
            val name = text(input); val expiry = text(input); val efficacy = text(input); val notes = text(input)
            val unit = text(input); val ocr = text(input)
            val flag = input.readUnsignedByte(); require(flag in 0..1) { "备份余量标记无效" }
            val quantity = if (flag == 1) input.readInt().also { require(it >= 0) { "备份余量无效" } } else null
            val created = input.readLong(); val updated = input.readLong()
            val tagsCount = input.readInt(); require(tagsCount in 0..100) { "备份标签数量无效" }
            val tags = List(tagsCount) { text(input) }
            require(tags == normalizeMedicineTags(tags)) { "备份标签不是规范化内容" }
            val information = BACKUP_INFORMATION_V1.associateWith { text(input) }
            val photoCount = input.readInt(); require(photoCount in 0..12) { "备份照片数量无效" }
            val photos = List(photoCount) { text(input).also(::photoEntry) }
            val groupKey = if (version >= 2) text(input) else ""
            val groupName = if (version >= 2) text(input) else ""
            require((groupKey.isEmpty() && groupName.isEmpty()) || (validGroupKey(groupKey) && groupNameError(groupName) == null)) { "备份分组无效" }
            require(name.isNotBlank()) { "备份药名为空" }
            // Preserve legacy unknown units/dates and multiline text rather than rewriting old records.
            BackupRecord(name, expiry, efficacy, notes, quantity, unit, ocr, created, updated, tags, information, photos, groupKey, groupName)
        }
        require(rows.filter { it.groupKey.isNotBlank() }.groupBy { it.groupKey }.values.all { group -> group.map { it.groupName }.distinct().size == 1 }) { "备份药品组名称冲突" }
        require(input.available() == 0) { "备份包含未支持的数据" }
        return BackupDocument(time, rows)
    }
}
