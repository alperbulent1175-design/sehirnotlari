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

    @Test
    fun reconcileResultPassesReportAsObjectOrError() {
        assertEquals(
            "window.onRoomReconcileResult && window.onRoomReconcileResult(\"r-1\",{\"summary\":{}},null);",
            RoomBridgeScripts.reconcileResult("r-1", org.json.JSONObject().put("summary", org.json.JSONObject()), null)
        )
        assertEquals(
            "window.onRoomReconcileResult && window.onRoomReconcileResult(\"r-2\",null,\"SQLiteFullException\");",
            RoomBridgeScripts.reconcileResult("r-2", null, "SQLiteFullException")
        )
    }

    @Test
    fun dataSafetyResultsOnlyReachKnownCallbacks() {
        assertEquals(
            "window.onRoomInspectResult && window.onRoomInspectResult(\"ds-1\",{\"kind\":null},null);",
            RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.INSPECT_RESULT, "ds-1", org.json.JSONObject().put("kind", org.json.JSONObject.NULL), null)
        )
        assertEquals(
            "window.onDataSafetyStatus && window.onDataSafetyStatus(\"ds-\\\"2\",null,\"Room unavailable\");",
            RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.SAFETY_STATUS, "ds-\"2", null, "Room unavailable")
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RoomBridgeScripts.dataSafetyResult("alert", "ds-3", null, null)
        }
    }

    @Test
    fun roomFirstResultsOnlyReachRoomFirstCallbacks() {
        assertEquals(
            "window.onRoomFirstResult && window.onRoomFirstResult(\"rf-1\",{\"saved\":true},null);",
            RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_RESULT, "rf-1", org.json.JSONObject().put("saved", true), null)
        )
        assertEquals(
            "window.onRoomFirstSnapshot && window.onRoomFirstSnapshot(\"rf-2\",null,\"Room-first mode is off\");",
            RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_SNAPSHOT, "rf-2", null, "Room-first mode is off")
        )
        listOf("alert", RoomBridgeScripts.SAFETY_STATUS).forEach { name ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                RoomBridgeScripts.roomFirstResult(name, "rf-3", null, null)
            }
        }
    }
}
