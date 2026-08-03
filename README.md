# nsupp Android SDK

Satıcının Android uygulamasına nsupp destek sohbetini gömer: mesajlaşma, çoklu konuşma,
LiveTranslate çevirisi ve FCM anlık bildirimi.

React Native / Expo kullanıyorsanız bu paketi doğrudan kullanmayın: `@nsupp/react-native-sdk` bunu
sarar ve JS'ten aynı yüzeyi verir.

Web widget'ı ile **aynı sunucu uçlarını** konuşur (`/widget/:publicKey/…`) — ayrı bir mobil API
yoktur, dolayısıyla "webde çalışıyor, mobilde çalışmıyor" sınıfı ayrışma olmaz.

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

## Sohbeti açmak

Hazır ekran:

```kotlin
NsuppChatActivity.start(context)
```

Kendi ekranınıza gömmek isterseniz:

```kotlin
setContent {
    MaterialTheme {
        NsuppChatScreen()   // renk/tipografi sizin temanızdan gelir
    }
}
```

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
```

**Çıkışta `reset()` çağırın.** Ziyaretçi jetonu kimliğe değil **cihaza** bağlıdır; çağırmazsanız
paylaşılan bir cihazda sonraki kullanıcı öncekinin sohbet geçmişini açar.

**CSAT:** konuşma çözülüp puanlanmadıysa `pendingRating` true olur ve hazır ekran 1–5 sorar. Kendi
arayüzünüzde yok sayarsanız mobil kanal memnuniyet ölçümünün dışında kalır.

**Ekler ve bot seçimleri** çizilir (`NsuppMessage.attachments`, `pickerChoices`). Tanımadığımız
içerik türünde balon boş bırakılmaz, "gösterilemiyor" yazar — sessiz boşluk teşhis edilemez.

**Metinler cihaz dilini izler** (tr/en) — kapsam bilinçli olarak ürünün geri kalanıyla aynı.

## Bilinmesi gerekenler

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

38 test koşar (JSON kenar durumları ve derinlik gölgelemesi, jeton sızıntısı, tekilleme, imleç
kayması, kimlik kuyruğu, ek/bot-seçimi çözümü, CSAT, kısıtlı oturum, kalıcı/geçici hata ayrımı). Bu betik `kotlinc` kullanır — Gradle/Android SDK gerektirmez.
Android kabuğunun derlenmesi Gradle işidir (`build.gradle.kts`).

## Yayın durumu

Modül kaynak olarak dahil edilir; Maven Central artefaktı (`com.nsupp:nsupp-sdk`) henüz
yayınlanmadı — yayın anahtarları gerekiyor. Kaynak dahil etme sözleşmeyi değiştirmez, yalnız
bağımlılık satırı değişecektir.
