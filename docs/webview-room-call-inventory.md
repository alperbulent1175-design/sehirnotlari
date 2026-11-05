# WebView → Room geçişi: doğrulanan çağrı envanteri

Kaynak: `app/src/main/assets/index.html`, `feature/room-sync-foundation`.

- `DB` açılışta `localStorage.getItem(NKEY)` ile okunuyor (satır 530).
- `sv()` tüm `DB` nesnesini `localStorage` içine yazıyor (satır 531).
- Yeni not oluşturma ve yıldız düzenleme `sv()` çağırıyor (822, 833, 932).
- `delNote(city,id)` notu diziden çıkarıp `sv()` çağırıyor (939).
- `toggleFav(city,id)` favori bilgisini değiştirip `sv()` çağırıyor (940).
- `saveEdit()` notu güncelleyip `sv()` çağırıyor (966).
- JSON içe aktarma, tümünü silme ve diğer değişiklikler de `sv()` çağırıyor (1037-1067).

## Uygulama sırası
1. Room'dan açılış verisi yükleme callback'i ve hazır/hata durumu oluştur; veri gelmeden yazma işlemi başlatma.
2. Her not için şehir + kimlik birleşimini koruyarak tekil upsert ve tombstone silme işlemleri kullan.
3. Yeni not, düzenleme, yıldız, favori, silme, JSON içe aktarma ve tümünü silme akışlarını tekil Room komutlarına bağla.
4. Başarılı Room yazımı ardından UI güncelle; hata olursa kullanıcıya bildir.
5. Kapat/aç, çevrimdışı, aynı ID farklı şehir, silinen notun geri gelmemesi, kategori/tarih/puan/favori testlerini çalıştır.
6. Android derlemesi ve cihaz testleri geçmeden `localStorage` yolunu kaldırma veya Firebase eşitlemesini açma.

Bu envanter uygulama entegrasyonunun tamamlandığı anlamına gelmez.