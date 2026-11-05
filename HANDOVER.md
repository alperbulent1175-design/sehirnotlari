# PROJE DEVİR / EL DEĞİŞTİRME BELGESİ (PROJECT HANDOVER)
> **Hedef:** Bu belge, projenin Cursor IDE, Grok Bot veya başka bir yapay zeka asistanına/geliştiriciye eksiksiz devredilmesi amacıyla hazırlanmıştır.

---

## 1. Proje Özeti ve Kimliği
* **Uygulama Adı:** Türkiye Şehir Notları (City Notes Turkey)
* **Paket Adı (Application ID):** `com.sehirnotlari`
* **Platform:** Android (Min SDK: 24, Target SDK: 37, Compile SDK: 37)
* **Mimari:** Hybrid Android (Kotlin Native + Fullscreen WebView + Leaflet.js + Google Generative AI Gemini 2.5)
* **Mevcut Sürüm:** Version Code: `1`, Version Name: `"1.0"`
* **Hazır APK Dosyası:** `app/build/outputs/apk/debug/app-debug.apk` (~15.2 MB)
* **Yedek Arşivi:** `c:\Users\crawi\AndroidStudioProjects\SehirNotlari_Backup.zip` (~675 KB)

---

## 2. Teknoloji Yığını (Tech Stack)
* **Native Android:**
  * Dil: Kotlin `2.2.10`
  * Android Gradle Plugin (AGP): `9.2.1`
  * UI: `WebView` (Donanım hızlandırmalı, JavaScriptInterface köprüsü)
  * Jetpack / KTX: `core-ktx:1.19.0`, `lifecycle-runtime-ktx:2.11.0`, `appcompat:1.7.0`
  * Asenkron Yapı: Kotlin Coroutines (`kotlinx-coroutines-android:1.7.3`)
  * Yerel Depolama: Android `SharedPreferences` (Şehir rehberleri önbelleği)
  * Bildirim ve Hatırlatıcı: `AlarmManager` + `BroadcastReceiver` (`ReminderReceiver`)
  * Dosya Paylaşımı: AndroidX `FileProvider` (`${applicationId}.fileprovider`)
* **Yapay Zeka (AI):**
  * SDK: `com.google.ai.client.generativeai:generativeai:0.9.0` (Google AI Studio SDK)
  * Aktif Model: `gemini-2.5-flash` (Yüksek erişilebilirlik ve hız için 3.5'ten 2.5'e sabitlendi)
  * API Anahtarı: `local.properties` dosyasından okunarak derleme anında `BuildConfig.GEMINI_API_KEY` olarak enjekte edilir.
* **Frontend (WebView):**
  * Dosya: `app/src/main/assets/index.html` (Tek sayfa SPA mimarisi, CSS3, Vanilla JS)
  * Harita Motoru: Leaflet.js `1.9.4` + Türkiye GeoJSON sınırları ve koordinatları
  * Veri Saklama: Tarayıcı `localStorage` (Kullanıcı notları, puanları, kategorileri) + JSON İçe/Dışa Aktarma

---

## 3. Dizin ve Dosya Haritası (File Structure)

```plaintext
SehirNotlari/
├── local.properties                     # GEMINI_API_KEY ve Android SDK yolu (HASSAS)
├── build.gradle.kts                     # Proje düzeyinde Gradle yapılandırması
├── gradle/
│   └── libs.versions.toml               # Sürüm kataloğu (BOM, kütüphane versiyonları)
├── app/
│   ├── build.gradle.kts                 # Uygulama bağımlılıkları, SDK ve BuildConfig tanımları
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # İzinler, Provider, Activity ve Receiver tanımları
│   │   ├── assets/
│   │   │   ├── index.html               # Tüm arayüz, Leaflet haritası, not defteri ve JS mantığı
│   │   │   └── manifest.json            # Web uygulama manifesti
│   │   ├── java/com/sehirnotlari/
│   │   │   ├── MainActivity.kt          # Ana Activity, WebView yapılandırması, WebAppInterface (Köprü), AI entegrasyonu
│   │   │   └── ReminderReceiver.kt      # Alarm ve zamanlanmış bildirim alıcısı
│   │   └── res/
│   │       ├── xml/file_paths.xml       # FileProvider izinli dosya yolları
│   │       └── values/strings.xml       # Uygulama başlığı ve kaynaklar
│   └── build/outputs/apk/debug/
│       └── app-debug.apk                # Fiziksel cihaza yüklenebilir güncel APK
```

---

## 4. Native <-> JavaScript İletişim Köprüsü (`WebAppInterface`)

WebView içerisindeki JavaScript, `window.Android` nesnesi üzerinden native Kotlin fonksiyonlarını çağırır:

