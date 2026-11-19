package com.sehirnotlari.data

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Synchronous, atomically replaced debug Room-first intent journal. */
class RoomFirstOutbox(file: File) {
    private val atomic = AtomicFile(file)

    @Synchronized
    fun read(): String? {
        return try {
            atomic.openRead().use { stream ->
                val bytes = stream.readBytes()
                require(bytes.size <= MAX_BYTES) { "Room outbox is too large" }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    @Synchronized
    fun write(body: String) {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Room outbox is too large" }
        var stream = atomic.startWrite()
        try {
            stream.write(body.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
