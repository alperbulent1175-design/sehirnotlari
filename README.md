# Türkiye Şehir Notları

Android uygulaması: Kotlin WebView kabuğu, yerel HTML/JavaScript arayüzü, şehir haritası ve notlar. Güncel kaynak sürümü `1.4` (`versionCode 5`); yayın geçmişi ve açık doğrulama işleri için [DEVAM.md](DEVAM.md), öncelikli geliştirme işleri için [geliştirme planı](docs/gelistirme-plani-2026-11-18.md) okunmalıdır. `HANDOVER.md` eski bir devir belgesidir.

## Derleme

- JDK 21, Android SDK 37 ve Gradle wrapper gerekir.
- `bash ./gradlew :app:assembleDebug :app:testDebugUnitTest`
- Firebase yapılandırması yokken bulut yedeği devre dışı kalır. `app/google-services.json` yerel dosyadır ve depoya eklenmez.
- Yapay zekâ özellikleri için Android uygulamasında Ayarlar → Akıllı özellikler bölümüne kendi Gemini API anahtarınızı girin. Anahtar Android Keystore ile cihazda şifrelenir, yedeklere dahil edilmez ve uygulama içinden okunamaz; kaldırılabilir. PDF, ses, fotoğraf ve şehir rehberi istekleri Google Gemini hizmetine gönderilir. Kota ve ücretler anahtar sahibine aittir. Anahtar olmadan özellikler kapalıdır.
- Geliştirme derlemesinde `local.properties` içindeki `GEMINI_API_KEY` geçici olarak kullanılabilir. Yayın derlemesine bu anahtar gömülmez. Ortak kullanıma açık, uygulama tarafından finanse edilen AI hizmeti için sunucu tarafı proxy ve kota denetimi gerekir.
- PDF özetleri Gemini 3.8 Flash, sesli not, fotoğraf ve ses analizi ile şehir rehberi Gemini 2.5 Flash kullanır. PDF modelinin gerçek anahtarla cihazda uçtan uca doğrulanması [AI doğrulama işinde](https://github.com/alperbulent1175-design/sehirnotlari/issues/7) izlenir.
- Firestore kural testleri: `cd firebase && npm ci && npm test` (Firebase emülatörleri gerekir).

## Veri

Üretimde notların ana kaydı WebView `localStorage` içindedir; Room kopyası veri tutarlılığı için kullanılır. Room-first yalnız debug prototipidir. JSON dışa aktarma ve isteğe bağlı Firebase bulut yedeği vardır. Telefon değiştirmeden önce JSON yedeği alın.

Room'u üretimde ana kayıt yapma kararı ve geçiş kapıları için [Room üretim hazırlığı](docs/room-production-readiness.md) belgesine bakın.

Leaflet BSD 2-Clause lisansıyla, il sınırları OpenStreetMap ODbL atfıyla uygulamaya paketlenmiştir. Ayrıntılar `app/src/main/assets/vendor/leaflet/LICENSE` ve `app/src/main/assets/data/ATTRIBUTION.md` dosyalarındadır.
