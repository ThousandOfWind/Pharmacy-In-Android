package com.thousandofwind.pharmacy

import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

const val UPDATE_REPOSITORY = "ThousandOfWind/Pharmacy-In-Android"
const val MAX_APK_BYTES = 150L * 1024 * 1024
const val LATEST_RELEASE_PAGE = "https://github.com/$UPDATE_REPOSITORY/releases/latest"
const val LATEST_UPDATE_METADATA = "$LATEST_RELEASE_PAGE/download/update.json"

/** The CI run number is the patch version; versionCode is run number + 1. */
fun releaseVersionCode(tag: String): Long? {
    val run = Regex("v1\\.0\\.([1-9][0-9]*)").matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    return run.takeIf { it < Int.MAX_VALUE }?.plus(1)
}

fun releaseAssetUrl(tag: String, name: String) =
    "https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/$name"

fun isTrustedReleaseAsset(url: String, tag: String, name: String): Boolean =
    releaseVersionCode(tag) != null && name in setOf("update.json", "app-debug.apk") &&
        url == releaseAssetUrl(tag, name)

/** Extract only this repository's exact, version-pinned metadata redirect; never trust JSON URLs. */
fun releaseMetadataTag(url: String): String? {
    val prefix = "https://github.com/$UPDATE_REPOSITORY/releases/download/"
    if (!url.startsWith(prefix)) return null
    val tag = url.removePrefix(prefix).removeSuffix("/update.json")
    return tag.takeIf { isTrustedReleaseAsset(url, it, "update.json") }
}

fun checkedReleaseMetadata(versionName: String, code: Long, sha256: String, size: Long, resolvedTag: String): ReleaseUpdate {
    val tag = "v$versionName"
    require(tag == resolvedTag && validUpdateMetadata(tag, code, sha256, size)) { "更新信息与发布版本不一致" }
    // Pin the download to the checked version, not a latest URL that could change mid-download.
    return ReleaseUpdate(tag, code, "", sha256, size)
}

internal class UpdateRequestFailure(val notice: String) : java.io.IOException()

internal fun updateFailureNotice(error: Exception): String = when (error) {
    is UpdateRequestFailure -> error.notice
    is UnknownHostException -> "无法解析 GitHub 下载地址。请检查网络和 VPN 是否包含药箱应用。"
    is SocketTimeoutException -> "连接 GitHub 发布文件超时。可换网络后重试，或用浏览器下载。"
    is SSLException -> "无法建立安全连接。请检查设备时间和网络；未降低 HTTPS 校验要求。"
    is IllegalArgumentException, is IllegalStateException -> "更新信息或文件校验失败。请稍后重试，或查看官方发布页。"
    else -> "无法读取 GitHub 发布文件。请检查药箱应用的网络/VPN 设置，或用浏览器下载。"
}

/** GitHub redirects public release downloads to these HTTPS asset hosts. */
fun isTrustedUpdateConnection(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
        uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}.getOrDefault(false)

fun validUpdateMetadata(tag: String, code: Long, sha256: String, size: Long): Boolean =
    releaseVersionCode(tag) == code && Regex("[0-9a-f]{64}").matches(sha256) && size in 1..MAX_APK_BYTES

data class ReleaseUpdate(
    val tag: String,
    val versionCode: Long,
    val notes: String,
    val sha256: String,
    val size: Long
) {
    val versionName: String get() = tag.removePrefix("v")
    val apkUrl: String get() = releaseAssetUrl(tag, "app-debug.apk")
}
