package com.thousandofwind.pharmacy

import java.net.URI

const val UPDATE_REPOSITORY = "ThousandOfWind/Pharmacy-In-Android"
const val MAX_APK_BYTES = 150L * 1024 * 1024

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

/** GitHub redirects public release downloads to these HTTPS asset hosts. */
fun isTrustedUpdateConnection(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
        uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
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
