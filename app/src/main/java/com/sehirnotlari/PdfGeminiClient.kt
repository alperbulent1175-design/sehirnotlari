package com.sehirnotlari

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Streams a staged PDF to Gemini Files API, then summarizes by file URI. */
class PdfGeminiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) {
    fun summarize(file: File, prompt: String, onProgress: (Int) -> Unit = {}, onStage: (String) -> Unit = {}): String {
        require(file.length() in 1..PDF_LIMIT_BYTES) { "Invalid PDF size" }
        val uploadUrl = startUpload(file.length())
        val uploaded = upload(uploadUrl, file, onProgress)
        val metadata = uploaded.getJSONObject("file")
        val name = metadata.getString("name")
        if (!Regex("files/[A-Za-z0-9_-]+").matches(name)) throw IOException("Invalid file name")
        try {
            onStage("processing")
            var state = metadata.optString("state")
            var uri = metadata.getString("uri")
            var attempts = 0
            while (state != "ACTIVE" && attempts < 60) {
                if (state == "FAILED") throw IOException("PDF processing failed")
                Thread.sleep(1000)
                val current = request("GET", "/v1beta/$name")
                state = current.optString("state")
                uri = current.optString("uri", uri)
                attempts++
            }
            if (state != "ACTIVE") throw IOException("PDF processing timed out")
            if (!uri.startsWith("https://generativelanguage.googleapis.com/")) throw IOException("Invalid file URI")
            onStage("summarizing")
            val parts = JSONArray()
                .put(JSONObject().put("file_data", JSONObject().put("mime_type", "application/pdf").put("file_uri", uri)))
                .put(JSONObject().put("text", prompt))
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            val response = request("POST", "/v1beta/models/gemini-3.8-flash:generateContent", body)
            val answer = response.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            return (0 until answer.length()).joinToString("\n") { answer.getJSONObject(it).optString("text") }.trim()
        } finally {
            runCatching { request("DELETE", "/v1beta/$name") }
        }
    }

    private fun startUpload(size: Long): URL {
        val connection = open("/upload/v1beta/files")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            connection.setRequestProperty("X-Goog-Upload-Command", "start")
            connection.setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
            connection.setRequestProperty("X-Goog-Upload-Header-Content-Type", "application/pdf")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{\"file\":{\"display_name\":\"Şehir Notları PDF\"}}".toByteArray()) }
            if (connection.responseCode !in 200..299) throw IOException("Upload start failed")
            val url = URL(connection.getHeaderField("X-Goog-Upload-URL") ?: throw IOException("Missing upload URL"))
            val expected = URI(baseUrl)
            if (url.protocol != expected.scheme || url.host != expected.host) throw IOException("Unexpected upload host")
            return url
        } finally { connection.disconnect() }
    }

    private fun upload(url: URL, file: File, onProgress: (Int) -> Unit): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            setRequestProperty("Content-Type", "application/pdf")
        }
        try {
            var sent = 0L
            var reported = -1
            file.inputStream().buffered().use { input ->
                connection.outputStream.buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        val percent = (sent * 100 / file.length()).toInt()
                        if (percent / 5 != reported / 5) { reported = percent; onProgress(percent) }
                    }
                }
            }
            return response(connection)
        } finally { connection.disconnect() }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = open(path)
        try {
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            return response(connection)
        } finally { connection.disconnect() }
    }

    private fun open(path: String): HttpURLConnection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 120_000
        instanceFollowRedirects = false
        setRequestProperty("x-goog-api-key", apiKey)
    }

    private fun response(connection: HttpURLConnection): JSONObject {
        if (connection.responseCode !in 200..299) throw IOException("Gemini request failed: HTTP ${connection.responseCode}")
        if (connection.responseCode == 204) return JSONObject()
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1_000_000) throw IOException("Gemini response too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    companion object { const val PDF_LIMIT_BYTES = 30_000_000L }
}
