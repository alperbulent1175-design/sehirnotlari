package com.sehirnotlari.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * The only place updates may come from: this public repository's published GitHub Releases.
 * [GITHUB] is the source of every release build; [testServer] exists for debug builds only.
 */
internal class UpdateSource private constructor(
    val releasesUrl: String,
    private val apiOrigin: String,
    private val assetOrigin: String,
    private val downloadOrigins: Set<String>,
) {
    fun isOfficialAssetUrl(url: String, tag: String, name: String): Boolean {
        if (!isSafeSegment(tag) || !isSafeSegment(name)) return false
        val uri = parse(url) ?: return false
        if (origin(uri) != assetOrigin) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        return uri.rawPath == "/$OWNER/$REPO/releases/download/$tag/$name"
    }

    fun isAllowedApiUrl(url: String): Boolean = parse(url)?.let { origin(it) } == apiOrigin

    fun isAllowedDownloadUrl(url: String): Boolean = parse(url)?.let { origin(it) } in downloadOrigins

    companion object {
        const val OWNER = "alperbulent1175-design"
        const val REPO = "sehirnotlari"
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val MAX_NOTES_CHARS = 20_000
        const val MAX_TITLE_CHARS = 200
        private const val PAGE = 10
        private const val API_PATH = "/repos/$OWNER/$REPO/releases?per_page=$PAGE"

        // github.com answers an asset URL with a 302 to a short-lived signed URL on GitHub's storage hosts.
        val GITHUB = UpdateSource(
            releasesUrl = "https://api.github.com$API_PATH",
            apiOrigin = "https://api.github.com",
            assetOrigin = "https://github.com",
            downloadOrigins = setOf(
                "https://github.com",
                "https://release-assets.githubusercontent.com",
                "https://objects.githubusercontent.com",
            ),
        )

        private val TEST_SERVER = Regex("""(127\.0\.0\.1|localhost):[0-9]{2,5}""")

        /** A local stand-in for GitHub, reached through `adb reverse`. Debug builds only. */
        fun testServer(hostPort: String): UpdateSource {
            require(TEST_SERVER.matches(hostPort)) { "test server must be on this device" }
            val origin = "http://$hostPort"
            return UpdateSource("$origin$API_PATH", origin, origin, setOf(origin))
        }

        // Tags and asset names are matched literally in URL paths, so anything needing escaping is refused.
        private val SAFE_SEGMENT = Regex("""[A-Za-z0-9._+-]{1,128}""")

        fun isSafeSegment(value: String): Boolean = SAFE_SEGMENT.matches(value) && value != "." && value != ".."

        private fun parse(url: String): URI? {
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                return null
            }
            if (uri.rawUserInfo != null || uri.host.isNullOrEmpty() || uri.scheme == null) return null
            return uri
        }

        /** scheme://host[:port] in lower case; https on 443 is written without the port. */
        private fun origin(uri: URI): String {
            val scheme = uri.scheme.lowercase()
            val port = if (scheme == "https" && uri.port == 443) -1 else uri.port
            return "$scheme://${uri.host.lowercase()}" + if (port == -1) "" else ":$port"
        }
    }
}

internal data class ReleaseAsset(
    val name: String,
    val contentType: String,
    val size: Long,
    val url: String,
    val digest: String = "",
    val state: String = "uploaded",
)

/** An APK asset that passed the source checks. [sha256] is GitHub's server-side digest, lowercase hex. */
internal data class ApkAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

internal data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val apk: ApkAsset?,
    val prerelease: Boolean = false,
    /** The APK's versionCode as the release declares it; the downloaded APK must match it. */
    val versionCode: Long? = null,
) {
    val label: String get() = ReleaseVersions.displayVersion(tag)

    /** Why this release cannot be installed from inside the app, or null when it can. */
    val blockReason: String?
        get() = when {
            apk == null -> "Bu sürümde uygulamaya ait doğrulanabilir bir APK dosyası yok."
            apk.sha256.isEmpty() -> "Bu sürümün güvenlik özeti (SHA-256) yayımlanmamış; uygulama içinden kurulamaz."
            else -> null
        }

    /** versionCode decides when the release declares one; older releases fall back to the tag. */
    fun isNewerThan(app: InstalledApp): Boolean =
        versionCode?.let { it > app.versionCode } ?: ReleaseVersions.isNewer(tag, app.versionName, app.versionCode)
}

