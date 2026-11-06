package com.sehirnotlari

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File

/** Runs the real assets/city-pick.js under Rhino, against the city list from index.html. */
class CityPickScriptTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        cx.evaluateString(scope, assetFile("city-pick.js").readText(), "city-pick.js", 1, null)
        val coords = assetFile("index.html").readText().lineSequence().first { it.startsWith("const CITY_COORDS={") }
        cx.evaluateString(scope, coords.replaceFirst("const ", "var "), "CITY_COORDS", 1, null)
        cx.evaluateString(scope, "var CITIES = Object.keys(CITY_COORDS);", "test", 1, null)
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun str(source: String) = Context.toString(cx.evaluateString(scope, source, "test", 1, null))
    private fun num(source: String) = Context.toNumber(cx.evaluateString(scope, source, "test", 1, null))

    private fun names(query: String, db: String = "{}") =
        str("CityPick.search(${'"'}$query${'"'}, CITIES, $db).map(function(r){ return r.city; }).join('|')")

    @Test
    fun pickerOffersAllEightyOneProvinces() {
        assertEquals(81.0, num("CITIES.length"), 0.0)
        assertEquals(81.0, num("CityPick.search('', CITIES, {}).length"), 0.0)
    }

    @Test
    fun foldMatchesTurkishLettersWithAndWithoutDiacritics() {
        assertEquals("sanliurfa", str("CityPick.fold('Şanlıurfa')"))
        assertEquals("sanliurfa", str("CityPick.fold('ŞANLIURFA')"))
        assertEquals("istanbul", str("CityPick.fold('İstanbul')"))
        assertEquals("igdir", str("CityPick.fold('  Iğdır ')"))
        assertEquals("", str("CityPick.fold(null)"))
    }

    @Test
    fun searchPutsPrefixMatchesBeforeSubstringMatches() {
        assertEquals("İstanbul", names("ist"))
        assertEquals("İzmir", names("izm"))
        assertEquals("Şanlıurfa", names("sanli"))
        val sehir = names("sehir").split("|")
        assertTrue(sehir.containsAll(listOf("Eskişehir", "Kırşehir", "Nevşehir")))
        val an = names("an").split("|")
        assertTrue(an.indexOf("Ankara") < an.indexOf("Adana"))
        assertTrue(an.indexOf("Antalya") < an.indexOf("Batman"))
        assertEquals("", names("zzz"))
    }

    @Test
    fun withoutQueryNotedCitiesComeFirstWithTheirCounts() {
        val db = "{ 'Van': [{id:1},{id:2}], 'Bursa': [{id:3}], 'Eski bir yer': [{id:4}] }"
        assertEquals("Bursa|Van", names("", db).split("|").take(2).joinToString("|"))
        assertEquals(2.0, num("CityPick.search('van', CITIES, $db)[0].count"), 0.0)
        assertEquals(0.0, num("CityPick.search('ankara', CITIES, $db)[0].count"), 0.0)
        // Only real provinces are offered, even if stored data has another key.
        assertEquals(81.0, num("CityPick.search('', CITIES, $db).length"), 0.0)
    }

    @Test
    fun countReadsTheCurrentListAndToleratesMissingData() {
        assertEquals(3.0, num("CityPick.count({ 'Bursa': [{},{},{}] }, 'Bursa')"), 0.0)
        assertEquals(0.0, num("CityPick.count({}, 'Bursa')"), 0.0)
        assertEquals(0.0, num("CityPick.count(null, 'Bursa')"), 0.0)
        assertEquals(0.0, num("CityPick.count({ 'Bursa': 'bozuk' }, 'toString')"), 0.0)
        // A note hidden by a pending (undoable) delete is not counted: the page passes its live DB.
        assertEquals(1.0, num("(function(){ var db = { 'Bursa': [{id:1},{id:2}] }; db['Bursa'] = db['Bursa'].filter(function(n){ return n.id !== 2; }); return CityPick.count(db, 'Bursa'); })()"), 0.0)
    }

    @Test
    fun searchNeverChangesTheNotes() {
        assertEquals(
            "true",
            str("(function(){ var db = { 'Bursa': [{id:1,title:'a'}] }; var before = JSON.stringify(db); CityPick.search('bur', CITIES, db); CityPick.count(db, 'Bursa'); return String(JSON.stringify(db) === before); })()")
        )
    }

    private fun assetFile(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }
}
