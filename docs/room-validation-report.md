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

## P4 — Room şema geçişi v1 → v2 (6 Kasım 2026)

Başlangıç: `c8d65b7db6cbaaa4118c75592e1d3de7a720728f`. Push/merge yok. `main` değişmedi. Otomatik aktarım kapalı. `index.html` ve `localStorage` yolu değişmedi. Room hâlâ gölge kopya; ana kaynak değil. Yıkıcı migration yok; not kimlikleri (`city::id`) aynen korunur.

### v1 incelemesi
- Tek tablo `notes`; `exportSchema = false` idi, şema dosyası yoktu. v1 şeması, değişiklikten önce aynı entity ile `app/schemas/com.sehirnotlari.data.CityNotesDatabase/1.json` olarak dışa aktarıldı. Kimlik özeti `d453335eff3e94eadbf36e25e651ec4b`; cihazlardaki v1 veritabanlarında `room_master_table` içinde bu değer durur.
- Okuma yolları: `findAny`, `allActive` (WebView anlık görüntüsü), `maxUpdatedAt` (bilet tohumu), `observeActive`, `observeCity`, `search`, `pendingSync`.
- Yazma yolları: sıralayıcı `upsert` / `delete` (`upsertRoomNote`, `deleteRoomNote`), `softDelete`, opt-in eski not aktarımı (`saveNotesToRoom` → `LegacyRoomImporter`).
- Sorun: P2'den beri `updatedAt` gerçek zaman değil, sıralama biletiydi. Tombstone'larda `deletedAt` de bilet değeri taşıyordu. `isAI` hiç saklanmıyordu.

### v2 şeması
| Sütun | v1 | v2 | Anlam |
| :--- | :--- | :--- | :--- |
| `updatedAt` | `INTEGER NOT NULL` | **kaldırıldı** | — |
| `writeTicket` | — | `INTEGER NOT NULL` | Yalnız yazma sırası (hibrit saat). Zaman değildir. |
| `modifiedAt` | — | `INTEGER` (boş olabilir) | Son uygulanan değişikliğin duvar saati. `NULL` = bilinmiyor. |
| `isAI` | — | `INTEGER` (boş olabilir) | `1`/`0` biliniyor; `NULL` = v1'den geldi, köken kaydedilmemiş. |
| `createdAt` | aynı | aynı | Satırın Room'a ilk yazılma zamanı. |
| `deletedAt` | aynı | aynı | Tombstone işareti; v2'de yeni silmeler duvar saati yazar. |
| İndeks | `index_notes_updatedAt` | `index_notes_writeTicket` | `index_notes_city` aynı. |

Veritabanı sürümü 2, `exportSchema = true`, şemalar `app/schemas` altında (KSP `room.schemaLocation`). Kimlik özeti v2: `b64828285a91f1f93115230ff973b648`.

### Açık migration (`NoteSchemaMigrations.MIGRATION_1_2`)
`ALTER TABLE … RENAME COLUMN` SQLite 3.25 (API 30) ister; `minSdk` 24 olduğu için tablo yeniden kuruldu. Room migration'ı tek işlem (transaction) içinde çalıştırır:
1. `notes_v2` tablosu Room'un v2 için ürettiği `CREATE TABLE` ile birebir aynı oluşturulur.
2. Tüm satırlar `INSERT … SELECT` ile kopyalanır: `updatedAt` → `writeTicket` aynen; `modifiedAt` ve `isAI` → `NULL`; diğer bütün sütunlar (kimlik, şehir, içerik, `createdAt`, `deletedAt`, `syncState`) aynen.
3. Eski tablo silinir, `notes_v2` → `notes` olarak adlandırılır, iki indeks yeniden kurulur.

Gerçek değişiklik zamanı ve yapay zekâ kökeni v1'de kaydedilmediği için **tahminle doldurulmadı**; `NULL` kalır. `updatedAt` değerinden `modifiedAt` türetilmedi, çünkü saat geri alınmışsa bilet gerçek zamandan ileride olabilir.

`CityNotesDatabase.builder()` migration'ı ekler; üretim `get()` ve testler aynı kurucuyu kullanır. `fallbackToDestructiveMigration` yok.

