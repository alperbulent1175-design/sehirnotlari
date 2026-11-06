package com.sehirnotlari.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
 * Startup reconciliation on a real SQLite file. A crash is modelled the way it happens on a
 * device: the bridge journals a write and the process dies before (or after) Room applies it;
 * the database is then closed and reopened by a fresh sequencer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomNoteReconcilerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "reconcile-test.db"
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

    /** One app process: its own sequencer and journal over the shared database file. */
    private inner class Process(var now: Long) {
        val db: CityNotesDatabase = run {
            room?.close()
            CityNotesDatabase.builder(context, name).allowMainThreadQueries().build().also { room = it }
        }
        val sequencer = RoomNoteWriteSequencer(
            RoomNoteWriteStore(db.notes()),
            clock = { now },
            seed = { maxOf(db.notes().maxWriteTicket() ?: 0L, db.journal().maxTicket() ?: 0L) }
        )
        val journal = NoteWriteJournal(db.journal()) { now }

        /** What the bridge does for one save; [completes] = false stops where a crash would. */
        suspend fun save(city: String, id: String, draft: RoomNoteDraft, completes: Boolean = true): Long {
            val ticket = sequencer.nextTicket()
            journal.recordUpsert(ticket, city, id, draft)
            if (completes) journal.resolve(ticket, sequencer.upsert(city, id, draft, ticket))
            return ticket
        }

        suspend fun delete(city: String, id: String, completes: Boolean = true): Long {
            val ticket = sequencer.nextTicket()
            journal.recordDelete(ticket, city, id)
            if (completes) journal.resolve(ticket, sequencer.delete(city, id, ticket))
            return ticket
        }

        suspend fun reconcile(
            localStorage: String,
            apply: Boolean = true,
            sequencer: RoomNoteWriteSequencer = this.sequencer,
            cutoff: Long = Long.MAX_VALUE,
        ) = db.reconcileWithLegacy(sequencer, localStorage, apply, cutoff) { now }

        suspend fun row(key: String) = db.notes().findAny(key)
        suspend fun entry(ticket: Long) = db.journal().find(ticket)!!
    }

    private fun draft(title: String, content: String = "$title içerik", favorite: Boolean = false) =
        RoomNoteDraft(title = title, content = content, category = "Gezi", star = 3, color = "#e74c3c",
            favorite = favorite, dateLabel = "06 Kas 2026")

    /** `tur_v10` as the WebView stores it. */
    private fun localStorage(vararg notes: Triple<String, Long, RoomNoteDraft>): String {
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

    @Test
    fun newNoteCutOffByCrashIsRepairedAfterRestart() = runBlocking {
        val note = draft("Ulu Cami")
        val ticket = Process(now = 1_000L).save("Bursa", "101", note, completes = false)

        val after = Process(now = 5_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, note)))

        assertEquals(listOf(ReconcileItem("Bursa::101", ReconcileKind.MISSING_IN_ROOM, ReconcileAction.REPAIRED)), report.items)
        val row = after.row("Bursa::101")!!
        assertEquals(note, row.toDraft())
        assertEquals(ticket, row.writeTicket)
        assertEquals(1_000L, row.modifiedAt)
        assertEquals(NoteWriteJournalEntry.REPAIRED, after.entry(ticket).state)
        assertNull(after.entry(ticket).previousJson)
        assertTrue(after.sequencer.nextTicket() > ticket)
    }

    @Test
    fun editCutOffByCrashIsRepairedAndReplacedRowIsKept() = runBlocking {
        val before = Process(now = 1_000L)
        val first = before.save("Bursa", "101", draft("Eski başlık"))
        val edit = draft("Yeni başlık", favorite = true)
        val second = before.save("Bursa", "101", edit, completes = false)

        val after = Process(now = 9_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, edit)))

        assertEquals(
            listOf(ReconcileItem("Bursa::101", ReconcileKind.CONTENT_MISMATCH, ReconcileAction.REPAIRED,
                listOf("title", "content", "favorite"))),
            report.items
        )
        val row = after.row("Bursa::101")!!
        assertEquals(edit, row.toDraft())
        assertEquals(second, row.writeTicket)
        assertEquals(1_000L, row.createdAt)
        val replaced = JSONObject(after.entry(second).previousJson!!)
        assertEquals("Eski başlık", replaced.getString("title"))
        assertEquals(first, replaced.getLong("writeTicket"))
        assertEquals(NoteWriteJournalEntry.APPLIED, after.entry(first).state)
    }

    @Test
    fun writeThatReachedRoomBeforeCrashIsNotRepeated() = runBlocking {
        val before = Process(now = 1_000L)
        val note = draft("Kaydedildi")
        val ticket = before.sequencer.nextTicket()
        before.journal.recordUpsert(ticket, "Bursa", "101", note)
        before.sequencer.upsert("Bursa", "101", note, ticket)

        val after = Process(now = 2_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, note)))

        assertTrue(report.items.isEmpty())
        assertEquals(1, report.consistent)
        assertEquals(1, report.superseded)
        assertEquals(NoteWriteJournalEntry.SUPERSEDED, after.entry(ticket).state)
        assertEquals(1_000L, after.row("Bursa::101")?.modifiedAt)
    }

    @Test
    fun roomNewerThanLocalStorageIsReportedNotOverwritten() = runBlocking {
        val before = Process(now = 1_000L)
        val old = draft("Birinci")
        before.save("Bursa", "101", old)
        before.save("Bursa", "101", draft("İkinci"))

        // localStorage lost its last write (WebView flushes it lazily); Room kept it.
        val after = Process(now = 3_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, old)))

        val item = report.items.single()
        assertEquals(ReconcileKind.CONTENT_MISMATCH, item.kind)
        assertEquals(ReconcileAction.REPORTED, item.action)
        assertTrue(item.roomNewer)
        assertEquals("İkinci", after.row("Bursa::101")?.title)
    }

    @Test
    fun olderLocalStorageIsNotWrittenOverRoomEvenWithPendingWrites() = runBlocking {
        val before = Process(now = 1_000L)
        val old = draft("Birinci")
        before.save("Bursa", "101", old)
        before.save("Bursa", "101", draft("İkinci"))
        val lost = before.save("Bursa", "101", draft("Üçüncü"), completes = false)

        val after = Process(now = 3_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, old)))

        val item = report.items.single()
        assertEquals(ReconcileAction.REPORTED, item.action)
        assertTrue(item.roomNewer)
        assertEquals("İkinci", after.row("Bursa::101")?.title)
        assertEquals(NoteWriteJournalEntry.PENDING, after.entry(lost).state)
    }

    @Test
    fun missingRoomRowWithoutJournalIsOnlyReported() = runBlocking {
        val process = Process(now = 1_000L)
        val report = process.reconcile(localStorage(Triple("Bursa", 101L, draft("Room öncesi not"))))

        assertEquals(listOf(ReconcileItem("Bursa::101", ReconcileKind.MISSING_IN_ROOM, ReconcileAction.REPORTED)), report.items)
        assertNull(process.row("Bursa::101"))
        assertTrue(process.db.journal().all().isEmpty())
    }

    @Test
    fun differentContentWithoutEvidenceIsReportedFieldByField() = runBlocking {
        val process = Process(now = 1_000L)
        process.sequencer.upsert("Bursa", "101", draft("Room"), process.sequencer.nextTicket())
        val local = RoomNoteDraft(title = "Yerel", content = "farklı", category = "Lezzet", star = 5, color = "#3498db",
            favorite = true, dateLabel = "07 Kas 2026", isAI = true)

        val report = process.reconcile(localStorage(Triple("Bursa", 101L, local)))

        assertEquals(
            ReconcileItem("Bursa::101", ReconcileKind.CONTENT_MISMATCH, ReconcileAction.REPORTED,
                listOf("title", "content", "category", "star", "color", "favorite", "isAI", "date")),
            report.items.single()
        )
        assertEquals("Room", process.row("Bursa::101")?.title)
    }

    @Test
    fun tombstoneIsNeverRevivedEvenByMatchingPendingWrite() = runBlocking {
        val before = Process(now = 1_000L)
        val note = draft("Silinen")
        before.save("Bursa", "101", note)
        before.delete("Bursa", "101")
        val revive = before.save("Bursa", "101", note, completes = false)

        val after = Process(now = 4_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, note)))

        assertEquals(listOf(ReconcileItem("Bursa::101", ReconcileKind.TOMBSTONE_CONFLICT, ReconcileAction.REPORTED)), report.items)
        assertNotNull(after.row("Bursa::101")?.deletedAt)
        assertEquals(NoteWriteJournalEntry.PENDING, after.entry(revive).state)
    }

    @Test
    fun rowsOfUnknownOriginAreReportedNotOverwritten() = runBlocking {
        val process = Process(now = 1_000L)
        val v1Row = NoteEntity(id = "Bursa::101", city = "Bursa", title = "v1", content = "v1 içerik", category = "Gezi",
            star = 3, color = "#e74c3c", dateLabel = "06 Kas 2026", createdAt = 10L, writeTicket = 500L)
        process.db.notes().upsert(v1Row)
        process.db.notes().upsert(v1Row.copy(id = "Bursa::102", title = "v1 ikinci"))

        val report = process.reconcile(
            localStorage(Triple("Bursa", 101L, draft("v1", "v1 içerik")), Triple("Bursa", 102L, draft("Değişti")))
        )

        assertEquals(
            listOf(
                ReconcileItem("Bursa::101", ReconcileKind.UNKNOWN_ORIGIN, ReconcileAction.REPORTED, unknownOrigin = true),
                ReconcileItem("Bursa::102", ReconcileKind.CONTENT_MISMATCH, ReconcileAction.REPORTED,
                    listOf("title", "content"), unknownOrigin = true),
            ),
            report.items
        )
        assertEquals(v1Row, process.row("Bursa::101"))
        assertEquals("v1 ikinci", process.row("Bursa::102")?.title)
    }

    @Test
    fun deletionCutOffByCrashIsCompletedAsTombstone() = runBlocking {
        val before = Process(now = 1_000L)
        before.save("Bursa", "101", draft("Silinecek"))
        before.now = 2_000L
        val delete = before.delete("Bursa", "101", completes = false)

        val after = Process(now = 8_000L)
        val report = after.reconcile("{}")

        assertEquals(listOf(ReconcileItem("Bursa::101", ReconcileKind.ONLY_IN_ROOM, ReconcileAction.REPAIRED)), report.items)
        val row = after.row("Bursa::101")!!
        assertEquals(2_000L, row.deletedAt)
        assertEquals(delete, row.writeTicket)
        assertEquals("Silinecek", row.title)
        assertEquals("Silinecek", JSONObject(after.entry(delete).previousJson!!).getString("title"))
    }

    @Test
    fun noteOnlyInRoomWithoutEvidenceIsNotDeleted() = runBlocking {
        val before = Process(now = 1_000L)
        before.save("Bursa", "101", draft("Yalnız Room"))

        val after = Process(now = 2_000L)
        val report = after.reconcile("{}")

        assertEquals(listOf(ReconcileItem("Bursa::101", ReconcileKind.ONLY_IN_ROOM, ReconcileAction.REPORTED)), report.items)
        assertNull(after.row("Bursa::101")?.deletedAt)
    }

    @Test
    fun pendingWriteMatchingNeitherSideIsReportedAsLost() = runBlocking {
        val before = Process(now = 1_000L)
        val note = draft("Kayıtlı")
        before.save("Bursa", "101", note)
        val lost = before.save("Bursa", "101", draft("Kaybolan düzenleme"), completes = false)

        val after = Process(now = 2_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, note)))

        assertEquals(
            listOf(ReconcileItem("Bursa::101", ReconcileKind.UNMATCHED_PENDING_WRITE, ReconcileAction.REPORTED, lostWrites = 1)),
            report.items
        )
        assertEquals(1, report.consistent)
        assertEquals(NoteWriteJournalEntry.LOST, after.entry(lost).state)
        assertEquals("Kayıtlı", after.row("Bursa::101")?.title)
        assertTrue(after.reconcile(localStorage(Triple("Bursa", 101L, note))).items.isEmpty())
    }

    @Test
    fun localStorageThatLostItsLastWritesIsRepairedToItsOwnState() = runBlocking {
        // Seen on the emulator: Room stopped at v1, localStorage kept v3, the journal knew v4 too.
        val before = Process(now = 1_000L)
        val applied = before.save("Ankara", "7", draft("v1"))
        val skipped = before.save("Ankara", "7", draft("v2"), completes = false)
        val kept = before.save("Ankara", "7", draft("v3"), completes = false)
        val lost = before.save("Ankara", "7", draft("v4"), completes = false)

        val after = Process(now = 9_000L)
        val ls = localStorage(Triple("Ankara", 7L, draft("v3")))
        val report = after.reconcile(ls)

        assertEquals(
            listOf(ReconcileItem("Ankara::7", ReconcileKind.CONTENT_MISMATCH, ReconcileAction.REPAIRED,
                listOf("title", "content"), lostWrites = 1)),
            report.items
        )
        assertEquals("v3", after.row("Ankara::7")?.title)
        assertEquals(kept, after.row("Ankara::7")?.writeTicket)
        assertEquals(NoteWriteJournalEntry.APPLIED, after.entry(applied).state)
        assertEquals(NoteWriteJournalEntry.SUPERSEDED, after.entry(skipped).state)
        assertEquals(NoteWriteJournalEntry.REPAIRED, after.entry(kept).state)
        assertEquals(NoteWriteJournalEntry.LOST, after.entry(lost).state)
        assertEquals("v1", JSONObject(after.entry(kept).previousJson!!).getString("title"))

        val again = after.reconcile(ls)
        assertTrue(again.items.isEmpty())
        assertEquals(1, again.consistent)
    }

    @Test
    fun writesAfterTheLocalStorageCopyAreLeftForTheNextStart() = runBlocking {
        val process = Process(now = 1_000L)
        val note = draft("Açılış")
        process.save("Bursa", "101", note)
        val cutoff = process.sequencer.nextTicket()
        val newer = process.save("Bursa", "101", draft("Açılıştan sonra"))
        val queued = process.save("Bursa", "102", draft("Kuyrukta"), completes = false)

        val report = process.reconcile(localStorage(Triple("Bursa", 101L, note)), cutoff = cutoff)

        assertTrue(report.items.isEmpty())
        assertEquals(1, report.changedSince)
        assertEquals("Açılıştan sonra", process.row("Bursa::101")?.title)
        assertEquals(NoteWriteJournalEntry.APPLIED, process.entry(newer).state)
        assertEquals(NoteWriteJournalEntry.PENDING, process.entry(queued).state)
    }

    @Test
    fun reconciliationCanRunAgainWithoutFurtherChanges() = runBlocking {
        val before = Process(now = 1_000L)
        val repaired = draft("Onarılacak")
        before.save("Bursa", "101", repaired, completes = false)
        before.save("Bursa", "102", draft("Room"))
        val ls = localStorage(Triple("Bursa", 101L, repaired), Triple("Bursa", 102L, draft("Yerel")), Triple("İzmir", 7L, draft("Eksik")))

        val after = Process(now = 5_000L)
        val first = after.reconcile(ls)
        val rows = after.db.notes().allRows()
        val journal = after.db.journal().all()
        after.now = 6_000L
        val second = after.reconcile(ls)

        assertEquals(1, first.items.count { it.action == ReconcileAction.REPAIRED })
        assertTrue(second.items.none { it.action == ReconcileAction.REPAIRED })
        assertEquals(first.items.filter { it.action == ReconcileAction.REPORTED }, second.items.filter { it.action == ReconcileAction.REPORTED })
        assertEquals(1, second.consistent)
        assertEquals(rows.toSet(), after.db.notes().allRows().toSet())
        assertEquals(journal, after.db.journal().all())
    }

    @Test
    fun reconciliationInterruptedMidwayRollsBackAndCanRunAgain() = runBlocking {
        val before = Process(now = 1_000L)
        val a = draft("A")
        val b = draft("B")
        val ta = before.save("Bursa", "101", a, completes = false)
        val tb = before.save("Bursa", "102", b, completes = false)
        val ls = localStorage(Triple("Bursa", 101L, a), Triple("Bursa", 102L, b))

        val after = Process(now = 5_000L)
        val failing = RoomNoteWriteSequencer(FailingStore(RoomNoteWriteStore(after.db.notes()), failOnUpsert = 2), clock = { 5_000L })
        try {
            after.reconcile(ls, sequencer = failing)
            fail("second repair should fail")
        } catch (_: IllegalStateException) {
        }
        assertNull(after.row("Bursa::101"))
        assertNull(after.row("Bursa::102"))
        assertEquals(NoteWriteJournalEntry.PENDING, after.entry(ta).state)
        assertEquals(NoteWriteJournalEntry.PENDING, after.entry(tb).state)

        val report = after.reconcile(ls)
        assertEquals(2, report.items.count { it.action == ReconcileAction.REPAIRED })
        assertEquals("A", after.row("Bursa::101")?.title)
        assertEquals("B", after.row("Bursa::102")?.title)
    }

    @Test
    fun dryRunReportsRepairsWithoutWriting() = runBlocking {
        val note = draft("Önizleme")
        val ticket = Process(now = 1_000L).save("Bursa", "101", note, completes = false)

        val after = Process(now = 2_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, note)), apply = false)

        assertEquals(ReconcileAction.REPAIRABLE, report.items.single().action)
        assertNull(after.row("Bursa::101"))
        assertEquals(NoteWriteJournalEntry.PENDING, after.entry(ticket).state)
    }

    @Test
    fun queuedWriteFinishingAfterRepairIsIgnoredAndLaterEditWins() = runBlocking {
        val process = Process(now = 1_000L)
        val note = draft("Kuyrukta")
        val ticket = process.sequencer.nextTicket()
        process.journal.recordUpsert(ticket, "Bursa", "101", note)

        process.reconcile(localStorage(Triple("Bursa", 101L, note)))
        val late = process.sequencer.upsert("Bursa", "101", note, ticket)
        process.journal.resolve(ticket, late)

        assertEquals(RoomWriteAck.IgnoredStale, late)
        assertEquals(NoteWriteJournalEntry.REPAIRED, process.entry(ticket).state)
        process.save("Bursa", "101", draft("Sonraki düzenleme"))
        assertEquals("Sonraki düzenleme", process.row("Bursa::101")?.title)
    }

    @Test
    fun restartSeedsTicketsAboveJournaledWrites() = runBlocking {
        val future = 9_000_000_000_000L
        Process(now = future).save("Bursa", "101", draft("Gelecek"), completes = false)

        val sequencer = RoomNoteWriteSequencer.forDatabase(Process(now = 0L).db)
        assertTrue(sequencer.nextTicket() > future)
    }

    @Test
    fun resolvedJournalEntriesArePrunedButUnresolvedOnesAreKept() = runBlocking {
        val before = Process(now = 1_000L)
        val applied = before.save("Bursa", "101", draft("Eski"))
        val pending = before.save("Bursa", "102", draft("Bekleyen"), completes = false)

        // localStorage holds other text for 102, so its pending write stays unresolved.
        val after = Process(now = 1_000L + RoomNoteReconciler.JOURNAL_RETENTION_MS + 1)
        val report = after.reconcile(localStorage(Triple("Bursa", 102L, draft("Başka"))), apply = true)

        assertEquals(1, report.pruned)
        assertNull(after.db.journal().find(applied))
        assertNotNull(after.db.journal().find(pending))
    }

    @Test
    fun repairOfAWriteCutOffLongAgoKeepsItsBackup() = runBlocking {
        val before = Process(now = 1_000L)
        before.save("Bursa", "101", draft("Eski"))
        val edit = draft("Yeni")
        val cutOff = before.save("Bursa", "101", edit, completes = false)

        // The app is reopened only after the retention window.
        val after = Process(now = 1_000L + RoomNoteReconciler.JOURNAL_RETENTION_MS + 5_000L)
        val report = after.reconcile(localStorage(Triple("Bursa", 101L, edit)))

        assertEquals(ReconcileAction.REPAIRED, report.items.single().action)
        val repaired = after.entry(cutOff)
        assertEquals(NoteWriteJournalEntry.REPAIRED, repaired.state)
        assertEquals("Eski", JSONObject(repaired.previousJson!!).getString("title"))
        assertEquals(1, report.pruned)
    }

    @Test
    fun malformedLocalStorageFailsSafelyAndBadEntriesAreSkipped() = runBlocking {
        val process = Process(now = 1_000L)
        process.sequencer.upsert("Bursa", "101", draft("Room"), process.sequencer.nextTicket())
        val before = process.db.notes().allRows()
        try {
            process.reconcile("""{"Bursa":[{"id":101,"title":"Gizli başlık"""")
            fail("malformed payload must fail")
        } catch (e: RoomBridgeFailure) {
            assertEquals("Invalid legacy notes payload", RoomBridgeErrors.safe(e))
        }
        assertEquals(before, process.db.notes().allRows())

        val report = process.reconcile(
            """{"Bursa":[{"title":"kimliksiz"},{"id":101,"title":"Room","content":"Room içerik","category":"Gezi","star":3,
               "color":"#e74c3c","fav":false,"date":"06 Kas 2026"},{"id":101,"title":"kopya"}],"İzmir":"dizi değil"}"""
        )
        assertEquals(1, report.legacyNotes)
        assertEquals(3, report.skippedLegacy)
        assertEquals(1, report.consistent)
    }

    @Test
    fun reportSummaryHasCountsOnly() = runBlocking {
        val process = Process(now = 1_000L)
        val report = process.reconcile(localStorage(Triple("Bursa", 101L, draft("Gizli başlık", "Gizli içerik"))))
        val line = report.logLine()
        assertFalse(line.contains("Gizli"))
        assertFalse(line.contains("Bursa"))
        assertTrue(line.contains("missingInRoom=1"))
        assertFalse(report.toJson().toString().contains("Gizli"))
    }

    private class FailingStore(private val inner: NoteWriteStore, private val failOnUpsert: Int) : NoteWriteStore by inner {
        private var upserts = 0
        override suspend fun upsert(note: NoteEntity) {
            if (++upserts == failOnUpsert) throw IllegalStateException("simulated crash")
            inner.upsert(note)
        }
    }
}
