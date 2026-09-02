package com.nsupp.sdk.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nsupp.sdk.NsuppApi
import com.nsupp.sdk.NsuppArticle
import com.nsupp.sdk.NsuppConfig
import com.nsupp.sdk.NsuppHttp
import com.nsupp.sdk.NsuppSession
import com.nsupp.sdk.NsuppState
import com.nsupp.sdk.NsuppTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android kabuğu — saf çekirdeği (NsuppApi/NsuppSession) platforma bağlar.
 *
 * Buradaki her şey ince ve değiştirilebilirdir: HTTP, jeton saklama, zamanlama. İş mantığı
 * çekirdekte durduğu için bu dosyanın hatası mantığı bozmaz, yalnız taşımayı etkiler.
 */

/** HttpURLConnection ile HTTP. OkHttp DEĞİL: SDK satıcının uygulamasına bağımlılık dayatmaz. */
internal class AndroidHttp : NsuppHttp {
    override fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            // Hata gövdesi errorStream'dedir; okumazsak sunucunun sebebi KAYBOLUR.
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }
}

/**
 * SharedPreferences tabanlı jeton saklama.
 *
 * EncryptedSharedPreferences DEĞİL — gerekçe [NsuppTokenStore] belgesinde: jeton bir sır değil,
 * anonim oturum tanıtıcısıdır ve uygulama silinince GİTMELİDİR.
 */
internal class PrefsTokenStore(context: Context, publicKey: String) : NsuppTokenStore {
    // Anahtar çalışma alanına özel: aynı cihazda iki nsupp alanı kullanılırsa jetonlar karışmasın.
    private val prefs = context.applicationContext.getSharedPreferences("nsupp.$publicKey", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("visitorToken", null)
    override fun write(token: String?) {
        prefs.edit().apply { if (token == null) remove("visitorToken") else putString("visitorToken", token) }.apply()
    }

    /**
     * SUNUCUDA KAYITLI FCM JETONU — çıkışın sunucu ayağı bunsuz çalışamaz.
     *
     * 🔴 NİÇİN DİSKTE, BELLEKTE DEĞİL: kayıt uygulama açılışında yapılır, çıkış ise ÇOK SONRA
     *    (çoğu zaman başka bir süreçte) gelir. Alanda tutsaydık "uygulama yeniden başladıktan
     *    sonra yapılan çıkış" — yani gerçek hayattaki çıkışların çoğu — silinecek jetonu bulamaz
     *    ve sunucu satırı SESSİZCE ayakta kalırdı. Aynı gerekçe iOS tarafında da yazılı.
     *
     * Ziyaretçi jetonuyla AYNI dosyada ve aynı yaşam döngüsünde: uygulama silinince ikisi de gider.
     */
    fun readPushDevice(): String? = prefs.getString("pushDeviceToken", null)
    fun writePushDevice(token: String?) {
        prefs.edit().apply { if (token == null) remove("pushDeviceToken") else putString("pushDeviceToken", token) }.apply()
    }
}

/**
 * Uygulamanın gördüğü tek giriş noktası.
 *
 * KULLANIM (Application.onCreate):
 *   Nsupp.init(this, apiBase = "https://api.nsupp.com", publicKey = "pk_…")
 * Sohbeti açmak:  NsuppChatActivity.start(context)
 *
 * Sohbet arayüzü WEB WIDGET'ININ KENDİSİDİR (NsuppWebChat): görünüm ve işlevin tamamı çalışma
 * alanı ayarından gelir. Yerel bir sohbet ekranı SUNMUYORUZ — sunsaydık widget iki yerde çizilir
 * ve "tek noktadan yönetim" vaadi kod düzeyinde tutulamazdı.
 */
object Nsupp {
    @Volatile private var session: NsuppSession? = null

    /**
     * Sohbet yüzeyi — **web widget'ının kendisi** (yerel bir kopyası DEĞİL). Görünüm ve işlevin
     * tamamı çalışma alanı ayarından gelir; ayrı bir mobil arayüz sunmuyoruz.
     */
    @Volatile internal var webChat: NsuppWebChat? = null
        private set

