package com.sehirnotlari.update

import com.sehirnotlari.update.UpdateState.DISMISSED
import com.sehirnotlari.update.UpdateState.FAILED
import com.sehirnotlari.update.UpdateState.READY_TO_INSTALL
import com.sehirnotlari.update.UpdateState.UPDATE_AVAILABLE
import com.sehirnotlari.update.UpdateState.UP_TO_DATE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Release channels, versionCode, GitHub's rate limit, automatic downloads and installer answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateChannelTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var scope: TestScope
    private lateinit var github: FakeGitHub
    private lateinit var prefs: MemoryPrefs
    private lateinit var inspector: FakeInspector
    private lateinit var updatesDir: File
    private var installed = installedV13()
    private var unmetered = true
    private var now = 1_800_000_000_000L

    private val beta = fakeApk(seed = 21)
    private val stable = fakeApk(seed = 22)

    @Before
    fun setUp() {
        scope = TestScope()
        prefs = MemoryPrefs()
        inspector = FakeInspector()
        updatesDir = File(temp.newFolder("cache"), "updates")
        github = FakeGitHub(release = channels(), apkBytes = stable)
    }

    /** GitHub lists the newest first: a test version above the stable one. */
    private fun channels() = "[" + releaseJson(
        tag = "v1.5-beta.1", apk = beta, body = "## Test\n- yeni harita\nversionCode: 6\n", prerelease = true,
    ) + "," + releaseJson(tag = "v1.4", apk = stable, body = "## v1.4\nversionCode: 5") + "]"

    private fun controller() = UpdateController(
        transport = github,
        prefs = prefs,
        updatesDir = updatesDir,
        installedApp = { installed },
        inspector = inspector,
        canInstallPackages = { true },
        scope = scope,
        clock = { now },
        retryDelayMs = 1_000L,
        isUnmetered = { unmetered },
    )

    private fun idle() = scope.testScheduler.advanceUntilIdle()

    private fun started(host: FakeHost = FakeHost()): Pair<UpdateController, FakeHost> {
        val c = controller()
        c.attach(host)
        c.onAppStart()
        idle()
        return c to host
    }

    // Pre-releases

    @Test
    fun testVersionsAreOfferedOnlyAfterTheUserSwitchesThemOn() {
        val (stableOnly, _) = started()
        assertEquals("v1.4", stableOnly.current.release?.tag)
        assertFalse(stableOnly.current.release!!.prerelease)

        prefs.prereleases = true
        github.apkBytes = beta
        inspector.identity = goodIdentity(versionCode = 6, versionName = "1.5-beta.1")
        val (withTests, host) = started()
        val offered = withTests.current.release!!
        assertEquals("v1.5-beta.1", offered.tag)
        assertTrue(offered.prerelease)
        assertEquals(6L, offered.versionCode)
        assertFalse("the marker line is not shown", offered.notes.contains("versionCode"))
        assertTrue(offered.notes.contains("yeni harita"))
        withTests.accept()
        idle()
        assertEquals(READY_TO_INSTALL, withTests.current.state)
        assertEquals(1, host.installed.size)
    }

    @Test
    fun switchingTheChannelAllowsAnImmediateAutomaticCheck() {
        prefs.upToDateAt = now
        val c = controller()
        c.setPrereleases(true)
        assertEquals(0L, prefs.upToDateAt)
        assertTrue(c.prereleases)
    }

    @Test
    fun draftsAreNeverOffered() {
        github.release = releaseJson(tag = "v2.0", draft = true, body = "versionCode: 9")
        prefs.prereleases = true
        val (c, _) = started()
        assertEquals(UP_TO_DATE, c.current.state)
    }

    // versionCode

    @Test
    fun theDeclaredVersionCodeDecidesNotTheTag() {
        installed = InstalledApp("com.sehirnotlari", "1.4", 5, setOf(CERT))
        github.release = releaseJson(tag = "v1.4", body = "Düzeltme derlemesi\nversionCode: 6")
        inspector.identity = goodIdentity(versionCode = 6)
        assertEquals("same name, higher code", UPDATE_AVAILABLE, started().first.current.state)

        github.release = releaseJson(tag = "v9.9", body = "versionCode: 5")
        assertEquals("higher name, same code", UP_TO_DATE, started().first.current.state)
    }

    @Test
    fun theDownloadedApkMustCarryTheDeclaredVersionCode() {
        github.release = releaseJson(tag = "v1.4", apk = stable, body = "versionCode: 5")
        inspector.identity = goodIdentity(versionCode = 7)
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FailureKind.VERSION, c.current.failure)
        assertTrue(host.installed.isEmpty())
        assertTrue(updatesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aReleaseWithConflictingVersionCodesIsSkipped() {
        val list = ReleaseParser.parseList("[" + releaseJson(tag = "v1.5+6", body = "versionCode: 7") + "]", withPrereleases = true)
        assertTrue(list.isEmpty())
        assertEquals(6L, ReleaseParser.parseList("[" + releaseJson(tag = "v1.5+6") + "]", withPrereleases = false).single().versionCode)
    }

    @Test
    fun releasesWithoutADeclaredCodeStillCompareByTag() {
        github.release = releaseJson(tag = "v1.4", body = "## Eski biçim")
        val (c, _) = started()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertNull(c.current.release?.versionCode)
    }

    // Rate limit

    @Test
    fun githubsRateLimitStopsRequestsUntilTheResetTime() {
        val reset = now / 1000 + 30 * 60
        github.apiStatus = 403
        github.apiHeaders = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to reset.toString())
        val (c, host) = started()
        assertEquals(FAILED, c.current.state)
        assertEquals(FailureKind.RATE_LIMITED, c.current.failure)
        assertEquals(reset * 1000, c.current.retryAt)
        assertFalse("nothing to retry before the reset", c.current.retryable)
        assertFalse("an automatic check stays quiet", host.last.interactive)
        assertEquals(reset * 1000, prefs.rateLimitedUntil)

        github.apiStatus = 200
        c.checkNow()
        idle()
        assertEquals(FailureKind.RATE_LIMITED, c.current.failure)
        assertEquals("no request while limited", 1, github.apiRequests)

        now = reset * 1000 + 1
        c.checkNow()
        idle()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertEquals(2, github.apiRequests)
    }

    @Test
    fun retryAfterAndPlainForbiddenAreToldApart() {
        github.apiStatus = 429
        github.apiHeaders = mapOf("Retry-After" to "120")
        val (limited, _) = started()
        assertEquals(FailureKind.RATE_LIMITED, limited.current.failure)
        assertEquals(now + 120_000, prefs.rateLimitedUntil)

        prefs = MemoryPrefs()
        github.apiStatus = 403
        github.apiHeaders = mapOf("X-RateLimit-Remaining" to "41")
        val (forbidden, _) = started()
        assertEquals(FailureKind.SERVER, forbidden.current.failure)
        assertEquals(0L, prefs.rateLimitedUntil)
    }

    @Test
    fun aLimitFarBeyondWhatGitHubAsksIsIgnored() {
        prefs.rateLimitedUntil = now + 48L * 60 * 60 * 1000
        val (c, _) = started()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertEquals(1, github.apiRequests)
    }

    // Automatic download

    @Test
    fun automaticDownloadWaitsForTheUserBeforeInstalling() {
        prefs.autoDownload = true
        val host = FakeHost()
        val (c, _) = started(host)
        assertEquals(READY_TO_INSTALL, c.current.state)
        assertFalse(c.current.interactive)
        assertTrue("the installer opens only when the user taps Yükle", host.installed.isEmpty())
        assertEquals(2, github.apkRequests)
        c.install()
        idle()
        assertEquals(1, host.installed.size)
    }

    @Test
    fun automaticDownloadNeedsTheSettingAnUnmeteredNetworkAndNoRecentDismissal() {
        prefs.autoDownload = true
        unmetered = false
        assertEquals(UPDATE_AVAILABLE, started().first.current.state)
        assertEquals(0, github.apkRequests)

        unmetered = true
        prefs.autoDownload = false
        assertEquals(UPDATE_AVAILABLE, started().first.current.state)
        assertEquals(0, github.apkRequests)

        prefs.autoDownload = true
        prefs.dismissedTag = "v1.4"
        prefs.dismissedAt = now
        assertEquals(DISMISSED, started().first.current.state)
        assertEquals(0, github.apkRequests)

        prefs.dismissedTag = ""
        val (manual, _) = controller().let { c -> c.attach(FakeHost()); c.checkNow(); idle(); c to Unit }
        assertEquals("a manual check still asks first", UPDATE_AVAILABLE, manual.current.state)
        assertEquals(0, github.apkRequests)
    }

    @Test
    fun automaticDownloadIsNeverStartedForAnUnverifiableRelease() {
        prefs.autoDownload = true
        github.release = releaseJson(digest = null)
        val (c, _) = started()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertEquals(0, github.apkRequests)
    }

    // Installer answers

    @Test
    fun cancelledInstallationCanBeRetriedWithoutDownloadingAgain() {
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(1, host.installed.size)
        val downloads = github.apkRequests
        c.onInstallOutcome(InstallOutcome.CANCELLED)
        assertEquals(FailureKind.INSTALL_CANCELLED, c.current.failure)
        assertEquals(FailedStage.INSTALL, c.current.failedStage)
        assertTrue(c.current.retryable)
        c.retry()
        idle()
        assertEquals(2, host.installed.size)
        assertEquals(downloads, github.apkRequests)
    }

    @Test
    fun rejectedInstallationDeletesTheFileAndIsNotRetried() {
        val (c, _) = started()
        c.accept()
        idle()
        c.onInstallOutcome(InstallOutcome.REJECTED)
        assertEquals(FailureKind.INSTALL_REJECTED, c.current.failure)
        assertFalse(c.current.retryable)
        assertTrue(updatesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun installerAnswersOutsideAnInstallAreIgnored() {
        val (c, _) = started()
        c.onInstallOutcome(InstallOutcome.CANCELLED)
        c.onInstallOutcome(InstallOutcome.SUCCESS)
        assertEquals(UPDATE_AVAILABLE, c.current.state)
    }

    @Test
    fun aFileThatChangesWhileStreamingToTheInstallerIsBlocked() {
        val host = FakeHost().apply { launchResult = InstallLaunch.CHANGED }
        val (c, _) = started(host)
        c.accept()
        idle()
        assertEquals(FailureKind.HASH, c.current.failure)
        assertTrue(host.installed.isEmpty())
        assertTrue(updatesDir.listFiles().orEmpty().isEmpty())
    }

    // Sources

    @Test
    fun theLocalTestServerIsConfinedToThisDeviceAndGitHubToHttps() {
        val local = UpdateSource.testServer("127.0.0.1:8099")
        val asset = "http://127.0.0.1:8099/alperbulent1175-design/sehirnotlari/releases/download/v1.5/a.apk"
        assertTrue(local.isOfficialAssetUrl(asset, "v1.5", "a.apk"))
        assertTrue(local.isAllowedApiUrl(local.releasesUrl))
        assertFalse(local.isAllowedDownloadUrl("https://github.com/x"))
        assertFalse(local.isAllowedDownloadUrl("http://127.0.0.1:8100/x"))
        assertFalse(UpdateSource.GITHUB.isOfficialAssetUrl(asset, "v1.5", "a.apk"))
        assertFalse(UpdateSource.GITHUB.isAllowedApiUrl(local.releasesUrl))
        for (bad in listOf("10.0.2.2:8099", "example.com:80", "127.0.0.1", "127.0.0.1:8099/x")) {
            assertThrows(bad, IllegalArgumentException::class.java) { UpdateSource.testServer(bad) }
        }
    }
}
