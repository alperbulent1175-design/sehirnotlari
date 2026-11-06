package com.sehirnotlari

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.sehirnotlari.update.ApkIdentity
import com.sehirnotlari.update.ApkVerifier
import com.sehirnotlari.update.FailureKind
import com.sehirnotlari.update.InstallLaunch
import com.sehirnotlari.update.InstallOutcome
import com.sehirnotlari.update.InstalledApp
import com.sehirnotlari.update.SessionInstaller
import com.sehirnotlari.update.SharedUpdatePrefs
import com.sehirnotlari.update.UpdateController
import com.sehirnotlari.update.UpdateSnapshot
import com.sehirnotlari.update.UpdateSource
import com.sehirnotlari.update.UrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity side of the in-app updater: renders [UpdateController] state into the WebView's
 * "Neler Yeni?" dialog and forwards the user's choices. The controller itself is process-wide.
 */
internal class GithubReleaseUpdater(
    private val activity: AppCompatActivity,
    private val webView: () -> WebView,
    private val requestInstallPermission: () -> Unit,
) : UpdateController.Host {
    private val controller = AppUpdates.controller(activity)

    override val isResumed: Boolean
        get() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun onWebViewReady() {
        AppUpdates.visible = this
        controller.attach(this)
        controller.onAppStart()
    }

    fun onResume() {
        controller.onHostResumed()
        launchPendingConfirm()
    }

    fun onInstallPermissionResult() = controller.onHostResumed()

    fun onDestroy() {
        if (AppUpdates.visible === this) AppUpdates.visible = null
        controller.detach(this)
    }

    fun checkForUpdate() = controller.checkNow()

    fun downloadUpdate() = controller.accept()

    fun installUpdate() = controller.install()

    fun dismissUpdate() = controller.later()

    fun retryUpdate() = controller.retry()

    fun settings(): String = JSONObject()
        .put("autoDownload", controller.autoDownload)
        .put("prereleases", controller.prereleases)
        .put("version", BuildConfig.VERSION_NAME)
        .toString()

    fun setAutoDownload(on: Boolean) = controller.setAutoDownload(on)

    fun setPrereleases(on: Boolean) = controller.setPrereleases(on)

    /** Android's install confirmation opens only over this app while it is in front. */
    fun launchPendingConfirm() {
        activity.runOnUiThread {
            if (activity.isDestroyed || !isResumed) return@runOnUiThread
            val confirm = AppUpdates.takeConfirm() ?: return@runOnUiThread
            try {
                activity.startActivity(confirm)
            } catch (e: Exception) {
                Log.w(TAG, "Install confirmation failed error=${e.javaClass.simpleName}")
                controller.onInstallOutcome(InstallOutcome.FAILED)
            }
        }
    }

    override fun render(snapshot: UpdateSnapshot, replay: Boolean) {
        val script = "if(typeof onGithubUpdate==='function'){onGithubUpdate(${toJson(snapshot, replay)});}"
        activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            try {
                webView().evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.w(TAG, "Update UI unreachable error=${e.javaClass.simpleName}")
            }
        }
    }

    override fun requestInstallPermission() {
        activity.runOnUiThread {
            try {
                requestInstallPermission.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Install permission screen failed error=${e.javaClass.simpleName}")
            }
        }
    }

    override fun launchInstaller(file: File, sha256: String): InstallLaunch =
        SessionInstaller.commit(activity.applicationContext, file, sha256)

    private fun toJson(s: UpdateSnapshot, replay: Boolean): JSONObject = JSONObject().apply {
        put("state", s.state.name)
        put("interactive", s.interactive)
        put("replay", replay)
        put("fontScale", activity.resources.configuration.fontScale.toDouble())
        put("percent", s.percent)
        put("downloaded", s.downloaded)
        put("total", s.total)
        put("retryable", s.retryable)
        s.failedStage?.let { put("stage", it.name) }
        s.failure?.let {
            val text = when {
                it.blocksInstall -> "${it.message} Kurulum güvenlik nedeniyle engellendi ve dosya silindi."
                it == FailureKind.RATE_LIMITED && s.retryAt > 0L ->
                    "${it.message} Saat ${SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr")).format(Date(s.retryAt))} sonrasında yeniden deneyin."
                else -> it.message
            }
            put("message", text)
            put("blocked", it.blocksInstall)
        }
        s.release?.let { r ->
            put("tag", r.tag)
            put("label", r.label)
            put("title", r.title)
            put("notes", r.notes)
            put("size", r.apk?.size ?: -1L)
            put("installable", r.blockReason == null)
            put("prerelease", r.prerelease)
            r.blockReason?.let { put("blockReason", it) }
        }
    }

    private companion object {
        const val TAG = "SehirNotlari"
    }
}