    /**
     * ⚠️ `session` ile BİRLİKTE okunur ve `session != null` görüldüğünde DOLU olmak zorundadır
     * (bkz. [init]) — bu yüzden `@Volatile`: aksi hâlde yazma başka bir iş parçacığında görünmeyip
     * `reset()` sessizce no-op olurdu.
     */
    @Volatile private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var pendingPushToken: String? = null
    /**
     * Kalıcı FCM jeton kaydı — çıkışta sunucudaki cihaz satırını düşürmek için (bkz.
     * [PrefsTokenStore.readPushDevice]). `session` ile birlikte kurulur, `@Volatile` gerekçesi de
     * onunkiyle aynı.
     */
    @Volatile private var pushDeposu: PrefsTokenStore? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val current: NsuppSession?
        get() = session

    /**
     * @param appKey panelde Ayarlar → Uygulamalar'dan üretilen uygulama anahtarı. Alan adı kilidi
     *   AÇIK bir çalışma alanında yerel yüzeyin TEK geçiş yoludur; kilit kapalıysa boş bırakılabilir.
     *
     * `@Synchronized` + `session` ATAMASI EN SONDA — ikisi birden gerekli:
     *  · Kilit, iki iş parçacığının aynı anda İKİ oturum kurmasını engeller (giriş koruması
     *    `session != null` tek başına yarışı kapatmaz).
     *  · Sıra, kilidi TUTMAYAN okurlar içindir: `session`ı gören her iş parçacığı `scope`u da
     *    görmek ZORUNDA (`@Volatile` yazma sırası). Ters sırada, iki atama arasına düşen bir
     *    `reset()` çağrısı `scope?.launch`ta no-op oluyordu — yani ÇIKIŞ sessizce hiç koşmuyordu.
     */
    @Synchronized
    fun init(context: Context, apiBase: String, publicKey: String, appKey: String? = null) {
        if (session != null) return
        val cfg = NsuppConfig(apiBase, publicKey, appKey = appKey)
        val store = PrefsTokenStore(context, publicKey)
        pushDeposu = store
        val s = NsuppSession(NsuppApi(cfg, AndroidHttp()), store)
        // Sohbet arayüzü WebView'da; `NsuppSession` artık YALNIZ kimlik/bildirim/yapılandırma için.
        // Bağlam ÜÇÜNCÜ argüman: çıkışta kalıcı WebView önbelleğini silmek için gerekli
        // (bkz. `NsuppWebChat.onbellegiTemizle`). Bağlamsız kurulsaydı, sohbet o süreçte
        // hiç açılmadan yapılan çıkış temizlenecek WebView'ı bulamazdı.
        webChat = NsuppWebChat(cfg, store, context.applicationContext)
        // 🔴 TEK İŞ PARÇACIĞI: NsuppSession'ın durumu (state/seen/lastTs) korumasız alanlar — saf
        // Kotlin olması için bilinçli bir tercih. Çok-iş-parçacıklı bir IO havuzunda `send` ile
        // `pollOnce` çakışırsa mesaj kaybı ve bozuk imleç üretir. `limitedParallelism(1)` çekirdeği
        // seri tutar; iOS tarafında aynı güvenceyi `@MainActor` zaten sağlıyor.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val seriIO = Dispatchers.IO.limitedParallelism(1)
        scope = CoroutineScope(SupervisorJob() + seriIO)
        session = s
    }

    /**
     * Sohbet ekranı açıldığında: oturumu aç ve yoklamayı başlat.
     *
     * [onState] ANA İŞ PARÇACIĞINDA çağrılır. Çekirdek yoklamayı IO'da yapar; Compose durumunu
     * arka plandan yazmak yarış demektir — sınırı burada, TEK yerde geçiyoruz.
     */
    fun onChatOpened(onState: (NsuppState) -> Unit) {
        val s = session ?: return
        s.onState = { st -> mainHandler.post { onState(st) } }
        val sc = scope ?: return
        pollJob?.cancel()
        pollJob = sc.launch {
            s.start()
            // Anlık bildirim jetonu oturumdan ÖNCE geldiyse şimdi kaydedilir (yarış kapanır).
            pendingPushToken?.let { t -> pendingPushToken = null; pushJetonunuGonder(s, t) }
            // Yoklama YALNIZ ekran açıkken. Arka planda değil: pili yakar ve Android süreci öldürür;
            // arka plan işi FCM'in işidir.
            //
            // `s.stopped` = oturum KALICI olarak bitti (401/403/404 ya da reset). Bunu okumazsak
            // döngü sonsuza kadar aynı hatayı alarak pili ve sunucuyu boşuna yakar.
            while (isActive && !s.stopped) {
                delay(4_000)
                s.pollOnce()
            }
        }
    }

