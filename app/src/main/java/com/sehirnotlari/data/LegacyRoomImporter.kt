package com.sehirnotlari.data

import org.json.JSONObject

interface NoteImportStore {
    suspend fun findAny(id: String): NoteEntity?
    suspend fun upsert(note: NoteEntity)
}

class RoomNoteImportStore(private val dao: NoteDao) : NoteImportStore {
    override suspend fun findAny(id: String) = dao.findAny(id)
    override suspend fun upsert(note: NoteEntity) = dao.upsert(note)
}

/**
 * Opt-in legacy localStorage → Room import. The WebView must not call this
 * automatically; callers wrap [importOrThrow] in a database transaction so a
 * malformed payload rolls back instead of leaving a partial copy.
 */
object LegacyRoomImporter {
    const val MAX_PAYLOAD_CHARS = 2_000_000

    data class Outcome(val inserted: Int, val skipped: Int)

    fun oversizedPayloadMessage(json: String): String? =
        if (json.length > MAX_PAYLOAD_CHARS) "Import payload too large" else null

    suspend fun importOrThrow(
        json: String,
        store: NoteImportStore,
        stamp: () -> Long = { System.currentTimeMillis() },
    ): Outcome {
        val cities = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid legacy notes payload")
        }
        if (cities.length() == 0 && json.trim() != "{}") {
            throw IllegalArgumentException("Invalid legacy notes payload")
        }
        var inserted = 0
        var skipped = 0
        val keys = cities.keys()
        while (keys.hasNext()) {
            val city = keys.next()
            val notes = cities.optJSONArray(city)
                ?: throw IllegalArgumentException("Invalid notes array for city")
            for (i in 0 until notes.length()) {
                val item = notes.optJSONObject(i)
                    ?: throw IllegalArgumentException("Invalid note entry")
                val id = RoomNoteKeys.idFromJson(item.opt("id"))
                    ?: throw IllegalArgumentException("Missing or invalid note id")
                val key = RoomNoteKeys.key(city, id)
                // Existing active rows and tombstones are left untouched on retry.
                if (store.findAny(key) != null) {
                    skipped++
                    continue
                }
                val time = stamp()
                store.upsert(
                    NoteEntity(
                        id = key,
                        city = city,
                        title = item.optString("title"),
                        content = item.optString("content"),
                        category = item.optString("category", "Genel"),
                        star = item.optInt("star").coerceIn(0, 5),
                        color = item.optString("color"),
                        favorite = item.optBoolean("fav"),
                        dateLabel = item.optString("date"),
                        createdAt = time,
                        updatedAt = time
                    )
                )
                inserted++
            }
        }
        return Outcome(inserted, skipped)
    }
}
