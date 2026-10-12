package com.thousandofwind.pharmacy

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class UpdateRejected(val notice: String) : Exception()

data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val update: ReleaseUpdate? = null,
    val readyApk: File? = null,
    val message: String = "直接检查公开发布文件，不使用 GitHub API，也不上传药箱数据。"
)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = GitHubUpdates()
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    private val installed = application.packageManager.getPackageInfo(application.packageName, 0)
    val versionName: String = installed.versionName ?: "未知"
    val versionCode: Long = PackageInfoCompat.getLongVersionCode(installed)

    fun check() {
        if (mutable.value.checking || mutable.value.downloading) return
        mutable.value = AppUpdateState(checking = true, message = "正在读取最新发布的 update.json（不使用 API）…")
        viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { client.latest() }
                mutable.value = AppUpdateState(update = latest.takeIf { it.versionCode > versionCode },
                    message = when {
                        latest.versionCode <= versionCode -> "当前已是最新版本。"
                        else -> "发现新版本 ${latest.versionName}，是否下载？"
                    })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = AppUpdateState(message = "检查失败：${updateFailureNotice(e)}")
            }
        }
    }

    fun download() {
        val update = mutable.value.update ?: return
        if (mutable.value.checking || mutable.value.downloading || mutable.value.readyApk != null) return
        mutable.update { it.copy(downloading = true, progress = 0, message = "正在下载并校验 APK…") }
        viewModelScope.launch {
            var partial: File? = null
            try {
                val ready = withContext(Dispatchers.IO) {
                    val folder = File(getApplication<Application>().cacheDir, "updates")
                    check(folder.isDirectory || folder.mkdirs())
                    // Do not remove photos, database files, or files outside the update cache.
                    folder.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".apk") }?.forEach {
                        check(it.delete()) { "无法清理旧更新缓存" }
                    }
                    val temp = File(folder, "${update.tag}.part")
                    partial = temp
                    client.download(update, temp) { percent -> mutable.update { it.copy(progress = percent) } }
                    verifyPackage(temp, update)
                    val apk = File(folder, "${update.tag}.apk")
                    check(temp.renameTo(apk))
                    apk
                }
                mutable.update { it.copy(downloading = false, readyApk = ready, message = "校验通过。安装将由 Android 确认，不会自动卸载或清除药箱。") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(downloading = false, message = if (e is UpdateRejected) e.notice
                    else "下载或校验失败，未安装任何更新。${updateFailureNotice(e)}") }
            } finally { partial?.delete() }
        }
    }

    fun report(message: String) { mutable.update { it.copy(message = message) } }

    fun missingApk() { mutable.update { it.copy(readyApk = null, message = "下载文件已被清理，请重新下载。") } }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, update: ReleaseUpdate) {
        val app = getApplication<Application>()
        val pm = app.packageManager
        // Some Android 13/OEM archive parsers omit signingInfo; request legacy certificates too.
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateRejected("下载的文件不是有效 APK，未安装。")
        if (candidate.packageName != app.packageName || PackageInfoCompat.getLongVersionCode(candidate) != update.versionCode ||
            update.versionCode <= versionCode) throw UpdateRejected("APK 包名或版本不符合要求，未安装。")
        val current = pm.getPackageInfo(app.packageName, flags)
        val currentSigners = signers(current)
        val newSigners = signers(candidate)
        if (currentSigners.isEmpty() || currentSigners != newSigners) throw UpdateRejected(
            "APK 签名与当前应用不一致，不能覆盖安装。请保留现有应用及药箱数据，不要卸载；需先备份后再迁移。")
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures
            else info.signatures
        return signatures?.map { it.toByteArray().hex() }?.toSet().orEmpty()
    }
}
