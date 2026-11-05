package com.sehirnotlari

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionsTest {
    @Test
    fun newerTagIsAnUpdate() {
        assertTrue(ReleaseVersions.isNewer("v1.1", "1.0", 1))
        assertTrue(ReleaseVersions.isNewer("V1.0.1", "1.0", 1))
        assertTrue(ReleaseVersions.isNewer("v1.10", "1.9", 1))
        assertTrue(ReleaseVersions.isNewer("v2", "1.0", 1))
        assertTrue(ReleaseVersions.isNewer("  v1.2 ", "1.0", 1))
    }

    @Test
    fun sameOrOlderTagIsNotAnUpdate() {
        assertFalse(ReleaseVersions.isNewer("v1.0", "1.0", 1))
        assertFalse(ReleaseVersions.isNewer("v1.0.0", "1.0", 1))
        assertFalse(ReleaseVersions.isNewer("v1", "1.0", 1))
        assertFalse(ReleaseVersions.isNewer("v1.2", "1.10", 1))
        assertFalse(ReleaseVersions.isNewer("v0.9", "1.0", 9))
        assertFalse(ReleaseVersions.isNewer("v1.0", "1.0", 5))
    }

    @Test
    fun versionCodeSuffixBreaksTies() {
        assertTrue(ReleaseVersions.isNewer("v1.0+2", "1.0", 1))
        assertTrue(ReleaseVersions.isNewer("1.0 (3)", "1.0", 2))
        assertFalse(ReleaseVersions.isNewer("v1.0+1", "1.0", 1))
        assertFalse(ReleaseVersions.isNewer("v1.0+1", "1.0", 4))
        assertTrue(ReleaseVersions.isNewer("v1.1+1", "1.0", 99))
    }

    @Test
    fun unreadableTagsAreIgnored() {
        assertFalse(ReleaseVersions.isNewer("", "1.0", 1))
        assertFalse(ReleaseVersions.isNewer("latest", "1.0", 1))
        assertFalse(ReleaseVersions.isNewer("v1..2", "1.0", 1))
    }

    @Test
    fun displayVersionDropsBuildSuffix() {
        assertEquals("v1.1", ReleaseVersions.displayVersion("v1.1"))
        assertEquals("v1.1", ReleaseVersions.displayVersion("1.1"))
        assertEquals("v1.0", ReleaseVersions.displayVersion("V1.0+3"))
        assertEquals("v1.2", ReleaseVersions.displayVersion("1.2 (4)"))
        assertEquals("", ReleaseVersions.displayVersion("  "))
    }

    @Test
    fun apkAssetPrefersReleaseOverLargerDebug() {
        val chosen = selectApkAsset(
            listOf(
                ReleaseAsset("app-debug.apk", "application/vnd.android.package-archive", 90, "https://example.com/debug.apk"),
                ReleaseAsset("app-release.apk", "application/vnd.android.package-archive", 10, "https://example.com/release.apk"),
                ReleaseAsset("sources.zip", "application/zip", 5, "https://example.com/sources.zip")
            )
        )
        assertEquals("app-release.apk", chosen?.name)
    }

    @Test
    fun apkAssetUsesOnlyApkAndSkipsEmpty() {
        assertNull(
            selectApkAsset(
                listOf(ReleaseAsset("notes.txt", "text/plain", 1, "https://example.com/notes.txt"))
            )
        )
        val onlyDebug = selectApkAsset(
            listOf(ReleaseAsset("app-debug.apk", "application/octet-stream", 4, "https://example.com/a.apk"))
        )
        assertEquals("app-debug.apk", onlyDebug?.name)
    }
}