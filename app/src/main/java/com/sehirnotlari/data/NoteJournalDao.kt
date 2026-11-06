package com.sehirnotlari.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoteJournalDao {
    // Blocking on purpose: runs on the JavaBridge thread so the entry exists before the write is queued.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entry: NoteWriteJournalEntry)

    @Query("UPDATE note_write_journal SET state = :state, resolvedAt = :time WHERE ticket = :ticket AND state = 'PENDING'")
    suspend fun resolvePending(ticket: Long, state: String, time: Long)

    @Query("UPDATE note_write_journal SET state = :state, resolvedAt = :time, previousJson = :previousJson WHERE ticket = :ticket")
    suspend fun resolve(ticket: Long, state: String, time: Long, previousJson: String?)

    @Query("SELECT * FROM note_write_journal ORDER BY ticket")
    suspend fun all(): List<NoteWriteJournalEntry>

    @Query("SELECT * FROM note_write_journal WHERE ticket = :ticket LIMIT 1")
    suspend fun find(ticket: Long): NoteWriteJournalEntry?

    @Query("SELECT MAX(ticket) FROM note_write_journal")
    fun maxTicket(): Long?

    @Query("SELECT * FROM note_write_journal WHERE noteKey = :noteKey ORDER BY ticket")
    suspend fun forNote(noteKey: String): List<NoteWriteJournalEntry>

    /** Retention counts from resolution: a write cut off long ago and repaired now keeps its backup. */
    @Query("DELETE FROM note_write_journal WHERE state NOT IN ('PENDING', 'FAILED') AND COALESCE(resolvedAt, createdAt) < :before")
    suspend fun pruneResolved(before: Long): Int
}
