package com.sehirnotlari.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A confirmed JSON import whose `tur_v10` write never reached disk, finished at the next process start
 * from its staged copy. Real SQLite and real files; each [Process] is a restarted app, and the
 * localStorage text it is given is what a new process read from disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportStagingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "import-staging-test.db"
    private val dir = File(context.filesDir, "import-staging-test")
    private var room: CityNotesDatabase? = null

    @Before
    fun clean() {
        context.deleteDatabase(name)
        dir.deleteRecursively()
    }

    @After
    fun close() {
        room?.close()
        context.deleteDatabase(name)
        dir.deleteRecursively()
    }

    private inner class Process(var now: Long) {
        val db: CityNotesDatabase = run {
            room?.close()
            CityNotesDatabase.builder(context, name).allowMainThreadQueries().build().also { room = it }
        }
        val sequencer = RoomNoteWriteSequencer.forDatabase(db) { now }
        val journal = NoteWriteJournal(db.journal()) { now }
        val staging = ImportStaging(dir)

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

        /** `beginRoomRestore` for the page's `[[city, note], …]`: staged, then recorded. */
        fun begin(requestId: String, notesJson: String): List<RestoreItem> {
            val notes = RestoreNote.parseList(notesJson)!!
            val items = notes.map { RestoreItem(it.city, it.noteId, sequencer.nextTicket()) }
            staging.stage(requestId, notes.mapIndexed { i, n -> n.staged(items[i].ticket) })
            db.runInTransaction(Runnable { items.forEachIndexed { i, item -> journal.recordRestore(item.ticket, item.city, item.noteId, notes[i].draft) } })
            return items
        }

        suspend fun commit(ls: String, items: List<RestoreItem>) = db.restoreFromLocal(sequencer, ls, items) { now }

        /** `resumeRoomImports` at the first page load of this process. */
        suspend fun resume(ls: String) = ImportResume.run(staging, ls) { db.journal().forNote(it) }
        suspend fun start(ls: String) = db.reconcileWithLegacy(sequencer, ls, apply = true) { now }
        suspend fun row(key: String) = db.notes().findAny(key)
        suspend fun rows() = db.notes().allRows().sortedBy { it.id }
        suspend fun backups() = db.resolutions().recent(100)
        suspend fun entry(ticket: Long) = db.journal().find(ticket)!!
        suspend fun journalRows() = db.journal().all()
    }

    private fun draft(title: String, content: String = "$title içerik") =
        RoomNoteDraft(title = title, content = content, category = "Gezi", star = 3, color = "#fef3c7",
            favorite = true, dateLabel = "06 Kas 2026", isAI = false)

    private fun pageNote(id: Any, d: RoomNoteDraft): JSONObject =
        JSONObject().put("id", id).put("title", d.title).put("content", d.content).put("category", d.category)
            .put("star", d.star).put("color", d.color).put("fav", d.favorite).put("date", d.dateLabel).put("isAI", d.isAI)

    private fun localStorage(vararg notes: Triple<String, Any, RoomNoteDraft>): String {
        val root = JSONObject()
        notes.forEach { (city, id, d) ->
            val list = root.optJSONArray(city) ?: JSONArray().also { root.put(city, it) }
            list.put(pageNote(id, d))
        }
        return root.toString()
    }

    /** What the page does with a resume result: NoteIds.mergeNotes by id, then sv(). */
    private fun readd(ls: String, result: ImportResumeResult): String {
        val root = JSONObject(ls)
        val back = JSONObject(result.toJson()).getJSONArray("notes")
        for (i in 0 until back.length()) {
            val city = back.getJSONArray(i).getString(0)
            val note = back.getJSONArray(i).getJSONObject(1)
            val list = root.optJSONArray(city) ?: JSONArray().also { root.put(city, it) }
            if ((0 until list.length()).none { list.getJSONObject(it).get("id").toString() == note.get("id").toString() }) list.put(note)
        }
        return root.toString()
    }

    private val note = draft("Muş’ta kış — ŞĞÜİÖÇ", "Satır: ğüşıöç\n“tırnak” </script> 🙂")
    private val id = "1793974551210905"
    private val key = "Muş::$id"
    private val kept = draft("Eski not")
    private val before = localStorage(Triple("Van", 7L, kept))
    private val imported = JSONArray().put(JSONArray().put("Muş").put(pageNote(id.toLong(), note))).toString()
    private val stored = localStorage(Triple("Van", 7L, kept), Triple("Muş", id.toLong(), note))

    /** A note saved, then deleted (a Room deletion record), beside a note that stays. */
    private suspend fun deletedNote(): Process {
        val p = Process(now = 1_000L)
        p.save("Van", "7", kept)
        p.save("Muş", id, note)
        p.now = 2_000L
        p.delete("Muş", id)
        return p
    }

    @Test
    fun killedBeforeTheTurV10WriteReachedDiskIsFinishedFromTheStagedCopy() = runBlocking {
        val p = deletedNote()
        val tombstone = p.row(key)!!
        p.now = 3_000L
        val items = p.begin("restore-1-a", imported)
        // sv() returned and Room committed, but the process died before WebView wrote tur_v10 to disk.
        p.commit(stored, items)
        val journal = p.journalRows()

        val q = Process(now = 9_000L)
        val result = q.resume(before)
        assertEquals(1, result.notes.size)
        assertEquals(0, result.reported + result.unreadable + result.dropped + result.confirmed)
        val back = JSONObject(result.toJson()).getJSONArray("notes").getJSONArray(0)
        assertEquals("Muş", back.getString(0))
        // The exact note object: same id (as a number), every field and character.
        assertEquals(pageNote(id.toLong(), note).toString(), back.getJSONObject(1).toString())
        assertEquals(journal, q.journalRows())
        val ls = readd(before, result)
        val report = q.start(ls)
        assertTrue(report.items.toString(), report.items.isEmpty())
        val row = q.row(key)!!
        assertNull(row.deletedAt)
        assertTrue(note.differingFields(row).isEmpty())
        // The one history entry is the revived deletion record from the original import, nothing new.
        assertEquals(tombstone, NoteRowJson.decode(q.backups().single().beforeJson!!))
        assertEquals(1, dir.list()!!.size)

        // That page write reached disk: the next start confirms it and drops the staged copy.
        val r = Process(now = 10_000L)
        val confirmed = r.resume(ls)
        assertTrue(confirmed.notes.isEmpty())
        assertEquals(1, confirmed.confirmed)
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(r.start(ls).items.isEmpty())
        assertEquals(1, r.backups().size)
    }

    @Test
    fun killedBeforeTheRoomCommitToo() = runBlocking {
        val p = deletedNote()
        p.now = 3_000L
        val items = p.begin("restore-1-a", imported)

        val q = Process(now = 9_000L)
        val ls = readd(before, q.resume(before))
        assertEquals(stored, ls)
        // The open RESTORE record now matches the re-added note, so the startup recovery revives it.
        val report = q.start(ls)
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT, report.items.single().kind)
        assertEquals(ReconcileAction.REPAIRED, report.items.single().action)
        assertNull(q.row(key)!!.deletedAt)
        assertEquals(NoteWriteJournalEntry.REPAIRED, q.entry(items.single().ticket).state)
    }

    @Test
    fun killedAfterTheWriteReachedDiskLeavesNothingToDo() = runBlocking {
        val p = deletedNote()
        p.now = 3_000L
        p.commit(stored, p.begin("restore-1-a", imported))
        val rows = p.rows()
        val journal = p.journalRows()

        val q = Process(now = 9_000L)
        val result = q.resume(stored)
        assertTrue(result.notes.isEmpty())
        assertEquals(1, result.confirmed)
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(q.start(stored).items.isEmpty())
        assertEquals(rows, q.rows())
        assertEquals(journal, q.journalRows())
        assertEquals(1, q.backups().size)
    }

    @Test
    fun twoRestartsBeforeTheWriteReachesDiskAddTheNoteOnceWithoutNewHistory() = runBlocking {
        val p = deletedNote()
        p.now = 3_000L
        p.commit(stored, p.begin("restore-1-a", imported))

        val q = Process(now = 9_000L)
        val first = readd(before, q.resume(before))
        q.start(first)
        val rows = q.rows()
        val journal = q.journalRows()
        val backups = q.backups()
        // Killed again before the re-added copy reached disk.
        val r = Process(now = 10_000L)
        val second = r.resume(before)
        assertEquals(1, second.notes.size)
        val ls = readd(readd(before, second), second)
        assertEquals(stored, ls)
        assertTrue(r.start(ls).items.isEmpty())
        assertEquals(rows, r.rows())
        assertEquals(journal, r.journalRows())
        assertEquals(backups, r.backups())
    }

    @Test
    fun theSameNoteImportedAgainIsResumedOnceFromTheNewestImport() = runBlocking {
        val p = deletedNote()
        p.now = 3_000L
        p.begin("restore-1-a", imported)
        // A second import of the same file (the first never reached the page's disk copy).
        p.now = 4_000L
        val again = p.begin("restore-2-b", imported)

        val q = Process(now = 9_000L)
        val result = q.resume(before)
        assertEquals(1, result.notes.size)
        assertEquals(again.single().ticket, result.notes.single().ticket)
        assertEquals(1, result.dropped)
        assertEquals(listOf("restore-2-b.json"), dir.list()!!.toList())
    }

    @Test
    fun aDeletionAfterTheImportIsKept() = runBlocking {
        val p = deletedNote()
        p.now = 3_000L
        p.commit(stored, p.begin("restore-1-a", imported))
        p.now = 4_000L
        p.delete("Muş", id)
        val tombstone = p.row(key)

        // Neither the import nor the deletion reached disk; the deletion is the user's last word.
        val q = Process(now = 9_000L)
        val result = q.resume(before)
        assertTrue(result.notes.isEmpty())
        assertEquals(1, result.dropped)
        assertEquals(0, result.reported)
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(q.start(before).items.isEmpty())
        assertEquals(tombstone, q.row(key))
    }

    @Test
    fun anEditAfterTheImportIsReportedNotOverwrittenWithTheImportedText() = runBlocking {
        val p = deletedNote()
        p.now = 3_000L
        p.commit(stored, p.begin("restore-1-a", imported))
        p.now = 4_000L
        val edited = draft("Düzenlendi")
        p.save("Muş", id, edited)

        val q = Process(now = 9_000L)
        val result = q.resume(before)
        assertTrue(result.notes.isEmpty())
        assertEquals(1, result.reported)
        assertTrue(dir.list()!!.isEmpty())
        // The edit is still in Room and the startup report shows it.
        assertTrue(edited.differingFields(q.row(key)!!).isEmpty())
        assertEquals(ReconcileKind.ONLY_IN_ROOM, q.start(before).items.single().kind)
    }

    @Test
    fun aCancelledImportNeverComesBack() = runBlocking {
        val p = deletedNote()
        val items = p.begin("restore-1-a", imported)
        // sv() failed: the page cancels; the staged copy goes first, then the records.
        assertTrue(p.staging.discard("restore-1-a"))
        items.forEach { p.journal.cancelled(it.ticket) }
        assertTrue(Process(now = 9_000L).resume(before).notes.isEmpty())

        // Even if the copy outlived the cancel, a closed record does not prove it.
        val r = Process(now = 10_000L)
        val again = r.begin("restore-2-b", imported)
        again.forEach { r.journal.cancelled(it.ticket) }
        val result = Process(now = 11_000L).resume(before)
        assertTrue(result.notes.isEmpty())
        assertEquals(1, result.reported)
    }

    @Test
    fun missingOrMismatchingRecordsAreReportedNotResumed() = runBlocking {
        val p = deletedNote()
        val staged = RestoreNote.parseList(imported)!!.single()
        // Killed after staging, before the records: begin never returned, the page stored nothing.
        p.staging.stage("restore-1-a", listOf(staged.staged(p.sequencer.nextTicket())))
        val first = Process(now = 9_000L).resume(before)
        assertTrue(first.notes.isEmpty())
        assertEquals(1, first.reported)
        assertTrue(dir.list()!!.isEmpty())

        // A record for different content, or another city, than the staged copy.
        val q = Process(now = 10_000L)
        val ticket = q.sequencer.nextTicket()
        q.staging.stage("restore-2-b", listOf(staged.staged(ticket)))
        q.journal.recordRestore(ticket, "Muş", id, draft("başka"))
        val other = q.sequencer.nextTicket()
        q.staging.stage("restore-3-c", listOf(staged.copy(city = "Van").staged(other)))
        q.journal.recordRestore(other, "Bitlis", id, note)
        val second = Process(now = 11_000L).resume(before)
        assertTrue(second.notes.isEmpty())
        assertEquals(2, second.reported)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun aDamagedStagedFileIsReportedAndRemoved() = runBlocking {
        val p = deletedNote()
        p.begin("restore-1-a", imported)
        val file = File(dir, "restore-1-a.json")
        val text = file.readText()
        assertTrue(text.contains("Gezi"))
        file.writeText(text.replace("Gezi", "Gezx"))
        File(dir, "restore-2-b.json.tmp").writeText("{")
        File(dir, "restore-3-c.json").writeText("not json")

        val result = Process(now = 9_000L).resume(before)
        assertTrue(result.notes.isEmpty())
        assertEquals(2, result.unreadable)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun aStorageFailureStagesNothing() {
        val blocked = File(context.filesDir, "import-staging-blocked")
        blocked.writeText("a file where the directory should be")
        try {
            val staging = ImportStaging(File(blocked, "dir"))
            val note = RestoreNote.parseList(imported)!!.single().staged(1L)
            try {
                staging.stage("restore-1-a", listOf(note))
                fail("staged without a directory")
            } catch (_: RoomBridgeFailure) {
            }
            assertTrue(staging.all().isEmpty())
            listOf("", "../x", "a/b", "x".repeat(101)).forEach {
                try {
                    ImportStaging(dir).stage(it, listOf(note))
                    fail(it)
                } catch (_: RoomBridgeFailure) {
                }
            }
            assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
        } finally {
            blocked.delete()
        }
    }

    @Test
    fun oldAppDataWithoutStagedImportsIsUntouched() = runBlocking {
        val p = deletedNote()
        val rows = p.rows()
        val journal = p.journalRows()
        val result = Process(now = 9_000L).resume(before)
        assertEquals(ImportResumeResult.NONE.toJson(), result.toJson())
        assertFalse(dir.exists())
        val q = Process(now = 10_000L)
        assertEquals(rows, q.rows())
        assertEquals(journal, q.journalRows())
    }

    @Test
    fun resumeWritesNeitherRoomNorTheJournal() = runBlocking {
        val p = deletedNote()
        p.begin("restore-1-a", imported)
        val q = Process(now = 9_000L)
        val rows = q.rows()
        val journal = q.journalRows()
        repeat(2) { q.resume(before) }
        assertEquals(rows, q.rows())
        assertEquals(journal, q.journalRows())
        assertTrue(q.backups().isEmpty())
        // Only the reconciliation, through the matching RESTORE record, may revive the deletion record.
        assertTrue(q.row(key)!!.deletedAt != null)
    }
}
