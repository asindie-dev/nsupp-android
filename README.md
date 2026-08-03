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
private val destek = NsuppChatPresenter(kip = NsuppSunumKipi.BALON)

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    destek.start(this)          // köşedeki ikon görünür; dokununca panel açılır/kapanır
}
```

Kendi düğmenizden açmak isterseniz `kip = NsuppSunumKipi.PANEL` verin ve ikon çizmeyin:

```kotlin
private val destek = NsuppChatPresenter()          // varsayılan: PANEL
destekDugmesi.setOnClickListener { destek.present(this) }
```

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
- **Ölçüler web widget'ıyla eşlenmiştir**: panel 360×520 dp, dar ekranda `ekran − 40` /
  `ekran − 120` dp'ye kısılır (web'in `max-width`/`max-height` kuralı), balon 60 dp, kenar boşluğu
  20 dp, balon varken panelin alt boşluğu 76 dp. Aynı sayılar masaüstü kabuğunda da geçerlidir.
- **Panel açıkken geri tuşu paneli kapatır**, Activity'nizi değil. Bunun için panel açılırken odağı
  alır (geri tuşu yalnız odaklı görünüm zincirine ulaşır); klavye açılmaz.
- **Sistem çubukları hesaba katılır.** Kenardan kenara (Android 15 / targetSdk 35) pencerede balon
  ve panel gezinme çubuğunun üstüne oturur; boşluk bilgisi **tüketilmez**, sizin düzeniniz de alır.
- **Balonun rengi uygulamanızın tema vurgu rengidir** (`android:colorAccent`), çalışma alanı rengi
  değil: balon sohbet açılmadan önce görünür, o an widget yapılandırması henüz indirilmemiştir —
  rengi ağdan beklemek balonun geç ve renk atlayarak belirmesi demekti. Kendi ikonunuzu istiyorsanız
  `PANEL` kipini kullanıp düğmeyi siz koyun.
- **Aynı anda tek sohbet yüzeyi tutun.** Panel, gömülü görünüm ve `NsuppChatActivity` aynı köprüyü
  paylaşır; ikisi birden canlıyken komutlar (bildirimden konuşma açma, çıkışta sıfırlama) yalnız en
  son açılan yüzeye gider. İkinci yüzey açıldığında logcat'e `NsuppWebChat` etiketiyle uyarı düşer.
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
val destek = NsuppChatPresenter(kip = NsuppSunumKipi.BALON)
destek.start(activity)                         // köşedeki ikon (yalnız BALON kipinde)
destek.present(activity, conversationId)       // paneli aç (konuşma id'si isteğe bağlı)
destek.dismiss(); destek.toggle(activity)
destek.reset()                                 // ÇIKIŞTA — Nsupp.reset()'i de kapsar
NsuppWebChatView(context)                      // kendi düzeninize gömülü görünüm
NsuppChatActivity.start(context)               // hazır tam ekran

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

**CSAT:** konuşma çözülüp puanlanmadıysa `pendingRating` true olur ve hazır ekran 1–5 sorar. Kendi
arayüzünüzde yok sayarsanız mobil kanal memnuniyet ölçümünün dışında kalır.

**Ekler ve bot seçimleri** çizilir (`NsuppMessage.attachments`, `pickerChoices`). Tanımadığımız
içerik türünde balon boş bırakılmaz, "gösterilemiyor" yazar — sessiz boşluk teşhis edilemez.

**Metinler cihaz dilini izler** (tr/en) — kapsam bilinçli olarak ürünün geri kalanıyla aynı.

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
Android'de o depo diske yazılır. `reset()` kabuk deposunu boşaltır ve sayfa yeniden yüklenmeden
**önce** çalışan bir script'le `localStorage`/`sessionStorage`'ı siler — kabuk deposu tek doğruluk
kaynağıdır, dolayısıyla süreç öldükten sonra bile bayat oturum bir sonraki açılışta temizlenir.
Yalnız köprü kurulamayan **ve** canlı bir WebView da olmayan durumda son çare olarak
`WebStorage.deleteAllData()` çağrılır; bu çağrı **uygulama genelidir** (sizin kendi WebView'larınızın
verisi de gider).

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

Çekirdek Android'e dokunmadığı için **emülatörsüz test edilir**:

```bash
npm run test:android-sdk
```

56 test koşar (JSON kenar durumları ve derinlik gölgelemesi, jeton sızıntısı, tekilleme, imleç
kayması, kimlik kuyruğu, ek/bot-seçimi çözümü, CSAT, kısıtlı oturum, kalıcı/geçici hata ayrımı). Bu betik `kotlinc` kullanır — Gradle/Android SDK gerektirmez.

**WebView kabuğunun güvenlik sınırı da burada koşar.** `NsuppWebChat` gerçek `createWebView`
kablolamasıyla test edilir (köprünün bağlandığı origin, gezinme kararları, dış bağlantı şeması,
çıkışta depo temizliği); Android API'leri `tools/Shim*.kt` altındaki — ürüne/AAR'a **girmeyen** —
saplamalarla temsil edilir. Bu, derlemenin söyleyemediğini söyler; **imza uyumunun** kanıtı yine
Gradle derlemesidir (`build.gradle.kts`).

## Yayın durumu

Modül kaynak olarak dahil edilir; Maven Central artefaktı (`com.nsupp:nsupp-sdk`) henüz
yayınlanmadı — yayın anahtarları gerekiyor. Kaynak dahil etme sözleşmeyi değiştirmez, yalnız
bağımlılık satırı değişecektir.
