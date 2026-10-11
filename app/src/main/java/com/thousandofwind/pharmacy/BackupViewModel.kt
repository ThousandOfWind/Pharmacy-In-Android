package com.thousandofwind.pharmacy

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Contents are staged privately and never put in SavedStateHandle or logs.
data class BackupUiState(
    val busy: Boolean = false, val preview: BackupPreview? = null, val message: String? = null,
    val exported: BackupExportResult? = null, val restored: BackupRestoreResult? = null
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BackupStore(application)
    private val mutable = MutableStateFlow(BackupUiState())
    val uiState = mutable.asStateFlow()
    private var pending: PendingBackup? = null

    fun export(uri: Uri) {
        if (mutable.value.busy) return
        discard()
        mutable.value = BackupUiState(busy = true)
        viewModelScope.launch {
            try {
                val result = store.export(uri)
                mutable.value = BackupUiState(exported = result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = BackupUiState(message = "备份未完成：${error.message ?: "请重试"}。原药箱未改动；若已产生目标文件，请删除不完整文件。")
            }
        }
    }

    fun prepare(uri: Uri) {
        if (mutable.value.busy) return
        discard()
        mutable.value = BackupUiState(busy = true)
        viewModelScope.launch {
            var staged: PendingBackup? = null
            try {
                staged = store.prepare(uri)
                val preview = store.preview(staged)
                pending = staged
                mutable.value = BackupUiState(preview = preview)
            } catch (error: Exception) {
                staged?.let { withContext(Dispatchers.IO + NonCancellable) { store.discard(it) } }
                if (error is CancellationException) throw error
                mutable.value = BackupUiState(message = "未恢复：${error.message ?: "备份无法读取"}。现有药箱未改动。")
            }
        }
    }

    fun restore() {
        val staged = pending ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                pending = null // Ownership transfers to this coroutine before the first suspension.
                val result = store.restore(staged)
                mutable.value = BackupUiState(restored = result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = BackupUiState(message = "恢复失败：${error.message ?: "请重试"}。现有药箱未改动。")
            } finally {
                pending = null
                withContext(Dispatchers.IO + NonCancellable) { store.discard(staged) }
            }
        }
    }

    fun discard() {
        if (mutable.value.busy) return
        pending?.let { staged -> viewModelScope.launch(Dispatchers.IO) { store.discard(staged) } }
        pending = null
        mutable.value = BackupUiState()
    }

    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCleared() {
        val staged = pending
        pending = null
        if (staged != null) cleanupScope.launch { store.discard(staged) }
        super.onCleared()
    }
}
