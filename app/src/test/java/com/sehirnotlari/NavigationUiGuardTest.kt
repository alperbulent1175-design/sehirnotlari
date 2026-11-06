package com.sehirnotlari

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static guards on the v1.4 bottom navigation, the add-note entry points and their accessibility. */
class NavigationUiGuardTest {
    private val html = listOf(File("src/main/assets/index.html"), File("app/src/main/assets/index.html"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

    private fun fn(signature: String) = html.substringAfter(signature).substringBefore("\n}")

    @Test
    fun bottomNavigationHasFourLabelledDestinationsAndReplacesTheDrawer() {
        val nav = html.substringAfter("<nav class=\"bnav\" aria-label=\"Ana gezinme\">").substringBefore("</nav>")
        val buttons = Regex("""<button\b[^>]*>""").findAll(nav).map { it.value }.toList()
        assertEquals(4, buttons.size)
        buttons.forEach { assertTrue(it, it.contains("type=\"button\"")) }
        assertEquals(listOf("map", "notes", "backup", "settings"),
            buttons.map { Regex("""data-view="(\w+)"""").find(it)!!.groupValues[1] })
        listOf(">Harita<", ">Notlarım<", ">Yedek<", ">Ayarlar<").forEach { assertTrue(it, nav.contains(it)) }
        listOf("map", "notes", "backup", "settings").forEach {
            assertTrue(it, html.contains("<section class=\"view") && html.contains("id=\"view-$it\""))
        }
        listOf("id=\"drawer\"", "toggleDr", "id=\"menuBtn\"", "id=\"thBtn\"").forEach { assertFalse(it, html.contains(it)) }
        // The nav cannot overflow: equal flexible columns that may shrink below their text width.
        assertTrue(html.contains(".bnav button{flex:1 1 0;min-width:0;min-height:64px;"))
        assertTrue(html.contains(".bnav button{min-height:48px;flex-direction:row;"))
    }

    @Test
    fun backKeyClosesTheTopLayerThenReturnsToTheMapBeforeExit() {
        val back = fn("function handleAndroidBack")
        val order = listOf("'delOv'", "'dsOv'", "'editOv'", "'aiOv'", "'remOv'", "'micOv'", "'pickOv'", "'ov'", "curView !== 'map'")
        val at = order.map { back.indexOf(it) }
        at.forEachIndexed { i, pos -> assertTrue(order[i], pos >= 0) }
        assertEquals(at.sorted(), at)
        assertTrue(back.contains("showView('map', true);"))
        assertTrue(back.trimEnd().endsWith("return false; // Haritada ve hiçbir şey açık değilse uygulamadan çıkış onayına geç"))
        assertTrue(back.contains("closePicker();"))
    }

    @Test
    fun addNoteUsesARealCityAndNeverInventsAPosition() {
        assertTrue(html.contains("<button type=\"button\" class=\"map-fab\" id=\"addFab\">"))
        assertTrue(html.contains("document.getElementById('addFab').onclick=e=>openPicker(e.currentTarget);"))
        assertTrue(html.contains("<script src=\"city-pick.js\"></script>"))
        val pick = fn("function renderPick(){")
        assertTrue(pick.contains("CityPick.search(document.getElementById('pickQ').value,Object.keys(CITY_COORDS),DB)"))
        val chosen = fn("function pickCity(city){")
        assertTrue(chosen.contains("if(!CITY_COORDS[city])return;"))
        assertTrue(chosen.contains("{add:true"))
        // The tap that opens the list must not also choose the row that appears under the finger.
        assertTrue(html.contains("if(b&&Date.now()-pickOpenedAt>400)pickCity(b.dataset.city);"))
        // Position only from the device; a city is used only when it is a known province.
        val gps = fn("function findMyCity(onCity){")
        assertTrue(gps.contains("navigator.geolocation.getCurrentPosition("))
        assertTrue(gps.contains("if(city&&CITY_COORDS[city])onCity(city);else toast('Şehir bulunamadı.');"))
        assertFalse(Regex("""lat\s*[:=]\s*\d""").containsMatchIn(html.substringAfter("const pickOv=").substringBefore("// ── ")))
        // The map keeps opening the city panel on tap.
        assertTrue(html.contains("layer.on('click',()=>openModal(city,IL_BOLGE[city]||'ic'));"))
    }

    @Test
    fun cityPanelCountFollowsEveryChange() {
        val notes = fn("function renderNotes(){")
        assertTrue(notes.indexOf("renderCityMeta();") in 0 until notes.indexOf("nb.textContent=ns.length;"))
        assertTrue(fn("function renderCityMeta(){").contains("CityPick.count(DB,city)"))
        // Delete, undo and a failed delete all re-render through refreshNoteViews.
        assertTrue(html.contains("function refreshNoteViews(){renderNotes();"))
        assertTrue(html.substringAfter("function saveEdit(){").substringBefore("\n").contains("sv();mirrorNewNoteToRoom(editingCity,n);renderNotes();"))
        val add = html.substringAfter("document.getElementById('ba').onclick=()=>{").substringBefore("\n};")
        assertTrue(add.contains("sv();resetAddForm();showAddForm(false);"))
        assertTrue(add.indexOf("sv();") < add.indexOf("renderNotes();"))
    }

    @Test
    fun theYedekTabSettlesAPendingDeleteAndKeepsBackupTools() {
        val show = fn("function showView(v,focus){")
        assertTrue(show.indexOf("if(v==='backup'&&noteDel)noteDel.commit();") in 0 until show.indexOf("curView=v;"))
        val backup = html.substringAfter("id=\"view-backup\"").substringBefore("</section>\n\n")
        listOf("onclick=\"exportJSON()\"", "onchange=\"importJSON(this)\"", "id=\"dataSafety\"", "onclick=\"clearAll()\"").forEach {
            assertTrue(it, backup.contains(it))
        }
    }

    @Test
    fun touchTargetsAreAtLeast48AndIconButtonsHaveNames() {
        listOf(
            ".map-tool{width:48px;height:48px;", ".mcl{width:48px;height:48px;", ".city-btn{flex:1 1 130px;min-height:48px;",
            ".cat{background:var(--surf);border:1.5px solid var(--brd);min-height:48px;", "min-width:48px;min-height:48px;padding:0;border:none;background:none;color:#c27c00;",
            ".color-opt{width:48px;height:48px;", ".nbtn{background:var(--surf2);border:1px solid var(--brd);min-height:48px;",
            ".pick-row{width:100%;display:flex;align-items:center;gap:10px;min-height:52px;"
        ).forEach { assertTrue(it, html.contains(it)) }
        listOf("gpsBtn" to "Bulunduğum şehri bul", "zoomIn" to "Haritayı yakınlaştır", "zoomOut" to "Haritayı uzaklaştır").forEach { (id, label) ->
            assertTrue(id, html.contains("id=\"$id\" aria-label=\"$label\""))
        }
        // Buttons whose visible text is only a symbol carry an aria-label.
        Regex("""<button\b([^>]*)>(.*?)</button>""").findAll(html.substringBefore("<script")).forEach { m ->
            val text = m.groupValues[2].replace(Regex("""<span[^>]*aria-hidden="true"[^>]*>.*?</span>"""), "").replace(Regex("""<[^>]+>"""), "").trim()
            if (text.isEmpty()) assertTrue("unnamed button: ${m.value}", m.groupValues[1].contains("aria-label="))
        }
        assertTrue(html.contains("<div class=\"toast\" id=\"toast\" role=\"status\" aria-live=\"polite\"></div>"))
    }

    @Test
    fun noteButtonsAreNamedPerNote() {
        val notes = fn("function renderNotes(){")
        assertTrue(notes.contains("aria-label=\"\${esc('Düzenle: '+n.title)}\""))
        assertTrue(notes.contains("aria-pressed=\"\${!!n.fav}\""))
        Regex("""<button\b[^>]*>""").findAll(notes + fn("function renderFavs(){")).forEach {
            assertTrue(it.value, it.value.contains("type=\"button\""))
        }
        assertTrue(fn("function updateSum(){").contains("<button type=\"button\" class=\"cc\""))
    }

    @Test
    fun dialogsAreNamedAndOpenOverlaysHideTheAppFromScreenReaders() {
        listOf("<div class=\"ov\" id=\"ov\">" to "aria-labelledby=\"mname\"", "<div class=\"ov\" id=\"pickOv\">" to "aria-labelledby=\"pickTitle\"",
            "<div class=\"sub-ov\" id=\"editOv\">" to "aria-labelledby=\"editTitle\"").forEach { (open, label) ->
            val inner = html.substringAfter(open).substringAfter("\n").substringBefore("\n")
            assertTrue(open, inner.contains("role=\"dialog\" aria-modal=\"true\"") && inner.contains(label))
        }
        assertTrue(html.contains("if(document.querySelector('.ov.open,.sub-ov.open'))appEl.setAttribute('aria-hidden','true');"))
        assertTrue(html.contains("<h3 id=\"editTitle\" tabindex=\"-1\">"))
        assertTrue(fn("function closeEdit(){").contains("editOpener.focus("))
    }

    @Test
    fun darkThemeAndAccentTextKeepReadableContrast() {
        assertTrue(html.contains("--btn-grad:linear-gradient(135deg,#d63031,#c0392b)"))
        assertTrue(html.contains(".toast:not(.show){visibility:hidden}"))
        assertFalse(html.contains(".sh2{font-size:10px"))
        assertFalse(html.contains(".bk-desc{font-size:11px"))
    }
}
