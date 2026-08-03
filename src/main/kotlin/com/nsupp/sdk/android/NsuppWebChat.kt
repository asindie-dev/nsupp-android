package com.nsupp.sdk.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nsupp.sdk.NsuppConfig
import com.nsupp.sdk.NsuppTokenStore
import org.json.JSONObject

/**
 * nsupp sohbet ekranı — **web widget'ının KENDİSİ**, yerel bir kopyası değil.
 *
 * ── NİÇİN WEBVIEW ────────────────────────────────────────────────────────────────────────────
 * Ürün kuralı: widget ayarı çalışma alanı başına TEKtir ve web/mobil/masaüstünde AYNI görünür
 * (ön-sohbet formu, Makaleler sekmesi, hamburger menüsü, powered-by, CSAT, kesinti bandı, hızlı
 * yanıtlar, dosya eki). Yerel arayüzü ikinci/üçüncü kez yazmak bu vaadi kod düzeyinde tutulamaz
 * kılıyordu: `widget.js` ~4900 satır, yerel Compose ekranı 225'ti ve `widgetConfig`ten yalnız renk
 * okunuyordu — yüzeyin %92'si eksikti.
 *
 * ── YEREL TARAFIN İŞİ ────────────────────────────────────────────────────────────────────────
 * Kabuk sohbetten HİÇBİR ŞEY çizmez. Yalnız WebView'ın yapamadıklarını üstlenir: uygulama
 * anahtarını/jetonu sayfaya vermek, sayfadan gelen jetonu kalıcı saklamak, hata durumunu
 * göstermek, dış bağlantıları sistem tarayıcısına yollamak.
 */
class NsuppWebChat(
    private val config: NsuppConfig,
    private val store: NsuppTokenStore,
) {
    /** Yükleme başarısız oldu mu — arayüz sebebi gösterir; boş beyaz ekran "bozuk" demektir. */
    var onLoadFailed: ((String) -> Unit)? = null
    var onLoaded: (() -> Unit)? = null

    private var webView: WebView? = null

    /**
     * Widget'ın açılacağı adres — SUNUCUDAKİ host sayfası.
     *
     * Sayfa APK'ya GÖMÜLMEZ: gömseydik widget sürümü satıcının uygulama yayın döngüsüne kilitlenir
     * ve panelde yapılan değişiklik, satıcı yeni sürüm yayınlayana kadar mobilde görünmezdi.
     */
    val hostUrl: String get() = "${config.base}/widget/${config.publicKey}/app"

    /**
     * ⚠️ `setJavaScriptEnabled` ZORUNLU: gösterdiğimiz şey bizim kendi widget'ımız ve KENDİ
     * sunucumuzdan yükleniyor. Gezinme kısıtlıdır (aşağıda): dış adresler WebView'da AÇILMAZ,
     * sistem tarayıcısına gider — yani JS yalnız bizim sayfamızda çalışır.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView {
        val wv = WebView(context.applicationContext)
        wv.settings.javaScriptEnabled = true
        // Ziyaretçi jetonu için: kapalıysa köprü devreye girer (aşağıdaki `visitorToken`).
        wv.settings.domStorageEnabled = true
        // Karışık içerik ASLA: https sayfaya http kaynak yüklenmesi ağdaki birinin araya girmesine
        // kapı açar.
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Dosya erişimi KAPALI: widget'ın yerel dosya sistemine ihtiyacı yok; açık bırakmak
        // kötü niyetli bir sayfanın uygulama verisini okumasına yarayan klasik bir yüzeydir.
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.setBackgroundColor(Color.TRANSPARENT)

        // Köprü SENKRON okunur (document-start'tan itibaren hazır) — Android'de
        // `evaluateJavascript` zamanlaması sayfa scriptleriyle YARIŞIR, bu yüzden JS arayüzü.
        wv.addJavascriptInterface(Bridge(), "NsuppNative")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { onLoaded?.invoke() }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                // YALNIZ ana çerçevenin hatası kullanıcıya yansır; bir ikonun düşmesi "sohbet
                // açılmadı" demek değildir.
                if (req?.isForMainFrame == true) onLoadFailed?.invoke(err?.description?.toString() ?: "yüklenemedi")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val url = req?.url?.toString() ?: return false
                // Kendi adresimiz içeride kalır; DIŞ bağlantılar (makale, durum sayfası, powered-by)
                // sistem tarayıcısında açılır — içeride açılsalardı kullanıcı sohbetten çıkar ve
                // geri dönemezdi.
                if (url.startsWith(config.base)) return false
                return try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Exception) {
                    true // açacak uygulama yok → hiçbir şey yapma (WebView'da AÇMA)
                }
            }
        }
        webView = wv
        wv.loadUrl(hostUrl)
        return wv
    }

    /** Sohbeti belirli bir konuşmada aç (bildirimden derin bağlantı). */
    fun openConversation(id: String) {
        val guvenli = JSONObject.quote(id)
        webView?.evaluateJavascript("window.\$nsupp && window.\$nsupp.push(['do','chat:open',$guvenli]);", null)
    }

    /**
     * Çıkışta: jetonu sil ve sayfayı sıfırla. Paylaşılan cihazda sonraki kullanıcı öncekinin
     * sohbetini AÇMAMALI.
     */
    fun reset() {
        store.write(null)
        webView?.clearHistory()
        webView?.loadUrl(hostUrl)
    }

    /** Sayfanın senkron okuduğu köprü. Yalnız `@JavascriptInterface` işaretli metotlar görünür. */
    private inner class Bridge {
        @JavascriptInterface
        fun appKey(): String? = config.appKey

        @JavascriptInterface
        fun visitorToken(): String? = store.read()

        @JavascriptInterface
        fun postMessage(raw: String) {
            try {
                val o = JSONObject(raw)
                if (o.optString("type") == "visitorToken") {
                    val t = o.optString("token")
                    // DOM storage kapalı olabilir; jetonu KABUK saklar, yoksa her açılış YENİ
                    // ziyaretçi üretir ve geçmiş kaybolurdu.
                    if (t.isNotEmpty()) store.write(t)
                }
            } catch (_: Exception) {
            }
        }
    }
}
