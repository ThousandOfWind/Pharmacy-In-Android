package com.thousandofwind.pharmacy

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
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