    /** Ekran kapandığında yoklama DURUR. */
    fun onChatClosed() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Mesaj gönder. [onResult] ANA İŞ PARÇACIĞINDA çağrılır; `false` gelirse arayüz kullanıcının
     * yazdığı metni geri koyar — aksi halde ağ hatasında yazılan mesaj buharlaşır.
     */
    fun send(text: String, onResult: ((Boolean) -> Unit)? = null) {
        val s = session ?: return
        scope?.launch {
            val ok = s.send(text)
            if (onResult != null) mainHandler.post { onResult(ok) }
        }
    }

    /**
     * Ziyaretçiyi tanıt — **giriş yaptığı anda çağırın**, sohbet ekranını beklemeyin.
     *
     * Oturum henüz yoksa kimlik bekletilir ve sohbet ilk açıldığında gönderilir.
     * [signature]'ı SUNUCUNUZDA üretin (`HMAC-SHA256(email, identity_secret)`); uygulamaya gömülen
     * bir sır doğrulamayı anlamsız kılar.
     */
    fun identify(
        email: String,
        name: String? = null,
        signature: String? = null,
        attributes: Map<String, Any?>? = null,
    ) {
        val s = session ?: return
        scope?.launch { s.identify(email, name, signature, attributes) }
    }

    /** Özel öznitelik yaz/güncelle. Önce [identify] gerekir (öznitelikler kişi kaydında yaşar). */
    fun setSessionData(attributes: Map<String, Any?>) {
        val s = session ?: return
        scope?.launch { s.setSessionData(attributes) }
    }

    /** Segmentleri ayarla (VIP/plan yönlendirmesi). `attributes.segments` DEĞİŞTİRİLİR. */
    fun setSegments(segments: List<String>) {
        val s = session ?: return
        scope?.launch { s.setSegments(segments) }
    }

    /** Özel olay bildir (kampanya/tetikleyici koşulları + kişi zaman-çizelgesi). */
    fun trackEvent(name: String) {
        val s = session ?: return
        scope?.launch { s.trackEvent(name) }
    }

    /**
     * Yardım merkezi makalelerini yükle — sohbeti hiç açmadan çözülen sorular (self-servis).
     * Sonuç `NsuppState.articles`e düşer.
     */
    suspend fun loadArticles(locale: String? = null): Boolean =
        withContext(Dispatchers.IO) { session?.loadArticles(locale) ?: false }

    /** Makale ara. Sorgu boşsa yüklü liste döner. */
    suspend fun searchArticles(query: String, locale: String? = null): List<NsuppArticle> =
        withContext(Dispatchers.IO) { session?.searchArticles(query, locale) ?: emptyList() }

    /** Tek makaleyi getir (görüntülenme sayacı sunucuda artar). */
    suspend fun article(slug: String, locale: String? = null): NsuppArticle? =
        withContext(Dispatchers.IO) { session?.article(slug, locale) }

    /** Konuşmayı puanla (CSAT, 1–5). `NsuppState.pendingRating` true iken sorulur. */
    fun rate(score: Int, comment: String? = null) {
        val s = session ?: return
        scope?.launch { s.rate(score, comment) }
    }

    /** Bir mesaj tetikleyicisini çalıştır (Crisp'in `runBotScenario` karşılığı). */
    fun runTrigger(identifier: String) {
        val s = session ?: return
        scope?.launch { s.runTrigger(identifier) }
    }

