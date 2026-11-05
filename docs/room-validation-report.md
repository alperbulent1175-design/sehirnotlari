# Room aktarımı doğrulama raporu

Tarih: 6 Kasım 2026  
Dal: `feature/room-migration-validation`  
Depo: `https://github.com/alperbulent1175-design/sehirnotlari`  
Kapsam: opt-in `saveNotesToRoom` aktarımı. Otomatik geçiş **açılmadı**. `localStorage` / `tur_v10` ana kaynak olarak kaldı. Yıkıcı Room migration eklenmedi. `main` birleştirilmedi.

## Derleme

| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug` (KSP 2.2.10-2.0.2) | **Başarısız.** AGP 9 built-in Kotlin, KSP’nin `kotlin.sourceSets` kaydını reddetti. |
| KSP `2.3.13` sonrası `.\gradlew.bat :app:assembleDebug` | **BUILD SUCCESSFUL** (3m 45s). Uyarı: `MediaRecorder` kurucusu deprecated (`MainActivity.kt`); bu görevle ilgili değil. |
| `.\gradlew.bat :app:testDebugUnitTest` | **BUILD SUCCESSFUL** (25s). 21 test, 0 başarısız, 0 atlanan. |

KSP yükseltmesi Android AGP 9 göç rehberine uyuyor (`com.google.devtools.ksp` ≥ 2.3.6). `android.disallowKotlinSourceSets=false` eklenmedi.

## Birim testleri

Hepsi geçti:

| Sınıf | Test | Senaryo |
| :--- | :--- | :--- |
| `LegacyRoomImporterTest` | `firstImportInsertsLegacyNote` | Eski not ilk kez eklenir |
| `LegacyRoomImporterTest` | `reimportDoesNotDuplicate` | Aynı not tekrar aktarılmaz / çoğalmaz |
| `LegacyRoomImporterTest` | `deletedNoteIsNotRecreated` | Tombstone’lu ID yeniden oluşturulmaz |
| `LegacyRoomImporterTest` | `invalidPayloadRollsBackEarlierInserts` | Hatalı kayıtta önceki yazımlar geri alınır |
| `LegacyRoomImporterTest` | `existingRoomNoteIsNotOverwritten` | Room’daki mevcut notun üzerine yazılmaz |
| `LegacyRoomImporterTest` | `oversizedPayloadIsRejectedBeforeImport` | Aşırı büyük yük import’a girmez |
| `RoomBridgeScriptsTest` | 3 test | `onRoomImportResult` / yazma / silme / okuma hata script’leri |
| `RoomMigrationGuardTest` | 2 test | WebView `saveNotesToRoom` çağırmaz; destructive migration yok |
| `NoteEntityContractTest` | 2 test | Önceki sözleşme |
| `ReleaseVersionsTest` | 7 test | Güncelleyici |
| `ExampleUnitTest` | 1 test | Şablon |

Aktarım JVM’de `InMemoryNoteImportStore` ile ölçüldü. Room `withTransaction` davranışı aynı “fırlat → geri al” sözleşmesini kullanır; gerçek SQLite transaction’ı bu raporda cihaz üzerinde doğrulanmadı.

## Emülatör / cihaz

Çalıştırılmadı.

- `adb devices`: bağlı cihaz yok
- `emulator -list-avds`: kayıtlı AVD yok

Bu yüzden gerçek `localStorage` notlarının uygulama açılışında korunduğu cihazda görülmedi. Kod yolu açılışta yalnız salt okunur `requestRoomNotes` karşılaştırması yapar; `DB` / `sv()` sıfırlanmaz.

## Korunan güvenlik kuralları

- `main` dalına geçilmedi, birleştirme yapılmadı.
- `index.html` hâlâ `Android.saveNotesToRoom(` çağırmaz.
- `localStorage` silinmedi / sıfırlanmadı.
- `CityNotesDatabase` `fallbackToDestructiveMigration` kullanmıyor; sürüm 1.
- Yayın / Release yapılmadı.

## Kalan riskler

1. **Cihaz duman testi yok.** Mevcut notların fiziksel emülatör/telefonda durduğu henüz kanıtlanmadı.
2. **`isAI` alanı Room’da yok.** Gidiş-dönüşte AI işareti kaybolabilir.
3. **Gölge yazma (`upsertRoomNote`) mevcut satırı değiştirir.** Yalnız toplu `saveNotesToRoom` üzerine yazmaz. JSON içe aktarma hâlâ `mirrorNewNoteToRoom` kullanır.
4. **Kimlik çakışması.** UI `Date.now()` sayısal ID üretir; aynı milisaniyede aynı şehirde iki not riski duruyor.
5. **Asenkron sıra.** JS önce legacy `DB` yazar, Room sonucu sonra gelir; hızlı sil/düzenle sırası cihaz testinde doğrulanmadı.
6. **Room hâlâ ana kaynak değil.** Geçiş kapısı bilinçli olarak kapalı.

**Geçiş kararı:** Room’u tek doğruluk kaynağı yapmayın. Opt-in aktarım birim testlerinden geçti; cihaz kabulü bekleniyor.

## P1 — Yazma sırası ve veri bütünlüğü (6 Kasım 2026)

Başlangıç: `61005018a25c3ed0f318c36f67451d8dac6f6985`. Push/merge yok. Otomatik aktarım kapalı. Not kimlikleri `city::id` olarak kaldı. Room şeması hâlâ sürüm 1 (yeni kolon yok).

### Tespit
`upsertRoomNote` JSON’u mutex dışında ayrıştırıp `OnConflictStrategy.REPLACE` ile yazıyordu. Gecikmiş eski bir coroutine kilidi daha sonra alırsa daha yeni içeriği eziyordu. Tombstone kontrolü kilit içindeydi; silmeden sonra gelen yazma reddediliyordu, ama daha yeni bir düzenlemeden sonra gelen eski silme kaydı satırı yok edebilirdi.

### Uygulama
`RoomNoteWriteSequencer`: JavascriptInterface iş parçacığında monotonic bilet, ardından kilit altında `ticket < lastApplied` ise yok say; `deletedAt != null` ise upsert reddi. Eski kayıtlar ek alan olmadan uyumlu.

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` | **BUILD SUCCESSFUL** (29s). 27 test, 0 başarısız, 0 atlanan. |

Yeni `RoomNoteWriteSequencerTest` (6): gecikmiş upsert ezmez; tombstone dirilmez; gecikmiş silme yeni düzenlemeyi silmez; kaydet/düzenle/sil 6 tamamlanma sırası tombstone ile biter; eşzamanlı tamamlamalar bilet sırasını korur; WebView başarı/hata eşlemesi.

### Kalan riskler
Cihaz duman testi hâlâ yok. `isAI` yok. JSON içe aktarma hâlâ `mirrorNewNoteToRoom` (gölge upsert). `Date.now()` ID çakışması duruyor. Bilet haritası süreç belleğinde; süreç ölümü yarışı zaten bitirir. Room ana kaynak değil.

## P2 — Yaşam döngüsü boyunca sıralama (6 Kasım 2026)

Başlangıç: `9e218118f17f58b41cf9fd1560e4f2ab3872432b`. Push/merge yok. `main` değişmedi. Otomatik aktarım kapalı. Not silinmedi. Room şeması sürüm 1; yeni kolon yok. Kimlikler `city::id`.

### P1 mekanizmasında tespit edilen riskler
1. **Activity yeniden oluşturma (döndürme, tema, sistem):** sıralayıcı ve `Mutex` Activity alanıydı. Yeni Activity biletleri 1’den başlatıyor, `lastAppliedTicket` haritası boş kalıyordu. Eski Activity’nin bekleyen yazmaları ve yenisinin yazmaları **iki ayrı kilit** altında aynı veritabanına gidebiliyordu; sonra biten kazanıyordu.
2. **`lifecycleScope` iptali:** Activity yok olunca bekleyen Room yazmaları iptal edilebiliyordu. `localStorage` güncellenmişken Room’da sessizce eksik kalıyordu.
3. **Süreç yeniden başlatma:** bellek içi harita sıfırlanıyordu. Kalıcı satırlardaki sıra bilgisi kullanılmıyordu; tekrar oynatılan eski bir istek yeni bilet alıp daha yeni içeriği ezebilirdi. Yalnız tombstone kontrolü kalıcıydı.
4. **Tekrarlanan istek:** aynı `requestId` her seferinde yeni bilet alıyordu; araya giren daha yeni düzenlemenin üstüne eski içerik yazılabiliyordu.
5. **Saatin geri alınması:** duvar saati geriye giderse yeni yazmalar eski kayıtlardan küçük zaman damgası taşıyabilirdi.

### Uygulanan çözüm
- **Kalıcı monoton bilet:** `max(son + 1, duvar saati)`. İlk biletten önce bir kez `SELECT MAX(updatedAt)` ile tohumlanır. Uygulanan yazma biletini mevcut `updatedAt` sütununa yazar. Karşılaştırma bellek haritası yerine kalıcı satırla yapılır: `ticket <= updatedAt` ise yok sayılır. Şema değişmedi; eski satırların `updatedAt` değerleri zaten milisaniye zaman damgasıydı, uyumlu.
- **Süreç genelinde tek sıralayıcı ve kilit:** `RoomNoteWriteSequencer.shared(dao)`; aktarım da aynı kilidi kullanır.
- **Süreç kapsamlı yazma alanı:** `RoomNoteWriteSequencer.writeScope` (`SupervisorJob + IO`). Activity yeniden oluşturulsa da bekleyen yazma tamamlanır; sonuç yalnız yaşayan Activity’nin WebView’ına iletilir.
- **İstek kimliğiyle tekrar koruması:** son 512 `requestId` sonucu saklanır; tekrar gelen istek yeniden uygulanmaz, aynı sonucu döner.
- **Tombstone:** silinmiş satır hiçbir upsert ile dirilmez; tekrar silme orijinal `deletedAt` değerini korur.
- **Aktarım damgası:** `LegacyRoomImporter` artık sıralayıcının biletini `createdAt` / `updatedAt` olarak kullanır.

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` | **BUILD SUCCESSFUL** (23s). 36 test, 0 başarısız, 0 atlanan. |

Yeni `RoomNoteWriteRestartTest` (8):
- Saat geri alınmış yeniden başlatmada sıra kalıcı satırlardan sonra devam eder; gecikmiş eski bilet yok sayılır.
- Tombstone yeniden başlatmadan sonra korunur; tekrar silme `deletedAt` değerini değiştirmez.
- Kaydet → düzenle → yeniden başlat → sil → yeniden başlat → eski kaydı tekrar oynat: not silinmiş kalır.
- Tekrarlanan `requestId` daha yeni düzenlemeyi ezmez.
- Tekrarlanan silme orijinal tombstone zamanını korur.
- Saat durduğunda veya geri gittiğinde biletler kesin artar.
- Gelecek tarihli `updatedAt` taşıyan eski satır yeni düzenlemeyi kabul eder; `createdAt` korunur.
- İçe aktarılan satırlar sıralayıcı biletleriyle sıralanır.

`RoomMigrationGuardTest` +1: `MainActivity` paylaşılan sıralayıcıyı ve `writeScope` kullanır; Activity’ye bağlı kilit kalmamıştır.

### Kalan riskler
1. **Cihaz duman testi yok.** Döndürme ve süreç öldürme gerçek cihazda denenmedi; testler JVM’de bellek içi depoyla çalıştı.
2. **Süreç sınırını aşan tekrar.** `requestId` önbelleği bellekte; yeniden başlatmadan sonra aynı istek tekrar gelirse yeni bilet alır. Bugünkü JS bekleyen istekleri yeniden göndermez, ama bir ileride yeniden deneme mantığı eklenirse istemci tarafı sürüm gerekir.
3. **`updatedAt` artık sıralama bileti.** Duvar saatine çok yakın, ama saat geri alındıysa gerçek zamandan ileride olabilir. Gelecekteki bulut eşitlemesi bunu “son değişiklik zamanı” diye yorumlamamalı.
4. **Gecikmiş sonuç geri bildirimi.** Activity yeniden oluşursa eski isteğin sonucu yeni sayfaya iletilmez; yeni sayfa o isteği beklemediği için kullanıcıya hata gösterilmez, yalnız Logcat’e düşer.
5. Önceki riskler sürüyor: `isAI` alanı yok, JSON içe aktarma gölge upsert kullanıyor, `Date.now()` kimlik çakışması, Room ana kaynak değil.
