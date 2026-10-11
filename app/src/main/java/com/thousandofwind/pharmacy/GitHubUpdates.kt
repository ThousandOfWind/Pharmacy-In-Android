package com.thousandofwind.pharmacy

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Public GitHub requests only; never sends account credentials or medicine data. */
class GitHubUpdates {
    suspend fun latest(): ReleaseUpdate? {
        val releaseText = text("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest", allowNotFound = true)
            ?: return null
        val release = JSONObject(releaseText)
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "最新发布不是可用的正式更新" }
        val tag = release.getString("tag_name")
        val code = releaseVersionCode(tag) ?: error("最新发布的版本格式不支持")
        val assets = release.getJSONArray("assets")
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
        val metadata = byName["update.json"] ?: error("最新发布缺少更新信息")
        val apk = byName["app-debug.apk"] ?: error("最新发布缺少 APK")
        require(isTrustedReleaseAsset(metadata.getString("browser_download_url"), tag, "update.json") &&
            isTrustedReleaseAsset(apk.getString("browser_download_url"), tag, "app-debug.apk")) { "更新下载地址不属于本仓库" }
        val info = JSONObject(text(metadata.getString("browser_download_url")) ?: error("无法读取更新信息"))
        val sha = info.getString("sha256")
        val size = apk.getLong("size")
        require(info.getLong("versionCode") == code && info.getString("versionName") == tag.removePrefix("v") &&
            info.getLong("size") == size && validUpdateMetadata(tag, code, sha, size)) { "更新信息校验失败" }
        return ReleaseUpdate(tag, code, release.optString("body").take(3000), sha, size)
    }

    suspend fun download(update: ReleaseUpdate, destination: File, progress: (Int) -> Unit) {
        val connection = connect(update.apkUrl) ?: error("APK 不存在")
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

    private suspend fun text(url: String, allowNotFound: Boolean = false): String? {
        val connection = connect(url, allowNotFound) ?: return null
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
            return output.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun connect(address: String, allowNotFound: Boolean = false): HttpURLConnection? {
        var url = address
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(isTrustedUpdateConnection(url)) { "更新连接地址不受信任" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Pharmacy-Android-Updater")
            connection.setRequestProperty("Accept", if (URL(url).host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            val status = try { connection.responseCode } catch (e: Exception) { connection.disconnect(); throw e }
            when (status) {
                200 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    require(!location.isNullOrBlank()) { "更新下载跳转无效" }
                    url = URL(URL(url), location).toString()
                }
                else -> {
                    connection.disconnect()
                    if (status == 404 && allowNotFound) return null
                    throw IOException(when (status) {
                        403, 429 -> "GitHub 暂时限制访问，请稍后重试"
                        404 -> "更新文件不存在，请稍后重试"
                        else -> "GitHub 请求失败（HTTP $status）"
                    })
                }
            }
        }
        error("更新下载跳转次数过多")
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
