package com.sehirnotlari.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One note of a confirmed JSON restore; [ticket] was taken and journaled on the bridge thread. */
data class RestoreItem(val city: String, val noteId: String, val ticket: Long) {
    val key: String get() = RoomNoteKeys.key(city, noteId)
}

/**
 * A note the page is about to store for a confirmed JSON restore, as sent before `tur_v10` is written.
 * [json] is the note object itself, staged so the import survives a crash ([ImportStaging]).
 */
data class RestoreNote(val city: String, val noteId: String, val draft: RoomNoteDraft, val json: String) {
    val fingerprint: String get() = NoteFingerprint.of(draft)

    fun staged(ticket: Long) = StagedNote(city, noteId, ticket, fingerprint, json)

    companion object {
        const val MAX_ITEMS = 20_000

        /** `[[city, note], …]` with each note in its localStorage shape; null when anything is unusable. */
        fun parseList(json: String): List<RestoreNote>? {
            val array = try {
                JSONArray(json)
            } catch (_: Exception) {
                return null
            }
            if (array.length() !in 1..MAX_ITEMS) return null
            val notes = LinkedHashMap<String, RestoreNote>()
            for (i in 0 until array.length()) {
                val pair = array.optJSONArray(i) ?: return null
                val city = pair.opt(0) as? String ?: return null
                val note = pair.opt(1) as? JSONObject ?: return null
                val id = RoomNoteKeys.idFromJson(note.opt("id")) ?: return null
                if (city.isBlank() || city.length > 120 || pair.length() != 2) return null
                notes.putIfAbsent(RoomNoteKeys.key(city, id), RestoreNote(city, id, RoomNoteDraft.fromJson(note), note.toString()))
            }
            return notes.values.toList()
        }
    }
}

/** Counts only: safe for Logcat and the WebView console. */
data class RestoreOutcome(
    val added: Int,
    val revived: Int,
    val replaced: Int,
    val unchanged: Int,
    /** Rows written after the restore was requested (e.g. deleted again); left as they are. */
    val stale: Int,
) {
    fun logLine() = "added=$added revived=$revived replaced=$replaced unchanged=$unchanged stale=$stale"

    fun toJson(): JSONObject = JSONObject().put("added", added).put("revived", revived).put("replaced", replaced)
        .put("unchanged", unchanged).put("stale", stale)
}

enum class RestoreChange { ADDED, REVIVED, REPLACED, UNCHANGED }

/** True when [record] is an open restore record for exactly the note localStorage holds now. */
fun provesRestore(record: NoteWriteJournalEntry?, local: LegacyNote): Boolean =
    record != null && record.op == NoteWriteJournalEntry.RESTORE && record.unresolved &&
        record.city == local.city && record.fingerprint == local.fingerprint

/**
 * Brings one Room row to the stored note under its restore record's [ticket]. The caller holds the
 * sequencer's mutex inside a transaction and has checked the record ([provesRestore]) and that [row]
 * is older than [ticket]. A replaced row (also a deletion record) is backed up first, so the
 * data-safety history can undo it. Throws when Room did not apply it, rolling the transaction back.
 */
internal suspend fun restoreNote(
    sequencer: RoomNoteWriteSequencer,
    insertBackup: suspend (NoteResolutionBackup) -> Long,
    key: String,
    local: LegacyNote,
    row: NoteEntity?,
    ticket: Long,
    changedAt: Long,
    now: Long,
): RestoreChange {
    val change = when {
        row == null -> RestoreChange.ADDED
        row.deletedAt != null -> RestoreChange.REVIVED
        local.draft.differingFields(row).isNotEmpty() -> RestoreChange.REPLACED
        else -> RestoreChange.UNCHANGED
    }
    val kind = when (change) {
        RestoreChange.REVIVED -> ReconcileKind.TOMBSTONE_CONFLICT
        RestoreChange.REPLACED -> ReconcileKind.CONTENT_MISMATCH
        else -> null
    }
    if (kind != null) {
        insertBackup(
            NoteResolutionBackup(
                noteKey = key,
                city = local.city,
                action = ResolutionAction.SYNC_FROM_LOCAL.name,
                kind = kind.name,
                beforeJson = NoteRowJson.encode(row!!),
                afterTicket = ticket,
                createdAt = now,
            )
        )
    }
    if (sequencer.applyRestore(local.city, key, local.draft, ticket, changedAt) != RoomWriteAck.Applied) {
        throw RoomBridgeFailure("Restore was not applied")
    }
    return change
}

/**
 * Room side of a JSON restore the user confirmed, run after the page stored the notes in localStorage.
 * Each note must have the RESTORE journal record taken before the page stored it, and localStorage must
 * hold exactly the note that record describes; otherwise nothing is written. For those notes Room is
 * brought to the stored copy (see [restoreNote]) and the records are resolved in the same transaction,
 * so a crash leaves either the finished restore or open records the next start can finish.
 * A row written after the restore was requested (a larger ticket, e.g. deleted again) is left alone.
 */
class RoomNoteRestorer(
    private val sequencer: RoomNoteWriteSequencer,
    private val store: NoteResolutionStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Caller holds the sequencer's mutex inside one transaction, so all notes are written or none. */
    suspend fun restore(legacyJson: String, items: List<RestoreItem>): RestoreOutcome {
        val legacy = LegacyNotes.parse(legacyJson)
        val counts = IntArray(RestoreChange.entries.size)
        var stale = 0
        for (item in items) {
            val key = item.key
            val local = legacy.notes[key] ?: throw RoomBridgeFailure("Restored note is not in local storage")
            val row = store.row(key)
            val now = clock()
            if (row != null && row.writeTicket >= item.ticket) {
                stale++
                store.resolvePendingJournal(item.ticket, NoteWriteJournalEntry.IGNORED, now)
                continue
            }
            val record = store.journalFor(key).firstOrNull { it.ticket == item.ticket }
            if (!provesRestore(record, local)) throw RoomBridgeFailure("Restore record does not match local storage")
            counts[restoreNote(sequencer, store::insertBackup, key, local, row, item.ticket, now, now).ordinal]++
            store.resolvePendingJournal(item.ticket, NoteWriteJournalEntry.APPLIED, now)
        }
        return RestoreOutcome(
            added = counts[RestoreChange.ADDED.ordinal],
            revived = counts[RestoreChange.REVIVED.ordinal],
            replaced = counts[RestoreChange.REPLACED.ordinal],
            unchanged = counts[RestoreChange.UNCHANGED.ordinal],
            stale = stale,
        )
    }
}

/** One confirmed restore, atomic with respect to bridge writes; a crash mid-way leaves Room unchanged. */
suspend fun CityNotesDatabase.restoreFromLocal(
    sequencer: RoomNoteWriteSequencer,
    legacyJson: String,
    items: List<RestoreItem>,
    clock: () -> Long = { System.currentTimeMillis() },
): RestoreOutcome = sequencer.mutex.withLock {
    withTransaction { RoomNoteRestorer(sequencer, RoomNoteResolutionStore(this), clock).restore(legacyJson, items) }
}
