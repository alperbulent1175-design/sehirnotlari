// Room-first prototype (debug builds only). The page shows Room's committed notes and sends
// every change as one request; a note counts as saved only after native reports that the Room
// transaction committed. One request per note is in flight; edits made meanwhile are coalesced
// (the latest full note wins) and sent with the ticket of the version they were made from.
// In-flight requests are kept in an outbox and re-sent unchanged after a reload; native compares
// the request's base ticket with Room's row, so a request whose reply was lost is never applied twice.
// Pure logic: no DOM, no console, no direct storage access (storage is injected).
(function (root) {
  'use strict';

  var OUTBOX_KEY = 'room_first_outbox';

  var STATUS_TEXT = {
    saving: 'Kaydediliyor…',
    deleting: 'Siliniyor…',
    saved: 'Room’a kaydedildi',
    removed: 'Not silindi',
    error: 'Kaydedilemedi; değişiklik uygulanmadı',
    conflict: 'Not başka bir işlemle değişmiş; değişikliğiniz kaydedilmedi. Güncel hâli gösteriliyor.',
    deleted: 'Bu not silinmiş; geri getirilmez. Değişikliğiniz kaydedilmedi.'
  };

  // Pre-switch audit blockers (native RoomFirstPreflight keys).
  var BLOCKERS = {
    skippedLegacy: 'Okunamayan yerel not',
    missingInRoom: 'Room kopyasında eksik not',
    contentMismatch: 'İçeriği farklı not',
    roomNewer: 'Room’da daha yeni olabilecek not',
    tombstoneConflict: 'Room’da silinmiş ama telefonda duran not',
    onlyInRoom: 'Yalnız Room’da olan not',
    unknownOrigin: 'Kökeni belirsiz kayıt',
    unmatchedPending: 'Hiçbir yere ulaşmamış yazma',
    repairable: 'Kanıtla onarılabilir fark',
    unresolvedJournal: 'Tamamlanmamış yazma kaydı',
    changedSince: 'Denetim sırasında değişen not'
  };

  function keyOf(city, id) { return city + '::' + String(id); }

  function copyNote(n) {
    var o = {
      id: n.id, title: n.title || '', content: n.content || '', category: n.category || 'Genel',
      star: n.star || 0, color: n.color || '', fav: !!n.fav, date: n.date || ''
    };
    if (n.isAI !== undefined && n.isAI !== null) o.isAI = !!n.isAI;
    return o;
  }

  function blockerList(preflight) {
    var out = [], b = (preflight && preflight.blockers) || {};
    Object.keys(BLOCKERS).forEach(function (k) {
      if (b[k] > 0) out.push({ key: k, count: b[k], label: BLOCKERS[k] });
    });
    return out;
  }

  function createStore(opts) {
    var send = opts.send, storage = opts.storage, newId = opts.newId;
    var sortValue = opts.sortValue || function (id) { var n = Number(id); return isFinite(n) ? n : 0; };
    var rows = {};       // key -> {city, note, ticket}: Room's committed state
    var notes = {};      // key -> {city, id, inflight, pending, status}
    var byRequest = {};  // requestId -> key
    var ready = false, seq = 0;

    function entryFor(key, city, id) {
      return notes[key] || (notes[key] = { city: city, id: id, inflight: null, pending: null, status: null });
    }

    function readOutbox() {
      try {
        var list = JSON.parse(storage.get(OUTBOX_KEY) || '[]');
        return Array.isArray(list) ? list : [];
      } catch (e) { return []; }
    }

    function writeOutbox() {
      var list = [];
      Object.keys(notes).forEach(function (k) { if (notes[k].inflight) list.push(notes[k].inflight); });
      list.sort(function (a, b) { return a.seq - b.seq; });
      storage.set(OUTBOX_KEY, JSON.stringify(list));
    }

    function payload(op) {
      var p = { op: op.op, city: op.city, base: op.base };
      if (op.op === 'UPSERT') p.note = op.note; else p.id = op.id;
      return JSON.stringify(p);
    }

    function transmit(key, op) {
      var e = notes[key];
      e.inflight = op;
      e.status = op.op === 'DELETE' ? 'deleting' : 'saving';
      byRequest[op.requestId] = key;
      writeOutbox();
      try {
        send(payload(op), op.requestId);
      } catch (err) {
        // The bridge call never happened, so nothing can have reached Room.
        delete byRequest[op.requestId];
        e.inflight = null;
        e.pending = null;
        e.status = 'error';
        writeOutbox();
      }
    }

    function dispatch(key) {
      var e = notes[key];
      if (!e || e.inflight || !e.pending) return;
      var op = e.pending;
      e.pending = null;
      op.base = rows[key] ? rows[key].ticket : 0;
      op.requestId = newId();
      op.seq = ++seq;
      transmit(key, op);
    }

    function change(city, id, op) {
      if (!ready) return false;
      var key = keyOf(city, id), e = entryFor(key, city, id);
      e.pending = op;
      if (!e.inflight) dispatch(key);
      else e.status = op.op === 'DELETE' ? 'deleting' : 'saving';
      return true;
    }

    function applyRow(key, row) {
      if (row && !row.deleted) rows[key] = { city: row.city, note: copyNote(row), ticket: row.ticket };
      else delete rows[key];
    }

    function cityLists(includeIntents) {
      var db = {}, seen = {};
      function put(city, note) { (db[city] || (db[city] = [])).push(note); }
      Object.keys(rows).forEach(function (k) {
        var e = notes[k], intent = includeIntents && e && (e.pending || e.inflight);
        seen[k] = true;
        put(rows[k].city, intent && intent.op === 'UPSERT' ? copyNote(intent.note) : copyNote(rows[k].note));
      });
      if (includeIntents) {
        Object.keys(notes).forEach(function (k) {
          var e = notes[k], intent = e.pending || e.inflight;
          if (!seen[k] && intent && intent.op === 'UPSERT') put(e.city, copyNote(intent.note));
        });
      }
      Object.keys(db).forEach(function (city) {
        db[city].sort(function (a, b) { return sortValue(a.id) - sortValue(b.id); });
      });
      return db;
    }

    return {
      isReady: function () { return ready; },

      hydrate: function (snapshot) {
        rows = {};
        var cities = (snapshot && snapshot.notes) || {};
        Object.keys(cities).forEach(function (city) {
          (cities[city] || []).forEach(function (row) { applyRow(row.key || keyOf(city, row.id), row); });
        });
        ready = true;
      },

      /** Re-sends requests that were in flight when the page went away, with their original ids. */
      resume: function () {
        var list = readOutbox().sort(function (a, b) { return a.seq - b.seq; }), sent = 0;
        list.forEach(function (op) {
          if (!op || !op.requestId || !op.city || op.id === undefined) return;
          var key = keyOf(op.city, op.id), e = entryFor(key, op.city, op.id);
          if (e.inflight) return;
          seq = Math.max(seq, op.seq || 0);
          transmit(key, op);
          sent++;
        });
        return sent;
      },

      save: function (city, note) {
        return change(city, note.id, { op: 'UPSERT', city: city, id: note.id, note: copyNote(note) });
      },

      remove: function (city, id) {
        return change(city, id, { op: 'DELETE', city: city, id: id });
      },

      /** Handles a native reply; returns what the page should show. Unknown ids change nothing. */
      onResult: function (requestId, result, error) {
        var key = byRequest[requestId];
        if (key === undefined) return { unknown: true };
        delete byRequest[requestId];
        var e = notes[key], op = e.inflight, out = { key: key, op: op.op, roomChanged: false, ticket: null };
        e.inflight = null;
        if (error || !result) {
          out.status = 'error';
        } else {
          applyRow(key, result.row);
          out.roomChanged = true;
          out.ticket = result.row ? result.row.ticket : null;
          if (result.saved) out.status = op.op === 'DELETE' ? 'removed' : 'saved';
          else if (result.status === 'CONFLICT') out.status = 'conflict';
          else if (result.status === 'DELETED') out.status = 'deleted';
          else out.status = 'error';
          if (out.status === 'conflict' || out.status === 'deleted') e.pending = null;
        }
        out.notice = STATUS_TEXT[out.status];
        if (e.pending) {
          dispatch(key);
        } else {
          e.status = out.status === 'removed' ? null : out.status;
          if (!e.status) delete notes[key];
        }
        writeOutbox();
        out.followUp = !!(notes[key] && notes[key].inflight);
        return out;
      },

      /** Notes as the page shows them: committed rows with unsent or unconfirmed edits on top. */
      view: function () { return cityLists(true); },

      /** Room's committed notes only (the phone copy is rebuilt from this when switching back). */
      committed: function () { return cityLists(false); },

      statusOf: function (city, id) {
        var e = notes[keyOf(city, id)];
        return e && e.status ? { status: e.status, text: STATUS_TEXT[e.status] } : null;
      },

      ticketOf: function (city, id) {
        var r = rows[keyOf(city, id)];
        return r ? r.ticket : null;
      },

      pendingCount: function () {
        return Object.keys(notes).filter(function (k) { return notes[k].inflight || notes[k].pending; }).length;
      }
    };
  }

  root.RoomFirst = {
    OUTBOX_KEY: OUTBOX_KEY,
    STATUS_TEXT: STATUS_TEXT,
    BLOCKERS: BLOCKERS,
    keyOf: keyOf,
    blockerList: blockerList,
    createStore: createStore
  };
})(typeof window !== 'undefined' ? window : this);
