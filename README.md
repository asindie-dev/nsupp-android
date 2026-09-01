# nsupp Android SDK

Satıcının Android uygulamasına nsupp destek sohbetini gömer: mesajlaşma, çoklu konuşma,
LiveTranslate çevirisi ve FCM anlık bildirimi.

React Native / Expo kullanıyorsanız bu paketi doğrudan kullanmayın: `@nsupp/react-native-sdk` bunu
sarar ve JS'ten aynı yüzeyi verir.

Web widget'ı ile **aynı sunucu uçlarını** konuşur (`/widget/:publicKey/…`) — ayrı bir mobil API
yoktur, dolayısıyla "webde çalışıyor, mobilde çalışmıyor" sınıfı ayrışma olmaz.

---

## Uygulama anahtarı (alan adı kilidi açıksa ZORUNLU)

Alan adı kilidi bir **tarayıcı** kontrolüdür: web widget'ı `Origin` başlığı gönderir, yerel uygulama
göndermez. Bu yüzden yerel yüzeyin kendi kimliği vardır — **uygulama anahtarı**.

Panelde **Ayarlar → Uygulamalar** → platform + ad seçip *Uygulama ekle*. Anahtar **bir kez**
gösterilir; kopyalayıp yapılandırmaya koyun:

```kotlin
val config = NsuppConfig(
    apiBase = "https://api.nsupp.com",
    publicKey = "PUBLIC_KEY",
    appKey = "nsupp_app_...",  // Ayarlar → Uygulamalar
)
```

- Alan adı kilidi **kapalı** bir çalışma alanında `appKey` verilmeyebilir.
- Kilit **açıkken** anahtarsız istek `403 domain_locked` alır.
- Anahtar sızarsa panelden **döndürün**: eskisi anında geçersizleşir, uygulama yeni sürüm çıkana
  kadar bağlanamaz.

> **Dürüst sınır.** Uygulamanıza gömülen anahtar bir sır değildir — APK açılarak çıkarılabilir.
> Bu anahtarın verdiği şey: kodun gelişigüzel kopyalanmasını zorlaştırmak, sızıntıda **iptal**
> edebilmek ve konuşmanın hangi uygulamadan geldiğini bilmek. Konuşan **kişinin** kim olduğunu
> güvenceye almak için imzalı kimlik doğrulaması gerekir; bu anahtar onun yerine geçmez.

## Mimari: sohbet arayüzü WEB WIDGET'ININ KENDİSİ

Bu SDK yerel bir sohbet ekranı çizmez. Sohbeti bir WebView'da **aynı `widget.js`** ile gösterir —
web sitenizde çalışan widget'ın ta kendisi.

**Neden:** widget ayarı çalışma alanı başına tektir ve web/mobil/masaüstünde aynı görünmelidir
(ön-sohbet formu, Makaleler sekmesi, hamburger menüsü, powered-by, CSAT, kesinti bandı, hızlı
yanıtlar, dosya eki). Bunu yerel arayüzü ikinci ve üçüncü kez yazarak tutmak mümkün değildi:
`widget.js` ~4900 satır, yerel ekran 153'tü ve çalışma alanı ayarından yalnız renk okunuyordu —
yüzeyin **%92'si eksikti**. Her yeni widget özelliği üç yerde yazılsaydı "eksiksiz aynısı" vaadi
kod düzeyinde yalan olurdu. Bu alandaki lider ürünün iOS SDK'sı da aynı yolu izliyor: kendi web
istemcisini WebView'da açar, powered-by bağlantısı dahil her öğe web widget'ından gelir.

**Yerel tarafın işi** sohbeti çizmek değil, WebView'ın yapamadıklarını üstlenmektir: uygulama
anahtarı ve ziyaretçi jetonu, anlık bildirim kaydı, bildirimden derin bağlantı, yükleme/hata
durumu, dış bağlantıların sistem tarayıcısında açılması.

**Sunumu siz seçersiniz, SDK dayatmaz.** Sohbetin nerede duracağı üç yoldan biriyle kararlaştırılır
(aşağıdaki "Sohbeti nerede göstereceksiniz" bölümü): köşede ikon/balon, kayan panel ya da kendi
arayüzünüze gömülü. Görünümün kendisi hiçbir kipte değişmez — metinler, bölümler ve renkler tek
kaynaktan, çalışma alanı ayarından gelir.

