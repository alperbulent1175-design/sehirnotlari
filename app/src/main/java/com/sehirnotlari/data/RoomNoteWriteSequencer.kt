package com.sehirnotlari.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Orders Room shadow writes per note across activity recreation and process restarts.
 *
 * A ticket is a hybrid clock: `max(last + 1, wall clock)`, seeded once from the largest
 * persisted `updatedAt`. Applied writes store their ticket in `updatedAt`, so the ordering
 * survives restarts without a schema change. Assign [nextTicket] on the bridge thread
 * before launching work. Tombstones are never revived. Note IDs stay `city::id`.
 */
class RoomNoteWriteSequencer(
    private val store: NoteWriteStore,
    val mutex: Mutex = Mutex(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val seed: () -> Long = { 0L },
) {
    private val ticketLock = Any()
    private var lastTicket = 0L
    private var seeded = false

    private val recentRequests = object : LinkedHashMap<String, RoomWriteAck>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RoomWriteAck>?) =
            size > MAX_RECENT_REQUESTS
    }

    fun nextTicket(): Long = synchronized(ticketLock) {
        if (!seeded) {
            lastTicket = maxOf(lastTicket, seed())
            seeded = true
        }
        lastTicket = maxOf(lastTicket + 1, clock())
        lastTicket
    }

    suspend fun upsert(
        city: String,
        noteId: String,
        draft: RoomNoteDraft,
        ticket: Long,
        requestId: String? = null,
    ): RoomWriteAck {
        val key = RoomNoteKeys.key(city, noteId)
        return mutex.withLock {
            requestId?.let { id -> recentRequests[id]?.let { return@withLock it } }
            val existing = store.findAny(key)
            val ack = when {
                existing?.deletedAt != null ->
                    RoomWriteAck.Rejected("Deleted note cannot be restored by a stale write")
                existing != null && existing.updatedAt >= ticket -> RoomWriteAck.IgnoredStale
                else -> {
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
                            createdAt = existing?.createdAt ?: ticket,
                            updatedAt = ticket,
                            deletedAt = null,
                            syncState = existing?.syncState ?: "LOCAL"
                        )
                    )
                    RoomWriteAck.Applied
                }
            }
            requestId?.let { recentRequests[it] = ack }
            ack
        }
    }

    suspend fun delete(city: String, noteId: String, ticket: Long, requestId: String? = null): RoomWriteAck {
        val key = RoomNoteKeys.key(city, noteId)
        return mutex.withLock {
            requestId?.let { id -> recentRequests[id]?.let { return@withLock it } }
            val existing = store.findAny(key)
            val ack = when {
                existing == null -> {
                    store.upsert(
                        NoteEntity(
                            id = key,
                            city = city,
                            title = "",
                            createdAt = ticket,
                            updatedAt = ticket,
                            deletedAt = ticket,
                            syncState = "PENDING"
                        )
                    )
                    RoomWriteAck.Applied
                }
                existing.deletedAt != null -> RoomWriteAck.Applied
                existing.updatedAt >= ticket -> RoomWriteAck.IgnoredStale
                else -> {
                    store.softDelete(key, ticket)
                    RoomWriteAck.Applied
                }
            }
            requestId?.let { recentRequests[it] = ack }
            ack
        }
    }

    companion object {
        const val MAX_RECENT_REQUESTS = 512

        /** Outlives activities so recreation does not cancel or reorder pending writes. */
        val writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var shared: RoomNoteWriteSequencer? = null

        fun shared(dao: NoteDao): RoomNoteWriteSequencer =
            shared ?: synchronized(this) {
                shared ?: RoomNoteWriteSequencer(
                    store = RoomNoteWriteStore(dao),
                    seed = { dao.maxUpdatedAt() ?: 0L }
                ).also { shared = it }
            }
    }
}

fun RoomWriteAck.toBridgeSuccess(): Boolean = this !is RoomWriteAck.Rejected

fun RoomWriteAck.toBridgeError(): String? = (this as? RoomWriteAck.Rejected)?.message
