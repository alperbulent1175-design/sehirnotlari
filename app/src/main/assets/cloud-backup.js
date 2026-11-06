// Cloud backup texts and the restore plan (keep ES5 for Rhino). Read-only: the page imports the plan's
// notes through the same confirmed JSON import (applyImport), which never deletes or overwrites a note.
(function (root) {
  var MONTHS = ['Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık'];
  var SKIPS = {
    DISABLED: 'Otomatik yedekleme kapalı.',
    NOT_DUE: 'Otomatik yedek bugün zaten alındı.',
    UNCHANGED: 'Son yedekten beri not değişmedi; yeni yedek gerekmedi.',
    EMPTY: 'Yedeklenecek not yok.',
    FEWER_NOTES: 'Notlar son yedeğin yarısından az olduğu için otomatik yedek alınmadı; eski yedekler korunuyor. Gerekirse “Şimdi yedekle”yi kullanın.'
  };

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function when(ms) {
    var d = new Date(ms);
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear() + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function describe(meta) {
    return when(meta.createdAt) + ' · ' + meta.noteCount + ' not, ' + meta.cityCount + ' şehir';
  }

  function skipText(reason) { return SKIPS[reason] || ''; }

  function has(db, city, id) {
    var list = Object.prototype.hasOwnProperty.call(db, city) ? db[city] : null;
    if (!list) return false;
    for (var i = 0; i < list.length; i++) if (root.NoteIds.sameId(list[i].id, id)) return true;
    return false;
  }

  // Notes of the backup that were deleted on this phone stay deleted unless the user ticks the box;
  // notes already on the phone are skipped by the merge either way.
  function restorePlan(db, data, localDeleted, includeDeleted) {
    var deleted = {}, held = 0, out = {};
    for (var i = 0; i < (localDeleted || []).length; i++) deleted[localDeleted[i][0] + '::' + localDeleted[i][1]] = true;
    for (var city in data) {
      if (!Object.prototype.hasOwnProperty.call(data, city)) continue;
      var list = [];
      for (var j = 0; j < data[city].length; j++) {
        var n = data[city][j];
        if (deleted[city + '::' + String(n.id)] && !has(db, city, n.id)) {
          held++;
          if (!includeDeleted) continue;
        }
        list.push(n);
      }
      if (list.length) out[city] = list;
    }
    return { data: out, held: held };
  }

  root.CloudBackup = { when: when, describe: describe, skipText: skipText, restorePlan: restorePlan };
})(this);
