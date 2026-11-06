package com.sehirnotlari

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import java.io.ByteArrayInputStream

/** File checks on the Android side of the JSON backup, and the script that hands results to the page. */
class NotesFileTest {
    private fun refusal(block: () -> Unit): String = assertThrows(NotesFile.Refused::class.java) { block() }.message!!

    @Test
    fun readLimitedStopsJustPastTheLimit() {
        val exact = ByteArray(NotesFile.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(NotesFile.MAX_BYTES, NotesFile.readLimited(ByteArrayInputStream(exact)).size)
        val over = ByteArray(NotesFile.MAX_BYTES + 1)
        assertEquals("Dosya çok büyük (en fazla 5 MB).", refusal { NotesFile.readLimited(ByteArrayInputStream(over)) })
        assertEquals(0, NotesFile.readLimited(ByteArrayInputStream(ByteArray(0))).size)
    }

    @Test
    fun declaredSizeIsCheckedBeforeReading() {
        NotesFile.checkDeclaredSize(null)
        NotesFile.checkDeclaredSize(NotesFile.MAX_BYTES.toLong())
        assertEquals("Dosya çok büyük (en fazla 5 MB).", refusal { NotesFile.checkDeclaredSize(NotesFile.MAX_BYTES + 1L) })
    }

    @Test
    fun decodeAcceptsUtf8JsonObjectsAndDropsTheBom() {
        val json = """{"İzmir":[{"id":1,"title":"Çeşme ğüşıöç"}]}"""
        assertEquals(json, NotesFile.decode(json.toByteArray(Charsets.UTF_8)))
        assertEquals(json, NotesFile.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json.toByteArray(Charsets.UTF_8)))
        assertEquals("{}", NotesFile.decode("{}".toByteArray()))
    }

    @Test
    fun decodeRefusesEmptyCorruptAndForeignFiles() {
        assertEquals("Dosya boş.", refusal { NotesFile.decode(ByteArray(0)) })
        assertEquals("Dosya boş.", refusal { NotesFile.decode(" \n ".toByteArray()) })
        assertEquals("Dosya geçerli bir JSON değil.", refusal { NotesFile.decode("""{"Bursa":[""".toByteArray()) })
        val notNotes = "Bu dosya bir Şehir Notları yedeği değil."
        assertEquals(notNotes, refusal { NotesFile.decode("[1,2]".toByteArray()) })
        assertEquals(notNotes, refusal { NotesFile.decode("hello".toByteArray()) })
        // Latin-1 "ş" and a PNG header are not UTF-8 text.
        assertEquals(notNotes, refusal { NotesFile.decode(byteArrayOf('{'.code.toByte(), 0xFE.toByte(), '}'.code.toByte())) })
        assertEquals(notNotes, refusal { NotesFile.decode(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) })
    }

    @Test
    fun exportBytesAreUtf8AndLimited() {
        val json = """{"Muş":[{"id":1,"content":"ı"}]}"""
        assertArrayEquals(json.toByteArray(Charsets.UTF_8), NotesFile.exportBytes(json))
        assertEquals("Yedek çok büyük (en fazla 5 MB).", refusal { NotesFile.exportBytes("{\"a\":\"" + "x".repeat(NotesFile.MAX_BYTES) + "\"}") })
        assertEquals("Yedek hazırlanamadı.", refusal { NotesFile.exportBytes("not json") })
        assertEquals("Yedek hazırlanamadı.", refusal { NotesFile.exportBytes("[]") })
    }

    @Test
    fun suggestedNameKeepsOnlyPlainJsonNames() {
        assertEquals("turkiye-notlari-2026-11-06.json", NotesFile.suggestedName("turkiye-notlari-2026-11-06.json"))
        assertEquals("Şehir notları (1).json", NotesFile.suggestedName(" Şehir notları (1).json "))
        listOf("../secret.json", "a/b.json", "notes.txt", "notes.json.exe", "", ".json".padStart(90, 'a'), "x\n.json").forEach {
            assertEquals(it, NotesFile.DEFAULT_NAME, NotesFile.suggestedName(it))
        }
    }

    @Test
    fun resultObjectsCarryOnlyStatusAndNeededFields() {
        assertEquals("""{"status":"cancel"}""", NotesFile.cancelled().toString())
        assertEquals("ok", NotesFile.saved("a.json", 12).getString("status"))
        assertEquals(12, NotesFile.saved(null, 12).getInt("bytes"))
        assertEquals("", NotesFile.saved(null, 12).getString("name"))
        assertEquals("Dosya boş.", NotesFile.failed("Dosya boş.").getString("message"))
    }

    @Test
    fun scriptOnlyCallsTheTwoKnownCallbacks() {
        assertThrows(IllegalArgumentException::class.java) { NotesFile.script("alert", NotesFile.cancelled()) }
        assertThrows(IllegalArgumentException::class.java) { NotesFile.script("onNotesFileSaved(1);alert", NotesFile.cancelled()) }
        assertEquals("window.onNotesFileSaved && window.onNotesFileSaved({\"status\":\"cancel\"});", NotesFile.script(NotesFile.SAVED_CALLBACK, NotesFile.cancelled()))
    }

    /** The page receives the file text exactly, whatever it contains. */
    @Test
    fun scriptDeliversHostileTextUnchanged() {
        val text = "{\"Şırnak\":[{\"title\":\"</script><script>alert(1)</script>\",\"content\":\"'\\\"\\\\ \u2028 \u2029 ` \${x} \u0000 ğüşıöçİ 🙂\"}]}\n"
        val script = NotesFile.script(NotesFile.OPENED_CALLBACK, NotesFile.opened("a'b\".json", text))
        assertFalse(script.contains('\u2028'))
        assertFalse(script.contains('\u2029'))
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "var window = {}; window.onNotesFileOpened = function(r){ window.got = r; };", "stub", 1, null)
            cx.evaluateString(scope, script, "callback", 1, null)
            assertEquals(text, Context.toString(cx.evaluateString(scope, "window.got.text", "t", 1, null)))
            assertEquals("a'b\".json", Context.toString(cx.evaluateString(scope, "window.got.name", "t", 1, null)))
            assertEquals("ok", Context.toString(cx.evaluateString(scope, "window.got.status", "t", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun scriptIsHarmlessWhenThePageHasNoCallback() {
        val cx = Context.enter()
        try {
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "var window = {};", "stub", 1, null)
            cx.evaluateString(scope, NotesFile.script(NotesFile.SAVED_CALLBACK, NotesFile.saved("a.json", 1)), "callback", 1, null)
            assertTrue(Context.toString(cx.evaluateString(scope, "String(window.got)", "t", 1, null)) == "undefined")
        } finally {
            Context.exit()
        }
    }
}
