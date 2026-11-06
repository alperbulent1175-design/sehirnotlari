# DEVAM

## 2026-11-06 — P5 Android cihaz doğrulaması

- **Dal:** `feature/room-migration-validation` (başlangıç `5f588d8`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı (emülatörde; fiziksel cihaz yok)
- **Ne yapıldı:** Kurulu API 37 imajından `SehirNotlari_P5_Test` AVD'si oluşturuldu (WHPX). Gerçek v1 APK'sıyla (`c8d65b7`) yazılmış test veritabanı, v2 APK'sı üstüne kurularak cihazda yükseltildi: satırlar, kimlikler, tombstone ve `localStorage` korundu. Oluştur/düzenle/favori/sil, döndürme, zorla durdurma, normal çıkış ve hızlı art arda kayıt senaryolarında Room ile `localStorage` alan alan tutarlı kaldı; Logcat'te başarı ve hata bildirimleri iki katmanda da görüldü. Köprüye içeriksiz Logcat kayıtları ve debug'a özel WebView hata ayıklaması eklendi. `assembleDebug` + `testDebugUnitTest` 67/67 geçti. Rapor güncellendi.
- **Sonraki tek somut adım:** Fiziksel cihaz veya eski API'li AVD'de aynı yükseltmeyi tekrarla; süreç ölümünde Room gölge sapmasını giderecek yeniden eşitleme tasarımını planla.

## 2026-11-06 — P4 Room şema geçişi v1 → v2

- **Dal:** `feature/room-migration-validation` (başlangıç `c8d65b7`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı
- **Ne yapıldı:** Room v2: `isAI` (boş olabilir), yazma sırası için `writeTicket` (eski `updatedAt` aynen taşındı), gerçek değişiklik zamanı için `modifiedAt` (v1 satırlarında `NULL`, tahmin yok). Açık `MIGRATION_1_2` tabloyu tek işlemde yeniden kurar. Kimlikler, tombstone'lar ve tüm alanlar korunur. Şemalar `app/schemas` altında (v1 dahil). Sıralayıcı, DAO, eski not aktarımı ve köprü yeni şemaya uyarlandı. Robolectric ile gerçek v1 dosyası taşındı; yarıda kalan migration'ın geri alındığı doğrulandı. `assembleDebug` + `testDebugUnitTest` 66/66 geçti. Rapor güncellendi.
- **Sonraki tek somut adım:** v1 APK ile not yazılmış bir cihaza/AVD'ye bu sürümü kurup migration'ı gerçek dosyada doğrula; ardından Room'u ana kaynak yapma planı.

## 2026-11-06 — P3 not kimliği güvenliği

- **Dal:** `feature/room-migration-validation` (başlangıç `933ff7a`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı
- **Ne yapıldı:** İki `Date.now()` kimlik üretimi `note-ids.js` içindeki `NoteIds.nextNoteId` ile değişti (ms×1000 + rastgele, oturumda artan, mevcut kimliklerden kaçınan, güvenli tam sayı). Aramalar `sameId`, `onclick` kimlikleri `idArg`, JSON içe aktarma ve bulut geri yükleme `mergeNotes` kullanıyor. Kotlin `RoomNoteKeys` ile `city::id` anahtarı ve WebView’a dönen kimlik tek yerde. Eski kimlikler değişmedi, şema değişmedi. `assembleDebug` + `testDebugUnitTest` 54/54 geçti (Rhino ile JS testleri dahil). Rapor güncellendi.
- **Sonraki tek somut adım:** Cihaz/AVD’de karışık kimlikli notlarla kaydet–düzenle–sil–içe aktar duman testi; çoklu cihaz eşitlemesinden önce UUID/cihaz kimliği kararı.

## 2026-11-06 — P2 yaşam döngüsü sıralaması

- **Dal:** `feature/room-migration-validation` (başlangıç `9e218118`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı
- **Ne yapıldı:** Activity’ye bağlı sıralayıcı/kilit ve `lifecycleScope` iptali riskleri giderildi. Kalıcı monoton bilet `updatedAt` içinde (şema değişmedi), süreç genelinde tek sıralayıcı, `requestId` tekrar koruması, tombstone zamanı korunuyor. `assembleDebug` + `testDebugUnitTest` 36/36 geçti. Rapor güncellendi.
- **Sonraki tek somut adım:** Cihaz/AVD’de döndürme ve süreç öldürme duman testi; Room’u ana kaynak yapma.

## 2026-11-06 — P1 yazma sırası

- **Dal:** `feature/room-migration-validation` (başlangıç `6100501`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı
- **Ne yapıldı:** Gecikmiş `upsertRoomNote` ezmesi tespit edildi. `RoomNoteWriteSequencer` bilet + tombstone koruması eklendi; not kimlikleri değişmedi. `assembleDebug` + `testDebugUnitTest` 27/27 geçti. Rapor güncellendi.
- **Sonraki tek somut adım:** Cihaz/AVD duman testi; Room’u ana kaynak yapma.

## 2026-11-06 — Room aktarımı doğrulama

- **Dal:** `feature/room-migration-validation` (`main` birleştirilmedi)
- **Durum:** tamamlandı (doğrulama partisi)
- **Ne yapıldı:** GitHub’dan dal çekildi. KSP 2.3.13 ile `assembleDebug` geçti. Opt-in importer çıkarıldı; altı aktarım senaryosu + WebView callback + otomatik geçiş yasağı birim testleri yazıldı (`testDebugUnitTest` 21/21). `docs/room-validation-report.md` eklendi. Emülatör/AVD yok; cihaz duman testi yapılmadı. Otomatik Room aktarımı kapalı kaldı.
- **Sonraki tek somut adım:** AVD veya fiziksel cihazda uygulamayı açıp mevcut `tur_v10` notlarının durduğunu doğrula; Room’u ana kaynak yapma.

## 2026-11-06 — masaüstü çalışma kopyası hazır

- **Durum:** tamamlandı (hazırlık)
- **Ne yapıldı:** GitHub `main` (`alperbulent1175-design/sehirnotlari`) masaüstünde `C:/Users/crawi/Desktop/sehirnotlari` olarak güncel. `origin/main` ile senkron. Android Studio kopyasından gitignore’lı `local.properties` (SDK yolu + Gemini anahtarı) alındı; commit edilmedi.
- **Sürüm notu:** kaynak `versionName` hâlâ `"1.0"` / `versionCode` `1`. GitHub Releases’te yayımlanmış etiketler: `v1.1`, `v1.2` (latest).
- **Test:** `$env:JAVA_HOME` = Android Studio JBR ile `.\gradlew.bat compileDebugKotlin` **BUILD SUCCESSFUL** (4m 43s). İlk `--offline` denemesi eklenti önbelleği olmadığı için düştü; çevrimiçi derleme geçti. `local.properties` gitignore’da.
- **Sonraki tek somut adım:** kullanıcı yeni bir özellik istemezse `HANDOVER.md` bölüm 6’daki çoklu cihaz senkronizasyonu veya sesli not oynatıcıdan hangisini istediğini netleştirip ona geç.
