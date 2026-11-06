// Checks a notes JSON file and previews its import before anything is merged (keep ES5 for Rhino).
// Read-only: the page merges with NoteIds.mergeNotes only after the user confirms the preview.
(function (root) {
  var MAX_BYTES = 5 * 1024 * 1024;
  var MAX_NOTES = 20000;
  var MAX_CITY_CHARS = 100;
  // Keys that would reach Object.prototype when the page indexes its notes by city.
  // Compared as strings: an object literal with a '__proto__' key would set the prototype instead.
  function badKey(city) { return city === '__proto__' || city === 'constructor' || city === 'prototype'; }
  var NOT_NOTES = 'Bu dosya bir Şehir Notları yedeği değil.';

  function fail(message) { return { ok: false, error: message }; }
  function isPlainObject(v) { return v !== null && typeof v === 'object' && Object.prototype.toString.call(v) === '[object Object]'; }
  function isArray(v) { return Object.prototype.toString.call(v) === '[object Array]'; }
  function optionalText(v) { return v === undefined || v === null || typeof v === 'string'; }

  // The whole file is refused when any part of it is unexpected, so an import is all or nothing.
  function parse(text) {
    if (typeof text !== 'string') return fail('Dosya okunamadı.');
    if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
    if (!/\S/.test(text)) return fail('Dosya boş.');
    var data;
    try { data = JSON.parse(text); } catch (e) { return fail('Dosya geçerli bir JSON değil.'); }
    if (!isPlainObject(data)) return fail(NOT_NOTES);
    var cities = 0, notes = 0;
    for (var city in data) {
      if (!Object.prototype.hasOwnProperty.call(data, city)) continue;
      if (badKey(city) || !/\S/.test(city) || city.length > MAX_CITY_CHARS) return fail('Dosyada geçersiz bir şehir adı var.');
      var list = data[city];
      if (!isArray(list)) return fail(NOT_NOTES);
      for (var i = 0; i < list.length; i++) {
        var n = list[i];
        if (!isPlainObject(n) || !optionalText(n.title) || !optionalText(n.content)) return fail('Dosyada okunamayan bir not var.');
      }
      if (list.length) cities++;
      notes += list.length;
      if (notes > MAX_NOTES) return fail('Dosyada çok fazla not var.');
    }
    if (!notes) return fail('Dosyada içe aktarılacak not yok.');
    return { ok: true, data: data, cities: cities, notes: notes };
  }

  // Runs the real merge on copies, so "added" is exactly what confirming would add.
  function preview(db, data) {
    var copy = JSON.parse(JSON.stringify(db || {}));
    var added = root.NoteIds.mergeNotes(copy, JSON.parse(JSON.stringify(data)));
    var total = 0;
    for (var city in data) if (Object.prototype.hasOwnProperty.call(data, city)) total += data[city].length;
    return { added: added, skipped: total - added };
  }

  function fileName(date) { return 'turkiye-notlari-' + date.toISOString().slice(0, 10) + '.json'; }

  function count(db) {
    var n = 0;
    for (var city in db) if (Object.prototype.hasOwnProperty.call(db, city) && isArray(db[city])) n += db[city].length;
    return n;
  }

  root.NotesFile = { MAX_BYTES: MAX_BYTES, parse: parse, preview: preview, fileName: fileName, count: count };
})(this);
