# Room geçişi — 6 Kasım 2026 kod denetimi ve doğrulama kapısı

Kapsam: `feature/room-migration-validation`, salt kod incelemesi. Bu belge Android derlemesi veya cihaz testi yapıldığını iddia etmez.

## Doğrulanan mevcut pilot
- `index.html` içinde `tur_v10` / `localStorage` ana kayıt sistemi olarak çalışıyor.
- `mirrorNewNoteToRoom` yeni not, AI notu, düzenleme, puan ve favoride `upsertRoomNote` çağırıyor.
- `delNote` `deleteRoomNote` çağırıyor; Kotlin tombstone yazıyor.
- `requestRoomNotes` ve `onRoomNotesReady` yalnız karşılaştırma yapıyor; Room snapshot'ı ekrana yüklenmiyor.
- Kotlin `saveNotesToRoom` toplu içe aktarması var ama UI'da aktif bir güvenli ilk geçiş protokolü yok.

## P0 — Veri kaybı / yanlış durum riskleri
1. **Eski kayıtların ilk aktarımı:** Pilot yalnız yeni/değişen notları gölgeliyor. Eski `tur_v10` notlarının Room'a eksiksiz aktarılması doğrulanmadı. Room'u ana kaynak yapmayın.
2. **Sessiz okuma hatası:** `requestRoomNotes` catch yalnız Log.e yapıyor. JS hata/timeout durumunu ayırt edemiyor. Request ID + başarı/hata callback'i ve zaman aşımı görünürlüğü ekleyin.
3. **Eşzamanlı yazma sırası:** JS önce legacy DB'yi değiştiriyor; Kotlin çağrıları asenkron. Hızlı ardışık düzenle/sil akışlarında sonucun sırası ve hata kurtarma davranışı test edilmeli.
4. **Silme/geri getirme:** Tombstone'lu ID'ye upsert reddediliyor. Kullanıcının bilerek geri yükleme, JSON içe aktarma ve bulut geri yükleme davranışı açıkça tanımlanmalı.
5. **Kimlik tutarlılığı:** UI `Date.now()` ile sayısal ID kullanıyor; Kotlin `city::id` string saklıyor ve okurken `toLongOrNull()` ile geri çeviriyor. Aynı milisaniyede aynı şehirde iki not oluşması ve JS güvenli tamsayı sınırı test edilmeli.
6. **Alan kaybı:** JS AI notlarında `isAI` alanı var; Room `NoteEntity` ve snapshot'ı bu alanı korumuyor. Gidiş-dönüşte görünüm bilgisi kaybolabilir.
7. **Toplu işlemler:** JSON içe aktarma, bulut geri yükleme ve tümünü silme için tekil Room gölgelemesi doğrulanmadı; snapshot'ın silinmiş notları diriltmemesi garanti edilmeli.

## P1 — Minimum güvenli uygulama sırası
1. Eski `tur_v10` verisini değiştirmeden test yedeği alın; test cihazında örnek veri kümesi hazırlayın.
2. Kotlin `requestRoomNotes(requestId)` için hata/success callback sözleşmesi ve JS tarafında açık durum göstergesi ekleyin.
3. ID üretimini çakışmaya dayanıklı hale getirin; eski sayısal ID'leri bozmadan sürümleyin.
4. `isAI` dahil bütün kalıcı alanları Room gidiş-dönüş testine alın.
5. Silme, düzenleme, favori, yıldız, hızlı ardışık işlem ve offline senaryolarını otomatik test edin.
6. İlk aktarımı tek seferlik, idempotent, başarısızlıkta tekrar denenebilir ve veri sayısı/kimlikleri karşılaştırılabilir şekilde tasarlayın.
7. Tüm cihaz testleri geçene kadar `localStorage` ana kaynak kalsın; Firebase eşitlemesi ve `main` yayını kapalı kalsın.

## Test matrisi
- Eski APK'da 3 şehirde notlar; güncelleme; not sayısı, ID, içerik, tarih, puan, favori ve AI işareti eşitliği.
- Aynı şehirde art arda hızlı iki not; aynı ID farklı şehir.
- Not ekle → düzenle → sil → uygulamayı zorla kapat/aç; silinen not dönmemeli.
- Room yazma/okuma hatası ve çevrimdışı kullanım; kullanıcıya görünür hata.
- JSON içe aktarma ve tümünü silme; tombstone davranışı.
- `compileDebugKotlin`, `assembleDebug`, Android cihaz/emülatör smoke test.

**Geçiş kararı:** Yukarıdakiler doğrulanmadan Room'u tek doğruluk kaynağı yapmayın.
