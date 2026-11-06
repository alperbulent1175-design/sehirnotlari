package com.sehirnotlari.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Written in the same transaction as a user-approved change to a Room row, before the change.
 * [beforeJson] is the exact previous row ([NoteRowJson]); null when Room had no row.
 * Never pruned automatically.
 */
@Entity(tableName = "note_resolution_backup", indices = [Index("noteKey")])
data class NoteResolutionBackup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteKey: String,
    val city: String,
    val action: String,
    /** [ReconcileKind] name of the difference the user resolved. */
    val kind: String,
    val beforeJson: String?,
    /** Ticket of the row written by the action; undo is allowed only while the row still has it. */
    val afterTicket: Long,
    val createdAt: Long,
    val undoneAt: Long? = null,
)
