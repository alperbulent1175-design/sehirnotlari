package com.sehirnotlari.data

import android.util.AtomicFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class RoomFirstOutboxTest {
    @Test
    fun survivesReopenAndInterruptedReplacement() {
        val file = Files.createTempDirectory("room-outbox-test").resolve("outbox.json").toFile()
        val outbox = RoomFirstOutbox(file)
        assertNull(outbox.read())
        outbox.write("[{\"id\":1}]")
        assertEquals("[{\"id\":1}]", RoomFirstOutbox(file).read())

        // An interrupted replacement must leave the last completed journal intact.
        val interrupted = AtomicFile(file).startWrite()
        interrupted.write("partial".toByteArray())
        interrupted.close()
        assertEquals("[{\"id\":1}]", RoomFirstOutbox(file).read())
    }
}