| JS Fonksiyonu | Parametreler | Görev ve Davranış |
| :--- | :--- | :--- |
| `window.Android.getCityGuide(city)` | `city: String` | Şehir rehberi üretir. **Önce `city_guides_cache` SharedPreferences'ı kontrol eder.** Varsa anında JS `onCityGuideReady(content)` çağırır. Yoksa Gemini 2.5 API'sine gider, cevabı önbelleğe yazar ve UI'a iletir. |
| `window.Android.startRecording()` | - | Mikrofon iznini kontrol eder ve ses kaydını (`MediaRecorder`) başlatır. |
| `window.Android.stopRecording(city)` | `city: String` | Ses kaydını durdurur, ses dosyasını Gemini modeline gönderip transkript/özet alır, JS `onVoiceNoteReady(text, city)` çağırır. |
| `window.Android.startMultiModalRecording()`| - | Kamera ve ses izinlerini kontrol eder, fotoğraf çeker ve ses kaydını başlatır. |
| `window.Android.stopMultiModalRecording(city)` | `city: String` | Fotoğraf + ses ikilisini Gemini Multimodal'a gönderip not üretir. |
| `window.Android.pickPDF(city)` | `city: String` | Sistem dosya yöneticisinden PDF seçtirir, Gemini ile çözümleyip not olarak ekler. |
| `window.Android.shareNote(text)` | `text: String` | Android sistem paylaşım menüsünü (`Intent.ACTION_SEND`) tetikler. |
| `window.Android.scheduleReminder(city, msg, time)` | `city, message, timestamp` | `AlarmManager` ile exact alarm kurar; zamanı gelince bildirim fırlatılır. |
| `window.Android.backupToCloud(json)` | `json: String` | Firebase veritabanına JSON yedeği atar. |
| `window.Android.restoreFromCloud()` | - | Firebase'den veriyi çeker ve `importFromCloud(json)` JS fonksiyonunu çalıştırır. |

---

## 5. Kritik Geliştirme Notları & Çözülmüş Sorunlar

1. **Gemini Modeli (503 & 404 Hataları):**
   * `gemini-1.5-flash` kaldırıldığı için 404 veriyordu.
   * `gemini-3.5-flash` önizleme sürümü olduğu için yoğun saatlerde `503 Service Unavailable` hatası veriyordu.
   * **Çözüm:** Model `gemini-2.5-flash` olarak sabitlendi. Kararlı, hızlı ve kotayı verimli kullanır.
2. **Günlük API Kota Koruması (Caching):**
   * Her şehir tıklandığında tekrar tekrar API çağrısı yapıp kotayı tüketmemek için `MainActivity.kt` içinde Android `SharedPreferences` ("`city_guides_cache`") önbelleği eklendi. Bir şehir için rehber bir kez alındıktan sonra sonraki açılışlarda sıfır gecikmeyle telefondan yüklenir.
3. **Derleme Ortamı (Environment - PowerShell/Windows):**
   * Global `JAVA_HOME` tanımlı değildir. Derleme yaparken Android Studio'nun gömülü JetBrains Runtime (JBR) yolu kullanılır:
     ```powershell
     $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
     .\gradlew.bat compileDebugKotlin
     .\gradlew.bat installDebug
     ```

---

## 6. Sırada Bekleyen / Planlanan Görevler (Roadmap)

1. **Uygulama İçi Otomatik Güncelleme (In-App GitHub Updater):**
   * `AndroidManifest.xml` içerisine `REQUEST_INSTALL_PACKAGES` izni eklenmeli.
   * `MainActivity.kt` içerisine GitHub Releases API (`https://api.github.com/repos/{owner}/{repo}/releases/latest`) üzerinden yeni sürüm kontrolü ve APK indirme motoru yazılmalı.
   * `index.html` arayüzüne "Yeni Güncelleme Mevcut (v1.1)" penceresi ve indirme çubuğu eklenmeli.
2. **Çoklu Cihaz Senkronizasyonu (Cloud Sync):**
   * Telefon ve tablet arasında notların ortak görünmesi için Google Sign-In veya Supabase/Firebase Firestore entegrasyonu.
3. **Sesli Notları Saklama (Voice Player):**
   * Sesli not alındıktan sonra ses dosyasını silmek yerine yerel hafızada saklayıp notun altında dinleme butonu sunulması.

---

## 7. Cursor / Grok İçin Hazır Başlangıç Promptu (Kopyala-Yapıştır)

> **Aşağıdaki metni Cursor veya Grok Bot'a ilk mesaj olarak yapıştırabilirsiniz:**
> 
> ```markdown
> Merhaba! "Türkiye Şehir Notları" adlı Android hibrit projesi üzerinde çalışıyoruz.
> Proje mimarisi:
> - Native Katman: Kotlin, Android SDK 37, WebView (Hardware Accelerated), Google Generative AI SDK (gemini-2.5-flash), AlarmManager.
> - Frontend Katmanı: app/src/main/assets/index.html (Leaflet.js haritası, localStorage veri tabanı, modern CSS SPA).
> - Köprü: MainActivity.kt içerisindeki WebAppInterface (@JavascriptInterface).
> 
> Projenin tüm detayları, mimarisi ve çözülmüş geçmiş hataları projenin kök dizinindeki HANDOVER.md dosyasında belgelenmiştir. 
> 
> Sıradaki görevimiz: Uygulamaya GitHub Releases üzerinden çalışan "In-App Auto Updater" (Uygulama İçi Otomatik Güncelleme) sistemini entegre etmek. Lütfen HANDOVER.md dosyasını incele ve kaldığımız yerden devam edelim.
> ```
