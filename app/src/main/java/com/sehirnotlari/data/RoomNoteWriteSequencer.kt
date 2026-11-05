package com.sehirnotlari.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface NoteWriteStore : NoteImportStore {
    suspend fun softDelete(id: String, time: Long)
}

class RoomNoteWriteStore(private val dao: NoteDao) : NoteWriteStore {
    override suspend fun findAny(id: String) = dao.findAny(id)
    override suspend fun upsert(note: NoteEntity) = dao.upsert(note)
    override suspend fun softDelete(id: String, time: Long) = dao.softDelete(id, time)
}

data class RoomNoteDraft(
    val title: String,
    val content: String = "",
    val category: String = "Genel",
    val star: Int = 0,
    val color: String = "",
    val favorite: Boolean = false,
    val dateLabel: String = "",
)

sealed class RoomWriteAck {
    data object Applied : RoomWriteAck()
    data object IgnoredStale : RoomWriteAck()
    data class Rejected(val message: String) : RoomWriteAck()
}

/**
 * Assign [nextTicket] on the JavascriptInterface thread before launching work.
 * Later completions with a lower ticket cannot overwrite a newer apply or
 * resurrect a tombstone. Note IDs stay `city::id`.
 */
class RoomNoteWriteSequencer(
    private val store: NoteWriteStore,
    private val mutex: Mutex = Mutex(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val tickets = AtomicLong(0)
    private val lastAppliedTicket = ConcurrentHashMap<String, Long>()

    fun nextTicket(): Long = tickets.incrementAndGet()

    suspend fun upsert(city: String, noteId: String, draft: RoomNoteDraft, ticket: Long): RoomWriteAck {
        val key = "$city::$noteId"
        return mutex.withLock {
            val existing = store.findAny(key)
            if (existing?.deletedAt != null) {
                return@withLock RoomWriteAck.Rejected("Deleted note cannot be restored by a stale write")
            }
            val last = lastAppliedTicket[key] ?: 0L
            if (ticket < last) {
                return@withLock RoomWriteAck.IgnoredStale
            }
            val now = clock()
            store.upsert(
                NoteEntity(
                    id = key,
                    city = city,
                    title = draft.title,
                    content = draft.content,
                    category = draft.category,
                    star = draft.star.coerceIn(0, 5),
                    color = draft.color,
                    favorite = draft.favorite,
                    dateLabel = draft.dateLabel,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    deletedAt = null,
                    syncState = existing?.syncState ?: "LOCAL"
                )
            )
            lastAppliedTicket[key] = ticket
            RoomWriteAck.Applied
        }
    }

    suspend fun delete(city: String, noteId: String, ticket: Long): RoomWriteAck {
        val key = "$city::$noteId"
        return mutex.withLock {
            val last = lastAppliedTicket[key] ?: 0L
            if (ticket < last) {
                return@withLock RoomWriteAck.IgnoredStale
            }
            val existing = store.findAny(key)
            val now = clock()
            when {
                existing == null -> store.upsert(
                    NoteEntity(
                        id = key,
                        city = city,
                        title = "",
                        deletedAt = now,
                        updatedAt = now,
                        syncState = "PENDING"
                    )
                )
                existing.deletedAt == null -> store.softDelete(key, now)
            }
            lastAppliedTicket[key] = ticket
            RoomWriteAck.Applied
        }
    }
}

fun RoomWriteAck.toBridgeSuccess(): Boolean = this !is RoomWriteAck.Rejected

fun RoomWriteAck.toBridgeError(): String? = (this as? RoomWriteAck.Rejected)?.message
