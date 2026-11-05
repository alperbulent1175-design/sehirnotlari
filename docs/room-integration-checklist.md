# Room geçişi: mevcut koddan doğrulanan bulgular

## Kritik uyumluluk sorunları
- WebView `DB` kaydı `tur_v10` anahtarıyla localStorage'da tutuluyor; `sv()` dokuz yerde çağrılıyor.
- Not kimlikleri `Date.now()` ile sayısal oluşturuluyor; mevcut Room modeli string ID kullanıyor. JSON dönüşünde sayı/string tipi korunmazsa `find(n => n.id === id)` ile düzenleme/silme bozulabilir.
- Room köprüsü henüz UI tarafından çağrılmıyor. `requestRoomNotes()` sonucunu karşılayan `window.onRoomNotesReady` tanımlı değil.
- `saveNotesToRoom` mevcut kayıtları upsert ediyor ama silinmiş kayıtları temizlemiyor; bu yüzden silme senkronizasyonu için kullanılamaz.
- Room veri modeline `dateLabel` eklendi; diğer eski kayıt alanlarının birebir korunması ve test edilmesi gerekiyor.
- Aynı notun iki cihazda değiştirilmesi için henüz çakışma çözümü yok.

## Uygulamaya bağlamadan önce
1. Android Gradle/KSP/Room sürümlerini CI üzerinde derleyerek doğrula.
2. Not ID türünü (Long ya da String) WebView ve Room'da aynı tut; iki farklı şehirde aynı ID durumunu ele al.
3. Kaydetme işlemlerini tekil upsert/delete komutlarıyla tasarla; tüm DB snapshot'ını silmeleri hesaba katmadan içeri aktarma.
4. WebView ilk açılışında Room hazır olmadan UI yazımını engelle; hata durumunda kullanıcıya bildir.
5. Ekle, düzenle, sil, favori, puan, kategori, tarih, uygulama yeniden açma ve çevrimdışı testlerini çalıştır.
6. Bunlar geçmeden localStorage'ı kaldırma veya Firebase eşitlemesini etkinleştirme.

Bu belge uygulamanın çalıştığını iddia etmez; derleme/cihaz testi bekleniyor.
