package com.thousandofwind.pharmacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class LegalDocument(val title: String, val asset: String) {
    PROJECT("项目许可", "legal/project-license.txt"),
    THIRD_PARTY("第三方通知", "legal/third-party-notices.txt"),
    PRIVACY("隐私说明", "legal/privacy.txt")
}

/** Fixed packaged assets only. Never opens a network URL or reads user files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegalDocumentDialog(document: LegalDocument, dismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    var paragraphs by remember(document) { mutableStateOf<List<String>?>(null) }
    var failed by remember(document) { mutableStateOf(false) }
    LaunchedEffect(document) {
        try {
            paragraphs = withContext(Dispatchers.IO) {
                assets.open(document.asset).bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText().split(Regex("\\r?\\n[ \\t]*\\r?\\n")).flatMap { paragraph ->
                        // Bound individual layout work even for very long supplier paragraphs.
                        paragraph.chunked(4000)
                    }.filter { it.isNotBlank() }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(document.title) }, navigationIcon = {
                InkIconButton(InkSymbol.BACK, "返回关于", dismiss)
            })
        }) { padding ->
            val content = paragraphs
            if (content == null) Column(Modifier.padding(padding).padding(20.dp)) {
                Text(if (failed) "无法读取随包文本，请关闭后重试。" else "正在读取离线文本…")
            } else LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                items(content) { paragraph ->
                    SelectionContainer { Text(paragraph, Modifier.padding(vertical = 6.dp)) }
                }
            }
        }
    }
}
