package com.sehirnotlari.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room side of a confirmed JSON restore, on a real SQLite file.
 * Each [Process] reopens the database the way a restarted app does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomNoteRestorerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "restorer-test.db"
    private var room: CityNotesDatabase? = null

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun close() {
        room?.close()
        context.deleteDatabase(name)
    }

    private inner class Process(var now: Long) {
        val db: CityNotesDatabase = run {
            room?.close()
            CityNotesDatabase.builder(context, name).allowMainThreadQueries().build().also { room = it }
        }
        val sequencer = RoomNoteWriteSequencer.forDatabase(db) { now }
        val journal = NoteWriteJournal(db.journal()) { now }

        suspend fun save(city: String, id: String, draft: RoomNoteDraft): Long {
            val ticket = sequencer.nextTicket()
            journal.recordUpsert(ticket, city, id, draft)
            journal.resolve(ticket, sequencer.upsert(city, id, draft, ticket))
            return ticket
        }

        suspend fun delete(city: String, id: String): Long {
            val ticket = sequencer.nextTicket()
            journal.recordDelete(ticket, city, id)
            journal.resolve(ticket, sequencer.delete(city, id, ticket))
            return ticket
        }

        /** What `beginRoomRestore` does before the page writes tur_v10: tickets and records, all or none. */
        fun record(ls: String, vararg notes: Pair<String, String>): List<RestoreItem> {
            val legacy = LegacyNotes.parse(ls)
            val items = notes.map { (city, id) -> RestoreItem(city, id, sequencer.nextTicket()) }
            db.runInTransaction(Runnable {
                items.forEach { journal.recordRestore(it.ticket, it.city, it.noteId, legacy.notes.getValue(it.key).draft) }
            })
            return items
        }

        suspend fun run(ls: String, items: List<RestoreItem>): RestoreOutcome = db.restoreFromLocal(sequencer, ls, items) { now }

        suspend fun restore(ls: String, vararg notes: Pair<String, String>) = run(ls, record(ls, *notes))

        suspend fun reconcile(ls: String) = db.reconcileWithLegacy(sequencer, ls, apply = false) { now }
        suspend fun row(key: String) = db.notes().findAny(key)
        suspend fun backups() = db.resolutions().recent(100)
        suspend fun status() = db.dataSafetyStatus(sequencer) { now }
        suspend fun entry(ticket: Long) = db.journal().find(ticket)!!
    }

    private fun draft(title: String, content: String = "$title içerik") =
        RoomNoteDraft(title = title, content = content, category = "Gezi", star = 4, color = "#fef3c7",
            favorite = true, dateLabel = "06 Kas 2026", isAI = false)

    private fun localStorage(vararg notes: Triple<String, Any, RoomNoteDraft>): String {
        val root = JSONObject()
        notes.forEach { (city, id, d) ->
            val list = root.optJSONArray(city) ?: JSONArray().also { root.put(city, it) }
            list.put(
                JSONObject().put("id", id).put("title", d.title).put("content", d.content).put("category", d.category)
                    .put("star", d.star).put("color", d.color).put("fav", d.favorite).put("date", d.dateLabel)
                    .put("isAI", d.isAI)
            )
        }
        return root.toString()
    }

    private val turkish = draft("Iğdır’da çay — ŞĞÜİÖÇ", "Satır 1: ğüşıöç\n“tırnak” </script> 🙂")
    private val id = "1793973336782796"
    private val key = "Iğdır::$id"

    @Test
    fun deletedNoteIsRevivedWithItsIdAndStoredContent() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.now = 2_000L
        p.delete("Iğdır", id)
        val tombstone = p.row(key)!!
        assertNotNull(tombstone.deletedAt)
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT, p.reconcile(ls).items.single().kind)

        p.now = 3_000L
        val items = p.record(ls, "Iğdır" to id)
        val out = p.run(ls, items)
        assertEquals(1, out.revived)
        val row = p.row(key)!!
        assertNull(row.deletedAt)
        assertEquals(key, row.id)
        assertEquals(0, turkish.differingFields(row).size)
        assertEquals(1_000L, row.createdAt)
        assertEquals(3_000L, row.modifiedAt)
        // The record is closed in the same transaction as the write.
        assertEquals(NoteWriteJournalEntry.APPLIED, p.entry(items.single().ticket).state)
        // The deletion record is kept as an undoable backup.
        val backup = p.backups().single()
        assertEquals(ResolutionAction.SYNC_FROM_LOCAL.name, backup.action)
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT.name, backup.kind)
        assertEquals(tombstone, NoteRowJson.decode(backup.beforeJson!!))
        assertEquals(row.writeTicket, backup.afterTicket)

        // After a restart both stores agree and nothing is left to review.
        val q = Process(now = 4_000L)
        val report = q.reconcile(ls)
        assertTrue(report.items.toString(), report.items.isEmpty())
        assertEquals(1, report.consistent)
        assertEquals(0, q.status().unresolvedWrites)
        assertTrue(q.status().backups.single().undoable)
    }

    @Test
    fun otherDeletionRecordsAreLeftAlone() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.save("Bursa", "7", draft("Silinmiş kalacak"))
        p.save("Van", "8", draft("Silinmiş kalacak 2"))
        p.delete("Iğdır", id)
        p.delete("Bursa", "7")
        p.delete("Van", "8")
        val others = listOf(p.row("Bursa::7"), p.row("Van::8"))
        // The file also held the other two notes, but only the Iğdır note was imported.
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))

        p.restore(ls, "Iğdır" to id)
        assertNull(p.row(key)!!.deletedAt)
        assertEquals(others, listOf(p.row("Bursa::7"), p.row("Van::8")))
        assertEquals(listOf(key), p.backups().map { it.noteKey })
    }

    @Test
    fun restoringTheSameNotesAgainChangesNothingButTheTicket() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.delete("Iğdır", id)
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))
        p.restore(ls, "Iğdır" to id)

        val again = p.restore(ls, "Iğdır" to id)
        assertEquals(1, again.unchanged)
        assertEquals(0, again.revived + again.replaced + again.added)
        assertEquals(1, p.backups().size)
        assertEquals(1, p.db.notes().allRows().count { it.id == key })
        assertTrue(p.reconcile(ls).items.isEmpty())
    }

    @Test
    fun missingRowIsAddedWithoutABackup() = runBlocking {
        val p = Process(now = 1_000L)
        val ls = localStorage(Triple("Bursa", 5L, draft("Yeni")), Triple("Bursa", "metin-id", draft("Metin kimlik")))

        val out = p.restore(ls, "Bursa" to "5", "Bursa" to "metin-id")
        assertEquals(2, out.added)
        assertTrue(p.backups().isEmpty())
        assertTrue(p.reconcile(ls).items.isEmpty())
    }

    @Test
    fun differentActiveRowIsBackedUpAndReplaced() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "5", draft("Room’daki eski sürüm"))
        val before = p.row("Bursa::5")!!
        val ls = localStorage(Triple("Bursa", 5L, draft("Yedekteki sürüm")))

        val out = p.restore(ls, "Bursa" to "5")
        assertEquals(1, out.replaced)
        assertEquals("Yedekteki sürüm", p.row("Bursa::5")!!.title)
        val backup = p.backups().single()
        assertEquals(ReconcileKind.CONTENT_MISMATCH.name, backup.kind)
        assertEquals(before, NoteRowJson.decode(backup.beforeJson!!))
    }

    @Test
    fun aDeletionMadeAfterTheRestoreRequestWins() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))
        val items = p.record(ls, "Iğdır" to id)
        // The user deletes the restored note again before the queued restore ran.
        p.delete("Iğdır", id)
        val tombstone = p.row(key)

        val out = p.run(ls, items)
        assertEquals(1, out.stale)
        assertEquals(tombstone, p.row(key))
        assertTrue(p.backups().isEmpty())
        assertEquals(NoteWriteJournalEntry.IGNORED, p.entry(items.single().ticket).state)
    }

    @Test
    fun aDeletionRequestedBeforeTheRestoreButAppliedLaterIsIgnored() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        // An undo window ends (deletion queued), then the import is confirmed; the restore runs first.
        val deleteTicket = p.sequencer.nextTicket()
        p.journal.recordDelete(deleteTicket, "Iğdır", id)
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))
        p.restore(ls, "Iğdır" to id)

        assertEquals(RoomWriteAck.IgnoredStale, p.sequencer.delete("Iğdır", id, deleteTicket))
        assertNull(p.row(key)!!.deletedAt)
    }

    @Test
    fun anUnconfirmedWriteStillCannotReviveATombstone() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.delete("Iğdır", id)
        val ticket = p.sequencer.nextTicket()

        assertTrue(p.sequencer.upsert("Iğdır", id, turkish, ticket) is RoomWriteAck.Rejected)
        assertNotNull(p.row(key)!!.deletedAt)
    }

    @Test
    fun aNoteMissingFromLocalStorageRefusesTheWholeRestore() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.delete("Iğdır", id)
        val tombstone = p.row(key)
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))
        val items = p.record(ls, "Iğdır" to id) + RestoreItem("Bursa", "404", p.sequencer.nextTicket())

        try {
            p.run(ls, items)
            fail("restore of a note that is not stored must fail")
        } catch (e: RoomBridgeFailure) {
            assertEquals("Restored note is not in local storage", e.message)
        }
        assertEquals(tombstone, p.row(key))
        assertTrue(p.backups().isEmpty())
        assertEquals(NoteWriteJournalEntry.PENDING, p.entry(items.first().ticket).state)
    }

    @Test
    fun aStoredNoteThatDiffersFromItsRecordIsNotRestored() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.delete("Iğdır", id)
        val tombstone = p.row(key)
        val recorded = localStorage(Triple("Iğdır", id.toLong(), turkish))
        val items = p.record(recorded, "Iğdır" to id)
        // tur_v10 holds another version of the note than the one the user confirmed.
        val stored = localStorage(Triple("Iğdır", id.toLong(), turkish.copy(content = "başka metin")))

        try {
            p.run(stored, items)
            fail("a note that differs from its restore record must not be restored")
        } catch (e: RoomBridgeFailure) {
            assertEquals("Restore record does not match local storage", e.message)
        }
        assertEquals(tombstone, p.row(key))
        assertTrue(p.backups().isEmpty())
    }

    @Test
    fun aTicketWithoutARestoreRecordCannotReviveATombstone() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.delete("Iğdır", id)
        val tombstone = p.row(key)
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))
        // An ordinary write record for the same content, and a ticket with no record at all.
        val upsertTicket = p.sequencer.nextTicket()
        p.journal.recordUpsert(upsertTicket, "Iğdır", id, turkish)
        listOf(upsertTicket, p.sequencer.nextTicket()).forEach { ticket ->
            try {
                p.run(ls, listOf(RestoreItem("Iğdır", id, ticket)))
                fail("ticket $ticket has no restore record")
            } catch (e: RoomBridgeFailure) {
                assertEquals("Restore record does not match local storage", e.message)
            }
        }
        assertEquals(tombstone, p.row(key))
    }

    @Test
    fun aRoomWriteErrorRollsBackEveryNoteAndBackup() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.save("Bursa", "5", draft("İkinci"))
        p.delete("Iğdır", id)
        p.delete("Bursa", "5")
        val rowsBefore = p.db.notes().allRows().sortedBy { it.id }
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish), Triple("Bursa", 5L, draft("İkinci")))
        val items = p.record(ls, "Iğdır" to id, "Bursa" to "5")

        // The second row write fails (e.g. disk full) after the first note was revived.
        var writes = 0
        val failing = object : NoteWriteStore {
            val real = RoomNoteWriteStore(p.db.notes())
            override suspend fun findAny(id: String) = real.findAny(id)
            override suspend fun upsert(note: NoteEntity) {
                if (++writes == 2) throw android.database.sqlite.SQLiteFullException("database or disk is full")
                real.upsert(note)
            }
            override suspend fun softDelete(id: String, ticket: Long, time: Long) = real.softDelete(id, ticket, time)
        }
        val sequencer = RoomNoteWriteSequencer(failing, clock = { p.now })
        try {
            sequencer.mutex.withLock {
                p.db.withTransaction { RoomNoteRestorer(sequencer, RoomNoteResolutionStore(p.db)) { p.now }.restore(ls, items) }
            }
            fail("the failing write must surface")
        } catch (_: android.database.sqlite.SQLiteFullException) {
        }
        items.forEach { p.journal.failed(it.ticket) }
        assertEquals(2, writes)
        assertEquals(rowsBefore, p.db.notes().allRows().sortedBy { it.id })
        assertTrue(p.backups().isEmpty())
        // The records stay open, so the restore can still be finished.
        assertTrue(items.all { p.entry(it.ticket).state == NoteWriteJournalEntry.FAILED })

        // Retrying (new records) succeeds; the failed records are then superseded.
        p.restore(ls, "Iğdır" to id, "Bursa" to "5")
        val report = Process(now = 2_000L).reconcile(ls)
        assertTrue(report.items.isEmpty())
        assertEquals(2, report.consistent)
    }

    @Test
    fun undoingARestoreBringsTheDeletionRecordBack() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Iğdır", id, turkish)
        p.delete("Iğdır", id)
        val tombstone = p.row(key)
        val ls = localStorage(Triple("Iğdır", id.toLong(), turkish))
        p.restore(ls, "Iğdır" to id)

        val undo = p.db.undoResolution(p.sequencer, p.backups().single().id) { p.now }
        assertEquals(ResolutionOutcome.UNDONE, undo.code)
        assertEquals(tombstone, p.row(key))
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT, p.reconcile(ls).items.single().kind)
        // The closed restore record does not revive it again at the next start.
        val q = Process(now = 2_000L)
        val report = q.db.reconcileWithLegacy(q.sequencer, ls, apply = true) { q.now }
        assertEquals(ReconcileAction.REPORTED, report.items.single().action)
        assertEquals(tombstone, q.row(key))
    }

    @Test
    fun parseListAcceptsOnlyCityNotePairs() {
        val notes = RestoreNote.parseList(
            """[["Iğdır",{"id":$id,"title":"Çay","content":"ğüş"}],["Bursa",{"id":"abc","title":"B"}],["Iğdır",{"id":"$id","title":"kopya"}]]"""
        )!!
        assertEquals(listOf("Iğdır" to id, "Bursa" to "abc"), notes.map { it.city to it.noteId })
        assertEquals(RoomNoteDraft(title = "Çay", content = "ğüş"), notes.first().draft)
        listOf(
            "", "{}", "[]", "[1]", """[["Bursa"]]""", """[["Bursa",{"id":1},2]]""", """[["",{"id":1}]]""", """[[1,{"id":1}]]""",
            """[["Bursa",1]]""", """[["Bursa",{}]]""", """[["Bursa",{"id":null}]]""", """[["Bursa",{"id":""}]]""",
            """[["${"x".repeat(121)}",{"id":1}]]""",
        ).forEach { assertNull(it, RestoreNote.parseList(it)) }
        val many = JSONArray().apply { repeat(RestoreNote.MAX_ITEMS + 1) { put(JSONArray().put("Bursa").put(JSONObject().put("id", it))) } }
        assertNull(RestoreNote.parseList(many.toString()))
    }
}
