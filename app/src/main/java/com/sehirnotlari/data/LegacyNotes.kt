package com.sehirnotlari.data

import org.json.JSONObject

/** One note from the WebView's `localStorage` copy (`tur_v10`), read like a bridge write. */
data class LegacyNote(val city: String, val draft: RoomNoteDraft) {
    val fingerprint: String = NoteFingerprint.of(draft)
}

/** A read-only parse of `tur_v10`; keys are Room keys (`city::id`). */
class LegacyNotes(val notes: Map<String, LegacyNote>, val skipped: Int) {
    companion object {
        fun parse(json: String): LegacyNotes {
            val cities = try {
                JSONObject(json)
            } catch (_: Exception) {
                throw RoomBridgeFailure("Invalid legacy notes payload")
            }
            val notes = linkedMapOf<String, LegacyNote>()
            var skipped = 0
            val keys = cities.keys()
            while (keys.hasNext()) {
                val city = keys.next()
                val array = cities.optJSONArray(city)
                if (array == null) {
                    skipped++
                    continue
                }
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val id = item?.let { RoomNoteKeys.idFromJson(it.opt("id")) }
                    val key = id?.let { RoomNoteKeys.key(city, it) }
                    // The WebView acts on the first note with an id, so a later duplicate is skipped too.
                    if (item == null || key == null || key in notes) {
                        skipped++
                        continue
                    }
                    notes[key] = LegacyNote(city, RoomNoteDraft.fromJson(item))
                }
            }
            return LegacyNotes(notes, skipped)
        }
    }
}
