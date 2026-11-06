package com.sehirnotlari.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One write from the Room-first WebView: the full note for an upsert, the id for a deletion. */
data class RoomFirstWrite(
    val op: String,
    val city: String,
    val noteId: String,
    val draft: RoomNoteDraft?,
    /** Ticket of the Room row the page edited; 0 when the page created the note. */
    val baseTicket: Long,
) {
    val key: String get() = RoomNoteKeys.key(city, noteId)
    val fingerprint: String? get() = draft?.let(NoteFingerprint::of)

    companion object {
        const val UPSERT = NoteWriteJournalEntry.UPSERT
        const val DELETE = NoteWriteJournalEntry.DELETE
        const val MAX_PAYLOAD = 100_000

        /** `{op, city, base, note: {id, …}}` for an upsert, `{op, city, base, id}` for a deletion. */
        fun parse(json: String): RoomFirstWrite {
            if (json.length > MAX_PAYLOAD) throw RoomBridgeFailure("Invalid write payload")
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                throw RoomBridgeFailure("Invalid write payload")
            }
            val op = o.optString("op")
            val city = o.optString("city")
            val base = o.optLong("base", -1L)
            val note = o.optJSONObject("note")
            val id = RoomNoteKeys.idFromJson(if (op == UPSERT) note?.opt("id") else o.opt("id"))
            if ((op != UPSERT && op != DELETE) || city.isBlank() || city.length > 120 || id == null || base < 0 ||
                (op == UPSERT && note == null)
            ) {
                throw RoomBridgeFailure("Invalid write payload")
            }
            return RoomFirstWrite(op, city, id, note?.let(RoomNoteDraft::fromJson), base)
        }
    }
}

/**
 * Result sent to the WebView strictly after the transaction committed (or after it was
 * refused without writing). [row] is Room's current row for the note, tombstones included.
 */
data class RoomFirstResult(
    val status: String,
    val ticket: Long? = null,
    val row: NoteEntity? = null,
) {
    /** Room holds the requested change: committed now, or by an earlier delivery of the same request. */
    val saved: Boolean get() = status in SAVED

    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("ticket", ticket ?: JSONObject.NULL)
        put("saved", saved)
        put("row", row?.let(::roomFirstRowJson) ?: JSONObject.NULL)
    }

    companion object {
        const val COMMITTED = "COMMITTED"
        /** Room already holds exactly this note (a re-sent request whose reply was lost); nothing written. */
        const val ALREADY_SAVED = "ALREADY_SAVED"
        /** Deletion of a note that already is a tombstone, or that never reached Room; nothing written. */
        const val ALREADY_DELETED = "ALREADY_DELETED"
        /** Room's row is not the version the page edited (or a new note's id is taken); nothing written. */
        const val CONFLICT = "CONFLICT"
        /** Upsert of a tombstone; deleted notes are never revived. Nothing written. */
        const val DELETED = "DELETED"
        private val SAVED = setOf(COMMITTED, ALREADY_SAVED, ALREADY_DELETED)
    }
}

/** A Room row in the WebView's note shape plus the fields the Room-first page needs. */
fun roomFirstRowJson(note: NoteEntity): JSONObject = JSONObject().apply {
    put("key", note.id)
    put("city", note.city)
    put("id", RoomNoteKeys.webViewId(note))
    put("title", note.title)
    put("content", note.content)
    put("category", note.category)
    put("star", note.star)
    put("color", note.color)
    put("fav", note.favorite)
    put("date", note.dateLabel)
    note.isAI?.let { put("isAI", it) }
    put("ticket", note.writeTicket)
    put("deleted", note.deletedAt != null)
}

