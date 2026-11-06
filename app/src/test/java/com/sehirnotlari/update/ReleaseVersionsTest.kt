package com.sehirnotlari.update

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
    fun sameVersionMatchesTagAndApkVersionName() {
        assertTrue(ReleaseVersions.sameVersion("v1.4", "1.4"))
        assertTrue(ReleaseVersions.sameVersion("v1.4", "1.4.0"))
        assertTrue(ReleaseVersions.sameVersion("v1.4+5", "1.4"))
        assertFalse(ReleaseVersions.sameVersion("v1.4", "1.3"))
        assertFalse(ReleaseVersions.sameVersion("v1.4", "1.5"))
        assertFalse(ReleaseVersions.sameVersion("v1.4", ""))
        assertFalse(ReleaseVersions.sameVersion("latest", "1.4"))
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

    @Test
    fun publishedV13ReleaseParsesAndIsOfferedOnlyToOlderVersions() {
        assertEquals(
            "https://api.github.com/repos/alperbulent1175-design/sehirnotlari/releases?per_page=10",
            UpdateSource.GITHUB.releasesUrl
        )
        val body = """
            {"tag_name":"v1.3","name":"Şehir Notları v1.3","draft":false,"prerelease":false,"body":"## Şehir Notları v1.3","assets":[
              {"name":"sehirnotlari-1.3.apk","state":"uploaded","content_type":"application/vnd.android.package-archive","size":11529036,
               "digest":"sha256:362ec83a5683d8ced8f2eb52d7c35620f0c12663c65f9475e2c15cfd3bf7308f",
               "browser_download_url":"https://github.com/alperbulent1175-design/sehirnotlari/releases/download/v1.3/sehirnotlari-1.3.apk"},
              {"name":"sehirnotlari-1.3.apk.sha256","state":"uploaded","content_type":"text/plain","size":86,
               "digest":"sha256:3d63a07c19fef91fa7ae28b7349ebfa18c5bd2e17a649095bd9853288ec9f67e",
               "browser_download_url":"https://github.com/alperbulent1175-design/sehirnotlari/releases/download/v1.3/sehirnotlari-1.3.apk.sha256"}]}
        """.trimIndent()
        val release = ReleaseParser.parse(body)
        assertEquals("v1.3", release.tag)
        assertEquals("sehirnotlari-1.3.apk", release.apk?.name)
        assertEquals(11529036L, release.apk?.size)
        assertEquals("362ec83a5683d8ced8f2eb52d7c35620f0c12663c65f9475e2c15cfd3bf7308f", release.apk?.sha256)
        assertNull(release.blockReason)
        assertTrue(ReleaseVersions.isNewer(release.tag, "1.2", 3))
        assertFalse(ReleaseVersions.isNewer(release.tag, "1.3", 4))
        assertFalse(ReleaseVersions.isNewer(release.tag, "1.4", 5))
    }
}
