package com.sehirnotlari.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateReleaseTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun onlyThisRepositorysReleaseAssetsAreOfficial() {
        val tag = "v1.4"
        val name = "sehirnotlari-1.4.apk"
        assertTrue(UpdateSource.GITHUB.isOfficialAssetUrl(assetUrl(tag, name), tag, name))
        val refused = listOf(
            "http://github.com/alperbulent1175-design/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk",
            "https://github.com/someone/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk",
            "https://github.com/alperbulent1175-design/other/releases/download/v1.4/sehirnotlari-1.4.apk",
            "https://github.com/alperbulent1175-design/sehirnotlari/releases/download/v1.3/sehirnotlari-1.4.apk",
            "https://github.com.evil.com/alperbulent1175-design/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk",
            "https://evilgithub.com/alperbulent1175-design/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk",
            "https://user@github.com/alperbulent1175-design/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk",
            "https://github.com:8443/alperbulent1175-design/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk",
            "https://github.com/alperbulent1175-design/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk?x=1",
            "https://github.com/alperbulent1175-design/sehirnotlari/releases/download/v1.4/../v1.4/sehirnotlari-1.4.apk",
            "not a url",
        )
        refused.forEach { assertFalse(it, UpdateSource.GITHUB.isOfficialAssetUrl(it, tag, name)) }
        assertFalse(UpdateSource.GITHUB.isOfficialAssetUrl(assetUrl("..", name), "..", name))
    }

    @Test
    fun redirectsMayOnlyReachGitHubStorage() {
        assertTrue(UpdateSource.GITHUB.isAllowedDownloadUrl(STORAGE_URL))
        assertTrue(UpdateSource.GITHUB.isAllowedDownloadUrl("https://objects.githubusercontent.com/a/b"))
        assertFalse(UpdateSource.GITHUB.isAllowedDownloadUrl("http://release-assets.githubusercontent.com/a"))
        assertFalse(UpdateSource.GITHUB.isAllowedDownloadUrl("https://release-assets.githubusercontent.com.evil.io/a"))
        assertFalse(UpdateSource.GITHUB.isAllowedDownloadUrl("https://example.com/sehirnotlari.apk"))
        assertTrue(UpdateSource.GITHUB.isAllowedApiUrl(RELEASES_URL))
        assertFalse(UpdateSource.GITHUB.isAllowedApiUrl("https://api.github.com.evil.io/repos/x"))
    }

    @Test
    fun redirectChainIsCheckedHopByHop() {
        val github = FakeGitHub().apply { redirect = "https://example.com/x.apk" }
        val failure = assertThrows(UpdateFailure::class.java) {
            UpdateHttp.openChecked(
                github, assetUrl("v1.4"), emptyMap(),
                firstHop = { UpdateSource.GITHUB.isOfficialAssetUrl(it, "v1.4", "sehirnotlari-1.4.apk") },
                laterHop = UpdateSource.GITHUB::isAllowedDownloadUrl,
            )
        }
        assertEquals(FailureKind.SOURCE, failure.kind)
        assertEquals(listOf(assetUrl("v1.4")), github.requests)
    }

    @Test
    fun releaseFieldsAreReadForTheWhatsNewDialog() {
        val apk = fakeApk()
        val release = ReleaseParser.parse(releaseJson(apk = apk, body = "## Başlık\r\n- madde"))
        assertEquals("v1.4", release.tag)
        assertEquals("v1.4", release.label)
        assertEquals("Şehir Notları v1.4", release.title)
        assertEquals("## Başlık\n- madde", release.notes)
        assertEquals(apk.size.toLong(), release.apk?.size)
        assertEquals(ApkVerifier.sha256(apk), release.apk?.sha256)
        assertNull(release.blockReason)
    }

    @Test
    fun emptyNullAndHugeNotesAreHandled() {
        assertEquals("", ReleaseParser.parse(releaseJson(body = null)).notes)
        assertEquals("", ReleaseParser.parse(releaseJson(body = "")).notes)
        assertEquals("v1.4", ReleaseParser.parse(releaseJson(name = null)).title)
        assertEquals("v1.4", ReleaseParser.parse(releaseJson(name = "   ")).title)
        val huge = ReleaseParser.parse(releaseJson(body = "x".repeat(50_000))).notes
        assertEquals(UpdateSource.MAX_NOTES_CHARS, huge.length)
        val controls = ReleaseParser.parse(releaseJson(body = "a\u0000b\u001bc\td\ne")).notes
        assertEquals("abc\td\ne", controls)
    }

    @Test
    fun unpublishedOrUnreadableReleasesAreRejected() {
        val draft = releaseJson().replace("\"draft\":false", "\"draft\":true")
        val pre = releaseJson().replace("\"prerelease\":false", "\"prerelease\":true")
        for (body in listOf(draft, pre, "", "not json", "[]", "{\"tag_name\":\"\"}", "{\"tag_name\":\"v1.4/../x\"}")) {
            assertThrows(body, ReleaseFormatException::class.java) { ReleaseParser.parse(body) }
        }
    }

    @Test
    fun missingOrMalformedDigestMakesTheReleaseUninstallable() {
        assertNotNull(ReleaseParser.parse(releaseJson(digest = null)).blockReason)
        assertNotNull(ReleaseParser.parse(releaseJson(digest = "md5:abcd")).blockReason)
        assertNotNull(ReleaseParser.parse(releaseJson(digest = "sha256:1234")).blockReason)
        assertNotNull(ReleaseParser.parse(releaseJson(apk = null)).blockReason)
    }

    @Test
    fun foreignOrIncompleteAssetsAreIgnored() {
        val foreign = releaseJson(url = "https://github.com/someone/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk")
        assertNull(ReleaseParser.parse(foreign).apk)
        val pending = releaseJson().replace("\"state\":\"uploaded\"", "\"state\":\"starter\"")
        assertNull(ReleaseParser.parse(pending).apk)
        assertNull(ReleaseParser.parse(releaseJson(size = 0)).apk)
        assertNull(ReleaseParser.parse(releaseJson(size = UpdateSource.MAX_APK_BYTES + 1)).apk)
    }

    @Test
    fun identityChecksPackageVersionAndSigner() {
        val installed = installedV13()
        assertNull(ApkVerifier.checkIdentity(goodIdentity(), installed, "v1.4"))
        assertEquals(FailureKind.PACKAGE, ApkVerifier.checkIdentity(null, installed, "v1.4"))
        assertEquals(FailureKind.PACKAGE, ApkVerifier.checkIdentity(goodIdentity().copy(packageName = "x.y"), installed, "v1.4"))
        assertEquals(FailureKind.VERSION, ApkVerifier.checkIdentity(goodIdentity(versionCode = 4), installed, "v1.4"))
        assertEquals(FailureKind.VERSION, ApkVerifier.checkIdentity(goodIdentity(versionName = "1.5"), installed, "v1.4"))
        assertEquals(FailureKind.SIGNATURE, ApkVerifier.checkIdentity(goodIdentity(signer = OTHER_CERT), installed, "v1.4"))
        assertEquals(
            FailureKind.SIGNATURE,
            ApkVerifier.checkIdentity(goodIdentity().copy(signers = emptySet(), pastSigners = emptySet()), installed, "v1.4"),
        )
        assertEquals(
            FailureKind.SIGNATURE,
            ApkVerifier.checkIdentity(goodIdentity().copy(signers = setOf(CERT, OTHER_CERT)), installed, "v1.4"),
        )
    }

    @Test
    fun keyRotationIsAcceptedOnlyWhenTheHistoryContainsTheInstalledKey() {
        val rotated = ApkIdentity("com.sehirnotlari", 5, "1.4", setOf(OTHER_CERT), setOf(CERT, OTHER_CERT))
        assertTrue(ApkVerifier.signersCompatible(rotated, installedV13()))
        val unrelated = rotated.copy(pastSigners = setOf(OTHER_CERT))
        assertFalse(ApkVerifier.signersCompatible(unrelated, installedV13()))
    }

    @Test
    fun verifyChecksSizeThenDigestThenIdentity() {
        val bytes = fakeApk()
        val release = ReleaseParser.parse(releaseJson(apk = bytes))
        val file = temp.newFile("a.apk")
        val inspector = FakeInspector()

        file.writeBytes(bytes)
        assertNull(ApkVerifier.verify(file, release, installedV13(), inspector))

        file.writeBytes(bytes + 1)
        assertEquals(FailureKind.SIZE, ApkVerifier.verify(file, release, installedV13(), inspector))

        file.writeBytes(fakeApk(seed = 42))
        assertEquals(FailureKind.HASH, ApkVerifier.verify(file, release, installedV13(), inspector))

        file.delete()
        assertEquals(FailureKind.STORAGE, ApkVerifier.verify(file, release, installedV13(), inspector))
    }
}
