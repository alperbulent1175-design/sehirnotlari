package com.sehirnotlari

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks the public GitHub Releases API once per activity start, downloads a newer APK
 * without blocking the WebView, then opens the package installer (or the unknown-sources
 * setting when Android 8+ has not granted it yet).
 *
 * No token is sent. The repository is expected to be public.
 */
class GithubReleaseUpdater(
    private val activity: AppCompatActivity,
    private val webView: () -> WebView,
    private val requestInstallPermission: () -> Unit,
) {
    private val sessionStarted = AtomicBoolean(false)
    private val permissionPrompted = AtomicBoolean(false)

    @Volatile private var phase = Phase.IDLE
    @Volatile private var pendingRelease: ReleaseInfo? = null
    @Volatile private var downloadedFile: File? = null
    @Volatile private var lastPercent = -1
    @Volatile private var lastDownloaded = 0L
    @Volatile private var lastTotal = -1L
    @Volatile private var failureMessage = ""

    fun onWebViewReady() {
        if (!sessionStarted.compareAndSet(false, true)) {
            replayUi()
            return
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runUpdate()
        }
    }

    /** Bridge entry. Starts the same one-shot check used at startup, or installs an APK already on disk. */
    fun checkForUpdate() {
        onWebViewReady()
    }

    fun downloadUpdate() {
        val file = downloadedFile
        if (file != null && file.exists() && file.length() > 0L) {
            installDownloaded()
            return
        }
        onWebViewReady()
    }

    fun installDownloaded() {
        activity.runOnUiThread {
            val file = downloadedFile
            if (file == null || !file.exists() || file.length() <= 0L) {
                showFailure("APK henüz hazır değil.")
                return@runOnUiThread
            }
            if (needsInstallPermission()) {
                phase = Phase.NEEDS_PERMISSION
                emit("needs_permission", releaseLabel())
                try {
                    requestInstallPermission()
                } catch (e: Exception) {
                    Log.e(TAG, "Install permission screen failed", e)
                    showFailure("Kurulum izni ekranı açılamadı.")
                }
                return@runOnUiThread
            }
            openInstaller(file)
        }
    }

    fun onInstallPermissionResult() {
        if (downloadedFile == null) return
        if (needsInstallPermission()) {
            phase = Phase.NEEDS_PERMISSION
            emit("needs_permission", releaseLabel())
            return
        }
        installDownloaded()
    }

    private suspend fun runUpdate() {
        phase = Phase.CHECKING
        var announced = false
        try {
            val release = fetchLatestRelease()
            val (versionName, versionCode) = installedVersion()
            if (!ReleaseVersions.isNewer(release.tag, versionName, versionCode)) {
                phase = Phase.UP_TO_DATE
                Log.i(TAG, "Up to date. installed=$versionName ($versionCode) latest=${release.tag}")
                return
            }
            pendingRelease = release
            if (release.apkUrl.isBlank()) {
                announced = true
                showFailure("Yeni sürüm bulundu ancak APK dosyası yok.")
                return
            }
            announced = true
            phase = Phase.DOWNLOADING
            emit("available", releaseLabel())
            val cached = cachedApk
            val file = if (
                cached != null &&
                cached.tag == release.tag &&
                cached.file.exists() &&
                looksLikeZip(cached.file)
            ) {
                cached.file
            } else {
                downloadApk(release)
            }
            downloadedFile = file
            cachedApk = CachedApk(release.tag, file)
            withContext(Dispatchers.Main) {
                beginInstallAfterDownload()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GitHub update failed", e)
            if (announced) {
                showFailure("Güncelleme indirilemedi. Bağlantınızı kontrol edin.")
            } else {
                phase = Phase.FAILED
            }
        }
    }

    private fun beginInstallAfterDownload() {
        val file = downloadedFile
        if (file == null || !file.exists() || file.length() <= 0L) {
            showFailure("İndirilen APK bulunamadı.")
            return
        }
        if (needsInstallPermission()) {
            phase = Phase.NEEDS_PERMISSION
            emit("needs_permission", releaseLabel())
            if (permissionPrompted.compareAndSet(false, true)) {
                try {
                    requestInstallPermission()
                } catch (e: Exception) {
                    Log.e(TAG, "Install permission screen failed", e)
                    showFailure("Kurulum izni ekranı açılamadı.")
                }
            }
            return
        }
        openInstaller(file)
    }

    private fun openInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("update", uri)
            }
            val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            for (info in matches) {
                activity.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            phase = Phase.READY
            emit("ready", releaseLabel())
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Package installer failed", e)
            showFailure("Paket yükleyici açılamadı.")
        }
    }

    private fun needsInstallPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
    }

    private fun replayUi() {
        when (phase) {
            Phase.DOWNLOADING -> {
                emit("available", releaseLabel())
                emit(
                    "progress",
                    releaseLabel() + mapOf(
                        "percent" to lastPercent,
                        "downloaded" to lastDownloaded,
                        "total" to lastTotal
                    )
                )
            }
            Phase.NEEDS_PERMISSION -> emit("needs_permission", releaseLabel())
            Phase.READY -> emit("ready", releaseLabel())
            Phase.FAILED -> if (failureMessage.isNotBlank()) showFailure(failureMessage)
            Phase.IDLE, Phase.CHECKING, Phase.UP_TO_DATE -> Unit
        }
    }

    private fun showFailure(message: String) {
        phase = Phase.FAILED
        failureMessage = message
        val extras = linkedMapOf<String, Any>()
        extras.putAll(releaseLabel())
        extras["message"] = message
        val file = downloadedFile
        if (file != null && file.exists() && file.length() > 0L) extras["canInstall"] = true
        emit("failed", extras)
    }

    private fun releaseLabel(): Map<String, Any> {
        val tag = pendingRelease?.tag ?: return emptyMap()
        return mapOf("tag" to tag, "label" to ReleaseVersions.displayVersion(tag))
    }

    private fun installedVersion(): Pair<String, Long> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }
        val versionName = info.versionName?.takeIf { it.isNotBlank() } ?: "0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return versionName to versionCode
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val conn = openFollowing(
            LATEST_RELEASE_URL,
            "application/vnd.github+json",
            mapOf("X-GitHub-Api-Version" to "2022-11-28")
        )
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "GitHub releases HTTP $code")
                throw IOException("releases HTTP $code")
            }
            return parseRelease(body)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadApk(release: ReleaseInfo): File {
        val dir = activity.externalCacheDir ?: activity.cacheDir
        if (!dir.exists()) dir.mkdirs()
        val part = File(dir, "sehirnotlari-update.apk.part")
        val dest = File(dir, "sehirnotlari-update.apk")
        if (part.exists()) part.delete()
        var conn: HttpURLConnection? = null
        try {
            conn = openFollowing(release.apkUrl, "*/*")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "APK download HTTP $code")
                throw IOException("download HTTP $code")
            }
            val reported = conn.contentLengthLong
            val total = when {
                reported > 0L -> reported
                release.apkSize > 0L -> release.apkSize
                else -> -1L
            }
            val input = conn.inputStream
            val output = FileOutputStream(part)
            try {
                val buffer = ByteArray(16 * 1024)
                var readTotal = 0L
                var lastEmit = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    readTotal += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 200L) {
                        lastEmit = now
                        publishProgress(readTotal, total)
                    }
                }
                publishProgress(readTotal, total)
            } finally {
                output.close()
                input.close()
            }
            if (dest.exists() && !dest.delete()) {
                Log.w(TAG, "Previous update APK could not be replaced")
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            if (!looksLikeZip(dest)) {
                dest.delete()
                throw IOException("downloaded file is not an apk")
            }
            return dest
        } catch (e: Exception) {
            if (part.exists()) part.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun publishProgress(downloaded: Long, total: Long) {
        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
        lastPercent = percent
        lastDownloaded = downloaded
        lastTotal = total
        emit(
            "progress",
            releaseLabel() + mapOf(
                "percent" to percent,
                "downloaded" to downloaded,
                "total" to total
            )
        )
    }

    private fun emit(type: String, extras: Map<String, Any> = emptyMap()) {
        val json = JSONObject()
        json.put("type", type)
        for ((key, value) in extras) {
            when (value) {
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Boolean -> json.put(key, value)
                is Double -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }
        val script = "if(typeof onGithubUpdate==='function'){onGithubUpdate($json);}"
        activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            try {
                webView().evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not reach update UI", e)
            }
        }
    }

    private enum class Phase {
        IDLE, CHECKING, DOWNLOADING, NEEDS_PERMISSION, READY, FAILED, UP_TO_DATE
    }

    companion object {
        private data class CachedApk(val tag: String, val file: File)

        @Volatile
        private var cachedApk: CachedApk? = null

        private const val TAG = "SehirNotlari"
        private const val USER_AGENT = "SehirNotlari-Android"
        private const val APK_MIME = "application/vnd.android.package-archive"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/alperbulent1175-design/sehirnotlari/releases/latest"

        private fun openFollowing(
            startUrl: String,
            accept: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): HttpURLConnection {
            var current = startUrl
            repeat(5) {
                if (!current.startsWith("https://")) throw IOException("Refusing non-HTTPS URL")
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", accept)
                    setRequestProperty("Accept-Encoding", "identity")
                    extraHeaders.forEach { (name, header) -> setRequestProperty(name, header) }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) throw IOException("Redirect without location")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                return conn
            }
            throw IOException("Too many redirects")
        }

        internal fun parseRelease(body: String): ReleaseInfo {
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim().ifEmpty { json.optString("name").trim() }
            if (tag.isEmpty()) throw IOException("Release tag is missing")
            val assetsJson = json.optJSONArray("assets") ?: JSONArray()
            val assets = buildList {
                for (i in 0 until assetsJson.length()) {
                    val item = assetsJson.optJSONObject(i) ?: continue
                    add(
                        ReleaseAsset(
                            name = item.optString("name"),
                            contentType = item.optString("content_type"),
                            size = item.optLong("size"),
                            url = item.optString("browser_download_url")
                        )
                    )
                }
            }
            val apk = selectApkAsset(assets)
            return ReleaseInfo(
                tag = tag,
                apkUrl = apk?.url.orEmpty(),
                apkSize = apk?.size ?: -1L
            )
        }

        private fun looksLikeZip(file: File): Boolean {
            if (!file.exists() || file.length() < 4L) return false
            val head = ByteArray(2)
            file.inputStream().use { input ->
                if (input.read(head) < 2) return false
            }
            return head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        }
    }
}

