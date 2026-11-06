package com.sehirnotlari.data

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File

/**
 * The real assets/note-ids.js and note-delete.js under Rhino, wired the way index.html wires them:
 * `sv()` writes `tur_v10` from `ctrl.view(DB)`, `mirrorDelete` stands for `Android.deleteRoomNote`.
 * [boot] is one page load over the same localStorage map, so a crash is "boot again".
 */
class NoteDeleteJs : AutoCloseable {
    private val cx: Context = Context.enter().apply {
        optimizationLevel = -1
        languageVersion = Context.VERSION_ES6
    }
    private val scope: Scriptable = cx.initStandardObjects()

    init {
        listOf("note-ids.js", "note-delete.js").forEach { cx.evaluateString(scope, asset(it).readText(), it, 1, null) }
        js(HARNESS)
    }

    fun js(source: String): Any? = cx.evaluateString(scope, source, "test", 1, null)
    fun str(source: String): String = Context.toString(js(source))
    fun bool(source: String): Boolean = Context.toBoolean(js(source))
    fun num(source: String): Double = Context.toNumber(js(source))

    /** `tur_v10` exactly as stored, or null. */
    fun stored(): String? = js("storage.get('tur_v10')").let { if (it == null || it is org.mozilla.javascript.Undefined) null else Context.toString(it) }
    fun marker(): String? = js("storage.get(NoteDelete.MARKER_KEY)").let { if (it == null || it is org.mozilla.javascript.Undefined) null else Context.toString(it) }
    fun roomDeletes(): List<String> = str("roomDeletes.join('|')").split('|').filter { it.isNotEmpty() }

    fun seed(json: String) = js("store['tur_v10'] = ${org.json.JSONObject.quote(json)}; boot();")
    fun boot() = js("boot()")
    fun remove(city: String, id: String) = js("ctrl.remove(${org.json.JSONObject.quote(city)}, $id)")
    fun fireTimers() = js("fire()")

    override fun close() = Context.exit()

    private fun asset(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }

    private companion object {
        const val HARNESS = """
            var store = {}, failSet = {}, roomDeletes = [], events = [], timers = [], mirrorFails = false, DB = {}, ctrl = null;
            var storage = {
              get: function (k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
              set: function (k, v) { if (failSet[k]) throw new Error('QuotaExceededError'); store[k] = String(v); },
              remove: function (k) { delete store[k]; }
            };
            function sv() { storage.set('tur_v10', JSON.stringify(ctrl.view(DB))); }
            function boot() {
              try { DB = JSON.parse(store['tur_v10'] || '{}') || {}; } catch (e) { DB = {}; }
              timers = [];
              ctrl = NoteDelete.create({
                getDB: function () { return DB; },
                setDB: function (d) { DB = d; },
                storage: storage,
                persist: sv,
                mirrorDelete: function (city, id) {
                  if (mirrorFails) throw new Error('process died');
                  roomDeletes.push(city + '::' + String(id));
                },
                schedule: function (fn, ms) { timers.push({ fn: fn, ms: ms, live: true }); return timers.length - 1; },
                cancel: function (h) { timers[h].live = false; },
                now: function () { return 1762400000000; },
                windowMs: 8000,
                onChange: function (event, info) { events.push(event + ':' + info.title); }
              });
            }
            function fire() {
              for (var i = 0; i < timers.length; i++) if (timers[i].live) { timers[i].live = false; timers[i].fn(); }
            }
            function titles(db) {
              var out = [];
              Object.keys(db).forEach(function (c) { db[c].forEach(function (n) { out.push(c + '/' + n.title); }); });
              return out.join(',');
            }
        """
    }
}
