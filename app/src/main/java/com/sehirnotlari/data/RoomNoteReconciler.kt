package com.sehirnotlari.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class ReconcileKind {
    /** In localStorage, no Room row. */
    MISSING_IN_ROOM,
    /** Both sides have the note with different field values. */
    CONTENT_MISMATCH,
    /** In localStorage, deleted in Room. Never revived automatically. */
    TOMBSTONE_CONFLICT,
    /** Active in Room, absent from localStorage. */
    ONLY_IN_ROOM,
    /** Same fields, but the Room row predates schema v2 or came from the legacy import. */
    UNKNOWN_ORIGIN,
    /** Journaled writes newer than localStorage that reached neither store; their text is not recoverable. */
    UNMATCHED_PENDING_WRITE,
}

enum class ReconcileAction { REPAIRED, REPAIRABLE, REPORTED }

data class ReconcileItem(
    val key: String,
    val kind: ReconcileKind,
    val action: ReconcileAction,
    val fields: List<String> = emptyList(),
    /** Journal shows localStorage holds an older version than Room (e.g. a localStorage write lost in a crash). */
    val roomNewer: Boolean = false,
    val unknownOrigin: Boolean = false,
    /** Journaled writes after the localStorage state that neither store kept (marked LOST). */
    val lostWrites: Int = 0,
)

data class ReconcileReport(
    val applied: Boolean,
    val legacyNotes: Int,
    val skippedLegacy: Int,
    val roomActive: Int,
    val roomTombstones: Int,
    val consistent: Int,
    val superseded: Int,
    /** Notes written after the localStorage copy was taken; compared at the next start instead. */
    val changedSince: Int,
    val pruned: Int,
    val items: List<ReconcileItem>,
) {
    fun count(kind: ReconcileKind) = items.count { it.kind == kind }

    /** Counts only: safe for Logcat and the WebView console. */
    fun summary(): Map<String, Any> = linkedMapOf(
        "applied" to applied,
        "legacy" to legacyNotes,
        "skippedLegacy" to skippedLegacy,
        "roomActive" to roomActive,
        "roomTombstones" to roomTombstones,
        "consistent" to consistent,
        "repaired" to items.count { it.action == ReconcileAction.REPAIRED },
        "repairable" to items.count { it.action == ReconcileAction.REPAIRABLE },
        "missingInRoom" to count(ReconcileKind.MISSING_IN_ROOM),
        "contentMismatch" to count(ReconcileKind.CONTENT_MISMATCH),
        "roomNewer" to items.count { it.roomNewer },
        "tombstoneConflict" to count(ReconcileKind.TOMBSTONE_CONFLICT),
        "onlyInRoom" to count(ReconcileKind.ONLY_IN_ROOM),
        "unknownOrigin" to items.count { it.unknownOrigin },
        "unmatchedPending" to count(ReconcileKind.UNMATCHED_PENDING_WRITE),
        "lostWrites" to items.sumOf { it.lostWrites },
        "superseded" to superseded,
        "changedSince" to changedSince,
        "pruned" to pruned,
    )

    fun logLine(): String = summary().entries.joinToString(" ") { "${it.key}=${it.value}" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("summary", JSONObject(summary()))
        put("items", JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().apply {
                    put("key", item.key)
                    put("kind", item.kind.name)
                    put("action", item.action.name)
                    put("fields", JSONArray(item.fields))
                    put("roomNewer", item.roomNewer)
                    put("unknownOrigin", item.unknownOrigin)
                    put("lostWrites", item.lostWrites)
                })
            }
        })
    }
}

interface NoteReconcileStore {
    suspend fun allNotes(): List<NoteEntity>
    suspend fun journal(): List<NoteWriteJournalEntry>
    suspend fun resolveJournal(ticket: Long, state: String, time: Long, previousJson: String?)
    suspend fun pruneJournal(before: Long): Int
}

class RoomNoteReconcileStore(private val db: CityNotesDatabase) : NoteReconcileStore {
    override suspend fun allNotes() = db.notes().allRows()
    override suspend fun journal() = db.journal().all()
    override suspend fun resolveJournal(ticket: Long, state: String, time: Long, previousJson: String?) =
        db.journal().resolve(ticket, state, time, previousJson)
    override suspend fun pruneJournal(before: Long) = db.journal().pruneResolved(before)
}

