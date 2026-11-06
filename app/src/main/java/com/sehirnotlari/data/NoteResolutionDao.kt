package com.sehirnotlari.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoteResolutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(backup: NoteResolutionBackup): Long

    @Query("SELECT * FROM note_resolution_backup WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): NoteResolutionBackup?

    @Query("SELECT * FROM note_resolution_backup ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NoteResolutionBackup>

    @Query("UPDATE note_resolution_backup SET undoneAt = :time WHERE id = :id AND undoneAt IS NULL")
    suspend fun markUndone(id: Long, time: Long): Int

    // Blocking on purpose: seeds the write ticket clock from the JavaBridge thread, never main.
    @Query("SELECT MAX(afterTicket) FROM note_resolution_backup")
    fun maxAfterTicket(): Long?
}