### Yeni şemaya uyarlanan yollar
- **Sıralayıcı:** karşılaştırma `writeTicket` ile. Uygulanan yazma `writeTicket = bilet`, `modifiedAt = duvar saati`, `isAI = taslak değeri` yazar; `createdAt` korunur. Silme `writeTicket = bilet`, `deletedAt = modifiedAt = duvar saati`; var olan tombstone'a dokunulmaz. Tohum `maxWriteTicket()`.
- **Eski not aktarımı:** `writeTicket = bilet`, `createdAt = şimdi`, `modifiedAt = NULL` (localStorage'daki son değişiklik zamanı bilinmiyor), `isAI` localStorage'daki değerden. Var olan satır ve tombstone atlanır.
- **Köprü:** `upsertRoomNote` `isAI` taşır. `requestRoomNotes` `isAI` biliniyorsa gönderir; bilinmiyorsa alanı hiç koymaz. Bilet ataması artık açılış veya migration hatasında JavaBridge iş parçacığını düşürmez; hata WebView'a bildirilir.
- **DAO sıralamaları:** `ORDER BY writeTicket DESC`.

### Migration sonuçları (Robolectric, gerçek SQLite dosyası, üretim Room kurucusu)
Eski veritabanı `1.json` içindeki SQL ve v1 kimlik özetiyle, gerçek bir v1 dosyası olarak kuruldu. Sonra `CityNotesDatabase.builder()` ile açıldı. İçerik: eski `Date.now()` kimliği, başka şehirde aynı sayı, metin kimlik, P3 kimliği, tombstone, tırnak ve Türkçe karakterli içerik.

| Kontrol | Sonuç |
| :--- | :--- |
| Satır sayısı ve her alan (kimlik, içerik, `createdAt`, `deletedAt`, `syncState`) | 5/5 birebir korundu |
| `writeTicket` | eski `updatedAt` ile aynı |
| `modifiedAt`, `isAI` | hepsi `NULL` |
| Tombstone | korundu; diriltme `Rejected`; `deletedAt` değişmedi |
| `PRAGMA user_version` | 2 |
| `room_master_table` | v2 kimlik özeti |
| Sütun düzeni | `2.json` ile aynı; `updatedAt` yok |
| İndeksler | `index_notes_city`, `index_notes_writeTicket`; eski indeks yok; `notes_v2` artığı yok |
| Boş v1 veritabanı | sorunsuz geçti |
| Sıfırdan kurulum | doğrudan v2 |
| Yarıda kalan migration | işlem geri alındı; dosya v1'de, 5 satır ve tombstone aynen; sonra doğru migration başarılı |
| Gelecek tarihli v1 bileti | yeni biletler ondan büyük başlar; gecikmiş eski yazma `IgnoredStale` |

Ek doğrulama: migration'dan yazma bileti indeksi geçici olarak çıkarıldığında Room şema doğrulaması açılışı reddetti ve 6 migration testi düştü. Değişiklik geri alındı.

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL** (2m 16s). 66 test, 0 başarısız, 0 atlanan. |

Yeni test bağımlılıkları (yalnız `testImplementation`, APK'ya girmez): `org.robolectric:robolectric:4.17`, `androidx.test:core:1.7.0`. Robolectric testleri `@Config(sdk = [35])` ile çalışır; `compileSdk` 37'yi henüz desteklemiyor.

Yeni testler (12):
- `NoteSchemaMigrationTest` (7, Robolectric): kayıpsız taşıma; v2 düzeni ve kimlik özeti; başarısız migration'ın geri alınması; boş v1; sıfırdan v2; migration sonrası sıra, tombstone, `modifiedAt` ve `isAI` yazımı; migration sonrası eski not aktarımı.
- `NoteSchemaContractTest` (4): v1 şema özeti; migration `CREATE TABLE` ve indekslerinin `2.json` ile birebir aynılığı; v2'de boş olabilen sütunların yalnız `isAI`, `modifiedAt`, `deletedAt` olması; her v1 sütununun kopyalanması ve `updatedAt` → `writeTicket` eşlemesi.
- `RoomMigrationGuardTest` +1: her sürümün şema dosyası ve açık migration'ı var; veritabanı sürüm 2, migration kayıtlı, yıkıcı geri dönüş yok.

Mevcut testler yeni alan adlarına uyarlandı (`writeTicket`, `maxWriteTicket`, `softDelete(id, ticket, time)`); davranış beklentileri değişmedi.

### Kalan riskler
1. **Cihaz duman testi yok.** Migration Robolectric'in SQLite'ında doğrulandı; gerçek bir cihazdaki v1 dosyasında denenmedi. İlk açılışta tablo yeniden kurulduğu için çok büyük veritabanlarında kısa bir gecikme olabilir. Not sayısı küçük olduğundan beklenmiyor.
2. **Geri dönüş yok.** v2'ye geçmiş bir cihaza v1 APK'sı kurulursa Room açılmaz; yıkıcı geri dönüş bilerek eklenmedi. Room gölge kopya olduğu için localStorage notları etkilenmez.
3. **v1'den gelen belirsizlik kalıcı.** v1 satırlarında `modifiedAt` ve `isAI` `NULL` kalır. `isAI` ancak not yeniden yazılınca, `modifiedAt` ancak yeni bir değişiklikle dolar. Gelecekteki eşitleme `NULL` değerini "bilinmiyor" olarak ele almalı.
4. **v1 tombstone'larında `deletedAt` bilet değeridir.** Duvar saatine çok yakın olsa da saat geri alınmışsa ileride olabilir. Bu tombstone'larda `modifiedAt` `NULL` kalarak belirsizliği gösterir.
5. **`createdAt`** notun WebView'da oluşturulma zamanı değil, Room'a ilk yazılma zamanıdır.
6. **Eski not aktarımında `isAI`:** localStorage'da alan yoksa `false` yazılır. Bu, arayüzün o notu gösterdiği şekille aynıdır.
7. Önceki riskler sürüyor: Activity yeniden oluşursa köprü sonucu kaybolur; cihazlar arası kimlik çakışması çok düşük olasılıkla mümkündür; Room ana kaynak değildir.

## P5 — Android cihaz doğrulaması (6 Kasım 2026)

Başlangıç: `5f588d82c85bb7ac1727df2adf17e9f8b92a9086`. Push/merge yok. `main` değişmedi. Otomatik Room aktarımı kapalı kaldı (oturum boyunca `import` kaydı 0). Room ana kaynak yapılmadı. Yıkıcı migration yok. Gerçek kullanıcı verisine dokunulmadı: bütün testler yeni oluşturulan, yalnız test notları içeren bir emülatörde yapıldı.

### Ortam
| Öğe | Durum |
| :--- | :--- |
| Ana makine | Windows 10 19045, i7-6700HQ, 15,9 GB RAM; hızlandırma: WHPX “installed and usable” (`emulator -accel-check`) |
| SDK | `platform-tools` (adb 37.0.0), `emulator` 36.6.11, platformlar 35/36/36.1/37.0 |
| Eksik | `cmdline-tools` (dolayısıyla `avdmanager`/`sdkmanager`) kurulu değil; başlangıçta hiç AVD yoktu, `adb devices` boştu |
| AVD | `SehirNotlari_P5_Test`: kurulu sistem imajından (`android-37.0/google_apis_playstore_ps16k/x86_64`, rev 6) Android Studio'nun yazdığı `config.ini` biçimiyle elle oluşturuldu; yeni indirme yapılmadı. 4 çekirdek, 3 GB RAM, 1080×2400, 420 dpi |
| Cihaz | `sdk_gphone16k_x86_64`, Android 17 (API 37), yapı `CE2A.260420.019`, 16 KB sayfa; WebView/Chrome 145.0.7632.218 |

### Test ortamı için yapılan ayarlar (kaynak koda girmeyen)
- **Sürüm numarası:** İlk kurulumda uygulama içi güncelleyici gerçek `v1.2` sürümünü indirmeye başladı. Kurulum ve izin ekranları döndürme testlerini bozacağı için test uygulaması, henüz veri yokken kaldırıldı. Test APK'ları geçici kopyalarda `versionName` 98.0 (v1) ve 99.0 (v2) ile derlendi. Güncelleyici “Up to date” diyerek durdu. AGP 9 `android.injected.version.*` parametresini dikkate almadı.
- **Ekran klavyesi:** Gboard düzeni kaydırıp dokunuşları bozdu; emülatörde devre dışı bırakıldı. Yeniden kurulumdan sonra sistem Gboard'u tekrar açtığı için v2 aşamasında metin alanları DevTools ile dolduruldu. **Not Ekle, yıldız, Düzenle/Kaydet, Favori ve Sil işlemleri gerçek `adb input tap` dokunuşlarıyla yapıldı.**
- **Döndürme:** `wm user-rotation lock 1/0`.
- Cihazda çalışan v2 APK'sının `app/src/main` ağacı bu commit'le birebir aynı (dosya özetleriyle karşılaştırıldı); tek fark geçici kopyadaki sürüm numarası.

### Doğrulama için eklenen kod (bu commit)
- `MainActivity`: Room köprüsü sonuçları `SehirNotlariRoom` etiketiyle Logcat'e yazılır: `upsert/delete request=… ticket=… ack=…`, `read ok active=N`, `import ok …`, hatalar. Not içeriği loglanmaz. WebView uzaktan hata ayıklaması yalnız `FLAG_DEBUGGABLE` derlemelerde açılır.
- `index.html`: `[RoomBridge] write ok / delete ok` konsol satırları; artık beklenmeyen istek için geri bildirim; karşılaştırma satırı okunur metin (`legacy=… room=… onlyLegacy=… onlyRoom=…`). Konsol satırları Logcat'te `chromium … CONSOLE` olarak görünür.
- `RoomMigrationGuardTest` +1: `setWebContentsDebuggingEnabled(true)` yalnız debuggable kontrolü içinde ve tek yerde.

### v1 → v2 yükseltmesi (cihazda)
1. `c8d65b7` (Room v1) test APK'sı kuruldu. Ankara'ya gerçek dokunuşlarla üç not eklendi; A favorilere alındı, C silindi.
2. Cihazdan çekilen v1 veritabanı: `user_version 1`, kimlik `d453335e…`, `integrity ok`, sütunlar `updatedAt` dahil v1. Satırlar: A (favori), B, C (tombstone, `deletedAt` = `updatedAt`).
3. v2 test APK'sı `adb install -r` ile **verileri silmeden** üstüne kuruldu ve açıldı.

| Kontrol | Sonuç |
| :--- | :--- |
| Logcat | `read ok active=2`; `[RoomBridge] comparison legacy=2 room=2 onlyLegacy=0 onlyRoom=0`; çökme yok |
| `PRAGMA user_version` / kimlik | 2 / `b64828285a91f1f93115230ff973b648` |
| `integrity_check` | ok |
| Sütunlar / indeksler | v2 düzeni; `index_notes_city`, `index_notes_writeTicket`; `updatedAt` ve `notes_v2` artığı yok |
| Satırlar | 3/3 korundu; kimlikler aynı; `writeTicket` = eski `updatedAt` (birebir); `modifiedAt` ve `isAI` `NULL` |
| Tombstone | C korunuyor; `deletedAt` değişmedi |
| `localStorage` (`tur_v10`) | A (favori) ve B, sayısal kimliklerle aynen duruyor |

### v2 işlevleri (gerçek dokunuşlarla)
| Adım | Logcat (native → WebView) | Room |
| :--- | :--- | :--- |
| Not oluştur (D, 4 yıldız) | `upsert … ack=Applied` → `[RoomBridge] write ok` (aynı istek kimliği) | yeni satır, `isAI=0`, `modifiedAt` dolu |
| Düzenle ve Kaydet | `upsert … Applied` → `write ok` | başlık ve içerik güncellendi |
| Favori | `upsert … Applied` → `write ok` | `favorite=1` |
| Sil | `delete … Applied` → `delete ok` | tombstone: `deletedAt = modifiedAt` duvar saati, `writeTicket` silme bileti; düzenlenmiş başlık ve favori korunur |
| v1'den gelen B'yi favorile | `upsert … Applied` | migration satırı güncellendi: `isAI=0`, `modifiedAt` doldu, yeni bilet; `createdAt` korundu |

### Yaşam döngüsü ve yük senaryoları
| Senaryo | Sonuç |
| :--- | :--- |
| Favoriye dokun ve hemen döndür | Yazma `Applied` ve `write ok`; Activity iki kez yeniden oluştu (`relaunch`); yeni sayfada `legacy=2 room=2` |
| Yataya kilitle (2400×1080) | Activity yeniden oluştu; `legacy=2 room=2`; döndürmeden önceki favori korunmuş |
| Yatayda E notunu ekle ve hemen dikeye döndür | `Applied` ve `write ok`; yeni Activity'de `legacy=3 room=3` |
| Zorla durdur ve soğuk başlat | Yeni süreç; `read ok active=3`; `legacy=3 room=3` |
| Geri tuşu, “Evet” ile çıkış, yeniden aç | `legacy=13 room=13`; alan alan karşılaştırmada fark yok |
| 7 hızlı favori dokunuşu (tek komut) | 7 ayrı `upsert`, biletler kesin artan, hepsi `Applied`; son durum iki tarafta aynı |
| Art arda 10 not ekleme (gerçek `Not Ekle` işleyicisi) ve aynı note 9 favori değişimi | 19/19 `Applied` ve 19/19 `write ok`; `IgnoredStale`, `Rejected` veya hata 0; 10 kimlik benzersiz ve güvenli tam sayı |
| Aynı milisaniyede 5000 kimlik (`NoteIds.nextNoteId`, Chrome 145) | çakışma 0 |

10 eklemenin tıklamaları her seferinde yeniden çizim yaptığı için 190 ms'ye yayıldı. Aynı milisaniye koşulu, kimlik üreticisinin gerçek WebView motorunda doğrudan çağrılmasıyla ayrıca sınandı.

**Son tam karşılaştırma** (cihazdan çekilen veritabanı ve DevTools ile okunan `tur_v10`): `localStorage` 13 not; Room 13 etkin ve 2 tombstone (v1'den C, v2'den D); yalnız Room'da bulunan etkin not yok. Başlık, içerik, kategori, yıldız, renk, favori ve tarih alanlarında **fark yok**. Veritabanı `integrity ok`.

### Hata bildirimleri (Logcat)
Test için köprü doğrudan çağrıldı; `localStorage` değişmedi (`before === after`).
- Silinmiş D'yi diriltme denemesi: `upsert request=p5-reject-test … ack=Rejected(message=Deleted note cannot be restored by a stale write)` → WebView `Room pilot write failed: Deleted note cannot be restored…`. D tombstone'u değişmedi.
- Bozuk JSON: `E/SehirNotlariRoom upsert failed request=p5-bad-json` ve `JSONException` → WebView `Room pilot write failed: Expected ':' …`.
- Kilitlenme kaydında `com.sehirnotlari` yok. Dropbox ANR/çökme kayıtları yalnız açılış sırasındaki Google sistem uygulamalarına ait (Play Store, GMS, Android System Intelligence).

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL** (1m 10s). 67 test, 0 başarısız, 0 atlanan. |

### Yapılamayan veya sonuçsuz kalanlar
1. **Yazma sürerken süreç ölümü:** Favori dokunuşu ve `am force-stop` aynı komutta verildi. Süreç dokunuş JS'e ulaşmadan öldü; iki tarafta da değişiklik olmadı. Room yazması 10–20 ms sürdüğü için bu durum `adb` ile güvenilir biçimde üretilemedi. Bu yolda `localStorage` önce yazıldığından, olursa Room'da eksik veya eski bir gölge kalır (aşağıdaki risk 1).
2. **Kaybolan geri bildirim:** Döndürme sırasındaki yazmalar Activity yenilenmeden bittiği için “unknown request” satırı görülmedi; yol kodda var, cihazda tetiklenmedi.
3. **Gerçek fiziksel cihaz** kullanılmadı; yalnız emülatör (API 37). Daha eski Android sürümleri (minSdk 24'e kadar) ve farklı WebView sürümleri denenmedi.
4. **Yapay zekâ notu** (`isAI=true`) cihazda oluşturulmadı (Gemini çağrısı gerektirir). `isAI` yazımı birim testlerinde doğrulandı; cihazda elle notların `isAI=0` yazıldığı görüldü.
5. JSON dosyasından içe aktarma ve buluttan geri yükleme cihazda denenmedi.

### Kalan riskler
1. **Süreç ölümünde gölge sapması:** `localStorage` eşzamanlı yazılır, Room yazması süreç içi kuyruktadır. Süreç tam bu aralıkta ölürse Room eski kalır. Uygulamanın kendisi Room'u yeniden eşitlemez; açılıştaki karşılaştırma yalnız kimlik kümelerini sayar, alan farklarını göstermez.
2. **Hata kaydında içerik:** Bozuk JSON durumunda `org.json` istisna mesajı girdinin bir kısmını içerir ve bu Logcat'e düşebilir (bu davranış P5'ten önce de vardı).
3. Önceki riskler sürüyor: geri dönüş yok (v2 → v1 APK'sı Room'u açamaz); v1 satırlarında `isAI` ve `modifiedAt` boş kalır; `createdAt` Room'a ilk yazılma zamanıdır; Room ana kaynak değil.

### Gereken ortam (eksikleri tamamlamak için)
- Fiziksel bir Android cihaz (USB hata ayıklama açık) veya daha eski API'li (ör. API 24/26/30) ek AVD'ler; bunun için Android Studio SDK Manager'dan ilgili sistem imajları ve isteğe bağlı olarak `cmdline-tools`.
- Yapay zekâ notu için geçerli Gemini anahtarı ve ağ (yerelde mevcut, ama test kapsamına alınmadı).

## P6 — Veri tutarlılığı ve güvenli toparlama (6 Kasım 2026)

Başlangıç: `316ef27d0e30886f48016afc7e504e492aea33ec`. Push/merge yok. `main` değişmedi. `localStorage` (`tur_v10`) ana kaynak olarak kaldı. Toparlama onu yalnız okur; cihazda her senaryodan önce ve sonra alınan `tur_v10` kopyaları bayt bayt aynı çıktı. Otomatik Room ana kaynak geçişi açılmadı. Not kimlikleri değişmedi. Yıkıcı migration yok. Toparlama hiçbir Room satırını silmez ve hiçbir tombstone'u diriltmez.

### Sorun
Not önce eşzamanlı olarak `localStorage`'a yazılır, sonra Room'a gönderilir. Room yazması süreç içi kuyrukta bekler. Süreç bu arada ölürse Room eski kalır (P5 risk 1). Cihazda ikinci bir sorun da ortaya çıktı: **Chromium WebView `localStorage`'ı diske gecikmeli yazıyor.** Süreç öldürülünce son `localStorage` yazmaları da kaybolabiliyor, bu yüzden Room `localStorage`'dan *daha yeni* kalabiliyor. Bu nedenle iki taraftan hiçbiri her zaman doğru sayılamaz: Room'u körlemesine `localStorage`'a eşitlemek, daha yeni bir Room kaydının üzerine eski veri yazabilir.

### Tasarım
- **Kalıcı yazma günlüğü** (`note_write_journal`, şema v3). Her köprü yazması, kuyruğa alınmadan *önce* JavaBridge thread'inde eşzamanlı olarak `PENDING` durumuyla kaydedilir. Kayıtta bilet, `city::id` anahtarı, işlem (`UPSERT`/`DELETE`) ve notun **SHA-256 parmak izi** bulunur. Not metni günlüğe yazılmaz. Yazma bitince kayıt `APPLIED`, `IGNORED` (eski yazma) veya `REJECTED` (tombstone) olur; istisna olursa `FAILED`. Uygulama bu aralıkta ölürse kayıt `PENDING` kalır ve yarım kalan yazmanın kalıcı kanıtı olur. Günlük yazılamazsa not yazması yine yapılır; hata yalnız içeriksiz olarak loglanır.
- **Parmak izi** (`NoteFingerprint`): başlık, içerik, kategori, yıldız, renk, favori, isAI ve tarih alanları uzunluk önekiyle birleştirilip özetlenir. Köprü ve toparlama `localStorage` notunu aynı ayrıştırıcıyla (`RoomNoteDraft.fromJson`) okur.
- **v2 → v3 migration** (`MIGRATION_2_3`) yalnız günlük tablosunu ve indeksini ekler. `notes` tablosuna dokunmaz; şema dosyasında `notes` tanımı v2 ile birebir aynı. Kimlik özeti `3cfaf8977031672fcc732178080cc3c3`.
- **Açılışta toparlama:** Sayfa yüklenince JS, `JSON.stringify(DB)`'yi salt okunur olarak `Android.reconcileRoomNotes` köprüsüne gönderir. Native taraf, istek anındaki bileti sınır (`cutoff`) olarak alır. Karşılaştırma ve onarım, yazma sıralayıcısının kilidi altında **tek bir Room transaction'ında** yapılır. Sınırdan sonraki yazmalar ve onlara ait günlük kayıtları bu turda değerlendirilmez (`changedSince`); bir sonraki açılışa kalır.
- **Alan alan karşılaştırma:** başlık, içerik, kategori, yıldız, renk, favori, tarih ve isAI. Room'da isAI bilinmiyorsa (v1 satırı) bu alan karşılaştırılmaz ve satır `UNKNOWN_ORIGIN` olarak işaretlenir.
- **Onarım kuralı:** Onarım yalnız kanıt varsa yapılır. Kanıt, Room satırının biletinden daha yeni, çözülmemiş (`PENDING`/`FAILED`) ve parmak izi `localStorage`'daki hâlle **birebir aynı** olan bir günlük kaydıdır. Onarım o kaydın biletini ve oluşturulma zamanını kullanır, değiştirilen satırı JSON olarak günlük kaydının `previousJson` alanında saklar. Bu sayede Room'daki önceki hâl geri getirilebilir. Bu kayıttan eski çözülmemiş kayıtlar `SUPERSEDED` olur. Daha yeni olanlar `LOST` olur: iki depoya da ulaşmamışlardır ve metinleri kurtarılamaz.
- **Tekrar çalıştırılabilirlik ve kesinti:** Onarılan satırın bileti kanıt kaydına eşitlendiği ve kayıt çözüldüğü için ikinci çalıştırma bir şey değiştirmez. Transaction yarıda kesilirse (çökme veya istisna) tamamı geri alınır ve bir sonraki açılışta aynı kanıtla yeniden denenir. Kuyrukta onarımdan sonra biten eski bir yazma, sıralayıcı bileti sayesinde `IgnoredStale` olur. Uygulama yeniden başladığında bilet sayacı, Room ve günlükteki en büyük biletin üstünden devam eder.
- **Önizleme:** `reconcileRoomNotes(json, id, false)` hiçbir şey yazmaz; kanıtı olan onarımları `REPAIRABLE` olarak raporlar.
- **Budama:** Çözülmüş günlük kayıtları 30 gün sonra silinir. `PENDING` ve `FAILED` kayıtlar silinmez.
- **Rapor:** Logcat'te `SehirNotlariRoom … reconcile` ve konsolda `[RoomBridge] reconcile` satırları **yalnız sayıları** gösterir. Not anahtarlı ayrıntılar yalnız WebView belleğindeki `window.roomReconcileReport` içinde tutulur ve loglanmaz.

### Fark türleri ve davranış
| Tür | Anlamı | Otomatik düzeltme | Kanıt yoksa |
| :--- | :--- | :--- | :--- |
| `MISSING_IN_ROOM` | Not `localStorage`'da var, Room'da hiç yok | Parmak izi eşleşen bekleyen `UPSERT` varsa eklenir | Raporlanır |
| `CONTENT_MISMATCH` | Room'da etkin, alanlar farklı (`fields` listesi) | Room biletinden yeni ve parmak izi eşleşen bekleyen `UPSERT` varsa güncellenir, eski satır `previousJson`'da saklanır | Raporlanır. Günlük `localStorage` hâlinin Room'dan eski olduğunu gösteriyorsa `roomNewer=true` olur |
| `TOMBSTONE_CONFLICT` | Not `localStorage`'da var, Room'da silinmiş | **Asla düzeltilmez**; eşleşen bekleyen yazma olsa da diriltilmez | Raporlanır |
| `ONLY_IN_ROOM` | Room'da etkin, `localStorage`'da yok | Room satırından yeni bekleyen `DELETE` varsa yalnız tombstone yapılır; içerik korunur, satır silinmez | Raporlanır, satır silinmez |
| `UNKNOWN_ORIGIN` | Alanlar aynı, ama satır v1'den veya eski aktarımdan geliyor (`isAI`/`modifiedAt` boş) | Satıra dokunulmaz | Raporlanır |
| `UNMATCHED_PENDING_WRITE` | Bekleyen yazma iki depoya da ulaşmamış | Kayıt `LOST` olur (yalnız günlük) | Raporlanır, sayısı `lostWrites` |

**Kullanıcı onayı gerektiren durumlar** (otomatik dokunulmaz, yalnız raporlanır): günlük kanıtı olmayan eksik kayıt, farklı içerik ve yalnız Room'da olan not; her tombstone çakışması; kökeni belirsiz satırlar; Room'un daha yeni olduğu durumlar. Henüz bir onay arayüzü yok. Rapor `window.roomReconcileReport` ve önizleme modu üzerinden alınabiliyor.

### Log ve hata güvenliği
- Room köprüsünün bütün logları ve WebView'a dönen hata mesajları `RoomBridgeErrors.safe` üzerinden geçer. Uygulamanın kendi `RoomBridgeFailure` mesajları sabit metinlerdir (`Invalid note payload`, `Invalid legacy notes payload`…). Diğer istisnalarda yalnız sınıf adı yazılır. Throwable, `e.message` ve JSON loglanmaz. Bu, P5 risk 2'deki `org.json` mesajı sızıntısını kapatır. Eski not aktarımının hataları da `RoomBridgeFailure` kullanıyor.
- Gemini tarafında API anahtarının ilk 4 karakteri ("Key prefix") loglanıyordu; kaldırıldı. Şehir rehberi istem ve yanıtı da loglanıyordu; artık yalnız yanıt uzunluğu loglanıyor. PDF ve rehber hatalarında yığın izi ve sunucu mesajı yerine yalnız istisna sınıf adı yazılıyor. PDF yanıtının tam metni (`Full response`) artık loglanmıyor. Şehir adı (not dışı, herkese açık veri) rehber loglarında kalıyor.
- `RoomMigrationGuardTest` bu kuralları kaynak koddan denetler. Gemini kuralı, geçici olarak eski bir log satırı eklenerek sınandı ve test başarısız oldu.

### Otomatik testler (P6)
- `RoomNoteReconcilerTest` (22, Robolectric, gerçek SQLite dosyası). Her "süreç" veritabanını yeniden açar; `completes=false`, Room yazması sırasında yaşanan çökmeyi temsil eder. Kapsanan senaryolar: çökmede kesilen yeni not ve düzenleme (onarılır, eski satır saklanır); Room'a ulaşmış yazmanın tekrarlanmaması; Room daha yeniyken ve eski `localStorage` için bekleyen yazmalar varken üzerine yazılmaması; günlüksüz eksik kayıt ve alan alan içerik farkı (yalnız rapor); tombstone'un eşleşen bekleyen yazmayla bile diriltilmemesi; kökeni belirsiz satır; çökmede kesilen silme (tombstone olarak tamamlanır); günlüksüz yalnız-Room notu (silinmez); iki tarafa da ulaşmayan yazma (`LOST`); son yazmalarını kaybetmiş `localStorage`; `cutoff` sonrası yazmalar; tekrar çalıştırma; transaction yarıda kesilince geri alma ve yeniden deneme; önizleme; onarımdan sonra biten eski yazma ve sonraki düzenleme; yeniden başlatmada bilet tohumu; budama; bozuk payload; yalnız sayılardan oluşan özet.
- `NoteFingerprintTest` (6): karşılaştırılan her alan parmak izini değiştiriyor; alan sınırları kaydırılamıyor (uzunluk öneki); isAI bilinmiyorsa satır parmak izi yok; `differingFields` yalnız bilinmeyen isAI'yi atlıyor; WebView notu köprüyle aynı okunuyor; köprü hataları yabancı istisna mesajını yansıtmıyor.
- `NoteSchemaMigrationTest`: gerçek v2 dosyası v3'e taşınıyor; bütün satırlar korunuyor ve günlük boş geliyor. Yeni kurulum doğrudan v3. `NoteSchemaContractTest` +2: v3 yalnız günlük tablosunu ekliyor; günlükte metin değil parmak izi var. `RoomBridgeScriptsTest` +1. `RoomMigrationGuardTest` +3: Room logları, Gemini logları, salt okunur toparlama ve yalnız sayı loglayan JS.

### Emülatör sonuçları (`emulator-5554`, `SehirNotlari_P5_Test`, API 37, WebView 145)
Test APK'sı geçici kopyada `versionCode` 100 / `versionName` "100.0" ile derlendi (güncelleyici yüzünden, P5'teki gibi). Depodaki sürüm numarası değişmedi. Cihazdaki derlemenin kaynağı bu commit'le aynı. Tek fark, emülatör testlerinden sonra yapılan Gemini log temizliği: o yol cihazda çalıştırılmadı (Gemini çağrısı gerektirir) ve birim testleriyle denetlendi. Bütün veriler yalnız test notlarıdır.

| Senaryo | Sonuç |
| :--- | :--- |
| v2 → v3 yükseltme (`install -r`, veri silinmeden) | `user_version` 3, kimlik `3cfaf897…`, `integrity ok`, günlük tablosu ve indeksi var. 15 `notes` satırı birebir aynı, `tur_v10` özeti değişmedi. İlk toparlama: legacy=13, roomActive=13, tombstones=2, consistent=12, unknownOrigin=1 (v1'den kalan A), onarım 0 |
| Günlüklü CRUD (uygulamanın kendi işleyicileriyle oluştur/düzenle/sil) | 3 `UPSERT` ve 1 `DELETE` kaydının hepsi `APPLIED` |
| **Gerçek ani kapanma:** aynı nota 5,3 sn'de 400 hızlı düzenleme, sonra `am force-stop` | Room 298. düzenlemede kaldı. Günlükte 102 `PENDING` vardı. Yeniden açılışta `localStorage` 378. düzenlemedeydi: WebView son 22 yazmayı diske yazmamıştı. Toparlama **repaired=1, lostWrites=22, superseded=79**. Room satırı 378. düzenlemeye getirildi; bilet ve `modifiedAt` kanıt kaydından alındı; 298. düzenleme `previousJson`'da saklandı. `localStorage` değişmedi. İkinci açılış: değişiklik 0 |
| **Room daha yeni:** başka bir nota 30 düzenleme, 0,4 sn sonra `force-stop` | Room 30. düzenlemeyi tuttu, `localStorage` bunların hepsini kaybetti. Sonuç `CONTENT_MISMATCH` REPORTED, `fields=[content]`. **Room'un üzerine yazılmadı.** `roomNewer=false` çıktı, çünkü `localStorage`'daki eski hâl P6 öncesinde yazılmıştı ve günlükte yoktu |
| **Kontrollü farklar** (Room dosyası çekildi, yalnız Room tarafında 6 fark oluşturuldu ve cihaza geri yüklendi) | Bkz. aşağıdaki tablo |
| Kontrollü farklardan sonra ikinci açılış | repaired=0. Onarılan iki not artık tutarlı veya tombstone; kalan dört fark aynen raporlandı |
| Hata yolları | Bozuk not JSON'u: `upsert failed … error=Invalid note payload`. Bozuk toparlama JSON'u: `reconcile failed … error=Invalid legacy notes payload`. Tombstone'a yazma: `Rejected(Deleted note cannot be restored by a stale write)`. WebView'da aynı sabit mesajlar görüldü |
| Logcat taraması | Son açılış ve hata yollarından sonra alınan 564 satırlık tam Logcat dökümünde, test başlıkları, içerikleri, gizli test payload'ları ve `tur_v10` için **0 eşleşme** var. Room ve konsol satırlarında yalnız sayılar ve istek kimlikleri bulunuyor |

Kontrollü farklar (ilk açılış: legacy=13, roomActive=12, tombstones=4, consistent=7, repaired=2, `tur_v10` değişmedi, `integrity ok`):

| Room'da yapılan değişiklik | Rapor | Room'un son hâli |
| :--- | :--- | :--- |
| Bir satır silindi | `MISSING_IN_ROOM` REPORTED | Eksik kaldı |
| Bir satırın başlığı değiştirildi | `CONTENT_MISMATCH` REPORTED, `fields=[title]` | Değişmedi |
| Bir satır tombstone yapıldı | `TOMBSTONE_CONFLICT` REPORTED | Tombstone kaldı |
| Yalnız Room'da bir not eklendi | `ONLY_IN_ROOM` REPORTED | Etkin kaldı |
| Bir satır silindi ve `localStorage` parmak iziyle bir `PENDING UPSERT` eklendi | `MISSING_IN_ROOM` **REPAIRED** | Satır eklendi; parmak izi Python ve Kotlin uygulamalarında aynı çıktı |
| Yalnız Room'da bir not ve onun için `PENDING DELETE` eklendi | `ONLY_IN_ROOM` **REPAIRED** | Tombstone oldu, içerik korundu |
| (önceden var) Room'un daha yeni kaldığı not / v1'den A | `CONTENT_MISMATCH` REPORTED / `UNKNOWN_ORIGIN` REPORTED | Değişmedi |

Hata yolu denemesinden önceki son günlük durumu: 331 `APPLIED`, 1 `IGNORED`, 3 `REPAIRED`, 79 `SUPERSEDED`, 22 `LOST`, 0 `PENDING`.

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL**. 102 test, 0 başarısız, 0 atlanan (P5'te 67) |

### Kalan riskler
1. **Onay arayüzü yok:** Raporlanan farklar kullanıcıya gösterilmiyor ve uygulama içinden çözülemiyor. Rapor yalnız `window.roomReconcileReport` ve Logcat sayılarında görülüyor.
2. **`roomNewer` geçmişe bağlı:** Bu işaret yalnız `localStorage`'daki eski hâl günlükte kayıtlıysa açılır. P6 öncesinde yazılmış notlarda Room'un daha yeni olduğu anlaşılamaz; fark yine de güvenli biçimde yalnız raporlanır.
3. **`LOST` yazmaların metni kurtarılamaz:** Günlükte yalnız parmak izi var. Bu bilinçli bir tercih: günlük not metni saklamıyor.
4. **WebView `localStorage` kaybı:** Ana kaynak, süreç ölümünde son saniyelerdeki yazmaları kaybedebiliyor. Toparlama bu yazmaları geri getiremez; yalnız Room'u ezmemeyi ve kaybı saymayı garanti eder. Kalıcı çözüm Room'u ana kaynak yapmak veya `localStorage` yazmasını onaylı hâle getirmektir; ikisi de bu fazın kapsamı dışında.
5. **Günlük öncesi farklar:** P6'dan önce oluşmuş farkların kanıtı olmadığı için hiçbiri otomatik düzeltilmez.
6. Fiziksel cihaz ve eski API sürümleri yine denenmedi. Gemini log temizliği cihazda çalıştırılmadı. Önceki riskler sürüyor: geri dönüş yok (v3 → v2 APK'sı Room'u açamaz), Room ana kaynak değil.

## P7 — Güvenli veri uzlaştırma ekranı (6 Kasım 2026)

Başlangıç: `c2819fa5fb4554a207364533f4dcf23aa970a27b`. Push/merge yok, `main` değişmedi. `localStorage` (`tur_v10`) ana kaynak olarak kaldı: ekrandaki hiçbir işlem ona yazmıyor ve cihazda testlerden önce ve sonra alınan kopyalar bayt bayt aynı çıktı. Room ana kaynak yapılmadı. Otomatik geçiş açılmadı. Belirsiz kayıtlar yalnız kullanıcı onayıyla değişiyor.

### Mimari inceleme
- **Raporun WebView'a aktarımı:** Açılışta JS, `reconcileRoomNotes(JSON.stringify(DB), id, true)` çağırır. Native taraf raporu `onRoomReconcileResult(id, report, error)` ile döndürür. Not anahtarlı ayrıntılar yalnız `window.roomReconcileReport` belleğinde tutulur.
- **Yeniden açılış ve döndürme:** Activity ve WebView yeniden oluştuğu için rapor her sayfa yüklemesinde yeniden hesaplanır. Çözülmemiş farklar kaybolmaz, çünkü veri farklı kaldıkça yeniden bulunurlar. Ancak **otomatik onarımlar ve kaybolan yazmalar ikinci açılışta raporda 0 görünüyordu**; bilgi kayboluyordu. Ekran bu sayıları artık günlükten (`REPAIRED`/`LOST` kayıtları, son 30 gün) ve yedek tablosundan okuyor.
- **Günlük yaşam döngüsü:** `PENDING` → `APPLIED`/`IGNORED`/`REJECTED`/`FAILED`; açılışta toparlama → `REPAIRED`/`SUPERSEDED`/`LOST`. Çözülmüş kayıtlar 30 gün sonra budanır. **P6 hatası düzeltildi:** budama `createdAt`'e göre yapılıyordu. Uzun süre önce kesilmiş bir yazmanın onarımı, sakladığı önceki satırla (`previousJson`) birlikte aynı turda silinebiliyordu. Artık `COALESCE(resolvedAt, createdAt)` kullanılıyor. Eski sorguyla başarısız olan bir regresyon testi eklendi.
- **Köprü protokolü:** Mevcut köprü fonksiyonları ve geri çağırmalar değişmedi. Dört yeni fonksiyon (`inspectRoomNote`, `applyRoomResolution`, `undoRoomResolution`, `requestDataSafetyStatus`) ve beyaz listeli üç geri çağırma (`onRoomInspectResult`, `onRoomResolutionResult`, `onDataSafetyStatus`) eklendi. Hepsi istek kimliğiyle eşleşir; bilinmeyen kimlikli sonuçlar yok sayılır.
- **Yerleşim:** Uygulamada ayrı bir "Ayarlar" ekranı yok. Veri ayarları çekmecedeki **"Veri ve Yedek"** sekmesinde. "🛡️ Veri Güvenliği" bölümü o sekmenin ilk kartı oldu. İnceleme bekleyen fark varsa çekmecedeki sekmede bir rozet görünür.

### Tasarım
- **Şema v4:** Yeni tablo `note_resolution_backup` (anahtar, şehir, işlem, fark türü, önceki satırın JSON'u, işlem bileti, zaman, geri alınma zamanı). Migration (`MIGRATION_3_4`) yalnız bu tabloyu ve indeksini ekler. `notes` ve günlük tabloları değişmez. Kimlik özeti `2c956b95e63f67a6c80939ca693ddad1`.
- **İşlemler yalnız Room'u ve her seferinde tek bir notu değiştirir:**
  - `SYNC_FROM_LOCAL` Room satırını telefondaki notla ekler veya günceller ya da kökeni doğrular.
  - `MARK_DELETED_IN_ROOM` satırı tombstone yapar; metin silinmez.
- **Önizleme → onay akışı:**
  - `inspectRoomNote` iki sürümü, uygulanabilir seçeneği ve bir jeton döndürür. Jeton; Room biletini, silinmiş durumunu ve `localStorage` parmak izini kodlar. Bu adım hiçbir şey yazmaz.
  - `applyRoomResolution` sıralayıcı kilidi altında ve tek transaction'da çalışır:
    1. Planı yeniden hesaplar. Jeton farklıysa işlem `STALE` olur, hiçbir şey yazılmaz.
    2. Seçenek kapalıysa `NOT_ALLOWED`; onay gerekip verilmemişse `CONFIRMATION_REQUIRED` döner.
    3. Önce önceki satırın yedeği yazılır, sonra değişiklik yeni bir biletle uygulanır.
- **Geri alma:** Önceki satır birebir geri yazılır. Bu yalnız satır işlemden sonra hiç değişmediyse (`writeTicket == afterTicket`) mümkündür; değiştiyse `UNDO_BLOCKED` döner. Room'a ekleme işlemlerinde geri getirilecek önceki satır yoktur. Bilet sayacının başlangıç değeri yedeklerdeki biletleri de kapsar.
- **Toplu işlem:**
  - Varsayılan olarak kapalıdır: kapalı bir `details` bölümünün içindedir, etkinleştirme kutusu işaretsizdir ve düğme devre dışıdır.
  - Etkinleştirildiğinde ayrıca bir onay kutusu ister.
  - Yalnız *Room'da eksik* ve *kökeni belirsiz* kayıtları kapsar; Room'un daha yeni olduğu kayıtlar dahil değildir. Hiçbir kaydın üzerine yazmaz.
  - Her not ayrı incelenir ve ayrı uygulanır; durumu değişen not atlanır.
- **Loglar:** Logcat'e yalnız işlem kodu, yedek numarası ve sayılar yazılır; konsola `[DataSafety] <kod>` yazılır. Not metni ve anahtar loglanmaz. Bu kural, kaynak koddan denetleyen testlerle korunuyor.

### Kullanıcıya gösterilen durumlar
| Durum | Başlık |
| :--- | :--- |
| Kontrol sürüyor | "Kontrol ediliyor…" |
| Köprü hatası / zaman aşımı | "Kontrol tamamlanamadı. Notlarınız değişmedi; daha sonra yeniden deneyin." |
| Fark yok | "Tutarlı: telefondaki notlarınız ve Room kopyası aynı." |
| İnceleme bekleyen fark | "İnceleme bekleyen N fark var." |
| Yalnız kanıtlı onarım (önizlemede) | "N fark kanıtla onarılabilir." |
| Yalnız kökeni belirsiz kayıt | "Fark yok, ancak N kaydın kökeni belirsiz." |

Ayrı sayılar şunlardır: **otomatik onarılan (son 30 gün)**, **inceleme bekleyen**, **kökeni belirsiz**. Gerekirse **kanıtla onarılabilir** ve **kaybolan değişiklik** sayıları da gösterilir. Her fark türünün Türkçe bir etiketi ve açıklaması var: *Room kopyasında eksik*, *İçerik farklı* (farklı alanlar adlarıyla listelenir), *Room'da silinmiş*, *Yalnız Room'da*, *Kökeni belirsiz*, *Kaybolan değişiklik*. "Room daha yeni olabilir" ayrı bir uyarı etiketi olarak gösterilir.

"İncele" penceresinde şunlar gösterilir:
- korunacak kaynak ve işlemin sonucu;
- yalnız farklı olan alanlar, telefondaki ve Room'daki değerleriyle yan yana;
- uyarı ve onay kutusu;
- işlem sonrası sonuç metni.

"Son işlemler" listesinde her işlem için şu durumlardan biri görünür: "Geri al" düğmesi, "Geri alındı", "Geri alınacak önceki kayıt yok" veya "Not sonradan değiştiği için geri alınamaz".

### Uygulanabilir güvenli işlemler
| Fark | Düğme | Onay | Sonuç |
| :--- | :--- | :--- | :--- |
| Room kopyasında eksik | Room'a ekle | Gerekmez | Satır eklenir. Geri alınacak önceki satır yoktur |
| İçerik farklı | Room'u telefondaki notla güncelle | **Gerekir** | Room sürümü yedeklenir, sonra değiştirilir; geri alınabilir. Room daha yeni olabilirse veya kökeni belirsizse ek uyarı gösterilir |
| Kökeni belirsiz | Kökeni doğrula | Gerekmez | Metin değişmez; yalnız `isAI`/`modifiedAt` doldurulur; geri alınabilir |
| Yalnız Room'da | Room'da silindi olarak işaretle | **Gerekir** | Tombstone olur, metin silinmez; geri alınabilir |
| Room'da silinmiş | Uygulanamaz (düğme kapalı) | — | **Tombstone diriltilmez**; yalnız bilgi verilir |
| Kaybolan değişiklik | — | — | Yalnız bilgi verilir (metni kurtarılamaz) |
| Kanıtlı onarım (önizlemede) | Kanıtlı onarımları uygula | Gerekmez | Açılıştaki otomatik onarımın aynısıdır (P6 kuralları) |

"Yeniden kontrol et" ve sekmenin açılması salt okunur bir önizleme çalıştırır (`requestRoomReconcile(false)`).

### Otomatik testler (P7)
- `RoomNoteResolverTest` (15, Robolectric, gerçek SQLite). Kapsanan senaryolar:
  - tutarlı notta seçenek olmaması ve onayın etkisiz kalması;
  - eksik satırın eklenmesi (geri alma hedefi yok);
  - üzerine yazmanın onay istemesi, yedeklenmesi ve birebir geri alınması;
  - Room daha yeniyken yalnız onayla değiştirme;
  - tombstone'un hiçbir onayla diriltilmemesi;
  - kökeni belirsiz kaydın metin değişmeden doğrulanması;
  - yalnız Room'daki notun metni korunarak silindi işaretlenmesi ve geri alınması;
  - önizlemeden sonra iki taraftan biri değişince işlemin reddedilmesi (`STALE`);
  - aynı onayın iki kez uygulanması (`ALREADY_CONSISTENT`);
  - **onay sırasında çökme** (transaction geri alınır; yedek de değişiklik de kalmaz; yeniden denenebilir);
  - not yeniden değişince geri almanın engellenmesi;
  - yeniden başlatmada onarım, kayıp ve yedek geçmişinin korunması;
  - geri alma ve yeniden başlatmadan sonra biletlerin büyümeye devam etmesi;
  - planın her fark türünde açılış raporuyla aynı sınıflandırmayı yapması;
  - plan JSON'unun iki sürümü içermesi ve bozuk girdide güvenli hata vermesi.
- `DataSafetyScriptTest` (15, Rhino ile gerçek `data-safety.js`). Kapsanan senaryolar:
  - fark yokken ekranın davranışı;
  - kontrol sürerken ve hata durumunda hiçbir şey iddia edilmemesi;
  - otomatik onarımların kalıcı geçmişten sayılması ve inceleme listesine girmemesi;
  - yeniden başlatmadan sonra bekleyen farkların ve önceki onarımların görünmesi;
  - farkların gruplanması ve Türkçe açıklamalar;
  - tombstone için hiçbir zaman etkin onay olmaması;
  - kökeni belirsiz kayıtların ayrı sayılması;
  - üzerine yazmanın onay kutusu istemesi ve iki sürümü göstermesi;
  - yalnız Room'daki not;
  - toplu işlemin yalnız düşük riskli türleri kapsaması;
  - her native sonuç kodu ve gerekçe için metin bulunması;
  - geçmişte geri almanın yalnız güvenli olduğunda sunulması;
  - anahtarların Kotlin'le aynı biçimde olması;
  - model dosyasının log, depolama ve köprü kullanmaması.
- `RoomMigrationGuardTest` +2:
  - Ekran bloğu `localStorage`'a ve `DB`'ye yazmıyor; konsola yalnız kod ve sayı yazıyor.
  - Erişilebilirlik öznitelikleri doğru: `role=dialog` + `aria-modal`, `role=status` + `aria-live`, her düğmede `type="button"`, 44 px dokunma hedefi, 360 px kırılımı.
  - Toplu işlem varsayılan olarak kapalı; onay düğmesi başlangıçta devre dışı; geri tuşu önce pencereyi kapatıyor.
- `RoomBridgeScriptsTest` +1: geri çağırma beyaz listesi.
- `NoteSchemaContractTest` +1, `NoteSchemaMigrationTest` +1: gerçek v3 dosyası v4'e taşınıyor; satırlar ve günlük korunuyor. `RoomNoteReconcilerTest` +1: budama hatasının regresyon testi.

### Emülatör sonuçları (`emulator-5554`, `SehirNotlari_P5_Test`, API 37, WebView 145)
Test APK'sı geçici kopyada `versionCode` 101 / `versionName` "101.0" ile derlendi; depodaki sürüm numarası değişmedi. Veriler P6'dan kalan test notlarıdır.

| Senaryo | Sonuç |
| :--- | :--- |
| v3 → v4 yükseltme (`install -r`) | `user_version` 4, `integrity ok`. İlk toparlama P6'daki farkları aynen buldu: eksik 1, içerik farkı 2, tombstone 1, yalnız Room 1, kökeni belirsiz 1 |
| Ekran (dikey) | "İnceleme bekleyen 5 fark var." Sayılar: 3 otomatik onarılan (günlükten; açılış raporunda 0), 5 inceleme bekleyen, 1 kökeni belirsiz, 22 kaybolan değişiklik. Rozet 6 |
| Her fark için İncele | Doğru düğme ve onay gereksinimi gösterildi. Tombstone için düğme "Uygulanamaz" ve kapalı. Üzerine yazma ve yalnız Room'daki kayıt için düğme onay kutusu işaretlenene kadar kapalı |
| Üzerine yazma (onaylı) | `APPLIED` döndü, yedek alındı. Aynı jetonla tekrar gönderilince `ALREADY_CONSISTENT` döndü |
| Eksik satır / kökeni belirsiz / yalnız Room | Sırasıyla `APPLIED` (geri alma yok), `APPLIED`, onaysız `CONFIRMATION_REQUIRED` ve onaylı `APPLIED`. Tekrar gönderilince `ALREADY_CONSISTENT` |
| Önizlemeden sonra telefondaki not değişti (yalnız gönderilen kopyada) | `STALE`, değişiklik yok |
| Tombstone'u köprüden onayla zorlama | `NOT_ALLOWED` |
| Geri alma (arayüzden) | `UNDONE`; ikinci deneme `ALREADY_UNDONE`. Kökeni doğrulama geri alındı ve yeniden uygulandı |
| Pencere açıkken döndürme | WebView yeniden yüklendi, pencere kapandı. Rapor, sayılar ve 4 işlemlik geçmiş Room'dan geri geldi |
| İşlem sırasında `force-stop` | İşlem kapanmadan önce bitti: yedek ve satır aynı biletle, birlikte yazılmış; `integrity ok`. Yarım kalan transaction senaryosu birim testinde doğrulandı (cihazda zamanlanamıyor) |
| Yeniden açılış | 5 işlemlik geçmiş ve çözülmemiş 2 fark korundu |
| Küçük ekran (300 dp genişlik, yazı ölçeği 1,3) ve erişilebilirlik ağacı | Yatay taşma yok; 44 px'ten küçük düğme yok; karşılaştırma tek sütuna düştü. **Düzeltildi:** onay ve kapat düğmeleri kaydırılınca görünmüyordu; artık pencerenin altına sabitlendi. Erişilebilirlik ağacında pencere `modal=true`, odak pencere başlığında, onay kutusu etiketli, onay düğmesi `disabled`, İncele ve Geri al düğmeleri not adıyla anılıyor. **Düzeltildi:** pencere kapanınca açan düğme yeniden çizilmiş olsa bile odak bölüm başlığına dönüyor |
| `localStorage` | Bütün işlemlerden sonra `tur_v10` bayt bayt aynı |
| Logcat | Uygulamanın Room ve WebView satırlarında test başlıkları, içerikleri ve anahtarlar için 0 eşleşme var; yalnız kodlar ve sayılar yazılıyor |
| Ekran ayarları | `wm size`, `wm density`, `font_scale` ve döndürme eski değerlerine döndürüldü |

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL**. 138 test, 0 başarısız, 0 atlanan (P6'da 102) |

### Kalan riskler
1. **`localStorage`'a yazan bir işlem yok.** Room'daki sürüm daha yeniyse ekran bunu gösterir, ancak kullanıcı o sürümü telefondaki notlara ancak elle (notu düzenleyerek) taşıyabilir. Bu, "`localStorage` korunacak" sınırı nedeniyle bilinçli bir tercih.
2. **Tombstone çakışması ekrandan çözülemiyor.** Diriltme yasak olduğu için bu fark kalıcı olarak "İnceleme bekleyen" listesinde kalır.
3. **Geri alma sınırlı:** Not işlemden sonra değiştiyse geri alınamaz. Room'a ekleme işlemlerinde geri getirilecek önceki satır yoktur.
4. **Yedekler budanmıyor.** Tablo, kullanıcı işlemi sayısıyla sınırlı olarak büyür. Ekran son 50 işlemi gösteriyor.
5. Cihazda işlem tam transaction sırasında öldürülemedi (işlem milisaniyeler sürüyor); bu durum yalnız birim testiyle kanıtlandı. Emülatörde sonuçların ekrana ulaşması yaklaşık 3 sn sürebiliyor; bu sürede ekranda "Kontrol ediliyor…" görünüyor.
6. Önceki riskler sürüyor: Room ana kaynak değil, WebView `localStorage` kaybı, fiziksel cihaz ve eski API sürümleri denenmedi, geri dönüş yok (v4 → v3 APK'sı Room'u açamaz).

## P8 — Room ana kaynak geçişi / izole prototip (6 Kasım 2026)
Dal: `feature/room-migration-validation`, başlangıç `a4eba8e`. `main`'e dokunulmadı; push veya merge yapılmadı. Şema v4'te kaldı: yeni tablo, kolon ya da migration yok. Üretimde (release) Room-first kapalı. Üretimdeki `localStorage` yolu değişmedi.

### Aşama A — Mevcut akış ve dayanıklı yazma protokolü
**Bugünkü (üretim) akış:**
- `index.html` önce notu `localStorage`'a yazıyor (`sv()`), "kaydedildi" bildirimini de buna göre gösteriyor.
- Room'a yazma ayrı bir gölge çağrı: `upsertRoomNote`/`deleteRoomNote`. Önce günlüğe `PENDING` yazılıyor, ardından tek SQL komutu çalışıyor (otomatik commit). Sonuç, komut döndükten sonra sayfaya gönderiliyor.
- Sayfa o sırada yoksa (döndürme, yeniden yükleme, ölüm) sonuç kayboluyor.
- WebView `localStorage`'ı diske gecikmeyle yazıyor. P6'da son yazmaların ani kapanmada kaybolduğu görülmüştü.

**Room-first yazmasının başarı noktası:** Not yalnız şu sırayla "kaydedildi" sayılıyor:
1. `RoomFirstWriter.write()` içindeki `db.withTransaction` bloğu COMMIT ile döner.
2. Native yanıtı bundan sonra kurar ve sayfaya gönderir.
3. Sayfa bu yanıtı işler.

Bu noktaya kadar notun yanında "Kaydediliyor…/Siliniyor…" görünüyor. Hata, çakışma veya silinmiş durumunda "kaydedildi" hiç gösterilmiyor.

**Tekrar ve kayıp yanıt koruması (şema değişmeden):** Her istek, sayfanın düzenlediği sürümün biletini (`base`) taşır; yeni notta bu değer 0'dır. Native, sıralayıcı kilidi altında ve tek transaction içinde şöyle karar verir:

| Room'daki satır | İstek | Sonuç |
| :--- | :--- | :--- |
| Tombstone | Silme | `ALREADY_DELETED` (kaydedildi sayılır) |
| Tombstone | Yazma | `DELETED`; diriltilmez, değişiklik kaydedilmez |
| Yok | Silme | `ALREADY_DELETED` |
| Yok, `base` ≠ 0 | Yazma | `CONFLICT` |
| Bilet = `base` | Herhangi biri | `COMMITTED`, yeni bilet verilir, `modifiedAt` = gerçek değişiklik zamanı |
| Bilet ≠ `base`, içerik isteğinkiyle aynı | Yazma | `ALREADY_SAVED`; hiçbir şey yazılmaz (yanıtı kaybolmuş, zaten uygulanmış istek) |
| Bilet ≠ `base`, içerik farklı | Herhangi biri | `CONFLICT`; güncel satır sayfaya döner |

- Biletler artarak ilerlediği ve hiç tekrar kullanılmadığı için bir istek en fazla bir kez uygulanır.
- Aynı istek kimliği veya aynı içerik tekrar gönderilse de sonuç değişmez.
- Silmede satırın içeriği korunur.

**Uygulama ölürse:**
- COMMIT'ten önce ölürse hiçbir şey yazılmaz (SQLite geri alır). Sayfa isteği giden kutusunda (`room_first_outbox`) tuttuğu için açılışta aynı istek tekrar gönderilir ve normal biçimde uygulanır.
- COMMIT'ten sonra, yanıt ulaşmadan ölürse aynı istek tekrar gönderilir ve `ALREADY_SAVED` döner.

**Ölçülen dayanıklılık ayarı:** Cihazda `PRAGMA journal_mode = wal`, `PRAGMA synchronous = NORMAL` (1) okundu. Varsayılmadı; açılışta okunup ekranda ve Logcat'te gösteriliyor.
- **Bu ayarla garanti edilen:** Commit dönmüş bir transaction'ın verisi WAL dosyasına `write()` ile işlenmiştir. Uygulama çökse, öldürülse veya `force-stop` edilse de korunur.
- **Garanti edilmeyen:** NORMAL modda WAL her commit'te `fsync` edilmez; yalnız checkpoint'te edilir. Ani güç kesintisinde veya çekirdek çökmesinde son checkpoint'ten sonraki commit'ler geri alınabilir. Veritabanı tutarlı kalır, bozulmaz, ama "kaydedildi" denmiş son yazmalar kaybolabilir.
- Depolama arızası kapsam dışı. `synchronous = FULL` bir üretim kararı olarak bırakıldı; prototipte değiştirilmedi.

### Aşama B — Yalnız debug derlemesinde Room-first prototipi
- **Kapı:** `RoomFirstSwitch(available = BuildConfig.DEBUG)`. Release derlemesinde mod bayrağı açık bile olsa `isOn()` false döner. Köprü yazmaları ve geçiş komutları reddedilir; birim ve koruma testleriyle kanıtlandı.
- **Bayrak:** `room_first_prototype` SharedPreferences dosyası, `commit()` ile senkron yazılıyor. Varsayılan kapalı.
- **Açma (`ENABLE`):**
  1. Ön denetim temiz olmalı.
  2. Doğrulanmış yedek yazılır.
  3. Ancak bunlardan sonra bayrak açılır.
  Yedek, bayrak yazılamazsa da kalır ve tekrar denemede yenisi alınır. Yedekler silinmez.
- **Kapatma (`DISABLE`):**
  1. Sayfa, Room'un commit edilmiş notlarından `tur_v10`'u yeniden yazar ve 3 sn bekler.
  2. Yazdığı değeri geri okur.
  3. Native bu kopyayı Room'la denetler. Temizse bayrak kapanır; değilse mod açık kalır.
  Bu, modda `localStorage`'a not yazan tek yer.
- **Mod açıkken:**
  - Sayfa notları yalnız Room'dan okur ve `tur_v10`'a dokunmaz.
  - Oluşturma, düzenleme, favori ve silme işlemlerinin her biri, değişen not başına bir istek olarak gider.
  - Not başına aynı anda tek istek uçuşta olur. Arada yapılan düzenlemeler birleştirilir: son tam sürüm, onaylanan biletin üstüne gönderilir.
  - Native, `localStorage`'dan türeyen bütün Room yazmalarını "Room-first mode is on" hatasıyla reddeder: içe aktarma, gölge yazma/silme, uzlaştırma, P7 işlemleri ve geri alma.
- **Açılış:** Room okunur. Giden kutusundaki istekler aynı kimlik ve aynı `base` ile tekrar gönderilir. Room okunamazsa notlar gösterilmez ve yazma kabul edilmez ("Room okunamadı").
- **Kullanıcı durumları:**
  - Not kartında "Kaydediliyor…", "Siliniyor…", "Room'a kaydedildi", "Kaydedilemedi; değişiklik uygulanmadı", çakışma ve silinmiş mesajları.
  - Altta, ekran okuyucuya duyurulan bir durum çubuğu.
  - Veri ve Yedek sekmesinde debug kartı: SQLite ayarı, denetim sonucu, onay kutusu, geçiş düğmesi ve yedek listesi (SHA-256 doğrulamasıyla).

### Aşama C — Geçiş öncesi denetim
- **Geçişi engelleyen fark türleri:**
  - okunamayan yerel not,
  - Room'da eksik not,
  - içerik farkı,
  - Room'un daha yeni olduğu kayıt,
  - tombstone çakışması,
  - yalnız Room'da olan kayıt,
  - kökeni belirsiz kayıt,
  - eşleşmeyen bekleyen yazma,
  - kanıtla onarılabilir fark,
  - çözülmemiş günlük kaydı,
  - denetim sırasında değişen not.
- Bu türlerden herhangi biri sıfırdan büyükse geçiş otomatik olarak `REFUSED` olur; hiçbir şey dönüştürülmez.
- Denetim, P6 uzlaştırıcısını önizleme modunda, sıralayıcı kilidi altında ve tek transaction içinde çalıştırır.
- **Yedek:** `files/room-first-backups/rf-backup-<zaman>.json` dosyası ve yanında `.sha256`. İçeriği: `localStorage` metni bayt bayt aynen ve tombstone'lar dahil bütün Room satırları. Önce geçici dosyaya yazılır, `fsync` edilir, sonra yeniden adlandırılır. Ekranda ve `STATUS` yanıtında her yedek yeniden doğrulanır.
- **Geri yükleme planı (elle):**
  1. Uygulamayı durdurun.
  2. Yedeği `run-as` ile alın ve SHA-256 değerini yan dosyayla karşılaştırın.
  3. `localStorage` alanını `tur_v10` olarak, `room` satırlarını Room'a geri yazın.
  Okuma ve doğrulama birim testlerinde ve cihazda (bilgisayar tarafında) denendi. Tek düğmeli geri yükleme yok; cihazda geri yükleme yapılmadı.

### Otomatik testler (P8)
171 test, 0 başarısız (P7'de 138; **33 yeni**):
- `RoomFirstWriterTest` (10, Robolectric, gerçek SQLite dosyası, her adımda yeniden açılan süreç):
  - onaylanan oluşturma/düzenleme/favori/silmenin yeniden açılışta okunması;
  - COMMIT'ten önce çökmede hiçbir şey yazılmaması ve aynı isteğin tekrar uygulanabilmesi;
  - yanıtı kaybolmuş isteğin iki kez uygulanmaması;
  - eski sürümden gelen isteğin çakışma vermesi;
  - yeni ve eski tombstone'ların diriltilmemesi;
  - 400 hızlı düzenlemede bilet sırasının ve son sürümün korunması;
  - aynı sürümden gelen 20 eşzamanlı istekten yalnız birinin uygulanması;
  - mod kapalıyken reddetme;
  - bozuk isteğin içeriği tekrarlamaması;
  - dayanıklılık ayarlarının raporlanması.
- `RoomFirstSwitchTest` (10):
  - release derlemesinde modun hiç açılmaması;
  - her fark türünde reddetme (bayrak ve yedek yazılmaz);
  - okunamayan kopya;
  - tombstone, bekleyen yazma, kökeni belirsiz kayıt ve Room'un daha yeni olduğu durumda reddetme;
  - doğrulanmış yedeğin bayraktan önce yazılması;
  - değiştirilmiş veya kesik yedeğin tespiti;
  - bayrak yazılırken kesilen geçişin eski modda kalması ve tekrar denenebilmesi (eski yedek de korunur);
  - eski kopyayla dönüşün reddedilmesi, Room'dan yazılmış kopyayla dönüşün kabulü;
  - logların yalnız sayı içermesi.
- `RoomFirstScriptTest` (9, Rhino ile gerçek `room-first.js`):
  - Room okunmadan kabul yok;
  - "kaydedildi" yalnız commit yanıtından sonra;
  - 400 düzenlemenin birleştirilmesi;
  - yeniden yüklemede aynı kimlik ve `base` ile tekrar gönderim;
  - hata, çakışma, silinmiş ve köprü hatasının "kaydedildi" göstermemesi;
  - onaylanmamış oluşturmanın arkasında bekleyen silme;
  - bilinmeyen yanıt;
  - Türkçe engel etiketleri;
  - dosyada konsol veya `localStorage` kullanımı olmaması.
- `RoomMigrationGuardTest` (+3; ayrıca yükleme dinleyicisi beklentisi güncellendi):
  - modun yalnız `BuildConfig.DEBUG` ile açılması;
  - altı reddetme noktası;
  - üretimde `sv()`/`DB` dizgelerinin aynen korunması;
  - `localStorage.setItem(NKEY` yalnız `sv()` ve denetimli dönüşte;
  - gölge yazmalarda erken dönüş;
  - konsolda yalnız kod ve sayı.
- `RoomBridgeScriptsTest` (+1): Room-first geri çağırma beyaz listesi.

### Emülatör sonuçları (`emulator-5554`, API 37)
Test APK'sı geçici kopyada `versionCode` 102 / `versionName` "102.0" ile derlendi; depodaki sürüm numarası değişmedi. P8 öncesi `tur_v10` (13 Ankara notu) ve veritabanının anlık görüntüsü alındı.

| Senaryo | Sonuç (cihazda doğrulandı) |
| :--- | :--- |
| v102 kurulumu, üretim modu | `RF_MODE=false`, `tur_v10` bayt bayt aynı, durum çubuğu gizli |
| Farklar varken geçiş | Ön denetim: içerik farkı 1, tombstone çakışması 1. Düğme kapalı. Köprüden zorla `ENABLE`: `REFUSED`; yedek ve bayrak yazılmadı |
| Farkların giderilmesi | İki P5 test notu normal uygulama işlemleriyle düzeltildi: "P5 burst 3" düzenlenip kaydedildi; Room'da zaten tombstone olan "P5 burst 4" telefonda silindi. Denetim temiz (12 = 12) |
| Arayüzden geçiş | `ENABLED`. Bayrak `commit()` ile yazıldı. Yedek bilgisayarda bağımsız doğrulandı: SHA-256 yan dosyayla aynı, gömülü `localStorage` anlık görüntüyle bayt bayt aynı, 18 Room satırı (6 tombstone) |
| Oluşturma, düzenleme, favori | Her birinde önce "Kaydediliyor…", commit yanıtından sonra "Room'a kaydedildi" |
| `force-stop` + yeniden açılış | Onaylanan not (başlık, içerik, favori) aynen geri okundu. Giden kutusu boş. Mod açıkken `tur_v10` bayt bayt aynı kaldı |
| 400 hızlı düzenleme | 618 ms. Birleştirilerek 4 Room transaction'ı oldu, hepsi `COMMITTED`. Son sürüm ("rapid 399") yeniden açılışta okundu |
| Yazma yağmuru sırasında `force-stop` | Sayfanın onayladığı son sürüm "kill 78"di; native "kill 113"e kadar commit etmişti. Dosyada "kill 113" ve son loglanan bilet vardı (`integrity ok`, bekleyen günlük 0). Açılışta uçuştaki istek aynı kimlikle tekrar gönderildi ve `ALREADY_SAVED` döndü (kayıp yanıt, çift uygulama yok) |
| Yazma sırasında döndürme (Activity yeniden oluşturuldu) | Son istek commit edilmişti, yanıtı eski sayfayla kayboldu. Yeni sayfa tekrar gönderdi: `ALREADY_SAVED`, aynı bilet, not "Room'a kaydedildi" |
| Aynı istek kimliği iki kez (köprüden) | `COMMITTED`, ardından `ALREADY_SAVED`; aynı bilet |
| Eski sürümden yazma | `CONFLICT`; hiçbir şey yazılmadı |
| Eski P5 tombstone'una yeni yazma (`base` 0 ve tombstone bileti) | İkisi de `DELETED`; diriltilmedi |
| Room-first'te silme, ardından eski biletle yazma ve tekrar silme | `COMMITTED`, ardından `DELETED` ve `ALREADY_DELETED`. Satır, içeriği korunarak tombstone oldu. Yeniden açılışta silinmiş kaldı |
| P7 Veri Güvenliği ekranı | "Room ana kaynak deneme modu açık" bildirimi; karşılaştırma ve onarım düğmeleri gizli. Köprüden uzlaştırma çağrısı `reconcile refused … reason=room-first` |
| Dönüş, kopya yazıldıktan sonra kesilerek | Kopya yazıldı, `DISABLE` gönderilmeden `force-stop`. Bayrak açık kaldı, Room değişmedi, açılışta mod yine açıktı |
| Dönüşün tekrarı | `DISABLED` (denetim temiz: 12 = 12). Üretim modunda `tur_v10` Room'un 12 etkin notuyla aynı; ön denetim temiz (7 tombstone); yedek ve yedek kimliği korundu |
| Gerçek v1 dosyasından v4'e (cihazda) | Şema v1 dışa aktarımından üretilen dosya cihaza kondu. Room v1 → v2 → v3 → v4 zinciriyle açtı: `user_version` 4, v4 kimlik özeti, `integrity ok`, 3 satır, favori ve tombstone korundu, `isAI`/`modifiedAt` boş (kökeni belirsiz). Açılış uzlaştırması hiçbir şey onarmadı. Bu veriyle `ENABLE`: `REFUSED` (kökeni belirsiz 2, yalnız Room 2, eksik 12). Ardından asıl veritabanı geri kondu; denetim temiz, `tur_v10` aynı |
| Logcat | 10 log dosyasında 110 satır (42 native Room, 34 sayfa konsolu): test başlıkları, içerikleri, anahtar ve yığın izi için 0 eşleşme |
| Ekran ayarları | Döndürme (`user_rotation` 0, otomatik döndürme kapalı), `wm size` ve `wm density` başlangıç değerlerinde |

**Yalnız birim testiyle doğrulananlar:**
- COMMIT'ten hemen önce ölüm (cihazda milisaniyelik pencere zamanlanamıyor).
- Bayrak yazılırken kesilen açma işlemi.
- Değiştirilmiş veya kesik yedek.
- Release derlemesinin kapalı olması (ayrıca koruma testi var; release APK cihazda denenmedi).
- Eşzamanlı aynı sürüm istekleri.
- Okunamayan `localStorage`.

**Test edilemeyenler:** Gerçek güç kesintisi, çekirdek çökmesi ve depolama arızası. Fiziksel cihaz ve eski API sürümleri de denenmedi.

### Veri kaybı gözlemleri
1. **"Kaydedildi" onayı almış hiçbir değişiklik kaybolmadı:** `force-stop`, yazma sırasında öldürme, döndürme ve yeniden açılışlarda hep aynı sonuç alındı.
2. **Onay almamış değişiklikler kaybolabiliyor.** Öldürme veya döndürme anında sayfa belleğinde bekleyen, henüz gönderilmemiş birleştirilmiş düzenlemeler kayboldu: öldürme testinde "kill 113"ten, döndürmede "rotate 127"den sonraki düzenlemeler. Bunlar hiçbir zaman "kaydedildi" olarak gösterilmedi; çubukta "kaydediliyor" yazıyordu.
3. **Giden kutusu da WebView `localStorage`'ında duruyor.** Diske yazılmadan ölünürse uçuştaki istek tekrar gönderilmez. Bu istek ya commit edilmiştir (Room'da vardır) ya da edilmemiştir (kullanıcıya "kaydedildi" denmemiştir). Onaylanmış veri kaybı oluşmaz.
4. **Dönüşte risk:** `DISABLE` sayfanın gönderdiği kopyayı denetler. Bayrak kapandıktan sonra, WebView kopyayı diske yazmadan ölürse üretim modu eski `tur_v10` ile açılır. Room ve yedek veriyi korur. P6/P7 farkı "Room daha yeni / içerik farkı" olarak raporlar; Room ezilmez. Bu sıra cihazda üretilemedi: 3 sn beklemeden sonra kopya her denemede diskteydi.
5. Emülatörde açılıştan sonraki ilk saniyelerde yanıtlar sayfaya 3–5 sn gecikmeyle ulaşıyor (native milisaniyeler içinde commit ediyor). Bu sürede durum doğru biçimde "Kaydediliyor…/Siliniyor…" gösteriyor.

### Derleme ve test
| Komut | Sonuç |
| :--- | :--- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL**. 171 test, 0 başarısız, 0 atlanan (P7'de 138) |

### Kalan riskler
1. `synchronous = NORMAL` ile güç kesintisinde son commit'ler geri alınabilir. Üretime geçmeden önce `FULL` (yazma maliyeti karşılığında) veya kullanıcıya açık bir uyarı kararı gerekiyor.
2. Onay bekleyen düzenlemeler ve giden kutusu WebView belleğinde ve `localStorage`'ında duruyor. Native tarafta kalıcı bir istek kuyruğu yok; bilinçli olarak şema yükseltmesi yapılmadı.
3. Dönüş akışında WebView'ın `localStorage`'ı diske yazma zamanı garanti değil (gözlem 4). Veri Room'da ve yedekte kalıyor, ama farkların elle giderilmesi gerekebilir.
4. Geri yükleme elle yapılıyor ve cihazda denenmedi. Yedekler silinmediği için dosya sayısı artar.
5. Başka bir cihaz veya ikinci bir sekme yok; çakışma çözümü yalnız "reddet ve güncel hâli göster".
6. Önceki riskler sürüyor: fiziksel cihaz ve eski API'ler denenmedi; v4'ten v3'e geri dönüş yok.