internal data class ReleaseInfo(
    val tag: String,
    val apkUrl: String,
    val apkSize: Long,
)

internal data class ReleaseAsset(
    val name: String,
    val contentType: String,
    val size: Long,
    val url: String,
)

internal fun selectApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
    val apks = assets.filter { asset ->
        asset.url.isNotBlank() && (
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType.equals("application/vnd.android.package-archive", ignoreCase = true)
            )
    }
    if (apks.isEmpty()) return null
    fun score(asset: ReleaseAsset): Int {
        val name = asset.name.lowercase()
        return when {
            "debug" in name -> 0
            "release" in name -> 2
            else -> 1
        }
    }
    return apks.maxWithOrNull(compareBy<ReleaseAsset> { score(it) }.thenBy { it.size })
}

/**
 * Compares a GitHub release tag with the installed versionName / versionCode.
 * A leading "v" is ignored. Numeric dot-segments are compared in order.
 * When the names are equal, an optional "+N" or "(N)" suffix is compared with versionCode.
 */
internal object ReleaseVersions {
    fun isNewer(remoteTag: String, localVersionName: String, localVersionCode: Long): Boolean {
        val remote = parse(remoteTag) ?: return false
        val local = parse(localVersionName) ?: return false
        val compared = compareParts(remote.parts, local.parts)
        if (compared > 0) return true
        if (compared < 0) return false
        val remoteCode = remote.versionCode ?: return false
        return remoteCode > localVersionCode
    }

