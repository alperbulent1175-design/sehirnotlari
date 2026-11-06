package com.sehirnotlari.update

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Turkish, user-facing reasons. [blocksInstall] marks verification failures that stop installation. */
internal enum class FailureKind(val message: String, val blocksInstall: Boolean = false) {
    OFFLINE("İnternet bağlantısı yok ya da GitHub'a ulaşılamadı."),
    SERVER("GitHub şu an yanıt vermiyor. Biraz sonra yeniden deneyin."),
    BAD_RELEASE("Sürüm bilgisi okunamadı."),
    SOURCE("İndirme adresi resmî GitHub kaynağının dışına çıktığı için durduruldu.", true),
    SIZE("İndirilen dosyanın boyutu beklenenle uyuşmuyor.", true),
    HASH("İndirilen dosyanın SHA-256 güvenlik özeti uyuşmuyor.", true),
    PACKAGE("İndirilen dosya bu uygulamaya ait değil.", true),
    VERSION("İndirilen dosyanın sürümü duyurulan sürümle uyuşmuyor.", true),
    SIGNATURE("İndirilen dosyanın imzası yüklü uygulamayla uyuşmuyor.", true),
    STORAGE("Güncelleme dosyası kaydedilemedi. Depolama alanını kontrol edin."),
    INSTALLER("Paket yükleyici açılamadı."),
    RATE_LIMITED("GitHub'a çok sık istek gönderildi; GitHub bir süre bekletiyor."),
    INSTALL_CANCELLED("Kurulum iptal edildi. Hazır olduğunuzda yeniden deneyebilirsiniz."),
    INSTALL_REJECTED("Android bu güncellemeyi kurmadı: sürüm veya imza yüklü uygulamayla uyumsuz.", true),
}

internal class UpdateFailure(
    val kind: FailureKind,
    val retryable: Boolean,
    /** For [FailureKind.RATE_LIMITED]: when GitHub accepts requests again (epoch millis). */
    val retryAt: Long = 0L,
) : IOException(kind.name)

internal class HttpResponse(
    val code: Int,
    val location: String?,
    val contentLength: Long,
    private val openBody: () -> InputStream,
    private val release: () -> Unit,
    private val headers: (String) -> String? = { null },
) : Closeable {
    fun body(): InputStream = openBody()
    fun header(name: String): String? = headers(name)
    override fun close() = release()
}

/** One request, never following redirects itself. */
internal fun interface HttpTransport {
    fun open(url: String, headers: Map<String, String>): HttpResponse
}

internal class UrlConnectionTransport : HttpTransport {
    override fun open(url: String, headers: Map<String, String>): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "SehirNotlari-Android")
        conn.setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        return try {
            val code = conn.responseCode
            HttpResponse(
                code = code,
                location = conn.getHeaderField("Location"),
                contentLength = conn.contentLengthLong,
                openBody = { if (code in 200..299) conn.inputStream else conn.errorStream ?: ByteArrayInputStream(ByteArray(0)) },
                release = { conn.disconnect() },
                headers = { conn.getHeaderField(it) },
            )
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }
}

internal object UpdateHttp {
    private const val MAX_REDIRECTS = 5

    /**
     * Follows redirects by hand so every hop is checked. [firstHop] judges the starting URL,
     * [laterHop] every redirect target; a refused URL is never requested.
     */
    fun openChecked(
        transport: HttpTransport,
        startUrl: String,
        headers: Map<String, String>,
        firstHop: (String) -> Boolean,
        laterHop: (String) -> Boolean,
    ): HttpResponse {
        var current = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            val allowed = if (hop == 0) firstHop(current) else laterHop(current)
            if (!allowed) throw UpdateFailure(FailureKind.SOURCE, retryable = false)
            val response = transport.open(current, headers)
            if (response.code !in 300..399) return response
            val location = response.location
            response.close()
            if (location.isNullOrBlank()) throw UpdateFailure(FailureKind.SERVER, retryable = true)
            current = try {
                URL(URL(current), location).toString()
            } catch (e: Exception) {
                throw UpdateFailure(FailureKind.SOURCE, retryable = false)
            }
        }
        throw UpdateFailure(FailureKind.SOURCE, retryable = false)
    }

    fun httpFailure(code: Int): UpdateFailure =
        UpdateFailure(FailureKind.SERVER, retryable = code >= 500 || code == 408 || code == 429)

    /**
     * GitHub's primary limit answers 403/429 with `x-ratelimit-remaining: 0` and a reset time;
     * the secondary limit sends `retry-after`. Returns when to ask again, or null for other errors.
     */
    fun rateLimitedUntil(response: HttpResponse, now: Long): Long? {
        if (response.code != 403 && response.code != 429) return null
        response.header("retry-after")?.trim()?.toLongOrNull()?.let { return now + it.coerceIn(1, MAX_WAIT_S) * 1000 }
        if (response.header("x-ratelimit-remaining")?.trim() == "0") {
            val reset = response.header("x-ratelimit-reset")?.trim()?.toLongOrNull()?.times(1000)
            return reset?.takeIf { it > now }?.coerceAtMost(now + MAX_WAIT_S * 1000) ?: (now + DEFAULT_WAIT_S * 1000)
        }
        return if (response.code == 429) now + DEFAULT_WAIT_S * 1000 else null
    }

    private const val MAX_WAIT_S = 6L * 60 * 60
    private const val DEFAULT_WAIT_S = 60L * 60
}
