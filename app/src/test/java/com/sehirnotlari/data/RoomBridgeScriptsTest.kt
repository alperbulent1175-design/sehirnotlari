package com.sehirnotlari.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBridgeScriptsTest {
    @Test
    fun successImportReachesWebViewCallback() {
        val script = RoomBridgeScripts.importResult(true, 2, 1, null)
        assertEquals(
            "window.onRoomImportResult && window.onRoomImportResult(true,2,1,null);",
            script
        )
    }

    @Test
    fun failureImportReachesWebViewCallbackWithQuotedError() {
        val script = RoomBridgeScripts.importResult(false, 0, 0, "Missing or invalid note id")
        assertTrue(script.startsWith("window.onRoomImportResult && window.onRoomImportResult(false,0,0,"))
        assertTrue(script.contains("Missing or invalid note id"))
        assertTrue(script.endsWith(");"))
    }

    @Test
    fun writeAndDeleteResultsReachMatchingCallbacks() {
        assertEquals(
            "window.onRoomWriteResult && window.onRoomWriteResult(\"req-1\",true,null);",
            RoomBridgeScripts.writeResult("req-1", true, null)
        )
        val failed = RoomBridgeScripts.deleteResult("req-2", false, "Room delete failed")
        assertTrue(failed.contains("window.onRoomDeleteResult"))
        assertTrue(failed.contains("false"))
        assertTrue(failed.contains("Room delete failed"))
        assertTrue(RoomBridgeScripts.notesError("Room read failed").contains("onRoomNotesError"))
    }
}
