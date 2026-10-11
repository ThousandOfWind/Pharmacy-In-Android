package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid backup accepted") } catch (_: Exception) { }
    }

    @Test fun allFieldsRoundTripWithoutChangingLegacyValuesOrMultilineText() {
        val record = BackupRecord(" 示例药 ", expiry = "旧日期格式", efficacy = " 第一行\n第二行 ", notes = "原备注", quantity = 0,
            unit = "旧单位", ocrText = "包装识别原文\n第二行", createdAt = 123, updatedAt = 456,
            tags = listOf("外用", "自定义"), information = BACKUP_INFORMATION_V1.associateWith { "$it 原文\n保留" }, photos = listOf("a".repeat(64)))
        val document = BackupDocument(789, listOf(record))
        assertEquals(document, decodeBackup(encodeBackup(document)))
        assertNotEquals(backupFingerprint(record), backupFingerprint(record.copy(quantity = null)))
        assertNotEquals(backupFingerprint(record), backupFingerprint(record.copy(updatedAt = 457)))
        assertEquals(backupFingerprint(record), backupFingerprint(record.copy(information = record.information.toList().reversed().toMap())))
        rejected { decodeBackup(encodeBackup(document) + byteArrayOf(1)) }
        rejected { decodeBackup(encodeBackup(document).copyOf(12)) }
    }

    @Test fun archiveIncludesPhotosAndCanBeRepeatedlyImportedByContentIdentity() {
        val folder = Files.createTempDirectory("pharmacy-backup-test").toFile()
        try {
            val image = byteArrayOf(1, 2, 3, 4, 5)
            val hash = sha256(image)
            val photo = folder.resolve("source").apply { writeBytes(image) }
            val record = BackupRecord("药品", photos = listOf(hash, hash))
            val doc = BackupDocument(1000, listOf(record))
            val bytes = ByteArrayOutputStream().also { writeBackupArchive(it, doc, mapOf(hash to photo)) }.toByteArray()
            val stage = folder.resolve("stage").apply { mkdir() }
            val result = readBackupArchive(ByteArrayInputStream(bytes), stage)
            assertEquals(doc, result.document)
            assertArrayEquals(image, result.photos.getValue(hash).readBytes())
            val known = mutableSetOf(backupFingerprint(record))
            assertEquals(0, result.document.records.count { known.add(backupFingerprint(it)) })
            assertTrue(known.add(backupFingerprint(record.copy(quantity = 2, unit = "片"))))
        } finally { folder.deleteRecursively() }
    }

    @Test fun archiveRejectsTraversalMissingPhotosAndChangedBytes() {
        val folder = Files.createTempDirectory("pharmacy-backup-reject").toFile()
        try {
            fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
            }.toByteArray()
            fun read(bytes: ByteArray) { val stage = Files.createTempDirectory(folder.toPath(), "stage").toFile(); readBackupArchive(ByteArrayInputStream(bytes), stage) }
            val image = byteArrayOf(1, 2); val hash = sha256(image)
            val manifest = encodeBackup(BackupDocument(1, listOf(BackupRecord("药品", photos = listOf(hash)))))
            rejected { read(archive("../outside" to image, "cabinet.bin" to manifest)) }
            rejected { read(archive("cabinet.bin" to manifest)) }
            rejected { read(archive("cabinet.bin" to manifest, photoEntry(hash) to byteArrayOf(2, 3))) }
            rejected { photoEntry("../outside") }
            assertFalse(requireNotNull(folder.parentFile).resolve("outside").exists())
        } finally { folder.deleteRecursively() }
    }

    @Test fun manifestBoundsRejectUnsafeCountsAndKeepUnknownStockSeparateFromZero() {
        val unknown = BackupRecord("药品", quantity = null)
        assertNull(decodeBackup(encodeBackup(BackupDocument(1, listOf(unknown)))).records.single().quantity)
        rejected { encodeBackup(BackupDocument(1, List(MAX_BACKUP_RECORDS + 1) { unknown })) }
        rejected { encodeBackup(BackupDocument(1, listOf(unknown.copy(quantity = -1)))) }
        rejected { encodeBackup(BackupDocument(1, listOf(unknown.copy(photos = List(13) { "a".repeat(64) })))) }
        rejected { encodeBackup(BackupDocument(1, listOf(unknown.copy(notes = "x".repeat(1024 * 1024 + 1))))) }
    }
}
