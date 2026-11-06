package com.sehirnotlari.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One Room shadow write requested by the WebView, recorded before the write is queued.
 * An entry left PENDING or FAILED after a restart proves the write never reached Room.
 * Note text is never stored here; [fingerprint] identifies the requested field values.
 */
@Entity(tableName = "note_write_journal", indices = [Index("noteKey")])
data class NoteWriteJournalEntry(
    @PrimaryKey val ticket: Long,
    val noteKey: String,
    val city: String,
    val op: String,
    /** [NoteFingerprint] of the requested note; null for deletions. */
    val fingerprint: String?,
    val state: String,
    /** Wall-clock time of the user's change. */
    val createdAt: Long,
    val resolvedAt: Long? = null,
    /** Room row replaced by a startup repair (JSON), kept so the repair can be undone. */
    val previousJson: String? = null,
) {
    val unresolved: Boolean get() = state == PENDING || state == FAILED

    companion object {
        const val UPSERT = "UPSERT"
        const val DELETE = "DELETE"
        /**
         * One note of a JSON restore the user confirmed, recorded before the page stores it. The only
         * entry that may revive a Room deletion record, and only while it matches localStorage.
         */
        const val RESTORE = "RESTORE"

        const val PENDING = "PENDING"
        const val FAILED = "FAILED"
        const val APPLIED = "APPLIED"
        const val IGNORED = "IGNORED"
        const val REJECTED = "REJECTED"
        const val REPAIRED = "REPAIRED"
        const val SUPERSEDED = "SUPERSEDED"
        /** Newer than the localStorage state after a crash and absent from Room: kept in neither store. */
        const val LOST = "LOST"
    }
}