---

## Kurulum

`settings.gradle.kts` — modülü dahil edin (yayınlanmış artefakt için aşağıdaki nota bakın):

```kotlin
include(":nsupp-sdk")
project(":nsupp-sdk").projectDir = file("../nsupp/packages/android-sdk")
```

Uygulama modülünüzde:

```kotlin
dependencies {
    implementation(project(":nsupp-sdk"))
}
```

`Application.onCreate` içinde tek satır:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Nsupp.init(this, apiBase = "https://api.nsupp.com", publicKey = "pk_…")
    }
}
```

`publicKey` = panelde **Ayarlar → Kurulum ve Entegrasyonlar** ekranındaki gömme kodunda geçen `data-public-key`.
Gizli değildir; tarayıcıda da açıkta durur.

## Sohbeti nerede göstereceksiniz — üç yol, hiçbiri dayatılmaz

Üçünde de **aynı web widget'ı** çalışır: metinler, bölümler (Makaleler, ön-sohbet, hamburger, marka
satırı) ve renkler yalnız panelden, çalışma alanı ayarından gelir. Kabuk hiçbirini geçersiz kılmaz —
web, mobil ve masaüstü birebir aynı görünür.

**① Köşede ikon (balon) + kayan panel** — web'deki launcher'ın karşılığı:

```kotlin
class App : Application() {
    val destek = NsuppChatPresenter(kip = NsuppSunumKipi.BALON)

    override fun onCreate() {
        super.onCreate()
        Nsupp.init(this, apiBase = "https://api.nsupp.com", publicKey = "pk_…")
        destek.start(this)      // `this` = Application → balon UYGULAMA GENELİNDE görünür
    }
}
```

Balon, Activity'nin içerik kökünde yaşar; o kök Activity ile birlikte gider. `start(application)`
bunu sizin yerinize halleder (`ActivityLifecycleCallbacks` ile her öne gelen ekrana yeniden koyar) —
40 Activity'nizde tek tek çağrı yazmanız gerekmez. Balonu **yalnız bazı ekranlarda** istiyorsanız bu
yolu kullanmayın, ilgili Activity'de `destek.start(this)` çağırın (`this` = Activity):

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    destek.start(this)          // yalnız BU ekranda ikon görünür
}
```

Kendi düğmenizden açmak isterseniz `kip = NsuppSunumKipi.EKRAN` verin ve ikon çizmeyin; sohbet
**tam ekran** açılır:

```kotlin
private val destek = NsuppChatPresenter()          // varsayılan: EKRAN
destekDugmesi.setOnClickListener { destek.present(this) }
```

> **Kip adı mobilde `EKRAN`, masaüstünde `panel`.** Masaüstü kabuklarında (macOS, Electron)
> balon-olmayan kip gerçekten **köşede kayan bir pencere** açar; mobilde **tam ekran** açar.
> Aynı adı iki davranışa vermek, kodun yapmadığını vaat etmektir — bu yüzden adlar ayrı.
> Telefonda "kendi düğmenizden açılan köşe penceresi" diye bir kip **yok**: 360×520 dp'lik bir
> pencere telefon ekranının neredeyse tamamıdır ve kenar boşlukları yalnız okuma alanını daraltır.
> Kayan panel yalnız `BALON` kipinde vardır (launcher paritesi: balona dokun-aç, tekrar dokun-kapat).

**② Kendi arayüzünüze gömülü** — sekme, yan panel, ayrı ekran, bölünmüş görünümün yarısı:

```xml
<com.nsupp.sdk.android.NsuppWebChatView
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
// Compose:
AndroidView(factory = { NsuppWebChatView(it) }, modifier = Modifier.fillMaxSize())
```

**③ Hazır tam ekran** — en kısa yol:

```kotlin
NsuppChatActivity.start(context)
```

**Yerel bir sohbet ekranı çizmeyin** — widget'ın görünümü ve işlevi çalışma alanı ayarından gelir;
yerel bir kopya "tek noktadan yönetim" vaadini kırar. `NsuppSession` sohbet DIŞI entegrasyon
içindir (kimlik, anlık bildirim, kendi ekranınızda göstermek istediğiniz makaleler).

### Sunumun bilinmesi gerekenleri

