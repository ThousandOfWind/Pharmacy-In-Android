package com.thousandofwind.pharmacy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CabinetScreen(
    state: UiState, search: (String) -> Unit, filter: (CabinetFilter) -> Unit, filterTag: (String?) -> Unit,
    add: () -> Unit, open: (MedicineEntity) -> Unit
) {
    val rows = state.visible
    val listState = rememberLazyListState()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val filtered = state.session.filter != CabinetFilter.ALL || state.session.tag != null
    LaunchedEffect(state.session.highlightId, rows.map { it.id }) {
        val index = rows.indexOfFirst { it.id == state.session.highlightId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    fun reset() { search(""); filter(CabinetFilter.ALL); filterTag(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        when {
            !state.loaded -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在打开药箱…", Modifier.padding(vertical = 24.dp))
            }
            state.databaseError != null -> Text(state.databaseError, color = MaterialTheme.colorScheme.error)
            state.medicines.isEmpty() -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(painterResource(R.drawable.ink_empty_cabinet), null, Modifier.size(218.dp))
                    Text("先收好，第一盒。", style = PaperHeading)
                    Text("拍照或手动记下家里的药，下次需要时，就好找了。", color = PaperPalette.Muted)
                    PenButton("添加第一件药品", add, icon = InkSymbol.PLUS)
                    Text("从一个药名开始，其他慢慢补充。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                }
            }
            else -> {
                PaperField(
                    state.session.search, search, placeholder = { Text("找药名、品牌或标签") },
                    modifier = Modifier.fillMaxWidth(), accessibilityLabel = "搜索药名、品牌或标签", leadingIcon = { InkIcon(InkSymbol.SEARCH) },
                    trailingIcon = {
                        if (state.session.search.isNotEmpty()) TextButton(onClick = { search("") }) { Text("清除") }
                    }
                )
                val expired = state.medicines.count { expiryStatus(it.expiryDate) == ExpiryStatus.EXPIRED }
                val soon = state.medicines.count { expiryStatus(it.expiryDate) == ExpiryStatus.SOON }
                if (state.session.search.isBlank() && !filtered && (expired > 0 || soon > 0)) {
                    TextButton(
                        onClick = { filter(CabinetFilter.ATTENTION); filterTag(null) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                    ) {
                        InkIcon(InkSymbol.WARNING, color = PaperPalette.Error)
                        Spacer(Modifier.width(10.dp))
                        Text(listOfNotNull(
                            if (expired > 0) "$expired 件已过期" else null,
                            if (soon > 0) "$soon 件临期" else null
                        ).joinToString(" · "), Modifier.weight(1f), color = PaperPalette.Error)
                        InkIcon(InkSymbol.NEXT, color = PaperPalette.Error)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            state.session.search.isNotBlank() -> "找到 ${rows.size} 条记录"
                            state.session.filter == CabinetFilter.ATTENTION -> "需要留意的药"
                            filtered -> "筛选结果"
                            else -> "药箱里的药"
                        }, Modifier.weight(1f), style = if (state.session.search.isBlank()) PaperHeading else MaterialTheme.typography.titleSmall,
                        color = PaperPalette.Muted
                    )
                    TextButton(onClick = { showFilters = true }) { InkIcon(InkSymbol.FILTER); Spacer(Modifier.width(5.dp)); Text("筛选") }
                }
                if (filtered) {
                    TextButton(onClick = { filter(CabinetFilter.ALL); filterTag(null) }, modifier = Modifier.paperBorder()) {
                        Text(listOfNotNull(state.session.filter.takeUnless { it == CabinetFilter.ALL }?.label, state.session.tag).joinToString(" · "))
                        Spacer(Modifier.width(8.dp)); InkIcon(InkSymbol.CLOSE)
                    }
                }
                if (rows.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("没有符合条件的药品", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = ::reset) { Text("查看全部药品") }
                    }
                } else {
                    LazyColumn(
                        state = listState, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp)
                    ) {
                        items(rows, key = { it.id }) { item -> MedicineRow(item, item.id == state.session.highlightId, { open(item) }) }
                    }
                }
            }
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("按需要找", style = PaperHeading)
            Text("有效期", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CabinetFilter.entries.filterNot { it == CabinetFilter.ATTENTION }.forEach { entry ->
                    FilterChip(selected = state.session.filter == entry, onClick = { filter(entry) }, label = { Text(entry.label) })
                }
            }
            val tags = normalizeMedicineTags(state.availableTags + listOfNotNull(state.session.tag))
            if (tags.isNotEmpty()) {
                Text("标签", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.session.tag == null, onClick = { filterTag(null) }, label = { Text("不限标签") })
                    tags.forEach { tag ->
                        FilterChip(selected = tag.equals(state.session.tag, ignoreCase = true), onClick = { filterTag(tag) }, label = { Text(tag) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { filter(CabinetFilter.ALL); filterTag(null) }) { Text("重置") }
                PenButton("查看结果", { showFilters = false }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MedicineRow(item: MedicineEntity, highlighted: Boolean, open: () -> Unit) {
    val background by animateColorAsState(if (highlighted) PaperPalette.Wash.copy(alpha = .5f) else PaperPalette.Paper, tween(220), label = "saved record")
    Column {
        Surface(onClick = open, color = background, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val photo = item.photos().firstOrNull()
                if (photo == null) Image(painterResource(R.drawable.ink_package), null, Modifier.width(44.dp).height(58.dp))
                else AsyncImage(model = photo, contentDescription = "${item.name}的包装照片", modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small).background(PaperPalette.Wash), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val summary = listOf(item.information.brandName.takeUnless { it.equals(item.name, ignoreCase = true) }.orEmpty(), item.information.specification).filter(String::isNotBlank).joinToString(" · ")
                    if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when (expiryStatus(item.expiryDate)) {
                        ExpiryStatus.EXPIRED -> Text("已过期", color = PaperPalette.Error, style = MaterialTheme.typography.bodySmall)
                        ExpiryStatus.SOON -> Text("90 天内到期", color = PaperPalette.Amber, style = MaterialTheme.typography.bodySmall)
                        ExpiryStatus.UNKNOWN -> Text(if (item.expiryDate.isBlank()) "日期待补充" else "有效期需核对", color = PaperPalette.Muted, style = MaterialTheme.typography.bodySmall)
                        ExpiryStatus.VALID -> Unit
                    }
                    if (item.remainingQuantity == 0) Text("已用完", color = PaperPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                InkIcon(InkSymbol.NEXT, modifier = Modifier.size(18.dp), color = PaperPalette.Frame)
            }
        }
        InkDivider()
    }
}

@Composable
fun ExpiryLabel(value: String) {
    val status = expiryStatus(value)
    Text(
        when (status) {
            ExpiryStatus.UNKNOWN -> if (value.isBlank()) "有效期未填写" else "有效期需核对：$value"
            ExpiryStatus.VALID -> "有效期至 $value"
            else -> "${status.label} · $value"
        }, color = when (status) {
            ExpiryStatus.EXPIRED -> PaperPalette.Error
            ExpiryStatus.SOON -> PaperPalette.Amber
            ExpiryStatus.UNKNOWN -> PaperPalette.Muted
            ExpiryStatus.VALID -> PaperPalette.Green
        }, style = MaterialTheme.typography.bodyMedium
    )
}
