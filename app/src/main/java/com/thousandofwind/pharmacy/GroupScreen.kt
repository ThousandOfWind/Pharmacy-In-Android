package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GroupRow(group: CabinetGroup, matching: Int, highlighted: Boolean, open: () -> Unit) {
    Column {
        Surface(onClick = open, color = if (highlighted) PaperPalette.Wash else PaperPalette.Paper, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text("${groupBrandCount(group.records)} 个已填品牌 · ${group.records.size} 条库存" + if (matching != group.records.size) "（匹配 $matching 条）" else "", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
                val expired = group.records.count { expiryStatus(it.expiryDate) == ExpiryStatus.EXPIRED }
                val soon = group.records.count { expiryStatus(it.expiryDate) == ExpiryStatus.SOON }
                if (expired > 0) Text("其中 $expired 条已过期", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Error)
                if (soon > 0) Text("其中 $soon 条90天内到期", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Amber)
                if (group.records.all { it.remainingQuantity == 0 }) Text("这些库存已用完", style = MaterialTheme.typography.bodySmall)
                if (group.records.any { expiryStatus(it.expiryDate) == ExpiryStatus.UNKNOWN }) Text("含日期待补充或待核对的库存", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
            }
        }
        InkDivider()
    }
}

@Composable
fun GroupScreen(group: CabinetGroup, visible: List<MedicineEntity>, open: (MedicineEntity) -> Unit, add: () -> Unit) {
    val matching = group.records.filter { item -> visible.any { it.id == item.id } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(group.name, style = MaterialTheme.typography.headlineSmall)
        Text("${group.records.size} 条独立库存；品牌、规格、有效期和余量分别保留，不代表可相互替代。", style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted)
        if (matching.size != group.records.size) Text("当前条件匹配 ${matching.size} 条，以下展示组内全部库存，避免漏看异常。", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(sortedGroupStocks(group.records), key = { it.id }) { item ->
                MedicineRow(item, false, { open(item) }, showStock = true)
            }
        }
        PenButton("添加一条新库存", add, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupLinkDialog(item: MedicineEntity, all: List<MedicineEntity>, busy: Boolean, dismiss: () -> Unit, link: (MedicineEntity?, String?) -> Unit) {
    var name by rememberSaveable(item.id) { mutableStateOf(item.information.genericName.ifBlank { item.name }) }
    val options = cabinetGroups(all.filterNot { it.id == item.id || item.groupKey.isNotBlank() && it.groupKey == item.groupKey })
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("关联药品组", style = MaterialTheme.typography.titleLarge)
            Text("请核对通用名和剂型。这里只关联库存，不合并资料、不表示不同品牌或规格可替代。", style = MaterialTheme.typography.bodySmall)
            if (item.groupKey.isNotBlank()) Text("当前：${item.groupName}")
            options.forEach { group ->
                TextButton(onClick = { link(group.records.first(), null); dismiss() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("关联到 ${group.name}（${group.records.size} 条）")
                }
            }
            PaperField(name, { name = it }, label = "新药品组名称", modifier = Modifier.fillMaxWidth(), enabled = !busy,
                isError = groupNameError(name) != null)
            PenButton("创建并关联", { link(null, name); dismiss() }, Modifier.fillMaxWidth(), enabled = !busy && groupNameError(name) == null)
            if (item.groupKey.isNotBlank()) TextButton(onClick = { link(null, null); dismiss() }, enabled = !busy) { Text("移出药品组（保留库存）") }
        }
    }
}
