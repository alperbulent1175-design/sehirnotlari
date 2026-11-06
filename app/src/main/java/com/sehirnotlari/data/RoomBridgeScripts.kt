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

    fun reconcileResult(requestId: String, report: JSONObject?, error: String?): String {
        val err = if (error == null) "null" else JSONObject.quote(error)
        return "window.onRoomReconcileResult && window.onRoomReconcileResult(${JSONObject.quote(requestId)},${report ?: "null"},$err);"
    }

    fun restoreResult(requestId: String, result: JSONObject?, error: String?): String = callback("onRoomRestoreResult", requestId, result, error)

    fun notesError(error: String): String =
        "window.onRoomNotesError && window.onRoomNotesError(${JSONObject.quote(error)});"

    /** Data-safety screen callbacks; only these names can be called. */
    const val INSPECT_RESULT = "onRoomInspectResult"
    const val RESOLUTION_RESULT = "onRoomResolutionResult"
    const val SAFETY_STATUS = "onDataSafetyStatus"
    private val dataSafetyCallbacks = setOf(INSPECT_RESULT, RESOLUTION_RESULT, SAFETY_STATUS)

    fun dataSafetyResult(callback: String, requestId: String, result: JSONObject?, error: String?): String {
        require(callback in dataSafetyCallbacks)
        return callback(callback, requestId, result, error)
    }

    /** Room-first prototype callbacks (debug builds only); only these names can be called. */
    const val ROOM_FIRST_SNAPSHOT = "onRoomFirstSnapshot"
    const val ROOM_FIRST_RESULT = "onRoomFirstResult"
    const val ROOM_FIRST_SWITCH = "onRoomFirstSwitch"
    private val roomFirstCallbacks = setOf(ROOM_FIRST_SNAPSHOT, ROOM_FIRST_RESULT, ROOM_FIRST_SWITCH)

    fun roomFirstResult(callback: String, requestId: String, result: JSONObject?, error: String?): String {
        require(callback in roomFirstCallbacks)
        return callback(callback, requestId, result, error)
    }

    private fun callback(name: String, requestId: String, result: JSONObject?, error: String?): String {
        val err = if (error == null) "null" else JSONObject.quote(error)
        return "window.$name && window.$name(${JSONObject.quote(requestId)},${result ?: "null"},$err);"
    }
}
