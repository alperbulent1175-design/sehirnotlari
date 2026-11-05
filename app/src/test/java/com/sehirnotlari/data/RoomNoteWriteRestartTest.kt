package com.sehirnotlari.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomNoteWriteRestartTest {
    private fun relaunch(store: InMemoryNoteWriteStore, clock: () -> Long) =
        RoomNoteWriteSequencer(store, clock = clock, seed = { store.maxWriteTicket() })

    @Test
    fun restartWithRewoundClockStillOrdersAfterPersistedWrites() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val before = relaunch(store) { 1_000_000L }
        val staleTicket = before.nextTicket()
        val lastTicket = before.nextTicket()
        before.upsert("Bursa", "101", draft("Oturum 1"), lastTicket)

        val after = relaunch(store) { 500L }
        val fresh = after.nextTicket()
        assertTrue(fresh > lastTicket)
        assertEquals(RoomWriteAck.Applied, after.upsert("Bursa", "101", draft("Oturum 2"), fresh))
        assertEquals(RoomWriteAck.IgnoredStale, after.upsert("Bursa", "101", draft("Gecikmiş"), staleTicket))
        assertEquals("Oturum 2", store.findAny("Bursa::101")?.title)
    }

    @Test
    fun tombstoneSurvivesRestartAndLaterWrites() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val before = relaunch(store) { 2_000_000L }
        before.upsert("Bursa", "101", draft("Kaydet"), before.nextTicket())
        before.delete("Bursa", "101", before.nextTicket())
        val deletedAt = store.findAny("Bursa::101")?.deletedAt
        assertNotNull(deletedAt)

        val after = relaunch(store) { 3_000_000L }
        val ack = after.upsert("Bursa", "101", draft("Diriltme"), after.nextTicket())
        assertTrue(ack is RoomWriteAck.Rejected)
        assertEquals(RoomWriteAck.Applied, after.delete("Bursa", "101", after.nextTicket()))
        val kept = store.findAny("Bursa::101")
        assertEquals(deletedAt, kept?.deletedAt)
        assertEquals("Bursa::101", kept?.id)
    }

    @Test
    fun saveEditRestartDeleteRestartReplayEndsDeleted() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val first = relaunch(store) { 10_000L }
        val saveTicket = first.nextTicket()
        first.upsert("Bursa", "101", draft("Kaydet"), saveTicket, "note-save")
        first.upsert("Bursa", "101", draft("Düzenle"), first.nextTicket(), "note-edit")

        val second = relaunch(store) { 10_000L }
        assertEquals(RoomWriteAck.Applied, second.delete("Bursa", "101", second.nextTicket(), "delete-1"))
        val deletedAt = store.findAny("Bursa::101")?.deletedAt
        assertNotNull(deletedAt)

        val third = relaunch(store) { 9_000L }
        val replay = third.upsert("Bursa", "101", draft("Kaydet"), third.nextTicket(), "note-save")
        assertTrue(replay is RoomWriteAck.Rejected)
        val staleReplay = third.upsert("Bursa", "101", draft("Kaydet"), saveTicket)
        assertTrue(staleReplay is RoomWriteAck.Rejected)
        val kept = store.findAny("Bursa::101")
        assertEquals(deletedAt, kept?.deletedAt)
        assertEquals("Bursa::101", kept?.id)
    }

    @Test
    fun duplicateRequestIdDoesNotOverwriteNewerEdit() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = relaunch(store) { 5_000L }
        sequencer.upsert("Bursa", "101", draft("Eski"), sequencer.nextTicket(), "note-r1")
        sequencer.upsert("Bursa", "101", draft("Yeni"), sequencer.nextTicket(), "note-r2")
        val replay = sequencer.upsert("Bursa", "101", draft("Eski"), sequencer.nextTicket(), "note-r1")
        assertEquals(RoomWriteAck.Applied, replay)
        assertEquals("Yeni", store.findAny("Bursa::101")?.title)
    }

    @Test
    fun duplicateDeleteKeepsOriginalTombstoneTime() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = relaunch(store) { 7_000L }
        sequencer.upsert("Bursa", "101", draft("Not"), sequencer.nextTicket())
        sequencer.delete("Bursa", "101", sequencer.nextTicket(), "delete-r1")
        val deletedAt = store.findAny("Bursa::101")?.deletedAt
        assertEquals(RoomWriteAck.Applied, sequencer.delete("Bursa", "101", sequencer.nextTicket(), "delete-r1"))
        assertEquals(RoomWriteAck.Applied, sequencer.delete("Bursa", "101", sequencer.nextTicket(), "delete-r2"))
        assertEquals(deletedAt, store.findAny("Bursa::101")?.deletedAt)
    }

    @Test
    fun ticketsStayMonotonicWhenClockStallsOrRewinds() {
        var now = 1_000L
        val sequencer = RoomNoteWriteSequencer(InMemoryNoteWriteStore(), clock = { now })
        val a = sequencer.nextTicket()
        val b = sequencer.nextTicket()
        now = 10L
        val c = sequencer.nextTicket()
        assertTrue(a < b && b < c)
    }

    @Test
    fun legacyRowsWithFutureWriteTicketStillAcceptNewEdits() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val future = 9_000_000_000_000L
        store.upsert(NoteEntity(id = "Bursa::101", city = "Bursa", title = "Eski kayıt", writeTicket = future, createdAt = future))
        val sequencer = relaunch(store) { 1_700_000_000_000L }
        assertEquals(RoomWriteAck.Applied, sequencer.upsert("Bursa", "101", draft("Düzenlendi"), sequencer.nextTicket()))
        val kept = store.findAny("Bursa::101")
        assertEquals("Düzenlendi", kept?.title)
        assertEquals(future, kept?.createdAt)
        assertNull(kept?.deletedAt)
    }

    @Test
    fun importedRowsAreOrderedWithSequencerTickets() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = relaunch(store) { 4_000L }
        val staleTicket = sequencer.nextTicket()
        LegacyRoomImporter.importOrThrow(
            """{"Bursa":[{"id":101,"title":"İçe aktarılan"}]}""",
            store,
            stamp = sequencer::nextTicket
        )
        assertEquals(RoomWriteAck.IgnoredStale, sequencer.upsert("Bursa", "101", draft("Eski"), staleTicket))
        assertEquals(RoomWriteAck.Applied, sequencer.upsert("Bursa", "101", draft("Yeni"), sequencer.nextTicket()))
        assertEquals("Yeni", store.findAny("Bursa::101")?.title)
    }

    private fun draft(title: String) = RoomNoteDraft(title = title, content = title)
}
