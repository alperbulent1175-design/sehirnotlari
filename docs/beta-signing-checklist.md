# v1.5-beta.2 paketleme kontrolü

Kurulu v1.5-beta.1 üzerine güncelleme için `versionCode 7` APK, aynı uygulama kimliği ve aynı imza sertifikası gerekir. Depodaki CI yayın türünü derler fakat imzalı APK üretmez.

1. Önceki betayı paketleyen bilgisayarda `release/v1.5-beta.2` dalını güncelleyin ve Android Studio'da açın.
2. **Build → Generate Signed Bundle / APK → APK** ile önceki betada kullanılan aynı keystore ve anahtar takma adını seçin. Parola veya keystore dosyasını GitHub'a, sohbete ya da bu depoya koymayın.
3. Release varyantından üretilen APK'nın paket kimliği `com.sehirnotlari`, sürüm kodu `7`, sürüm adı `1.5-beta.2` olmalıdır.
4. `apksigner verify --print-certs` ile imza sertifikasının SHA-256 değerini önceki betayla karşılaştırın:

   `ED:D1:59:FC:1D:C6:11:5E:A0:07:9F:80:E2:C9:D4:3A:11:E1:90:6A:16:3A:BC:FA:2C:D6:CB:3C:F6:26:EF:AC`

5. GitHub Release yayınından önce cihazda beta.1 üzerine kurulum ve notların korunması; Gemini, PDF, hatırlatıcı, yedek ve geri yükleme akışlarını deneyin. Kurulum Android tarafından reddedilirse imza veya sürüm kodu uyuşmazlığını çözmeden APK yayımlamayın.

Bu sertifika önceki yayımlanmış APK'dan okunmuştur. İmza anahtarı bu çalışma alanında yoktur; yukarıdaki değer imzalama için yeterli değildir.
