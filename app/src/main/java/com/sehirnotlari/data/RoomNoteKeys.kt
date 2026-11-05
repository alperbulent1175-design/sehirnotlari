package com.sehirnotlari.data

import org.json.JSONObject

/**
 * Room primary keys are `city::id`, where `id` must equal JavaScript's `String(note.id)`.
 * Legacy Date.now() numbers, new microsecond-scaled numbers and imported string ids
 * all map to the same text the WebView compares with.
 */
object RoomNoteKeys {
    const val MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991L

    fun key(city: String, id: String): String = "$city::$id"

    /** Returns the WebView-compatible id text, or null when the JSON value is unusable. */
    fun idFromJson(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.takeIf { it.isNotBlank() }
        is Int, is Long -> value.toString()
        is Double -> if (value.isFinite() && value == Math.floor(value) && kotlin.math.abs(value) <= MAX_SAFE_JS_INTEGER) {
            value.toLong().toString()
        } else if (value.isFinite()) {
            value.toString()
        } else {
            null
        }
        is Number -> value.toString()
        else -> null
    }

    /** Id value for the WebView snapshot: a JS-safe integer stays numeric, anything else stays text. */
    fun webViewId(note: NoteEntity): Any {
        val raw = note.id.removePrefix("${note.city}::")
        val numeric = raw.toLongOrNull()
        return if (numeric != null && numeric.toString() == raw && kotlin.math.abs(numeric) <= MAX_SAFE_JS_INTEGER) {
            numeric
        } else {
            raw
        }
    }
}
