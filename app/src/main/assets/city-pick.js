// City lookup for the "+ Not Ekle" picker and the note counts shown per city (keep ES5 for Rhino).
// Read-only over the page's DB: nothing here stores notes or invents a city or position.
(function (root) {
  var FOLD = { 'ı': 'i', 'ş': 's', 'ğ': 'g', 'ü': 'u', 'ö': 'o', 'ç': 'c', 'â': 'a', 'î': 'i', 'û': 'u' };

  // "Şanlıurfa", "SANLIURFA" and "sanliurfa" all fold to "sanliurfa". Plain toLowerCase keeps
  // engines alike: "İ" becomes "i" plus a combining dot, which is dropped.
  function fold(text) {
    return String(text === undefined || text === null ? '' : text).toLowerCase()
      .replace(/\u0307/g, '')
      .replace(/[ışğüöçâîû]/g, function (ch) { return FOLD[ch]; })
      .replace(/^\s+|\s+$/g, '');
  }

  function count(db, city) {
    var notes = db && Object.prototype.hasOwnProperty.call(db, city) ? db[city] : null;
    return notes && typeof notes.length === 'number' ? notes.length : 0;
  }

  // Without a query: cities that already have notes first, then the rest, each alphabetically.
  // With a query: names starting with it first, then names containing it.
  function search(query, cities, db) {
    var q = fold(query);
    var rows = [];
    for (var i = 0; i < cities.length; i++) {
      var name = fold(cities[i]);
      var at = q ? name.indexOf(q) : 0;
      if (at < 0) continue;
      var n = count(db, cities[i]);
      rows.push({ city: cities[i], count: n, rank: q ? (at === 0 ? 0 : 1) : (n > 0 ? 0 : 1) });
    }
    rows.sort(function (a, b) { return a.rank - b.rank || a.city.localeCompare(b.city, 'tr'); });
    return rows.map(function (r) { return { city: r.city, count: r.count }; });
  }

  root.CityPick = { fold: fold, count: count, search: search };
})(this);
