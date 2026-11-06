# DEVAM

## 2026-11-06 — v1.4 ve v1.5-beta.1 yayınlandı

- **Kaynak:** `c28461c` hem `feature/v14-updater-ux` hem `main` (fast-forward). `v1.4` etiketi bu commit'te.
- **v1.4** (kararlı, latest): `sehirnotlari-1.4.apk`, versionCode 5, SHA-256 `dfeab709…db5e8` (GitHub digest aynı). İmza sertifikası v1.2/v1.3 ile aynı (`edd159fc…6efac`).
- **v1.5-beta.1** (pre-release): `release/v1.5-beta.1` dalında yalnız sürüm artışı (`136774b`), versionCode 6, SHA-256 `fd28aaf6…204e4`.
- APK'lar `git archive` ile temiz kopyadan derlendi; Gemini anahtarı yok, test sunucusu boş.
- Doğrulama: v1.3 → v1.4 (eski güncelleyici) ve v1.4 → v1.5-beta.1 (yeni güncelleyici, gerçek GitHub) emülatörde kuruldu; ayar kapalıyken beta önerilmedi; notlar, Room ve veri güvenliği günlüğü aynı kaldı.
- **Sonraki tek somut adım:** v1.4'ü gerçek telefonda uygulama içinden almak; sorun yoksa beta kullanıcılarına sonraki kararlı sürüm.

## 2026-11-06 — v1.4 GitHub otomatik güncelleme (PackageInstaller)

- **Dal:** `feature/v14-updater-ux` (commit/push/tag/Release yok; `main` dokunulmadı)
- **Durum:** kod ve testler tamamlandı; emülatörde uçtan uca doğrulandı. Gerçek telefon denemesi ve yayın kullanıcıda.
- **Ne yapıldı:**
  - Kaynak `releases?per_page=10` listesine geçti. Taslaklar hiç, ön sürümler yalnız "Test sürümlerini de al" açıkken sunuluyor.
  - Sürüm notunda tek satır `versionCode: N` (veya etiket sonu `+N`) sürüm kodunu bildiriyor; satır notlarda gösterilmiyor. Seçim versionCode ile yapılıyor; APK'nın gerçek versionCode'u bildirilenle aynı olmalı. Bildirim yoksa eski etiket karşılaştırması.
  - Kurulum `ACTION_VIEW` yerine `PackageInstaller` oturumuyla (her zaman kullanıcı onayı). Dosya oturuma yazılırken yeniden hash'leniyor; farklıysa oturum iptal. Sonuç alıcısı dışa kapalı; onay ekranı yalnız etkinlik öndeyken açılıyor. İptal → yeniden indirmeden "Tekrar Dene".
  - GitHub hız sınırı (403/429, `x-ratelimit-reset`, `retry-after`) saklanıyor; süre bitene kadar istek gönderilmiyor, saat gösteriliyor (en çok 6 saat).
  - "Güncellemeleri otomatik indir" (varsayılan kapalı): yalnız açılış denetiminde ve kotasız ağda indirir; kurulumu asla kendisi başlatmaz.
  - Yeni izin: `ACCESS_NETWORK_STATE` (kotasız ağ denetimi). Debug'a özel `-PupdateTestServer=127.0.0.1:PORT` yerel GitHub taklidi; release'te her zaman boş.
  - Room-first, Firebase yedekleme, JSON kurtarma ve Firestore kuralları değişmedi.
  - Testler: birim 389/389 (21 yeni), smoke 39/39, kural emülatör testleri 25/25.
  - Emülatörde: ön sürüm seçimi, kesilen indirme (3 deneme + Tekrar Dene), Android onay ekranı, iptal ve yeniden deneme, v1.4 (5) → v1.5-beta.1 (6) kurulumu; notlar, Room dosyaları, veri güvenliği günlüğü ve ayar dosyaları bayt bayt aynı. Yanlış SHA-256 ve farklı imza engellendi, dosya silindi, kurulum açılmadı. Hız sınırı, Wi-Fi kapalı açılış, otomatik indirme ve gerçek GitHub denetimi (güncel) doğrulandı. Emülatör eski hâline döndürüldü.
  - Google Play Protect, Play dışı kurulan güncellemede "App scan recommended" gösterebiliyor ("Install without scanning" seçeneği var).
- **Sonraki tek somut adım:** Kullanıcı onayıyla commit; ardından aynı sertifikayla imzalı, notunda `versionCode: N` satırı olan bir ön sürüm Release'iyle gerçek telefonda deneme.

## 2026-11-06 — v1.4 Firebase bulut yedekleme ve telefon APK'sı

