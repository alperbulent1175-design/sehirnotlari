package com.sehirnotlari

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files

class PdfGeminiClientTest {
    @Test fun streamsPdfThenSummarizesAndDeletesRemoteFile() {
        val file = Files.createTempFile("pdf-gemini-test", ".pdf").toFile()
        file.writeBytes("%PDF-".toByteArray() + ByteArray(256 * 1024) { 7 })
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
                "/v1beta/models/gemini-3.8-flash:generateContent" -> {
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
