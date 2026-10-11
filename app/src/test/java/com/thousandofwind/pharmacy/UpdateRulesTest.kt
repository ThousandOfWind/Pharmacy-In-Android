package com.thousandofwind.pharmacy

import org.junit.Assert.*
import org.junit.Test

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
    }

    @Test fun initialAssetsAreRepositoryBoundAndRedirectsStayOnTrustedHttpsHosts() {
        val url = releaseAssetUrl("v1.0.42", "app-debug.apk")
        assertTrue(isTrustedReleaseAsset(url, "v1.0.42", "app-debug.apk"))
        assertFalse(isTrustedReleaseAsset(url.replace("ThousandOfWind", "other"), "v1.0.42", "app-debug.apk"))
        assertFalse(isTrustedReleaseAsset("$url?extra=1", "v1.0.42", "app-debug.apk"))
        assertTrue(isTrustedUpdateConnection("https://release-assets.githubusercontent.com/asset?download=1"))
        listOf("http://github.com/asset", "https://github.com.evil.test/asset", "https://user@github.com/asset",
            "https://github.com:443/asset", "https://example.com/asset").forEach { assertFalse(isTrustedUpdateConnection(it)) }
    }
}