    /**
     * Oturumu sıfırla — **kullanıcı uygulamanızdan ÇIKIŞ yaptığında çağırın.**
     *
     * Jetonu siler ve yoklamayı durdurur. Bu olmadan paylaşılan bir cihazda bir sonraki kullanıcı,
     * öncekinin sohbet geçmişini açar. Bekleyen anlık-bildirim jetonu da düşer: o jeton artık
     * ESKİ ziyaretçiye aitti.
     *
     * HANGİ İŞ PARÇACIĞINDAN ÇAĞRILIRSA ÇAĞRILSIN GÜVENLİDİR — ve iş İKİYE ayrılır, çünkü iki
     * hedefin iş parçacığı sözleşmesi ZITTIR:
     *
     *  ① `session.reset()` → SERİ IO kanalı. `NsuppSession`ın durumu (`state`/`seen`/`lastTs`)
     *     korumasız alanlardır ve bu sınıfın değişmezi onları `limitedParallelism(1)` ile seri
     *     tutmaktır (bkz. [init]). `reset()` TEK İSTİSNAYDI: çağıranın iş parçacığında koşuyor,
     *     yani uçuşta bir `pollOnce`/`send` varken `seen.clear()` diyordu — `pollOnce`un içindeki
     *     `apply()` aynı `HashSet`i yazarken. Kuyruğa alınınca bu yapısal olarak imkânsız hâle
     *     gelir; üstelik SIRA da düzelir: `pollJob.cancel()` BLOKLAYAN `HttpURLConnection`
     *     okumasını KESMEZ, ama sıfırlama o okumanın ARKASINA dizildiği için son söz sıfırlamanın
     *     olur (eskiden sıfırlama önce koşar, biten istek üstüne yazardı).
     *  ② `webChat.reset()` → ANA iş parçacığı. Orada WebView'a dokunuluyor ve `WebView` yanlış iş
     *     parçacığından çağrılınca çalışma-zamanı istisnası atar; satıcı çıkışı bir arka plan
     *     işinden (ör. oturum kapatma ağ çağrısının geri-çağrımı) tetiklediğinde SDK ÇÖKÜYORDU.
     *
     * ── JETONU YALNIZ ① SİLER ────────────────────────────────────────────────────────────────
     * İki yarı AYRI kuyruklardadır, dolayısıyla birbirlerine göre SIRASIZDIR. İkisi de depoya
     * yazdığı sürece bir kayıp senaryosu vardı: ana iş parçacığı ekran geçişinde meşgulken yeni
     * kullanıcı sohbeti açar, `start()` seri kanalda taze jetonu yazar, SONRA bekleyen ②
     * drenaj olup `store.write(null)` ile onu SİLERDİ (yeni kullanıcı oturumunu ve geçmişini
     * kaybeder, sunucuda öksüz ziyaretçi kalır). Artık silme TEK sahiplidir — seri kanal — ve ②
     * yalnız WebView işi yapar (nesil artışı, script tazeleme, `loadUrl`); depoya ne yazar ne de
     * okur. ②'yi ①'in ARKASINA dizmek çözüm DEĞİLDİ: uçuştaki bir yoklama okuması seri kanalı
     * saniyelerce tutabilir ve çıkan kullanıcının sohbeti o kadar süre EKRANDA kalırdı.
     *
     * @param onComplete çıkış TAMAMLANDIĞINDA — jeton silinmiş, oturum durdurulmuş ve bekleyen
     *   bildirim jetonu düşürülmüş olarak — ANA iş parçacığında çağrılır. Bu andan itibaren
     *   `Nsupp.current?.visitorToken` `null`dur; satıcı "çıkışta jeton silindi" vaadini kendi
     *   tarafında böyle doğrulayabilir. Sohbet YÜZEYİNİN yeniden yüklenmesi ayrı ve asenkrondur
     *   (② yarısı); onu gözlemek isteyen `NsuppWebChat.onLoaded`ı kullanır.
     */
    fun reset(onComplete: (() -> Unit)? = null) {
        // Yoklama ÖNCE ve SENKRON durur: kuyruğa alsaydık iptal, sıfırlamanın arkasına dizilirdi.
        onChatClosed()
        val s = session
        val sc = scope
        if (sc == null) {
            // SDK hiç `init` edilmedi: silinecek jeton da yok. Geri-çağrım YİNE de çalışır —
            // hiç çalışmayan bir geri-çağrım satıcının çıkış akışını sessizce askıda bırakırdı.
            if (onComplete != null) mainHandler.post { onComplete() }
            return
        }
        sc.launch {
            // Bekleyen anlık-bildirim jetonu da SERİ kanalda düşürülür: onu okuyan yer
            // ([onChatOpened] döngüsü) aynı kanalda koşuyor.
            pendingPushToken = null
            /**
             * SUNUCUDAKİ CİHAZ KAYDI DA DÜŞER. Yerel jetonu silmek YETMEZ: satır sunucuda kalır ve
             * gönderim yükü çalışma alanı ADINI + mesaj ÖNİZLEMESİNİ taşır — paylaşılan/devredilen
             * telefonda önceki kullanıcının destek yazışması sonraki sahibin KİLİT EKRANINA düşer.
             * Bildirimi sistem çizer; istemci tarafında bastırmak mümkün değil.
             *
             * Jeton ÇEKİRDEĞE VERİLİR, burada silinmez: sıra (sunucu adımı → yerel silme) çekirdekte
             * korunur, çünkü sunucu sahibi ziyaretçi jetonundan bulur ve o jeton `reset()` içinde
             * siliniyor (bkz. `NsuppSession.reset`).
             */
            val depo = pushDeposu
            s?.reset(depo?.readPushDevice())
            // Kayıt DÜŞTÜ (ya da hiç yoktu): yerel iz de kalmaz. Silme başarısız olsa bile burayı
            // temizleriz — jeton artık bu kullanıcıya ait değil ve sunucu tarafında saklama süresi
            // tavanı devrede (`VISITOR_PUSH_DEVICE_RETENTION_DAYS`).
            depo?.writePushDevice(null)
            if (onComplete != null) mainHandler.post { onComplete() }
        }
        // Paylaşılan cihazda sonraki kullanıcı öncekinin sohbetini EKRANDA görmemeli: sayfa
        // jetonsuz baştan yüklenir (kabuk jetonunu ① sildi).
        mainHandler.post { webChat?.reset() }
    }

