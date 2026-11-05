package com.sehirnotlari.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomNoteKeysTest {
    @Test
    fun legacyNumericIdKeepsCityScopedKey() {
        val id = RoomNoteKeys.idFromJson(JSONObject("""{"id":1700000000000}""").opt("id"))
        assertEquals("1700000000000", id)
        assertEquals("Bursa::1700000000000", RoomNoteKeys.key("Bursa", id!!))
    }

    @Test
    fun newMicrosecondIdRoundTripsAsJsSafeNumber() {
        val id = RoomNoteKeys.idFromJson(JSONObject("""{"id":1762380000000123}""").opt("id"))
        assertEquals("1762380000000123", id)
        val note = NoteEntity(id = RoomNoteKeys.key("Bursa", id!!), city = "Bursa", title = "")
        assertEquals(1762380000000123L, RoomNoteKeys.webViewId(note))
    }

    @Test
    fun textIdsStayText() {
        assertEquals("import-a", RoomNoteKeys.idFromJson("import-a"))
        assertEquals("import-a", RoomNoteKeys.webViewId(NoteEntity(id = "Bursa::import-a", city = "Bursa", title = "")))
        assertEquals("007", RoomNoteKeys.webViewId(NoteEntity(id = "Bursa::007", city = "Bursa", title = "")))
        assertEquals("9007199254740993", RoomNoteKeys.webViewId(NoteEntity(id = "Bursa::9007199254740993", city = "Bursa", title = "")))
    }

    @Test
    fun unusableIdsAreRejected() {
        assertNull(RoomNoteKeys.idFromJson(null))
        assertNull(RoomNoteKeys.idFromJson(JSONObject.NULL))
        assertNull(RoomNoteKeys.idFromJson(""))
        assertNull(RoomNoteKeys.idFromJson("   "))
        assertNull(RoomNoteKeys.idFromJson(Double.NaN))
        assertNull(RoomNoteKeys.idFromJson(JSONObject()))
    }

    @Test
    fun integralDoubleMatchesJavaScriptString() {
        assertEquals("5", RoomNoteKeys.idFromJson(5.0))
        assertEquals("1.5", RoomNoteKeys.idFromJson(1.5))
    }

    @Test
    fun sameMillisecondNotesImportAsSeparateRoomRows() = runBlocking {
        val store = InMemoryNoteImportStore()
        val outcome = store.withTransaction {
            LegacyRoomImporter.importOrThrow(
                """{"Bursa":[{"id":1762380000000123,"title":"A"},{"id":1762380000000124,"title":"B"},{"id":1700000000000,"title":"Eski"}]}""",
                store
            )
        }
        assertEquals(3, outcome.inserted)
        assertEquals("A", store.findAny("Bursa::1762380000000123")?.title)
        assertEquals("B", store.findAny("Bursa::1762380000000124")?.title)
        assertEquals("Eski", store.findAny("Bursa::1700000000000")?.title)
    }

    @Test
    fun sequencerUsesSameKeyForLegacyAndNewIds() = runBlocking {
        val store = InMemoryNoteWriteStore()
        val sequencer = RoomNoteWriteSequencer(store)
        sequencer.upsert("Bursa", "1700000000000", RoomNoteDraft(title = "Eski"), sequencer.nextTicket())
        sequencer.upsert("Bursa", "1762380000000123", RoomNoteDraft(title = "Yeni"), sequencer.nextTicket())
        sequencer.delete("Bursa", "1700000000000", sequencer.nextTicket())
        assertEquals("Yeni", store.findAny("Bursa::1762380000000123")?.title)
        assertEquals(true, store.findAny("Bursa::1700000000000")?.deletedAt != null)
    }
}
