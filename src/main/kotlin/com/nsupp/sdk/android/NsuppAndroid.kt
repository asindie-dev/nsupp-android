package com.nsupp.sdk.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nsupp.sdk.NsuppApi
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
 * Sohbeti açmak:  NsuppChatActivity.start(context)   — ya da NsuppChatScreen()'i kendi ekranınıza gömün.
 */
object Nsupp {
    @Volatile private var session: NsuppSession? = null
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var pendingPushToken: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val current: NsuppSession?
        get() = session

    fun init(context: Context, apiBase: String, publicKey: String) {
        if (session != null) return
        val cfg = NsuppConfig(apiBase, publicKey)
        val s = NsuppSession(NsuppApi(cfg, AndroidHttp()), PrefsTokenStore(context, publicKey))
        session = s
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    fun send(text: String) {
        val s = session ?: return
        scope?.launch { s.send(text) }
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