- **Dal:** `feature/v14-updater-ux` (`ac11e96` üzerinde, commit/push/Release yok; `main` dokunulmadı)
- **Durum:** kod ve testler tamamlandı (emülatörde Firebase Emulator Suite ile). Gerçek telefon testi ve Firebase Console ayarları kullanıcıda.
- **Ne yapıldı:**
  - Google ile oturum açma (Credential Manager + Firebase Auth) eklendi. Oturum açmadan uygulama ve notlar aynen çalışıyor.
  - Yedekler Firestore'da `users/{uid}/backups/{id}` altında, 700 kB'lık en çok 8 parça hâlinde (5 MiB sınırı) tek atomik batch ile yazılıyor.
  - Her yedek SHA-256 ile doğrulanıyor. Kullanıcı başına son 5 tamamlanmış yedek tutuluyor; eski yedekler yalnız yeni yedek sunucudan yeniden okunup doğrulandıktan sonra siliniyor.
  - Firebase Storage kullanılmadı (Blaze planı gerektirir).
  - Otomatik yedek: varsayılan kapalı, günde en çok bir kez ve yalnız içerik değiştiyse alınıyor.
  - Geri yükleme mevcut içe aktarma önizlemesini, ImportStaging'i ve yalnız-ekleme birleştirmesini kullanıyor. Bu telefonda silinmiş notlar ancak kutu işaretlenirse geri geliyor.
  - Room-first açıkken bulut işlemleri reddediliyor. Room şeması ve `tur_v10` biçimi değişmedi.
  - Güvenlik kuralları `firebase/firestore.rules` içinde. Canlıya yüklenmedi.
  - Testler: birim 368/368, smoke 39/39, kural emülatör testleri 25/25.
  - Emülatörde uçtan uca doğrulananlar: oturum açma, yedekleme, saklama sınırı, bağlantısız deneme, yedek sırasında öldürme, geri yükleme önizlemesi ve çakışma, onaydan hemen sonra öldürme, otomatik yedek, oturum kapatma.
  - Uçtan uca testlerden sonra emülatör verisi test öncesi anlık görüntüye döndürüldü.
  - Android 17 (targetSdk 37) yerel ağ kısıtı yüzünden emülatör testi `adb reverse` ve `-PfirebaseEmulatorHost=127.0.0.1` ile yapılıyor; 10.0.2.2 uygulamadan erişilemiyor.
  - Telefon APK'sı: Gemini anahtarı olmayan ayrı kopyadan release olarak derlendi ve v1.2/v1.3 ile aynı sertifikayla imzalandı (`versionCode 5`).
- **Sonraki tek somut adım:** Firebase Console'da Google oturum açma sağlayıcısını açmak, Firestore veritabanını oluşturmak ve onaylanırsa `firestore.rules`'u yüklemek. Ardından APK'yı gerçek telefonda denemek.

## 2026-11-06 — P8 Room ana kaynak geçişi / izole prototip

