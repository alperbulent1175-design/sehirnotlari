package com.sehirnotlari

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Notes JSON files saved and opened through the Android system file screens.
 * Results reach the page only as encoded JSON; note content never goes to the log.
 */
object NotesFile {
    const val MIME = "application/json"
    const val MAX_BYTES = 5 * 1024 * 1024
    const val SAVED_CALLBACK = "onNotesFileSaved"
    const val OPENED_CALLBACK = "onNotesFileOpened"
    const val DEFAULT_NAME = "turkiye-notlari.json"

    // File managers label .json files inconsistently.
    val OPEN_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

    private val SAFE_NAME = Regex("""^[\p{L}\p{N} ._()-]{1,80}\.json$""")
    private const val TOO_LARGE = "Dosya çok büyük (en fazla 5 MB)."
    private const val NOT_NOTES = "Bu dosya bir Şehir Notları yedeği değil."

    /** A refusal whose message is shown to the user as is. */
    class Refused(message: String) : Exception(message)

    fun suggestedName(requested: String): String = requested.trim().takeIf { SAFE_NAME.matches(it) } ?: DEFAULT_NAME

    fun exportBytes(json: String): ByteArray {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw Refused("Yedek çok büyük (en fazla 5 MB).")
        if (!isJsonObject(json)) throw Refused("Yedek hazırlanamadı.")
        return bytes
    }

    fun readLimited(input: InputStream, max: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw Refused(TOO_LARGE)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun checkDeclaredSize(size: Long?) {
        if (size != null && size > MAX_BYTES) throw Refused(TOO_LARGE)
    }

    /** Strict UTF-8 text holding a JSON object; the page then checks every note before its preview. */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw Refused("Dosya boş.")
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw Refused(NOT_NOTES)
        }.removePrefix("\uFEFF")
        if (text.isBlank()) throw Refused("Dosya boş.")
        if (!isJsonObject(text)) throw Refused(if (text.trimStart().startsWith("{")) "Dosya geçerli bir JSON değil." else NOT_NOTES)
        return text
    }

    private fun isJsonObject(text: String): Boolean = try {
        JSONTokener(text).nextValue() is JSONObject
    } catch (_: Exception) {
        false
    }

    fun saved(name: String?, bytes: Int): JSONObject = JSONObject().put("status", "ok").put("name", name ?: "").put("bytes", bytes)
    fun opened(name: String?, text: String): JSONObject = JSONObject().put("status", "ok").put("name", name ?: "").put("text", text)
    fun cancelled(): JSONObject = JSONObject().put("status", "cancel")
    fun failed(message: String): JSONObject = JSONObject().put("status", "error").put("message", message)

    /** `window.cb && window.cb({...})`; the object is JSON, with the two line separators JavaScript once rejected escaped. */
    fun script(callback: String, result: JSONObject): String {
        require(callback == SAVED_CALLBACK || callback == OPENED_CALLBACK)
        val json = result.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return "window.$callback && window.$callback($json);"
    }
}
