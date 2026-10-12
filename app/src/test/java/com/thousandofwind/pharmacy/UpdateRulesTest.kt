package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class UpdateRulesTest {
    @Test fun versionsUseStrictTagsAndMonotonicCodesWithoutOverflow() {
        assertEquals(2L, releaseVersionCode("v1.0.1"))
        assertEquals(43L, releaseVersionCode("v1.0.42"))
        listOf("v1.0.0", "v1.0.01", "1.0.1", "v2.0.1", "v1.0.2147483647", "v1.0.999999999999999999999").forEach {
            assertNull(releaseVersionCode(it))
        }
    }

    @Test fun metadataMustMatchTagChecksumAndBoundedApkSize() {
        val sha = "a".repeat(64)
        assertTrue(validUpdateMetadata("v1.0.42", 43, sha, MAX_APK_BYTES))
        assertFalse(validUpdateMetadata("v1.0.42", 42, sha, 1))
        assertFalse(validUpdateMetadata("v1.0.42", 43, "invalid", 1))
        listOf(0L, MAX_APK_BYTES + 1).forEach { assertFalse(validUpdateMetadata("v1.0.42", 43, sha, it)) }
        val update = checkedReleaseMetadata("1.0.42", 43, sha, 1, "v1.0.42")
        assertEquals(releaseAssetUrl("v1.0.42", "app-debug.apk"), update.apkUrl)
        assertThrows(IllegalArgumentException::class.java) { checkedReleaseMetadata("1.0.43", 44, sha, 1, "v1.0.42") }
        assertThrows(IllegalArgumentException::class.java) { checkedReleaseMetadata("1.0.42", 42, sha, 1, "v1.0.42") }
    }

    @Test fun initialAssetsAreRepositoryBoundAndRedirectsStayOnTrustedHttpsHosts() {
        assertEquals("https://github.com/$UPDATE_REPOSITORY/releases/latest/download/update.json", LATEST_UPDATE_METADATA)
        val metadata = releaseAssetUrl("v1.0.42", "update.json")
        assertEquals("v1.0.42", releaseMetadataTag(metadata))
        listOf(LATEST_UPDATE_METADATA, "$metadata?extra=1", metadata.replace("ThousandOfWind", "other"),
            metadata.replace("v1.0.42", "v1.0.042"), metadata.replace("update.json", "app-debug.apk")).forEach {
            assertNull(releaseMetadataTag(it))
        }
        val url = releaseAssetUrl("v1.0.42", "app-debug.apk")
        assertTrue(isTrustedReleaseAsset(url, "v1.0.42", "app-debug.apk"))
        assertFalse(isTrustedReleaseAsset(url.replace("ThousandOfWind", "other"), "v1.0.42", "app-debug.apk"))
        assertFalse(isTrustedReleaseAsset("$url?extra=1", "v1.0.42", "app-debug.apk"))
        assertTrue(isTrustedUpdateConnection("https://release-assets.githubusercontent.com/asset?download=1"))
        listOf("http://github.com/asset", "https://github.com.evil.test/asset", "https://user@github.com/asset",
            "https://github.com:443/asset", "https://example.com/asset", "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest")
            .forEach { assertFalse(isTrustedUpdateConnection(it)) }
    }

    @Test fun failuresGiveSafeActionableReasonsWithoutReflectingRawExceptionText() {
        val secretLikeUrl = "https://example.test/?private=do-not-show"
        assertTrue(updateFailureNotice(UnknownHostException(secretLikeUrl)).contains("VPN"))
        assertTrue(updateFailureNotice(SocketTimeoutException(secretLikeUrl)).contains("超时"))
        assertTrue(updateFailureNotice(SSLException(secretLikeUrl)).contains("安全连接"))
        assertTrue(updateFailureNotice(IllegalArgumentException(secretLikeUrl)).contains("校验失败"))
        assertFalse(updateFailureNotice(Exception(secretLikeUrl)).contains(secretLikeUrl))
        assertEquals("HTTP 403", updateFailureNotice(UpdateRequestFailure("HTTP 403")))
    }
}