- **Balon şeffaf tam-ekran katman DEĞİLDİR.** Activity'nin içerik kökünde yalnız 60 dp'lik yer
  kaplar; kaplamadığı hiçbir piksel dokunma yakalamaz, yani sizin arayüzünüz tıklanabilir kalır.
- **Ölçüler web widget'ıyla eşlenmiştir**: panel 360×520 dp, dar ekranda `kullanılabilir alan − 40` /
  `kullanılabilir alan − 120` dp'ye kısılır (web'in `max-width`/`max-height` kuralı), balon 60 dp,
  kenar boşluğu 20 dp, panelin alt boşluğu 76 dp. Aynı sayılar masaüstü kabuğunda da geçerlidir.
- **Panel açıkken geri tuşu paneli kapatır**, Activity'nizi değil. Bunun için panel açılırken odağı
  alır (geri tuşu yalnız odaklı görünüm zincirine ulaşır); klavye açılmaz.
- **Sistem çubukları TEK yerden hesaplanır.** Panelin **boyutu** kullanılabilir alandan gelir
  (`WindowMetrics` − `WindowInsets`), **konumu** ise inset kadar yukarı itilir. Ölçü kaynağı olarak
  `Configuration.screenWidthDp/screenHeightDp` **kullanılmaz**: Android 15'te o alanlar sistem
  çubuklarını artık dışarıda bırakmıyor ([davranış değişikliği][a15]) ve hem oradan ölçüp hem
  inset eklemek çift sayım demekti — 48 dp'lik gezinme çubuğunda panelin üst kenarı ekranın dışına
  çıkıyordu. Kenardan kenara pencerede balon ve panel gezinme çubuğunun üstüne oturur; boşluk
  bilgisi **tüketilmez**, sizin düzeniniz de alır.

[a15]: https://developer.android.com/about/versions/15/behavior-changes-15
- **Balonun rengi uygulamanızın tema vurgu rengidir** (`android:colorAccent`), çalışma alanı rengi
  değil: balon sohbet açılmadan önce görünür, o an widget yapılandırması henüz indirilmemiştir —
  rengi ağdan beklemek balonun geç ve renk atlayarak belirmesi demekti. Kendi ikonunuzu istiyorsanız
  `EKRAN` kipini kullanıp düğmeyi siz koyun.
