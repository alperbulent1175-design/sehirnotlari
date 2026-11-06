package com.sehirnotlari.cloud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File

/** Runs the real assets/cloud-backup.js (with note-ids.js and notes-file.js) under Rhino. */
class CloudBackupScriptTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        listOf("note-ids.js", "notes-file.js", "cloud-backup.js").forEach { cx.evaluateString(scope, assetFile(it).readText(), it, 1, null) }
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun str(source: String) = Context.toString(cx.evaluateString(scope, source, "test", 1, null))

    /** "held | city:id,id; …" of the plan for the phone notes [db] and the backup [data]. */
    private fun plan(db: String, data: String, localDeleted: String, include: Boolean) = str(
        """(function(){
          var p = CloudBackup.restorePlan($db, $data, $localDeleted, $include);
          var parts = [];
          for (var c in p.data) parts.push(c + ':' + p.data[c].map(function(n){ return String(n.id); }).join(','));
          return p.held + ' | ' + parts.join('; ');
        })()"""
    )

    private val backup = """{"Bursa":[{"id":1},{"id":2},{"id":"x"}],"Rize":[{"id":3}]}"""

    @Test
    fun notesDeletedOnThisPhoneStayDeletedUnlessTheUserTicksTheBox() {
        assertEquals("2 | Bursa:1,x", plan("{}", backup, """[["Bursa","2"],["Rize","3"]]""", false))
        assertEquals("2 | Bursa:1,2,x; Rize:3", plan("{}", backup, """[["Bursa","2"],["Rize","3"]]""", true))
        assertEquals("0 | Bursa:1,2,x; Rize:3", plan("{}", backup, "[]", false))
    }

    @Test
    fun aNoteStillOnThePhoneIsNotCountedAsHeld() {
        // Present locally (e.g. restored since): the merge skips it anyway, so it is not offered back.
        assertEquals("1 | Bursa:1,x; Rize:3", plan("""{"Rize":[{"id":3}]}""", backup, """[["Bursa","2"],["Rize","3"]]""", false))
    }

    @Test
    fun previewOfThePlanNeverCountsExistingNotesAsAdded() {
        val added = str(
            """(function(){
              var db = {"Bursa":[{"id":1,"title":"telefondaki"}]};
              var p = CloudBackup.restorePlan(db, $backup, [], false);
              var pv = NotesFile.preview(db, p.data);
              return pv.added + '/' + pv.skipped + '/' + db.Bursa[0].title + '/' + db.Bursa.length;
            })()"""
        )
        assertEquals("3/1/telefondaki/1", added)
    }

    @Test
    fun describesABackupInTurkish() {
        val text = str("CloudBackup.describe({createdAt: new Date(2026, 10, 6, 9, 5).getTime(), noteCount: 5, cityCount: 4})")
        assertEquals("6 Kasım 2026 09:05 · 5 not, 4 şehir", text)
        assertTrue(str("CloudBackup.skipText('FEWER_NOTES')").contains("eski yedekler korunuyor"))
        assertEquals("", str("CloudBackup.skipText('UNKNOWN')"))
    }

    private fun assetFile(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }
}

class CloudScriptsTest {
    @Test
    fun resultsReachOnlyTheFixedCallbackAsJson() {
        val s = CloudScripts.result("backup", "backup-1-abc", org.json.JSONObject().put("t", "a\u2028b"), null)
        assertTrue(s.startsWith("window.onCloudResult && window.onCloudResult(\"backup\",\"backup-1-abc\",{"))
        assertFalse(s.contains("\u2028"))
        assertTrue(s.endsWith(",null);"))
        val e = CloudScripts.result("fetch", "f-1", null, "'); alert(1); //")
        assertTrue(e.contains("null,\"'); alert(1); //\");"))
    }

    @Test
    fun refusesUnknownOperationsAndRequestIds() {
        assertThrows(IllegalArgumentException::class.java) { CloudScripts.result("eval", "x", null, null) }
        assertThrows(IllegalArgumentException::class.java) { CloudScripts.result("list", "a\");x(\"", null, null) }
        assertThrows(IllegalArgumentException::class.java) { CloudScripts.result("list", "", null, null) }
    }
}
