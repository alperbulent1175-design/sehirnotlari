package com.sehirnotlari.data

import java.security.MessageDigest

/** Identifies a note's field values without storing them. Fields are length-prefixed so boundaries cannot shift. */
object NoteFingerprint {
    fun of(draft: RoomNoteDraft): String = sha256(
        listOf(
            draft.title,
            draft.content,
            draft.category,
            draft.star.toString(),
            draft.color,
            draft.favorite.toString(),
            draft.isAI.toString(),
            draft.dateLabel,
        ).joinToString("") { "${it.length}:$it;" }
    )

    /** Null when the row's AI origin is unknown (rows from schema v1). */
    fun of(note: NoteEntity): String? = note.isAI?.let { of(note.toDraft(it)) }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

fun NoteEntity.toDraft(isAI: Boolean = this.isAI ?: false) = RoomNoteDraft(
    title = title,
    content = content,
    category = category,
    star = star,
    color = color,
    favorite = favorite,
    dateLabel = dateLabel,
    isAI = isAI,
)

/**
 * Names of the fields that differ between localStorage and Room. An unknown Room `isAI`
 * (rows from schema v1) is not a difference; callers report it as unknown origin.
 */
fun RoomNoteDraft.differingFields(room: NoteEntity): List<String> = buildList {
    if (title != room.title) add("title")
    if (content != room.content) add("content")
    if (category != room.category) add("category")
    if (star != room.star) add("star")
    if (color != room.color) add("color")
    if (favorite != room.favorite) add("favorite")
    if (room.isAI != null && isAI != room.isAI) add("isAI")
    if (dateLabel != room.dateLabel) add("date")
}