internal class ReleaseFormatException(message: String) : Exception(message)

internal object ReleaseParser {
    private val DIGEST = Regex("""sha256:([0-9a-fA-F]{64})""")

    // A line of its own in the release notes, e.g. "versionCode: 6"; it is not shown to the user.
    private val VERSION_CODE_LINE = Regex("""(?im)^[ \t]*versionCode[ \t]*[:=][ \t]*([0-9]{1,9})[ \t]*$\n?""")

    /** A single release object, as returned by `releases/latest`. Drafts and pre-releases are refused. */
    fun parse(body: String, source: UpdateSource = UpdateSource.GITHUB): ReleaseInfo {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw ReleaseFormatException("release JSON unreadable")
        }
        if (json.optBoolean("prerelease", false)) throw ReleaseFormatException("release is not published")
        return release(json, source)
    }

    /**
     * The `releases` list. Drafts are always skipped, pre-releases unless [withPrereleases];
     * an unreadable entry is skipped rather than hiding the others.
     */
    fun parseList(body: String, withPrereleases: Boolean, source: UpdateSource = UpdateSource.GITHUB): List<ReleaseInfo> {
        val array = try {
            JSONArray(body)
        } catch (e: JSONException) {
            throw ReleaseFormatException("release list unreadable")
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optBoolean("prerelease", false) && !withPrereleases) continue
                try {
                    add(release(item, source))
                } catch (e: ReleaseFormatException) {
                    continue
                }
            }
        }
    }

    /** The newest release this app may update to, or null when it is up to date. */
    fun pick(releases: List<ReleaseInfo>, app: InstalledApp): ReleaseInfo? =
        releases.filter { it.isNewerThan(app) }.sortedByDescending { it.versionCode ?: -1L }.firstOrNull()

    private fun release(json: JSONObject, source: UpdateSource): ReleaseInfo {
        if (json.optBoolean("draft", false)) throw ReleaseFormatException("release is not published")
        val tag = text(json, "tag_name").trim()
        if (!UpdateSource.isSafeSegment(tag)) throw ReleaseFormatException("release tag missing or unsafe")
        val title = clean(text(json, "name")).trim().take(UpdateSource.MAX_TITLE_CHARS).ifEmpty { tag }
        val rawNotes = clean(text(json, "body"))
        val declared = VERSION_CODE_LINE.findAll(rawNotes).map { it.groupValues[1].toLong() }.toSet()
        val tagCode = ReleaseVersions.declaredCode(tag)
        val codes = declared + listOfNotNull(tagCode)
        if (codes.size > 1) throw ReleaseFormatException("release declares conflicting version codes")
        val notes = rawNotes.replace(VERSION_CODE_LINE, "").take(UpdateSource.MAX_NOTES_CHARS)
        val assetsJson = json.optJSONArray("assets") ?: JSONArray()
        val assets = buildList {
            for (i in 0 until assetsJson.length()) {
                val item = assetsJson.optJSONObject(i) ?: continue
                add(
                    ReleaseAsset(
                        name = text(item, "name"),
                        contentType = text(item, "content_type"),
                        size = item.optLong("size", -1L),
                        url = text(item, "browser_download_url"),
                        digest = text(item, "digest"),
                        state = text(item, "state").ifEmpty { "uploaded" },
                    )
                )
            }
        }
        val trusted = assets.filter {
            it.state == "uploaded" &&
                it.size in 1..UpdateSource.MAX_APK_BYTES &&
                source.isOfficialAssetUrl(it.url, tag, it.name)
        }
        val chosen = selectApkAsset(trusted)
        val apk = chosen?.let {
            ApkAsset(
                name = it.name,
                url = it.url,
                size = it.size,
                sha256 = DIGEST.matchEntire(it.digest.trim())?.groupValues?.get(1)?.lowercase().orEmpty(),
            )
        }
        return ReleaseInfo(
            tag = tag,
            title = title,
            notes = notes,
            apk = apk,
            prerelease = json.optBoolean("prerelease", false),
            versionCode = codes.singleOrNull()?.takeIf { it > 0 },
        )
    }

    // Android's org.json turns JSON null into the string "null"; the JVM one does not.
    private fun text(json: JSONObject, key: String): String =
        if (json.isNull(key)) "" else json.optString(key, "")

    private fun clean(value: String): String =
        value.replace("\r\n", "\n").filter { it == '\n' || it == '\t' || !it.isISOControl() }
}

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
