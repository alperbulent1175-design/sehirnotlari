# Room'u üretimde ana kayıt yapma: karar ve geçiş kapıları

18 Kasım 2026, `main` kaynak sürümü 1.4 ve PR #2 temel alınarak hazırlanmıştır. Bu belge üretim modunu açmaz.

## Bugünkü akış

- Üretimde `index.html` içindeki `DB` ilk olarak `tur_v10` (`localStorage`) verisini okur; `sv()` aynı anahtara yazar. Room'a gölge yazma, fark denetimi, onarım ve tombstone desteği vardır.
- `RoomFirstSwitch` yalnız debug derlemede kullanılabilir. Geçiş öncesi bütün farklar ve çözümlenmemiş günlük girdileri sıfır olmalı; yerel kopya ve bütün Room satırları SHA-256 doğrulamalı özel dosyaya yedeklenir. Daha sonra kalıcı mod bayrağı yazılır.
- Room-first yazıları sürüm bileti ve istek kimliğiyle korunur. Bekleyen istekler debug modunda native `AtomicFile` outbox'ına senkron yazılır; eski `localStorage` outbox'ı ilk açılışta taşınır. Yanıtı kaybolan istekler aynı kimlikle tekrar yürütülür. Bu kuyruk henüz fiziksel cihazda güç kesintisiyle doğrulanmadı ve üretim bayrağı kapalıdır.
- `cloudBackup` ve `cloudFetchBackup` Room-first açıkken reddedilir. Üretimde bu modu doğrudan açmak, mevcut bulut yedeği ve geri yüklemeyi devre dışı bırakır.
- Cloud biçimi v1, `tur_v10` not nesnesi ve Room silme kayıtlarını taşır. Geri yükleme yalnız ekleme önizlemesiyle çalışır. Eşitleme özelliği değildir.

## Üretim kararları

1. **Kaynak ve kayıt onayı:** Room commit'i tek başarı ölçütü olmalı. UI, yanıt gelmeden düzenlemeyi kaydedilmiş göstermemeli. Prototipte bekleyen düzenlemeler native outbox'a alınır; üretim öncesinde güç kesintisi ve eski kuyruktan geçiş cihazda doğrulanmalıdır. Room kaynaklı bulut yedeği yalnız kuyruk boşken commit edilmiş snapshot'tan oluşturulur; Room'a güvenli geri yükleme henüz kapalıdır.
2. **Yedek uyumu:** Room snapshot'ından mevcut cloud v1 biçimine kayıpsız `tur_v10` dönüşümü yazılmalı. Bulut yedeği sadece Room'un doğrulanmış commit durumundan alınmalı. Geri yükleme, onaydan sonra Room'a tek işlem veya tekrar denenebilir stage ile yazılmalı; tombstone açık onay olmadan diriltilmemeli.
3. **Dayanıklılık:** Mevcut cihaz raporunda `WAL / synchronous=NORMAL` kullanılmıştır. Son onaylanmış commit'in güç kesintisinde korunması şartsa üretimde `FULL` seçilmeli; cihazdaki gecikme ölçümüyle karar doğrulanmalı. Uygulama çökmesi ve güç kesintisi ayrı test edilmelidir.
4. **Eski sürüme dönüş:** Room ana kaynak olduktan sonra eski APK yalnız `localStorage` okur. Geri dönüşte Room snapshot'ı tam ve doğrulanmış biçimde `tur_v10` içine yazılmadan eski sürüm kurulursa son notlar görünmez. Geri dönüş işlemi ayrı, kullanıcıya görünür bir işlem olmalı.
5. **Geçiş bayrağı:** Prototipteki `available = BuildConfig.DEBUG` kapısı, yukarıdaki akışlar tamamlanmadan kaldırılmamalı. Var olan debug bayrağı üretimde `false` okunur; üretim geçişi için eski prototip bayrağına körü körüne güvenilmemeli.

## Uygulama sırası

| Aşama | Çıktı | Geçiş ölçütü |
| --- | --- | --- |
| 1. Sözleşme ve kuyruk | Room snapshot, yazma, hata ve bekleyen düzenlemeler için tek protokol | Süreç ölümünde hiçbir onaylanmamış değişiklik sessizce kaybolmaz; UI durumu açıkça gösterir. |
| 2. Bulut uyumu | Room kaynaklı yedek ve Room'a geri yükleme | JSON ve bulut yedeği mevcut notları ve silme kayıtlarını aynı biçimde korur; 5 MiB sınırı kullanıcıya açıklanır. |
| 3. Geçiş | Temiz preflight, doğrulanmış yedek, kalıcı bayrak | Eski APK → yeni APK, 0/1/çok not, tombstone, karışık kimlik ve farklı Room şemaları test edilir. |
| 4. Kurtarma | Başarısız açılış ve eski sürüme dönüş yolu | Room okunamazsa boş not defteri gösterilmez; yedek seçimi ve veri kurtarma sunulur. |
| 5. Yayın | Ön sürüm ve sınırlı dağıtım | Fiziksel cihazda güç kesintisi dışındaki süreç ölüm senaryoları, yükseltme, bulut ve geri dönüş geçer; güç kesintisi kararı belgelenir. |

## Test matrisi

- Elle ekle, AI ekle, düzenle, yıldız, favori, sil, tümünü sil ve JSON içe aktar; her adımda sayfayı yeniden yükle ve işlemi iki kez gönder.
- Yazma sırasında döndür, arka plana al, `force-stop` yap; gönderilmiş ve yalnız `pending` işlemleri ayrı ayrı kontrol et.
- Room daha yeni, `localStorage` daha yeni, yalnız Room, yalnız `localStorage` ve tombstone çakışmalarını önizle; otomatik üzerine yazma olmadığını doğrula.
- Bulut yedeğini Room snapshot'ından al; diğer cihazda geri yükle; mevcut notları, AI işaretini, metin kimliklerini ve silme kayıtlarını karşılaştır.
- v1.4 → ön sürüm → geri dönüş için not sayısı, kimlik ve içerik parmak izlerini karşılaştır. Test öncesi ve sonrası JSON yedeğini sakla.

## Bu PR'ın sınırı

PR #2, WebView, harita, hatırlatıcı, hava durumu, belgeler ve CI işlerini içerir. Room üretim geçişi için kod veya bayrak değişikliği içermez. Bu geçiş ayrı PR'da, yukarıdaki kapılar karşılandıktan sonra yapılmalıdır.
