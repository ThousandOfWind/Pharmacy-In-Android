package com.thousandofwind.pharmacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object PhotoStore {
    private fun newFile(context: Context, extension: String = "jpg"): File = File(
        File(context.filesDir, "medicine_photos").apply { mkdirs() },
        "${UUID.randomUUID()}.$extension"
    )

    fun cameraUri(context: Context): Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider",
        File(File(context.cacheDir, "images").apply { mkdirs() }, "capture_${UUID.randomUUID()}.jpg")
    )

    /** Never delete a picker source, public gallery image, or anything outside the owned roots. */
    fun deletePhotos(context: Context, photos: List<String>): Boolean {
        val root = File(context.filesDir, "medicine_photos")
        return photos.map { photo ->
            runCatching {
                val uri = Uri.parse(photo)
                uri.scheme == "file" && uri.authority.isNullOrEmpty() &&
                    uri.path?.let { deleteOwnedPhotoFile(File(it), root) } == true
            }.getOrDefault(false)
        }.all { it }
    }

    fun deleteCameraCapture(context: Context, source: Uri) {
        if (source.scheme != "content" || source.authority != "${context.packageName}.fileprovider") return
        val name = source.lastPathSegment ?: return
        if (source.path != "/images/$name" || !Regex("capture_[0-9a-f-]{36}\\.jpg").matches(name)) return
        runCatching { deleteOwnedPhotoFile(File(File(context.cacheDir, "images"), name), File(context.cacheDir, "images")) }
    }

    /** Normalize EXIF first, then rotate actual pixels. PNG works independently of EXIF support
     * in the original format or viewer. Decoding is bounded to 2400px for phone memory safety. */
    fun rotateClockwise(context: Context, photo: String): String {
        val destination = newFile(context, "png")
        var upright: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            val image = loadOcrBitmap(photo)
            upright = image
            val result = Bitmap.createBitmap(image, 0, 0, image.width, image.height,
                Matrix().apply { setRotate(90f) }, false)
            rotated = result
            destination.outputStream().use { require(result.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存旋转图片" } }
            require(destination.length() > 0) { "旋转图片为空" }
            return Uri.fromFile(destination).toString()
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            if (rotated !== upright) rotated?.recycle()
            upright?.recycle()
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