    fun displayVersion(tag: String): String {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return ""
        var core = if (trimmed[0] == 'v' || trimmed[0] == 'V') trimmed.substring(1) else trimmed
        core = core.replace(Regex("""(?:\+\d+|\s*\(\d+\))\s*$"""), "")
        return "v$core"
    }

    private data class Parsed(val parts: List<Int>, val versionCode: Long?)

    private fun parse(raw: String): Parsed? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (text[0] == 'v' || text[0] == 'V') text = text.substring(1).trim()
        var versionCode: Long? = null
        val suffix = SUFFIX.matchEntire(text)
        if (suffix != null) {
            text = suffix.groupValues[1].trim()
            val codeText = suffix.groupValues[2].ifEmpty { suffix.groupValues[3] }
            versionCode = codeText.toLongOrNull()
        }
        if (text.isEmpty()) {
            return if (versionCode != null) Parsed(emptyList(), versionCode) else null
        }
        val parts = mutableListOf<Int>()
        for (token in text.split('.')) {
            val digits = token.trim().takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            parts.add(digits.toIntOrNull() ?: return null)
        }
        return Parsed(parts, versionCode)
    }

    private fun compareParts(left: List<Int>, right: List<Int>): Int {
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private val SUFFIX = Regex("""^(.*?)(?:\+(\d+)|\s*\((\d+)\))\s*$""")
}
