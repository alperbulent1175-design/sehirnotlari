package com.sehirnotlari.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

internal const val CERT = "edd159fc1dc6115ea0079f80e2c9d43a11e1906a163abcfa2cd6cb3cf626efac"
internal const val OTHER_CERT = "0000000000000000000000000000000000000000000000000000000000000001"
internal const val STORAGE_URL = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sig=x"

internal fun assetUrl(tag: String, name: String = "sehirnotlari-${tag.removePrefix("v")}.apk") =
    "https://github.com/alperbulent1175-design/sehirnotlari/releases/download/$tag/$name"

internal fun fakeApk(size: Int = 64 * 1024, seed: Int = 7): ByteArray {
    val bytes = Random(seed).nextBytes(size)
    bytes[0] = 0x50
    bytes[1] = 0x4B
    return bytes
}

internal fun releaseJson(
    tag: String = "v1.4",
    apk: ByteArray? = fakeApk(),
    body: Any? = "## Neler Yeni?\n- Güncelleme artık onay ister",
    name: Any? = "Şehir Notları $tag",
    url: String = assetUrl(tag),
    digest: String? = apk?.let { "sha256:" + ApkVerifier.sha256(it) },
    size: Long? = apk?.size?.toLong(),
    prerelease: Boolean = false,
    draft: Boolean = false,
): String {
    val json = JSONObject()
        .put("tag_name", tag)
        .put("name", name ?: JSONObject.NULL)
        .put("body", body ?: JSONObject.NULL)
        .put("draft", draft)
        .put("prerelease", prerelease)
    val assets = JSONArray()
    if (apk != null) {
        val asset = JSONObject()
            .put("name", url.substringAfterLast('/'))
            .put("state", "uploaded")
            .put("content_type", "application/vnd.android.package-archive")
            .put("size", size)
            .put("browser_download_url", url)
        if (digest != null) asset.put("digest", digest)
        assets.put(asset)
    }
    return json.put("assets", assets).toString()
}

internal class MemoryPrefs : UpdatePrefs {
    override var dismissedTag = ""
    override var dismissedAt = 0L
    override var upToDateAt = 0L
    override var rateLimitedUntil = 0L
    override var autoDownload = false
    override var prereleases = false
}

internal val RELEASES_URL = UpdateSource.GITHUB.releasesUrl

/**
 * Plays GitHub: the releases list, the github.com 302 and the storage host. Records every URL requested.
 * [release] is one release object (served as a one-item list) or a whole JSON list.
 */
internal class FakeGitHub(var release: String = releaseJson(), var apkBytes: ByteArray = fakeApk()) : HttpTransport {
    val requests = mutableListOf<String>()
    var apiFailure: IOException? = null
    var apiStatus = 200
    var apiHeaders: Map<String, String> = emptyMap()
    var brokenDownloads = 0
    var redirect: String? = STORAGE_URL
    var reportedLength: Long? = null

    val apkRequests: Int get() = requests.count { it != RELEASES_URL }
    val apiRequests: Int get() = requests.count { it == RELEASES_URL }

    override fun open(url: String, headers: Map<String, String>): HttpResponse {
        requests += url
        if (url == RELEASES_URL) {
            apiFailure?.let { throw it }
            if (apiStatus != 200) {
                val lower = apiHeaders.mapKeys { it.key.lowercase() }
                return HttpResponse(apiStatus, null, 0L, { ByteArrayInputStream(ByteArray(0)) }, {}, { lower[it.lowercase()] })
            }
            val list = if (release.trimStart().startsWith("[")) release else "[$release]"
            return ok(list.toByteArray())
        }
        val target = redirect
        if (url.startsWith("https://github.com/") && target != null) {
            return HttpResponse(302, target, -1L, { ByteArrayInputStream(ByteArray(0)) }, {})
        }
        if (url == target || url.startsWith("https://github.com/")) {
            if (brokenDownloads > 0) {
                brokenDownloads--
                return HttpResponse(200, null, reportedLength ?: apkBytes.size.toLong(), { DroppingStream(apkBytes) }, {})
            }
            return ok(apkBytes, reportedLength)
        }
        return HttpResponse(404, null, 0L, { ByteArrayInputStream(ByteArray(0)) }, {})
    }

    private fun ok(bytes: ByteArray, length: Long? = null) =
        HttpResponse(200, null, length ?: bytes.size.toLong(), { ByteArrayInputStream(bytes) }, {})

    /** Delivers half the file, then the connection drops. */
    private class DroppingStream(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes, 0, bytes.size / 2)
        override fun read(): Int = inner.read().also { if (it < 0) throw IOException("connection reset") }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it < 0) throw IOException("connection reset") }
    }
}

internal class FakeInspector(var identity: ApkIdentity? = goodIdentity()) : ApkInspector {
    var inspected = 0
    override fun inspect(file: File): ApkIdentity? {
        inspected++
        return if (file.isFile) identity else null
    }
}

internal fun goodIdentity(versionCode: Long = 5, versionName: String = "1.4", signer: String = CERT) =
    ApkIdentity("com.sehirnotlari", versionCode, versionName, setOf(signer), setOf(signer))

internal fun installedV13() = InstalledApp("com.sehirnotlari", "1.3", 4, setOf(CERT))

internal class FakeHost(override var isResumed: Boolean = true) : UpdateController.Host {
    val renders = mutableListOf<Pair<UpdateSnapshot, Boolean>>()
    val installed = mutableListOf<File>()
    var permissionRequests = 0
    var installerWorks = true
    var launchResult = InstallLaunch.STARTED

    val states: List<UpdateState> get() = renders.map { it.first.state }
    val last: UpdateSnapshot get() = renders.last().first

    override fun render(snapshot: UpdateSnapshot, replay: Boolean) {
        renders += snapshot to replay
    }

    override fun requestInstallPermission() {
        permissionRequests++
    }

    /** Like the real installer, the file is checked against the expected digest as it is read. */
    override fun launchInstaller(file: File, sha256: String): InstallLaunch {
        if (!installerWorks) return InstallLaunch.FAILED
        if (ApkVerifier.sha256(file) != sha256) return InstallLaunch.CHANGED
        if (launchResult == InstallLaunch.STARTED) installed += file
        return launchResult
    }
}
