package com.sehirnotlari

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.encoding.Base64

class PdfGeminiClientTest {
    @Test fun sendsSmallPdfInlineAndOmitsThoughtParts() {
        val file = Files.createTempFile("pdf-inline-test", ".pdf").toFile()
        file.writeText("%PDF-1.7 test")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestPath = ""
        var requestBody = ""
        server.createContext("/") { exchange ->
            requestPath = exchange.requestURI.path
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = """{"candidates":[{"content":{"parts":[{"text":"hidden","thought":true},{"text":"BAŞLIK: Test İÇERİK: Özet"}]}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        server.start()
        try {
            val stages = mutableListOf<String>()
            val client = PdfGeminiClient("test-key", "http://127.0.0.1:${server.address.port}")
            assertEquals("BAŞLIK: Test İÇERİK: Özet", client.summarize(file, "Özetle", onStage = stages::add))
            assertEquals(listOf("summarizing"), stages)
            assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", requestPath)
            val parts = JSONObject(requestBody).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            val data = parts.getJSONObject(0).getJSONObject("inline_data")
            assertEquals("application/pdf", data.getString("mime_type"))
            assertEquals(Base64.encode(file.readBytes()), data.getString("data"))
            assertEquals("Özetle", parts.getJSONObject(1).getString("text"))
        } finally { server.stop(0); file.delete() }
    }

    @Test fun streamsPdfThenSummarizesAndDeletesRemoteFile() {
        val file = Files.createTempFile("pdf-gemini-test", ".pdf").toFile()
        file.writeBytes("%PDF-".toByteArray() + ByteArray(PdfGeminiClient.INLINE_LIMIT_BYTES.toInt()) { 7 })
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val events = mutableListOf<String>()
        var uploaded = 0
        var prompt = ""
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            events += "${exchange.requestMethod} $path"
            val body = exchange.requestBody.use { it.readBytes() }
            val response = when (path) {
                "/upload/v1beta/files" -> {
                    assertEquals("secret", exchange.requestHeaders.getFirst("x-goog-api-key"))
                    exchange.responseHeaders.add("X-Goog-Upload-URL", "http://127.0.0.1:${server.address.port}/upload-session")
                    "{}"
                }
                "/upload-session" -> {
                    uploaded = body.size
                    JSONObject().put("file", JSONObject()
                        .put("name", "files/test")
                        .put("state", "ACTIVE")
                        .put("uri", "https://generativelanguage.googleapis.com/v1beta/files/test")).toString()
                }
                "/v1beta/models/gemini-3.5-flash-lite:generateContent" -> {
                    prompt = JSONObject(String(body)).getJSONArray("contents").getJSONObject(0)
                        .getJSONArray("parts").getJSONObject(1).getString("text")
                    """{"candidates":[{"content":{"parts":[{"text":"BAŞLIK: Test\\nİÇERİK: Özet"}]}}]}"""
                }
                "/v1beta/files/test" -> "{}"
                else -> error("Unexpected request: $path")
            }
            if (exchange.requestMethod == "DELETE") exchange.sendResponseHeaders(204, -1)
            else {
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        try {
            val progress = mutableListOf<Int>()
            val stages = mutableListOf<String>()
            val result = PdfGeminiClient("secret", "http://127.0.0.1:${server.address.port}")
                .summarize(file, "Summarize accurately", progress::add, stages::add)
            assertTrue(result.contains("Özet"))
            assertEquals(file.length().toInt(), uploaded)
            assertEquals("Summarize accurately", prompt)
            assertEquals(100, progress.last())
            assertEquals(listOf("processing", "summarizing"), stages)
            assertEquals("DELETE /v1beta/files/test", events.last())
        } finally { server.stop(0); file.delete() }
    }
}
