// Filters existing note fields; hashtags in a title or body act as tags without a data migration.
(function (root) {
  'use strict';
  var MONTHS = { oca: '01', sub: '02', mar: '03', nis: '04', may: '05', haz: '06', tem: '07', agu: '08', eyl: '09', eki: '10', kas: '11', ara: '12' };
  function fold(s) { return String(s || '').toLocaleLowerCase('tr-TR').replace(/ı/g, 'i').replace(/ğ/g, 'g').replace(/ü/g, 'u').replace(/ş/g, 's').replace(/ö/g, 'o').replace(/ç/g, 'c'); }
  function dateKey(raw) {
    var s = String(raw || '').trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s;
    var m = /^(\d{1,2})\s+([^\s.]+)\.?\s+(\d{4})$/.exec(s);
    if (!m) return '';
    var month = MONTHS[fold(m[2]).slice(0, 3)];
    var day = Number(m[1]);
    return month && day >= 1 && day <= 31 ? m[3] + '-' + month + '-' + ('0' + day).slice(-2) : '';
  }
  function tags(note) {
    var found = (String(note.title || '') + ' ' + String(note.content || '')).match(/#[A-Za-zÇĞİÖŞÜçğıöşü0-9_-]+/g) || [];
    return found.map(function (tag) { return fold(tag.slice(1)); });
  }
  function matches(city, note, f) {
    if (f.city && f.city !== city) return false;
    if (f.minStar && Number(note.star || 0) < Number(f.minStar)) return false;
    if (f.tag && tags(note).indexOf(fold(f.tag)) < 0) return false;
    if (f.from || f.to) {
      var date = dateKey(note.date);
      if (!date || (f.from && date < f.from) || (f.to && date > f.to)) return false;
    }
    var q = fold(f.query).trim();
    return !q || fold(city + ' ' + (note.title || '') + ' ' + (note.content || '') + ' ' + (note.category || '')).indexOf(q) >= 0;
  }
  root.NoteSearch = { dateKey: dateKey, tags: tags, matches: matches };
})(typeof window !== 'undefined' ? window : this);