- **Birden çok sohbet yüzeyi açabilirsiniz; çıkış hepsini kapsar.** Panel, gömülü görünüm ve
  `NsuppChatActivity` aynı köprüyü paylaşır. `Nsupp.reset()` **canlı yüzeylerin HEPSİNİ** sıfırlar
  (her birinin kimlik script'i ayrı tazelenir) — hayalet bir yüzeyin çıkıştan sonra eski oturumu
  göstermeye devam etmesi mümkün değildir. **Komutlar** (bildirimden konuşma açma) ise tek yüzeye,
  **en son açılana** gider: kullanıcının o an baktığı yüzey odur. İkinci yüzey açıldığında logcat'e
  `NsuppWebChat` etiketiyle bilgi düşer.
- **Sohbet yüzeyi kapanınca WebView yok edilir.** `NsuppWebChatView` görünüm ağacından ayrılınca ve
  `NsuppChatActivity` kapanınca WebView `destroy()` edilir. Yok edilmeseydi, uygulama ömrü boyunca
  yaşayan köprü ölü Activity'nin görünüm ağacını canlı tutar ve widget'ın **kendi yoklaması sürerdi**
  (kapanmış ekran için pil/veri/sunucu yükü). **Bedeli:** görünümü ağaçtan çıkarıp geri koyarsanız
  sayfa **baştan yüklenir** — yazılmakta olan taslak gider. Sohbeti geçici olarak gizlemek istiyorsanız
  görünümü ağaçtan çıkarmayın, `View.GONE` yapın (`presenter.dismiss()` zaten böyle çalışır).
- **`EKRAN` kipinde `dismiss()` yoktur.** Tam ekran sohbet ayrı bir Activity'dedir ve geri tuşuyla
  kapanır; presenter'ın onu dışarıdan kapatabilmesi için Activity'ye statik referans tutması
  gerekirdi (ölü Activity'yi canlı tutan klasik sızıntı). Çağırırsanız logcat'e uyarı düşer.
- **Çıkışta `presenter.reset()`** çağırmak yeter: içinde `Nsupp.reset()` vardır (jeton + oturum +
  sayfa) ve paneli kapatır. Yalnız `Nsupp.reset()` çağırırsanız panel açık kalır.

## Anlık bildirim (FCM)

1. **Panelde**: Ayarlar → Anlık Bildirimler → *Android (FCM)* — Firebase servis hesabı JSON'undaki
   `project_id`, `client_email` ve `private_key` alanlarını girin. Özel anahtar sunucuda mühürlü
   saklanır, GET'te asla düz dönmez.
2. **Uygulamada**: `google-services.json` dosyasını normal Firebase kurulumundaki gibi ekleyin.
3. **Manifest**: jeton yenilemesini SDK'ya bağlayın —

```xml
<service
    android:name="com.nsupp.sdk.android.NsuppMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

Kendi `FirebaseMessagingService`'iniz varsa mirasa gerek yok; sadece şunu çağırın:

```kotlin
override fun onNewToken(token: String) = Nsupp.registerPushToken(token)
```

**Bildirimi SDK göstermez.** Kanal, ikon, ses ve Android 13+ `POST_NOTIFICATIONS` izin akışı
uygulamanın tasarım kararıdır; kütüphanenin bunları dayatması uygulamanızın bildirim düzenini bozar.
Yük ayrıştırma yardımcıları hazır:

```kotlin
override fun onMessageReceived(m: RemoteMessage) {
    if (!NsuppPush.isNsupp(m.data)) return
    val convId = NsuppPush.conversationId(m.data)
    // …kendi bildiriminizi gösterin; tıklanınca:
    NsuppChatActivity.start(this, convId)
}
```

---

## Sesli mesaj ve dosya eki — mikrofon izni sizde, gerisi SDK'da

Widget'ın **sesli mesaj** düğmesi mikrofona, **dosya/görsel eki** ise dosya seçicisine ihtiyaç
duyar. Tarayıcıda ikisini de tarayıcı halleder; WebView'da halleden **barındıran uygulamadır** ve
cevap vermezse varsayılan **reddetmektir**. SDK bu köprüyü kurar — sizden istenen tek şey mikrofon
iznidir.

**Dosya/görsel eki: yapmanız gereken hiçbir şey yok.** Seçici SDK'nın kendi görünmez
Activity'sinden açılır (`NsuppFileChooserActivity`, kütüphane manifest'inde bildirilir); sonucu
`onActivityResult` ile o alır, sizin Activity'nizden hiçbir şey yönlendirmeniz gerekmez.

**Sesli mesaj: mikrofon izni sizin kararınız.** Kütüphane `RECORD_AUDIO`yu **kendi manifest'ine
yazmaz** — yazsaydı kütüphane manifest'i sizinkiyle birleştiği için sesli mesajı hiç kullanmayan
uygulamalar da Play sayfasında "Mikrofon" ile görünürdü. Sesli mesaj istiyorsanız iki adım:

```xml
<!-- 1) Uygulamanızın AndroidManifest.xml'i -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

```kotlin
// 2) Çalışma-zamanı izni — kullanıcı sesli mesaj düğmesine basmadan ÖNCE elinizde olmalı.
// SDK bunu sizin yerinize İSTEYEMEZ: izin istemek Activity gerektirir ve kütüphanenin
// satıcının izin akışına (gerekçe ekranı, "bir daha sorma" durumu) karışması yanlış olur.
registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    .launch(Manifest.permission.RECORD_AUDIO)
```

İzin yoksa istek **reddedilir ve sebebi söylenir** — sessiz kalmaz. Sebep logcat'e `NsuppWebChat`
etiketiyle yazılır:

```
W/NsuppWebChat: mikrofon isteği reddedildi: uygulamanın RECORD_AUDIO izni yok —
                manifest'e ekleyip çalışma-zamanı iznini isteyin (bkz. android-sdk README)
```

"Düğmeye bastım, hiçbir şey olmadı" diye görünen her red bu satırlardan birini bırakır: yabancı
origin, mikrofon dışı kaynak, eksik çalışma-zamanı izni, açılamayan dosya seçici. Metin **teşhis
içindir**, kullanıcıya gösterilmek için değil (çevirisi yoktur, iç ayrıntı taşır).

**Açılan tek izin mikrofondur.** Kamera, konum, MIDI ve korumalı-medya istekleri **reddedilir**;
kendi sunucumuz dışındaki bir origin'den gelen istek de reddedilir. Bunun bilinen bir sonucu var:
operatörün başlattığı **görüntülü çağrı** uygulamada yanıtlanamaz (widget kamera+mikrofonu birlikte
ister, kamera reddedilince çağrı `no_media` ile kapanır). **Sesli** çağrı ve sesli mesaj çalışır.

---

## Kullanıcıyı tanıtma

```kotlin
// Kullanıcı GİRİŞ YAPTIĞI ANDA çağırın — sohbet ekranını beklemeyin.
Nsupp.identify(
    email = "ada@ornek.com",
    name = "Ada Lovelace",
    signature = imzaSunucudanGeldi,          // HMAC-SHA256(email, identity_secret)
    attributes = mapOf("plan" to "pro", "segments" to listOf("vip")),
)
```

Oturum henüz yoksa kimlik **bekletilir** ve sohbet ilk açıldığında gönderilir. Kuyruk olmasaydı
çağrı sessizce düşer, müşteri operatörde anonim görünür, VIP/segment yönlendirmesi hiç çalışmazdı.

**`signature` sunucunuzda üretilir.** Uygulamaya gömülen bir sır doğrulamayı anlamsız kılar.
`NsuppState.identityStatus` sunucunun teşhisini taşır (`valid` / `invalid` / `unsigned` /
`no_secret`) — "neden doğrulanmadı" sorusunun cevabı oradadır.

## Tüm yüzey

```kotlin
// Sunum — üç yol (yukarıdaki bölüm)
val destek = NsuppChatPresenter(kip = NsuppSunumKipi.BALON)   // varsayılan: EKRAN (tam ekran)
destek.start(application)                      // köşedeki ikon UYGULAMA GENELİNDE (yalnız BALON)
destek.start(activity)                         // …ya da yalnız bu ekranda
destek.present(activity, conversationId)       // BALON: paneli aç · EKRAN: tam ekran aç
destek.dismiss()                               // BALON: paneli gizle · EKRAN: yok (geri tuşu)
destek.toggle(activity)                        // BALON: aç/kapat
destek.reset()                                 // ÇIKIŞTA — Nsupp.reset()'i de kapsar
NsuppWebChatView(context)                      // kendi düzeninize gömülü görünüm
NsuppChatActivity.start(context)               // hazır tam ekran (EKRAN kipinin açtığı ekran)

Nsupp.init(context, apiBase, publicKey)
Nsupp.send(text) { ok -> … }                   // başarısızsa false
Nsupp.identify(email, name, signature, attributes)
Nsupp.setSessionData(mapOf("sonSiparis" to "#1042"))   // önce identify gerekir
Nsupp.setSegments(listOf("vip"))               // attributes.segments'i DEĞİŞTİRİR
Nsupp.trackEvent("Checkout")
Nsupp.runTrigger("hosgeldin")
Nsupp.rate(5, "hızlıydı")                      // state.pendingRating true iken
Nsupp.startNewConversation()                   // ayrı konu; öncekiler KAPANMAZ
Nsupp.openConversation(id)
Nsupp.registerPushToken(fcmToken)
Nsupp.reset()                                  // ÇIKIŞTA çağırın

// Yardım merkezi (self-servis — sohbeti hiç açmadan çözülen sorular)
Nsupp.loadArticles()                           // state.articles doldurulur
Nsupp.searchArticles("kargo")
Nsupp.article("iade")
```

**Çıkışta `reset()` çağırın.** Ziyaretçi jetonu kimliğe değil **cihaza** bağlıdır; çağırmazsanız
paylaşılan bir cihazda sonraki kullanıcı öncekinin sohbet geçmişini açar.
`Nsupp.reset()` **hangi iş parçacığından çağrılırsa çağrılsın güvenlidir**: oturum sıfırlaması
SDK'nın seri kanalına dizilir (uçuştaki bir isteğin ARKASINA — böylece son söz sıfırlamanın olur),
WebView sıfırlaması ise ana iş parçacığına postalanır. Çıkışı bir ağ geri-çağrımından tetikleyebilirsiniz.

**`reset()` ASENKRONDUR — "bitti" anını geri-çağrımdan okuyun.** Çağrı hemen döner; hemen ardından
okunan `Nsupp.current?.visitorToken` hâlâ ESKİ jetonu gösterebilir. Çıkışın tamamlandığını
gözlemek (ve "çıkışta jeton silindi"yi kendi tarafınızda doğrulamak) istiyorsanız:

```kotlin
Nsupp.reset {
    // ANA iş parçacığında çalışır; bu andan itibaren visitorToken null'dır.
    girisEkraninaDon()
}
```

Sohbet **yüzeyinin** yeniden yüklenmesi bundan bağımsız ve asenkrondur — jetonu silen tek yer
SDK'nın seri kanalıdır, WebView tarafı yalnız sayfayı jetonsuz baştan yükler. (İkisi de depoya
yazsaydı, çıkış sırasında açılan YENİ bir oturumun taze jetonu geç drenaj olan sayfa
sıfırlamasıyla silinebilirdi.) `presenter.reset()` kullanıyorsanız geri-çağrım için doğrudan
`Nsupp.reset { … }` çağırın; panel kapatma `presenter.dismiss()` ile ayrıca yapılır.

