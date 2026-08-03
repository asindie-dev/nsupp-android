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
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var pendingPushToken: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val current: NsuppSession?
        get() = session

    /**
     * @param appKey panelde Ayarlar → Uygulamalar'dan üretilen uygulama anahtarı. Alan adı kilidi
     *   AÇIK bir çalışma alanında yerel yüzeyin TEK geçiş yoludur; kilit kapalıysa boş bırakılabilir.
     */
    fun init(context: Context, apiBase: String, publicKey: String, appKey: String? = null) {
        if (session != null) return
        val cfg = NsuppConfig(apiBase, publicKey, appKey = appKey)
        val store = PrefsTokenStore(context, publicKey)
        val s = NsuppSession(NsuppApi(cfg, AndroidHttp()), store)
        session = s
        // Sohbet arayüzü WebView'da; `NsuppSession` artık YALNIZ kimlik/bildirim/yapılandırma için.
        webChat = NsuppWebChat(cfg, store)
        // 🔴 TEK İŞ PARÇACIĞI: NsuppSession'ın durumu (state/seen/lastTs) korumasız alanlar — saf
        // Kotlin olması için bilinçli bir tercih. Çok-iş-parçacıklı bir IO havuzunda `send` ile
        // `pollOnce` çakışırsa mesaj kaybı ve bozuk imleç üretir. `limitedParallelism(1)` çekirdeği
        // seri tutar; iOS tarafında aynı güvenceyi `@MainActor` zaten sağlıyor.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val seriIO = Dispatchers.IO.limitedParallelism(1)
        scope = CoroutineScope(SupervisorJob() + seriIO)
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
            pendingPushToken?.let { t -> pendingPushToken = null; s.registerPushToken(t) }
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
     */
    fun reset() {
        onChatClosed()
        pendingPushToken = null
        session?.reset()
        // Paylaşılan cihazda sonraki kullanıcı öncekinin sohbetini AÇMAMALI: jeton silinir ve
        // sayfa sıfırlanır.
        webChat?.reset()
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
        scope?.launch { s.registerPushToken(token) }
    }
}