- **Dal:** `feature/room-migration-validation` (başlangıç `a4eba8e`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı (emülatörde; yalnız debug, üretimde kapalı)
- **Ne yapıldı:**
  - Şema v4'te kaldı. Room-first yazması (`RoomFirstWriter`) not başına bilet karşılaştırmasıyla çalışıyor ve tek transaction içinde yapılıyor. "Kaydedildi" yalnız COMMIT'ten sonra gönderilen yanıtla gösteriliyor.
  - Tekrar gönderilen veya yanıtı kaybolmuş istek iki kez uygulanmıyor (`ALREADY_SAVED`). Eski sürümden gelen istek `CONFLICT`, tombstone'a yazma `DELETED` alıyor; tombstone diriltilmiyor.
  - Geçiş yalnız `BuildConfig.DEBUG` derlemesinde ve açık onayla yapılıyor. Önce denetim temiz olmalı (11 fark türünden biri bile varsa ret), ardından SHA-256 doğrulamalı yedek alınıyor (silinmiyor), en son `commit()` ile bayrak yazılıyor. Dönüşte `tur_v10` Room'dan yeniden yazılıp denetleniyor.
  - Mod açıkken `localStorage`'dan türeyen bütün Room yazmaları native tarafta reddediliyor; P7 ekranı bunu bildiriyor.
  - Cihazda ölçülen ayar: `wal` / `synchronous=NORMAL`. Süreç ölümüne dayanıklı; güç kesintisinde son commit'ler geri alınabilir.
  - Emülatörde (API 37): onaylanan notlar `force-stop`, yazma sırasında öldürme ve döndürmeden sonra aynen geri okundu. Kayıp yanıt ve aynı istek kimliği `ALREADY_SAVED` döndü. 400 düzenleme 4 transaction'a indi.
  - Ayrıca cihazda doğrulananlar: kesilen ve tekrarlanan dönüş, v1 → v4 zinciri, P7 uyumu. Logcat'te not verisi yok. Ekran ayarları geri alındı.
  - `assembleDebug` + `testDebugUnitTest` 171/171 geçti (33 yeni). Rapor güncellendi.
- **Sonraki tek somut adım:** Üretim kararı: `synchronous=FULL` mı, yoksa güç kesintisi riski kabul mü? Onay bekleyen düzenlemeler için native kalıcı kuyruk gerekip gerekmediğine de karar verilmeli. Ardından fiziksel cihazda aynı senaryolar denenmeli.

## 2026-11-06 — P7 güvenli veri uzlaştırma ekranı

- **Dal:** `feature/room-migration-validation` (başlangıç `c2819fa`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı (emülatörde)
- **Ne yapıldı:**
  - Çekmecedeki "Veri ve Yedek" sekmesine "🛡️ Veri Güvenliği" bölümü eklendi; uygulamada ayrı bir Ayarlar ekranı yok. Bölüm Room ile `localStorage` tutarlılığını, otomatik onarılan (son 30 gün), inceleme bekleyen ve kökeni belirsiz kayıt sayılarını ve Türkçe fark açıklamalarını gösteriyor.
  - Her fark için önizleme penceresi var: iki sürüm yan yana, korunacak kaynak, işlemin sonucu ve gerektiğinde onay kutusu.
  - İşlemler yalnız Room'u ve tek notu değiştiriyor. Jetonla, kilit altında ve tek transaction'da çalışıyor. Önce yedek alınıyor (şema v4, `note_resolution_backup`), işlemler geri alınabiliyor.
  - Tombstone diriltilmiyor. Room'un daha yeni olduğu veya kökeni belirsiz kayıtlar onaysız değişmiyor. Toplu işlem varsayılan olarak kapalı.
  - Otomatik onarım ve kayıp sayıları artık yeniden açılışta günlükten okunuyor. P6'daki budama hatası (`createdAt` yerine `resolvedAt`) düzeltildi.
  - Emülatörde v3 → v4 yükseltmesi, bütün işlemler, döndürme, `force-stop`, küçük ekran ve erişilebilirlik ağacı doğrulandı. `tur_v10` değişmedi; loglarda not verisi yok.
  - `assembleDebug` + `testDebugUnitTest` 138/138 geçti. Rapor güncellendi.
- **Sonraki tek somut adım:** Room'u ana kaynak yapma planı (`localStorage` kaybı ve "Room daha yeni" farklarının ekrandan telefona taşınabilmesi için); tombstone çakışmaları için kullanıcı kararı tasarımı.

## 2026-11-06 — P6 veri tutarlılığı ve güvenli toparlama

- **Dal:** `feature/room-migration-validation` (başlangıç `316ef27`, `main` dokunulmadı, push/merge yok)
- **Durum:** tamamlandı (emülatörde; onay arayüzü yok)
- **Ne yapıldı:**
  - Room v3'e içerik saklamayan kalıcı yazma günlüğü (`note_write_journal`) eklendi. Migration yalnız bu tabloyu ekliyor. Her köprü yazması kuyruğa girmeden önce bilet ve SHA-256 parmak iziyle `PENDING` olarak kaydediliyor.
  - Açılışta `localStorage` salt okunur olarak Room ile alan alan karşılaştırılıyor (başlık, içerik, kategori, yıldız, renk, favori, tarih, isAI). Karşılaştırma ve onarım tek bir transaction'da, sıralayıcı kilidi altında yapılıyor.
  - Onarım yalnız günlük kanıtıyla yapılıyor: Room'dan yeni, parmak izi `localStorage` ile aynı, bekleyen bir yazma olmalı. Değiştirilen satır saklanıyor. Tombstone diriltilmiyor, satır silinmiyor, Room'un daha yeni olduğu veya kökeni belirsiz satırlar yalnız raporlanıyor.
  - Room ve Gemini logları içeriksiz ve anahtarsız hâle getirildi.
  - Emülatörde gerçek ani kapanma (400 düzenleme + `force-stop`) günlük kanıtıyla onarıldı. WebView'ın son `localStorage` yazmalarını kaybettiği görüldü; Room'un daha yeni kaldığı durum ezilmeden raporlandı. Altı kontrollü fark doğru sınıflandı ve tekrar açılışta değişiklik olmadı.
  - `assembleDebug` + `testDebugUnitTest` 102/102 geçti. Rapor güncellendi.
- **Sonraki tek somut adım:** Raporlanan farklar için kullanıcı onaylı uzlaştırma ekranı (önizleme modu hazır); ardından `localStorage` kaybı nedeniyle Room'u ana kaynak yapma planı.

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
