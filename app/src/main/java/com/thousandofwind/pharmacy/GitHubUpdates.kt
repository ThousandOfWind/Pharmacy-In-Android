package com.thousandofwind.pharmacy

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Public Release assets only; no REST API, account credentials or medicine data. */
class GitHubUpdates {
    suspend fun latest(): ReleaseUpdate {
        // GitHub's latest asset shortcut avoids the shared-IP anonymous REST API rate limit.
        val response = text(LATEST_UPDATE_METADATA)
        try {
            val info = JSONObject(response.body)
            // Reject fractional/string integers instead of allowing JSONObject's numeric coercion.
            require(info.get("versionCode") is Int || info.get("versionCode") is Long)
            require(info.get("size") is Int || info.get("size") is Long)
            return checkedReleaseMetadata(info.getString("versionName"), info.getLong("versionCode"),
                info.getString("sha256"), info.getLong("size"),
                response.releaseTag ?: error("未收到本仓库的版本化更新信息跳转"))
        } catch (_: JSONException) {
            throw UpdateRequestFailure("更新信息不是有效的发布元数据。请稍后重试，或查看官方发布页。")
        }
    }

    private data class UpdateConnection(val connection: HttpURLConnection, val releaseTag: String?)
    private data class MetadataText(val body: String, val releaseTag: String?)

    suspend fun download(update: ReleaseUpdate, destination: File, progress: (Int) -> Unit) {
        val connection = connect(update.apkUrl).connection
        try {
            val declared = connection.contentLengthLong
            require(declared == -1L || declared == update.size) { "APK 大小与发布信息不一致" }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            var lastProgress = -1
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        count += read
                        require(count <= update.size && count <= MAX_APK_BYTES) { "APK 超出预期大小" }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        val percent = (count * 100 / update.size).toInt()
                        if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                    }
                }
            }
            require(count == update.size && digest.digest().hex() == update.sha256) { "APK 完整性校验失败，请重新下载" }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun text(url: String): MetadataText {
        val response = connect(url)
        val connection = response.connection
        try {
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= 512 * 1024) { "更新信息过大" }
                    output.write(buffer, 0, count)
                }
            }
            return MetadataText(output.toString("UTF-8"), response.releaseTag)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun connect(address: String): UpdateConnection {
        var url = address
        var releaseTag: String? = null
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(isTrustedUpdateConnection(url)) { "更新连接地址不受信任" }
            releaseMetadataTag(url)?.let { tag ->
                require(releaseTag == null || releaseTag == tag) { "更新跳转版本不一致" }
                releaseTag = tag
            }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "Pharmacy-Android-Updater")
            connection.setRequestProperty("Accept", "application/octet-stream")
            val status = try { connection.responseCode } catch (e: Exception) { connection.disconnect(); throw e }
            when (status) {
                200 -> return UpdateConnection(connection, releaseTag)
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    require(!location.isNullOrBlank()) { "更新下载跳转无效" }
                    url = URL(URL(url), location).toString()
                }
                else -> {
                    connection.disconnect()
                    throw UpdateRequestFailure(when (status) {
                        403, 429 -> "GitHub 发布文件暂时拒绝或限制访问（HTTP $status）。请稍后重试，或用浏览器下载。"
                        404 -> "未找到正式发布的更新文件（HTTP 404）。可能尚未发布或附件缺失，请查看发布页。"
                        else -> "GitHub 发布文件请求失败（HTTP $status）。可稍后重试，或用浏览器下载。"
                    })
                }
            }
        }
        error("更新下载跳转次数过多")
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
