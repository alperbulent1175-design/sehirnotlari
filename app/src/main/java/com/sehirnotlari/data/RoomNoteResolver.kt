package com.sehirnotlari.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** User-approved changes; both touch Room only. localStorage is never written from here. */
enum class ResolutionAction {
    /** Make the Room row equal to the localStorage note (insert, update or confirm origin). */
    SYNC_FROM_LOCAL,
    /** Mark a Room-only note deleted. The row keeps its fields as a tombstone. */
    MARK_DELETED_IN_ROOM,
}

/** [reason] is a code; the WebView words it. */
data class ResolutionOption(
    val action: ResolutionAction,
    val enabled: Boolean,
    val needsConfirmation: Boolean,
    val reason: String,
) {
    companion object {
        const val ADDS_MISSING_ROW = "ADDS_MISSING_ROW"
        const val OVERWRITES_ROOM_ROW = "OVERWRITES_ROOM_ROW"
        const val ROOM_MAY_BE_NEWER = "ROOM_MAY_BE_NEWER"
        const val ROOM_ORIGIN_UNKNOWN = "ROOM_ORIGIN_UNKNOWN"
        const val CONFIRMS_ORIGIN = "CONFIRMS_ORIGIN"
        const val TOMBSTONE_NOT_REVIVED = "TOMBSTONE_NOT_REVIVED"
        const val TOMBSTONES_ROOM_ROW = "TOMBSTONES_ROOM_ROW"
    }
}

