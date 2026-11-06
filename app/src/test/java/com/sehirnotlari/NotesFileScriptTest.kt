package com.sehirnotlari

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File

/** Runs the real assets/notes-file.js (with note-ids.js) under Rhino. */
class NotesFileScriptTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        listOf("note-ids.js", "notes-file.js").forEach { cx.evaluateString(scope, assetFile(it).readText(), it, 1, null) }
        defineNotes()
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun str(source: String) = Context.toString(cx.evaluateString(scope, source, "test", 1, null))

    /** parse() of a JS string literal: "ok cities notes" or the error shown to the user. */
    private fun parse(literal: String) =
        str("(function(){ var r = NotesFile.parse($literal); return r.ok ? 'ok ' + r.cities + ' ' + r.notes : r.error; })()")

    private fun quoted(json: String) = "'" + json.replace("\\", "\\\\").replace("'", "\\'") + "'"

    @Test
    fun acceptsAnExportedBackup() {
        assertEquals("ok 2 3", parse(quoted("""{"Bursa":[{"id":1,"title":"a","content":"b"},{"id":2}],"Rize":[{"id":3,"title":null}],"Van":[]}""")))
    }

    @Test
    fun refusesEmptyAndBlankFiles() {
        assertEquals("Dosya boş.", parse("''"))
        assertEquals("Dosya boş.", parse("'  \\n\\t '"))
        assertEquals("Dosya boş.", parse("'\\uFEFF'"))
        assertEquals("Dosyada içe aktarılacak not yok.", parse(quoted("{}")))
        assertEquals("Dosyada içe aktarılacak not yok.", parse(quoted("""{"Bursa":[]}""")))
    }

    @Test
    fun refusesCorruptJson() {
        listOf("{", """{"Bursa":[{"id":1}]""", """{"Bursa":[{"id":1},]}""", "<html></html>", """{'Bursa':[]}""").forEach {
            assertEquals(it, "Dosya geçerli bir JSON değil.", parse(quoted(it)))
        }
    }

    @Test
    fun refusesJsonThatIsNotANotesBackup() {
        val notNotes = "Bu dosya bir Şehir Notları yedeği değil."
        listOf("[]", "[{\"id\":1}]", "\"text\"", "42", "null", "true", """{"Bursa":{"id":1}}""", """{"Bursa":"note"}""", """{"Bursa":null}""").forEach {
            assertEquals(it, notNotes, parse(quoted(it)))
        }
        val badNote = "Dosyada okunamayan bir not var."
        listOf("""{"Bursa":[1]}""", """{"Bursa":[null]}""", """{"Bursa":[[]]}""", """{"Bursa":[{"title":5}]}""", """{"Bursa":[{"content":{}}]}""").forEach {
            assertEquals(it, badNote, parse(quoted(it)))
        }
    }

    @Test
    fun refusesCityKeysThatReachObjectPrototype() {
        val bad = "Dosyada geçersiz bir şehir adı var."
        listOf("__proto__", "constructor", "prototype", "", "  ", "x".repeat(101)).forEach {
            assertEquals(it, bad, parse(quoted("""{"Bursa":[{"id":1}],"$it":[{"id":2}]}""")))
        }
        assertEquals("ok 1 1", parse(quoted("""{"${"x".repeat(100)}":[{"id":2}]}""")))
    }

    @Test
    fun refusesMoreNotesThanTheLimit() {
        assertEquals("ok 2 20000", parse("JSON.stringify({ Bursa: notes(19999), Rize: notes(1) })"))
        assertEquals("Dosyada çok fazla not var.", parse("JSON.stringify({ Bursa: notes(19999), Rize: notes(2) })"))
    }

    private fun defineNotes() =
        cx.evaluateString(scope, "function notes(n){ var a = []; for (var i = 0; i < n; i++) a.push({ id: i }); return a; }", "notes", 1, null)

    @Test
    fun keepsTurkishTextExactly() {
        val text = "Şişli’de ıhlamur, çiğ köfte; ĞÜŞİÖÇ ğüşıöç — “tırnak” \\\"kaçış\\\" </script> 🙂"
        assertEquals(
            "true",
            str(
                """(function(){
                    var db = { 'İzmir': [{ id: 7, title: 'Çeşme', content: "$text" }] };
                    var r = NotesFile.parse('\uFEFF' + JSON.stringify(db, null, 2));
                    return String(r.ok && JSON.stringify(r.data) === JSON.stringify(db));
                })()"""
            )
        )
    }

    @Test
    fun previewCountsOnlyNewNotesAndChangesNothing() {
        assertEquals(
            "1 2 true true",
            str(
                """(function(){
                    var db = { 'Bursa': [{ id: 1, title: 'a' }], 'Rize': [{ id: '9', title: 'b' }] };
                    var data = { 'Bursa': [{ id: 1, title: 'a' }, { id: 2, title: 'c' }], 'Rize': [{ id: 9, title: 'b' }] };
                    var dbBefore = JSON.stringify(db), dataBefore = JSON.stringify(data);
                    var p = NotesFile.preview(db, data);
                    return p.added + ' ' + p.skipped + ' ' + (JSON.stringify(db) === dbBefore) + ' ' + (JSON.stringify(data) === dataBefore);
                })()"""
            )
        )
    }

    @Test
    fun reimportingTheSameExportAddsNothing() {
        assertEquals(
            "0 3 3",
            str(
                """(function(){
                    var db = { 'Bursa': [{ id: 1 }, { id: 2 }], 'Iğdır': [{ id: 3, title: 'ğ' }] };
                    var r = NotesFile.parse(JSON.stringify(db, null, 2));
                    var p = NotesFile.preview(db, r.data);
                    var added = NoteIds.mergeNotes(db, r.data);
                    return p.added + ' ' + p.skipped + ' ' + (added + NotesFile.count(db));
                })()"""
            )
        )
    }

    @Test
    fun previewMatchesTheRealMerge() {
        assertEquals(
            "3 3",
            str(
                """(function(){
                    var db = { 'Bursa': [{ id: 1 }] };
                    var data = { 'Bursa': [{ id: 1 }, { title: 'kimliksiz' }, { id: 5 }, { id: 5 }], 'Van': [{ id: 1 }] };
                    var p = NotesFile.preview(db, data);
                    return p.added + ' ' + NoteIds.mergeNotes(db, data);
                })()"""
            )
        )
    }

    @Test
    fun fileNameAndCount() {
        assertEquals("turkiye-notlari-2026-11-06.json", str("NotesFile.fileName(new Date(Date.UTC(2026, 10, 6, 12)))"))
        assertEquals("3", str("String(NotesFile.count({ a: [1, 2], b: [3], c: 'x' }))"))
        assertEquals("5242880", str("String(NotesFile.MAX_BYTES)"))
    }

    private fun assetFile(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }
}
