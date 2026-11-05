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

## P3 — Not kimliği güvenliği (6 Kasım 2026)

Başlangıç: `933ff7aee2fb6db3037cce75e916946e0a540155`. Push/merge yok. `main` değişmedi. Otomatik aktarım kapalı. Not veya `localStorage` silinmedi. Room şeması sürüm 1; değişmedi. Anahtar biçimi `city::id` aynen kaldı.

### Tespitler
1. **Aynı milisaniyede çakışma:** yeni not kimliği iki yerde `Date.now()` ile üretiliyordu (elle not ekleme `ba` ve yapay zekâ notu `confirmAI`). Aynı milisaniyede iki not aynı kimliği alıp Room’da aynı `city::id` satırına düşebiliyordu; düzenleme/silme yanlış notu etkileyebiliyordu.
2. **Karışık kimlik türleri:** `onclick` şablonları kimliği tırnaksız gömüyordu (`delNote('Bursa',${n.id})`). Metin kimlikli içe aktarılmış notlarda bu sözdizimi hatası ya da başka bir değişkene başvuru demekti. Aramalar `===` kullandığından `"123"` ile `123` eşleşmiyordu.
3. **İçe aktarma / bulut geri yükleme:** tekrar kontrolü `Set.has` ile türe duyarlıydı (`"123"` ≠ `123`), aynı dosyadaki tekrarları ayıklamıyordu; kimliksiz veya nesne olmayan bir kayıt tüm içe aktarmayı düşürebiliyor ya da Room tarafında hata veriyordu.
4. **Kotlin köprüsü:** `upsertRoomNote` `optString("id")` kullandığından `null` kimlik `"null"` anahtarına dönüşüyordu. `requestRoomNotes` her sayısal metni `Long` olarak gönderiyordu; 2^53 üzerindeki değerler JS’te hassasiyet kaybederdi.

### Uygulanan çözüm
- **`app/src/main/assets/note-ids.js`** (yeni, ES5, `index.html` içinde Leaflet’ten sonra yüklenir):
  - `nextNoteId(DB)`: `Date.now() * 1000 + rastgele(0..999)`; oturum içinde kesin artan (`lastIssued`) ve tüm şehirlerdeki mevcut kimliklerle karşılaştırılarak boş olan ilk değere kayar. Sonuç her zaman `Number.MAX_SAFE_INTEGER` altında tam sayıdır (2026 için ~1,76·10^15). Eski `Date.now()` kimliklerinden büyük olduğu için sayısal sıralama korunur.
  - `sameId(a, b)`: `String(a) === String(b)`; `null`/`undefined` asla eşleşmez. Room anahtarı da `String(note.id)` olduğundan JS ve Room aynı kimliği görür.
  - `idArg(id)`: sayı aynen, metin `JSON.stringify` + HTML öznitelik kaçışıyla `onclick` içine güvenle gömülür.
  - `mergeNotes(DB, gelen, onAdded)`: JSON içe aktarma ve bulut geri yükleme ortak yolu. Var olan notlara dokunmaz; şehir içinde metin olarak aynı kimliği (dosya içi tekrarlar dahil) atlar; nesne olmayan kayıtları atlar; yalnız eksik/boş kimliğe yeni kimlik verir. Kullanılabilir eski kimlikler (negatif, kesirli, uzun metin dahil) **yeniden numaralandırılmaz**.
- **`index.html`:** iki `Date.now()` kimlik üretimi `NoteIds.nextNoteId(DB)` oldu. `delNote`, `toggleFav`, `openEdit`, `saveEdit`, `editStars`, `shareNote` aramaları `NoteIds.sameId` kullanır. Not ve favori listelerindeki tüm `onclick` kimlikleri `NoteIds.idArg`. Favori sıralaması `NoteIds.sortValue`. `importJSON` ve `importFromCloud` `NoteIds.mergeNotes` kullanır. Mevcut `localStorage` verisi okunurken hiçbir kimlik değiştirilmez.
- **`RoomNoteKeys.kt`** (yeni): `key(city, id)`, JS `String(id)` ile aynı metni veren `idFromJson` (boş, `null`, NaN, nesne reddedilir) ve yalnız ±(2^53−1) aralığındaki kanonik tam sayıları sayı olarak, diğerlerini metin olarak döndüren `webViewId`. `LegacyRoomImporter`, `RoomNoteWriteSequencer`, `MainActivity.upsertRoomNote` ve `requestRoomNotes` bunu kullanır.