/** The current difference for one note, recomputed from both stores whenever it is asked for. */
data class ResolutionPlan(
    val key: String,
    val city: String?,
    /** Null when the stores agree, or neither has an active note. */
    val kind: ReconcileKind?,
    val fields: List<String>,
    val roomNewer: Boolean,
    val unknownOrigin: Boolean,
    /** Identifies both sides' state; an approval is applied only while it still matches. */
    val token: String,
    val local: RoomNoteDraft?,
    val room: NoteEntity?,
    val options: List<ResolutionOption>,
) {
    /** For the WebView only (shows both versions to the user); never logged. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("city", city ?: JSONObject.NULL)
        put("kind", kind?.name ?: JSONObject.NULL)
        put("fields", JSONArray(fields))
        put("roomNewer", roomNewer)
        put("unknownOrigin", unknownOrigin)
        put("token", token)
        put("local", local?.let { d ->
            JSONObject().put("title", d.title).put("content", d.content).put("category", d.category)
                .put("star", d.star).put("color", d.color).put("fav", d.favorite).put("isAI", d.isAI)
                .put("date", d.dateLabel)
        } ?: JSONObject.NULL)
        put("room", room?.let { r ->
            JSONObject().put("title", r.title).put("content", r.content).put("category", r.category)
                .put("star", r.star).put("color", r.color).put("fav", r.favorite)
                .put("isAI", r.isAI ?: JSONObject.NULL).put("date", r.dateLabel)
                .put("deleted", r.deletedAt != null).put("modifiedAt", r.modifiedAt ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        put("options", JSONArray().apply {
            options.forEach { o ->
                put(JSONObject().put("action", o.action.name).put("enabled", o.enabled)
                    .put("needsConfirmation", o.needsConfirmation).put("reason", o.reason))
            }
        })
    }
}

data class ResolutionOutcome(
    val code: String,
    val backupId: Long? = null,
    val undoable: Boolean = false,
    val plan: ResolutionPlan? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("backupId", backupId ?: JSONObject.NULL)
        put("undoable", undoable)
        put("plan", plan?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        const val APPLIED = "APPLIED"
        const val ALREADY_CONSISTENT = "ALREADY_CONSISTENT"
        const val STALE = "STALE"
        const val NOT_ALLOWED = "NOT_ALLOWED"
        const val CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
        const val UNDONE = "UNDONE"
        const val ALREADY_UNDONE = "ALREADY_UNDONE"
        const val UNDO_BLOCKED = "UNDO_BLOCKED"
        const val NOTHING_TO_UNDO = "NOTHING_TO_UNDO"
        const val NOT_FOUND = "NOT_FOUND"
    }
}

data class SafetyBackupView(val backup: NoteResolutionBackup, val title: String, val undoable: Boolean)

/** What the data-safety screen shows beyond the startup report; survives restarts. */
data class DataSafetyStatus(
    val backups: List<SafetyBackupView>,
    /** Startup repairs resolved within the journal retention window. */
    val repairedKeys: List<String>,
    val lastRepairAt: Long?,
    val lostWrites: Int,
    val lostKeys: List<String>,
    /** PENDING or FAILED journal entries (writes in flight, or cut off and not yet explained). */
    val unresolvedWrites: Int,
) {
    fun logLine() = "backups=${backups.size} undoable=${backups.count { it.undoable }} repaired=${repairedKeys.size} " +
        "lostWrites=$lostWrites unresolved=$unresolvedWrites"

    /** For the WebView only: backup titles are shown to the user, never logged. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("backups", JSONArray().apply {
            backups.forEach { v ->
                put(JSONObject().put("id", v.backup.id).put("key", v.backup.noteKey).put("city", v.backup.city)
                    .put("action", v.backup.action).put("kind", v.backup.kind).put("createdAt", v.backup.createdAt)
                    .put("undoneAt", v.backup.undoneAt ?: JSONObject.NULL).put("hadRow", v.backup.beforeJson != null)
                    .put("undoable", v.undoable).put("title", v.title))
            }
        })
        put("repaired", JSONObject().put("count", repairedKeys.size).put("keys", JSONArray(repairedKeys))
            .put("last", lastRepairAt ?: JSONObject.NULL))
        put("lost", JSONObject().put("count", lostWrites).put("keys", JSONArray(lostKeys)))
        put("unresolvedWrites", unresolvedWrites)
        put("retentionDays", RoomNoteReconciler.JOURNAL_RETENTION_MS / (24L * 60 * 60 * 1000))
    }
}

interface NoteResolutionStore {
    suspend fun row(key: String): NoteEntity?
    suspend fun journalFor(key: String): List<NoteWriteJournalEntry>
    suspend fun journal(): List<NoteWriteJournalEntry>
    suspend fun insertBackup(backup: NoteResolutionBackup): Long
    suspend fun backup(id: Long): NoteResolutionBackup?
    suspend fun recentBackups(limit: Int): List<NoteResolutionBackup>
    suspend fun markUndone(id: Long, time: Long): Int
    suspend fun restoreRow(note: NoteEntity)
    suspend fun resolvePendingJournal(ticket: Long, state: String, time: Long)
}

class RoomNoteResolutionStore(private val db: CityNotesDatabase) : NoteResolutionStore {
    override suspend fun row(key: String) = db.notes().findAny(key)
    override suspend fun journalFor(key: String) = db.journal().forNote(key)
    override suspend fun journal() = db.journal().all()
    override suspend fun insertBackup(backup: NoteResolutionBackup) = db.resolutions().insert(backup)
    override suspend fun backup(id: Long) = db.resolutions().find(id)
    override suspend fun recentBackups(limit: Int) = db.resolutions().recent(limit)
    override suspend fun markUndone(id: Long, time: Long) = db.resolutions().markUndone(id, time)
    override suspend fun restoreRow(note: NoteEntity) = db.notes().upsert(note)
    override suspend fun resolvePendingJournal(ticket: Long, state: String, time: Long) =
        db.journal().resolvePending(ticket, state, time)
}

/**
 * Applies differences the user approved on the data-safety screen, one note at a time.
 * Room is changed only after a backup of the previous row is written in the same transaction,
 * and only while both stores are still in the state the user saw. Tombstones are never revived
 * by an approval; an undo restores the exact previous row while nothing changed it since.
 */
class RoomNoteResolver(
    private val sequencer: RoomNoteWriteSequencer,
    private val store: NoteResolutionStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun inspect(legacyJson: String, key: String): ResolutionPlan = planFor(LegacyNotes.parse(legacyJson), key)

    /** Caller holds the sequencer's mutex inside one transaction (see [applyResolution]). */
    suspend fun apply(
        legacyJson: String,
        key: String,
        action: ResolutionAction,
        token: String,
        confirmed: Boolean,
    ): ResolutionOutcome {
        val legacy = LegacyNotes.parse(legacyJson)
        val current = planFor(legacy, key)
        val kind = current.kind ?: return ResolutionOutcome(ResolutionOutcome.ALREADY_CONSISTENT, plan = current)
        if (current.token != token) return ResolutionOutcome(ResolutionOutcome.STALE, plan = current)
        val option = current.options.firstOrNull { it.action == action && it.enabled }
            ?: return ResolutionOutcome(ResolutionOutcome.NOT_ALLOWED, plan = current)
        if (option.needsConfirmation && !confirmed) {
            return ResolutionOutcome(ResolutionOutcome.CONFIRMATION_REQUIRED, plan = current)
        }
        val now = clock()
        val ticket = sequencer.nextTicket()
        val before = current.room
        val backupId = store.insertBackup(
            NoteResolutionBackup(
                noteKey = key,
                city = current.city!!,
                action = action.name,
                kind = kind.name,
                beforeJson = before?.let(NoteRowJson::encode),
                afterTicket = ticket,
                createdAt = now,
            )
        )
        val ack = when (action) {
            ResolutionAction.SYNC_FROM_LOCAL -> {
                val local = legacy.notes.getValue(key)
                sequencer.applyUpsert(local.city, key, local.draft, ticket, now)
            }
            ResolutionAction.MARK_DELETED_IN_ROOM -> sequencer.applyDelete(before!!.city, key, ticket, now)
        }
        // Rolls the backup back with the transaction.
        if (ack != RoomWriteAck.Applied) throw RoomBridgeFailure("Resolution was not applied")
        return ResolutionOutcome(ResolutionOutcome.APPLIED, backupId, undoable = before != null, plan = planFor(legacy, key))
    }

    suspend fun undo(backupId: Long): ResolutionOutcome {
        val backup = store.backup(backupId) ?: return ResolutionOutcome(ResolutionOutcome.NOT_FOUND)
        if (backup.undoneAt != null) return ResolutionOutcome(ResolutionOutcome.ALREADY_UNDONE, backupId)
        val beforeJson = backup.beforeJson ?: return ResolutionOutcome(ResolutionOutcome.NOTHING_TO_UNDO, backupId)
        val row = store.row(backup.noteKey)
        if (row == null || row.writeTicket != backup.afterTicket) {
            return ResolutionOutcome(ResolutionOutcome.UNDO_BLOCKED, backupId)
        }
        store.restoreRow(NoteRowJson.decode(beforeJson))
        store.markUndone(backupId, clock())
        return ResolutionOutcome(ResolutionOutcome.UNDONE, backupId)
    }

    suspend fun status(limit: Int = MAX_HISTORY): DataSafetyStatus {
        val since = clock() - RoomNoteReconciler.JOURNAL_RETENTION_MS
        val journal = store.journal()
        val repaired = journal.filter { it.state == NoteWriteJournalEntry.REPAIRED && (it.resolvedAt ?: 0L) >= since }
        val lost = journal.filter { it.state == NoteWriteJournalEntry.LOST && (it.resolvedAt ?: 0L) >= since }
        val backups = store.recentBackups(limit).map { backup ->
            val row = store.row(backup.noteKey)
            val title = row?.title?.takeIf { it.isNotEmpty() }
                ?: backup.beforeJson?.let { runCatching { NoteRowJson.decode(it).title }.getOrNull() }
                ?: ""
            val undoable = backup.undoneAt == null && backup.beforeJson != null && row?.writeTicket == backup.afterTicket
            SafetyBackupView(backup, title, undoable)
        }
        return DataSafetyStatus(
            backups = backups,
            repairedKeys = repaired.map { it.noteKey }.distinct(),
            lastRepairAt = repaired.mapNotNull { it.resolvedAt }.maxOrNull(),
            lostWrites = lost.size,
            lostKeys = lost.map { it.noteKey }.distinct(),
            unresolvedWrites = journal.count { it.unresolved },
        )
    }

    private suspend fun planFor(legacy: LegacyNotes, key: String): ResolutionPlan =
        plan(key, legacy.notes[key], store.row(key), store.journalFor(key))

    companion object {
        const val MAX_HISTORY = 50

        /** Decides the difference and which approvals are safe; mirrors [RoomNoteReconciler]'s classification. */
        fun plan(key: String, local: LegacyNote?, row: NoteEntity?, journal: List<NoteWriteJournalEntry>): ResolutionPlan {
            val token = "${row?.let { "${it.writeTicket}:${it.deletedAt ?: "-"}" } ?: "none"}|${local?.fingerprint ?: "none"}"
            val unknownOrigin = row != null && (row.modifiedAt == null || row.isAI == null)
            fun result(
                kind: ReconcileKind?,
                fields: List<String> = emptyList(),
                roomNewer: Boolean = false,
                options: List<ResolutionOption> = emptyList(),
            ) = ResolutionPlan(key, local?.city ?: row?.city, kind, fields, roomNewer, unknownOrigin, token,
                local?.draft, row, options)

            val sync = ResolutionAction.SYNC_FROM_LOCAL
            return when {
                local != null && row == null ->
                    result(ReconcileKind.MISSING_IN_ROOM,
                        options = listOf(ResolutionOption(sync, true, false, ResolutionOption.ADDS_MISSING_ROW)))
                local != null && row!!.deletedAt != null ->
                    result(ReconcileKind.TOMBSTONE_CONFLICT,
                        options = listOf(ResolutionOption(sync, false, false, ResolutionOption.TOMBSTONE_NOT_REVIVED)))
                local != null -> {
                    val fields = local.draft.differingFields(row!!)
                    when {
                        fields.isNotEmpty() -> {
                            val roomNewer = journal.any { it.ticket < row.writeTicket && it.fingerprint == local.fingerprint }
                            val reason = when {
                                roomNewer -> ResolutionOption.ROOM_MAY_BE_NEWER
                                unknownOrigin -> ResolutionOption.ROOM_ORIGIN_UNKNOWN
                                else -> ResolutionOption.OVERWRITES_ROOM_ROW
                            }
                            // Every overwrite needs an explicit approval: neither side is known to be newer.
                            result(ReconcileKind.CONTENT_MISMATCH, fields, roomNewer,
                                listOf(ResolutionOption(sync, true, true, reason)))
                        }
                        unknownOrigin ->
                            result(ReconcileKind.UNKNOWN_ORIGIN,
                                options = listOf(ResolutionOption(sync, true, false, ResolutionOption.CONFIRMS_ORIGIN)))
                        else -> result(null)
                    }
                }
                row != null && row.deletedAt == null ->
                    result(ReconcileKind.ONLY_IN_ROOM, options = listOf(
                        ResolutionOption(ResolutionAction.MARK_DELETED_IN_ROOM, true, true, ResolutionOption.TOMBSTONES_ROOM_ROW)))
                else -> result(null)
            }
        }
    }
}

private suspend fun <T> CityNotesDatabase.withResolver(
    sequencer: RoomNoteWriteSequencer,
    clock: () -> Long,
    block: suspend RoomNoteResolver.() -> T,
): T = sequencer.mutex.withLock {
    withTransaction { RoomNoteResolver(sequencer, RoomNoteResolutionStore(this), clock).block() }
}

suspend fun CityNotesDatabase.inspectNote(
    sequencer: RoomNoteWriteSequencer,
    legacyJson: String,
    key: String,
    clock: () -> Long = { System.currentTimeMillis() },
): ResolutionPlan = withResolver(sequencer, clock) { inspect(legacyJson, key) }

/** One approval, atomic with respect to bridge writes; a crash mid-way leaves neither backup nor change. */
suspend fun CityNotesDatabase.applyResolution(
    sequencer: RoomNoteWriteSequencer,
    legacyJson: String,
    key: String,
    action: ResolutionAction,
    token: String,
    confirmed: Boolean,
    clock: () -> Long = { System.currentTimeMillis() },
): ResolutionOutcome = withResolver(sequencer, clock) { apply(legacyJson, key, action, token, confirmed) }

suspend fun CityNotesDatabase.undoResolution(
    sequencer: RoomNoteWriteSequencer,
    backupId: Long,
    clock: () -> Long = { System.currentTimeMillis() },
): ResolutionOutcome = withResolver(sequencer, clock) { undo(backupId) }

suspend fun CityNotesDatabase.dataSafetyStatus(
    sequencer: RoomNoteWriteSequencer,
    clock: () -> Long = { System.currentTimeMillis() },
): DataSafetyStatus = withResolver(sequencer, clock) { status() }
