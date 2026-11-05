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
