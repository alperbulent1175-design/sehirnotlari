package com.sehirnotlari

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** How index.html drives the Android file screens for JSON export and import. */
class NotesFileUiGuardTest {
    private val html = listOf(File("src/main/assets/index.html"), File("app/src/main/assets/index.html"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

    private fun fn(start: String) = html.substringAfter(start).substringBefore("\n}")

    @Test
    fun exportReportsSuccessOnlyFromTheAndroidCallback() {
        val android = fn("function exportJSON(){").substringAfter("if(androidFiles()){").substringBefore("\n  }")
        assertTrue(android.contains("window.Android.exportNotesFile(text,NotesFile.fileName(new Date()));"))
        assertFalse(android.contains("✅"))
        assertFalse(android.contains("toast("))
        val saved = fn("window.onNotesFileSaved=function(r){")
        assertTrue(saved.indexOf("if(r.status==='ok')") < saved.indexOf("✅ Yedek kaydedildi"))
        assertTrue(saved.contains("Kaydetme iptal edildi; dosya oluşturulmadı."))
    }

    @Test
    fun importIsCheckedAndPreviewedBeforeAnyMerge() {
        val opened = fn("window.onNotesFileOpened=function(r){")
        assertTrue(opened.contains("if(r.status==='ok')previewImport(r.text,r.name);"))
        assertTrue(opened.contains("Dosya seçilmedi; notlarınız değişmedi."))
        val preview = fn("function previewImport(text,name){")
        assertTrue(preview.indexOf("NotesFile.parse(text)") < preview.indexOf("if(!p.ok)"))
        assertFalse(preview.contains("mergeNotes"))
        assertFalse(preview.contains("sv()"))
        assertFalse(fn("function importJSON(inp){").contains("mergeNotes"))
        // Only the confirm button applies the import.
        assertEquals(2, Regex("""applyImport\(""").findAll(html).count())
        assertTrue(fn("function confirmImport(){").contains("const r=applyImport(d);"))
        assertTrue(html.contains("document.getElementById('impConfirm').onclick=confirmImport;"))
    }

    @Test
    fun importDialogIsAnAccessibleModalAndBackCancelsIt() {
        val dlg = html.substringAfter("<div class=\"sub-ov\" id=\"impOv\">").substringBefore("<!--")
        assertTrue(dlg.contains("role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"impTitle\""))
        assertTrue(dlg.contains("<h3 id=\"impTitle\" tabindex=\"-1\">"))
        val back = fn("function handleAndroidBack() {")
        val imp = back.indexOf("getElementById('impOv')")
        assertTrue(imp > back.indexOf("getElementById('dsOv')"))
        assertTrue(imp < back.indexOf("getElementById('editOv')"))
        assertTrue(back.substring(imp).substringBefore("return true;").contains("getElementById('impCancel').click();"))
    }

    @Test
    fun fileResultsAreAnnouncedInPersistentStatusLines() {
        listOf("exportStatus", "importStatus").forEach {
            assertTrue(it, html.contains("<p class=\"bk-status\" id=\"$it\" role=\"status\" aria-live=\"polite\"></p>"))
        }
        assertTrue(html.contains("<script src=\"notes-file.js\"></script>"))
    }
}
