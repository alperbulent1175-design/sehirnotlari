package com.sehirnotlari.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deletion with undo in assets/note-delete.js: the saved copy and Room only change once a deletion is final. */
class NoteDeleteScriptTest {
    private val page = NoteDeleteJs()
    private val original =
        """{"İstanbul":[{"id":1762380000000101,"title":"Galata","content":"a","category":"Gezi","star":4,"color":"","fav":true,"date":"01 Kas 2026"}],""" +
            """"Bursa":[{"id":1700000000000,"title":"Eski","content":"b","category":"Genel","star":0,"color":"","fav":false,"date":"02 Kas 2026"},""" +
            """{"id":"import-a","title":"Metin kimlik","content":"c","category":"Lezzet","star":2,"color":"#fef3c7","fav":false,"date":"03 Kas 2026"},""" +
            """{"id":1762380000000102,"title":"İskender","content":"d","category":"Lezzet","star":5,"color":"","fav":false,"date":"04 Kas 2026"}],""" +
            """"Ankara":[{"id":1762380000000201,"title":"Anıtkabir","content":"e","category":"Kültür","star":5,"color":"","fav":false,"date":"05 Kas 2026"}]}"""

    @After
    fun tearDown() = page.close()

    @Test
    fun removeHidesTheNoteButLeavesSavedCopyAndRoomUntouched() {
        page.seed(original)
        page.remove("Bursa", "'import-a'")
        assertEquals("İstanbul/Galata,Bursa/Eski,Bursa/İskender,Ankara/Anıtkabir", page.str("titles(DB)"))
        assertEquals(original, page.stored())
        assertEquals(original, page.str("JSON.stringify(ctrl.view(DB))"))
        assertTrue(page.roomDeletes().isEmpty())
        val marker = org.json.JSONObject(page.marker()!!)
        assertEquals("Bursa", marker.getString("city"))
        assertEquals("import-a", marker.getString("id"))
        assertEquals("Metin kimlik", marker.getString("title"))
        assertEquals("removed:Metin kimlik", page.str("events.join('|')"))
        assertEquals(8000.0, page.num("timers[0].ms"), 0.0)
    }

    @Test
    fun otherSavesInsideTheWindowKeepThePendingNoteInTheSavedCopy() {
        page.seed(original)
        page.remove("Bursa", "1700000000000")
        page.js("DB['Ankara'][0].title = 'Anıtkabir (düzenlendi)'; sv();")
        assertEquals(original.replace("\"Anıtkabir\"", "\"Anıtkabir (düzenlendi)\""), page.stored())
        assertTrue(page.roomDeletes().isEmpty())
    }

    @Test
    fun undoRestoresExactOrderWithoutAnyWrite() {
        page.seed(original)
        page.remove("Bursa", "'import-a'")
        page.js("storage.setCalls = 0; var realSet = storage.set; storage.set = function (k, v) { storage.setCalls++; realSet(k, v); };")
        assertTrue(page.bool("ctrl.undo()"))
        assertEquals(original, page.str("JSON.stringify(DB)"))
        assertEquals(original, page.stored())
        assertEquals(0.0, page.num("storage.setCalls"), 0.0)
        assertNull(page.marker())
        assertTrue(page.roomDeletes().isEmpty())
        assertFalse(page.bool("timers[0].live"))
        page.fireTimers()
        assertTrue(page.roomDeletes().isEmpty())
        assertFalse(page.bool("ctrl.undo()"))
    }

    @Test
    fun lastNoteOfACityComesBackAtTheSameKeyPosition() {
        page.seed(original)
        page.remove("İstanbul", "1762380000000101")
        assertFalse(page.bool("Object.prototype.hasOwnProperty.call(DB, 'İstanbul')"))
        assertEquals(original, page.stored())
        page.js("sv()")
        assertEquals(original, page.stored())
        page.js("ctrl.undo()")
        assertEquals(original, page.str("JSON.stringify(DB)"))
    }

    @Test
    fun timerMakesTheDeletionFinalInSavedCopyThenRoom() {
        page.seed(original)
        page.remove("Bursa", "1762380000000102")
        page.fireTimers()
        val saved = org.json.JSONObject(page.stored()!!)
        assertEquals(2, saved.getJSONArray("Bursa").length())
        assertFalse(page.stored()!!.contains("İskender"))
        assertEquals(listOf("Bursa::1762380000000102"), page.roomDeletes())
        assertNull(page.marker())
        assertEquals("removed:İskender|committed:İskender", page.str("events.join('|')"))
        assertFalse(page.bool("ctrl.undo()"))
        assertNull(page.js("ctrl.pending()"))
    }