/** Room's committed notes for the Room-first page, plus the SQLite settings that decide durability. */
data class RoomFirstSnapshot(
    val rows: List<NoteEntity>,
    val journalMode: String,
    val synchronous: String,
) {
    val active: List<NoteEntity> get() = rows.filter { it.deletedAt == null }

    fun logLine() = "active=${active.size} tombstones=${rows.size - active.size} journalMode=$journalMode synchronous=$synchronous"

    fun toJson(): JSONObject = JSONObject().apply {
        val notes = JSONObject()
        active.sortedBy { it.writeTicket }.forEach { note ->
            (notes.optJSONArray(note.city) ?: JSONArray().also { notes.put(note.city, it) }).put(roomFirstRowJson(note))
        }
        put("notes", notes)
        put("active", active.size)
        put("tombstones", rows.size - active.size)
        put("journalMode", journalMode)
        put("synchronous", synchronous)
    }
}

/**
 * Room-first writes. One write is one transaction under the sequencer's mutex, so a reply is
 * only produced after COMMIT returned; a crash before COMMIT leaves nothing behind.
 *
 * Every write names the ticket of the version it was made from ([RoomFirstWrite.baseTicket]),
 * which makes it apply at most once without storing request ids: after it applied, the row's
 * ticket has moved on (tickets are never reused). A re-sent request then finds either its own
 * content ([RoomFirstResult.ALREADY_SAVED]) or a different version ([RoomFirstResult.CONFLICT]),
 * and changes nothing. Ordering tickets, `modifiedAt` and tombstone rules are the sequencer's.
 */
class RoomFirstWriter(
    private val db: CityNotesDatabase,
    private val sequencer: RoomNoteWriteSequencer,
    /** Checked under the lock: a write queued before the mode was switched off is refused. */
    private val enabled: () -> Boolean,
    /** Test hook, runs inside the transaction after the write and before COMMIT. */
    private val beforeCommit: suspend () -> Unit = {},
) {
    suspend fun write(write: RoomFirstWrite): RoomFirstResult = sequencer.mutex.withLock {
        if (!enabled()) throw RoomBridgeFailure("Room-first mode is off")
        db.withTransaction {
            val row = db.notes().findAny(write.key)
            val result = when {
                row?.deletedAt != null ->
                    RoomFirstResult(if (write.op == RoomFirstWrite.DELETE) RoomFirstResult.ALREADY_DELETED else RoomFirstResult.DELETED, row = row)
                row == null && write.op == RoomFirstWrite.DELETE -> RoomFirstResult(RoomFirstResult.ALREADY_DELETED)
                row != null && row.writeTicket != write.baseTicket ->
                    if (write.op == RoomFirstWrite.UPSERT && NoteFingerprint.of(row) == write.fingerprint) {
                        RoomFirstResult(RoomFirstResult.ALREADY_SAVED, row.writeTicket, row)
                    } else {
                        RoomFirstResult(RoomFirstResult.CONFLICT, row = row)
                    }
                row == null && write.baseTicket != 0L -> RoomFirstResult(RoomFirstResult.CONFLICT)
                else -> {
                    val next = sequencer.nextTicket()
                    val ack = if (write.op == RoomFirstWrite.UPSERT) {
                        sequencer.applyUpsert(write.city, write.key, write.draft!!, next)
                    } else {
                        sequencer.applyDelete(write.city, write.key, next)
                    }
                    if (ack != RoomWriteAck.Applied) throw RoomBridgeFailure("Write was not applied")
                    RoomFirstResult(RoomFirstResult.COMMITTED, next, db.notes().findAny(write.key))
                }
            }
            beforeCommit()
            result
        }
    }

    /** Reads after any queued write finished. */
    suspend fun snapshot(): RoomFirstSnapshot = sequencer.mutex.withLock {
        RoomFirstSnapshot(db.notes().allRows(), pragma("journal_mode"), pragma("synchronous").let { SYNCHRONOUS[it] ?: it })
    }

    private fun pragma(name: String): String =
        db.query(SimpleSQLiteQuery("PRAGMA $name")).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "?"
        }

    companion object {
        private val SYNCHRONOUS = mapOf("0" to "OFF", "1" to "NORMAL", "2" to "FULL", "3" to "EXTRA")
    }
}
