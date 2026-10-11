package com.thousandofwind.pharmacy

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

@Composable
fun DetailScreen(
    item: MedicineEntity, busy: Boolean, edit: () -> Unit,
    updateStock: (String, String, (Boolean) -> Unit) -> Unit, remove: () -> Unit
) {
    var stock by rememberSaveable(item.id) { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(item.id) { mutableStateOf(false) }
    var raw by rememberSaveable(item.id) { mutableStateOf(false) }
    var identity by rememberSaveable(item.id) { mutableStateOf(false) }
    var photos by rememberSaveable(item.id) { mutableStateOf(false) }
    var leaflet by rememberSaveable(item.id) { mutableStateOf(false) }
    var notes by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, style = MaterialTheme.typography.headlineMedium)
                val summary = listOf(item.information.brandName.takeUnless { it.equals(item.name, true) }.orEmpty(), item.information.specification).filter(String::isNotBlank).joinToString(" · ")
                if (summary.isNotBlank()) Text(summary, color = PaperPalette.Muted)
            }
            val photo = item.photos().firstOrNull()
            if (photo == null) Image(painterResource(R.drawable.ink_package), null, Modifier.width(72.dp).height(88.dp))
            else AsyncImage(photo, "${item.name}的包装照片", modifier = Modifier.size(78.dp).clip(MaterialTheme.shapes.small).clickable { photos = true }, contentScale = ContentScale.Crop)
        }
        val tags = item.tags()
        if (tags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MedicineTags(tags, limit = tags.size)
                Text("整理标签，不作为用药建议。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        ExpiryLabel(item.expiryDate)
        if (expiryStatus(item.expiryDate) == ExpiryStatus.EXPIRED) Text("该记录已过期，请核对包装并妥善处理。", color = PaperPalette.Error)
        InkDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前余量", color = PaperPalette.Muted, style = MaterialTheme.typography.bodySmall)
            Text(remainingLabel(item.remainingQuantity, item.quantityUnit), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { stock = true }, enabled = !busy, modifier = Modifier.align(Alignment.End).paperBorder()) {
                Text("更新余量"); Spacer(Modifier.width(8.dp)); InkIcon(InkSymbol.NEXT)
            }
        }
        InkDivider()
        Text("存放位置", color = PaperPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Text(item.information.storageLocation.ifBlank { "尚未填写" }, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        PaperDisclosure("包装与药品资料", identity, { identity = !identity }) {
            MedicineInformationDetails(item.information, MedicineInformationGroup.IDENTITY, exclude = setOf(MedicineInformationField.STORAGE_LOCATION))
            val hasIdentity = MedicineInformationField.entries.any {
                it.group == MedicineInformationGroup.IDENTITY && it != MedicineInformationField.STORAGE_LOCATION && item.information[it].isNotBlank()
            }
            if (!hasIdentity) Text("尚未补充药品资料。")
        }
        PaperDisclosure("药品图", photos, { photos = !photos }, subtitle = if (item.photos().isEmpty()) "尚未添加" else "${item.photos().size} 张") {
            if (item.photos().isNotEmpty()) PhotoStrip(item.photos(), cover = item.photos().firstOrNull())
            if (item.photos().size > 1) Text("旧照片已保留，可在编辑页确认仅保留一张。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = edit, enabled = !busy) { Text("编辑资料 / 补拍照片") }
        }
        PaperDisclosure("说明书原文", leaflet, { leaflet = !leaflet }) {
            if (item.efficacy.isNotBlank()) {
                Text("适应症 / 功能主治（原文）", style = MaterialTheme.typography.titleSmall)
                Text(item.efficacy)
            }
            val hasLeaflet = MedicineInformationField.entries.any { it.group == MedicineInformationGroup.LEAFLET && item.information[it].isNotBlank() }
            if (item.efficacy.isBlank() && !hasLeaflet) Text("尚未记录说明书，可编辑填写或补拍说明书照片。")
            MedicineInformationDetails(item.information, MedicineInformationGroup.LEAFLET)
        }
        LeafletDisclaimer()
        if (item.notes.isNotBlank()) PaperDisclosure("备注", notes, { notes = !notes }) { Text(item.notes) }
        if (item.ocrRawText.isNotBlank()) PaperDisclosure("识别原文", raw, { raw = !raw }) { Text(item.ocrRawText, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { confirmRemove = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("移出药箱", color = MaterialTheme.colorScheme.error) }
    }
    if (stock) StockDialog(item, busy, { stock = false }, updateStock)
    if (confirmRemove) AlertDialog(
        onDismissRequest = { if (!busy) confirmRemove = false },
        title = { Text("移出药箱？") }, text = { Text("将移除“${item.name}”这条库存记录。移除后可通过提示条撤销。") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; remove() }, enabled = !busy) { Text("确认移除") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }, enabled = !busy) { Text("保留") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockDialog(item: MedicineEntity, busy: Boolean, dismiss: () -> Unit, save: (String, String, (Boolean) -> Unit) -> Unit) {
    var quantity by rememberSaveable(item.id) { mutableStateOf(item.remainingQuantity?.toString().orEmpty()) }
    var unit by rememberSaveable(item.id) { mutableStateOf(item.quantityUnit) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("更新余量", style = PaperHeading)
            Text(item.name, color = PaperPalette.Muted)
            QuantityFields(quantity, unit, !busy, { quantity = it; error = null }, { unit = it; error = null })
            Text("留空代表未知，0 代表已用完。这里只记录库存，不推算服用剂量。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
            error?.let { Text(it, color = PaperPalette.Error) }
            PenButton(if (busy) "更新中…" else "确认更新", onClick = {
                error = quantityError(quantity, unit)
                if (error == null) save(quantity, unit) { success -> if (success) dismiss() else error = "更新失败，请重试" }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth(), icon = InkSymbol.CHECK)
            TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("取消") }
        }
    }
}

@Composable
fun PhotoStrip(
    photos: List<String>, enabled: Boolean = true, onCrop: ((String) -> Unit)? = null,
    cover: String? = null, onSetCover: ((String) -> Unit)? = null
) {
    var enlarged by rememberSaveable { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        photos.forEachIndexed { index, photo ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(
                    model = photo, contentDescription = "查看照片 ${index + 1}", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(156.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable { enlarged = photo }
                )
                Text(if (photo == cover) "药品图 · 点击放大" else "照片 ${index + 1} · 点击放大", style = MaterialTheme.typography.labelSmall)
                if (onSetCover != null) TextButton(onClick = { onSetCover(photo) }, enabled = enabled && photo != cover) {
                    Text(if (photo == cover) "已选为药品图" else "设为药品图")
                }
                if (onCrop != null) TextButton(onClick = { onCrop(photo) }, enabled = enabled) { Text("框选重识别") }
            }
        }
    }
    enlarged?.let { photo ->
        Dialog(onDismissRequest = { enlarged = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember(photo) { mutableFloatStateOf(1f) }
            var offset by remember(photo) { mutableStateOf(Offset.Zero) }
            Box(Modifier.fillMaxSize().background(Color(0xFF18201D))) {
                AsyncImage(model = photo, contentDescription = "放大照片，可双指缩放", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(photo) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
                TextButton(onClick = { enlarged = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("关闭", color = Color.White) }
                Text("双指缩放查看包装文字", Modifier.align(Alignment.BottomCenter).padding(24.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
