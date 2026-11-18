# Türkiye Şehir Notları

Android uygulaması: Kotlin WebView kabuğu, yerel HTML/JavaScript arayüzü, şehir haritası ve notlar. Güncel kaynak sürümü `1.4` (`versionCode 5`); yayın geçmişi ve açık doğrulama işleri için [DEVAM.md](DEVAM.md), öncelikli geliştirme işleri için [geliştirme planı](docs/gelistirme-plani-2026-11-18.md) okunmalıdır. `HANDOVER.md` eski bir devir belgesidir.

## Derleme

- JDK 21, Android SDK 37 ve Gradle wrapper gerekir.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest`
- Firebase yapılandırması yokken bulut yedeği devre dışı kalır. `app/google-services.json` yerel dosyadır ve depoya eklenmez.
- Yapay zekâ özellikleri için `local.properties` içinde `GEMINI_API_KEY` gerekir. Anahtar bulunmazsa özellikler devre dışı kalır. Yayın APK'sına gömülü anahtarın çıkarılabileceği unutulmamalıdır; üretimde anahtar sunucu tarafında tutulmalıdır.
- Firestore kural testleri: `cd firebase && npm ci && npm test` (Firebase emülatörleri gerekir).

## Veri

Üretimde notların ana kaydı WebView `localStorage` içindedir; Room kopyası veri tutarlılığı için kullanılır. Room-first yalnız debug prototipidir. JSON dışa aktarma ve isteğe bağlı Firebase bulut yedeği vardır. Telefon değiştirmeden önce JSON yedeği alın.

Leaflet BSD 2-Clause lisansıyla, il sınırları OpenStreetMap ODbL atfıyla uygulamaya paketlenmiştir. Ayrıntılar `app/src/main/assets/vendor/leaflet/LICENSE` ve `app/src/main/assets/data/ATTRIBUTION.md` dosyalarındadır.
