# Şehir Notları: geliştirme ve doğrulama planı

Tarih: 18 Kasım 2026. Temel: `main` dalı, `ba2d810`. Bu belge kod incelemesidir; Android cihaz testi yapılmadı. `HANDOVER.md` eski sürüm bilgileri içerir; güncel yayın geçmişi için `DEVAM.md` esas alınmalıdır.

## Öncelik 0 — güvenlik ve veri

### P0.1 WebView gezinmesini sınırla

- **Durum:** Ana sayfa dışına üst seviye gezinme engellendi; harici HTTP(S)/e-posta bağlantıları Android'e yönlendirildi. Alt çerçeveler CSP ile kapatıldı. Leaflet 1.9.4 betiği ve stili uygulamaya alındı (BSD 2-Clause lisansı dosyasıyla). Cihaz testi bekliyor.
- **Kanıt:** `MainActivity.setupWebView()` JavaScript'i ve `Android` köprüsünü açıyor; `WebViewClient` yalnız `onPageFinished` uyguluyor. `shouldOverrideUrlLoading` ile hedef URL kontrol edilmiyor. Sayfada harici bağlantılar ve `mailto:` gezinmesi bulunuyor.
- **İş:** Uygulama içi gezinmeyi yalnız beklenen `file:///android_asset/` sayfalarıyla sınırla. `https`, `mailto` ve diğer harici şemaları uygun Android intent'iyle aç; bilinmeyen şemaları reddet. Köprüye erişebilen sayfaları ayrıca sınırla. Dosya seçimi ve JSON dışa aktarma akışlarını koru.
- **Kabul:** Harici bir URL WebView içinde `window.Android` nesnesine erişemez. Uygulama içi sayfa açılır; paylaşım, e-posta, dosya seçimi ve dışa aktarma çalışır.

### P0.2 Notların ana kayıt geçişini karara bağla

- **Kanıt:** `index.html` içindeki `DB` ve `sv()` üretimde `localStorage` kullanıyor. Room-first yalnız debug derlemede açık. `DEVAM.md` ani kapanmada son `localStorage` yazılarının kaybolduğunu belgeliyor.
- **İş:** Üretimde Room'u ana kaynak yapma tasarımını tamamla. Mevcut notlar için doğrulanmış yedek, fark önizlemesi, tek seferlik geçiş ve geri dönüş yolu tanımla. Bekleyen yazıların süreç ölümündeki davranışını belirle. Bulut yedekleme ile Room-first uyumunu çözmeden geçişi yayınlama.
- **Kabul:** Eski sürümden yükseltme, hızlı art arda kayıt, zorla kapatma, geri yükleme ve sürüm düşürme senaryolarında not kaybı veya sessiz üzerine yazma yok. Gerçek cihazda doğrulanır.

## Öncelik 1 — doğruluk

### P1.1 Hava durumu saatini API zamanına göre seç

- **Durum:** Saatlik değerler `current_weather.time` ile `hourly.time` eşleştirilerek seçiliyor; cihaz testi bekliyor.
- **Kanıt:** `fetchWx()` Open-Meteo'dan `timezone=Europe/Istanbul` ile saatlik dizi alıyor, ancak diziyi cihazın `new Date().getHours()` değeriyle okuyor.
- **İş:** Yanıttaki `hourly.time` ve `current_weather.time` alanlarını eşleştir; cihaz saat dilimine dayanma. Eksik veya gecikmiş saatlik veri için açık boş durum göster.
- **Kabul:** İstanbul, UTC ve başka saat dilimlerinde aynı an için aynı saatlik nem ve hissedilen sıcaklık gösterilir. Gece yarısı sınırı doğrulanır.

### P1.2 Hatırlatıcı sonucunu ve yaşam döngüsünü doğrula

