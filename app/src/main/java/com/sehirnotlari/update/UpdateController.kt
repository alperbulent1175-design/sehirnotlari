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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** What happened when the verified APK was handed to the system installer. */
internal enum class InstallLaunch {
    /** The installer took the file; Android now asks the user to confirm. */
    STARTED,
    /** The bytes streamed to the installer did not match the release's SHA-256; nothing was installed. */
    CHANGED,
    FAILED,
}

/** The system installer's final answer for a session this app started. */
internal enum class InstallOutcome { SUCCESS, CANCELLED, REJECTED, STORAGE, FAILED }

/**
 * Process-wide update flow. It outlives activities, so rotation or a trip to the background
 * neither restarts a check nor cancels a download; an attached [Host] only renders state.
 *
 * Nothing is downloaded until the user accepts in UPDATE_AVAILABLE or has switched on automatic
 * downloads, and the installer only receives a file that passed [ApkVerifier] moments before.
 * Installation itself always waits for the user's confirmation in Android's installer.
 */
internal class UpdateController(
    private val transport: HttpTransport,
    private val prefs: UpdatePrefs,
    private val updatesDir: File,
    private val installedApp: () -> InstalledApp,
    private val inspector: ApkInspector,
    private val canInstallPackages: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val legacyFiles: List<File> = emptyList(),
    private val retryDelayMs: Long = 2_000L,
    private val source: UpdateSource = UpdateSource.GITHUB,
    /** Automatic downloads use only unmetered networks such as Wi-Fi. */
    private val isUnmetered: () -> Boolean = { false },
) {
    interface Host {
        val isResumed: Boolean
        fun render(snapshot: UpdateSnapshot, replay: Boolean)
        fun requestInstallPermission()
        /** Streams [file] into the system installer, checking it against [sha256] on the way. */
        fun launchInstaller(file: File, sha256: String): InstallLaunch
    }

    private val lock = Any()
    private var snapshot = UpdateSnapshot()
    private var host: Host? = null
    private var job: Job? = null
    private var generation = 0
    private var autoChecked = false
    private var installWhenReady = false
    private var installAfterPermission = false
    private var installing = false

    val current: UpdateSnapshot get() = synchronized(lock) { snapshot }

    fun attach(host: Host) {
        synchronized(lock) {
            this.host = host
            host.render(snapshot, replay = true)
        }
    }

    fun detach(host: Host) {
        synchronized(lock) { if (this.host === host) this.host = null }
    }

    /** Automatic check, at most once per process; skipped shortly after an "up to date" answer. */
    fun onAppStart() {
        synchronized(lock) {
            if (autoChecked) return
            autoChecked = true
        }
        legacyFiles.forEach { if (it.isFile) it.delete() }
        val last = prefs.upToDateAt
        if (last > 0L && clock() - last in 0 until AUTO_CHECK_INTERVAL_MS) return
        startCheck(manual = false)
    }

    fun checkNow() = startCheck(manual = true)

    val autoDownload: Boolean get() = prefs.autoDownload

    fun setAutoDownload(on: Boolean) {
        prefs.autoDownload = on
    }

    val prereleases: Boolean get() = prefs.prereleases

    /** A different channel may have a newer release, so the next automatic check is not skipped. */
    fun setPrereleases(on: Boolean) {
        if (prefs.prereleases == on) return
        prefs.prereleases = on
        prefs.upToDateAt = 0L
    }

    /** "Güncelle ve Yükle", or "Tekrar Dene" after a failed download, verification or installation. */
    fun accept() = startDownload(interactive = true)

    private fun startDownload(interactive: Boolean) {
        synchronized(lock) {
            val s = snapshot
            val release = s.release ?: return
            val retryingDownload = interactive && s.state == FAILED && s.retryable &&
                s.failedStage != null && s.failedStage != FailedStage.CHECK
            if (s.state != UPDATE_AVAILABLE && !retryingDownload) return
            val apk = release.apk
            if (apk == null || release.blockReason != null) return
            val gen = ++generation
            installWhenReady = interactive
            val moved = setLocked(
                s.copy(
                    state = DOWNLOADING, interactive = interactive, percent = 0, downloaded = 0L, total = apk.size,
                    failure = null, failedStage = null, retryable = false, retryAt = 0L,
                )
            )
            if (!moved) return
            if (interactive) prefs.dismissedTag = ""
            job = scope.launch { runDownload(gen, release) }
        }
    }

    /** The system installer reported back on the session started from READY_TO_INSTALL. */
    fun onInstallOutcome(outcome: InstallOutcome) {
        synchronized(lock) {
            val s = snapshot
            if (s.state != READY_TO_INSTALL) return
            val release = s.release ?: return
            val failed = s.copy(state = FAILED, failedStage = FailedStage.INSTALL, interactive = true)
            when (outcome) {
                // Android replaces the app and stops this process; nothing is left to show.
                InstallOutcome.SUCCESS -> Unit
                InstallOutcome.CANCELLED -> setLocked(failed.copy(failure = FailureKind.INSTALL_CANCELLED, retryable = true))
                InstallOutcome.REJECTED -> {
                    fileFor(release).delete()
                    setLocked(failed.copy(failure = FailureKind.INSTALL_REJECTED, retryable = false))
                }
                InstallOutcome.STORAGE -> setLocked(failed.copy(failure = FailureKind.STORAGE, retryable = true))
                InstallOutcome.FAILED -> setLocked(failed.copy(failure = FailureKind.INSTALLER, retryable = true))
            }
        }
    }

    fun retry() {
        val stage = current.failedStage
        if (stage == null || stage == FailedStage.CHECK) checkNow() else accept()
    }

    /** "Daha Sonra", "İptal" or "Kapat": stops any download and never opens the installer. */
    fun later() {
        synchronized(lock) {
            val s = snapshot
            if (!s.state.canMoveTo(DISMISSED)) return
            generation++
            job?.cancel()
            installWhenReady = false
            installAfterPermission = false
            s.release?.let {
                prefs.dismissedTag = it.tag
                prefs.dismissedAt = clock()
            }
            setLocked(s.copy(state = DISMISSED, interactive = false, failure = null, failedStage = null, retryable = false))
        }
    }

    /** "Yükle" / "İzin Ver". Re-verifies the file right before handing it to the system installer. */
    fun install() {
        var askPermission: Host? = null
        synchronized(lock) {
            val s = snapshot
            if (s.state == INSTALL_PERMISSION_REQUIRED) {
                if (canInstallPackages()) {
                    setLocked(s.copy(state = READY_TO_INSTALL))
                } else {
                    installAfterPermission = true
                    askPermission = host
                }
            }
            if (askPermission == null && snapshot.state == READY_TO_INSTALL && !installing) {
                val release = snapshot.release ?: return
                val gen = generation
                installing = true
                scope.launch { runInstall(gen, release) }
            }
        }
        askPermission?.requestInstallPermission()
    }

    /** The unknown-sources screen was left, or the activity came back to the foreground. */
    fun onHostResumed() {
        var proceed = false
        synchronized(lock) {
            val s = snapshot
            if (s.state == INSTALL_PERMISSION_REQUIRED && canInstallPackages()) {
                setLocked(s.copy(state = READY_TO_INSTALL))
                proceed = installAfterPermission
                installAfterPermission = false
            }
        }
        if (proceed) install()
    }

    private fun startCheck(manual: Boolean) {
        synchronized(lock) {
            val s = snapshot
            when (s.state) {
                CHECKING, DOWNLOADING, VERIFYING, UPDATE_AVAILABLE, READY_TO_INSTALL, INSTALL_PERMISSION_REQUIRED -> {
                    if (manual) setLocked(s.copy(interactive = true))
                    return
                }
                else -> Unit
            }
            if (!s.state.canMoveTo(CHECKING)) return
            val gen = ++generation
            setLocked(UpdateSnapshot(state = CHECKING, interactive = manual))
            job = scope.launch { runCheck(gen, manual) }
        }
    }

    private fun runCheck(gen: Int, manual: Boolean) {
        try {
            val app = installedApp()
            val release = ReleaseParser.pick(fetchReleases(), app)
            if (release == null) {
                prefs.upToDateAt = clock()
                clearDownloads(keep = null)
                moveFrom(gen) { it.copy(state = UP_TO_DATE) }
                return
            }
            clearDownloads(keep = fileFor(release))
            val sinceDismissed = clock() - prefs.dismissedAt
            val quiet = !manual && prefs.dismissedTag == release.tag && sinceDismissed in 0 until REMIND_AFTER_MS
            val shown = moveFrom(gen) { it.copy(state = if (quiet) DISMISSED else UPDATE_AVAILABLE, release = release) }
            if (shown && !quiet && !manual && prefs.autoDownload && release.blockReason == null && isUnmetered()) {
                startDownload(interactive = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val (kind, _) = classify(e)
            val retryAt = (e as? UpdateFailure)?.retryAt ?: 0L
            // Asking again during a rate limit would only be refused again.
            val retryable = kind != FailureKind.RATE_LIMITED
            moveFrom(gen) {
                it.copy(state = FAILED, failure = kind, failedStage = FailedStage.CHECK, retryable = retryable, retryAt = retryAt)
            }
        }
    }

    /** One request for the newest releases; none at all while GitHub's rate limit is in force. */
    private fun fetchReleases(): List<ReleaseInfo> {
        val now = clock()
        val until = prefs.rateLimitedUntil
        // A wait longer than GitHub ever asks for means the clock moved; it is not honoured.
        if (until > now && until - now <= MAX_RATE_LIMIT_WAIT_MS) {
            throw UpdateFailure(FailureKind.RATE_LIMITED, retryable = false, retryAt = until)
        }
        UpdateHttp.openChecked(
            transport, source.releasesUrl, API_HEADERS,
            source::isAllowedApiUrl, source::isAllowedApiUrl,
        ).use { response ->
            UpdateHttp.rateLimitedUntil(response, now)?.let {
                prefs.rateLimitedUntil = it
                throw UpdateFailure(FailureKind.RATE_LIMITED, retryable = false, retryAt = it)
            }
            if (response.code !in 200..299) throw UpdateHttp.httpFailure(response.code)
            val bytes = response.body().use { readCapped(it, MAX_API_BYTES) }
            return ReleaseParser.parseList(String(bytes, Charsets.UTF_8), prefs.prereleases, source)
        }
    }

    private suspend fun runDownload(gen: Int, release: ReleaseInfo) {
        val apk = release.apk ?: return
        val target = fileFor(release)
        try {
            // A file kept from an earlier, cancelled or uninstalled attempt is verified like a new one.
            if (!(target.isFile && target.length() == apk.size)) {
                target.delete()
                downloadWithRetries(gen, release, target)
            }
            if (!moveFrom(gen) { it.copy(state = VERIFYING, percent = 100, downloaded = apk.size) }) return
            val problem = ApkVerifier.verify(target, release, installedApp(), inspector)
            if (problem != null) {
                target.delete()
                fail(gen, problem, FailedStage.VERIFY, retryable = problem == FailureKind.SIZE || problem == FailureKind.HASH)
                return
            }
            becomeReady(gen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val (kind, retryable) = classify(e)
            if (kind.blocksInstall) target.delete()
            fail(gen, kind, FailedStage.DOWNLOAD, retryable)
        }
    }

    private suspend fun downloadWithRetries(gen: Int, release: ReleaseInfo, target: File) {
        var attempt = 1
        while (true) {
            try {
                downloadOnce(gen, release, target)
                return
            } catch (e: UpdateFailure) {
                if (!e.retryable || attempt >= MAX_ATTEMPTS) throw e
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) throw e
            }
            delay(retryDelayMs * attempt)
            attempt++
        }
    }

    private suspend fun downloadOnce(gen: Int, release: ReleaseInfo, target: File) {
        val apk = release.apk ?: throw UpdateFailure(FailureKind.BAD_RELEASE, retryable = false)
        if (!updatesDir.isDirectory && !updatesDir.mkdirs()) throw UpdateFailure(FailureKind.STORAGE, retryable = false)
        val part = File(updatesDir, target.name + ".part")
        part.delete()
        try {
            UpdateHttp.openChecked(
                transport, apk.url, DOWNLOAD_HEADERS,
                firstHop = { source.isOfficialAssetUrl(it, release.tag, apk.name) },
                laterHop = source::isAllowedDownloadUrl,
            ).use { response ->
                if (response.code !in 200..299) throw UpdateHttp.httpFailure(response.code)
                if (response.contentLength >= 0L && response.contentLength != apk.size) {
                    throw UpdateFailure(FailureKind.SIZE, retryable = false)
                }
                var read = 0L
                var lastEmit = 0L
                response.body().use { input ->
                    val output = try {
                        FileOutputStream(part)
                    } catch (e: IOException) {
                        throw UpdateFailure(FailureKind.STORAGE, retryable = false)
                    }
                    output.use { out ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > apk.size) throw UpdateFailure(FailureKind.SIZE, retryable = false)
                            try {
                                out.write(buffer, 0, n)
                            } catch (e: IOException) {
                                throw UpdateFailure(FailureKind.STORAGE, retryable = false)
                            }
                            val now = clock()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                progress(gen, read, apk.size)
                            }
                        }
                    }
                }
                if (read != apk.size) throw IOException("connection closed early")
                progress(gen, read, apk.size)
            }
            if (!part.renameTo(target)) throw UpdateFailure(FailureKind.STORAGE, retryable = false)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun becomeReady(gen: Int) {
        var launch = false
        synchronized(lock) {
            if (gen != generation) return
            if (!canInstallPackages()) {
                setLocked(snapshot.copy(state = INSTALL_PERMISSION_REQUIRED))
                return
            }
            if (!setLocked(snapshot.copy(state = READY_TO_INSTALL))) return
            // Only open the installer straight away while the user is looking at the app.
            launch = installWhenReady && host?.isResumed == true
            installWhenReady = false
        }
        if (launch) install()
    }

    private fun runInstall(gen: Int, release: ReleaseInfo) {
        try {
            val file = fileFor(release)
            val problem = ApkVerifier.verify(file, release, installedApp(), inspector)
            if (problem != null) {
                file.delete()
                fail(gen, problem, FailedStage.VERIFY, retryable = problem == FailureKind.SIZE || problem == FailureKind.HASH)
                return
            }
            val target = synchronized(lock) { if (gen == generation) host else null } ?: return
            val sha256 = release.apk?.sha256 ?: return
            when (target.launchInstaller(file, sha256)) {
                InstallLaunch.STARTED -> Unit
                InstallLaunch.CHANGED -> {
                    file.delete()
                    fail(gen, FailureKind.HASH, FailedStage.VERIFY, retryable = true)
                }
                InstallLaunch.FAILED -> fail(gen, FailureKind.INSTALLER, FailedStage.INSTALL, retryable = true)
            }
        } catch (e: Exception) {
            fail(gen, FailureKind.INSTALLER, FailedStage.INSTALL, retryable = true)
        } finally {
            synchronized(lock) { installing = false }
        }
    }

    private fun progress(gen: Int, read: Long, total: Long) {
        val percent = if (total > 0L) ((read * 100L) / total).toInt().coerceIn(0, 100) else -1
        moveFrom(gen) { if (it.state == DOWNLOADING) it.copy(percent = percent, downloaded = read, total = total) else null }
    }

    private fun fail(gen: Int, kind: FailureKind, stage: FailedStage, retryable: Boolean) {
        moveFrom(gen) { it.copy(state = FAILED, failure = kind, failedStage = stage, retryable = retryable, interactive = true) }
    }

    private fun moveFrom(gen: Int, transform: (UpdateSnapshot) -> UpdateSnapshot?): Boolean = synchronized(lock) {
        if (gen != generation) return false
        val next = transform(snapshot) ?: return false
        setLocked(next)
    }

    /** Caller holds [lock]. Rendering under the lock keeps the UI in transition order. */
    private fun setLocked(next: UpdateSnapshot): Boolean {
        val from = snapshot.state
        if (next.state != from && !from.canMoveTo(next.state)) return false
        snapshot = next
        host?.render(next, replay = false)
        return true
    }

    private fun fileFor(release: ReleaseInfo) = File(updatesDir, "sehirnotlari-${release.tag}.apk")

    /** Only this controller's own directory is touched; app data lives elsewhere. */
    private fun clearDownloads(keep: File?) {
        updatesDir.listFiles()?.forEach { if (it != keep) it.delete() }
    }

    private fun classify(e: Exception): Pair<FailureKind, Boolean> = when (e) {
        is UpdateFailure -> e.kind to e.retryable
        is ReleaseFormatException -> FailureKind.BAD_RELEASE to true
        is IOException -> FailureKind.OFFLINE to true
        else -> FailureKind.BAD_RELEASE to true
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw UpdateFailure(FailureKind.BAD_RELEASE, retryable = false)
        }
        return out.toByteArray()
    }

    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 60L * 60 * 1000
        const val REMIND_AFTER_MS = 3L * 24 * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 3
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val MAX_API_BYTES = 2 * 1024 * 1024
        private const val MAX_RATE_LIMIT_WAIT_MS = 6L * 60 * 60 * 1000
        private val API_HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
        private val DOWNLOAD_HEADERS = mapOf("Accept" to "application/octet-stream")
    }
}
