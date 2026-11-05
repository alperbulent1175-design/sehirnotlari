package com.sehirnotlari.data

import org.json.JSONObject

/** JavaScript snippets delivered to WebView after Room bridge work. */
object RoomBridgeScripts {
    fun importResult(ok: Boolean, inserted: Int, skipped: Int, error: String?): String {
        val err = if (error == null) "null" else JSONObject.quote(error)
        return "window.onRoomImportResult && window.onRoomImportResult($ok,$inserted,$skipped,$err);"
    }

    fun writeResult(requestId: String, ok: Boolean, error: String?): String {
        val err = if (error == null) "null" else JSONObject.quote(error)
        return "window.onRoomWriteResult && window.onRoomWriteResult(${JSONObject.quote(requestId)},$ok,$err);"
    }

    fun deleteResult(requestId: String, ok: Boolean, error: String?): String {
        val err = if (error == null) "null" else JSONObject.quote(error)
        return "window.onRoomDeleteResult && window.onRoomDeleteResult(${JSONObject.quote(requestId)},$ok,$err);"
    }

    fun notesError(error: String): String =
        "window.onRoomNotesError && window.onRoomNotesError(${JSONObject.quote(error)});"
}
