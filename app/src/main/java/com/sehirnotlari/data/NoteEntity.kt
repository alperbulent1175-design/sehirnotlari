package com.sehirnotlari.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notes", indices = [Index("city"), Index("writeTicket")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val city: String,
    val title: String,
    val content: String = "",
    val category: String = "Genel",
    val star: Int = 0,
    val color: String = "",
    val favorite: Boolean = false,
    /** Null: row written before schema v2, AI origin was never recorded. */
    val isAI: Boolean? = null,
    val dateLabel: String = "",
    /** Wall-clock time the row was first written to Room (not the note's creation in the WebView). */
    val createdAt: Long = System.currentTimeMillis(),
    /** Hybrid-clock write order from [RoomNoteWriteSequencer]; not a wall-clock time. */
    val writeTicket: Long = 0L,
    /** Wall-clock time of the last applied change. Null when unknown (rows from v1, legacy imports). */
    val modifiedAt: Long? = null,
    val deletedAt: Long? = null,
    val syncState: String = "LOCAL"
)
