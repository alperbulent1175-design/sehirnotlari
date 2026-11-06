// Data-safety screen model shared by index.html and JVM tests (keep ES5 for Rhino).
// Pure functions only: no DOM, no bridge calls, no logging. Note text passes through here
// to be shown on screen and must never be written to the console.
(function (root) {
  var KINDS = {
    MISSING_IN_ROOM: {
      label: 'Room kopyasında eksik', group: 'review',
      text: 'Bu not telefondaki notlarınızda var ama Room kopyasında yok. Büyük olasılıkla kayıt sırasında uygulama kapandı.'
    },
    CONTENT_MISMATCH: {
      label: 'İçerik farklı', group: 'review',
      text: 'Not iki yerde de var ama bazı alanları farklı.'
    },
    TOMBSTONE_CONFLICT: {
      label: 'Room’da silinmiş', group: 'review',
      text: 'Bu not telefonda duruyor ama Room kopyasında silinmiş olarak işaretli. Silinmiş kayıtlar yalnız onayladığınız bir JSON geri yüklemesinin doğrulanmış kaydıyla geri getirilir; bu not için böyle bir kayıt yok. Telefondaki notunuz korunur.'
    },
    ONLY_IN_ROOM: {
      label: 'Yalnız Room’da', group: 'review',
      text: 'Bu not Room kopyasında var ama telefondaki notlarınızda yok. Telefonda silinmiş ya da hiç kaydedilememiş olabilir.'
    },
    UNKNOWN_ORIGIN: {
      label: 'Kökeni belirsiz', group: 'uncertain',
      text: 'Not iki yerde aynı. Room kaydı eski bir sürümden geldiği için yapay zekâ işareti ve değişiklik zamanı bilinmiyor.'
    },
    UNMATCHED_PENDING_WRITE: {
      label: 'Kaybolan değişiklik', group: 'info',
      text: 'Uygulama kapanırken yapılan bazı değişiklikler iki yere de kaydedilemedi. Metinleri saklanmadığı için geri getirilemez.'
    }
  };

  var FIELDS = [
    ['title', 'Başlık'], ['content', 'İçerik'], ['category', 'Kategori'], ['star', 'Yıldız'],
    ['color', 'Renk'], ['favorite', 'Favori'], ['isAI', 'Yapay zekâ notu'], ['date', 'Tarih']
  ];

  // Only a JSON restore record that matches the phone copy makes a Room deletion record repairable.
  var RESTORE_PENDING = 'Bu notu JSON yedeğinden geri yüklediniz ve telefona kaydedildi, ancak Room kopyası güncellenmeden işlem yarıda kaldı. ' +
    'Geri yükleme kaydı telefondaki notla doğrulandı. “Kanıtlı onarımları uygula” Room’daki silme kaydını kaldırır; işlem geri alınabilir.';

  var KEEP_LOCAL = 'Telefondaki notunuz (ana kayıt) korunur ve değişmez.';

  var OPTIONS = {
    ADDS_MISSING_ROW: {
      button: 'Room’a ekle',
      keep: KEEP_LOCAL,
      result: 'Room kopyasına bu notun bir kopyası eklenir. Room’da önceden kayıt olmadığı için üzerine yazılan bir şey yok.'
    },
    OVERWRITES_ROOM_ROW: {
      button: 'Room’u telefondaki notla güncelle',
      keep: KEEP_LOCAL,
      result: 'Room’daki farklı sürüm önce yedeklenir, sonra telefondaki notla değiştirilir. İşlem geri alınabilir.',
      confirm: 'Room’daki sürümün yedeklenip telefondaki notla değiştirileceğini anladım.'
    },
    ROOM_MAY_BE_NEWER: {
      button: 'Room’u telefondaki notla güncelle',
      keep: KEEP_LOCAL,
      result: 'Room’daki sürüm önce yedeklenir, sonra telefondaki notla değiştirilir. İşlem geri alınabilir.',
      warning: 'Dikkat: Room’daki sürüm daha yeni görünüyor. Telefondaki not bir çökmede son değişikliklerini kaybetmiş olabilir. Room’daki metni aşağıda görebilir, gerekirse notunuza elle ekleyebilirsiniz.',
      confirm: 'Room’daki daha yeni görünen sürümün yedeklenip telefondaki notla değiştirileceğini anladım.'
    },
    ROOM_ORIGIN_UNKNOWN: {
      button: 'Room’u telefondaki notla güncelle',
      keep: KEEP_LOCAL,
      result: 'Room’daki sürüm önce yedeklenir, sonra telefondaki notla değiştirilir. İşlem geri alınabilir.',
      warning: 'Room kaydının kökeni belirsiz; hangi sürümün daha yeni olduğu bilinmiyor.',
      confirm: 'Kökeni belirsiz Room sürümünün yedeklenip telefondaki notla değiştirileceğini anladım.'
    },
    CONFIRMS_ORIGIN: {
      button: 'Kökeni doğrula',
      keep: KEEP_LOCAL,
      result: 'Metin değişmez. Room kaydına yapay zekâ işareti ve değişiklik zamanı telefondaki nottan eklenir. İşlem geri alınabilir.'
    },
    TOMBSTONE_NOT_REVIVED: {
      button: 'Uygulanamaz',
      keep: KEEP_LOCAL,
      result: 'Hiçbir şey değişmez.',
      blocked: 'Silinmiş kayıtlar geri getirilmez. Bu fark yalnız bilgi amaçlıdır.'
    },
    TOMBSTONES_ROOM_ROW: {
      button: 'Room’da silindi olarak işaretle',
      keep: 'Telefondaki notlarınız değişmez; bu not telefonda zaten yok.',
      result: 'Room kaydı silinmiş olarak işaretlenir. Metni silinmez, yedekte ve kayıtta kalır. İşlem geri alınabilir.',
      confirm: 'Bu notun Room kopyasında silinmiş olarak işaretleneceğini anladım.'
    }
  };

  var OUTCOMES = {
    APPLIED: 'Uygulandı. Room kopyası güncellendi; telefondaki notlarınız değişmedi.',
    ALREADY_CONSISTENT: 'Değişiklik gerekmedi: iki kayıt zaten aynı.',
    STALE: 'Not bu arada değişti. Hiçbir şey uygulanmadı; güncel fark yeniden yüklendi.',
    NOT_ALLOWED: 'Bu işlem güvenli biçimde uygulanamaz. Hiçbir şey değişmedi.',
    CONFIRMATION_REQUIRED: 'Önce onay kutusunu işaretleyin. Hiçbir şey değişmedi.',
    UNDONE: 'Geri alındı. Room kaydı önceki hâline döndü.',
    ALREADY_UNDONE: 'Bu işlem zaten geri alınmış.',
    UNDO_BLOCKED: 'Not bu işlemden sonra değiştiği için geri alınamaz. Hiçbir şey değişmedi.',
    NOTHING_TO_UNDO: 'Geri alınacak bir şey yok: Room’da önceden kayıt yoktu.',
    NOT_FOUND: 'İşlem kaydı bulunamadı.'
  };

  function noteKey(city, id) {
    return city + '::' + String(id);
  }

  function findLocal(db, key) {
    if (!db) return null;
    for (var city in db) {
      if (!Object.prototype.hasOwnProperty.call(db, city)) continue;
      var notes = db[city];
      if (!notes || typeof notes.length !== 'number') continue;
      for (var i = 0; i < notes.length; i++) {
        var n = notes[i];
        if (n && n.id !== undefined && n.id !== null && noteKey(city, n.id) === key) return { city: city, note: n };
      }
    }
    return null;
  }

  function cityOf(key) {
    var i = key.indexOf('::');
    return i < 0 ? '' : key.substring(0, i);
  }

  function fieldLabel(name) {
    for (var i = 0; i < FIELDS.length; i++) if (FIELDS[i][0] === name) return FIELDS[i][1];
    return name;
  }

  function kindInfo(kind) {
    return KINDS[kind] || { label: 'Bilinmeyen fark', group: 'info', text: '' };
  }

  function titleFor(db, key, fallback) {
    var local = findLocal(db, key);
    if (local && local.note.title) return String(local.note.title);
    if (fallback) return String(fallback);
    return local ? '(başlıksız not)' : '(telefonda olmayan not)';
  }

  function itemView(item, db) {
    var info = kindInfo(item.kind);
    var fields = [];
    for (var i = 0; item.fields && i < item.fields.length; i++) fields.push(fieldLabel(item.fields[i]));
    var group = item.action === 'REPAIRABLE' ? 'repairable' : info.group;
    return {
      key: item.key,
      kind: item.kind,
      action: item.action,
      label: info.label,
      group: group,
      text: item.kind === 'TOMBSTONE_CONFLICT' && item.action === 'REPAIRABLE' ? RESTORE_PENDING : info.text,
      fields: fields,
      city: cityOf(item.key),
      title: titleFor(db, item.key),
      onPhone: !!findLocal(db, item.key),
      roomNewer: !!item.roomNewer,
      unknownOrigin: !!item.unknownOrigin,
      lostWrites: item.lostWrites || 0,
      reviewable: item.action === 'REPORTED' && item.kind !== 'UNMATCHED_PENDING_WRITE',
      // Never overwrites anything: adds a missing copy or confirms an identical one.
      batchable: item.action === 'REPORTED' && !item.roomNewer &&
        (item.kind === 'MISSING_IN_ROOM' || item.kind === 'UNKNOWN_ORIGIN')
    };
  }

  function count(items, test) {
    var n = 0;
    for (var i = 0; i < items.length; i++) if (test(items[i])) n++;
    return n;
  }

  // phase: 'checking' (no report yet), 'failed' (bridge error) or 'ready'.
  function model(report, status, db, phase) {
    var items = [];
    var source = (report && report.items) || [];
    for (var i = 0; i < source.length; i++) {
      if (source[i].action === 'REPAIRED') continue;
      items.push(itemView(source[i], db));
    }
    var summary = (report && report.summary) || {};
    var counts = {
      review: count(items, function (x) { return x.group === 'review'; }),
      uncertain: count(items, function (x) { return x.group === 'uncertain'; }),
      repairable: count(items, function (x) { return x.group === 'repairable'; }),
      repaired: status && status.repaired ? status.repaired.count : (summary.repaired || 0),
      lost: status && status.lost ? status.lost.count : (summary.lostWrites || 0)
    };
    var state, headline;
    if (phase === 'failed') {
      state = 'failed';
      headline = 'Kontrol tamamlanamadı. Notlarınız değişmedi; daha sonra yeniden deneyin.';
    } else if (phase !== 'ready' || !report) {
      state = 'checking';
      headline = 'Kontrol ediliyor…';
    } else if (counts.review > 0) {
      state = 'review';
      headline = 'İnceleme bekleyen ' + counts.review + ' fark var.';
    } else if (counts.repairable > 0) {
      state = 'repairable';
      headline = counts.repairable + ' fark kanıtla onarılabilir.';
    } else if (counts.uncertain > 0) {
      state = 'uncertain';
      headline = 'Fark yok, ancak ' + counts.uncertain + ' kaydın kökeni belirsiz.';
    } else {
      state = 'consistent';
      headline = 'Tutarlı: telefondaki notlarınız ve Room kopyası aynı.';
    }
    var batch = [];
    for (var j = 0; j < items.length; j++) if (items[j].batchable) batch.push(items[j].key);
    return {
      state: state,
      headline: headline,
      preview: !!(report && report.summary && report.summary.applied === false),
      counts: counts,
      totals: {
        phone: summary.legacy || 0,
        roomActive: summary.roomActive || 0,
        roomDeleted: summary.roomTombstones || 0
      },
      items: items,
      batch: batch,
      attention: counts.review + counts.uncertain + counts.repairable
    };
  }

  function value(name, note) {
    if (!note) return '—';
    var v = name === 'favorite' ? note.fav : note[name];
    if (name === 'star') return v ? new Array(v + 1).join('★') + ' (' + v + ')' : 'Yok';
    if (name === 'favorite') return v ? 'Evet' : 'Hayır';
    if (name === 'isAI') return v === null || v === undefined ? 'Bilinmiyor' : (v ? 'Evet' : 'Hayır');
    if (v === null || v === undefined || v === '') return 'Boş';
    return String(v);
  }

  function rows(plan) {
    var names = [];
    var i;
    if (plan.kind === 'CONTENT_MISMATCH') {
      names = plan.fields.slice(0);
    } else {
      for (i = 0; i < FIELDS.length; i++) names.push(FIELDS[i][0]);
    }
    var out = [];
    for (i = 0; i < names.length; i++) {
      out.push({
        field: names[i],
        label: fieldLabel(names[i]),
        phone: plan.local ? value(names[i], plan.local) : 'Telefonda yok',
        room: plan.room ? (plan.room.deleted && names[i] === 'title' ? value('title', plan.room) + ' (silinmiş)' : value(names[i], plan.room)) : 'Room’da yok',
        differs: plan.kind === 'CONTENT_MISMATCH'
      });
    }
    return out;
  }

  // Dialog content for one inspected note (plan from the native inspect call).
  function planView(plan, db) {
    var info = plan.kind ? kindInfo(plan.kind) : { label: 'Fark yok', text: 'Telefondaki not ve Room kopyası aynı.' };
    var option = plan.options && plan.options.length ? plan.options[0] : null;
    var words = option ? (OPTIONS[option.reason] || {}) : {};
    var fields = [];
    for (var i = 0; plan.fields && i < plan.fields.length; i++) fields.push(fieldLabel(plan.fields[i]));
    var text = info.text;
    if (plan.kind === 'CONTENT_MISMATCH' && fields.length) text += ' Farklı alanlar: ' + fields.join(', ') + '.';
    return {
      title: plan.local ? (plan.local.title || '(başlıksız not)') : (plan.room ? (plan.room.title || '(başlıksız not)') : titleFor(db, plan.key)),
      city: plan.city || cityOf(plan.key),
      kindLabel: info.label,
      text: text,
      keep: option ? words.keep : KEEP_LOCAL,
      result: option ? words.result : 'Yapılacak bir işlem yok.',
      warning: words.warning || '',
      blocked: option && !option.enabled ? (words.blocked || 'Bu işlem güvenli biçimde uygulanamaz.') : '',
      rows: plan.kind ? rows(plan) : [],
      action: option ? option.action : null,
      enabled: !!(option && option.enabled),
      needsConfirmation: !!(option && option.needsConfirmation),
      confirm: words.confirm || '',
      button: option ? (words.button || 'Uygula') : 'Uygula',
      token: plan.token
    };
  }

  // The approve button is usable only for an enabled option, after the checkbox when one is required.
  function canApprove(view, confirmed) {
    return !!(view && view.enabled && view.action && (!view.needsConfirmation || confirmed));
  }

  function outcomeText(code) {
    return OUTCOMES[code] || 'Beklenmeyen sonuç. Hiçbir şey değişmemiş olabilir; yeniden kontrol edin.';
  }

  function historyView(status, db) {
    var out = [];
    var list = (status && status.backups) || [];
    for (var i = 0; i < list.length; i++) {
      var b = list[i];
      var label;
      if (b.action === 'MARK_DELETED_IN_ROOM') label = 'Room’da silindi olarak işaretlendi';
      else if (b.kind === 'MISSING_IN_ROOM') label = 'Room’a eklendi';
      // Only a confirmed JSON restore replaces a Room deletion record.
      else if (b.kind === 'TOMBSTONE_CONFLICT') label = 'JSON yedeğinden geri yüklendi; Room’daki silme kaydı kaldırıldı';
      else if (b.kind === 'UNKNOWN_ORIGIN') label = 'Kökeni doğrulandı';
      else label = 'Room telefondaki notla güncellendi';
      out.push({
        id: b.id,
        title: titleFor(db, b.key, b.title),
        city: b.city,
        label: label,
        createdAt: b.createdAt,
        undone: b.undoneAt !== null && b.undoneAt !== undefined,
        undoable: !!b.undoable,
        note: b.undoneAt !== null && b.undoneAt !== undefined ? 'Geri alındı' :
          (b.undoable ? '' : (b.hadRow ? 'Not sonradan değiştiği için geri alınamaz' : 'Geri alınacak önceki kayıt yok'))
      });
    }
    return out;
  }

  root.DataSafety = {
    KINDS: KINDS,
    noteKey: noteKey,
    findLocal: findLocal,
    fieldLabel: fieldLabel,
    model: model,
    planView: planView,
    canApprove: canApprove,
    outcomeText: outcomeText,
    historyView: historyView
  };
})(this);
