package com.sehirnotlari.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomNoteWriteSequencerTest {
    @Test
    fun delayedStaleUpsertDoesNotOverwriteNewerNote() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = RoomNoteWriteSequencer(store)
        val t1 = sequencer.nextTicket()
        val t2 = sequencer.nextTicket()
        sequencer.upsert("Bursa", "101", draft("Yeni"), t2)
        val stale = sequencer.upsert("Bursa", "101", draft("Eski"), t1)
        assertEquals(RoomWriteAck.IgnoredStale, stale)
        val kept = store.findAny("Bursa::101")
        assertEquals("Yeni", kept?.title)
        assertEquals("Bursa::101", kept?.id)
    }

    @Test
    fun delayedUpsertDoesNotResurrectTombstone() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = RoomNoteWriteSequencer(store)
        val saveTicket = sequencer.nextTicket()
        val deleteTicket = sequencer.nextTicket()
        sequencer.delete("Bursa", "101", deleteTicket)
        val resurrect = sequencer.upsert("Bursa", "101", draft("Geri gelme"), saveTicket)
        assertTrue(resurrect is RoomWriteAck.Rejected || resurrect is RoomWriteAck.IgnoredStale)
        val kept = store.findAny("Bursa::101")
        assertNotNull(kept?.deletedAt)
        assertEquals("Bursa::101", kept?.id)
    }

    @Test
    fun staleDeleteDoesNotRemoveNewerEdit() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = RoomNoteWriteSequencer(store)
        val deleteTicket = sequencer.nextTicket()
        val editTicket = sequencer.nextTicket()
        sequencer.upsert("Bursa", "101", draft("Son hali"), editTicket)
        val staleDelete = sequencer.delete("Bursa", "101", deleteTicket)
        assertEquals(RoomWriteAck.IgnoredStale, staleDelete)
        val kept = store.findAny("Bursa::101")
        assertNull(kept?.deletedAt)
        assertEquals("Son hali", kept?.title)
    }

    @Test
    fun saveEditDeletePermutationsEndAsDeleted() = runBlocking {
        val orders = listOf(
            listOf(2, 0, 1),
            listOf(1, 2, 0),
            listOf(2, 1, 0),
            listOf(0, 2, 1),
            listOf(1, 0, 2),
            listOf(0, 1, 2),
        )
        for (order in orders) {
            val store = InMemoryNoteWriteStore()
            val sequencer = RoomNoteWriteSequencer(store)
            val save = sequencer.nextTicket()
            val edit = sequencer.nextTicket()
            val delete = sequencer.nextTicket()
            val ops = listOf(
                suspend { sequencer.upsert("Bursa", "101", draft("Kaydet"), save) },
                suspend { sequencer.upsert("Bursa", "101", draft("Düzenle"), edit) },
                suspend { sequencer.delete("Bursa", "101", delete) },
            )
            for (index in order) {
                ops[index]()
            }
            val kept = store.findAny("Bursa::101")
            assertNotNull("tombstone missing for order $order", kept?.deletedAt)
            assertEquals("Bursa::101", kept?.id)
        }
    }

    @Test
    fun concurrentCompletionsHonorCallTickets() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = RoomNoteWriteSequencer(store)
        val t1 = sequencer.nextTicket()
        val t2 = sequencer.nextTicket()
        val t3 = sequencer.nextTicket()
        coroutineScope {
            async {
                delay(25)
                sequencer.upsert("Bursa", "101", draft("Bir"), t1)
            }
            async {
                sequencer.upsert("Bursa", "101", draft("Üç"), t3)
            }
            async {
                delay(5)
                sequencer.upsert("Bursa", "101", draft("İki"), t2)
            }
        }
        assertEquals("Üç", store.findAny("Bursa::101")?.title)
    }

    @Test
    fun rejectedTombstoneWriteReportsBridgeFailure() {
        val rejected = RoomWriteAck.Rejected("Deleted note cannot be restored by a stale write")
        assertEquals(false, rejected.toBridgeSuccess())
        assertEquals("Deleted note cannot be restored by a stale write", rejected.toBridgeError())
        assertEquals(true, RoomWriteAck.IgnoredStale.toBridgeSuccess())
        assertEquals(null, RoomWriteAck.IgnoredStale.toBridgeError())
    }

    private fun draft(title: String) = RoomNoteDraft(title = title, content = title)
}

class InMemoryNoteWriteStore : NoteWriteStore {
    private val notes = linkedMapOf<String, NoteEntity>()

    override suspend fun findAny(id: String): NoteEntity? = notes[id]

    override suspend fun upsert(note: NoteEntity) {
        notes[note.id] = note
    }

    override suspend fun softDelete(id: String, ticket: Long, time: Long) {
        val current = notes[id] ?: return
        notes[id] = current.copy(deletedAt = time, modifiedAt = time, writeTicket = ticket, syncState = "PENDING")
    }

    fun maxWriteTicket(): Long = notes.values.maxOfOrNull { it.writeTicket } ?: 0L
}