    @Test
    fun aSecondDeletionFinishesTheFirstOne() {
        page.seed(original)
        page.remove("Bursa", "1700000000000")
        page.remove("Ankara", "1762380000000201")
        assertEquals(listOf("Bursa::1700000000000"), page.roomDeletes())
        assertFalse(page.stored()!!.contains("\"Eski\""))
        assertTrue(page.stored()!!.contains("Anıtkabir"))
        assertEquals("Ankara", org.json.JSONObject(page.marker()!!).getString("city"))
        page.js("ctrl.undo()")
        assertTrue(page.str("titles(DB)").contains("Ankara/Anıtkabir"))
        assertFalse(page.str("titles(DB)").contains("Bursa/Eski"))
    }

    @Test
    fun appStoppedInsideTheWindowKeepsTheNoteAtNextStart() {
        page.seed(original)
        page.remove("Bursa", "1762380000000102")
        page.boot() // process killed: no timer, no visibility event
        assertEquals(original, page.str("JSON.stringify(DB)"))
        assertEquals("kept|Bursa|İskender", page.str("(function(){var r = ctrl.recover(); return [r.state, r.city, r.title].join('|');})()"))
        assertNull(page.marker())
        assertTrue(page.roomDeletes().isEmpty())
        assertEquals(original, page.stored())
        assertNull(page.js("ctrl.recover()"))
    }

    @Test
    fun appStoppedBetweenSavedCopyAndRoomSendsTheRoomDeleteAtNextStart() {
        page.seed(original)
        page.remove("Bursa", "1762380000000102")
        page.js("mirrorFails = true; ctrl.commit(); mirrorFails = false;")
        assertFalse(page.stored()!!.contains("İskender"))
        assertNotNull(page.marker())
        assertTrue(page.roomDeletes().isEmpty())
        page.boot()
        assertEquals("finalized", page.str("ctrl.recover().state"))
        assertEquals(listOf("Bursa::1762380000000102"), page.roomDeletes())
        assertNull(page.marker())
    }

    @Test
    fun failedSaveBringsTheNoteBackAndTellsRoomNothing() {
        page.seed(original)
        page.remove("Bursa", "'import-a'")
        page.js("failSet['tur_v10'] = true;")
        assertFalse(page.bool("ctrl.commit()"))
        assertEquals(original, page.str("JSON.stringify(DB)"))
        assertEquals(original, page.stored())
        assertTrue(page.roomDeletes().isEmpty())
        assertNull(page.marker())
        assertTrue(page.str("events.join('|')").endsWith("failed:Metin kimlik"))
    }

    @Test
    fun unwritableMarkerRefusesTheDeletion() {
        page.seed(original)
        page.js("failSet[NoteDelete.MARKER_KEY] = true;")
        assertNull(page.remove("Bursa", "'import-a'"))
        assertEquals(original, page.str("JSON.stringify(DB)"))
        assertNull(page.js("ctrl.pending()"))
        assertEquals(0.0, page.num("timers.length"), 0.0)
    }

    @Test
    fun unknownNoteAndBrokenMarkerChangeNothing() {
        page.seed(original)
        assertNull(page.remove("Bursa", "42"))
        assertNull(page.remove("Kars", "1"))
        assertNull(page.marker())
        page.js("store[NoteDelete.MARKER_KEY] = '{bozuk'; ")
        assertEquals("invalid", page.str("ctrl.recover().state"))
        assertNull(page.marker())
        page.js("store[NoteDelete.MARKER_KEY] = JSON.stringify({ city: 'Bursa', id: '' });")
        assertEquals("invalid", page.str("ctrl.recover().state"))
        assertEquals(original, page.stored())
        assertTrue(page.roomDeletes().isEmpty())
    }

    @Test
    fun legacyNumberAndTextIdsMatchTheMarker() {
        page.seed(original)
        page.remove("Bursa", "'1700000000000'")
        assertEquals("1700000000000", org.json.JSONObject(page.marker()!!).getString("id"))
        page.fireTimers()
        assertEquals(listOf("Bursa::1700000000000"), page.roomDeletes())
    }
}
