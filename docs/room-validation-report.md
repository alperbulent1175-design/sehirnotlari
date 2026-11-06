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
