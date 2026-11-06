package com.sehirnotlari.update

import com.sehirnotlari.update.UpdateState.CHECKING
import com.sehirnotlari.update.UpdateState.DISMISSED
import com.sehirnotlari.update.UpdateState.DOWNLOADING
import com.sehirnotlari.update.UpdateState.FAILED
import com.sehirnotlari.update.UpdateState.INSTALL_PERMISSION_REQUIRED
import com.sehirnotlari.update.UpdateState.READY_TO_INSTALL
import com.sehirnotlari.update.UpdateState.UPDATE_AVAILABLE
import com.sehirnotlari.update.UpdateState.UP_TO_DATE
import com.sehirnotlari.update.UpdateState.VERIFYING
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var scope: TestScope
    private lateinit var github: FakeGitHub
    private lateinit var prefs: MemoryPrefs
    private lateinit var inspector: FakeInspector
    private lateinit var cacheDir: File
    private lateinit var updatesDir: File
    private var installed = installedV13()
    private var canInstall = true
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        scope = TestScope()
        github = FakeGitHub()
        prefs = MemoryPrefs()
        inspector = FakeInspector()
        cacheDir = temp.newFolder("cache")
        updatesDir = File(cacheDir, "updates")
    }

    private fun controller(legacy: List<File> = emptyList()) = UpdateController(
        transport = github,
        prefs = prefs,
        updatesDir = updatesDir,
        installedApp = { installed },
        inspector = inspector,
        canInstallPackages = { canInstall },
        scope = scope,
        clock = { now },
        legacyFiles = legacy,
        retryDelayMs = 1_000L,
    )

    private fun idle() = scope.testScheduler.advanceUntilIdle()

    private fun started(host: FakeHost = FakeHost()): Pair<UpdateController, FakeHost> {
        val c = controller()
        c.attach(host)
        c.onAppStart()
        idle()
        return c to host
    }

    private fun downloadedFiles() = updatesDir.listFiles().orEmpty().map { it.name }

    // 1. A newer release is announced, the user has not answered: nothing is downloaded.
    @Test
    fun updateFoundWithoutConsentDownloadsNothing() {
        val (c, host) = started()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertEquals(listOf(UpdateState.IDLE, CHECKING, UPDATE_AVAILABLE), host.states)
        assertEquals("v1.4", host.last.release?.tag)
        assertTrue(host.last.release!!.notes.contains("Neler Yeni?"))
        repeat(3) { idle() }
        c.onHostResumed()
        idle()
        assertEquals(0, github.apkRequests)
        assertTrue(downloadedFiles().isEmpty())
        assertTrue(host.installed.isEmpty())
        assertFalse(host.states.contains(DOWNLOADING))
    }

    // 2. "Güncelle ve Yükle": download, verification, then the installer.
    @Test
    fun consentDownloadsVerifiesAndOpensInstaller() {
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        val order = host.states.distinct()
        assertTrue(order.indexOf(DOWNLOADING) < order.indexOf(VERIFYING))
        assertTrue(order.indexOf(VERIFYING) < order.indexOf(READY_TO_INSTALL))
        assertEquals(listOf(assetUrl("v1.4"), STORAGE_URL), github.requests.drop(1))
        assertEquals(1, host.installed.size)
        assertEquals(ApkVerifier.sha256(github.apkBytes), ApkVerifier.sha256(host.installed.single()))
        assertEquals(listOf("sehirnotlari-v1.4.apk"), downloadedFiles())
    }

    // 3. "Daha Sonra": no download, no installation, even if install() is called afterwards.
    @Test
    fun laterStartsNeitherDownloadNorInstall() {
        val (c, host) = started()
        c.later()
        idle()
        c.install()
        c.accept()
        idle()
        assertEquals(DISMISSED, c.current.state)
        assertEquals(0, github.apkRequests)
        assertTrue(host.installed.isEmpty())
        assertEquals("v1.4", prefs.dismissedTag)
        assertEquals(now, prefs.dismissedAt)
    }

    @Test
    fun laterOnAVerifiedDownloadKeepsTheInstallerClosed() {
        val host = FakeHost(isResumed = false)
        val (c, _) = started(host)
        c.accept()
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        c.later()
        c.install()
        idle()
        assertEquals(DISMISSED, c.current.state)
        assertTrue(host.installed.isEmpty())
    }

    @Test
    fun cancellingADownloadStopsItAndLeavesNoPartialFile() {
        val (c, host) = started()
        c.accept()
        c.later()
        idle()
        assertEquals(DISMISSED, c.current.state)
        assertTrue(host.installed.isEmpty())
        assertTrue(downloadedFiles().none { it.endsWith(".apk") || it.endsWith(".part") })
    }

    // 4. Empty, missing and broken release notes or release data.
    @Test
    fun emptyOrMissingNotesStillOfferTheUpdate() {
        for (body in listOf("", null, "   \n\n")) {
            github.release = releaseJson(body = body, name = null)
            prefs = MemoryPrefs()
            val (c, _) = started()
            assertEquals(UPDATE_AVAILABLE, c.current.state)
            assertEquals("v1.4", c.current.release?.title)
            assertTrue(c.current.release!!.notes.isBlank())
        }
    }

    @Test
    fun brokenReleaseJsonFailsQuietlyOnAutomaticCheck() {
        github.release = "{\"tag_name\": \"v1.4\", \"assets\": [ "
        val (c, host) = started()
        assertEquals(FAILED, c.current.state)
        assertEquals(FailureKind.BAD_RELEASE, c.current.failure)
        assertEquals(FailedStage.CHECK, c.current.failedStage)
        assertFalse("automatic failures must not open the dialog", host.last.interactive)
        assertEquals(0, github.apkRequests)
    }

    // 5. Network loss: offline start, a dropped download with automatic retry, and manual retry.
    @Test
    fun offlineStartFailsQuietlyAndAManualCheckRecovers() {
        github.apiFailure = UnknownHostException("api.github.com")
        val (c, host) = started()
        assertEquals(FAILED, c.current.state)
        assertEquals(FailureKind.OFFLINE, c.current.failure)
        assertFalse(host.last.interactive)
        github.apiFailure = null
        c.checkNow()
        idle()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertTrue(host.last.interactive)
    }

    @Test
    fun droppedDownloadIsRetriedAutomatically() {
        github.brokenDownloads = 1
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        assertEquals(4, github.apkRequests)
        assertEquals(1, host.installed.size)
        assertTrue(downloadedFiles().none { it.endsWith(".part") })
    }

    @Test
    fun repeatedDropsFailWithRetryAndTheUserCanTryAgain() {
        github.brokenDownloads = UpdateController.MAX_ATTEMPTS
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FAILED, c.current.state)
        assertEquals(FailureKind.OFFLINE, c.current.failure)
        assertEquals(FailedStage.DOWNLOAD, c.current.failedStage)
        assertTrue(c.current.retryable)
        assertTrue(host.last.interactive)
        assertTrue(downloadedFiles().isEmpty())
        assertTrue(host.installed.isEmpty())
        c.retry()
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        assertEquals(1, host.installed.size)
    }

    // 6. Corrupt APK, wrong size and wrong SHA-256.
    @Test
    fun corruptApkWithTheRightSizeIsBlockedByTheDigest() {
        github.apkBytes = fakeApk(seed = 99)
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FAILED, c.current.state)
        assertEquals(FailureKind.HASH, c.current.failure)
        assertEquals(FailedStage.VERIFY, c.current.failedStage)
        assertTrue(host.installed.isEmpty())
        assertTrue(downloadedFiles().isEmpty())
        assertEquals(0, inspector.inspected)
    }

    @Test
    fun wrongDigestInTheReleaseBlocksInstallation() {
        github.release = releaseJson(digest = "sha256:" + "ab".repeat(32))
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FailureKind.HASH, c.current.failure)
        assertTrue(host.installed.isEmpty())
    }

    @Test
    fun oversizedDownloadIsRejected() {
        github.apkBytes = fakeApk(size = 64 * 1024 + 10)
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FailureKind.SIZE, c.current.failure)
        assertFalse(c.current.retryable)
        assertTrue(host.installed.isEmpty())
        assertTrue(downloadedFiles().isEmpty())
    }

    @Test
    fun truncatedDownloadIsNeverInstalled() {
        github.apkBytes = fakeApk(size = 1000)
        github.reportedLength = 64L * 1024
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FAILED, c.current.state)
        assertEquals(FailedStage.DOWNLOAD, c.current.failedStage)
        assertEquals(UpdateController.MAX_ATTEMPTS * 2, github.apkRequests)
        assertTrue(host.installed.isEmpty())
        assertTrue(downloadedFiles().isEmpty())
    }

    @Test
    fun releaseWithoutDigestCannotBeAccepted() {
        github.release = releaseJson(digest = null)
        val (c, host) = started()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertNotNull(c.current.release?.blockReason)
        c.accept()
        idle()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertEquals(0, github.apkRequests)
        assertTrue(host.installed.isEmpty())
    }

    // 7. Wrong package, signer or version inside an APK whose digest matches.
    @Test
    fun wrongPackageSignerOrVersionBlocksInstallation() {
        val cases = mapOf(
            FailureKind.PACKAGE to goodIdentity().copy(packageName = "com.example.evil"),
            FailureKind.SIGNATURE to goodIdentity(signer = OTHER_CERT),
            FailureKind.VERSION to goodIdentity(versionCode = 4),
        )
        for ((expected, identity) in cases) {
            inspector.identity = identity
            val (c, host) = started()
            c.accept()
            idle()
            assertEquals(expected, c.current.failure)
            assertFalse("$expected must not be retried", c.current.retryable)
            assertTrue(host.installed.isEmpty())
            assertTrue(downloadedFiles().isEmpty())
        }
        inspector.identity = null
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FailureKind.PACKAGE, c.current.failure)
        assertTrue(host.installed.isEmpty())
    }

    @Test
    fun fileTamperedAfterVerificationIsCaughtAtInstallTime() {
        val host = FakeHost(isResumed = false)
        val (c, _) = started(host)
        c.accept()
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        File(updatesDir, "sehirnotlari-v1.4.apk").writeBytes(fakeApk(seed = 3))
        host.isResumed = true
        c.install()
        idle()
        assertEquals(FailureKind.HASH, c.current.failure)
        assertTrue(host.installed.isEmpty())
    }

    @Test
    fun redirectOutsideGitHubIsRefusedBeforeAnyRequest() {
        github.redirect = "https://evil.example.com/sehirnotlari-1.4.apk"
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(FailureKind.SOURCE, c.current.failure)
        assertFalse(github.requests.any { it.contains("evil.example.com") })
        assertTrue(host.installed.isEmpty())
    }

    @Test
    fun assetFromAnotherRepositoryIsNeverDownloaded() {
        github.release = releaseJson(url = "https://github.com/someone/sehirnotlari/releases/download/v1.4/sehirnotlari-1.4.apk")
        val (c, _) = started()
        assertNull(c.current.release?.apk)
        c.accept()
        idle()
        assertEquals(0, github.apkRequests)
    }

    // 8. The same version is not pushed at the user again and again.
    @Test
    fun installedLatestVersionIsUpToDate() {
        installed = InstalledApp("com.sehirnotlari", "1.4", 5, setOf(CERT))
        val (c, host) = started()
        assertEquals(UP_TO_DATE, c.current.state)
        assertFalse(host.states.contains(UPDATE_AVAILABLE))
        assertEquals(now, prefs.upToDateAt)
    }

    @Test
    fun dismissedVersionStaysQuietForAWhileButNotForever() {
        started().first.later()

        val (quiet, quietHost) = started()
        assertEquals(DISMISSED, quiet.current.state)
        assertFalse(quietHost.states.contains(UPDATE_AVAILABLE))

        now += UpdateController.REMIND_AFTER_MS + 1
        val (again, _) = started()
        assertEquals(UPDATE_AVAILABLE, again.current.state)
        assertEquals(0, github.apkRequests)
    }

    @Test
    fun manualCheckAndNewerReleaseIgnoreAnEarlierDismissal() {
        val (c, host) = started()
        c.later()
        c.checkNow()
        idle()
        assertEquals(UPDATE_AVAILABLE, c.current.state)
        assertTrue(host.last.interactive)

        prefs.dismissedTag = "v1.4"
        prefs.dismissedAt = now
        github.release = releaseJson(tag = "v1.5")
        val (newer, _) = started()
        assertEquals(UPDATE_AVAILABLE, newer.current.state)
        assertEquals("v1.5", newer.current.release?.tag)
    }

    @Test
    fun automaticCheckRunsOncePerProcessAndPausesAfterUpToDate() {
        val (c, _) = started()
        c.onAppStart()
        c.onAppStart()
        idle()
        assertEquals(1, github.apiRequests)

        installed = InstalledApp("com.sehirnotlari", "1.4", 5, setOf(CERT))
        started()
        assertEquals(2, github.apiRequests)
        started()
        assertEquals("a fresh up-to-date answer is reused for an hour", 2, github.apiRequests)
    }

    // 9. Notes and other app data are never touched; only the updater's own files are cleaned.
    @Test
    fun cleanupTouchesOnlyUpdaterFiles() {
        val filesDir = temp.newFolder("files")
        val notesDb = File(filesDir, "city_notes.db").apply { writeText("notes") }
        val backup = File(filesDir, "room-first-backups/backup.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        val cacheSibling = File(cacheDir, "temp_voice.m4a").apply { writeText("audio") }
        val legacyApk = File(cacheDir, "sehirnotlari-update.apk").apply { writeText("old") }
        val legacyPart = File(cacheDir, "sehirnotlari-update.apk.part").apply { writeText("old") }
        updatesDir.mkdirs()
        File(updatesDir, "sehirnotlari-v1.3.apk").writeText("stale")
        File(updatesDir, "sehirnotlari-v1.4.apk.part").writeText("half")

        val c = controller(legacy = listOf(legacyApk, legacyPart))
        c.attach(FakeHost())
        c.onAppStart()
        idle()
        assertFalse(legacyApk.exists())
        assertFalse(legacyPart.exists())
        assertTrue(downloadedFiles().isEmpty())

        c.accept()
        idle()
        installed = InstalledApp("com.sehirnotlari", "1.4", 5, setOf(CERT))
        prefs.upToDateAt = 0
        val after = controller()
        after.attach(FakeHost())
        after.onAppStart()
        idle()
        assertEquals(UP_TO_DATE, after.current.state)
        assertTrue("installed update files are removed", downloadedFiles().isEmpty())

        assertEquals("notes", notesDb.readText())
        assertEquals("{}", backup.readText())
        assertEquals("audio", cacheSibling.readText())
    }

    // 10. Rotation / background: the flow lives on without the activity and replays to the new one.
    @Test
    fun downloadSurvivesActivityRecreationAndReplays() {
        val first = FakeHost()
        val c = controller()
        c.attach(first)
        c.onAppStart()
        idle()
        c.accept()
        c.detach(first)
        val rendersBeforeDetach = first.renders.size
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        assertEquals(rendersBeforeDetach, first.renders.size)
        assertTrue("no installer without a visible activity", first.installed.isEmpty())

        val second = FakeHost()
        c.attach(second)
        c.onAppStart()
        idle()
        assertEquals(READY_TO_INSTALL to true, second.renders.single().let { it.first.state to it.second })
        assertEquals(1, github.apiRequests)
        c.install()
        idle()
        assertEquals(1, second.installed.size)
    }

    @Test
    fun backgroundCompletionWaitsForTheUserToTapInstall() {
        val host = FakeHost(isResumed = false)
        val (c, _) = started(host)
        c.accept()
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        host.isResumed = true
        c.onHostResumed()
        idle()
        assertTrue(host.installed.isEmpty())
        c.install()
        idle()
        assertEquals(1, host.installed.size)
    }

    @Test
    fun missingInstallPermissionIsRequestedOnlyAfterTheUserTaps() {
        canInstall = false
        val (c, host) = started()
        c.accept()
        idle()
        assertEquals(INSTALL_PERMISSION_REQUIRED, c.current.state)
        assertEquals(0, host.permissionRequests)
        c.install()
        assertEquals(1, host.permissionRequests)
        c.onHostResumed()
        idle()
        assertEquals("still not granted", INSTALL_PERMISSION_REQUIRED, c.current.state)
        canInstall = true
        c.onHostResumed()
        idle()
        assertEquals(READY_TO_INSTALL, c.current.state)
        assertEquals(1, host.installed.size)
    }

    @Test
    fun installerThatCannotOpenReportsAFailure() {
        val host = FakeHost().apply { installerWorks = false }
        val (c, _) = started(host)
        c.accept()
        idle()
        assertEquals(FailureKind.INSTALLER, c.current.failure)
        assertEquals(FailedStage.INSTALL, c.current.failedStage)
        assertTrue(c.current.retryable)
    }

    @Test
    fun onlyTheUsersConsentLeadsIntoDownloading() {
        for (from in UpdateState.values()) {
            val allowed = from.canMoveTo(DOWNLOADING)
            assertEquals("$from -> DOWNLOADING", from == UPDATE_AVAILABLE || from == FAILED, allowed)
        }
    }
}
