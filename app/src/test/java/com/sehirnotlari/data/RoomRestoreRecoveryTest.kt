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
import org.junit.Assert.assertFalse
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
 * A confirmed JSON restore cut off by a crash, finished (or safely dropped) at the next start from its
 * RESTORE journal record. Real SQLite file; each [Process] is a restarted app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomRestoreRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "restore-recovery-test.db"
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

        /** `beginRoomRestore`: the records exist before the page writes tur_v10. */
        fun record(city: String, id: String, draft: RoomNoteDraft): RestoreItem {
            val item = RestoreItem(city, id, sequencer.nextTicket())
            db.runInTransaction(Runnable { journal.recordRestore(item.ticket, city, id, draft) })
            return item
        }

        suspend fun commit(ls: String, vararg items: RestoreItem) = db.restoreFromLocal(sequencer, ls, items.toList()) { now }

        /** The startup reconciliation the page requests on every load. */
        suspend fun start(ls: String) = db.reconcileWithLegacy(sequencer, ls, apply = true) { now }
        suspend fun check(ls: String) = db.reconcileWithLegacy(sequencer, ls, apply = false) { now }
        suspend fun row(key: String) = db.notes().findAny(key)
        suspend fun rows() = db.notes().allRows().sortedBy { it.id }
        suspend fun backups() = db.resolutions().recent(100)
        suspend fun status() = db.dataSafetyStatus(sequencer) { now }
        suspend fun entry(ticket: Long) = db.journal().find(ticket)!!
        suspend fun journalRows() = db.journal().all()
    }

    private fun draft(title: String, content: String = "$title içerik") =
        RoomNoteDraft(title = title, content = content, category = "Gezi", star = 3, color = "#fef3c7",
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

    private val note = draft("Muş’ta kış — ŞĞÜİÖÇ", "Satır: ğüşıöç\n“tırnak” </script> 🙂")
    private val id = "1793974551210905"
    private val key = "Muş::$id"
    private val other = draft("Silinmiş kalacak")
    private val otherKey = "Van::8"

    /** A saved and deleted note, plus another deleted note that is never restored. */
    private suspend fun deletedNote(): Process {
        val p = Process(now = 1_000L)
        p.save("Muş", id, note)
        p.save("Van", "8", other)
        p.now = 2_000L
        p.delete("Muş", id)
        p.delete("Van", "8")
        return p
    }

    private val stored = localStorage(Triple("Muş", id.toLong(), note))

    @Test
    fun crashRightAfterTheTurV10WriteIsFinishedAtTheNextStart() = runBlocking {
        val p = deletedNote()
        val tombstone = p.row(key)!!
        val otherTombstone = p.row(otherKey)
        p.now = 3_000L
        val item = p.record("Muş", id, note)
        // tur_v10 now holds the note; the app is killed before the Room commit.

        val q = Process(now = 9_000L)
        val report = q.start(stored)
        val repaired = report.items.single()
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT, repaired.kind)
        assertEquals(ReconcileAction.REPAIRED, repaired.action)
        val row = q.row(key)!!
        assertNull(row.deletedAt)
        assertEquals(0, note.differingFields(row).size)
        assertEquals(item.ticket, row.writeTicket)
        // modifiedAt is the time the user confirmed the import, not the restart.
        assertEquals(3_000L, row.modifiedAt)
        assertEquals(1_000L, row.createdAt)
        assertEquals(otherTombstone, q.row(otherKey))
        // One undoable history entry keeps the deletion record; the record is closed with it.
        val backup = q.backups().single()
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT.name, backup.kind)
        assertEquals(tombstone, NoteRowJson.decode(backup.beforeJson!!))
        val entry = q.entry(item.ticket)
        assertEquals(NoteWriteJournalEntry.REPAIRED, entry.state)
        assertEquals(tombstone, NoteRowJson.decode(entry.previousJson!!))
        val status = q.status()
        assertEquals(0, status.unresolvedWrites)
        assertEquals(0, status.lostWrites)
        assertEquals(listOf(key), status.repairedKeys)
        assertTrue(status.backups.single().undoable)
    }

    @Test
    fun twoRestartsInARowChangeNothingAfterTheFirst() = runBlocking {
        val p = deletedNote()
        p.record("Muş", id, note)
        Process(now = 9_000L).start(stored)
        val second = Process(now = 10_000L)
        val rows = second.rows()
        val journal = second.journalRows()
        val backups = second.backups()

        val report = second.start(stored)
        assertTrue(report.items.toString(), report.items.isEmpty())
        assertEquals(1, report.consistent)
        val third = Process(now = 11_000L)
        assertTrue(third.start(stored).items.isEmpty())
        assertEquals(rows, third.rows())
        assertEquals(journal, third.journalRows())
        assertEquals(backups, third.backups())
    }

    @Test
    fun crashAfterTheRecordButBeforeTheTurV10WriteRestoresNothing() = runBlocking {
        val p = deletedNote()
        val rows = p.rows()
        val item = p.record("Muş", id, note)
        // Killed before tur_v10 was written (or the write never reached disk): the note is on neither side.
        val notStored = localStorage()

        val q = Process(now = 9_000L)
        val report = q.start(notStored)
        assertTrue(report.items.toString(), report.items.isEmpty())
        assertEquals(rows, q.rows())
        assertTrue(q.backups().isEmpty())
        assertEquals(NoteWriteJournalEntry.SUPERSEDED, q.entry(item.ticket).state)
        val status = q.status()
        assertEquals(0, status.lostWrites)
        assertEquals(0, status.unresolvedWrites)
        assertTrue(status.repairedKeys.isEmpty())
    }

    @Test
    fun aCancelledRecordNeverRevives() = runBlocking {
        val p = deletedNote()
        val tombstone = p.row(key)
        val item = p.record("Muş", id, note)
        // sv() failed: the page cancels; even a tur_v10 that somehow holds the note does not revive it.
        p.journal.cancelled(item.ticket)

        val q = Process(now = 9_000L)
        assertEquals(ReconcileAction.REPORTED, q.start(stored).items.single().action)
        assertEquals(tombstone, q.row(key))
        assertTrue(q.backups().isEmpty())
    }

    @Test
    fun aRoomErrorDuringTheCommitIsFinishedAtTheNextStart() = runBlocking {
        val p = deletedNote()
        val item = p.record("Muş", id, note)
        try {
            p.commit("{}", item)
            fail("the note is not in this tur_v10 copy")
        } catch (_: RoomBridgeFailure) {
        }
        p.journal.failed(item.ticket)
        assertNotNull(p.row(key)!!.deletedAt)

        val q = Process(now = 9_000L)
        assertEquals(ReconcileAction.REPAIRED, q.start(stored).items.single().action)
        assertNull(q.row(key)!!.deletedAt)
        assertEquals(NoteWriteJournalEntry.REPAIRED, q.entry(item.ticket).state)
    }

    @Test
    fun crashAfterTheRoomCommitLeavesNothingToRecover() = runBlocking {
        val p = deletedNote()
        val item = p.record("Muş", id, note)
        assertEquals(1, p.commit(stored, item).revived)

        val q = Process(now = 9_000L)
        val report = q.start(stored)
        assertTrue(report.items.isEmpty())
        assertEquals(1, report.consistent)
        assertEquals(NoteWriteJournalEntry.APPLIED, q.entry(item.ticket).state)
        assertEquals(1, q.backups().size)
        assertTrue(q.status().repairedKeys.isEmpty())
    }

    @Test
    fun aRoomErrorDuringRecoveryKeepsEverythingAndCanBeRetried() = runBlocking {
        val p = deletedNote()
        val item = p.record("Muş", id, note)
        val q = Process(now = 9_000L)
        val rows = q.rows()
        var failNext = true
        val failing = object : NoteWriteStore {
            val real = RoomNoteWriteStore(q.db.notes())
            override suspend fun findAny(id: String) = real.findAny(id)
            override suspend fun upsert(note: NoteEntity) {
                if (failNext) throw android.database.sqlite.SQLiteFullException("database or disk is full")
                real.upsert(note)
            }
            override suspend fun softDelete(id: String, ticket: Long, time: Long) = real.softDelete(id, ticket, time)
        }
        val sequencer = RoomNoteWriteSequencer(failing, clock = { q.now })
        try {
            sequencer.mutex.withLock {
                q.db.withTransaction { RoomNoteReconciler(sequencer, RoomNoteReconcileStore(q.db)) { q.now }.run(stored, apply = true) }
            }
            fail("the failing write must surface")
        } catch (_: android.database.sqlite.SQLiteFullException) {
        }
        // Nothing was half-done: no row, backup or journal change.
        assertEquals(rows, q.rows())
        assertTrue(q.backups().isEmpty())
        assertEquals(NoteWriteJournalEntry.PENDING, q.entry(item.ticket).state)

        // The data-safety check (a dry run) offers the proven repair and changes nothing itself.
        val preview = q.check(stored).items.single()
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT, preview.kind)
        assertEquals(ReconcileAction.REPAIRABLE, preview.action)
        assertEquals(rows, q.rows())
        // “Kanıtlı onarımları uygula” runs the same reconciliation with apply = true.
        failNext = false
        assertEquals(ReconcileAction.REPAIRED, q.start(stored).items.single().action)
        assertNull(q.row(key)!!.deletedAt)
        assertEquals(1, q.backups().size)
    }

    @Test
    fun aDeletionAfterTheRestoreIsKept() = runBlocking {
        val p = deletedNote()
        val item = p.record("Muş", id, note)
        // The Room commit never ran; the user deletes the restored note again (Geri Al window over).
        p.now = 4_000L
        assertTrue(p.delete("Muş", id) > item.ticket)
        val tombstone = p.row(key)

        // tur_v10 no longer holds the note.
        val q = Process(now = 9_000L)
        assertTrue(q.start(localStorage()).items.isEmpty())
        assertEquals(tombstone, q.row(key))
        assertEquals(NoteWriteJournalEntry.SUPERSEDED, q.entry(item.ticket).state)
        assertTrue(q.backups().isEmpty())
        assertEquals(0, q.status().lostWrites)
        assertEquals(0, q.status().unresolvedWrites)
    }

    @Test
    fun aDeletionWhoseTurV10WriteWasLostStillKeepsTheTombstone() = runBlocking {
        val p = deletedNote()
        val item = p.record("Muş", id, note)
        p.now = 4_000L
        p.delete("Muş", id)
        val tombstone = p.row(key)

        // The deletion reached the journal but its tur_v10 write was lost: the note is still stored.
        // The later deletion keeps the tombstone; the difference is reported, not revived.
        val q = Process(now = 9_000L)
        val report = q.start(stored).items.single()
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT, report.kind)
        assertEquals(ReconcileAction.REPORTED, report.action)
        assertEquals(tombstone, q.row(key))
        assertEquals(NoteWriteJournalEntry.SUPERSEDED, q.entry(item.ticket).state)
        assertTrue(q.backups().isEmpty())
    }

    @Test
    fun aRecordThatDoesNotMatchTheStoredNoteIsReportedNotRevived() = runBlocking {
        val p = deletedNote()
        val tombstone = p.row(key)
        // The user confirmed one version; tur_v10 holds another (e.g. edited before the restart).
        val item = p.record("Muş", id, note.copy(content = "dosyadaki sürüm"))

        val q = Process(now = 9_000L)
        val report = q.start(stored)
        assertEquals(ReconcileAction.REPORTED, report.items.single().action)
        assertEquals(tombstone, q.row(key))
        assertTrue(q.backups().isEmpty())
        assertEquals(NoteWriteJournalEntry.SUPERSEDED, q.entry(item.ticket).state)
        // The unusable record is closed: a second start reports the same and changes nothing.
        val again = Process(now = 10_000L)
        assertEquals(ReconcileAction.REPORTED, again.start(stored).items.single().action)
        assertEquals(tombstone, again.row(key))
    }

    @Test
    fun corruptOrMissingRecordsNeverRevive() = runBlocking {
        val p = Process(now = 1_000L)
        val fingerprint = NoteFingerprint.of(note)
        // One deleted note per case: no fingerprint, the wrong city, a garbled op, an ordinary write
        // (how restores were journaled before this version), and no record at all.
        val cases = listOf<Pair<String, String?>?>(
            NoteWriteJournalEntry.RESTORE to null,
            NoteWriteJournalEntry.RESTORE to "Van",
            "RESTOR" to "Muş",
            NoteWriteJournalEntry.UPSERT to "Muş",
            null,
        )
        val ids = cases.indices.map { "${it + 1}" }
        ids.forEach { p.save("Muş", it, note); p.delete("Muş", it) }
        val tombstones = p.rows()
        cases.forEachIndexed { i, case ->
            if (case == null) return@forEachIndexed
            val (op, city) = case
            p.db.journal().insert(
                NoteWriteJournalEntry(
                    ticket = p.sequencer.nextTicket(), noteKey = "Muş::${ids[i]}", city = city ?: "Muş", op = op,
                    fingerprint = if (city == null) null else fingerprint, state = NoteWriteJournalEntry.PENDING, createdAt = 3_000L,
                )
            )
        }
        val ls = localStorage(*ids.map { Triple("Muş", it as Any, note) }.toTypedArray())

        val q = Process(now = 9_000L)
        val report = q.start(ls)
        assertEquals(ids.map { "Muş::$it" }, report.items.map { it.key })
        assertTrue(report.items.all { it.kind == ReconcileKind.TOMBSTONE_CONFLICT && it.action == ReconcileAction.REPORTED })
        assertEquals(tombstones, q.rows())
        assertTrue(q.backups().isEmpty())
        assertEquals(0, q.status().lostWrites)
        // Unusable restore records are closed; a second start changes nothing.
        val again = Process(now = 10_000L)
        assertEquals(report.items, again.start(ls).items)
        assertEquals(tombstones, again.rows())
    }

    @Test
    fun aDryRunNeverRevivesAndRoomFirstStaysProtected() = runBlocking {
        val p = deletedNote()
        val tombstone = p.row(key)
        val item = p.record("Muş", id, note)

        val q = Process(now = 9_000L)
        assertEquals(ReconcileAction.REPAIRABLE, q.check(stored).items.single().action)
        assertEquals(tombstone, q.row(key))
        assertEquals(NoteWriteJournalEntry.PENDING, q.entry(item.ticket).state)
        // An ordinary write still cannot revive it while the record is open.
        assertTrue(q.sequencer.upsert("Muş", id, note, q.sequencer.nextTicket()) is RoomWriteAck.Rejected)
        // Room-first writes do not read the journal: an edit of the deleted note stays refused.
        val writer = RoomFirstWriter(q.db, q.sequencer, enabled = { true })
        val edit = JSONObject().put("op", "UPSERT").put("city", "Muş").put("base", tombstone!!.writeTicket)
            .put("note", JSONObject().put("id", id.toLong()).put("title", note.title).put("content", note.content)).toString()
        assertEquals(RoomFirstResult.DELETED, writer.write(RoomFirstWrite.parse(edit)).status)
        assertEquals(tombstone, q.row(key))
        // Room-first mode cannot be switched on while the record is open.
        val audit = RoomFirstPreflight(q.check(stored), q.db.journal().all().count { it.unresolved })
        assertFalse(audit.clean)
        assertEquals(1, audit.blockers["tombstoneConflict"])
        assertEquals(1, audit.blockers["repairable"])
        assertEquals(1, audit.blockers["unresolvedJournal"])
    }
}
