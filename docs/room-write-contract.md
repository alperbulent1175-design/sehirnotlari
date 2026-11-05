# Room ana kayıt sistemi: uygulanacak yazma sözleşmesi

## Doğrulanan mevcut durum
- `app/src/main/assets/index.html`: `DB` açılışta `localStorage` üzerinden yükleniyor; `sv()` bütün nesneyi yazıyor.
- Yeni not, AI notu, yıldız, favori, düzenleme, silme, JSON içe aktarma, bulut geri yükleme ve tümünü temizleme `sv()` kullanıyor.
- `delNote` mevcut diziden kaydı kaldırıyor; eşitleme için kalıcı tombstone üretmiyor.

## Güvenli uygulama sözleşmesi
1. Android WebView açıldığında `RoomReady(snapshot)` olayı gelene kadar not yazma/silme kontrolleri devre dışı olmalı. Hata halinde eski veriyi sessizce sıfırlama.
2. Kotlin köprüsüne tekil `upsertNote(city, noteJson, requestId)`, `deleteNote(city,id,requestId)` ve `getNotes(requestId)` komutları bağlanmalı. Her komut asenkron başarı/hata yanıtı dönmeli.
3. Ekleme, düzenleme, favori, yıldız ve AI notu aynı upsert yolunu kullanmalı. Silme Room'da tombstone olarak saklanmalı; tümünü temizleme de tombstone üretmeli.
4. Başarılı Room yazımından sonra WebView'deki `DB` güncellenmeli ve ekran yenilenmeli; hata halinde kullanıcıya mesaj gösterilmeli. Çift tıklama/tekrarlanan istek için requestId ile idempotent davranış tasarlanmalı.
5. Kimlik karşılaştırması şehir + kimlik üzerinden yapılmalı; JavaScript sayısal kimlikleri geri dönerken türü korunmalı. Silinmiş kayıtlar yeni açılış snapshot'ında görünmemeli.
6. JSON içe aktarma ve bulut geri yükleme ayrı toplu işlem olarak uygulanmalı; kısmi hata ve tekrar deneme davranışı test edilmeli.
7. Android derleme, kapat-aç, çevrimdışı, aynı ID farklı şehir, silme ve tümünü temizleme testleri geçmeden `localStorage` yazımı kapatılmamalı.

## Sonraki kod adımı
Önce Kotlin tarafındaki mevcut `JavascriptInterface` metod imzaları ve DAO API'siyle bu sözleşmeyi karşılaştır; sonra en küçük uçtan uca ekleme/okuma yolunu bağla. Bu belge kod entegrasyonunun veya testlerin tamamlandığını iddia etmez.
