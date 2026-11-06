// Single-note deletion with a short undo window, shared by index.html and JVM tests (keep ES5 for Rhino).
// While the window is open the note is hidden from the page only: the saved copy (localStorage)
// still holds it and Room is not told. Room tombstones are never revived, so the Room delete is
// sent once the deletion is final. A marker key records an open window so a crash during it is
// recognised at the next start. Requires note-ids.js.
(function (root) {
  var MARKER_KEY = 'tur_v10_pending_delete';

  function indexOf(list, id) {
    if (!list || typeof list.length !== 'number') return -1;
    for (var i = 0; i < list.length; i++) {
      if (list[i] && root.NoteIds.sameId(list[i].id, id)) return i;
    }
    return -1;
  }

  // [db] with the pending note back at its old place and its city at its old key position.
  // [db] itself is not changed; only the pending note's city list is copied.
  function withPending(db, pending) {
    if (!pending) return db;
    var out = {}, keys = Object.keys(db), hasCity = Object.prototype.hasOwnProperty.call(db, pending.city);
    var cityAt = Math.min(Math.max(pending.cityIndex, 0), keys.length);
    for (var i = 0; i <= keys.length; i++) {
      if (!hasCity && i === cityAt) out[pending.city] = [pending.note];
      if (i === keys.length) break;
      var key = keys[i];
      if (key === pending.city) {
        var list = db[key].slice();
        if (indexOf(list, pending.note.id) < 0) list.splice(Math.min(pending.index, list.length), 0, pending.note);
        out[key] = list;
      } else {
        out[key] = db[key];
      }
    }
    return out;
  }

  // options: getDB(), setDB(db), storage {get, set, remove}, persist(), mirrorDelete(city, id),
  // schedule(fn, ms) -> handle, cancel(handle), now(), windowMs,
  // onChange(event, info): event is 'removed', 'undone', 'committed' or 'failed' (note restored).
  function create(options) {
    var o = options, pending = null, timer = null;

    function info(p) {
      return { city: p.city, id: p.note.id, title: String(p.note.title || '') };
    }
    function changed(event, p) {
      if (o.onChange) o.onChange(event, info(p));
    }
    function stopTimer() {
      if (timer !== null) o.cancel(timer);
      timer = null;
    }
    function clearMarker() {
      try { o.storage.remove(MARKER_KEY); } catch (e) { /* the next start resolves a stale marker */ }
    }

    // What the saved copy must contain right now.
    function view(db) {
      return withPending(db, pending);
    }

    function remove(city, id) {
      if (pending) commit();
      var db = o.getDB(), list = db[city], index = indexOf(list, id);
      if (index < 0) return null;
      var note = list[index];
      var p = { city: city, note: note, index: index, cityIndex: Object.keys(db).indexOf(city) };
      try {
        o.storage.set(MARKER_KEY, JSON.stringify({ city: city, id: String(note.id), title: String(note.title || ''), at: o.now() }));
      } catch (e) {
        return null;
      }
      list.splice(index, 1);
      if (!list.length) delete db[city];
      pending = p;
      timer = o.schedule(commit, o.windowMs);
      changed('removed', p);
      return info(p);
    }

    function undo() {
      if (!pending) return false;
      var p = pending;
      pending = null;
      stopTimer();
      o.setDB(withPending(o.getDB(), p));
      clearMarker();
      changed('undone', p);
      return true;
    }

    // Makes the deletion final: saved copy first, then Room. A failed save brings the note back.
    function commit() {
      if (!pending) return false;
      var p = pending;
      pending = null;
      stopTimer();
      try {
        o.persist();
      } catch (e) {
        o.setDB(withPending(o.getDB(), p));
        clearMarker();
        changed('failed', p);
        return false;
      }
      try {
        o.mirrorDelete(p.city, p.note.id);
      } catch (e) {
        changed('committed', p);
        return true; // marker kept: the next start sends the Room delete again
      }
      clearMarker();
      changed('committed', p);
      return true;
    }

    // At start, before Room reconciliation. A note still in the saved copy was never deleted
    // (the app stopped inside the window), so it is kept. A note already gone from the saved
    // copy was final there; its Room delete is (re)sent, which Room treats as idempotent.
    function recover() {
      var raw = null, marker = null;
      try { raw = o.storage.get(MARKER_KEY); } catch (e) { return null; }
      if (!raw) return null;
      try { marker = JSON.parse(raw); } catch (e) { marker = null; }
      if (!marker || typeof marker.city !== 'string' || !root.NoteIds.isValidId(marker.id)) {
        clearMarker();
        return { state: 'invalid', city: '', title: '' };
      }
      var title = String(marker.title || '');
      if (indexOf(o.getDB()[marker.city], marker.id) >= 0) {
        clearMarker();
        return { state: 'kept', city: marker.city, title: title };
      }
      try {
        o.mirrorDelete(marker.city, String(marker.id));
      } catch (e) {
        return { state: 'retry', city: marker.city, title: title };
      }
      clearMarker();
      return { state: 'finalized', city: marker.city, title: title };
    }

    return {
      view: view,
      remove: remove,
      undo: undo,
      commit: commit,
      recover: recover,
      pending: function () { return pending ? info(pending) : null; }
    };
  }

  root.NoteDelete = { MARKER_KEY: MARKER_KEY, withPending: withPending, create: create };
})(this);
