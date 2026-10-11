package com.thousandofwind.pharmacy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decode a bounded upright bitmap; preview and crop use exactly the same pixel orientation. */
fun loadOcrBitmap(photo: String): Bitmap {
    val uri = Uri.parse(photo)
    require(uri.scheme == "file")
    val file = File(requireNotNull(uri.path))
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取照片" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
    val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
    val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrRegionDialog(photo: String, busy: Boolean, dismiss: () -> Unit, recognize: (OcrRegion, String) -> Unit) {
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(photo) { mutableStateOf(false) }
    var region by remember(photo) { mutableStateOf<OcrRegion?>(null) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var target by rememberSaveable(photo) { mutableStateOf(OCR_TARGET_NAME) }
    var menu by remember { mutableStateOf(false) }
    val choices = listOf(OCR_TARGET_NAME to "药品名称", OCR_TARGET_EXPIRY to "有效期至", OCR_TARGET_EFFICACY to "适应症 / 功能主治") +
        MedicineInformationField.entries.filterNot { it == MedicineInformationField.STORAGE_LOCATION }.map { it.column to it.label }
    LaunchedEffect(photo) {
        runCatching { withContext(Dispatchers.IO) { loadOcrBitmap(photo) } }.onSuccess { bitmap = it }.onFailure { failed = true }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("框选重识别", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = dismiss, enabled = !busy) { Text("关闭") }
                }
                Text("在照片上拖动，框住要识别的文字；重新拖动可重画。原图不会裁掉。", style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    if (image == null) Text(if (failed) "照片无法读取，请关闭后重试" else "正在读取照片…")
                    else BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val ratio = image.width.toFloat() / image.height
                        val width = minOf(maxWidth, maxHeight * ratio)
                        val height = width / ratio
                        Box(Modifier.size(width, height)) {
                            Image(image.asImageBitmap(), "原始照片，拖动框选文字", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            Canvas(Modifier.fillMaxSize().semantics {
                                stateDescription = if (region?.valid() == true) "已框选文字区域" else "未框选文字区域"
                            }.pointerInput(photo, busy) {
                                if (!busy) detectDragGestures(
                                    onDragStart = { start = it; region = null },
                                    onDragCancel = { region = null },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        region = OcrRegion.between(start.x / size.width, start.y / size.height, change.position.x / size.width, change.position.y / size.height)
                                    }
                                )
                            }) {
                                region?.let { r ->
                                    val topLeft = Offset(r.left * size.width, r.top * size.height)
                                    val rectSize = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height)
                                    drawRect(PaperPalette.Wash.copy(alpha = .25f), topLeft, rectSize)
                                    drawRect(PaperPalette.Green, topLeft, rectSize, style = Stroke(2.dp.toPx()))
                                }
                            }
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("归入：${choices.first { it.first == target }.second}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { target = id; menu = false }) }
                    }
                }
                PenButton(if (busy) "识别中…" else "识别框选文字", { region?.takeIf { it.valid() }?.let { recognize(it, target) } },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy && region?.valid() == true)
            }
        }
    }
}
