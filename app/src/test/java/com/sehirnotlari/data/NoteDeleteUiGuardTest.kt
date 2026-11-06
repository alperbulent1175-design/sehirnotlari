package com.sehirnotlari.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static guards on how index.html deletes a single note. */
class NoteDeleteUiGuardTest {
    private val html = listOf(File("src/main/assets/index.html"), File("app/src/main/assets/index.html"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

    @Test
    fun noteListHasNoDeleteButton() {
        val list = html.substringAfter("function renderNotes(){").substringBefore("\n}")
        assertFalse(list.contains("delNote"))
        assertFalse(list.contains("Sil<"))
        assertFalse(list.contains("nbtn del"))
        val favs = html.substringAfter("function renderFavs(){").substringBefore("\n}")
        assertFalse(favs.contains("delNote"))
    }

    @Test
    fun deletionStartsOnlyFromTheEditDialogAfterANamedConfirmation() {
        val edit = html.substringAfter("<div class=\"sub-ov\" id=\"editOv\">").substringBefore("<!--")
        assertTrue(edit.contains("<button type=\"button\" class=\"edit-del\" id=\"eDelete\" onclick=\"askDelete()\">"))
        assertTrue(edit.indexOf("saveEdit()") < edit.indexOf("askDelete()"))
        val dialog = html.substringAfter("<div class=\"sub-ov\" id=\"delOv\">").substringBefore("<!--")
        listOf("role=\"alertdialog\"", "aria-modal=\"true\"", "aria-labelledby=\"delTitle\"", "aria-describedby=\"delText\"",
            "Not silinsin mi?", ">Vazgeç<", ">Sil<").forEach { assertTrue(it, dialog.contains(it)) }
        Regex("""<button\b[^>]*>""").findAll(dialog).forEach { assertTrue(it.value, it.value.contains("type=\"button\"")) }
        val ask = html.substringAfter("function askDelete(){").substringBefore("\n}")
        assertTrue(ask.contains("document.getElementById('delText').textContent=`“\${n.title||'Başlıksız not'}” notu silinecek. `"))
        assertTrue(ask.contains("document.getElementById('delCancel').focus();"))
        assertFalse(ask.contains("innerHTML"))
        // Only confirmDelete starts a deletion: the immediate path and the undoable one.
        assertEquals(2, Regex("""\bdelNote\(""").findAll(html).count())
        assertEquals(1, Regex("""noteDel\.remove\(""").findAll(html).count())
        val confirm = html.substringAfter("function confirmDelete(){").substringBefore("\n}")
        assertTrue(confirm.contains("if(!noteDel){delNote(t.city,t.id);return;}"))
        assertTrue(confirm.contains("noteDel.remove(t.city,t.id)"))
    }

    @Test
    fun undoIsOffInRoomFirstModeAndTheSavedCopyIncludesAPendingNote() {
        assertTrue(html.contains("<script src=\"note-delete.js\"></script>"))
        assertTrue(html.indexOf("<script src=\"note-ids.js\"></script>") < html.indexOf("<script src=\"note-delete.js\"></script>"))
        assertTrue(html.contains("noteDel=RF_MODE?null:NoteDelete.create({"))
        assertTrue(html.contains("const storedDB=()=>noteDel?noteDel.view(DB):DB;"))
        assertTrue(html.contains("persist:sv,mirrorDelete:mirrorDeletedNoteToRoom,"))
        assertEquals(2, Regex("""nextNoteId\(storedDB\(\)\)""").findAll(html).count())
        assertFalse(html.contains("nextNoteId(DB)"))
    }

    @Test
    fun pendingDeletionBecomesFinalBeforeAnythingReadsTheSavedCopy() {
        assertTrue(html.contains("document.addEventListener('visibilitychange',()=>{if(document.hidden)settleDelete();});"))
        assertTrue(html.contains("window.addEventListener('pagehide',settleDelete);"))
        // The Yedek tab exports and imports the saved copy, so entering it settles the pending note first.
        val show = html.substringAfter("function showView(v,focus){").substringBefore("\n}")
        assertTrue(show.contains("if(v==='backup'&&noteDel)noteDel.commit();"))
        assertTrue(show.indexOf("noteDel.commit()") < show.indexOf("dsRecheck"))
        assertTrue(html.substringAfter("function previewImport(text,name){").substringBefore("NotesFile.preview").contains("settleDelete();"))
        assertTrue(html.substringAfter("function applyImport(d){").substringBefore("NoteIds.mergeNotes").contains("settleDelete();"))
        val clear = html.substringAfter("function clearAll(){").substringBefore("\n")
        assertTrue(clear.indexOf("settleDelete();") < clear.indexOf("const oldDB=DB;"))
        // Recovery runs while the script loads, so before the load-time Room reconciliation.
        assertTrue(html.indexOf("const r=noteDel.recover();") in 0 until html.lastIndexOf("</script>"))
        assertTrue(html.contains("window.addEventListener('load',()=>{if(RF_MODE)rfStart();else requestRoomReconcile(true);});"))
    }

    @Test
    fun undoBarIsAnnouncedAndLargeEnoughToTap() {
        assertTrue(html.contains("<div class=\"undo-bar\" id=\"undoBar\" role=\"status\" aria-live=\"polite\" hidden><span id=\"undoText\"></span><button type=\"button\" id=\"undoBtn\">Geri Al</button></div>"))
        assertTrue(html.contains(".undo-bar button{flex-shrink:0;min-height:48px;min-width:48px"))
        assertTrue(html.contains(".edit-del{display:flex;align-items:center;justify-content:center;gap:6px;width:100%;min-height:48px"))
        assertTrue(html.contains(".del-dlg .sub-row button{flex:1 1 120px;min-height:48px}"))
        val back = html.substringAfter("function handleAndroidBack").substringBefore("\n}")
        assertTrue(back.indexOf("'delOv'") in 0 until back.indexOf("'editOv'"))
    }
}
