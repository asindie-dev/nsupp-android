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
import java.lang.ref.WeakReference

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
 *
 * ── İŞ PARÇACIĞI: ANA (UI) İŞ PARÇACIĞINA KAPALI ─────────────────────────────────────────────
 * Bu sınıfın alanları KORUMASIZDIR ve öyle kalmalıdır: her giriş noktası ana iş parçacığındadır.
 * `createWebView`/`destroyWebView` zaten WebView'ın kendi sözleşmesi gereği oradadır
 * (`WebView` yanlış iş parçacığından çağrılınca çalışma-zamanı istisnası atar), `onPostMessage`
 * androidx yüzeyinde `@UiThread`tir, `reset()` ise `Nsupp.reset()` tarafından ana iş parçacığına
 * POSTALANIR (bkz. `NsuppAndroid.kt`). Bu kapatma olmadan `reset()` çağıranın iş parçacığında
 * koşuyordu — yani satıcı çıkışı bir arka plan işinden tetiklediğinde WebView istisna atıyordu.
 */
class NsuppWebChat(
    private val config: NsuppConfig,
    private val store: NsuppTokenStore,
) {
    /** Yükleme başarısız oldu mu — arayüz sebebi gösterir; boş beyaz ekran "bozuk" demektir. */
    var onLoadFailed: ((String) -> Unit)? = null
    var onLoaded: (() -> Unit)? = null

    /**
     * Bu köprüye bağlı CANLI yüzey — bir WebView + ONUN document-start tutamacı + üstündeki
     * belgenin nesli.
     *
     * TEK ALAN DEĞİL LİSTE: aynı köprü aynı anda birden çok WebView besleyebilir (satıcının
     * düzenine gömülü `NsuppWebChatView` + `NsuppChatPresenter`ın balon paneli + hazır
     * `NsuppChatActivity`). Tek bir `webView` alanı tutulduğunda [reset] yalnız SONUNCUSUNU
     * yeniden yüklüyordu: hayalet kalan yüzey çıkıştan sonra da ESKİ oturumu göstermeye devam
     * ediyor, üstelik `scriptHandler` de tekil olduğu için o yüzeyin kimlik script'i bir daha
     * HİÇ tazelenmiyordu (ikinci `createWebView` tutamacı üzerine yazıyordu).
     *
     * WebView ZAYIF tutulur: bu köprü `Nsupp.webChat` üzerinden uygulama ömrü boyunca yaşar;
     * güçlü tutsaydık `destroyWebView` çağrılmayan her yol (satıcı hatası) yok edilmiş bir
     * Activity'nin bütün görünüm ağacını canlı tutardı.
     */
    private class Yuzey(webView: WebView, nesil: Int) {
        private val ref = WeakReference(webView)

        val webView: WebView? get() = ref.get()

        /** Bu yüzeyin document-start script tutamacı — jeton değişince SÖKÜLÜP yeniden eklenir. */
        var scriptHandler: ScriptHandler? = null

        /**
         * Bu yüzeyde ŞU AN duran belgenin nesli; `null` = "sıfırlamadan sonra henüz yeni belge
         * commit olmadı", yani ekrandaki belge ESKİ oturuma ait ve jeton YAZAMAZ.
         */
        var belgeNesli: Int? = nesil

        /**
         * Bu yüzeyin ana belgesi bir kez kendi origin'imizin dışına kaçtı mı (bkz. [onPageStarted]
         * kurtarması). Bayrak DÖNGÜ koruması: sayfamız kalıcı olarak dışarı yönlendiriyorsa
         * (yanlış yapılandırma) sonsuz "durdur → yeniden yükle" turu yerine hata gösterilir.
         *
         * YÜZEY BAŞINA, tekil alan DEĞİL: çok yüzey DESTEKLENEN yapılandırmadır (bkz. [Yuzey]).
         * Tekil alanda iki yönlü bozulma vardı — (a) B yüzeyinin açılışı/yüklenmesi A'nın
         * bayrağını `false`a çekiyor ve "yalnız bir deneme" garantisini sınırsız tura çeviriyordu,
         * (b) A bayrağı tükettiyse B İLK kaçışında hiç kurtarma denemeden hata basıyordu.
         */
        var kurtarmaDenendi = false
    }

    private val yuzeyler = mutableListOf<Yuzey>()

    /**
     * OTURUM NESLİ (epok). [reset] her çağrıldığında artar.
     *
     * NİÇİN: [reset] sırası nesil artışı → script tazele (jetonsuz) → `loadUrl`. Yeni gezinme COMMIT
     * olana kadar ESKİ belge yaşamaya devam eder ve o aralıkta düşen bir `/session` yanıtı jetonu
     * köprüye postalayabilir. Kapı olmadan [dinleyici] bunu `store.write(jeton)` diye kabul
     * ediyordu: çıkış yapan kullanıcının jetonu geri geliyor ve tazelenen script onu YENİ belgeye
     * taşıyordu.
     *
     * Nesli mesajın KENDİSİNDEN okumuyoruz — sayfa (uzak içerik) kendi neslini uydurabilirdi ve
     * `widget.js` böyle bir alan da göndermez. Nesil, mesajı GÖNDEREN BELGEnin kimliğinden gelir:
     * damga yalnız bizim tetiklediğimiz yüklemenin geri-çağrımından konur (bkz. [nesliDamgala]).
     */
    private var nesil = 0

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
        // Serbest bırakılmış yüzeyler birikmesin (satıcı `destroyWebView` çağırmadan görünümü
        // bıraktıysa kayıt işe yaramaz durumdadır).
        yuzeyler.removeAll { it.webView == null }
        if (yuzeyler.isNotEmpty()) {
            // Sıfırlama TÜM yüzeylere gider (bkz. [Yuzey]); ama KOMUT (bildirimden konuşma açma)
            // tek bir yüzeye — kullanıcının o an baktığı, yani EN SON yaratılana. Sessiz kilit
            // teşhis edilemez, en azından sebebi logcat'e yazılır.
            Log.w(TAG, "ikinci sohbet yüzeyi açıldı; komutlar yalnız en son yüzeye gidiyor")
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

        // Yeni yüzey GÜNCEL nesle doğar: kaydın hemen ardından `loadUrl(hostUrl)` çağrılıyor,
        // yani üstüne gelecek ilk belge tanım gereği bu nesle aittir.
        val yuzey = Yuzey(wv, nesil)
        yuzeyler.add(yuzey)
        // Köprü loadUrl'den ÖNCE kurulur: document-start script'i yalnız "çağrı döndükten SONRA
        // yüklenmeye başlayan" çerçevelerde çalışır.
        kopruyuKur(yuzey, wv)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Kendi sayfamız gerçekten tamamlandı → BU YÜZEYİN kurtarma bayrağı sıfırlanır.
                // Bayrağı onPageStarted'ta sıfırlamak döngü korumasını işlevsiz bırakırdı.
                if (url != null && ayniOrigin(Uri.parse(url))) yuzey.kurtarmaDenendi = false
                // İKİNCİ DAMGA YOLU — bkz. [nesliDamgala]. `onPageCommitVisible` ÇİZİME bağlıdır
                // ve hiç çizilmeyen bir yüzeyde (ör. `View.GONE` panel) gecikebilir; `onPageFinished`
                // javadoc'a göre ana çerçeve yüklemesi için çizimden BAĞIMSIZ olarak çağrılır ve
                // commit'ten sonradır. Damga idempotenttir, iki yoldan gelmesi zararsızdır.
                nesliDamgala(view, url)
                onLoaded?.invoke()
            }

            /**
             * YENİ BELGE COMMIT OLDU → bu yüzey artık GÜNCEL nesle ait (bkz. [nesil]).
             *
             * Kanca `onPageStarted` DEĞİL: javadoc'u "a page has started loading" der ve `url`
             * parametresini "The url to be loaded" diye tanımlar — yani henüz commit YOKTUR, eski
             * belge hâlâ ekrandadır ve JS'i koşmaktadır. Orada damgalamak kapıyı tam da kapatmak
             * istediğimiz aralıkta açık bırakırdı.
             *
             * `onPageCommitVisible` javadoc'u ise tam bu anı tarif ediyor: "content left over from
             * previous page navigations will no longer be drawn … called at the earliest point at
             * which it can be guaranteed that WebView#onDraw will no longer draw any content from
             * previous navigations … called when the body of the HTTP response has started loading,
             * is reflected in the DOM". iOS'taki `didCommit`in Android karşılığı budur.
             */
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                nesliDamgala(view, url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // İKİNCİ KATMAN: `shouldOverrideUrlLoading` javadoc'a göre POST istekleri ve
                // `loadUrl` ile başlatılan gezinmeler için ÇAĞRILMAZ; bir form gönderimi saldırgan
                // belgesine SESSİZCE ulaşabilir. Adres çubuğu olmayan tam ekranda bu, marka
                // görünümlü kimlik-avı demektir — ana belge kendi origin'imizden çıktıysa geri alınır.
                if (url == null || url == "about:blank") return
                if (ayniOrigin(Uri.parse(url))) return
                view?.stopLoading()
                if (yuzey.kurtarmaDenendi) {
                    onLoadFailed?.invoke("sohbet adresi kendi sunucumuzun dışına yönlendiriyor")
                    return
                }
                yuzey.kurtarmaDenendi = true
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
     *  · SIZINTI: bu sınıf `Nsupp.webChat` üzerinden uygulama ömrü boyunca yaşar. Yüzey kaydı
     *    düşmezse WebView'ın `parent` zinciri (gömülü görünüm → … → DecorView) YOK EDİLMİŞ
     *    Activity'nin tüm görünüm ağacını canlı tutar. (Kayıt WebView'ı ZAYIF tutar, ama zayıf
     *    referans yalnız SIZINTIYI önler; WebView yok edilmeden ağdan/işlemciden çekilmez.)
     *  · YOKLAMA DURMAZ: sohbet sayfası (widget.js) kendi yoklamasını yapar. Yok edilmeyen bir
     *    WebView ekran kapandıktan sonra da yoklamayı sürdürür — pil, veri ve sunucu yükü.
     *
     * `wv` KİMLİK KARŞILAŞTIRMASI şart: yalnız KAPANAN yüzeyin kaydı düşer; aynı anda açık duran
     * başka bir yüzeyin kaydını da silmek onu sessizce komutsuz ve sıfırlanamaz bırakırdı.
     *
     * ÇAĞIRAN, WebView'ı görünüm ağacından ÖNCE çıkarır (`WebView.destroy` javadoc'u: "This method
     * should be called after this WebView has been removed from the view system").
     */
    fun destroyWebView(wv: WebView) {
        // Serbest bırakılmış kayıtlar da bu vesileyle temizlenir.
        yuzeyler.removeAll { it.webView === wv || it.webView == null }
        try {
            // Sürmekte olan yükleme `destroy`dan sonra geri-çağrım üretmesin.
            wv.stopLoading()
            wv.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView yok edilemedi: " + e)
        }
    }

    /**
     * Sohbeti belirli bir konuşmada aç (bildirimden derin bağlantı).
     *
     * Komut TEK yüzeye gider — EN SON yaratılan CANLI olana. Sıfırlamadan farkı burada: "çıkışta
     * her şey temizlensin" TÜM yüzeyleri ilgilendirir, "bildirime dokunulan konuşmayı aç" ise
     * kullanıcının o an baktığı yüzeyi.
     */
    fun openConversation(id: String) {
        val guvenli = JSONObject.quote(id)
        val wv = yuzeyler.lastOrNull { it.webView != null }?.webView ?: return
        wv.evaluateJavascript("window.\$nsupp && window.\$nsupp.push(['do','chat:open',$guvenli]);", null)
    }

    /**
     * Çıkışta: SAYFAYI sıfırla — jetonsuz, temiz bir belgeye dön. Paylaşılan cihazda sonraki
     * kullanıcı öncekinin sohbetini AÇMAMALI.
     *
     * `clearHistory()` TEK BAŞINA YETMİYORDU: yalnız geri/ileri yığınını siler. Jeton asıl olarak
     * WebView'ın localStorage'ında durur ve Android'de o depo DİSKE yazılır — süreç ölümünü de
     * uygulama yeniden açılışını da atlatır. Temizlik bu yüzden sayfanın kendi bağlamında,
     * sayfanın JS'i çalışmadan ÖNCE yapılır (bkz. [baslangicScripti]).
     *
     * TÜM YÜZEYLER: yalnız sonuncusunu yeniden yüklemek, hayalet kalan yüzeyin çıkıştan sonra da
     * eski oturumu göstermesi ve jetonu geri yazması demekti (bkz. [Yuzey]).
     *
     * ── KABUK JETONUNU BU METOT SİLMEZ ───────────────────────────────────────────────────────
     * Silme SERİ kanalın işidir (`NsuppSession.reset()`, bkz. `NsuppAndroid.kt`). Burası ANA iş
     * parçacığıdır ve iki kuyruk birbirine göre SIRASIZDIR: buradan `store.write(null)` demek,
     * ana iş parçacığı 100-500 ms meşgulken çıkış yapan kullanıcının silme işleminin, ARADA açılan
     * YENİ oturumun taze jetonunu (`start()` seri kanalda yazar) silmesi demekti — yeni kullanıcı
     * geçmişini kaybediyor, sunucuda öksüz ziyaretçi kalıyordu.
     *
     * Aynı sebeple depo OKUNMAZ da: [scriptiTazele] burada `cikis = true` ile çağrılır ve kimlik
     * script'i KOŞULSUZ "temizle + jeton yok" olur. Okusaydık yarışın hangi tarafta olduğuna göre
     * ya çıkan kullanıcının bayat jetonunu ya da yeni kullanıcının jetonunu hayalet yüzeye
     * taşırdık; çıkışta doğru içerik ikisinde de AYNI: temiz sayfa. Yeni jeton geldiğinde script
     * zaten tazelenir ([dinleyici]).
     *
     * ⚠️ ANA İŞ PARÇACIĞI. Burada WebView'a dokunuluyor; `Nsupp.reset()` bu çağrıyı ana iş
     * parçacığına postalar (bkz. sınıf belgesi).
     */
    fun reset() {
        // Ekranda duran belgelerin HEPSİ artık bayat: yenisi commit olana kadar hiçbiri jeton
        // yazamaz. Nesil ARTIŞI yeniden yükleme başlamadan ÖNCE yapılır — arada düşen bir mesaj
        // silinmiş oturumu geri getirmesin.
        nesil += 1
        yuzeyler.removeAll { it.webView == null }
        yuzeyler.forEach {
            it.belgeNesli = null
            // Sıfırlama taze bir yükleme başlatıyor: her yüzey kurtarma hakkını yeniden kazanır.
            it.kurtarmaDenendi = false
        }
        val canlilar = yuzeyler.filter { it.webView != null }
        if (canlilar.isEmpty()) {
            // Yapacak bir şey yok: bir sonraki `createWebView` zaten temizleyen script'i kurar
            // (o an kabuk deposu boştur — silme seri kanalda ağ beklemeden koşar)…
            if (originKurali != null && ozelliklerDestekli()) return
            // …ama köprü hiç kurulamıyorsa o script de olmayacak. SON ÇARE: `deleteAllData` bir
            // SINGLETON üzerinden çalışır ve UYGULAMA GENELİdir (satıcının kendi WebView verisi de
            // gider) — bu yüzden yalnız burada.
            try {
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                Log.w(TAG, "depo temizlenemedi: " + e)
            }
            return
        }
        // Script yeniden eklenince (çıkış: jeton KOŞULSUZ null) başına `localStorage.clear()` gelir
        // ve yeni belge yüklenmeden ÖNCE çalışır. Tazeleme yüklemelerden ÖNCE, TEK seferde.
        scriptiTazele(cikis = true)
        for (y in canlilar) {
            val wv = y.webView ?: continue
            wv.clearHistory()
            if (y.scriptHandler != null) {
                wv.loadUrl(hostUrl)
            } else {
                // `scriptHandler == null` = bu yüzeyde köprü kurulamadı (eski WebView sürümü ya da
                // kurulumda istisna): temizlenecek bir document-start script'i YOK, dolayısıyla
                // yalnız yeniden yüklemek çıkışı SESSİZCE yalan yapardı. Temizliği sayfa bağlamında
                // çalıştır ve BİTİNCE yeniden yükle — sıra garantisi geri-çağrımdan gelir.
                wv.evaluateJavascript(TEMIZLE_JS) { wv.loadUrl(hostUrl) }
            }
        }
    }

    /**
     * Bu yüzeydeki belgeyi GÜNCEL nesle damgala — artık jeton yazabilir.
     *
     * YALNIZ KENDİ ORIGIN'İMİZ: POST kaçışı sırasında yabancı bir belge de commit olabilir
     * (bkz. [onPageStarted] kurtarması). Köprü zaten origin'e bağlı olduğu için o belge mesaj
     * postalayamaz; damgalamamak kapıyı ikinci kez kapatır ve "damga = bizim belgemiz" değişmezini
     * korur.
     */
    private fun nesliDamgala(view: WebView?, url: String?) {
        if (view == null || url == null || url == "about:blank") return
        if (!ayniOrigin(Uri.parse(url))) return
        yuzeyler.firstOrNull { it.webView === view }?.belgeNesli = nesil
    }

    /** İki androidx yolu da destekleniyor mu — köprü ancak İKİSİ birden varsa kurulur. */
    private fun ozelliklerDestekli(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    } catch (_: Throwable) {
        false
    }

    private fun kopruyuKur(yuzey: Yuzey, wv: WebView) {
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
            yuzey.scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, baslangicScripti(), kurallar)
        } catch (e: Exception) {
            yuzey.scriptHandler = null
            Log.w(TAG, "köprü kurulamadı: " + e)
        }
    }

    /**
     * Sayfanın JS'inden ÖNCE çalışan kimlik script'i.
     *
     * DEĞİŞMEZ — **kabuk deposu tek doğruluk kaynağıdır**: kabukta jeton yoksa WebView'daki depo
     * BAYATTIR (çıkış yapıldı ya da uygulama silinip kuruldu) ve sayfa onu okumadan silinir.
     * Bu olmadan `widget.js` önce localStorage'a baktığı için ÖNCEKİ kullanıcının oturumu açılırdı.
     *
     * @param cikis ÇIKIŞ script'i: depoya HİÇ bakılmaz, jeton koşulsuz yok sayılır. Gerekçe
     *   [reset] belgesinde — çıkış anında depo iki kuyruğun yarışındadır, okumak hayalet yüzeye
     *   yanlış oturumu taşırdı.
     */
    private fun baslangicScripti(cikis: Boolean = false): String {
        val jeton = if (cikis) null else store.read()
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
     *
     * TÜM YÜZEYLER, her birinin KENDİ tutamacıyla: tutamaç tekil bir alanda tutulduğunda ikinci
     * `createWebView` birincininkini üzerine yazıyordu ve birinci yüzeyin script'i kurulduğu
     * andaki jetonla sonsuza kadar donuyordu.
     */
    private fun scriptiTazele(cikis: Boolean = false) {
        val kural = originKurali ?: return
        val kod = baslangicScripti(cikis)
        for (y in yuzeyler) {
            val wv = y.webView ?: continue
            if (y.scriptHandler == null) continue // köprü kurulmadı → tazelenecek script de yok
            try {
                y.scriptHandler?.remove()
                y.scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, kod, setOf(kural))
            } catch (e: Exception) {
                Log.w(TAG, "script tazelenemedi: " + e)
            }
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
            // NESİL KAPISI — mesajı gönderen BELGE hâlâ güncel oturuma mı ait?
            //
            // `reset()` nesli artırır ve her yüzeyin damgasını düşürür; damga yalnız yeni bir belge
            // COMMIT olduğunda geri konur. Yani çıkıştan sonra, henüz ölmemiş ESKİ belgeden düşen
            // bir `/session` yanıtı burada YOK SAYILIR — kabul edilseydi çıkan kullanıcının jetonu
            // depoya geri gelir ve tazelenen script onu yeni belgeye taşırdı.
            //
            // Yüzey bulunamazsa da yazmıyoruz (fail-closed): kaydı düşmüş bir WebView'dan gelen
            // mesajın hangi oturuma ait olduğunu söyleyemeyiz.
            val yuzey = yuzeyler.firstOrNull { it.webView === view } ?: return
            if (yuzey.belgeNesli != nesil) return
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
