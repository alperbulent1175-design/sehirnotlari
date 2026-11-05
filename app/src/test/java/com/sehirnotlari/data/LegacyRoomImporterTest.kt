package com.sehirnotlari.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacyRoomImporterTest {
    @Test
    fun firstImportInsertsLegacyNote() = runBlocking {
        val store = InMemoryNoteImportStore()
        val outcome = store.withTransaction {
            LegacyRoomImporter.importOrThrow(legacyPayload("Bursa", 101, "Ulu Cami"), store)
        }
        assertEquals(1, outcome.inserted)
        assertEquals(0, outcome.skipped)
        val saved = store.findAny("Bursa::101")
        assertEquals("Ulu Cami", saved?.title)
        assertEquals("Bursa", saved?.city)
        assertNull(saved?.deletedAt)
    }

    @Test
    fun reimportDoesNotDuplicate() = runBlocking {
        val store = InMemoryNoteImportStore()
        store.withTransaction {
            LegacyRoomImporter.importOrThrow(legacyPayload("Bursa", 101, "Ulu Cami"), store)
        }
        val outcome = store.withTransaction {
            LegacyRoomImporter.importOrThrow(legacyPayload("Bursa", 101, "Ulu Cami"), store)
        }
        assertEquals(0, outcome.inserted)
        assertEquals(1, outcome.skipped)
        assertEquals(1, store.size)
    }

    @Test
    fun deletedNoteIsNotRecreated() = runBlocking {
        val store = InMemoryNoteImportStore()
        val now = 1_700_000_000_000L
        store.upsert(
            NoteEntity(
                id = "Bursa::101",
                city = "Bursa",
                title = "Ulu Cami",
                deletedAt = now,
                writeTicket = now,
                syncState = "PENDING"
            )
        )
        val outcome = store.withTransaction {
            LegacyRoomImporter.importOrThrow(legacyPayload("Bursa", 101, "Yeniden"), store)
        }
        assertEquals(0, outcome.inserted)
        assertEquals(1, outcome.skipped)
        val kept = store.findAny("Bursa::101")
        assertEquals(now, kept?.deletedAt)
        assertEquals("Ulu Cami", kept?.title)
    }

    @Test
    fun invalidPayloadRollsBackEarlierInserts() = runBlocking {
        val store = InMemoryNoteImportStore()
        try {
            store.withTransaction {
                LegacyRoomImporter.importOrThrow(
                    """{"Bursa":[{"id":101,"title":"Korunmamalı"},{"title":"id yok"}]}""",
                    store
                )
            }
            fail("expected invalid payload to throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("Missing or invalid note id", e.message)
        }
        assertEquals(0, store.size)
        assertNull(store.findAny("Bursa::101"))
    }

    @Test
    fun existingRoomNoteIsNotOverwritten() = runBlocking {
        val store = InMemoryNoteImportStore()
        store.upsert(
            NoteEntity(
                id = "Bursa::101",
                city = "Bursa",
                title = "Orijinal",
                content = "Room'daki metin",
                star = 5
            )
        )
        val outcome = store.withTransaction {
            LegacyRoomImporter.importOrThrow(
                legacyPayload("Bursa", 101, "Eski kopya", content = "localStorage"),
                store
            )
        }
        assertEquals(0, outcome.inserted)
        assertEquals(1, outcome.skipped)
        val kept = store.findAny("Bursa::101")
        assertEquals("Orijinal", kept?.title)
        assertEquals("Room'daki metin", kept?.content)
        assertEquals(5, kept?.star)
    }

    @Test
    fun oversizedPayloadIsRejectedBeforeImport() {
        val json = "x".repeat(LegacyRoomImporter.MAX_PAYLOAD_CHARS + 1)
        assertEquals("Import payload too large", LegacyRoomImporter.oversizedPayloadMessage(json))
    }

    private fun legacyPayload(
        city: String,
        id: Long,
        title: String,
        content: String = "icerik"
    ): String = """{"$city":[{"id":$id,"title":"$title","content":"$content","star":2,"fav":false,"date":"6.11.2026"}]}"""
}

class InMemoryNoteImportStore : NoteImportStore {
    private val notes = linkedMapOf<String, NoteEntity>()

    val size: Int get() = notes.size

    override suspend fun findAny(id: String): NoteEntity? = notes[id]

    override suspend fun upsert(note: NoteEntity) {
        notes[note.id] = note
    }

    suspend fun <T> withTransaction(block: suspend () -> T): T {
        val snapshot = notes.toMap()
        return try {
            block()
        } catch (error: Throwable) {
            notes.clear()
            notes.putAll(snapshot)
            throw error
        }
    }
}
