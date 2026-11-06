package com.sehirnotlari.data

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFingerprintTest {
    private val base = RoomNoteDraft(title = "Başlık", content = "İçerik", category = "Gezi", star = 3,
        color = "#e74c3c", favorite = false, dateLabel = "06 Kas 2026", isAI = false)

    @Test
    fun everyComparedFieldChangesTheFingerprint() {
        val variants = listOf(
            base,
            base.copy(title = "Başlık2"),
            base.copy(content = "İçerik2"),
            base.copy(category = "Lezzet"),
            base.copy(star = 4),
            base.copy(color = "#3498db"),
            base.copy(favorite = true),
            base.copy(isAI = true),
            base.copy(dateLabel = "07 Kas 2026"),
        )
        assertEquals(variants.size, variants.map(NoteFingerprint::of).toSet().size)
        assertEquals(NoteFingerprint.of(base), NoteFingerprint.of(base.copy()))
    }

    @Test
    fun fieldBoundariesCannotShift() {
        assertTrue(NoteFingerprint.of(base.copy(title = "ab", content = "c")) != NoteFingerprint.of(base.copy(title = "a", content = "bc")))
    }

    @Test
    fun rowFingerprintIsUnknownWithoutAiOrigin() {
        val row = NoteEntity(id = "Bursa::1", city = "Bursa", title = base.title, content = base.content, category = base.category,
            star = base.star, color = base.color, dateLabel = base.dateLabel, isAI = false)
        assertEquals(NoteFingerprint.of(base), NoteFingerprint.of(row))
        assertNull(NoteFingerprint.of(row.copy(isAI = null)))
    }

    @Test
    fun differingFieldsIgnoresUnknownAiOriginOnly() {
        val row = NoteEntity(id = "Bursa::1", city = "Bursa", title = "Başlık", content = "İçerik", category = "Gezi", star = 3,
            color = "#e74c3c", dateLabel = "06 Kas 2026", isAI = null)
        assertEquals(emptyList<String>(), base.copy(isAI = true).differingFields(row))
        assertEquals(listOf("isAI"), base.copy(isAI = true).differingFields(row.copy(isAI = false)))
        assertEquals(listOf("star", "favorite"), base.copy(star = 5, favorite = true).differingFields(row))
    }

    @Test
    fun webViewNoteIsReadLikeTheBridgeWrite() {
        val draft = RoomNoteDraft.fromJson(JSONObject("""{"id":1,"title":"T","star":9,"fav":true,"date":"d","isAI":true}"""))
        assertEquals(RoomNoteDraft(title = "T", category = "Genel", star = 5, favorite = true, dateLabel = "d", isAI = true), draft)
    }

    @Test
    fun bridgeErrorsNeverEchoForeignMessages() {
        assertEquals("JSONException", RoomBridgeErrors.safe(JSONException("Expected ':' after Gizli başlık")))
        assertEquals("IllegalStateException", RoomBridgeErrors.safe(IllegalStateException("Gizli içerik")))
        assertEquals("Invalid note payload", RoomBridgeErrors.safe(RoomBridgeFailure("Invalid note payload")))
    }
}
