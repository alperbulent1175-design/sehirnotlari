package com.sehirnotlari.data

/** Bridge failure with fixed text and no note data, so its message may reach Logcat and the WebView. */
class RoomBridgeFailure(message: String) : IllegalArgumentException(message)

object RoomBridgeErrors {
    /**
     * Text that is safe to log or show. Other exception messages can quote the payload
     * (`JSONException` does), so only their type is reported.
     */
    fun safe(e: Throwable): String =
        if (e is RoomBridgeFailure) e.message.orEmpty() else e.javaClass.simpleName.ifEmpty { "Error" }
}