/**
 * Compares localStorage (authoritative, never modified here) with Room and repairs Room only
 * where the write journal proves a write was cut off: an unapplied entry newer than the Room row
 * requested exactly what localStorage holds now, so the Room row is provably older. Repairs reuse
 * that entry's ticket, so any later write still wins, and keep the replaced row in the journal.
 * Everything else is reported. Tombstones are never revived; rows are never deleted.
 */
class RoomNoteReconciler(
    private val sequencer: RoomNoteWriteSequencer,
    private val store: NoteReconcileStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Caller holds the sequencer's mutex inside one database transaction (see [reconcileWithLegacy]).
     * [cutoff] is a ticket taken when the localStorage copy was made; later writes are not compared.
     */
    suspend fun run(legacyJson: String, apply: Boolean, cutoff: Long = Long.MAX_VALUE): ReconcileReport {
        val legacy = LegacyNotes.parse(legacyJson)
        val allRows = store.allNotes()
        val rows = allRows.filter { it.writeTicket < cutoff }.associateBy { it.id }
        val changedSince = allRows.filter { it.writeTicket >= cutoff }.map { it.id }.toSet()
        val journal = store.journal().filter { it.ticket < cutoff }.groupBy { it.noteKey }
        val now = clock()
        val keys = sortedSetOf<String>().apply {
            addAll(legacy.notes.keys)
            addAll(rows.keys)
            journal.forEach { (key, entries) -> if (entries.any { it.unresolved }) add(key) }
            removeAll(changedSince)
        }
        val items = mutableListOf<ReconcileItem>()
        var consistent = 0
        var superseded = 0
        val fixed = if (apply) ReconcileAction.REPAIRED else ReconcileAction.REPAIRABLE

        suspend fun resolve(entries: List<NoteWriteJournalEntry>, state: String) {
            if (apply) entries.forEach { store.resolveJournal(it.ticket, state, now, null) }
        }

        // In a dry run a proven repair is only reported as REPAIRABLE.
        suspend fun repairUpsert(city: String, key: String, local: LegacyNote, entry: NoteWriteJournalEntry) =
            !apply || sequencer.applyUpsert(city, key, local.draft, entry.ticket, entry.createdAt) == RoomWriteAck.Applied

        suspend fun repairDelete(city: String, key: String, entry: NoteWriteJournalEntry) =
            !apply || sequencer.applyDelete(city, key, entry.ticket, entry.createdAt) == RoomWriteAck.Applied

        for (key in keys) {
            val local = legacy.notes[key]
            val row = rows[key]
            val entries = journal[key].orEmpty()
            val (stale, live) = entries.filter { it.unresolved }
                .partition { row != null && it.ticket <= row.writeTicket }
            resolve(stale, NoteWriteJournalEntry.SUPERSEDED)
            superseded += stale.size
            val city = local?.city ?: row?.city ?: live.firstOrNull()?.city ?: continue
            // The newest unapplied write that localStorage shows was made (localStorage may have lost later ones).
            val localWrite = local?.let { note ->
                live.filter { it.op == NoteWriteJournalEntry.UPSERT && it.fingerprint == note.fingerprint }.maxByOrNull { it.ticket }
            }
            val deleteWrite = live.filter { it.op == NoteWriteJournalEntry.DELETE }.maxByOrNull { it.ticket }

            /**
             * [anchor] explains the current localStorage state: older unapplied writes are done,
             * newer ones reached neither store and are marked lost. Returns the lost count.
             */
            suspend fun settle(anchor: NoteWriteJournalEntry?, replaced: NoteEntity?, repaired: Boolean): Int {
                val rest = live.filter { it !== anchor }
                val (older, newer) = rest.partition { anchor != null && it.ticket < anchor.ticket }
                if (repaired) {
                    if (apply) store.resolveJournal(anchor!!.ticket, NoteWriteJournalEntry.REPAIRED, now, replaced?.let(NoteRowJson::encode))
                } else if (anchor != null) {
                    resolve(listOf(anchor), NoteWriteJournalEntry.SUPERSEDED)
                    superseded++
                }
                resolve(older, NoteWriteJournalEntry.SUPERSEDED)
                superseded += older.size
                resolve(newer, NoteWriteJournalEntry.LOST)
                return newer.size
            }

            when {
                local != null && row == null -> {
                    if (localWrite != null && repairUpsert(city, key, local, localWrite)) {
                        val lost = settle(localWrite, null, repaired = true)
                        items += ReconcileItem(key, ReconcileKind.MISSING_IN_ROOM, fixed, lostWrites = lost)
                    } else {
                        items += ReconcileItem(key, ReconcileKind.MISSING_IN_ROOM, ReconcileAction.REPORTED)
                    }
                }
                local != null && row!!.deletedAt != null ->
                    items += ReconcileItem(key, ReconcileKind.TOMBSTONE_CONFLICT, ReconcileAction.REPORTED)
                local != null -> {
                    val active = row!!
                    val fields = local.draft.differingFields(active)
                    val unknownOrigin = active.modifiedAt == null || active.isAI == null
                    if (fields.isEmpty()) {
                        if (unknownOrigin) {
                            items += ReconcileItem(key, ReconcileKind.UNKNOWN_ORIGIN, ReconcileAction.REPORTED, unknownOrigin = true)
                        } else {
                            consistent++
                        }
                        if (live.isNotEmpty()) {
                            val lost = settle(localWrite, null, repaired = false)
                            if (lost > 0) items += ReconcileItem(key, ReconcileKind.UNMATCHED_PENDING_WRITE, ReconcileAction.REPORTED, lostWrites = lost)
                        }
                    } else if (localWrite != null && repairUpsert(city, key, local, localWrite)) {
                        val lost = settle(localWrite, active, repaired = true)
                        items += ReconcileItem(key, ReconcileKind.CONTENT_MISMATCH, fixed, fields, unknownOrigin = unknownOrigin, lostWrites = lost)
                    } else {
                        val roomNewer = entries.any { it.ticket < active.writeTicket && it.fingerprint == local.fingerprint }
                        items += ReconcileItem(key, ReconcileKind.CONTENT_MISMATCH, ReconcileAction.REPORTED, fields, roomNewer, unknownOrigin)
                    }
                }
                row != null && row.deletedAt == null -> {
                    if (deleteWrite != null && repairDelete(city, key, deleteWrite)) {
                        val lost = settle(deleteWrite, row, repaired = true)
                        items += ReconcileItem(key, ReconcileKind.ONLY_IN_ROOM, fixed, lostWrites = lost)
                    } else {
                        val unknownOrigin = row.modifiedAt == null || row.isAI == null
                        items += ReconcileItem(key, ReconcileKind.ONLY_IN_ROOM, ReconcileAction.REPORTED, unknownOrigin = unknownOrigin)
                    }
                }
                // Deleted (or never written) on both sides: a deletion has nothing left to do; later writes were lost.
                live.isNotEmpty() -> {
                    val lost = settle(deleteWrite, null, repaired = false)
                    if (lost > 0) items += ReconcileItem(key, ReconcileKind.UNMATCHED_PENDING_WRITE, ReconcileAction.REPORTED, lostWrites = lost)
                }
            }
        }

        val pruned = if (apply) store.pruneJournal(now - JOURNAL_RETENTION_MS) else 0
        return ReconcileReport(
            applied = apply,
            legacyNotes = legacy.notes.size,
            skippedLegacy = legacy.skipped,
            roomActive = rows.values.count { it.deletedAt == null },
            roomTombstones = rows.values.count { it.deletedAt != null },
            consistent = consistent,
            superseded = superseded,
            changedSince = changedSince.size,
            pruned = pruned,
            items = items,
        )
    }

    companion object {
        const val JOURNAL_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** Runs one reconciliation atomically with respect to bridge writes; a crash mid-way rolls back. */
suspend fun CityNotesDatabase.reconcileWithLegacy(
    sequencer: RoomNoteWriteSequencer,
    legacyJson: String,
    apply: Boolean,
    cutoff: Long = Long.MAX_VALUE,
    clock: () -> Long = { System.currentTimeMillis() },
): ReconcileReport = sequencer.mutex.withLock {
    withTransaction {
        RoomNoteReconciler(sequencer, RoomNoteReconcileStore(this), clock).run(legacyJson, apply, cutoff)
    }
}
