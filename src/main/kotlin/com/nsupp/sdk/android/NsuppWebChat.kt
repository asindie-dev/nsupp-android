package com.nsupp.sdk.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
 *
 * ── GÜVENLİK SINIRI ORIGIN'DİR, GEZİNME SÜZGECİ DEĞİL ────────────────────────────────────────
 * Köprü `addJavascriptInterface` ile kurulmuyor: o API nesneyi javadoc'unun deyişiyle sayfanın
 * "all frames ... including all the iframes" içine enjekte eder ve `removeJavascriptInterface`
 * ancak "next (re)loaded" belgede etkili olur — yani WEBVIEW'a bağlıdır, ORIGIN'e değil.
 * `shouldOverrideUrlLoading` de sınır olamaz: javadoc'a göre `loadUrl` ile başlatılan gezinmeler
 * ve POST istekleri için HİÇ ÇAĞRILMAZ. Bu yüzden kimlik `androidx.webkit`in origin-kurallı
 * yollarıyla veriliyor (`addDocumentStartJavaScript` + `addWebMessageListener`): ikisi de yalnız
 * origin'i eşleşen ÇERÇEVEye enjekte eder, dolayısıyla saldırgan belgesi ve opak origin'li
 * (`data:`/`srcdoc`/sandbox) çerçeveler köprüyü GÖREMEZ.
 */
