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

/** Runs the real assets/data-safety.js (the data-safety screen's model) under Rhino. */
class DataSafetyScriptTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        cx.evaluateString(scope, assetFile("data-safety.js").readText(), "data-safety.js", 1, null)
        js(
            """
            var DB = { 'Bursa': [ { id: 101, title: 'Ulu Cami' }, { id: 102, title: 'Kebap' } ], 'Ankara': [ { id: 'import-a', title: 'Anıtkabir' } ] };
            function summary(extra) {
              var s = { applied: true, legacy: 3, roomActive: 3, roomTombstones: 1, consistent: 3, repaired: 0, lostWrites: 0 };
              for (var k in extra) s[k] = extra[k];
              return s;
            }
            function item(key, kind, action, extra) {
              var i = { key: key, kind: kind, action: action || 'REPORTED', fields: [], roomNewer: false, unknownOrigin: false, lostWrites: 0 };
              for (var k in extra) i[k] = extra[k];
              return i;
            }
            function option(action, enabled, confirm, reason) {
              return { action: action, enabled: enabled, needsConfirmation: confirm, reason: reason };
            }
            var NOTE = { title: 'Ulu Cami', content: 'Telefon metni', category: 'Gezi', star: 3, color: '', fav: true, isAI: false, date: '06 Kas 2026' };
            var ROOM = { title: 'Ulu Cami (Room)', content: 'Room metni', category: 'Gezi', star: 3, color: '', fav: true, isAI: null, date: '06 Kas 2026', deleted: false, modifiedAt: null };
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
    fun noDifferenceShowsConsistentStateAndOffersNothing() {
        js("var m = DataSafety.model({ summary: summary({}), items: [] }, null, DB, 'ready')")
        assertEquals("consistent", str("m.state"))
        assertTrue(str("m.headline").startsWith("Tutarlı"))
        assertEquals(0, num("m.counts.review + m.counts.uncertain + m.counts.repaired + m.counts.repairable + m.counts.lost"))
        assertEquals(0, num("m.items.length"))
        assertEquals(0, num("m.batch.length"))
        assertEquals(0, num("m.attention"))
        assertEquals(3, num("m.totals.phone"))
        assertEquals(1, num("m.totals.roomDeleted"))
        assertFalse(bool("m.preview"))
    }

    @Test
    fun beforeTheReportAndAfterAFailureNothingIsClaimed() {
        assertEquals("checking", str("DataSafety.model(null, null, DB, 'checking').state"))
        assertEquals("checking", str("DataSafety.model(null, null, DB, 'ready').state"))
        js("var f = DataSafety.model({ summary: summary({}), items: [] }, null, DB, 'failed')")
        assertEquals("failed", str("f.state"))
        assertTrue(str("f.headline").contains("Notlarınız değişmedi"))
    }

    @Test
    fun automaticRepairsComeFromPersistentHistoryAndAreNotListedForReview() {
        js("var report = { summary: summary({ repaired: 1 }), items: [ item('Bursa::101', 'MISSING_IN_ROOM', 'REPAIRED') ] }")
        js("var m = DataSafety.model(report, { repaired: { count: 3 }, lost: { count: 2 }, backups: [] }, DB, 'ready')")
        assertEquals("consistent", str("m.state"))
        assertEquals(3, num("m.counts.repaired"))
        assertEquals(2, num("m.counts.lost"))
        assertEquals(0, num("m.items.length"))
        // Without the native history the startup report's own count is shown.
        assertEquals(1, num("DataSafety.model(report, null, DB, 'ready').counts.repaired"))
    }

    @Test
    fun reportAfterRestartStillShowsPendingReviewAndEarlierRepairs() {
        // Second start: the repair is already done (report says 0) but the journal still lists it.
        js(
            """
            var again = { summary: summary({ repaired: 0 }), items: [ item('Bursa::102', 'CONTENT_MISMATCH', 'REPORTED', { fields: ['content'] }) ] };
            var m = DataSafety.model(again, { repaired: { count: 1, keys: ['Bursa::101'] }, lost: { count: 0 }, backups: [] }, DB, 'ready');
            """
        )
        assertEquals("review", str("m.state"))
        assertEquals(1, num("m.counts.review"))
        assertEquals(1, num("m.counts.repaired"))
        assertEquals("Kebap", str("m.items[0].title"))
        assertEquals(1, num("m.attention"))
    }

    @Test
    fun differencesNeedingApprovalAreGroupedAndExplainedInTurkish() {
        js(
            """
            var m = DataSafety.model({ summary: summary({}), items: [
              item('Bursa::101', 'CONTENT_MISMATCH', 'REPORTED', { fields: ['title', 'content'], roomNewer: true }),
              item('Ankara::import-a', 'MISSING_IN_ROOM'),
              item('İzmir::7', 'ONLY_IN_ROOM'),
              item('Bursa::102', 'TOMBSTONE_CONFLICT'),
              item('Bursa::103', 'UNMATCHED_PENDING_WRITE', 'REPORTED', { lostWrites: 2 })
            ] }, null, DB, 'ready');
            """
        )
        assertEquals("review", str("m.state"))
        assertEquals(4, num("m.counts.review"))
        assertEquals(0, num("m.counts.uncertain"))
        assertEquals("İçerik farklı", str("m.items[0].label"))
        assertEquals("Başlık,İçerik", str("m.items[0].fields.join(',')"))
        assertTrue(bool("m.items[0].roomNewer"))
        assertEquals("Ulu Cami", str("m.items[0].title"))
        assertEquals("Anıtkabir", str("m.items[1].title"))
        assertEquals("(telefonda olmayan not)", str("m.items[2].title"))
        assertEquals("İzmir", str("m.items[2].city"))
        assertFalse(bool("m.items[2].onPhone"))
        assertEquals("info", str("m.items[4].group"))
        assertFalse(bool("m.items[4].reviewable"))
        assertEquals(2, num("m.items[4].lostWrites"))
        assertTrue(bool("m.items.slice(0, 4).every(function (x) { return x.reviewable; })"))
        assertTrue(bool("Object.keys(DataSafety.KINDS).every(function (k) { return DataSafety.KINDS[k].label && DataSafety.KINDS[k].text.length > 40; })"))
    }

    @Test
    fun tombstonesAreNeverOfferedAnEnabledApproval() {
        js(
            """
            var plan = { key: 'Bursa::102', city: 'Bursa', kind: 'TOMBSTONE_CONFLICT', fields: [], roomNewer: false, unknownOrigin: false,
              token: 't', local: NOTE, room: { title: 'Ulu Cami', content: '', category: 'Gezi', star: 0, color: '', fav: false, isAI: false, date: '', deleted: true },
              options: [ option('SYNC_FROM_LOCAL', false, false, 'TOMBSTONE_NOT_REVIVED') ] };
            var v = DataSafety.planView(plan, DB);
            """
        )
        assertFalse(bool("v.enabled"))
        assertFalse(bool("DataSafety.canApprove(v, true)"))
        assertTrue(str("v.blocked").contains("geri getirilmez"))
        assertEquals("Uygulanamaz", str("v.button"))
        assertTrue(str("v.rows[0].room").contains("silinmiş"))
    }

    @Test
    fun unknownOriginIsCountedSeparatelyAndConfirmsWithoutOverwriting() {
        js(
            """
            var m = DataSafety.model({ summary: summary({}), items: [ item('Bursa::101', 'UNKNOWN_ORIGIN', 'REPORTED', { unknownOrigin: true }) ] }, null, DB, 'ready');
            var plan = { key: 'Bursa::101', city: 'Bursa', kind: 'UNKNOWN_ORIGIN', fields: [], roomNewer: false, unknownOrigin: true,
              token: 't', local: NOTE, room: ROOM, options: [ option('SYNC_FROM_LOCAL', true, false, 'CONFIRMS_ORIGIN') ] };
            var v = DataSafety.planView(plan, DB);
            """
        )
        assertEquals("uncertain", str("m.state"))
        assertEquals(1, num("m.counts.uncertain"))
        assertEquals(0, num("m.counts.review"))
        assertEquals("Kökeni belirsiz", str("m.items[0].label"))
        assertTrue(bool("DataSafety.canApprove(v, false)"))
        assertTrue(str("v.result").startsWith("Metin değişmez"))
    }

    @Test
    fun overwritingRoomNeedsTheCheckboxAndShowsBothVersions() {
        js(
            """
            var plan = { key: 'Bursa::101', city: 'Bursa', kind: 'CONTENT_MISMATCH', fields: ['title', 'content'], roomNewer: true, unknownOrigin: false,
              token: 'tok', local: NOTE, room: ROOM, options: [ option('SYNC_FROM_LOCAL', true, true, 'ROOM_MAY_BE_NEWER') ] };
            var v = DataSafety.planView(plan, DB);
            """
        )
        assertFalse(bool("DataSafety.canApprove(v, false)"))
        assertTrue(bool("DataSafety.canApprove(v, true)"))
        assertTrue(str("v.warning").contains("daha yeni"))
        assertTrue(str("v.keep").contains("korunur"))
        assertTrue(str("v.result").contains("yedeklenir"))
        assertEquals(2, num("v.rows.length"))
        assertEquals("Ulu Cami", str("v.rows[0].phone"))
        assertEquals("Ulu Cami (Room)", str("v.rows[0].room"))
        assertEquals("Room metni", str("v.rows[1].room"))
        assertEquals("tok", str("v.token"))
        assertTrue(str("v.text").contains("Farklı alanlar: Başlık, İçerik."))
    }

    @Test
    fun roomOnlyNoteShowsItsRoomTextAndNeedsConfirmation() {
        js(
            """
            var plan = { key: 'İzmir::7', city: 'İzmir', kind: 'ONLY_IN_ROOM', fields: [], roomNewer: false, unknownOrigin: false,
              token: 't', local: null, room: ROOM, options: [ option('MARK_DELETED_IN_ROOM', true, true, 'TOMBSTONES_ROOM_ROW') ] };
            var v = DataSafety.planView(plan, DB);
            """
        )
        assertEquals("Ulu Cami (Room)", str("v.title"))
        assertEquals("Telefonda yok", str("v.rows[0].phone"))
        assertEquals("Room metni", str("v.rows[1].room"))
        assertEquals("Bilinmiyor", str("v.rows[6].room"))
        assertFalse(bool("DataSafety.canApprove(v, false)"))
        assertTrue(str("v.result").contains("Metni silinmez"))
    }

    @Test
    fun batchOnlyCoversChangesThatOverwriteNothing() {
        js(
            """
            var m = DataSafety.model({ summary: summary({ applied: false }), items: [
              item('Bursa::101', 'MISSING_IN_ROOM'),
              item('Bursa::102', 'UNKNOWN_ORIGIN', 'REPORTED', { unknownOrigin: true }),
              item('Bursa::103', 'CONTENT_MISMATCH', 'REPORTED', { fields: ['title'] }),
              item('Bursa::104', 'ONLY_IN_ROOM'),
              item('Bursa::105', 'TOMBSTONE_CONFLICT'),
              item('Bursa::106', 'MISSING_IN_ROOM', 'REPAIRABLE'),
              item('Bursa::107', 'UNMATCHED_PENDING_WRITE', 'REPORTED', { lostWrites: 1 })
            ] }, null, DB, 'ready');
            """
        )
        assertEquals("Bursa::101,Bursa::102", str("m.batch.join(',')"))
        assertEquals(1, num("m.counts.repairable"))
        assertTrue(bool("m.preview"))
        assertFalse(bool("m.items[5].reviewable"))
    }

    @Test
    fun everyNativeOutcomeHasItsOwnMessage() {
        val codes = listOf(
            ResolutionOutcome.APPLIED, ResolutionOutcome.ALREADY_CONSISTENT, ResolutionOutcome.STALE,
            ResolutionOutcome.NOT_ALLOWED, ResolutionOutcome.CONFIRMATION_REQUIRED, ResolutionOutcome.UNDONE,
            ResolutionOutcome.ALREADY_UNDONE, ResolutionOutcome.UNDO_BLOCKED, ResolutionOutcome.NOTHING_TO_UNDO,
            ResolutionOutcome.NOT_FOUND,
        )
        val fallback = str("DataSafety.outcomeText('SOMETHING_ELSE')")
        val texts = codes.map { str("DataSafety.outcomeText('$it')") }
        texts.forEach { assertFalse(it == fallback) }
        assertEquals(codes.size, texts.toSet().size)
        assertTrue(str("DataSafety.outcomeText('STALE')").contains("Hiçbir şey uygulanmadı"))
    }

    @Test
    fun everyNativeReasonHasWording() {
        val reasons = listOf(
            ResolutionOption.ADDS_MISSING_ROW, ResolutionOption.OVERWRITES_ROOM_ROW, ResolutionOption.ROOM_MAY_BE_NEWER,
            ResolutionOption.ROOM_ORIGIN_UNKNOWN, ResolutionOption.CONFIRMS_ORIGIN, ResolutionOption.TOMBSTONE_NOT_REVIVED,
            ResolutionOption.TOMBSTONES_ROOM_ROW,
        )
        reasons.forEach { reason ->
            js(
                """
                var v = DataSafety.planView({ key: 'Bursa::101', city: 'Bursa', kind: 'CONTENT_MISMATCH', fields: ['title'], token: 't',
                  local: NOTE, room: ROOM, options: [ option('SYNC_FROM_LOCAL', true, true, '$reason') ] }, DB);
                """
            )
            assertTrue(reason, str("v.button").isNotEmpty() && str("v.result").isNotEmpty() && str("v.keep").isNotEmpty())
            if (reason != ResolutionOption.ADDS_MISSING_ROW && reason != ResolutionOption.CONFIRMS_ORIGIN &&
                reason != ResolutionOption.TOMBSTONE_NOT_REVIVED) {
                assertTrue(reason, str("v.confirm").isNotEmpty())
            }
        }
    }

    @Test
    fun historyOffersUndoOnlyWhereItIsSafe() {
        js(
            """
            var h = DataSafety.historyView({ backups: [
              { id: 4, key: 'Bursa::101', city: 'Bursa', action: 'SYNC_FROM_LOCAL', kind: 'CONTENT_MISMATCH', createdAt: 1, undoneAt: null, hadRow: true, undoable: true, title: 'Room adı' },
              { id: 3, key: 'İzmir::7', city: 'İzmir', action: 'MARK_DELETED_IN_ROOM', kind: 'ONLY_IN_ROOM', createdAt: 1, undoneAt: 5, hadRow: true, undoable: false, title: 'Yalnız Room' },
              { id: 2, key: 'Bursa::102', city: 'Bursa', action: 'SYNC_FROM_LOCAL', kind: 'MISSING_IN_ROOM', createdAt: 1, undoneAt: null, hadRow: false, undoable: false, title: 'Kebap' },
              { id: 1, key: 'Bursa::103', city: 'Bursa', action: 'SYNC_FROM_LOCAL', kind: 'UNKNOWN_ORIGIN', createdAt: 1, undoneAt: null, hadRow: true, undoable: false, title: '' }
            ] }, DB);
            """
        )
        assertEquals("Ulu Cami", str("h[0].title"))
        assertTrue(bool("h[0].undoable"))
        assertEquals("Room telefondaki notla güncellendi", str("h[0].label"))
        assertEquals("Geri alındı", str("h[1].note"))
        assertEquals("Room’da silindi olarak işaretlendi", str("h[1].label"))
        assertEquals("Geri alınacak önceki kayıt yok", str("h[2].note"))
        assertEquals("Room’a eklendi", str("h[2].label"))
        assertEquals("Kökeni doğrulandı", str("h[3].label"))
        assertTrue(str("h[3].note").contains("sonradan değiştiği"))
        assertEquals("(telefonda olmayan not)", str("h[3].title"))
    }

    @Test
    fun keysMatchTheNativeRoomKeys() {
        assertEquals(RoomNoteKeys.key("Bursa", "1762380000000123"), str("DataSafety.noteKey('Bursa', 1762380000000123)"))
        assertEquals(RoomNoteKeys.key("Ankara", "import-a"), str("DataSafety.noteKey('Ankara', 'import-a')"))
        assertEquals("Anıtkabir", str("DataSafety.findLocal(DB, 'Ankara::import-a').note.title"))
        assertTrue(bool("DataSafety.findLocal(DB, 'Ankara::101') === null"))
    }

    @Test
    fun modelFileNeverLogsOrTouchesStorage() {
        val source = assetFile("data-safety.js").readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        listOf("console.", "localStorage", "Android", "document.", "XMLHttpRequest", "fetch(").forEach {
            assertFalse(it, source.contains(it))
        }
    }

    private fun assetFile(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }
}
