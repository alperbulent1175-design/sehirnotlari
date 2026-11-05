# Room + isteğe bağlı hesap / cihazlar arası senkronizasyon

## Mevcut durum (kod incelemesi)
- WebView notları `app/src/main/assets/index.html` içinde `localStorage` / `DB` nesnesine yazılıyor.
- `MainActivity.kt` içinde Firebase Realtime Database'e `backups/{Build.MODEL_Build.ID}` yolundan JSON yedekleme/geri yükleme yapılıyor.
- Bu yol kullanıcı kimliği değildir; farklı cihazlardan aynı notlara erişim sağlamaz. Mevcut Firebase güvenlik kuralları depoda doğrulanmadı.
- `importFromCloud` aynı ID'li mevcut kayıtları güncellemiyor ve silinmiş kayıtları senkronize etmiyor. Bu gerçek zamanlı senkronizasyon değildir.
- Firebase Authentication ve Room henüz entegre değil.

## Hedef
- Oturum açmadan yerel Room notları; Google girişi isteğe bağlı.
- Giriş yapan kullanıcıda yalnız kendi `uid` alanına bağlı Firebase Realtime Database verileri.
- Offline-first: Room tek yerel doğruluk kaynağı, Firebase uzak eşitleme kaynağı.
- WebView/Leaflet/Gemini mevcut davranışlarını koru.

## Uygulama sırası
1. Mevcut APK ve Firebase yedekleme davranışını test et; Firebase kurallarını kontrol et. Kurallar doğrulanmadan kişisel veri yükleme.
2. Room, KSP sürüm uyumu ve ilk şema: `Note(id, cityId, placeId?, title?, body, category?, rating?, createdAt, updatedAt, deletedAt?, syncState)`. Şehir/yer tablolarını gerçek UI veri modeline göre sonra ekle.
3. `@JavascriptInterface` üzerinden doğrulanmış JSON girişleri ve asenkron Room erişimi. WebView başlangıcında veritabanını yükle; hazır olmadan yazma işlemlerini engelle.
4. Google girişini isteğe bağlı ekle. Hesapsız notlar cihazda kalır; giriş sonrası kullanıcı onayıyla hesaba bağlanır.
5. `users/{uid}/notes/{noteId}` yapısına kullanıcı bazlı Firebase kuralları. Sunucu zaman damgası ve çakışma stratejisi, silme için tombstone.
6. WorkManager ile bağlantı gelince tekrar deneme; çoklu cihaz, çevrimdışı düzenleme, silme, çıkış/giriş testleri.
7. Başarılı testten sonra eski `localStorage` yazımını devre dışı bırak. Deneme verileri için aktarım zorunlu değil; fakat mevcut uygulama verileri otomatik silinmesin.

## Kabul kriterleri
- Oturumsuz not ekle/düzenle/sil/ara, uygulama yeniden açılınca kalır.
- İnternetsiz değişiklikler daha sonra tekil şekilde senkronize edilir.
- A ve B cihazında aynı hesapla aynı notlar görülür; başka hesap erişemez.
- Aynı notun iki cihazda düzenlenmesi ve silinmesi öngörülebilir sonuç verir.
- Harita, Gemini, Firebase mevcut özellikleri için regresyon testleri geçer.

Bu dalda şu anda yalnızca plan belgesi bulunur; çalışan Room entegrasyonu değildir.
