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
import java.io.File

/**
 * The debug-only source switch: audit → verified backup → durable flag, and back. Each
 * [Process] reopens the database and the flag the way a restarted app does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomFirstSwitchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "room-first-switch-test.db"
    private val backupDir = File(context.filesDir, "room-first-switch-test")
    private val flags = MemoryFlags()
    private var room: CityNotesDatabase? = null

    private class MemoryFlags : RoomFirstFlagStore {
        var stored = RoomFirstFlag()
        var failNextWrite = false
        var writes = 0
        override fun read() = stored
        override fun write(flag: RoomFirstFlag) {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("killed before the flag reached disk")
            }
            writes++
            stored = flag
        }
    }

    @Before
    fun clean() {
        context.deleteDatabase(name)
        backupDir.deleteRecursively()
    }

    @After
    fun close() {
        room?.close()
        context.deleteDatabase(name)
        backupDir.deleteRecursively()
    }

    private inner class Process(var now: Long, available: Boolean = true) {
        val db: CityNotesDatabase = run {
            room?.close()
            CityNotesDatabase.builder(context, name).allowMainThreadQueries().build().also { room = it }
        }
        val sequencer = RoomNoteWriteSequencer.forDatabase(db) { now }
        val backups = RoomFirstBackups(backupDir) { now }
        val switch = RoomFirstSwitch(db, sequencer, flags, backups, available) { now }
        val writer = RoomFirstWriter(db, sequencer, enabled = switch::isOn)
        val journal = NoteWriteJournal(db.journal()) { now }

        /** A production-mode save: localStorage first, then the journaled shadow write. */
        suspend fun shadowSave(city: String, id: String, draft: RoomNoteDraft, completes: Boolean = true) {
            val ticket = sequencer.nextTicket()
            journal.recordUpsert(ticket, city, id, draft)
            if (completes) journal.resolve(ticket, sequencer.upsert(city, id, draft, ticket))
        }

        suspend fun rows() = db.notes().allRows()
    }

    private fun draft(title: String, content: String = "$title içerik", isAI: Boolean = false) =
        RoomNoteDraft(title = title, content = content, category = "Gezi", star = 3, color = "", favorite = false,
            dateLabel = "06 Kas 2026", isAI = isAI)

    private fun noteJson(id: Any, d: RoomNoteDraft) = JSONObject().put("id", id).put("title", d.title).put("content", d.content)
        .put("category", d.category).put("star", d.star).put("color", d.color).put("fav", d.favorite)
        .put("date", d.dateLabel).put("isAI", d.isAI)

    private fun localStorage(vararg notes: Triple<String, Any, RoomNoteDraft>): String {
        val root = JSONObject()
        notes.forEach { (city, id, d) -> (root.optJSONArray(city) ?: JSONArray().also { root.put(city, it) }).put(noteJson(id, d)) }
        return root.toString()
    }

    /** What the Room-first page writes back to localStorage when the mode is switched off. */
    private fun copyFromRoom(rows: List<NoteEntity>): String {
        val root = JSONObject()
        rows.filter { it.deletedAt == null }.forEach { row ->
            (root.optJSONArray(row.city) ?: JSONArray().also { root.put(row.city, it) })
                .put(noteJson(RoomNoteKeys.webViewId(row), row.toDraft()))
        }
        return root.toString()
    }

    private suspend fun consistentPair(p: Process): String {
        p.shadowSave("Bursa", "101", draft("Ulu Cami"))
        p.shadowSave("Ankara", "import-a", draft("Anıtkabir", isAI = true))
        return localStorage(Triple("Bursa", 101L, draft("Ulu Cami")), Triple("Ankara", "import-a", draft("Anıtkabir", isAI = true)))
    }

    @Test
    fun releaseBuildsNeverReadTheModeAsOnNorSwitchIt() = runBlocking {
        flags.stored = RoomFirstFlag(enabled = true, backupId = "rf-backup-1")
        val p = Process(now = 1_000L, available = false)
        val ls = consistentPair(p)
        assertFalse(p.switch.isOn())
        assertFalse(p.switch.modeJson().getBoolean("on"))
        assertFalse(p.switch.modeJson().getBoolean("available"))
        assertEquals(RoomFirstSwitchOutcome.UNAVAILABLE, p.switch.enable(ls).code)
        assertEquals(RoomFirstSwitchOutcome.UNAVAILABLE, p.switch.disable(ls).code)
        try {
            p.writer.write(RoomFirstWrite.parse("""{"op":"DELETE","city":"Bursa","base":0,"id":101}"""))
            fail("release build accepted a Room-first write")
        } catch (_: RoomBridgeFailure) {
        }
        assertTrue(backupDir.listFiles().isNullOrEmpty())
        assertEquals(0, flags.writes)
    }

    @Test
    fun everyKindOfDifferenceRefusesTheSwitch() = runBlocking {
        val p = Process(now = 1_000L)
        val clean = consistentPair(p)
        assertTrue(p.switch.preflight(clean).preflight!!.clean)

        fun refused(ls: String, blocker: String) = runBlocking {
            val outcome = p.switch.enable(ls)
            assertEquals(blocker, RoomFirstSwitchOutcome.REFUSED, outcome.code)
            assertTrue(blocker, outcome.preflight!!.blockers.getValue(blocker) > 0)
            assertFalse(p.switch.isOn())
        }
        refused(localStorage(Triple("Bursa", 101L, draft("Ulu Cami")), Triple("Ankara", "import-a", draft("Anıtkabir (yeni)", isAI = true))), "contentMismatch")
        refused(localStorage(Triple("Bursa", 101L, draft("Ulu Cami")), Triple("Ankara", "import-a", draft("Anıtkabir", isAI = true)),
            Triple("İzmir", 5L, draft("Yalnız telefonda"))), "missingInRoom")
        refused(localStorage(Triple("Bursa", 101L, draft("Ulu Cami"))), "onlyInRoom")
        assertTrue(backupDir.listFiles().isNullOrEmpty())
        assertEquals(0, flags.writes)
    }

    @Test
    fun unreadableLocalCopyFailsWithoutSwitching() = runBlocking {
        val p = Process(now = 1_000L)
        consistentPair(p)
        try {
            p.switch.enable("not json")
            fail("accepted unreadable notes")
        } catch (e: RoomBridgeFailure) {
            assertEquals("Invalid legacy notes payload", e.message)
        }
        assertFalse(p.switch.isOn())
        assertTrue(backupDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun tombstonesUnknownOriginNewerRoomAndPendingWritesAlsoRefuse() = runBlocking {
        val p = Process(now = 1_000L)
        val ls = consistentPair(p)
        // Tombstone in Room for a note still on the phone: never revived, so the switch waits.
        p.sequencer.delete("Bursa", "101", p.sequencer.nextTicket())
        val tomb = p.switch.enable(ls)
        assertEquals(RoomFirstSwitchOutcome.REFUSED, tomb.code)
        assertEquals(1, tomb.preflight!!.blockers.getValue("tombstoneConflict"))

        // A shadow write cut off by a crash: its journal entry is still pending.
        val p2 = Process(now = 2_000L)
        p2.shadowSave("Ankara", "import-a", draft("Anıtkabir v2", isAI = true), completes = false)
        val pending = p2.switch.enable(localStorage(Triple("Ankara", "import-a", draft("Anıtkabir", isAI = true))))
        assertEquals(RoomFirstSwitchOutcome.REFUSED, pending.code)
        assertTrue(pending.preflight!!.blockers.getValue("unresolvedJournal") > 0)
        assertFalse(p2.switch.isOn())
    }

    @Test
    fun unknownOriginAndRoomNewerRowsRefuse() = runBlocking {
        val p = Process(now = 1_000L)
        // A v1-era row: AI origin and change time were never recorded.
        p.db.notes().upsert(NoteEntity(id = "Bursa::101", city = "Bursa", title = "Ulu Cami", content = "Ulu Cami içerik",
            category = "Gezi", star = 3, dateLabel = "06 Kas 2026", writeTicket = 10L, isAI = null, modifiedAt = null))
        val unknown = p.switch.enable(localStorage(Triple("Bursa", 101L, draft("Ulu Cami"))))
        assertEquals(RoomFirstSwitchOutcome.REFUSED, unknown.code)
        assertEquals(1, unknown.preflight!!.blockers.getValue("unknownOrigin"))

        // localStorage lost its last writes: the journal shows Room holds the newer version.
        val q = Process(now = 2_000L)
        q.shadowSave("İzmir", "7", draft("eski"))
        q.shadowSave("İzmir", "7", draft("yeni"))
        val newer = q.switch.enable(localStorage(Triple("Bursa", 101L, draft("Ulu Cami")), Triple("İzmir", 7L, draft("eski"))))
        assertEquals(RoomFirstSwitchOutcome.REFUSED, newer.code)
        assertEquals(1, newer.preflight!!.blockers.getValue("roomNewer"))
        assertEquals("yeni", q.db.notes().findAny("İzmir::7")!!.title)
    }

    @Test
    fun cleanAuditTakesAVerifiedBackupBeforeTheFlag() = runBlocking {
        val p = Process(now = 1_000L)
        val ls = consistentPair(p)
        // A tombstone without a phone copy is not a difference; it is backed up like any row.
        p.sequencer.delete("İzmir", "9", p.sequencer.nextTicket())
        val ls2 = ls
        val outcome = p.switch.enable(ls2)
        assertEquals(RoomFirstSwitchOutcome.ENABLED, outcome.code)
        assertTrue(p.switch.isOn())
        val backup = outcome.backup!!
        assertEquals(backup.id, flags.stored.backupId)
        assertEquals(2, backup.legacyNotes)
        assertEquals(3, backup.roomRows)

        val content = p.backups.read(backup.id)!!
        assertEquals(ls2, content.localStorage)
        assertEquals(p.rows().sortedBy { it.id }, content.rows.sortedBy { it.id })
        assertNotNull(content.rows.single { it.id == "İzmir::9" }.deletedAt)

        val again = Process(now = 2_000L).switch.enable(ls2)
        assertEquals(RoomFirstSwitchOutcome.ALREADY_ON, again.code)
        assertEquals(backup.id, again.backup!!.id)
        assertEquals(1, p.backups.ids().size)
    }

    @Test
    fun aTamperedOrTruncatedBackupNoLongerVerifies() = runBlocking {
        val p = Process(now = 1_000L)
        val backup = p.switch.enable(consistentPair(p)).backup!!
        val file = File(backupDir, "${backup.id}.json")
        file.writeText(file.readText().replace("Ulu Cami", "Ulu Camii"))
        assertNull(p.backups.verify(backup.id))
        val status = p.switch.status().getJSONArray("backups").getJSONObject(0)
        assertEquals(backup.id, status.getString("id"))
        assertFalse(status.getBoolean("ok"))
        file.writeText("")
        assertNull(p.backups.read(backup.id))
        assertNull(p.backups.verify("../../databases/city_notes"))
    }

    @Test
    fun aSwitchCutOffBeforeTheFlagLeavesTheOldModeAndCanBeRetried() = runBlocking {
        val p = Process(now = 1_000L)
        val ls = consistentPair(p)
        flags.failNextWrite = true
        try {
            p.switch.enable(ls)
            fail("expected the simulated crash")
        } catch (_: IllegalStateException) {
        }
        val restarted = Process(now = 2_000L)
        assertFalse(restarted.switch.isOn())
        assertEquals(1, restarted.backups.ids().size)
        val retried = restarted.switch.enable(ls)
        assertEquals(RoomFirstSwitchOutcome.ENABLED, retried.code)
        // The first backup is kept; backups are never deleted.
        assertEquals(2, restarted.backups.ids().size)
        assertTrue(restarted.backups.ids().all { restarted.backups.verify(it) != null })
    }

    @Test
    fun switchingBackNeedsAPhoneCopyThatMatchesRoom() = runBlocking {
        val p = Process(now = 1_000L)
        val before = consistentPair(p)
        assertEquals(RoomFirstSwitchOutcome.ENABLED, p.switch.enable(before).code)
        val bursa = p.db.notes().findAny("Bursa::101")!!
        val edit = JSONObject().put("op", "UPSERT").put("city", "Bursa").put("base", bursa.writeTicket)
            .put("note", noteJson(101L, draft("Ulu Cami", "Room-first düzenlemesi"))).toString()
        assertEquals(RoomFirstResult.COMMITTED, p.writer.write(RoomFirstWrite.parse(edit)).status)

        // The frozen pre-switch copy no longer matches Room: refusing keeps the edit reachable.
        val stale = p.switch.disable(before)
        assertEquals(RoomFirstSwitchOutcome.REFUSED, stale.code)
        assertEquals(1, stale.preflight!!.blockers.getValue("contentMismatch"))
        assertTrue(p.switch.isOn())

        val rebuilt = copyFromRoom(p.rows())
        val restarted = Process(now = 3_000L)
        assertEquals(RoomFirstSwitchOutcome.DISABLED, restarted.switch.disable(rebuilt).code)
        assertFalse(restarted.switch.isOn())
        assertEquals(RoomFirstSwitchOutcome.ALREADY_OFF, restarted.switch.disable(rebuilt).code)
        try {
            restarted.writer.write(RoomFirstWrite.parse(edit))
            fail("write accepted after switching off")
        } catch (e: RoomBridgeFailure) {
            assertEquals("Room-first mode is off", e.message)
        }
        // Back in production mode the rebuilt copy is consistent: nothing to repair or report.
        val report = restarted.db.reconcileWithLegacy(restarted.sequencer, rebuilt, apply = true) { 3_000L }
        assertEquals(0, report.items.size)
        assertEquals("Room-first düzenlemesi", restarted.db.notes().findAny("Bursa::101")!!.content)
    }

    @Test
    fun auditLogLineAndJsonCarryCountsOnly() = runBlocking {
        val p = Process(now = 1_000L)
        consistentPair(p)
        val outcome = p.switch.preflight(localStorage(Triple("Bursa", 101L, draft("Gizli başlık", "gizli içerik"))))
        val text = outcome.preflight!!.logLine() + outcome.toJson().toString()
        listOf("Gizli", "gizli", "Bursa::", "Ankara", "Ulu Cami").forEach { assertFalse(it, text.contains(it)) }
        assertFalse(outcome.preflight!!.clean)
    }
}