internal object AppUpdates {
    @Volatile private var instance: UpdateController? = null

    /** The updater of the activity showing the page; the install confirmation opens over it. */
    @Volatile var visible: GithubReleaseUpdater? = null

    @Volatile private var confirm: Intent? = null

    fun controller(context: Context): UpdateController =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    fun offerConfirm(intent: Intent) {
        confirm = intent
        visible?.launchPendingConfirm()
    }

    fun takeConfirm(): Intent? = synchronized(this) { confirm.also { confirm = null } }

    private fun create(app: Context): UpdateController {
        val legacyDirs = listOfNotNull(app.externalCacheDir, app.cacheDir)
        // Release builds are compiled with an empty test server and always use GitHub.
        val source = if (BuildConfig.DEBUG && BuildConfig.UPDATE_TEST_SERVER.isNotEmpty()) {
            UpdateSource.testServer(BuildConfig.UPDATE_TEST_SERVER)
        } else {
            UpdateSource.GITHUB
        }
        return UpdateController(
            transport = UrlConnectionTransport(),
            prefs = SharedUpdatePrefs(app.getSharedPreferences(SharedUpdatePrefs.FILE, Context.MODE_PRIVATE)),
            // Internal cache: other apps cannot swap the file between verification and install.
            updatesDir = File(app.cacheDir, "updates"),
            installedApp = { AndroidPackages.installed(app) },
            inspector = { file -> AndroidPackages.inspect(app, file) },
            canInstallPackages = {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            legacyFiles = legacyDirs.flatMap {
                listOf(File(it, "sehirnotlari-update.apk"), File(it, "sehirnotlari-update.apk.part"))
            },
            source = source,
            isUnmetered = {
                val cm = app.getSystemService(ConnectivityManager::class.java)
                cm != null && cm.activeNetwork != null && !cm.isActiveNetworkMetered
            },
        )
    }
}

internal object AndroidPackages {
    fun installed(context: Context): InstalledApp {
        val pm = context.packageManager
        val info = withSigners { flags ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, flags)
            }
        } ?: throw IllegalStateException("own package info missing")
        return InstalledApp(
            packageName = context.packageName,
            versionName = info.versionName?.takeIf { it.isNotBlank() } ?: "0",
            versionCode = versionCode(info),
            signers = signers(info).first,
        )
    }

    fun inspect(context: Context, file: File): ApkIdentity? {
        val pm = context.packageManager
        val info = withSigners { flags ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageArchiveInfo(file.path, flags)
            }
        } ?: return null
        val packageName = info.packageName ?: return null
        val (current, history) = signers(info)
        return ApkIdentity(packageName, versionCode(info), info.versionName.orEmpty(), current, history)
    }

    /** Asks for signing certificates first and falls back to the legacy signature list. */
    @SuppressLint("PackageManagerGetSignatures")
    private fun withSigners(query: (Int) -> PackageInfo?): PackageInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = query(PackageManager.GET_SIGNING_CERTIFICATES)
            if (info?.signingInfo != null) return info
        }
        @Suppress("DEPRECATION")
        return query(PackageManager.GET_SIGNATURES)
    }

    /** Current signers, and the rotation history (which includes the current signer). */
    private fun signers(info: PackageInfo): Pair<Set<String>, Set<String>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            if (signing != null) {
                if (signing.hasMultipleSigners()) {
                    return digests(signing.apkContentsSigners).toSet() to emptySet()
                }
                val history = digests(signing.signingCertificateHistory)
                return setOfNotNull(history.lastOrNull()) to history.toSet()
            }
        }
        @Suppress("DEPRECATION")
        return digests(info.signatures).toSet() to emptySet()
    }

    private fun digests(signatures: Array<Signature>?): List<String> =
        signatures.orEmpty().map { ApkVerifier.sha256(it.toByteArray()) }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
