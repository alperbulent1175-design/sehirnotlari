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
 * User-approved resolutions from the data-safety screen, on a real SQLite file.
 * Each [Process] reopens the database the way a restarted app does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomNoteResolverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "resolver-test.db"
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

        suspend fun save(city: String, id: String, draft: RoomNoteDraft, completes: Boolean = true): Long {
            val ticket = sequencer.nextTicket()
            journal.recordUpsert(ticket, city, id, draft)
            if (completes) journal.resolve(ticket, sequencer.upsert(city, id, draft, ticket))
            return ticket
        }

        suspend fun delete(city: String, id: String): Long {
            val ticket = sequencer.nextTicket()
            journal.recordDelete(ticket, city, id)
            journal.resolve(ticket, sequencer.delete(city, id, ticket))
            return ticket
        }

        suspend fun inspect(ls: String, key: String) = db.inspectNote(sequencer, ls, key) { now }
        suspend fun apply(ls: String, plan: ResolutionPlan, confirmed: Boolean = false, action: ResolutionAction? = null) =
            db.applyResolution(sequencer, ls, plan.key, action ?: plan.options.first().action, plan.token, confirmed) { now }
        suspend fun undo(backupId: Long) = db.undoResolution(sequencer, backupId) { now }
        suspend fun status() = db.dataSafetyStatus(sequencer) { now }
        suspend fun reconcile(ls: String, apply: Boolean = true) = db.reconcileWithLegacy(sequencer, ls, apply) { now }
        suspend fun row(key: String) = db.notes().findAny(key)
        suspend fun backups() = db.resolutions().recent(100)
    }

    private fun draft(title: String, content: String = "$title içerik", favorite: Boolean = false, isAI: Boolean = false) =
        RoomNoteDraft(title = title, content = content, category = "Gezi", star = 3, color = "#e74c3c",
            favorite = favorite, dateLabel = "06 Kas 2026", isAI = isAI)

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

    private val key = "Bursa::101"

    @Test
    fun consistentNoteOffersNothingAndApprovalIsANoOp() = runBlocking {
        val p = Process(now = 1_000L)
        val note = draft("Ulu Cami")
        p.save("Bursa", "101", note)
        val ls = localStorage(Triple("Bursa", 101L, note))

        val plan = p.inspect(ls, key)
        assertNull(plan.kind)
        assertTrue(plan.options.isEmpty())
        val before = p.row(key)
        val outcome = p.db.applyResolution(p.sequencer, ls, key, ResolutionAction.SYNC_FROM_LOCAL, plan.token, true) { p.now }
        assertEquals(ResolutionOutcome.ALREADY_CONSISTENT, outcome.code)
        assertEquals(before, p.row(key))
        assertTrue(p.backups().isEmpty())
    }

    @Test
    fun missingRoomRowIsAddedFromLocalStorageWithoutUndoTarget() = runBlocking {
        val p = Process(now = 1_000L)
        val note = draft("Eksik")
        val ls = localStorage(Triple("Bursa", 101L, note))

        val plan = p.inspect(ls, key)
        assertEquals(ReconcileKind.MISSING_IN_ROOM, plan.kind)
        assertEquals(listOf(ResolutionOption(ResolutionAction.SYNC_FROM_LOCAL, true, false, ResolutionOption.ADDS_MISSING_ROW)), plan.options)

        val outcome = p.apply(ls, plan)
        assertEquals(ResolutionOutcome.APPLIED, outcome.code)
        assertFalse(outcome.undoable)
        assertNull(outcome.plan!!.kind)
        assertEquals(note, p.row(key)!!.toDraft())
        val backup = p.backups().single()
        assertNull(backup.beforeJson)
        assertEquals(p.row(key)!!.writeTicket, backup.afterTicket)
        assertEquals(ReconcileKind.MISSING_IN_ROOM.name, backup.kind)
        assertEquals(ResolutionOutcome.NOTHING_TO_UNDO, p.undo(backup.id).code)
        assertEquals(note, p.row(key)!!.toDraft())
    }

    @Test
    fun overwriteNeedsExplicitConfirmationThenBacksUpAndUndoesExactly() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "101", draft("Room başlığı"))
        val original = p.row(key)!!
        val local = draft("Telefon başlığı", favorite = true)
        val ls = localStorage(Triple("Bursa", 101L, local))

        val plan = p.inspect(ls, key)
        assertEquals(ReconcileKind.CONTENT_MISMATCH, plan.kind)
        assertEquals(listOf("title", "content", "favorite"), plan.fields)
        assertEquals(ResolutionOption(ResolutionAction.SYNC_FROM_LOCAL, true, true, ResolutionOption.OVERWRITES_ROOM_ROW),
            plan.options.single())

        assertEquals(ResolutionOutcome.CONFIRMATION_REQUIRED, p.apply(ls, plan, confirmed = false).code)
        assertEquals(original, p.row(key))
        assertTrue(p.backups().isEmpty())

        p.now = 2_000L
        val outcome = p.apply(ls, plan, confirmed = true)
        assertEquals(ResolutionOutcome.APPLIED, outcome.code)
        assertTrue(outcome.undoable)
        val changed = p.row(key)!!
        assertEquals(local, changed.toDraft())
        assertEquals(2_000L, changed.modifiedAt)
        assertEquals(original.createdAt, changed.createdAt)
        assertEquals(original, NoteRowJson.decode(p.backups().single().beforeJson!!))

        val undo = p.undo(outcome.backupId!!)
        assertEquals(ResolutionOutcome.UNDONE, undo.code)
        assertEquals(original, p.row(key))
        assertEquals(ResolutionOutcome.ALREADY_UNDONE, p.undo(outcome.backupId!!).code)
        assertEquals(original, p.row(key))
        assertEquals(ReconcileKind.CONTENT_MISMATCH, p.inspect(ls, key).kind)
    }

    @Test
    fun newerRoomVersionIsFlaggedAndOnlyReplacedWithConfirmation() = runBlocking {
        val p = Process(now = 1_000L)
        val older = draft("Telefonda kalan")
        p.save("Bursa", "101", older)
        p.save("Bursa", "101", draft("Room'a ulaşan son düzenleme"))
        val ls = localStorage(Triple("Bursa", 101L, older))

        val plan = p.inspect(ls, key)
        assertEquals(ReconcileKind.CONTENT_MISMATCH, plan.kind)
        assertTrue(plan.roomNewer)
        val option = plan.options.single()
        assertEquals(ResolutionOption.ROOM_MAY_BE_NEWER, option.reason)
        assertTrue(option.needsConfirmation)
        assertEquals(ResolutionOutcome.CONFIRMATION_REQUIRED, p.apply(ls, plan).code)
        assertEquals("Room'a ulaşan son düzenleme", p.row(key)!!.title)
    }

    @Test
    fun tombstoneIsNeverRevivedByAnApproval() = runBlocking {
        val p = Process(now = 1_000L)
        val note = draft("Silinmiş")
        p.save("Bursa", "101", note)
        p.delete("Bursa", "101")
        val tombstone = p.row(key)!!
        val ls = localStorage(Triple("Bursa", 101L, note))

        val plan = p.inspect(ls, key)
        assertEquals(ReconcileKind.TOMBSTONE_CONFLICT, plan.kind)
        assertEquals(listOf(ResolutionOption(ResolutionAction.SYNC_FROM_LOCAL, false, false, ResolutionOption.TOMBSTONE_NOT_REVIVED)),
            plan.options)
        assertEquals(ResolutionOutcome.NOT_ALLOWED, p.apply(ls, plan, confirmed = true).code)
        assertEquals(ResolutionOutcome.NOT_ALLOWED,
            p.apply(ls, plan, confirmed = true, action = ResolutionAction.MARK_DELETED_IN_ROOM).code)
        assertEquals(tombstone, p.row(key))
        assertTrue(p.backups().isEmpty())
    }

    @Test
    fun unknownOriginIsConfirmedWithoutChangingTextAndCanBeUndone() = runBlocking {
        val p = Process(now = 1_000L)
        val note = draft("v1 notu")
        val legacyRow = NoteEntity(id = key, city = "Bursa", title = note.title, content = note.content,
            category = note.category, star = note.star, color = note.color, dateLabel = note.dateLabel,
            createdAt = 10L, writeTicket = 500L)
        p.db.notes().upsert(legacyRow)
        val ls = localStorage(Triple("Bursa", 101L, note))

        val plan = p.inspect(ls, key)
        assertEquals(ReconcileKind.UNKNOWN_ORIGIN, plan.kind)
        assertTrue(plan.unknownOrigin)
        assertEquals(ResolutionOption.CONFIRMS_ORIGIN, plan.options.single().reason)
        assertFalse(plan.options.single().needsConfirmation)

        val outcome = p.apply(ls, plan)
        assertEquals(ResolutionOutcome.APPLIED, outcome.code)
        val confirmed = p.row(key)!!
        assertEquals(note, confirmed.toDraft())
        assertEquals(false, confirmed.isAI)
        assertEquals(1_000L, confirmed.modifiedAt)
        assertEquals(10L, confirmed.createdAt)
        assertNull(p.inspect(ls, key).kind)

        assertEquals(ResolutionOutcome.UNDONE, p.undo(outcome.backupId!!).code)
        assertEquals(legacyRow, p.row(key))
    }

    @Test
    fun roomOnlyNoteIsMarkedDeletedKeepingItsTextAndUndoRestoresIt() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "101", draft("Yalnız Room"))
        val original = p.row(key)!!
        val ls = localStorage()

        val plan = p.inspect(ls, key)
        assertEquals(ReconcileKind.ONLY_IN_ROOM, plan.kind)
        assertEquals(ResolutionOption(ResolutionAction.MARK_DELETED_IN_ROOM, true, true, ResolutionOption.TOMBSTONES_ROOM_ROW),
            plan.options.single())
        assertEquals("Yalnız Room", plan.room!!.title)
        assertEquals(ResolutionOutcome.CONFIRMATION_REQUIRED, p.apply(ls, plan).code)

        p.now = 3_000L
        val outcome = p.apply(ls, plan, confirmed = true)
        assertEquals(ResolutionOutcome.APPLIED, outcome.code)
        val tombstone = p.row(key)!!
        assertEquals(3_000L, tombstone.deletedAt)
        assertEquals("Yalnız Room", tombstone.title)
        assertEquals("Yalnız Room içerik", tombstone.content)
        assertNull(outcome.plan!!.kind)

        val listed = p.status().backups.single()
        assertTrue(listed.undoable)
        assertEquals("Yalnız Room", listed.title)
        assertEquals(ResolutionOutcome.UNDONE, p.undo(outcome.backupId!!).code)
        assertEquals(original, p.row(key))
    }

    @Test
    fun approvalIsRefusedWhenEitherSideChangedAfterThePreview() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "101", draft("Room"))
        val ls = localStorage(Triple("Bursa", 101L, draft("Telefon")))
        val plan = p.inspect(ls, key)

        p.save("Bursa", "101", draft("Arada gelen düzenleme"))
        val roomChanged = p.apply(ls, plan, confirmed = true)
        assertEquals(ResolutionOutcome.STALE, roomChanged.code)
        assertEquals("Arada gelen düzenleme", p.row(key)!!.title)

        val fresh = p.inspect(ls, key)
        val lsChanged = localStorage(Triple("Bursa", 101L, draft("Telefonda yeni düzenleme")))
        assertEquals(ResolutionOutcome.STALE, p.apply(lsChanged, fresh, confirmed = true).code)
        assertEquals("Arada gelen düzenleme", p.row(key)!!.title)
        assertTrue(p.backups().isEmpty())
    }

    @Test
    fun repeatingTheSameApprovalAppliesItOnce() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "101", draft("Room"))
        val local = draft("Telefon")
        val ls = localStorage(Triple("Bursa", 101L, local))
        val plan = p.inspect(ls, key)

        assertEquals(ResolutionOutcome.APPLIED, p.apply(ls, plan, confirmed = true).code)
        val afterFirst = p.row(key)
        assertEquals(ResolutionOutcome.ALREADY_CONSISTENT, p.apply(ls, plan, confirmed = true).code)
        val restarted = Process(now = 2_000L)
        assertEquals(ResolutionOutcome.ALREADY_CONSISTENT, restarted.apply(ls, plan, confirmed = true).code)
        assertEquals(afterFirst, restarted.row(key))
        assertEquals(1, restarted.backups().size)
    }

    /** Fails at the very end of an approval, after the backup and the Room write. */
    private class CrashAfterWrite(private val real: NoteResolutionStore) : NoteResolutionStore by real {
        private var backedUp = false
        override suspend fun insertBackup(backup: NoteResolutionBackup): Long = real.insertBackup(backup).also { backedUp = true }
        override suspend fun journalFor(key: String): List<NoteWriteJournalEntry> {
            if (backedUp) throw IllegalStateException("process died")
            return real.journalFor(key)
        }
    }

    @Test
    fun crashDuringApprovalLeavesNeitherBackupNorChangeAndCanBeRetried() = runBlocking {
        val before = Process(now = 1_000L)
        before.save("Bursa", "101", draft("Room"))
        val original = before.row(key)
        val ls = localStorage(Triple("Bursa", 101L, draft("Telefon")))
        val plan = before.inspect(ls, key)

        try {
            before.sequencer.mutex.withLock {
                before.db.withTransaction {
                    RoomNoteResolver(before.sequencer, CrashAfterWrite(RoomNoteResolutionStore(before.db))) { before.now }
                        .apply(ls, key, ResolutionAction.SYNC_FROM_LOCAL, plan.token, confirmed = true)
                }
            }
            fail("approval must fail")
        } catch (_: IllegalStateException) {
        }
        assertEquals(original, before.row(key))
        assertTrue(before.backups().isEmpty())

        val after = Process(now = 5_000L)
        val retry = after.inspect(ls, key)
        assertEquals(plan.token, retry.token)
        assertEquals(ResolutionOutcome.APPLIED, after.apply(ls, retry, confirmed = true).code)
        assertEquals("Telefon", after.row(key)!!.title)
        assertEquals(1, after.backups().size)
    }

    @Test
    fun undoIsBlockedOnceTheNoteChangedAgain() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "101", draft("Room"))
        val ls = localStorage(Triple("Bursa", 101L, draft("Telefon")))
        val outcome = p.apply(ls, p.inspect(ls, key), confirmed = true)
        p.save("Bursa", "101", draft("Sonraki düzenleme"))

        assertFalse(p.status().backups.single().undoable)
        assertEquals(ResolutionOutcome.UNDO_BLOCKED, p.undo(outcome.backupId!!).code)
        assertEquals("Sonraki düzenleme", p.row(key)!!.title)
        assertEquals(ResolutionOutcome.NOT_FOUND, p.undo(999L).code)
    }

    @Test
    fun statusSurvivesRestartWithRepairsLostWritesAndBackups() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val crashed = Process(now = 1_000L)
        crashed.save("Bursa", "101", draft("Taslak 1"), completes = false)
        crashed.save("Bursa", "101", draft("Taslak 2"), completes = false)
        crashed.save("Bursa", "202", draft("Room"))

        val first = Process(now = 2_000L)
        // localStorage kept draft 1 only: draft 2 reached neither store.
        val ls = localStorage(Triple("Bursa", 101L, draft("Taslak 1")), Triple("Bursa", 202L, draft("Telefon")))
        first.reconcile(ls)
        first.apply(ls, first.inspect(ls, "Bursa::202"), confirmed = true)

        val restarted = Process(now = 3_000L).status()
        assertEquals(listOf(key), restarted.repairedKeys)
        assertEquals(2_000L, restarted.lastRepairAt)
        assertEquals(1, restarted.lostWrites)
        assertEquals(listOf(key), restarted.lostKeys)
        assertEquals(0, restarted.unresolvedWrites)
        assertEquals("Telefon", restarted.backups.single().title)
        assertTrue(restarted.backups.single().undoable)
        assertEquals(restarted.logLine(), "backups=1 undoable=1 repaired=1 lostWrites=1 unresolved=0")

        // Journal history follows the retention window; user backups are kept.
        val later = Process(now = 2_000L + RoomNoteReconciler.JOURNAL_RETENTION_MS + day)
        later.reconcile(ls)
        val status = later.status()
        assertTrue(status.repairedKeys.isEmpty())
        assertEquals(0, status.lostWrites)
        assertEquals(1, status.backups.size)
    }

    @Test
    fun ticketsStayAboveResolutionTicketsAfterUndoAndRestart() = runBlocking {
        val p = Process(now = 1_000_000L)
        p.save("Bursa", "101", draft("Room"))
        val ls = localStorage(Triple("Bursa", 101L, draft("Telefon")))
        val outcome = p.apply(ls, p.inspect(ls, key), confirmed = true)
        p.undo(outcome.backupId!!)
        val afterTicket = p.backups().single().afterTicket
        assertTrue(p.row(key)!!.writeTicket < afterTicket)

        val restarted = Process(now = 1L)
        assertTrue(restarted.sequencer.nextTicket() > afterTicket)
    }

    @Test
    fun planAgreesWithTheStartupReportForEveryKind() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "1", draft("Aynı"))
        p.save("Bursa", "2", draft("Room sürümü"))
        p.save("Bursa", "3", draft("Silinecek"))
        p.delete("Bursa", "3")
        p.save("Bursa", "4", draft("Yalnız Room"))
        p.db.notes().upsert(NoteEntity(id = "Bursa::5", city = "Bursa", title = "Eski", content = "Eski içerik",
            category = "Gezi", star = 3, color = "#e74c3c", dateLabel = "06 Kas 2026", writeTicket = 10L))
        val ls = localStorage(
            Triple("Bursa", 1L, draft("Aynı")),
            Triple("Bursa", 2L, draft("Telefon sürümü")),
            Triple("Bursa", 3L, draft("Silinecek")),
            Triple("Bursa", 5L, draft("Eski")),
            Triple("Bursa", 6L, draft("Eksik")),
        )
        val report = p.reconcile(ls, apply = false)
        assertEquals(5, report.items.size)
        report.items.forEach { item -> assertEquals(item.key, item.kind, p.inspect(ls, item.key).kind) }
        assertNull(p.inspect(ls, "Bursa::1").kind)
    }

    @Test
    fun planJsonCarriesBothVersionsForTheScreenAndMalformedInputFailsSafely() = runBlocking {
        val p = Process(now = 1_000L)
        p.save("Bursa", "101", draft("Room"))
        val json = p.inspect(localStorage(Triple("Bursa", 101L, draft("Telefon"))), key).toJson()
        assertEquals("CONTENT_MISMATCH", json.getString("kind"))
        assertEquals("Telefon", json.getJSONObject("local").getString("title"))
        assertEquals("Room", json.getJSONObject("room").getString("title"))
        assertFalse(json.getJSONObject("room").getBoolean("deleted"))
        val option = json.getJSONArray("options").getJSONObject(0)
        assertEquals("SYNC_FROM_LOCAL", option.getString("action"))
        assertTrue(option.getBoolean("needsConfirmation"))
        assertNotNull(json.getString("token"))

        try {
            p.inspect("""{"Bursa":[{"id":101,"title":"Gizli"""", key)
            fail("malformed payload must fail")
        } catch (e: RoomBridgeFailure) {
            assertEquals("Invalid legacy notes payload", RoomBridgeErrors.safe(e))
        }
    }
}
