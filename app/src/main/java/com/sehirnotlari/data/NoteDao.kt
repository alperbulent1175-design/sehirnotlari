package com.sehirnotlari.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findAny(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY writeTicket DESC")
    suspend fun allActive(): List<NoteEntity>

    /** Active rows and tombstones. */
    @Query("SELECT * FROM notes")
    suspend fun allRows(): List<NoteEntity>

    // Blocking on purpose: seeds the write ticket clock from the JavaBridge thread, never main.
    @Query("SELECT MAX(writeTicket) FROM notes")
    fun maxWriteTicket(): Long?

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY writeTicket DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE city = :city AND deletedAt IS NULL ORDER BY writeTicket DESC")
    fun observeCity(city: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND (title LIKE '%' || :term || '%' OR content LIKE '%' || :term || '%') ORDER BY writeTicket DESC")
    suspend fun search(term: String): List<NoteEntity>

    // REPLACE is last-writer-wins. RoomNoteWriteSequencer must drop stale tickets first,
    // so other writers must not set writeTicket ahead of its tickets.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET deletedAt = :time, modifiedAt = :time, writeTicket = :ticket, syncState = 'PENDING' WHERE id = :id")
    suspend fun softDelete(id: String, ticket: Long, time: Long)

    @Query("SELECT * FROM notes WHERE syncState != 'SYNCED'")
    suspend fun pendingSync(): List<NoteEntity>
}
