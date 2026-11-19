# Cihazlar arası eşitleme tasarımı

Bu belge eşitleme için veri sözleşmesi ve doğrulama planıdır. Mevcut Firebase özelliği anlık eşitleme değil, kullanıcı onaylı yedekleme ve geri yüklemedir. Room üretimde ana kayıt olana ve gerçek cihaz doğrulaması bitene kadar eşitleme açılmaz.

## Kaynak ve sahiplik

- Room cihazdaki tek yazılabilir not kaynağıdır. Arayüz yalnız Room commit'inden sonra “kaydedildi” gösterir.
- Google oturumu isteğe bağlıdır. Oturum açmak mevcut yerel notları kendiliğinden hesaba yüklemez; kullanıcı önce hangi notların bağlanacağını önizler ve onaylar.
- Oturum kapatmak buluttaki notları silmez. Cihazda kalan notlar için ayrı, açık bir seçim sunulur.
- Firestore yolları kullanıcı kimliğine bağlıdır; kurallar başka hesabın okuma/yazmasını reddeder. API anahtarı ve ses/fotoğraf/PDF ekleri bu not eşitlemesinin kapsamı dışındadır.

## Not kaydı

Uzak kayıt, kararlı not kimliği, şehir, içerik alanları, silme zamanı, sunucu sürümü ve son yazan cihaz kimliğini taşır. Cihaz kimliği yalnız çakışma ayırımı içindir; kullanıcıya görünen not kimliği değişmez. Başlık, içerik, yıldız, renk, kategori, tarih ve `isAI` alanları tam olarak korunur.

Yerel Room satırında uzak sürüm, son indirilen içerik özeti ve gönderim durumu tutulur. Yerel düzenleme, son indirilen sürüme dayanır. Sunucuya yazma bu sürüm eşleşiyorsa kabul edilir; farklıysa yerel içerik ezilmez ve çakışma olarak saklanır. Sunucu zamanı sıralama içindir, tek başına kazananı belirlemez.

## Silme ve çakışma

- Silme, tombstone olarak eşitlenir. Başka cihazdaki eski düzenleme tombstone'u otomatik diriltmez.
- Aynı not iki cihazda çevrimdışı değişirse iki sürüm de korunur. Kullanıcı “bu sürümü tut”, “diğerini tut” veya “iki kopya olarak sakla” seçer; karar yeni sürüm olarak gönderilir.
- Tombstone temizliği sabit kısa süreyle yapılmaz. Önce tüm bağlı cihazların veya uzun süre çevrimdışı cihazların durumu için güvenli bir saklama politikası ve kurtarma yolu doğrulanır.
- Yeniden denenen yazılar aynı işlem kimliğiyle gönderilir. Bağlantı kesilmesi veya süreç ölümü aynı notu çoğaltmamalıdır.

## Çalışma sırası

1. Room üretim geçişi, native kuyruk, yedek ve geri yükleme gerçek cihazda doğrulanır.
2. Şema ve açık migration ile uzak sürüm ve çakışma kayıtları eklenir; eski APK verisi silinmez.
3. Firestore kullanıcı yolu ve kuralları emülatörde ayrı hesaplarla test edilir.
4. İlk hesap bağlama yalnız önizleme ve onayla yapılır. İlk indirme mevcut yerel notlara sessizce yazmaz.
5. Çevrimdışı yazılar WorkManager ile tekrar denenir; uygulama açılışı ve bağlantı dönüşü aynı kuyruğu kullanır.
6. İki gerçek cihazda düzenleme, silme, oturum değişimi ve uzun çevrimdışı kalma test edilir.

## Kabul matrisi

| Senaryo | Beklenen sonuç |
| --- | --- |
| Aynı not iki cihazda çevrimdışı düzenlenir | İki içerik korunur; çakışma kullanıcıya görünür. |
| Bir cihaz siler, diğeri eski metni gönderir | Silme kaydı korunur; otomatik diriltme yoktur. |
| Gönderimden sonra yanıt kaybolur | Aynı işlem tekrarlandığında tek uzak sürüm oluşur. |
| Kullanıcı hesap değiştirir | Önceki hesabın notları yeni hesaba otomatik yüklenmez. |
| Kullanıcı oturum açmadan kullanır | Yerel notlar ve yedek dışı özellikler çalışır. |
| Başka hesap uzak not yolunu okur | Firestore kuralları isteği reddeder. |

Eşitleme, yedekleme düğmesi veya geçmiş yedek listesi olarak sunulmaz; ayrı açma/kapama ve durum ekranı gerektirir.