### Akış kontrolü
| Akış | Sonuç |
| :--- | :--- |
| Kaydet (elle / yapay zekâ) | Çakışmasız sayısal kimlik; Room anahtarı `city::<kimlik>` |
| Düzenle / yıldız / favori / paylaş | Eski sayı, yeni sayı ve metin kimlikler `sameId` ile bulunur |
| Sil | `String(id)` Room’a gider; tombstone kuralları P1/P2’deki gibi |
| JSON içe aktarma | Mevcut not korunur; `"123"`/`123` tekrar sayılır; kimliksize yeni kimlik |
| Bulut geri yükleme | İçe aktarmayla aynı `mergeNotes` yolu |

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` | **BUILD SUCCESSFUL** (3m 12s). 54 test, 0 başarısız, 0 atlanan. |

Test bağımlılığı olarak `org.mozilla:rhino:1.7.15` eklendi (yalnız `testImplementation`; APK’ya girmez). `index.html` içindeki ana betik ve `note-ids.js` ayrıca `node --check` ile sözdizimi denetiminden geçti.

Yeni `NoteIdsScriptTest` (10, gerçek `note-ids.js` Rhino’da çalışır):
- Aynı milisaniyede 2000 not benzersiz, güvenli tam sayı kimlik alır.
- Yeni kimlik başka şehirlerdeki mevcut kimlikleri atlar.
- Yeni kimlikler eski `Date.now()` kimliklerinden büyüktür.
- `sameId` sayı/metin karışımını doğru eşler; `null` / `undefined` eşleşmez.
- `idArg` tırnak, `<`, `&` içeren metni kaçışlar ve öznitelikten geri çözülünce aynı değeri verir.
- İçe aktarma mevcut notu ezmez, `"123"`/`123` ve dosya içi tekrarları atlar.
- Kimliksiz/boş kimlikli kayıtlara benzersiz kimlik verilir; bozuk kayıtlar atlanır.
- Kullanılabilir eski kimlikler (negatif, kesirli, uzun metin, boşluklu metin) değişmez.
- Karışık kimliklerle kaydet → düzenle → sil akışı.
- Yeni kimliğin `String()` biçimi Room anahtarı ve `webViewId` ile birebir eşleşir.

Yeni `RoomNoteKeysTest` (7): eski sayısal anahtar, yeni mikro-ölçekli kimliğin sayı olarak dönüşü, metin/öndeki sıfırlı/2^53 üstü kimliklerin metin kalması, geçersiz kimliklerin reddi, tam sayı `Double` biçimi, aynı milisaniyedeki iki notun ayrı Room satırı olması, sıralayıcının eski ve yeni kimlikler için aynı anahtarı kullanması.

`RoomMigrationGuardTest` +1: `index.html` `note-ids.js` yükler, `id: Date.now()` içermez, tırnaksız `${n.id}` gömmez, `.id ===` / `.id !==` karşılaştırması kalmamıştır, iki içe aktarma yolu `mergeNotes` kullanır.

### Kalan riskler
1. **Cihaz duman testi yok.** WebView’da tıklama/düzenleme/silme gerçek cihazda denenmedi; JS mantığı Rhino’da, sözdizimi Node’da doğrulandı.
2. **Cihazlar arası çakışma** artık çok düşük olasılıklı ama sıfır değil: iki cihaz aynı milisaniyede aynı rastgele son ekle (1/1000) not üretirse ve sonra notlar birleştirilirse ikincisi tekrar sayılıp atlanır. Çoklu cihaz eşitlemesinde UUID veya cihaz kimliği eklemek gerekir.
3. **Mevcut çakışmış kimlikler** (P3 öncesi aynı milisaniyede oluşmuş iki not) otomatik ayrıştırılmaz; düzenleme ilk eşleşeni etkiler, silme ikisini birden kaldırır (P3 öncesiyle aynı davranış). Toplu yeniden numaralandırma kurala aykırı olduğu için yapılmadı.
4. **Kayan nokta kimlikler:** `1e21` gibi üstel biçimli içe aktarılmış sayılarda JS (`"1e+21"`) ve Kotlin (`"1.0E21"`) metinleri farklıdır; gerçek veride beklenmez.
5. Önceki riskler sürüyor: `isAI` Room’da yok, Activity yeniden oluşursa köprü sonucu kaybolur, `updatedAt` sıralama biletidir, Room ana kaynak değil.
