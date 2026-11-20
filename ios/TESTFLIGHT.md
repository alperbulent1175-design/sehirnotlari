# Şehir Notları iOS TestFlight kurulumu

## Gerekenler

- macOS bilgisayar, tam Xcode 26 veya üstü ve iOS 26 SDK ya da daha yenisi. Uygulamanın cihaz hedefi iOS 16.0 ve üstüdür. [Apple’ın güncel yükleme koşulları](https://developer.apple.com/news/upcoming-requirements/)
- Aktif Apple Developer Program üyeliği olan ekip ve bu ekibe bağlı, App Store Connect’e yapı yükleme yetkisi olan Apple hesabı. Hesapla Xcode **Settings → Accounts** bölümünde oturum açın. Parola, API anahtarı, sertifika ve profilleri depoya eklemeyin.
- Ekipte `com.sehirnotlari.ios` Bundle ID’sini kaydedin ve App Store Connect **Apps** bölümünde bu kimliğe bağlı iOS uygulama kaydını oluşturun. Kayıt zaten varsa onu kullanın. [App Store Connect iş akışı](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-workflow/)

## İmzalı arşiv ve yükleme

1. Kaynak deponun tamamını Mac’e alın. iOS projesi `app/src/main/assets` içindeki ortak harita ve arayüz dosyalarını kullanır. XcodeGen’i kurun (`brew install xcodegen`) ve depo kökünde çalıştırın.

   ```sh
   cd ios
   xcodegen generate
   open SehirNotlari.xcodeproj
   ```

2. Daha önce yükleme yapıldıysa, `ios/project.yml` içindeki `CURRENT_PROJECT_VERSION` değerini App Store Connect’teki ilgili sürümün son yapı numarasının üstüne çıkarıp projeyi tekrar üretin. `MARKETING_VERSION` kullanıcıya gösterilen sürümdür.
3. Xcode’da `SehirNotlari` hedefinde **Signing & Capabilities** bölümünü açın. **Automatically manage signing** seçeneğini etkinleştirin, Apple Developer ekibini seçin ve Bundle ID’nin uygulama kaydıyla eşleştiğini doğrulayın.
4. `SehirNotlari` şemasını ve **Any iOS Device (arm64)** hedefini seçin. **Product → Archive** ile arşiv alın. Organizer’da **Distribute App → App Store Connect** yolunu izleyerek doğrulamayı ve yüklemeyi tamamlayın. [Apple’ın dağıtım adımları](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases)
5. Apple yapıyı işledikten sonra App Store Connect **TestFlight** sekmesinde varsa ihracat uyumluluğu sorularını yanıtlayın ve yapıyı iç test grubuna ekleyin. Testçiler daveti iPhone’daki TestFlight uygulamasından açabilir. [İç testçi ekleme](https://developer.apple.com/help/app-store-connect/test-a-beta-version/add-internal-testers)

## İlk cihaz doğrulaması

GitHub Actions simülatör paketi imzasızdır. iPhone kurulumu için yukarıdaki Apple imzası ve TestFlight dağıtımı gerekir. İlk TestFlight yapısında harita açılışını, şehir seçimini, not kaydının uygulama yeniden açıldığında korunmasını, Dosyalar’dan Android JSON yedeği içe aktarmayı ve paylaşım menüsünden JSON dışa aktarmayı kontrol edin. iOS prototipinde Gemini ve Android’e özgü bulut yedekleme akışları kapalıdır.