- **Durum:** Alarm kayıtları kalıcı saklanıyor; açılış, yeniden başlatma ve paket güncellemesinde yeniden kuruluyor. Arayüz gerçek kurulum sonucunu gösteriyor, yaklaşan kayıtları listeliyor ve iptal edebiliyor. Eski sürümün cihazda hâlâ kurulu alarmları da alıcıda işleniyor; eski kayıtlar kalıcı olmadığından yeniden başlatmadan sonra geri getirilemez. Android cihaz testi bekliyor.
- **Kanıt:** `saveReminder()` native çağrıdan hemen sonra başarı mesajı gösteriyor. `scheduleReminder()` exact alarm hatasında genel alarma düşüyor ve sonuç döndürmüyor. Manifestte yeniden başlatma alıcısı yok; planlar kalıcı uygulama verisine yazılmıyor.
- **İş:** Kurulum sonucunu UI'a bildir; exact alarm uygun değilse gecikme olasılığını açıkla. Planlanan hatırlatıcıları kalıcı sakla, yeniden başlatmada geri kur, düzenleme ve iptal işlemleri ekle. Bildirim iznini ve aynı zamandaki birden fazla alarmı doğrula.
- **Kabul:** Başarı mesajı yalnız alarm gerçekten kurulduğunda görünür. Cihaz yeniden başlatıldıktan sonra gelecek hatırlatıcılar sürer; iptal edilenler bildirilmez.

## Öncelik 2 — erişim ve deneyim

### P2.1 Çevrimdışı harita tabanı

- **Durum:** Leaflet ve 81 il sınırı yerel varlık olarak paketlendi; ağ yokken arka plan döşemeleri atlanıyor. İl sınırlarına ODbL atfı eklendi. Cihazda ilk açılış testi bekliyor.
- **Kanıt:** Leaflet ve il sınırları CDN'den geliyor; döşemeler de ağdan alınıyor. Ağ yokken harita hata kartına düşüyor, notlar erişilebilir kalıyor.
- **İş:** Leaflet ve sabit il sınırlarını uygulama varlığı olarak paketle. Döşemeler yokken sınır ve not işaretlerini gösterecek hafif harita tasarla. Üçüncü taraf harita sağlayıcılarının kullanım koşullarını kontrol et.
- **Kabul:** İlk açılışta internet yokken 81 il seçilebilir ve notlara ulaşılır.

### P2.2 Belgeleri ve doğrulama akışını güncelle

- **Durum:** Güncel başlangıç `README.md` eklendi. Pull request ve `main` için Android derleme/birim testi CI akışı çalışıyor. Firestore kural emülatör testleri (25/25 yerelde geçti) ayrı CI işi olarak eklendi; ilk CI çalışması bekliyor.
- **Kanıt:** `HANDOVER.md` sürüm 1.0'ı ve tamamlanmış bulut işlerini gelecek görev olarak anlatıyor; kod sürümü 1.4. `.github` altında CI iş akışı bulunmuyor.
- **İş:** Tek güncel başlangıç belgesi yaz; eski devralma belgesini arşiv olarak işaretle. Birim testleri ve Firebase kural testleri için CI ekle; Android SDK gerektiren testleri uygun çalıştırıcıya koy.
- **Kabul:** Yeni geliştirici güncel sürümü, derleme ön koşullarını, hangi özelliklerin anahtar veya Firebase yapılandırması gerektirdiğini ve test komutlarını tek yerden bulur.

## Özellik havuzu

1. **Gezi listeleri:** Şehirleri rota veya seyahat başlığı altında grupla. Önce not kimliği ve yedek biçimiyle uyumu tasarla.
2. **Ses kaydını saklama ve oynatma:** Mevcut sesli not akışına opt-in ek; dosya boyutu, silme ve yedek kapsamını belirle.
3. **Not ekleri:** Fotoğraf veya belgeyi nota bağla; dosya kaybı, paylaşım ve bulut kotasını önceden çöz.
4. **Gelişmiş arama:** Etiket, tarih, yıldız ve şehir filtreleri; mevcut serbest metin aramasını koru.
5. **Cihazlar arası eşitleme:** Mevcut bulut yedeğinden ayrı bir özellik olarak, çakışma ve silme semantiği netleştikten sonra ele al.

## Önerilen sıra

P0.1 → P1.1 ve P1.2 → P0.2 tasarımı ve cihaz doğrulaması → P2.1 → özellik havuzu. P2.2 ilk kod değişikliğiyle birlikte başlayabilir.

## Doğrulama sınırı

Bu turda statik kod ve belge incelemesi yapıldı. Android SDK ve cihaz testi bu incelemede çalıştırılmadı. Yukarıdaki kabul ölçütleri uygulama ve cihaz testleriyle ayrıca doğrulanmalıdır.