    /**
     * Yeni bir konu başlat — önceki konular KAPANMAZ (çoklu konuşma).
     * Konuşma İLK MESAJLA doğar; bu çağrı ekranı temizler ve sonraki gönderime "yeni konu" bayrağı iliştirir.
     */
    fun startNewConversation() {
        session?.startNewConversation()
    }

    /** Var olan bir konuya geç ([conversations] listesinden gelen id). */
    fun openConversation(id: String) {
        session?.openConversation(id)
    }

    suspend fun conversations() = withContext(Dispatchers.IO) { session?.conversations() ?: emptyList() }

    /**
     * FCM jetonunu bildir. `NsuppMessagingService.onNewToken` buradan geçer; ziyaretçi henüz
     * yoksa jeton BEKLETİLİR ve oturum açılınca gönderilir (sessizce düşürülmez).
     */
    fun registerPushToken(token: String) {
        val s = session
        if (s?.visitorToken == null) { pendingPushToken = token; return }
        scope?.launch { pushJetonunuGonder(s, token) }
    }

    /**
     * PUSH JETONUNU GÖNDER **VE KALICI OLARAK NOT ET** — kaydın TEK darboğazı.
     *
     * İki çağıran var (doğrudan [registerPushToken] ve [onChatOpened]'ın bekleyen-jeton drenajı);
     * notu ikisine ayrı ayrı yazsaydık biri unutulduğunda çıkış o cihazı SESSİZCE düşüremezdi —
     * ve hangi yoldan kaydedildiğine bağlı olarak bazen çalışan bir çıkış en kötü türdendir.
     *
     * NOT AĞ ÇAĞRISINDAN ÖNCE: [NsuppSession.registerPushToken] hatayı yutar, yani "kaydoldu mu"
     * bilinmez. Kaydolmamış bir jeton için çıkışta yapılan silme çağrısı ZARARSIZDIR (sunucu
     * eşleşen satır bulamaz); tersi — kaydolmuş ama not edilmemiş jeton — düşürülemeyen bir
     * satır bırakırdı.
     */
    private fun pushJetonunuGonder(s: NsuppSession, token: String) {
        pushDeposu?.writePushDevice(token)
        s.registerPushToken(token)
    }
}