**CSAT:** konuşma çözülüp puanlanmadıysa `pendingRating` true olur ve hazır ekran 1–5 sorar. Kendi
arayüzünüzde yok sayarsanız mobil kanal memnuniyet ölçümünün dışında kalır.

**Ekler ve bot seçimleri** çizilir (`NsuppMessage.attachments`, `pickerChoices`). Tanımadığımız
içerik türünde balon boş bırakılmaz, "gösterilemiyor" yazar — sessiz boşluk teşhis edilemez.

**Metinler cihaz dilini izler** (tr/en) — kapsam bilinçli olarak ürünün geri kalanıyla aynı.

## Gizlilik beyanı — Play **Data safety** formu

Android'de iOS'un `PrivacyInfo.xcprivacy` dosyasının karşılığı **yoktur**: Google Play beyanı
depoda bir dosyayla değil, Play Console'daki **Data safety** formuyla alır. Yani SDK'nın
taşıyabileceği bir artefakt yok — beyanı uygulama sahibi olarak siz doldurursunuz.

SDK'nın taşıdığı veri, iOS eşleniğiyle **birebir aynıdır** (aynı sunucu uçları, aynı protokol):

| Veri | Ne zaman gider | Play formundaki yeri |
| --- | --- | --- |
| E-posta ve ad | yalnız `identify(email, name)` çağırırsanız | Personal info → Email address / Name |
| Destek mesajları ve ekleri | ziyaretçi yazdığında/dosya eklediğinde | Messages → Other in-app messages · Photos and videos |
| Ziyaretçi jetonu | her oturumda (sohbetin sürekliliği için) | App activity → Other actions |

