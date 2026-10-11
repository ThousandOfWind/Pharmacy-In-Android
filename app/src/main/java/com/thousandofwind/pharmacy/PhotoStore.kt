package com.thousandofwind.pharmacy

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

object PhotoStore {
    private fun newFile(context: Context): File = File(
        File(context.filesDir, "medicine_photos").apply { mkdirs() },
        "${UUID.randomUUID()}.jpg"
    )

    fun cameraUri(context: Context): Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider",
        File(File(context.cacheDir, "images").apply { mkdirs() }, "capture_${UUID.randomUUID()}.jpg")
    )

    /** Copy-on-write, lossless EXIF rotation: never mutate a saved record's or backup's bytes. */
    fun rotateClockwise(context: Context, photo: String): String {
        val destination = newFile(context)
        try {
            context.contentResolver.openInputStream(Uri.parse(photo)).use { input ->
                requireNotNull(input) { "无法读取照片" }
                destination.outputStream().use { input.copyTo(it) }
            }
            val exif = ExifInterface(destination.path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val next = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> ExifInterface.ORIENTATION_ROTATE_180
                ExifInterface.ORIENTATION_ROTATE_180 -> ExifInterface.ORIENTATION_ROTATE_270
                ExifInterface.ORIENTATION_ROTATE_270 -> ExifInterface.ORIENTATION_NORMAL
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifInterface.ORIENTATION_TRANSVERSE
                ExifInterface.ORIENTATION_TRANSVERSE -> ExifInterface.ORIENTATION_FLIP_VERTICAL
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifInterface.ORIENTATION_TRANSPOSE
                ExifInterface.ORIENTATION_TRANSPOSE -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                else -> ExifInterface.ORIENTATION_ROTATE_90
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, next.toString())
            exif.saveAttributes()
            return Uri.fromFile(destination).toString()
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    /** Own the bytes rather than rely on temporary picker permissions or cache retention. */
    fun importPhoto(context: Context, source: Uri): String {
        val destination = newFile(context)
        try {
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "无法读取照片" }
                destination.outputStream().use { input.copyTo(it) }
            }
            require(destination.length() > 0) { "照片为空，请重新选择" }
            return Uri.fromFile(destination).toString()
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}
