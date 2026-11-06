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

/** Runs the real assets/room-first.js (the Room-first page's write queue) under Rhino. */
class RoomFirstScriptTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        cx.evaluateString(scope, assetFile("room-first.js").readText(), "room-first.js", 1, null)
        js(
            """
            var sent = [], mem = {}, n = 0, failSend = false;
            var storage = { get: function (k) { return mem.hasOwnProperty(k) ? mem[k] : null; }, set: function (k, v) { mem[k] = v; } };
            function mk() {
              return RoomFirst.createStore({
                send: function (p, id) { if (failSend) throw new Error('bridge'); sent.push({ p: JSON.parse(p), id: id }); },
                storage: storage,
                newId: function () { return 'r' + (++n); }
              });
            }
            function row(city, id, title, ticket, extra) {
              var r = { key: city + '::' + id, city: city, id: id, title: title, content: title + ' içerik', category: 'Gezi',
                star: 2, color: '', fav: false, date: '06 Kas 2026', isAI: false, ticket: ticket, deleted: false };
              for (var k in extra) r[k] = extra[k];
              return r;
            }
            function snap(rows) {
              var notes = {};
              rows.forEach(function (r) { (notes[r.city] || (notes[r.city] = [])).push(r); });
              return { notes: notes };
            }
            function reply(status, r, saved) {
              return { status: status, ticket: r ? r.ticket : null, saved: saved, row: r || null };
            }
            function last() { return sent[sent.length - 1]; }
            function note(id, title, extra) {
              var o = { id: id, title: title, content: title + ' içerik', category: 'Gezi', star: 2, color: '', fav: false, date: '06 Kas 2026', isAI: false };
              for (var k in extra) o[k] = extra[k];
              return o;
            }
            var s = mk();
            """
        )
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun js(source: String): Any? = cx.evaluateString(scope, source, "test", 1, null)
    private fun str(source: String) = Context.toString(js(source))
    private fun num(source: String) = Context.toNumber(js(source)).toInt()
    private fun bool(source: String) = Context.toBoolean(js(source))

    @Test
    fun nothingIsAcceptedBeforeRoomWasRead() {
        assertFalse(bool("s.save('Bursa', note(7, 'erken'))"))
        assertFalse(bool("s.remove('Bursa', 7)"))
        assertEquals(0, num("sent.length"))
        assertFalse(bool("s.isReady()"))
    }

    @Test
    fun aNoteIsShownAsSavedOnlyAfterTheCommitReply() {
        js("s.hydrate(snap([row('Bursa', 7, 'Ulu Cami', 100)]))")
        assertTrue(bool("s.save('Bursa', note(7, 'Ulu Cami (düzenlendi)'))"))
        assertEquals("UPSERT", str("last().p.op"))
        assertEquals(100, num("last().p.base"))
        assertEquals("saving", str("s.statusOf('Bursa', 7).status"))
        assertEquals("Kaydediliyor…", str("s.statusOf('Bursa', 7).text"))
        // The page shows the edit, but the committed copy (written to localStorage on exit) does not have it yet.
        assertEquals("Ulu Cami (düzenlendi)", str("s.view().Bursa[0].title"))
        assertEquals("Ulu Cami", str("s.committed().Bursa[0].title"))
        assertEquals(1, num("JSON.parse(mem[RoomFirst.OUTBOX_KEY]).length"))

        js("var out = s.onResult(last().id, reply('COMMITTED', row('Bursa', 7, 'Ulu Cami (düzenlendi)', 101), true), null)")
        assertEquals("saved", str("out.status"))
        assertEquals("Room’a kaydedildi", str("out.notice"))
        assertEquals("Ulu Cami (düzenlendi)", str("s.committed().Bursa[0].title"))
        assertEquals(101, num("s.ticketOf('Bursa', 7)"))
        assertEquals(0, num("s.pendingCount()"))
        assertEquals("[]", str("mem[RoomFirst.OUTBOX_KEY]"))
    }

    @Test
    fun rapidEditsAreCoalescedAndChainedOnTheCommittedTicket() {
        js("s.hydrate(snap([row('Ankara', 42, 'Not', 500)]))")
        js("for (var i = 0; i < 400; i++) s.save('Ankara', note(42, 'Not', { content: 'düzenleme ' + i }))")
        assertEquals(1, num("sent.length"))
        assertEquals("düzenleme 0", str("sent[0].p.note.content"))
        assertEquals("düzenleme 399", str("s.view().Ankara[0].content"))
        js("var out = s.onResult('r1', reply('COMMITTED', row('Ankara', 42, 'Not', 501, { content: 'düzenleme 0' }), true), null)")
        assertTrue(bool("out.followUp"))
        assertEquals(2, num("sent.length"))
        assertEquals(501, num("sent[1].p.base"))
        assertEquals("düzenleme 399", str("sent[1].p.note.content"))
        js("s.onResult('r2', reply('COMMITTED', row('Ankara', 42, 'Not', 502, { content: 'düzenleme 399' }), true), null)")
        assertEquals(0, num("s.pendingCount()"))
        assertEquals("düzenleme 399", str("s.committed().Ankara[0].content"))
    }

    @Test
    fun aRequestInFlightDuringAReloadIsResentWithTheSameIdAndBase() {
        js("s.hydrate(snap([row('Bursa', 7, 'Ulu Cami', 100)])); s.save('Bursa', note(7, 'yeni'))")
        val id = str("last().id")
        // Page gone (rotation, force-stop): its reply is lost. The new page reads Room, then resends.
        js("var t = mk(); sent = []; t.hydrate(snap([row('Bursa', 7, 'yeni', 101)]))")
        assertEquals(1, num("t.resume()"))
        assertEquals(id, str("last().id"))
        assertEquals(100, num("last().p.base"))
        js("var out = t.onResult(last().id, reply('ALREADY_SAVED', row('Bursa', 7, 'yeni', 101), true), null)")
        assertEquals("saved", str("out.status"))
        assertEquals("[]", str("mem[RoomFirst.OUTBOX_KEY]"))
        assertEquals(0, num("mk().resume()"))
    }

    @Test
    fun failuresAndRefusalsAreNeverShownAsSaved() {
        js("s.hydrate(snap([row('Bursa', 7, 'Ulu Cami', 100), row('Bursa', 8, 'Kebap', 200)]))")
        js("s.save('Bursa', note(7, 'hata alacak'))")
        js("var err = s.onResult(last().id, null, 'SQLiteFullException')")
        assertEquals("error", str("err.status"))
        assertEquals("Kaydedilemedi; değişiklik uygulanmadı", str("err.notice"))
        assertEquals("Ulu Cami", str("s.view().Bursa[0].title"))

        js("s.save('Bursa', note(8, 'eski sürümden'))")
        js("var c = s.onResult(last().id, reply('CONFLICT', row('Bursa', 8, 'başka cihazdan', 250), false), null)")
        assertEquals("conflict", str("c.status"))
        assertEquals("başka cihazdan", str("s.view().Bursa[1].title"))

        js("s.save('Bursa', note(8, 'diriltme'))")
        js("var d = s.onResult(last().id, reply('DELETED', row('Bursa', 8, 'başka cihazdan', 260, { deleted: true }), false), null)")
        assertEquals("deleted", str("d.status"))
        assertEquals(1, num("s.view().Bursa.length"))

        js("failSend = true; s.save('Bursa', note(7, 'köprü yok'))")
        assertEquals("error", str("s.statusOf('Bursa', 7).status"))
        assertEquals(0, num("s.pendingCount()"))
        assertEquals("Ulu Cami", str("s.view().Bursa[0].title"))
    }

    @Test
    fun deletingWaitsForAnUnconfirmedCreateAndUsesItsTicket() {
        js("s.hydrate(snap([])); s.save('İzmir', note(9, 'yeni not'))")
        assertEquals(0, num("last().p.base"))
        js("s.remove('İzmir', 9)")
        assertEquals(1, num("sent.length"))
        assertEquals("deleting", str("s.statusOf('İzmir', 9).status"))
        js("s.onResult('r1', reply('COMMITTED', row('İzmir', 9, 'yeni not', 300), true), null)")
        assertEquals("DELETE", str("last().p.op"))
        assertEquals(300, num("last().p.base"))
        js("var out = s.onResult('r2', reply('COMMITTED', row('İzmir', 9, 'yeni not', 301, { deleted: true }), true), null)")
        assertEquals("removed", str("out.status"))
        assertTrue(bool("s.view()['İzmir'] === undefined"))
        assertTrue(bool("s.statusOf('İzmir', 9) === null"))
    }

    @Test
    fun unknownRepliesChangeNothing() {
        js("s.hydrate(snap([row('Bursa', 7, 'Ulu Cami', 100)]))")
        assertTrue(bool("s.onResult('başka-sayfa', reply('COMMITTED', row('Bursa', 7, 'X', 999), true), null).unknown"))
        assertEquals("Ulu Cami", str("s.view().Bursa[0].title"))
        assertEquals(100, num("s.ticketOf('Bursa', 7)"))
    }

    @Test
    fun auditBlockersAreListedInTurkish() {
        js("var b = RoomFirst.blockerList({ clean: false, blockers: { contentMismatch: 2, tombstoneConflict: 1, onlyInRoom: 0 } })")
        assertEquals(2, num("b.length"))
        assertEquals("İçeriği farklı not", str("b[0].label"))
        assertEquals(2, num("b[0].count"))
        assertEquals("Room’da silinmiş ama telefonda duran not", str("b[1].label"))
        // Every native blocker has a label.
        RoomFirstPreflightKeys.forEach { assertTrue(it, bool("!!RoomFirst.BLOCKERS['$it']")) }
    }

    @Test
    fun theQueueNeverLogsOrTouchesTheProductionNotes() {
        val source = assetFile("room-first.js").readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        listOf("console.", "localStorage", "tur_v10", "Android", "document.", "fetch(").forEach {
            assertFalse(it, source.contains(it))
        }
    }

    private fun assetFile(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }

    private companion object {
        val RoomFirstPreflightKeys = listOf(
            "skippedLegacy", "missingInRoom", "contentMismatch", "roomNewer", "tombstoneConflict", "onlyInRoom",
            "unknownOrigin", "unmatchedPending", "repairable", "unresolvedJournal", "changedSince",
        )
    }
}
