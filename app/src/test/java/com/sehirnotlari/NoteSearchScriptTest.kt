package com.sehirnotlari

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import java.io.File

class NoteSearchScriptTest {
    @Test
    fun filtersTurkishDateCityHashtagAndStars() {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            val script = listOf(File("src/main/assets/note-search.js"), File("app/src/main/assets/note-search.js")).first { it.isFile }.readText()
            cx.evaluateString(scope, script, "note-search.js", 1, null)
            fun eval(source: String) = cx.evaluateString(scope, source, "test", 1, null)
            assertEquals("2026-11-19", Context.toString(eval("NoteSearch.dateKey('19 Kas 2026')")))
            assertEquals("2026-02-03", Context.toString(eval("NoteSearch.dateKey('03 Şub 2026')")))
            eval("var n={title:'İzmir #Kahve',content:'Güzel mekan',star:4,date:'19 Kas 2026',category:'Gezi'}")
            assertTrue(Context.toBoolean(eval("NoteSearch.matches('İzmir',n,{query:'izmir',city:'İzmir',tag:'kahve',from:'2026-11-01',to:'2026-11-30',minStar:4})")))
            assertFalse(Context.toBoolean(eval("NoteSearch.matches('İzmir',n,{tag:'kahve',minStar:5})")))
            assertFalse(Context.toBoolean(eval("NoteSearch.matches('İzmir',n,{from:'2026-12-01'})")))
            assertFalse(Context.toBoolean(eval("NoteSearch.matches('İzmir',n,{city:'Ankara'})")))
        } finally {
            Context.exit()
        }
    }
}
