// Note identity helpers shared by index.html and JVM tests (keep ES5 for Rhino).
// Stored ids are never rewritten: legacy notes keep their Date.now() numbers or imported strings.
// New ids stay numeric so Room keys remain `city::id` and numeric sorting keeps working.
(function (root) {
  var MAX_SAFE = 9007199254740991;
  var lastIssued = 0;

  // Any usable legacy id is kept as is; only missing, blank or non-primitive ids are replaced.
  function isValidId(id) {
    if (typeof id === 'number') return isFinite(id);
    if (typeof id === 'string') return id.replace(/^\s+|\s+$/g, '').length > 0;
    return false;
  }

  function idKey(id) {
    return String(id);
  }

  function sameId(a, b) {
    if (a === undefined || a === null || b === undefined || b === null) return false;
    return idKey(a) === idKey(b);
  }

  function usedIds(db) {
    var used = {};
    for (var city in db) {
      if (!Object.prototype.hasOwnProperty.call(db, city)) continue;
      var notes = db[city];
      if (!notes || typeof notes.length !== 'number') continue;
      for (var i = 0; i < notes.length; i++) {
        var note = notes[i];
        if (note && note.id !== undefined && note.id !== null) used[idKey(note.id)] = true;
      }
    }
    return used;
  }

  // Microsecond-scaled timestamp plus a random suffix: unique within this page through
  // lastIssued and the used-id scan, and unlikely to collide with another device's notes.
  function nextNoteId(db, now, random) {
    var ms = typeof now === 'number' ? now : Date.now();
    var suffix = typeof random === 'number' ? random : Math.floor(Math.random() * 1000);
    var candidate = Math.floor(ms) * 1000 + (Math.floor(suffix) % 1000);
    if (candidate <= lastIssued) candidate = lastIssued + 1;
    var used = usedIds(db || {});
    while (used[idKey(candidate)]) candidate++;
    if (candidate > MAX_SAFE) throw new Error('Note id exceeds safe integer range');
    lastIssued = candidate;
    return candidate;
  }

  // JavaScript literal for an inline onclick="…" attribute.
  function idArg(id) {
    if (typeof id === 'number') return String(id);
    return JSON.stringify(String(id))
      .replace(/&/g, '&amp;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function sortValue(id) {
    var value = Number(id);
    return isFinite(value) ? value : 0;
  }

  // Merges imported or restored notes without touching existing ones. Entries whose id
  // already exists in the city (compared as text, so "123" equals 123) are skipped.
  // Entries without a usable id get a new one; existing ids are kept as they are.
  function mergeNotes(db, incoming, onAdded) {
    var added = 0;
    if (!incoming || typeof incoming !== 'object') return added;
    for (var city in incoming) {
      if (!Object.prototype.hasOwnProperty.call(incoming, city)) continue;
      var notes = incoming[city];
      if (!notes || Object.prototype.toString.call(notes) !== '[object Array]') continue;
      var existing = db[city] || [];
      var seen = {};
      for (var i = 0; i < existing.length; i++) {
        if (existing[i] && existing[i].id !== undefined && existing[i].id !== null) seen[idKey(existing[i].id)] = true;
      }
      for (var j = 0; j < notes.length; j++) {
        var note = notes[j];
        if (!note || typeof note !== 'object') continue;
        if (!isValidId(note.id)) note.id = nextNoteId(db);
        var key = idKey(note.id);
        if (seen[key]) continue;
        if (!db[city]) db[city] = existing;
        db[city].push(note);
        seen[key] = true;
        added++;
        if (onAdded) onAdded(city, note);
      }
    }
    return added;
  }

  root.NoteIds = {
    MAX_SAFE: MAX_SAFE,
    isValidId: isValidId,
    sameId: sameId,
    nextNoteId: nextNoteId,
    idArg: idArg,
    sortValue: sortValue,
    mergeNotes: mergeNotes
  };
})(this);
