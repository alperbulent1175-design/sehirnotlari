package com.sehirnotlari.data

import org.json.JSONObject

/** Exact copy of a Room row, used for repair and resolution backups so a change can be undone. */
object NoteRowJson {
    fun encode(note: NoteEntity): String = JSONObject().apply {
        put("id", note.id)
        put("city", note.city)
        put("title", note.title)
        put("content", note.content)
        put("category", note.category)
        put("star", note.star)
        put("color", note.color)
        put("favorite", note.favorite)
        put("isAI", note.isAI ?: JSONObject.NULL)
        put("dateLabel", note.dateLabel)
        put("createdAt", note.createdAt)
        put("writeTicket", note.writeTicket)
        put("modifiedAt", note.modifiedAt ?: JSONObject.NULL)
        put("deletedAt", note.deletedAt ?: JSONObject.NULL)
        put("syncState", note.syncState)
    }.toString()

    fun decode(json: String): NoteEntity {
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw RoomBridgeFailure("Invalid backup row")
        }
        fun optLong(name: String): Long? = if (o.isNull(name)) null else o.getLong(name)
        return NoteEntity(
            id = o.getString("id"),
            city = o.getString("city"),
            title = o.getString("title"),
            content = o.getString("content"),
            category = o.getString("category"),
            star = o.getInt("star"),
            color = o.getString("color"),
            favorite = o.getBoolean("favorite"),
            isAI = if (o.isNull("isAI")) null else o.getBoolean("isAI"),
            dateLabel = o.getString("dateLabel"),
            createdAt = o.getLong("createdAt"),
            writeTicket = o.getLong("writeTicket"),
            modifiedAt = optLong("modifiedAt"),
            deletedAt = optLong("deletedAt"),
            syncState = o.getString("syncState"),
        )
    }
}
