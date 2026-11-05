package com.sehirnotlari.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File

/** Runs the real assets/note-ids.js under Rhino. */
class NoteIdsScriptTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        cx.evaluateString(scope, assetFile("note-ids.js").readText(), "note-ids.js", 1, null)
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun js(source: String): Any? = cx.evaluateString(scope, source, "test", 1, null)

    private fun bool(source: String) = Context.toBoolean(js(source))
    private fun num(source: String) = Context.toNumber(js(source))
    private fun str(source: String) = Context.toString(js(source))

    @Test
    fun sameMillisecondCreatesDistinctSafeIntegerIds() {
        assertTrue(
            bool(
                """
                (function(){
                  var db = {}, seen = {};
                  db['Bursa'] = [];
                  for (var i = 0; i < 2000; i++) {
                    var id = NoteIds.nextNoteId(db, 1762380000000, 7);
                    if (seen[String(id)]) return false;
                    if (typeof id !== 'number' || id % 1 !== 0 || id > NoteIds.MAX_SAFE) return false;
                    seen[String(id)] = true;
                    db['Bursa'].push({ id: id });
                  }
                  return true;
                })()
                """
            )
        )
    }

    @Test
    fun newIdSkipsIdsAlreadyStoredInAnyCity() {
        val id = num(
            """
            NoteIds.nextNoteId({ 'Bursa': [{ id: 1762380000000007 }], 'İzmir': [{ id: 1762380000000008 }] },
                               1762380000000, 7)
            """
        )
        assertEquals(1762380000000009.0, id, 0.0)
    }

    @Test
    fun newIdsSortAfterLegacyDateNowIds() {
        assertTrue(bool("NoteIds.nextNoteId({}, 1762380000000, 0) > 1762380000000"))
    }

    @Test
    fun sameIdTreatsLegacyNumbersAndTextConsistently() {
        assertTrue(bool("NoteIds.sameId(1700000000000, '1700000000000')"))
        assertTrue(bool("NoteIds.sameId('import-a', 'import-a')"))
        assertFalse(bool("NoteIds.sameId(1, 2)"))
        assertFalse(bool("NoteIds.sameId(undefined, undefined)"))
        assertFalse(bool("NoteIds.sameId(null, 'null')"))
    }

    @Test
    fun idArgRoundTripsThroughInlineOnclickAttribute() {
        assertEquals("1700000000000", str("NoteIds.idArg(1700000000000)"))
        val attr = str("NoteIds.idArg('a\"b<c>\\'d&e')")
        assertFalse(attr.contains("\""))
        assertFalse(attr.contains("'"))
        assertFalse(attr.contains("<"))
        val decoded = attr
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        assertEquals("a\"b<c>'d&e", Context.toString(js(decoded)))
    }

    @Test
    fun mergeKeepsExistingIdsAndSkipsTextualDuplicates() {
        assertEquals(
            "1|1700000000000|1700000000001|import-a",
            str(
                """
                (function(){
                  var db = { 'Bursa': [{ id: 1700000000000, title: 'Eski' }] };
                  var added = NoteIds.mergeNotes(db, { 'Bursa': [
                    { id: '1700000000000', title: 'Aynı not, metin kimlik' },
                    { id: 1700000000001, title: 'Yeni sayısal' },
                    { id: 1700000000001, title: 'Dosyada tekrar' }
                  ], 'İzmir': [{ id: 'import-a', title: 'Metin kimlik' }] });
                  return [added - 1,
                          db['Bursa'][0].id, db['Bursa'][1].id, db['İzmir'][0].id].join('|');
                })()
                """
            )
        )
        assertEquals("Eski", str("(function(){var db={'Bursa':[{id:5,title:'Eski'}]};NoteIds.mergeNotes(db,{'Bursa':[{id:'5',title:'Yeni'}]});return db['Bursa'][0].title;})()"))
    }

    @Test
    fun mergeAssignsIdsOnlyToEntriesWithoutUsableId() {
        assertTrue(
            bool(
                """
                (function(){
                  var db = { 'Bursa': [{ id: 1700000000000 }] };
                  var mirrored = [];
                  var added = NoteIds.mergeNotes(db, { 'Bursa': [
                    { title: 'Kimliksiz' }, { id: null, title: 'Boş' }, { id: '', title: 'Boş metin' },
                    null, 'bozuk', { id: 1700000000000, title: 'Var olan' }
                  ], 'Ankara': 'dizi değil' }, function(city, note){ mirrored.push(city + '::' + String(note.id)); });
                  var ids = db['Bursa'].map(function(n){ return n.id; });
                  return added === 3 && mirrored.length === 3 && ids[0] === 1700000000000 &&
                         ids[1] !== ids[2] && ids[2] !== ids[3] && ids[1] !== ids[3] &&
                         typeof ids[1] === 'number' && !db['Ankara'];
                })()
                """
            )
        )
    }

    @Test
    fun mergeNeverRenumbersUsableLegacyIds() {
        assertEquals(
            "-5|1.5|${"x".repeat(150)}|  a  ",
            str(
                """
                (function(){
                  var db = {};
                  NoteIds.mergeNotes(db, { 'Bursa': [{ id: -5 }, { id: 1.5 }, { id: '${"x".repeat(150)}' }, { id: '  a  ' }] });
                  return db['Bursa'].map(function(n){ return String(n.id); }).join('|');
                })()
                """
            )
        )
    }

    @Test
    fun saveEditDeleteWorkWithMixedLegacyAndNewIds() {
        assertTrue(
            bool(
                """
                (function(){
                  var db = { 'Bursa': [{ id: 1700000000000, title: 'Eski' }, { id: 'import-a', title: 'Metin' }] };
                  var fresh = NoteIds.nextNoteId(db, 1700000000000, 0);
                  db['Bursa'].push({ id: fresh, title: 'Yeni' });
                  function find(id){ return db['Bursa'].filter(function(n){ return NoteIds.sameId(n.id, id); })[0]; }
                  find(fresh).title = 'Yeni düzenlendi';
                  find('1700000000000').title = 'Eski düzenlendi';
                  db['Bursa'] = db['Bursa'].filter(function(n){ return !NoteIds.sameId(n.id, 'import-a'); });
                  return db['Bursa'].length === 2 && db['Bursa'][0].id === 1700000000000 &&
                         db['Bursa'][0].title === 'Eski düzenlendi' && find(fresh).title === 'Yeni düzenlendi';
                })()
                """
            )
        )
    }

    @Test
    fun roomKeyMatchesJavaScriptStringForNewIds() {
        val id = str("String(NoteIds.nextNoteId({}, 1762380000000, 123))")
        assertEquals("1762380000000123", id)
        assertEquals("Bursa::1762380000000123", RoomNoteKeys.key("Bursa", id))
        assertEquals(1762380000000123L, RoomNoteKeys.webViewId(NoteEntity(id = "Bursa::$id", city = "Bursa", title = "")))
    }

    private fun assetFile(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }
}
