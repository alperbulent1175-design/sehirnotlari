package com.sehirnotlari.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deletion with undo across both stores: the page (note-delete.js under Rhino) and a real Room file.
 * Every Room delete the page asks for is applied through the bridge's journal and sequencer, and
 * the startup reconciliation must then find localStorage and Room in agreement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteDeleteRoomConsistencyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "note-delete-test.db"
    private var room: CityNotesDatabase? = null
    private lateinit var page: NoteDeleteJs
    private var now = 1_000L

    private val notes = listOf(
        Triple("Bursa", "1762380000000101", RoomNoteDraft("Ulu Cami", "içerik", "Gezi", 4, "", true, "01 Kas 2026")),
        Triple("Bursa", "1762380000000102", RoomNoteDraft("İskender", "içerik", "Lezzet", 5, "#fef3c7", false, "02 Kas 2026")),
        Triple("Ankara", "1762380000000201", RoomNoteDraft("Anıtkabir", "içerik", "Kültür", 5, "", false, "03 Kas 2026")),
    )

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        page = NoteDeleteJs()
    }

    @After
    fun tearDown() {
        page.close()
        room?.close()
        context.deleteDatabase(name)
    }

    private inner class Process {
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

        suspend fun save(city: String, id: String, draft: RoomNoteDraft) {
            val ticket = sequencer.nextTicket()
            journal.recordUpsert(ticket, city, id, draft)
            journal.resolve(ticket, sequencer.upsert(city, id, draft, ticket))
        }

        /** What `deleteRoomNote` does for each request the page sent. */
        suspend fun applyPageDeletes(): List<RoomWriteAck> = page.roomDeletes().map { key ->
            val (city, id) = key.split("::", limit = 2)
            val ticket = sequencer.nextTicket()
            journal.recordDelete(ticket, city, id)
            sequencer.delete(city, id, ticket).also { journal.resolve(ticket, it) }
        }.also { page.js("roomDeletes = [];") }

        suspend fun reconcile() = db.reconcileWithLegacy(sequencer, page.stored()!!, apply = false, cutoff = Long.MAX_VALUE) { now }
        suspend fun row(key: String) = db.notes().findAny(key)
    }

    private fun localStorageJson(): String {
        val root = org.json.JSONObject()
        notes.forEach { (city, id, d) ->
            val list = root.optJSONArray(city) ?: org.json.JSONArray().also { root.put(city, it) }
            list.put(
                org.json.JSONObject().put("id", id.toLong()).put("title", d.title).put("content", d.content)
                    .put("category", d.category).put("star", d.star).put("color", d.color).put("fav", d.favorite)
                    .put("date", d.dateLabel).put("isAI", d.isAI)
            )
        }
        return root.toString()
    }

    private suspend fun start(): Process {
        val p = Process()
        notes.forEach { (city, id, d) -> p.save(city, id, d) }
        page.seed(localStorageJson())
        assertTrue(p.reconcile().items.isEmpty())
        return p
    }

    @Test
    fun undoNeedsNoRoomWriteAndBothSidesStayEqual() = runBlocking {
        val p = start()
        page.remove("Bursa", "1762380000000102")
        assertTrue("inside the window", p.reconcile().items.isEmpty())
        page.js("ctrl.undo()")
        assertTrue(p.applyPageDeletes().isEmpty())
        assertTrue(p.reconcile().items.isEmpty())
        assertNull(p.row("Bursa::1762380000000102")!!.deletedAt)
    }

    @Test
    fun finalDeletionTombstonesRoomAndBothSidesStayEqual() = runBlocking {
        val p = start()
        page.remove("Bursa", "1762380000000102")
        page.fireTimers()
        assertEquals(listOf(RoomWriteAck.Applied), p.applyPageDeletes())
        assertTrue(p.reconcile().items.isEmpty())
        val row = p.row("Bursa::1762380000000102")!!
        assertNotNull(row.deletedAt)
        assertEquals("İskender", row.title)
    }

    @Test
    fun undoThroughRoomAfterADeleteWouldBeRefused() = runBlocking {
        val p = start()
        page.remove("Bursa", "1762380000000102")
        page.fireTimers()
        p.applyPageDeletes()
        now += 10_000
        val revived = p.sequencer.upsert("Bursa", "1762380000000102", notes[1].third, p.sequencer.nextTicket())
        assertTrue(revived is RoomWriteAck.Rejected)
        assertNotNull(p.row("Bursa::1762380000000102")!!.deletedAt)
    }

    @Test
    fun crashInsideTheWindowLosesNothingAfterRestart() = runBlocking {
        start()
        page.remove("Ankara", "1762380000000201")
        page.boot()
        val after = Process()
        assertEquals("kept", page.str("ctrl.recover().state"))
        assertTrue(after.applyPageDeletes().isEmpty())
        assertTrue(after.reconcile().items.isEmpty())
        assertNull(after.row("Ankara::1762380000000201")!!.deletedAt)
        assertTrue(page.stored()!!.contains("Anıtkabir"))
    }

    @Test
    fun crashBeforeTheRoomDeleteIsRepairedAtNextStart() = runBlocking {
        start()
        page.remove("Bursa", "1762380000000101")
        page.js("mirrorFails = true; ctrl.commit(); mirrorFails = false;")
        page.boot()
        val after = Process()
        val beforeRecovery = after.reconcile().items
        assertEquals(listOf(ReconcileKind.ONLY_IN_ROOM), beforeRecovery.map { it.kind })
        assertEquals("finalized", page.str("ctrl.recover().state"))
        assertEquals(listOf(RoomWriteAck.Applied), after.applyPageDeletes())
        assertTrue(after.reconcile().items.isEmpty())
    }

    @Test
    fun repeatedRoomDeleteIsHarmless() = runBlocking {
        val p = start()
        page.remove("Bursa", "1762380000000101")
        page.fireTimers()
        p.applyPageDeletes()
        val first = p.row("Bursa::1762380000000101")!!
        page.js("roomDeletes.push('Bursa::1762380000000101');")
        now += 5_000
        assertEquals(listOf(RoomWriteAck.Applied), p.applyPageDeletes())
        assertEquals(first, p.row("Bursa::1762380000000101"))
        assertTrue(p.reconcile().items.isEmpty())
    }
}
