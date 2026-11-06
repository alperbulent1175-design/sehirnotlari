package com.sehirnotlari.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
 * Room-first writes on a real SQLite file. Each [Process] reopens the database the way an app
 * restarted after a force-stop does; a reply only exists for writes whose transaction committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomFirstWriterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "room-first-writer-test.db"
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

    private inner class Process(var now: Long, var on: Boolean = true, beforeCommit: suspend () -> Unit = {}) {
        val db: CityNotesDatabase = run {
            room?.close()
            CityNotesDatabase.builder(context, name).allowMainThreadQueries().build().also { room = it }
        }
        val sequencer = RoomNoteWriteSequencer.forDatabase(db) { now }
        val writer = RoomFirstWriter(db, sequencer, enabled = { on }, beforeCommit = beforeCommit)

        suspend fun write(json: String) = writer.write(RoomFirstWrite.parse(json))
        suspend fun row(key: String) = db.notes().findAny(key)
    }

    private fun upsert(city: String, id: Any, base: Long, title: String, content: String = "$title içerik", fav: Boolean = false) =
        JSONObject().put("op", "UPSERT").put("city", city).put("base", base).put(
            "note",
            JSONObject().put("id", id).put("title", title).put("content", content).put("category", "Gezi")
                .put("star", 2).put("color", "#2471a3").put("fav", fav).put("date", "06 Kas 2026").put("isAI", false)
        ).toString()

    private fun delete(city: String, id: Any, base: Long) =
        JSONObject().put("op", "DELETE").put("city", city).put("base", base).put("id", id).toString()

    @Test
    fun confirmedCreateEditFavoriteAndDeleteAreReadBackAfterRestart() = runBlocking {
        val first = Process(now = 1_000L)
        val created = first.write(upsert("Bursa", 1_762_380_000_000_123L, 0, "Ulu Cami"))
        assertEquals(RoomFirstResult.COMMITTED, created.status)
        assertTrue(created.saved)
        val edited = first.write(upsert("Bursa", 1_762_380_000_000_123L, created.ticket!!, "Ulu Cami", "düzenlendi"))
        val favorite = first.write(upsert("Bursa", 1_762_380_000_000_123L, edited.ticket!!, "Ulu Cami", "düzenlendi", fav = true))
        val other = first.write(upsert("İzmir", "import-a", 0, "Saat Kulesi"))
        val removed = first.write(delete("İzmir", "import-a", other.ticket!!))
        assertEquals(listOf(true, true, true), listOf(edited.saved, favorite.saved, removed.saved))
        assertTrue(created.ticket!! < edited.ticket!! && edited.ticket!! < favorite.ticket!! && other.ticket!! < removed.ticket!!)

        val snapshot = Process(now = 9_000L).writer.snapshot()
        val bursa = snapshot.rows.single { it.id == "Bursa::1762380000000123" }
        assertEquals("düzenlendi", bursa.content)
        assertTrue(bursa.favorite)
        assertEquals(favorite.ticket, bursa.writeTicket)
        assertEquals(false, bursa.isAI)
        val izmir = snapshot.rows.single { it.id == "İzmir::import-a" }
        assertNotNull(izmir.deletedAt)
        assertEquals("Saat Kulesi", izmir.title)
        assertEquals(listOf("Bursa::1762380000000123"), snapshot.active.map { it.id })
        val json = snapshot.toJson().getJSONObject("notes").getJSONArray("Bursa").getJSONObject(0)
        assertEquals(1_762_380_000_000_123L, json.getLong("id"))
        assertEquals(favorite.ticket, json.getLong("ticket"))
    }

    @Test
    fun crashBeforeCommitLeavesNothingAndTheSameRequestAppliesOnRetry() = runBlocking {
        val setup = Process(now = 1_000L)
        val created = setup.write(upsert("Bursa", 7, 0, "İlk"))
        val crashing = Process(now = 2_000L) { throw IllegalStateException("process killed") }
        try {
            crashing.write(upsert("Bursa", 7, created.ticket!!, "Kaybolmamalı"))
            fail("expected the simulated crash")
        } catch (_: IllegalStateException) {
        }
        val reopened = Process(now = 3_000L)
        val untouched = reopened.row("Bursa::7")!!
        assertEquals("İlk", untouched.title)
        assertEquals(created.ticket, untouched.writeTicket)
        assertEquals(1_000L, untouched.modifiedAt)

        val retried = reopened.write(upsert("Bursa", 7, created.ticket!!, "Kaybolmamalı"))
        assertEquals(RoomFirstResult.COMMITTED, retried.status)
        assertEquals("Kaybolmamalı", Process(now = 4_000L).row("Bursa::7")!!.title)
    }

    @Test
    fun resentRequestAfterALostReplyIsNotAppliedTwice() = runBlocking {
        val first = Process(now = 1_000L)
        val request = upsert("Bursa", 7, 0, "Bir kez")
        val committed = first.write(request)
        val stored = first.row("Bursa::7")!!

        val again = Process(now = 5_000L).write(request)
        assertEquals(RoomFirstResult.ALREADY_SAVED, again.status)
        assertTrue(again.saved)
        assertEquals(committed.ticket, again.ticket)
        assertEquals(stored, Process(now = 6_000L).row("Bursa::7"))

        val removal = delete("Bursa", 7, committed.ticket!!)
        val removed = Process(now = 7_000L).write(removal)
        assertEquals(RoomFirstResult.COMMITTED, removed.status)
        val tombstone = room!!.notes().findAny("Bursa::7")!!
        val removedAgain = Process(now = 8_000L).write(removal)
        assertEquals(RoomFirstResult.ALREADY_DELETED, removedAgain.status)
        assertTrue(removedAgain.saved)
        assertEquals(tombstone, room!!.notes().findAny("Bursa::7"))
    }

    @Test
    fun aRequestMadeFromAnOlderVersionChangesNothing() = runBlocking {
        val p = Process(now = 1_000L)
        val v1 = p.write(upsert("Bursa", 7, 0, "v1"))
        val v2 = p.write(upsert("Bursa", 7, v1.ticket!!, "v2"))
        val stale = p.write(upsert("Bursa", 7, v1.ticket!!, "eski sekmeden"))
        assertEquals(RoomFirstResult.CONFLICT, stale.status)
        assertFalse(stale.saved)
        assertEquals("v2", stale.row!!.title)
        assertEquals(v2.ticket, p.row("Bursa::7")!!.writeTicket)

        val takenId = p.write(upsert("Bursa", 7, 0, "aynı kimlikli yeni not"))
        assertEquals(RoomFirstResult.CONFLICT, takenId.status)
        val staleDelete = p.write(delete("Bursa", 7, v1.ticket!!))
        assertEquals(RoomFirstResult.CONFLICT, staleDelete.status)
        assertNull(p.row("Bursa::7")!!.deletedAt)
        assertEquals(RoomFirstResult.CONFLICT, p.write(upsert("Bursa", 99, 123L, "Room'da hiç olmayan")).status)
        assertNull(p.row("Bursa::99"))
    }

    @Test
    fun tombstonesAreNeverRevivedByANewWrite() = runBlocking {
        val p = Process(now = 1_000L)
        val created = p.write(upsert("Bursa", 7, 0, "Silinecek"))
        val removed = p.write(delete("Bursa", 7, created.ticket!!))
        val tombstone = p.row("Bursa::7")!!

        listOf(0L, created.ticket!!, removed.ticket!!, Long.MAX_VALUE).forEach { base ->
            val revive = Process(now = 9_000L).write(upsert("Bursa", 7, base, "Silinecek"))
            assertEquals(RoomFirstResult.DELETED, revive.status)
            assertFalse(revive.saved)
            assertTrue(revive.toJson().getJSONObject("row").getBoolean("deleted"))
        }
        assertEquals(tombstone, room!!.notes().findAny("Bursa::7"))

        // A legacy shadow tombstone (from P6 production writes) blocks Room-first writes too.
        val legacy = Process(now = 10_000L)
        legacy.sequencer.delete("Ankara", "5", legacy.sequencer.nextTicket())
        assertEquals(RoomFirstResult.DELETED, legacy.write(upsert("Ankara", 5, 0, "yeni")).status)
        assertEquals(RoomFirstResult.ALREADY_DELETED, legacy.write(delete("Ankara", 6, 0)).status)
        assertNull(legacy.row("Ankara::6"))
    }

    @Test
    fun fourHundredRapidEditsKeepOrderTicketsAndTheLastVersion() = runBlocking {
        val p = Process(now = 1_000L)
        var base = 0L
        val tickets = mutableListOf<Long>()
        repeat(400) { i ->
            val result = p.write(upsert("Ankara", 42, base, "Not", "düzenleme $i"))
            assertEquals(RoomFirstResult.COMMITTED, result.status)
            tickets += result.ticket!!
            base = result.ticket!!
        }
        assertEquals(tickets.sorted(), tickets)
        assertEquals(400, tickets.toSet().size)
        val reopened = Process(now = 2_000L)
        val row = reopened.row("Ankara::42")!!
        assertEquals("düzenleme 399", row.content)
        assertEquals(tickets.last(), row.writeTicket)
        assertEquals(1_000L, row.modifiedAt)
        assertTrue(reopened.sequencer.nextTicket() > tickets.last())
    }

    @Test
    fun concurrentRequestsFromTheSameVersionApplyExactlyOnce() = runBlocking {
        val p = Process(now = 1_000L)
        val created = p.write(upsert("Ankara", 42, 0, "Başlangıç"))
        val results = (1..20).map { i ->
            async { p.write(upsert("Ankara", 42, created.ticket!!, "yarış $i")) }
        }.awaitAll()
        assertEquals(1, results.count { it.status == RoomFirstResult.COMMITTED })
        assertEquals(19, results.count { it.status == RoomFirstResult.CONFLICT })
        val winner = results.single { it.status == RoomFirstResult.COMMITTED }
        assertEquals(winner.row!!.content, p.row("Ankara::42")!!.content)
    }

    @Test
    fun writesAreRefusedOnceTheModeIsOff() = runBlocking {
        val p = Process(now = 1_000L, on = false)
        try {
            p.write(upsert("Bursa", 7, 0, "Kapalıyken"))
            fail("expected a refusal")
        } catch (e: RoomBridgeFailure) {
            assertEquals("Room-first mode is off", e.message)
        }
        assertNull(p.row("Bursa::7"))
    }

    @Test
    fun malformedRequestsFailWithoutEchoingTheirContent() {
        listOf(
            "not json gizli-metin",
            """{"op":"UPSERT","city":"Bursa","base":0}""",
            """{"op":"DELETE","city":"","base":0,"id":1}""",
            """{"op":"DROP","city":"Bursa","base":0,"id":1}""",
            """{"op":"DELETE","city":"Bursa","base":-1,"id":1}""",
            """{"op":"UPSERT","city":"Bursa","base":0,"note":{"title":"kimliksiz gizli-metin"}}""",
            "x".repeat(RoomFirstWrite.MAX_PAYLOAD + 1),
        ).forEach { payload ->
            try {
                RoomFirstWrite.parse(payload)
                fail("accepted $payload")
            } catch (e: RoomBridgeFailure) {
                assertEquals("Invalid write payload", RoomBridgeErrors.safe(e))
            }
        }
    }

    @Test
    fun snapshotNamesTheSqliteDurabilitySettings() = runBlocking {
        val snapshot = Process(now = 1_000L).writer.snapshot()
        assertTrue(snapshot.journalMode.isNotBlank())
        assertTrue(snapshot.synchronous in setOf("OFF", "NORMAL", "FULL", "EXTRA"))
        assertTrue(snapshot.logLine().startsWith("active=0 tombstones=0 journalMode="))
    }
}
