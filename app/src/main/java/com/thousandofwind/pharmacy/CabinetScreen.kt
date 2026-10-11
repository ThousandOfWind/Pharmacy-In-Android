package com.thousandofwind.pharmacy

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun CabinetScreen(
    state: UiState, search: (String) -> Unit, filter: (CabinetFilter) -> Unit, filterTag: (String?) -> Unit,
    add: () -> Unit, open: (MedicineEntity) -> Unit
) {
    val rows = state.visible
    val listState = rememberLazyListState()
    LaunchedEffect(state.session.highlightId, rows.map { it.id }) {
        val index = rows.indexOfFirst { it.id == state.session.highlightId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("找到家里的药，也记住它们的有效期。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.session.search, onValueChange = search, label = { Text("搜索名称、品牌、标签等") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            trailingIcon = { if (state.session.search.isNotEmpty()) TextButton(onClick = { search("") }) { Text("清除") } }
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CabinetFilter.entries.forEach { entry ->
                FilterChip(selected = state.session.filter == entry, onClick = { filter(entry) }, label = { Text(entry.label) })
            }
        }
        val tags = normalizeMedicineTags(state.availableTags + listOfNotNull(state.session.tag))
        if (tags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.session.tag == null, onClick = { filterTag(null) }, label = { Text("全部标签") })
                tags.forEach { tag ->
                    FilterChip(selected = tag.equals(state.session.tag, ignoreCase = true), onClick = { filterTag(tag) }, label = { Text(tag) })
                }
            }
        }
        when {
            !state.loaded -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在打开药箱…") }
            state.databaseError != null -> Text(state.databaseError, color = MaterialTheme.colorScheme.error)
            state.medicines.isEmpty() -> {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("药", Modifier.padding(28.dp), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("把家里的第一盒药放进来", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("拍照识别，核对后保存。信息可以以后补充。", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = add) { Text("添加第一件药品") }
                    Spacer(Modifier.height(48.dp))
                }
            }
            rows.isEmpty() -> {
                Text("没有符合条件的药品", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { search(""); filter(CabinetFilter.ALL); filterTag(null) }) { Text("查看全部药品") }
            }
            else -> {
                Text("${rows.size} 条库存记录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows, key = { it.id }) { item ->
                        MedicineCard(item, item.id == state.session.highlightId, { open(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicineCard(item: MedicineEntity, highlighted: Boolean, open: () -> Unit) {
    Card(
        onClick = open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.White)
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val photo = item.photos().firstOrNull()
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (photo == null) Text("药", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                else AsyncImage(model = photo, contentDescription = "${item.name}的照片", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val summary = listOf(item.information.brandName.takeUnless { it.equals(item.name, ignoreCase = true) }.orEmpty(),
                    item.information.specification).filter(String::isNotBlank).joinToString(" · ")
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ExpiryLabel(item.expiryDate)
                Text(remainingLabel(item.remainingQuantity, item.quantityUnit), style = MaterialTheme.typography.bodyMedium)
                if (item.tags().isNotEmpty()) MedicineTags(item.tags(), limit = 3)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ExpiryLabel(value: String) {
    val status = expiryStatus(value)
    val color = when (status) {
        ExpiryStatus.EXPIRED -> MaterialTheme.colorScheme.error
        ExpiryStatus.SOON -> Color(0xFF885C16)
        ExpiryStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        ExpiryStatus.VALID -> MaterialTheme.colorScheme.primary
    }
    Text(
        when (status) {
            ExpiryStatus.UNKNOWN -> if (value.isBlank()) "有效期未填写" else "有效期需核对：$value"
            ExpiryStatus.VALID -> "有效期至 $value"
            else -> "${status.label} · $value"
        }, color = color, style = MaterialTheme.typography.bodySmall
    )
}