Üçünün de amacı **App functionality**'dir. SDK reklam kimliği okumaz, veri satmaz ve üçüncü
taraf izleyicisi çalıştırmaz.

## Bilinmesi gerekenler

**Sohbet WebView'ı yalnız kendi origin'imizde çalışır ve kimlik ORIGIN'e bağlıdır.**
Uygulama anahtarı ve ziyaretçi jetonu sayfaya `androidx.webkit`in origin-kurallı yollarıyla
verilir; başka bir adrese giden bir belge (ya da gömülü bir alt çerçeve) bunları **göremez**.
Bunun iki görünür sonucu var:

- **Asgari WebView sürümü.** Cihazın WebView'ı `DOCUMENT_START_SCRIPT` ve `WEB_MESSAGE_LISTENER`
  yeteneklerini desteklemiyorsa köprü **hiç kurulmaz** (fail-closed — eski, origin'e bağlanamayan
  yolu kullanmayız). Sohbet yine açılır; ama **alan adı kilidi açık** bir çalışma alanında o
  cihazlarda istekler anahtarsız gider ve `403 domain_locked` alır. Logcat'te `NsuppWebChat`
  etiketiyle sebep yazar.
- **`apiBase` bir origin olmalıdır** (`https://api.nsupp.com`), yol taşımamalıdır.