class NsuppWebChat(
    private val config: NsuppConfig,
    private val store: NsuppTokenStore,
) {
    /** Yükleme başarısız oldu mu — arayüz sebebi gösterir; boş beyaz ekran "bozuk" demektir. */
    var onLoadFailed: ((String) -> Unit)? = null
    var onLoaded: (() -> Unit)? = null

    private var webView: WebView? = null

    /** Document-start script'inin tutamacı — jeton değişince SÖKÜLÜP yeniden eklenir. */
    private var scriptHandler: ScriptHandler? = null

    /**
     * Ana belge bir kez kendi origin'imizin dışına kaçtı mı (bkz. [onPageStarted] kurtarması).
     * Bayrak DÖNGÜ koruması: ana sayfamız kalıcı olarak dışarı yönlendiriyorsa (yanlış yapılandırma)
     * sonsuz "durdur → yeniden yükle" turu yerine kullanıcıya hata gösterilir.
     */
    private var kurtarmaDenendi = false

    /**
     * Widget'ın açılacağı adres — SUNUCUDAKİ host sayfası.
     *
     * Sayfa APK'ya GÖMÜLMEZ: gömseydik widget sürümü satıcının uygulama yayın döngüsüne kilitlenir
     * ve panelde yapılan değişiklik, satıcı yeni sürüm yayınlayana kadar mobilde görünmezdi.
     */
    val hostUrl: String get() = "${config.base}/widget/${config.publicKey}/app"

    private val temel: Uri = Uri.parse(config.base)
    private val temelSema: String? = temel.scheme?.lowercase()
    private val temelHost: String? = temel.host?.lowercase()
    private val temelPort: Int = etkinPort(temelSema, temel.port)

    /**
     * Köprünün bağlanacağı TEK origin kuralı — `scheme://host[:port]`.
     *
     * Ham `apiBase` metninden değil AYRIŞTIRILMIŞ parçalardan kurulur: taban bir yol taşıyorsa
     * ("https://x/y") androidx kural biçimi geçersizdir ve API istisna atar. Kural üretilemiyorsa
     * köprü HİÇ kurulmaz (fail-closed) — origin'e bağlanamayan köprü, yanlış origin'e açılan
     * köprüdür.
     */
    private val originKurali: String? =
        if ((temelSema == "https" || temelSema == "http") && !temelHost.isNullOrEmpty()) {
            temelSema + "://" + temelHost + (if (temel.port > 0) ":" + temel.port else "")
        } else {
            null
        }

    /**
     * ⚠️ `setJavaScriptEnabled` ZORUNLU: gösterdiğimiz şey bizim kendi widget'ımız ve KENDİ
     * sunucumuzdan yükleniyor. Gezinme kısıtlıdır (aşağıda): dış adresler WebView'da AÇILMAZ,
     * sistem tarayıcısına gider — yani JS yalnız bizim sayfamızda çalışır.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView {
        if (webView != null) {
            // AYNI ANDA TEK YÜZEY: komutlar (bildirimden konuşma açma, çıkışta sıfırlama) aşağıda
            // saklanan SON WebView'a gider; önceki yüzey sessizce komutsuz kalırdı — sessiz kilit
            // teşhis edilemez, en azından sebebi logcat'e yazılır.
            Log.w(TAG, "ikinci sohbet yüzeyi açıldı; öncekine artık komut gitmiyor")
        }
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
        // Varsayılan zaten `false`, ama AÇIKÇA yazılıyor: açık olsaydı `target="_blank"` bağlantılar
        // `onCreateWindow` sözleşmesine düşer ve gezinme süzgecimizin GÖRMEDİĞİ ikinci bir pencere
        // açılabilirdi.
        wv.settings.setSupportMultipleWindows(false)
        wv.setBackgroundColor(Color.TRANSPARENT)

        webView = wv
        kurtarmaDenendi = false
        // Köprü loadUrl'den ÖNCE kurulur: document-start script'i yalnız "çağrı döndükten SONRA
        // yüklenmeye başlayan" çerçevelerde çalışır.
        kopruyuKur(wv)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Kendi sayfamız gerçekten tamamlandı → kurtarma bayrağı sıfırlanır. Bayrağı
                // onPageStarted'ta sıfırlamak döngü korumasını işlevsiz bırakırdı.
                if (url != null && ayniOrigin(Uri.parse(url))) kurtarmaDenendi = false
                onLoaded?.invoke()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // İKİNCİ KATMAN: `shouldOverrideUrlLoading` javadoc'a göre POST istekleri ve
                // `loadUrl` ile başlatılan gezinmeler için ÇAĞRILMAZ; bir form gönderimi saldırgan
                // belgesine SESSİZCE ulaşabilir. Adres çubuğu olmayan tam ekranda bu, marka
                // görünümlü kimlik-avı demektir — ana belge kendi origin'imizden çıktıysa geri alınır.
                if (url == null || url == "about:blank") return
                if (ayniOrigin(Uri.parse(url))) return
                view?.stopLoading()
                if (kurtarmaDenendi) {
                    onLoadFailed?.invoke("sohbet adresi kendi sunucumuzun dışına yönlendiriyor")
                    return
                }
                kurtarmaDenendi = true
                view?.loadUrl(hostUrl)
            }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                // YALNIZ ana çerçevenin hatası kullanıcıya yansır; bir ikonun düşmesi "sohbet
                // açılmadı" demek değildir.
                if (req?.isForMainFrame == true) onLoadFailed?.invoke(err?.description?.toString() ?: "yüklenemedi")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val uri = req?.url ?: return false
                // Kendi adresimiz içeride kalır; DIŞ bağlantılar (makale, durum sayfası, powered-by)
                // sistem tarayıcısında açılır — içeride açılsalardı kullanıcı sohbetten çıkar ve
                // geri dönemezdi.
                if (ayniOrigin(uri)) return false
                if (req?.isForMainFrame != true) {
                    // ALT ÇERÇEVE dışarı çıkamaz, ama uygulama da AÇILMAZ: gizli bir
                    // `<iframe src="birsey://…">` kullanıcı DOKUNMADAN başka uygulamayı tetiklerdi.
                    return true
                }
                // ŞEMA ALLOWLIST'İ: bağlantının kaynağı uzak ve düşük yetkili yazarların elinde
                // (KB makale gövdesi, sohbet mesajı). Şema süzülmezse uzak içerik cihazdaki başka
                // bir uygulamanın kimliği doğrulanmış derin bağlantısını tetikleyebilir.
                val sema = uri.scheme?.lowercase()
                if (sema != "http" && sema != "https" && sema != "mailto" && sema != "tel") {
                    Log.w(TAG, "izin verilmeyen şema, açılmadı: " + sema)
                    return true
                }
                return try {
                    val niyet = Intent(Intent.ACTION_VIEW, uri)
                    // CATEGORY_BROWSABLE: hedef kümesini "bağlantıdan açılmayı kabul etmiş"
                    // bileşenlerle sınırlar; dışa açık olmayan iç bileşenler uzak içerikle
                    // tetiklenemez.
                    niyet.addCategory(Intent.CATEGORY_BROWSABLE)
                    niyet.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(niyet)
                    true
                } catch (_: Exception) {
                    true // açacak uygulama yok → hiçbir şey yapma (WebView'da AÇMA)
                }
            }
        }
        wv.loadUrl(hostUrl)
        return wv
    }

    /**
     * Yüzey kapandı — WebView'ı BIRAK ve YOK ET.
     *
     * ZORUNLU, isteğe bağlı bir temizlik değil. İki sebep:
     *  · SIZINTI: bu sınıf `Nsupp.webChat` üzerinden uygulama ömrü boyunca yaşar. [webView] alanı
     *    null'lanmazsa, WebView'ın `parent` zinciri (gömülü görünüm → … → DecorView) YOK EDİLMİŞ
     *    Activity'nin tüm görünüm ağacını canlı tutar. Alan tek başına null'lansa bile WebView'ın
     *    kendisi yok edilmeden ağdan/işlemciden çekilmez.
     *  · YOKLAMA DURMAZ: sohbet sayfası (widget.js) kendi yoklamasını yapar. Yok edilmeyen bir
     *    WebView ekran kapandıktan sonra da yoklamayı sürdürür — pil, veri ve sunucu yükü.
     *
     * `wv` KİMLİK KARŞILAŞTIRMASI şart: aynı anda ikinci bir yüzey açılmışsa [webView] ARTIK ona
     * aittir; koşulsuz null'lamak canlı yüzeyi sessizce komutsuz bırakırdı.
     *
     * ÇAĞIRAN, WebView'ı görünüm ağacından ÖNCE çıkarır (`WebView.destroy` javadoc'u: "This method
     * should be called after this WebView has been removed from the view system").
     */
    fun destroyWebView(wv: WebView) {
        if (webView === wv) webView = null
        try {
            // Sürmekte olan yükleme `destroy`dan sonra geri-çağrım üretmesin.
            wv.stopLoading()
            wv.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView yok edilemedi: " + e)
        }
    }

    /** Sohbeti belirli bir konuşmada aç (bildirimden derin bağlantı). */
    fun openConversation(id: String) {
        val guvenli = JSONObject.quote(id)
        webView?.evaluateJavascript("window.\$nsupp && window.\$nsupp.push(['do','chat:open',$guvenli]);", null)
    }

    /**
     * Çıkışta: jetonu sil ve sayfayı sıfırla. Paylaşılan cihazda sonraki kullanıcı öncekinin
     * sohbetini AÇMAMALI.
     *
     * `clearHistory()` TEK BAŞINA YETMİYORDU: yalnız geri/ileri yığınını siler. Jeton asıl olarak
     * WebView'ın localStorage'ında durur ve Android'de o depo DİSKE yazılır — süreç ölümünü de
     * uygulama yeniden açılışını da atlatır. Temizlik bu yüzden sayfanın kendi bağlamında,
     * sayfanın JS'i çalışmadan ÖNCE yapılır (bkz. [baslangicScripti]).
     */
    fun reset() {
        store.write(null)
        kurtarmaDenendi = false
        val wv = webView
        // `scriptHandler == null` + canlı WebView = köprü kurulurken İSTİSNA oldu: ortada
        // tazelenecek script yok, dolayısıyla bu dal hiçbir şey temizlemeden döner ve çıkış
        // SESSİZCE yalan söylerdi. O durumda aşağıdaki sayfa-bağlamı temizliğine düşülür.
        if (originKurali != null && ozelliklerDestekli() && (wv == null || scriptHandler != null)) {
            // Script yeniden eklenince (jeton artık null) başına `localStorage.clear()` gelir ve
            // yeni belge yüklenmeden ÖNCE çalışır. WebView henüz yaratılmadıysa yapılacak bir şey
            // yok: bir sonraki `createWebView` zaten temizleyen script'i kurar.
            scriptiTazele()
            wv?.clearHistory()
            wv?.loadUrl(hostUrl)
            return
        }
        if (wv != null) {
            // Köprü kurulamadı (eski WebView sürümü): temizliği sayfa bağlamında çalıştır ve
            // BİTİNCE yeniden yükle — sıra garantisi geri-çağrımdan gelir, tahminden değil.
            wv.clearHistory()
            wv.evaluateJavascript(TEMIZLE_JS) { wv.loadUrl(hostUrl) }
            return
        }
        // SON ÇARE: ne köprü ne canlı WebView var. `deleteAllData` bir SINGLETON üzerinden çalışır
        // ve UYGULAMA GENELİdir (satıcının kendi WebView verisi de gider) — bu yüzden en sonda.
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            Log.w(TAG, "depo temizlenemedi: " + e)
        }
    }

    /** İki androidx yolu da destekleniyor mu — köprü ancak İKİSİ birden varsa kurulur. */
    private fun ozelliklerDestekli(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    } catch (_: Throwable) {
        false
    }

    private fun kopruyuKur(wv: WebView) {
        val kural = originKurali
        if (kural == null) {
            // Sessiz kilit yasak: yükleme de başarısız olacağı için kullanıcı `onLoadFailed`
            // görecek, ama sebebi ancak bu iz söyler.
            Log.w(TAG, "apiBase bir http(s) origin'ine ayrışmadı, köprü kurulmadı: " + config.base)
            return
        }
        if (!ozelliklerDestekli()) {
            // FAIL-CLOSED: origin'e bağlanamıyorsak köprüyü HİÇ kurmuyoruz. Bedeli README'de
            // yazılı — alan adı kilidi açık kiracıda o cihazlarda sohbet anahtarsız kalır.
            Log.w(TAG, "WebView sürümü origin-kurallı köprüyü desteklemiyor, köprü kurulmadı")
            return
        }
        val kurallar = setOf(kural)
        try {
            WebViewCompat.addWebMessageListener(wv, "NsuppNative", kurallar, dinleyici)
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, baslangicScripti(), kurallar)
        } catch (e: Exception) {
            scriptHandler = null
            Log.w(TAG, "köprü kurulamadı: " + e)
        }
    }

    /**
     * Sayfanın JS'inden ÖNCE çalışan kimlik script'i.
     *
     * DEĞİŞMEZ — **kabuk deposu tek doğruluk kaynağıdır**: kabukta jeton yoksa WebView'daki depo
     * BAYATTIR (çıkış yapıldı ya da uygulama silinip kuruldu) ve sayfa onu okumadan silinir.
     * Bu olmadan `widget.js` önce localStorage'a baktığı için ÖNCEKİ kullanıcının oturumu açılırdı.
     */
    private fun baslangicScripti(): String {
        val jeton = store.read()
        val anahtar = config.appKey
        val temizle = if (jeton == null) TEMIZLE_JS else ""
        val a = if (anahtar == null) "null" else JSONObject.quote(anahtar)
        val j = if (jeton == null) "null" else JSONObject.quote(jeton)
        return temizle + "window.NsuppApp={appKey:" + a + ",visitorToken:" + j + "};"
    }

    /**
     * Script'i sök ve GÜNCEL jetonla yeniden ekle.
     *
     * Tazelenmezse aynı WebView içindeki bir yeniden yükleme, yeni alınmış jetonu "temizle + null"
     * diyen BAYAT script'le silerdi — kullanıcı sohbetin ortasında geçmişini kaybederdi.
     */
    private fun scriptiTazele() {
        val wv = webView ?: return
        val kural = originKurali ?: return
        if (scriptHandler == null) return // köprü hiç kurulmadı → tazelenecek script de yok
        try {
            scriptHandler?.remove()
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, baslangicScripti(), setOf(kural))
        } catch (e: Exception) {
            Log.w(TAG, "script tazelenemedi: " + e)
        }
    }

    /**
     * Sayfadan gelen mesajlar. Kanal ORIGIN'e bağlıdır (`allowedOriginRules`), dolayısıyla buraya
     * yalnız kendi sayfamız yazabilir.
     */
    private val dinleyici = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView,
            message: WebMessageCompat,
            sourceOrigin: Uri,
            isMainFrame: Boolean,
            replyProxy: JavaScriptReplyProxy,
        ) {
            // Aynı origin'deki bir ALT ÇERÇEVE de kurala uyar; jetonu yalnız ana belge yazabilir.
            if (!isMainFrame) return
            val raw = try {
                message.data
            } catch (_: Exception) {
                null
            } ?: return
            val jeton = try {
                val o = JSONObject(raw)
                if (o.optString("type") != "visitorToken") return
                o.optString("token")
            } catch (_: Exception) {
                return
            }
            // DOM storage kapalı olabilir; jetonu KABUK saklar, yoksa her açılış YENİ ziyaretçi
            // üretir ve geçmiş kaybolurdu.
            if (jeton.isEmpty()) return
            store.write(jeton)
            // `onPostMessage` androidx API yüzeyinde `@UiThread`tir, tazeleme çağrısı da öyle;
            // yine de kuyruğa alınıyor: geri-çağrımın İÇİNDE dinleyici/script topolojisini
            // değiştirmek yerine mesaj işi bittikten sonra yapmak sürprizsizdir.
            view.post { scriptiTazele() }
        }
    }

    /**
     * "İçeride miyiz" kararı ÖNEK değil ORIGIN karşılaştırmasıdır.
     *
     * Önek testi (`startsWith`) `https://api.nsupp.com@saldirgan.example/` ve
     * `https://api.nsupp.com.saldirgan.example/` adreslerini İÇERİDE sayıyordu. Ayrıştırmayı elle
     * yapmıyoruz: `Uri.getHost` javadoc'una göre authority "bob@google.com" iken "google.com"
     * döner — yani userinfo'yu platformun kendi ayrıştırıcısı eler.
     *
     * `lowercase()` locale-BAĞIMSIZDIR; `toLowerCase(Locale.getDefault())` Türkçe locale'de
     * I→ı yapıp eşleşmeyi bozardı.
     */
    private fun ayniOrigin(u: Uri): Boolean {
        if (originKurali == null) return false
        val s = u.scheme?.lowercase() ?: return false
        val h = u.host?.lowercase() ?: return false
        return s == temelSema && h == temelHost && etkinPort(s, u.port) == temelPort
    }

    private companion object {
        const val TAG = "NsuppWebChat"
        const val TEMIZLE_JS = "try{localStorage.clear();sessionStorage.clear();}catch(e){}"

        /** Açık port yoksa şemanın varsayılanı — `https://x` ile `https://x:443` aynı origin'dir. */
        fun etkinPort(sema: String?, port: Int): Int = when {
            port > 0 -> port
            sema == "https" -> 443
            sema == "http" -> 80
            else -> -1
        }
    }
}
