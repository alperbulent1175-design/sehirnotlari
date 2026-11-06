package com.sehirnotlari.data

import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class RoomFirstFlag(val enabled: Boolean = false, val backupId: String? = null, val changedAt: Long = 0L)

interface RoomFirstFlagStore {
    fun read(): RoomFirstFlag

    /** Must be durable when it returns; throws otherwise. */
    fun write(flag: RoomFirstFlag)
}

class PrefsRoomFirstFlagStore(private val prefs: SharedPreferences) : RoomFirstFlagStore {
    override fun read() = RoomFirstFlag(
        enabled = prefs.getBoolean(ENABLED, false),
        backupId = prefs.getString(BACKUP, null),
        changedAt = prefs.getLong(CHANGED, 0L),
    )

    // commit(), not apply(): the switch is only reported after the file write finished.
    override fun write(flag: RoomFirstFlag) {
        val ok = prefs.edit()
            .putBoolean(ENABLED, flag.enabled)
            .putString(BACKUP, flag.backupId)
            .putLong(CHANGED, flag.changedAt)
            .commit()
        if (!ok) throw RoomBridgeFailure("Mode could not be stored")
    }

    companion object {
        const val FILE = "room_first_prototype"
        private const val ENABLED = "enabled"
        private const val BACKUP = "backupId"
        private const val CHANGED = "changedAt"
    }
}

/**
 * Pre-switch audit: localStorage and Room must agree on every note before either becomes the
 * source. Any difference, unknown origin or unfinished journaled write blocks the switch; the
 * user resolves it first (P7 screen or the app itself). Counts only.
 */
data class RoomFirstPreflight(val report: ReconcileReport, val unresolvedJournal: Int) {
    val blockers: Map<String, Int> = linkedMapOf(
        "skippedLegacy" to report.skippedLegacy,
        "missingInRoom" to report.count(ReconcileKind.MISSING_IN_ROOM),
        "contentMismatch" to report.count(ReconcileKind.CONTENT_MISMATCH),
        "roomNewer" to report.items.count { it.roomNewer },
        "tombstoneConflict" to report.count(ReconcileKind.TOMBSTONE_CONFLICT),
        "onlyInRoom" to report.count(ReconcileKind.ONLY_IN_ROOM),
        "unknownOrigin" to report.items.count { it.unknownOrigin },
        "unmatchedPending" to report.count(ReconcileKind.UNMATCHED_PENDING_WRITE),
        "repairable" to report.items.count { it.action == ReconcileAction.REPAIRABLE },
        "unresolvedJournal" to unresolvedJournal,
        "changedSince" to report.changedSince,
    )

    val clean: Boolean get() = blockers.values.all { it == 0 }

    fun logLine() = "clean=$clean legacy=${report.legacyNotes} roomActive=${report.roomActive} " +
        blockers.entries.filter { it.value > 0 }.joinToString(" ") { "${it.key}=${it.value}" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("clean", clean)
        put("legacy", report.legacyNotes)
        put("roomActive", report.roomActive)
        put("roomTombstones", report.roomTombstones)
        put("blockers", JSONObject(blockers as Map<*, *>))
    }
}

data class RoomFirstSwitchOutcome(
    val code: String,
    val preflight: RoomFirstPreflight? = null,
    val backup: RoomFirstBackupInfo? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("preflight", preflight?.toJson() ?: JSONObject.NULL)
        put("backup", backup?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        const val ENABLED = "ENABLED"
        const val DISABLED = "DISABLED"
        const val ALREADY_ON = "ALREADY_ON"
        const val ALREADY_OFF = "ALREADY_OFF"
        const val REFUSED = "REFUSED"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val CHECKED = "CHECKED"
    }
}

/**
 * Debug-only switch between localStorage (production) and Room as the note source.
 * Switching on: audit clean → verified backup → durable flag. Switching off: the page first
 * rewrites localStorage from Room; the audit must then be clean → flag cleared. Each step
 * runs under the sequencer's mutex, so no write lands between the audit and the flag. A crash
 * at any point leaves the previous mode in force; retrying is safe. Nothing is deleted.
 */
class RoomFirstSwitch(
    private val db: CityNotesDatabase,
    private val sequencer: RoomNoteWriteSequencer,
    private val flags: RoomFirstFlagStore,
    private val backups: RoomFirstBackups,
    /** False in release builds: the mode can then neither be read as on nor switched on. */
    val available: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun isOn(): Boolean = available && flags.read().enabled

    fun modeJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("on", isOn())
        put("backupId", flags.read().backupId ?: JSONObject.NULL)
    }

    suspend fun preflight(localStorage: String): RoomFirstSwitchOutcome = sequencer.mutex.withLock {
        RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.CHECKED, audit(localStorage))
    }

    suspend fun enable(localStorage: String): RoomFirstSwitchOutcome = sequencer.mutex.withLock {
        val flag = flags.read()
        when {
            !available -> RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.UNAVAILABLE)
            flag.enabled -> RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.ALREADY_ON, backup = flag.backupId?.let(backups::verify))
            else -> {
                val audit = audit(localStorage)
                if (!audit.clean) return@withLock RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.REFUSED, audit)
                val backup = backups.create(localStorage, db.notes().allRows())
                flags.write(RoomFirstFlag(enabled = true, backupId = backup.id, changedAt = clock()))
                RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.ENABLED, audit, backup)
            }
        }
    }

    /** [localStorage] is the copy the page has just written from Room's committed state. */
    suspend fun disable(localStorage: String): RoomFirstSwitchOutcome = sequencer.mutex.withLock {
        val flag = flags.read()
        when {
            !available -> RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.UNAVAILABLE)
            !flag.enabled -> RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.ALREADY_OFF)
            else -> {
                val audit = audit(localStorage)
                if (!audit.clean) return@withLock RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.REFUSED, audit)
                flags.write(flag.copy(enabled = false, changedAt = clock()))
                RoomFirstSwitchOutcome(RoomFirstSwitchOutcome.DISABLED, audit)
            }
        }
    }

    /** Mode plus every backup with its verification result (a tampered file reports ok=false). */
    fun status(): JSONObject = modeJson().apply {
        put("backups", JSONArray().apply {
            backups.ids().forEach { id ->
                val info = backups.verify(id)
                put((info?.toJson() ?: JSONObject().put("id", id)).put("ok", info != null))
            }
        })
    }

    /** Caller holds the mutex. Read-only: the reconciler runs in preview mode. */
    private suspend fun audit(localStorage: String): RoomFirstPreflight = db.withTransaction {
        val report = RoomNoteReconciler(sequencer, RoomNoteReconcileStore(db), clock).run(localStorage, apply = false)
        RoomFirstPreflight(report, db.journal().all().count { it.unresolved })
    }
}