**Dış bağlantılarda şema süzgeci var.** Sohbet/makale içeriğindeki bağlantılardan yalnız
`http`, `https`, `mailto` ve `tel` sistem tarayıcısına/uygulamasına açılır. Uzak içeriğin cihazdaki
başka bir uygulamanın derin bağlantısını tetiklemesi böylece engellenir; kendi `myapp://`
şemanızı sohbete koyarsanız **açılmaz** (logcat'e uyarı düşer).

**`reset()` WebView deposunu da temizler.** Jeton asıl olarak sayfanın `localStorage`'ında durur ve
Android'de o depo diske yazılır. `reset()` kabuk deposunu boşaltır (seri kanalda) ve sayfa yeniden
yüklenmeden **önce** çalışan bir script'le `localStorage`/`sessionStorage`'ı siler — kabuk deposu
tek doğruluk kaynağıdır, dolayısıyla süreç öldükten sonra bile bayat oturum bir sonraki açılışta
temizlenir. Çıkış script'i depoya **bakmaz**, jetonu koşulsuz boş verir: çıkış anında deponun ne
içerdiği iki kuyruğun yarışına bağlıdır, sayfanın doğru içeriği ise her hâlükârda temiz sayfadır.
Yalnız köprü kurulamayan **ve** canlı bir WebView da olmayan durumda son çare olarak
`WebStorage.deleteAllData()` çağrılır; bu çağrı **uygulama genelidir** (sizin kendi WebView'larınızın
verisi de gider).

**Çıkıştan sonra ESKİ sayfa jetonu geri yazamaz.** `reset()` yeniden yüklemeyi *başlatır*, ama yeni
belge commit olana kadar eski belge ekranda kalır ve o aralıkta uçuşta olan bir `/session` yanıtı
köprüye jetonu postalayabilir. Kabuk bir **oturum nesli** tutar: `reset()` nesli artırır ve ekrandaki
her belgenin damgasını düşürür; damga yalnız yeni bir belge **commit** olduğunda geri konur
(`WebViewClient.onPageCommitVisible` — javadoc'un deyişiyle "önceki gezinmelerden kalan içeriğin
artık çizilmeyeceğinin garanti edildiği en erken nokta"; çizilmeyen yüzeyler için `onPageFinished`
emniyet ağı). Damgasız bir belgeden gelen jeton **yok sayılır**, sebebi de: kabul edilseydi çıkan
kullanıcının oturumu geri gelirdi.

**Ziyaretçi jetonu `SharedPreferences`'ta durur, `EncryptedSharedPreferences`'ta değil.**
Jeton bir kimlik doğrulama sırrı değil, anonim oturum tanıtıcısıdır (web'de `localStorage`'ın
karşılığı). Şifreli/yedeklenen bir yere koymak, kullanıcı uygulamayı silip yeniden kurduğunda eski
sohbet geçmişinin geri gelmesi demekti — "verilerimi sildim" diyen kullanıcının beklediği bu
değildir. Uygulama kaldırılınca jeton gider: doğru davranış budur.

**Oturum jetonu HTTP başlığında gider (`x-nsupp-visitor-token`), sorgu dizesinde değil.**
Query string erişim/proxy/CDN loglarına düşer ve bu jeton oturumun tek kimliğidir — ele geçiren
konuşmayı okur ve ziyaretçi adına yazar. Bir test bunu pinliyor
(`yoklamada jeton BASLIKTA gider sorgu dizesinde DEGIL`).

**Yoklama yalnız sohbet ekranı açıkken çalışır (4 sn).** Ekran kapanınca durur. Arka planda yoklama
yapılmaz: pili yakar ve Android süreci öldürür — arka plan işi FCM'in işidir.

**Kısıtlı oturum sessiz kalmaz.** Sunucu sayfa/ülke/IP kuralı nedeniyle sohbeti kapatırsa boş ekran
değil, sebebi yazan bir bant gösterilir.

**Çeviri orijinali silmez.** `NsuppMessage.displayBody` çeviri varsa onu verir; `body` her zaman
orijinali tutar.

**Üçüncü taraf ağ/JSON bağımlılığı yok.** HTTP `HttpURLConnection`, JSON okuması elle. Sebep: bir
SDK'nın taşıdığı her bağımlılık satıcının uygulamasına dayatılır ve sürüm çakışması SDK'lardan en
sık şikâyet edilen konudur.

---

## Mimari

```
src/main/kotlin/com/nsupp/sdk/
├─ NsuppApi.kt      ← aktarım (saf Kotlin, Android API'si YOK)
├─ NsuppSession.kt  ← oturum mantığı (saf Kotlin)
├─ Json.kt          ← küçük JSON okuyucu
└─ android/         ← ince kabuk: HttpURLConnection, SharedPreferences, Compose, FCM
```

Testler Gradle ile koşar. Gerekli ortam: **JDK 17** (`JAVA_HOME`) ve Android SDK'sı
(`ANDROID_HOME` ya da `local.properties` içinde `sdk.dir` — bu dosya makineye özeldir, depoya
girmez).

**1) Birim testleri — emülatörsüz.** Çekirdek (`NsuppApi` / `NsuppSession` / `Json`) ve sunum
ölçüleri (`NsuppOlculer`) Android'e HİÇ dokunmaz:

```bash
npm run test:android-sdk        # ./gradlew testDebugUnitTest
```

50 test koşar (JSON kenar durumları ve derinlik gölgelemesi, jeton sızıntısı, tekilleme, imleç
kayması, kimlik kuyruğu, ek/bot-seçimi çözümü, CSAT, kısıtlı oturum, kalıcı/geçici hata ayrımı,
panel ölçülerinin web paritesi).

**2) WebView kabuğunun güvenlik sınırı — GERÇEK cihaz/emülatör gerekir.**

```bash
npm run test:android-sdk:device # ./gradlew connectedDebugAndroidTest
```

`NsuppWebChat` gerçek `WebView` ve gerçek `androidx.webkit` üzerinde koşar
(`src/androidTest/.../NsuppWebChatTest.kt`): iki ayrı yerel HTTP sunucusu iki AYRI origin üretir ve
köprünün yalnız **kendi origin'imizin ana çerçevesinde** göründüğü, alt çerçevenin jeton yazamadığı,
gezinme kararının önek değil **origin** karşılaştırması olduğu, şema allowlist'inin hem doğrudan
çağrıda hem **gerçek tıklamada** geçirmediği, POST ile kaçışın geri alındığı, `reset()`in
`localStorage`ı gerçekten sildiği, **oturum nesli kapısının** çıkış sonrası uçuşta kalan eski
belgenin jetonunu reddedip yeni belgeninkini kabul ettiği ve `reset()`in **tüm** canlı yüzeyleri
sıfırladığı ölçülür. Ayrıca: sayfa sıfırlaması kabuk deposuna **hiç dokunmuyor** (geç drenaj olan
bir çıkış, arada açılan yeni oturumun jetonunu ezemiyor) ve dışarı kaçış sonrası "yalnız bir
kurtarma" hakkı **yüzey başına** tutuluyor (bir yüzeyin hakkını tüketmesi diğerini ne cezalandırıyor
ne de ona ikinci hak veriyor). `NsuppResetIsParcaciginaTest` ayrıca `Nsupp.reset()`i arka plan iş
parçacığından çağırır: WebView istisna atmamalı, sıfırlama uçuştaki isteğin arkasına dizilmeli ve
tamamlanma geri-çağrımı ancak jeton silindikten SONRA, ana iş parçacığında çalışmalıdır.

> **Niçin emülatör:** bu testler bir zamanlar elle yazılmış Android saplamalarına karşı koşuyordu.
> Gerçek Gradle derlemesi açılınca hepsi "Unresolved reference" ile düştü — yani Android'i değil
> taklidi test ediyorlardı ve hiçbir şey kanıtlamamışlardı. Kabuğun kanıtlanacak tek şeyi köprünün
> hangi origin'e açıldığıdır ve bu yalnız gerçek WebView'da gözlenebilir.
>
> **Kanıtlanmayan tek dal (dürüst sınır):** WebView sürümü `DOCUMENT_START_SCRIPT` /
> `WEB_MESSAGE_LISTENER` desteklemediğinde köprünün HİÇ kurulmaması (fail-closed). `WebViewFeature`
> cihazın WebView sürümünden okur ve testten zorlanamaz; güncel WebView'lı emülatörde bu dal
> koşmaz.

## Yayın durumu

Modül kaynak olarak dahil edilir; Maven Central artefaktı (`com.nsupp:nsupp-sdk`) henüz
yayınlanmadı — yayın anahtarları gerekiyor. Kaynak dahil etme sözleşmeyi değiştirmez, yalnız
bağımlılık satırı değişecektir.
