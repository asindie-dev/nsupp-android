package com.nsupp.sdk.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nsupp.sdk.NsuppConfig
import com.nsupp.sdk.NsuppTokenStore
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `NsuppWebChat` — GERÇEK `WebView` ve GERÇEK `androidx.webkit` üzerinde, GERÇEK emülatörde.
 *
 * ── NİÇİN ENSTRÜMANLI TEST ───────────────────────────────────────────────────────────────────
 * Bu dosyanın bir önceki hâli elle yazılmış Android saplamalarına (`WebViewCompat.sifirla()`,
 * `WebViewFeature.destekli`, `wv.jsArayuzleri` gibi gerçek API'de VAR OLMAYAN üyeler) karşı
 * yazılmıştı. Gerçek Gradle derlemesi açılınca 14 testin tamamı "Unresolved reference" ile düştü:
 * yani o testler Android'i değil TAKLİDİ test ediyordu ve HİÇBİR ŞEY kanıtlamamıştı. Kabuğun
 * kanıtlanacak tek şeyi — köprünün hangi origin'e açıldığı — zaten yalnız gerçek WebView'da
 * gözlenebilir; bu yüzden testler buraya taşındı.
 *
 * ── BU DOSYA NEYİ KANITLIYOR ─────────────────────────────────────────────────────────────────
 *  1. Köprü (`window.NsuppApp` + `NsuppNative`) yalnız KENDİ origin'imizin belgelerinde görünür;
 *     BAŞKA origin'deki bir alt çerçeve ikisini de GÖREMEZ. (İki ayrı yerel sunucu = iki origin.)
 *  2. Kendi origin'imizdeki bir ALT ÇERÇEVE köprüyü görür (origin kuralı çerçeve başınadır) ama
 *     jetonu YAZAMAZ — `isMainFrame` kapısı gerçek geri-çağrımda çalışıyor.
 *  3. "İçeride miyiz" kararı ÖNEK değil ORIGIN karşılaştırması: userinfo, alt alan adı öneki,
 *     şema değişimi, port, sondaki nokta, punycode varyantları DIŞARIDA sayılıyor.
 *     Bu iddia MUTASYONLA ölçüldü: `ayniOrigin` bilerek `startsWith(config.base)` yapıldığında
 *     hem matris hem gerçek-tıklama testi DÜŞÜYOR. (İlk yazımda düşmüyordu — bkz. o testteki not.)
 *  4. Dış bağlantı şema allowlist'i: `intent:`/`javascript:`/`file:`/`content:`/özel şema
 *     sistem tarayıcısına da WebView'a da GİTMİYOR.
 *  5. POST ile kaçış geri alınıyor (gerçek form gönderimi — `shouldOverrideUrlLoading` POST için
 *     ÇAĞRILMAZ, dolayısıyla bunu yalnız gerçek gezinme gösterir).
 *  6. `reset()` WebView'ın localStorage'ını GERÇEKTEN siliyor; jeton gelince script tazeleniyor ve
 *     tazelenen script depoyu SİLMİYOR.
 *  7. Kabukta jeton yokken bir sonraki açılışta ÖNCEKİ oturumun deposu siliniyor.
 *  8. Yüzey kapanınca WebView `destroy()` ediliyor ve alan yalnız SAHİBİ tarafından bırakılıyor.
 *
 * ── BU DOSYA NEYİ KANITLAMIYOR (dürüst sınır) ────────────────────────────────────────────────
 *  · FAIL-CLOSED DALI: "WebView sürümü `DOCUMENT_START_SCRIPT`/`WEB_MESSAGE_LISTENER`
 *    desteklemiyorsa köprü HİÇ kurulmaz". Saplamalı sürümde bu "kanıtlanmış" sayılıyordu
 *    (`WebViewFeature.destekli = false` diye bir alan uydurulmuştu). Gerçek `WebViewFeature`
 *    statik ve cihazın WebView sürümünden okur; testten zorlanamaz. Güncel WebView'lı bir
 *    emülatörde özellikler HER ZAMAN desteklidir, dolayısıyla bu dal burada KOŞMAZ. Kanıtlanması
 *    için WebView'ı eski bir sürüme düşürmüş bir cihaz gerekir — elimizde yok, uydurmuyoruz.
 *  · `originKurali == null` dalı (apiBase bir http(s) origin'ine ayrışmıyorsa) da aynı sebeple
 *    burada değil: `createWebView` o durumda gerçek bir sayfa yükleyemez.
 *  · Gezinme MATRİSİ (madde 3 ve 4) gerçek `WebViewClient`e — `wv.webViewClient` ile WebView'dan
 *    GERİ OKUNAN, üretimde atanmış olan istemciye — doğrudan çağrı yapar; `WebResourceRequest`
 *    testte uygulanır. Bu bir saplama DEĞİLDİR (gerçek `android.webkit` arayüzüdür, imzasını
 *    derleyici zorlar), ama WebView'ın o istemciyi gerçekten çağırdığını KENDİ BAŞINA göstermez.
 *    Onu `dis_baglanti_gercek_tiklamada_tarayiciya_gider` gerçek gezinmeyle gösteriyor.
 */
@RunWith(AndroidJUnit4::class)
class NsuppWebChatTest {

    private val acilanlar = mutableListOf<WebView>()
    private val sunucular = mutableListOf<YerelSunucu>()
    private lateinit var senaryo: ActivityScenario<BosAktivite>

    @Before
    fun hazirla() {
        // WebView GÖRÜNÜM AĞACINA eklenir — üretimdeki gibi. Sebep: bkz. BosAktivite.
        senaryo = ActivityScenario.launch(BosAktivite::class.java)
    }

    @After
    fun temizle() {
        anaIsParcaciginda {
            acilanlar.forEach {
                // Üretimin sözleşmesi: `destroy` ÖNCE görünüm ağacından çıkarılır.
                (it.parent as? ViewGroup)?.removeView(it)
                try {
                    it.destroy()
                } catch (_: Exception) {
                }
            }
        }
        acilanlar.clear()
        senaryo.close()
        sunucular.forEach { it.kapat() }
        sunucular.clear()
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────

    /** Yazılan her değeri tutar — `reset()`in gerçekten null yazdığı iddiası buna dayanır. */
    private class KayitliDepo(private var deger: String? = null) : NsuppTokenStore {
        val yazilanlar: MutableList<String?> = mutableListOf()
        override fun read(): String? = deger
        override fun write(token: String?) {
            deger = token
            yazilanlar.add(token)
        }
    }

    /**
     * `startActivity`yi YAKALAYAN bağlam.
     *
     * Gerçek bir `ContextWrapper`dır (Android sınıfı, taklit değil) — `applicationContext` gerçek
     * uygulama bağlamını döndürür, dolayısıyla WebView gerçek olur. Yalnız `startActivity`
     * kaydedilir: yoksa test koşarken emülatörde tarayıcı/telefon uygulaması açılır ve sonraki
     * ölçümler bozulurdu.
     */
    private class KayitliBaglam(temel: Context) : ContextWrapper(temel) {
        val niyetler: MutableList<Intent> = mutableListOf()
        override fun startActivity(intent: Intent) {
            niyetler.add(intent)
        }
    }

    /**
     * GERÇEK `android.webkit.WebResourceRequest` uygulaması.
     *
     * Saplama değil: arayüz platformun kendisidir ve eksik/yanlış imza derlemeyi düşürür. Testin
     * kendi ürettiği tek şey isteğin İÇERİĞİdir (url + ana çerçeve mi).
     */
    private class Istek(private val adres: Uri, private val anaCerceve: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = adres
        override fun isForMainFrame(): Boolean = anaCerceve
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private class Kurulum(
        val chat: NsuppWebChat,
        val wv: WebView,
        val baglam: KayitliBaglam,
        val depo: KayitliDepo,
        val sunucu: YerelSunucu,
    )

    private fun anaIsParcaciginda(blok: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(blok)

    /**
     * Ana looper kuyruğunu boşaltır — İKİ TUR, ve bu bilinçli.
     *
     * Kabuk jetonu aldığında tazelemeyi `view.post { … }` ile KUYRUĞA ALIR (geri-çağrımın içinde
     * dinleyici/script topolojisini değiştirmemek için). Test jetonu depoda gördüğü anda ana iş
     * parçacığı hâlâ `onPostMessage`in İÇİNDE olabilir; o hâlde tazeleme henüz kuyruğa bile
     * girmemiştir. Tek tur beklemek yalnız "o ana kadar kuyruğa girmiş işler" için garanti verir:
     *  · 1. tur → `onPostMessage` bitti, tazeleme artık kuyrukta.
     *  · 2. tur → tazeleme koştu.
     * Uyku (`sleep`) yerine bu kullanılıyor: uyku bir TAHMİNDİR, kuyruk turu bir GARANTİDİR.
     */
    private fun anaKuyruguBosalt() {
        anaIsParcaciginda {}
        anaIsParcaciginda {}
    }

    private fun sunucuAc(): YerelSunucu = YerelSunucu().also { sunucular.add(it) }

    private val hostYolu = "/widget/pk_test/app"

    /** Kabuğun beklediği host sayfası + testlerin okuduğu ortak işaretler. */
    private fun hostSayfasi(govde: String = ""): String = """
        <!doctype html><html><head><meta charset="utf-8"></head><body>
        <script>
          // Kimlik script'i belge-başında koştuğu için burada zaten hazır olmalı.
          window.__kimlik = JSON.stringify(window.NsuppApp || null);
          window.__native = typeof window.NsuppNative;
          window.__cerceveler = [];
          window.addEventListener('message', function (e) { window.__cerceveler.push(String(e.data)); });
          // `openConversation` bunu çağırır; kaydediyoruz ki komutun HANGİ yüzeye gittiği görülsün.
          window.${'$'}nsupp = { push: function (a) { (window.__itilenler = window.__itilenler || []).push(JSON.stringify(a)); } };
        </script>
        $govde
        </body></html>
    """.trimIndent()

    private fun ac(sunucu: YerelSunucu, jeton: String? = null): Kurulum {
        val depo = KayitliDepo(jeton)
        val config = NsuppConfig(apiBase = sunucu.kok, publicKey = "pk_test", appKey = "nsupp_app_123")
        val chat = NsuppWebChat(config, depo)
        val baglam = KayitliBaglam(InstrumentationRegistry.getInstrumentation().targetContext)
        // WebView ANA İŞ PARÇACIĞINDA kurulur (platformun sözleşmesi) ve üretimdeki gibi görünüm
        // ağacına EKLENİR — `NsuppChatActivity` de `kok.addView(wv)` yapıyor.
        val wv = webViewAc { chat.createWebView(baglam) }
        return Kurulum(chat, wv, baglam, depo, sunucu)
    }

    /** WebView'ı ana iş parçacığında yaratır, Activity'ye ekler ve temizlik listesine alır. */
    private fun webViewAc(uret: () -> WebView): WebView {
        lateinit var wv: WebView
        senaryo.onActivity { aktivite ->
            wv = uret()
            aktivite.kok.addView(wv)
        }
        acilanlar.add(wv)
        return wv
    }

    /** Belgedeki damga sunucunun servis ettiği son damgaya eşitlenene kadar bekler. */
    private fun sayfaBekle(wv: WebView, sunucu: YerelSunucu, yol: String = hostYolu) {
        bekle("$yol yüklenmedi") {
            val beklenen = sunucu.sonDamga(yol)
            beklenen > 0 && js(wv, "window.__damga") == beklenen.toString()
        }
    }

    private fun bekle(mesaj: String, saniye: Long = 20, kosul: () -> Boolean) {
        val bitis = System.currentTimeMillis() + saniye * 1000
        while (System.currentTimeMillis() < bitis) {
            if (kosul()) return
            Thread.sleep(100)
        }
        throw AssertionError("zaman aşımı: $mesaj")
    }

    /** `evaluateJavascript` — ana iş parçacığında çağrılır, sonucu JSON metni olarak döner. */
    private fun js(wv: WebView, kod: String): String {
        val kutu = arrayOfNulls<String>(1)
        val kilit = CountDownLatch(1)
        anaIsParcaciginda {
            wv.evaluateJavascript(kod) { sonuc ->
                kutu[0] = sonuc
                kilit.countDown()
            }
        }
        assertTrue("JS sonucu gelmedi: $kod", kilit.await(15, TimeUnit.SECONDS))
        return kutu[0] ?: "null"
    }

    /** JSON metnini çözer: `"abc"` → `abc`, `null` → null. */
    private fun jsMetin(wv: WebView, kod: String): String? {
        val ham = js(wv, kod)
        if (ham == "null" || ham == "undefined") return null
        return org.json.JSONArray("[$ham]").optString(0, "")
    }

    private fun adres(wv: WebView): String? {
        val kutu = arrayOfNulls<String>(1)
        anaIsParcaciginda { kutu[0] = wv.url }
        return kutu[0]
    }

    private fun istemci(wv: WebView): WebViewClient {
        val kutu = arrayOfNulls<WebViewClient>(1)
        // İstemci WebView'DAN GERİ OKUNUR: testin elinde tuttuğu nesne değil, üretimin gerçekten
        // ATADIĞI nesne çağrılsın.
        anaIsParcaciginda { kutu[0] = wv.webViewClient }
        return kutu[0]!!
    }

    // ── 1) Köprü ORIGIN'e bağlıdır ───────────────────────────────────────────────────────────

    @Test
    fun kopru_yalniz_kendi_origininin_ANA_cercevesinde_gorunur() {
        val bizim = sunucuAc()
        val yabanci = sunucuAc()

        yabanci.koy(
            "/yabanci",
            """<script>parent.postMessage('yabanci:NsuppApp=' + (typeof window.NsuppApp) +
               ',NsuppNative=' + (typeof window.NsuppNative), '*');</script>"""
        )
        bizim.koy(
            "/altcerceve",
            """<script>
               parent.postMessage('alt:NsuppApp=' + (typeof window.NsuppApp) +
                 ',NsuppNative=' + (typeof window.NsuppNative), '*');
               try { NsuppNative.postMessage(JSON.stringify({ type: 'visitorToken', token: 'ALT-CERCEVE' })); } catch (e) {}
               </script>"""
        )
        bizim.koy(
            hostYolu,
            hostSayfasi(
                """<iframe src="/altcerceve"></iframe><iframe src="${yabanci.kok}/yabanci"></iframe>"""
            )
        )

        val k = ac(bizim, jeton = "jeton-1")
        sayfaBekle(k.wv, bizim)

        // (a) Ana çerçeve: kimlik script'i koştu, gerçek anahtar ve gerçek jeton içeride.
        val kimlik = jsMetin(k.wv, "window.__kimlik") ?: ""
        assertTrue(kimlik, kimlik.contains("nsupp_app_123"))
        assertTrue(kimlik, kimlik.contains("jeton-1"))
        // (b) Mesaj kanalı da ana çerçevede var.
        assertEquals("object", jsMetin(k.wv, "window.__native"))

        bekle("çerçeve raporları gelmedi") { js(k.wv, "window.__cerceveler.length") == "2" }
        val raporlar = jsMetin(k.wv, "JSON.stringify(window.__cerceveler)") ?: ""

        // (c) BAŞKA origin'deki alt çerçeve köprüyü GÖREMEZ — `addJavascriptInterface` kullanılsaydı
        //     görürdü (javadoc: nesne sayfanın TÜM çerçevelerine enjekte edilir).
        assertTrue(raporlar, raporlar.contains("yabanci:NsuppApp=undefined,NsuppNative=undefined"))
        // (d) KENDİ origin'imizdeki alt çerçeve köprüyü GÖRÜR (origin kuralı çerçeve başınadır)…
        assertTrue(raporlar, raporlar.contains("alt:NsuppApp=object,NsuppNative=object"))
        // (e) …ama jetonu YAZAMAZ: `isMainFrame` kapısı gerçek geri-çağrımda uyguluyor.
        assertEquals(emptyList<String?>(), k.depo.yazilanlar)
    }

    // ── 2) "İçeride miyiz" kararı ORIGIN karşılaştırmasıdır ──────────────────────────────────

    @Test
    fun gezinme_origin_karsilastirir_onek_degil() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)
        val c = istemci(k.wv)

        for (icerde in listOf("${bizim.kok}$hostYolu", "${bizim.kok}/x?q=1#f")) {
            assertFalse(icerde, c.shouldOverrideUrlLoading(k.wv, Istek(Uri.parse(icerde), true)))
        }

        // İLK İKİSİ MUTASYONLA SEÇİLDİ: `ayniOrigin` bilerek `startsWith(config.base)` yapılınca
        // listenin geri kalanı testi GEÇİRİYORDU — çünkü taban bir PORT taşıdığı için
        // "http://127.0.0.1.evil.example" zaten önekle eşleşmiyor. Yani test, kapattığını iddia
        // ettiği kusuru YAKALAMIYORDU. Aşağıdaki iki adres tam olarak öneki geçen ama origin'i
        // BAŞKA olan biçimlerdir; ikisi de gerçek saldırı biçimi.
        val disarida = listOf(
            "${bizim.kok}@evil.example/x",           // userinfo — `Uri.getHost` bunu ELER, önek ELEMEZ
            "${bizim.kok}0/x",                       // portun DEVAMI — önekle eşleşir, port BAŞKA
            "http://127.0.0.1@evil.example/x",       // userinfo (portsuz biçim)
            "http://127.0.0.1.evil.example/x",       // alt alan adı öneki
            "http://127.0.0.1.EVIL.example/",        // büyük harf varyantı
            "https://127.0.0.1/x",                   // şema yükseltme (taban http)
            "http://127.0.0.1:1/x",                  // farklı port
            "http://127.0.0.1./x",                   // sondaki nokta
            "http://xn--pi-xmc.example/",            // IDN/punycode
        )
        for (url in disarida) {
            assertTrue(url, c.shouldOverrideUrlLoading(k.wv, Istek(Uri.parse(url), true)))
        }
        // Hiçbiri WebView'ın İÇİNE alınmadı: belge hâlâ kendi sayfamız.
        assertEquals("${bizim.kok}$hostYolu", adres(k.wv))
        // Hepsi http(s) olduğu için sistem tarayıcısına yollandı.
        assertEquals(disarida.size, k.baglam.niyetler.size)
    }

    @Test
    fun alt_cerceve_disa_acmaz_ve_uygulama_tetiklemez() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)
        val c = istemci(k.wv)

        // Alt çerçeve dışarı çıkamaz AMA uygulama da açılmaz: gizli bir iframe kullanıcı
        // DOKUNMADAN başka uygulamayı tetiklerdi.
        assertTrue(c.shouldOverrideUrlLoading(k.wv, Istek(Uri.parse("https://evil.example/x"), false)))
        assertEquals(0, k.baglam.niyetler.size)
    }

    @Test
    fun sema_allowlisti_disariya_da_iceriye_de_gecirmez() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)
        val c = istemci(k.wv)

        val yasak = listOf(
            "bankauygulamasi://transfer?to=x",
            "intent://x#Intent;scheme=http;end",
            "javascript:alert(1)",
            "file:///data/data/x/y",
            "content://com.x/y",
        )
        for (url in yasak) {
            assertTrue(url, c.shouldOverrideUrlLoading(k.wv, Istek(Uri.parse(url), true)))
        }
        assertEquals(0, k.baglam.niyetler.size)
        assertEquals("${bizim.kok}$hostYolu", adres(k.wv))

        val izinli = listOf("https://haber.example/x", "mailto:a@b.c", "tel:+905550000000")
        for (url in izinli) {
            assertTrue(url, c.shouldOverrideUrlLoading(k.wv, Istek(Uri.parse(url), true)))
        }
        assertEquals(izinli, k.baglam.niyetler.map { it.data.toString() })
        for (niyet in k.baglam.niyetler) {
            // CATEGORY_BROWSABLE: hedef kümesi "bağlantıdan açılmayı kabul etmiş" bileşenlerle sınırlı.
            assertTrue(niyet.data.toString(), niyet.categories.contains(Intent.CATEGORY_BROWSABLE))
        }
    }

    @Test
    fun dis_baglanti_gercek_tiklamada_tarayiciya_gider() {
        // MATRİSİN AYAĞI YERE BASSIN: yukarıdaki testler istemciyi DOĞRUDAN çağırıyor. Burada
        // gerçek bir gezinme başlatılıyor — WebView'ın süzgeci gerçekten çağırdığı böyle görülür.
        val bizim = sunucuAc()
        // Adres ÖNEK testini geçen biçimde: gerçek gezinmede de origin karşılaştırması yapılmalı.
        bizim.koy(hostYolu, hostSayfasi("""<a id="dis" href="${bizim.kok}@evil.example/x">dış</a>"""))
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        js(k.wv, "document.getElementById('dis').click(); 1")

        bekle("dış bağlantı sisteme yollanmadı") { k.baglam.niyetler.size == 1 }
        val gidenAdres = k.baglam.niyetler.single().data.toString()
        assertTrue(gidenAdres, gidenAdres.contains("evil.example"))
        // Belge DEĞİŞMEDİ: dış adres WebView'ın içinde açılmadı.
        assertEquals("${bizim.kok}$hostYolu", adres(k.wv))
    }

    // ── 3) POST ile kaçış geri alınır ────────────────────────────────────────────────────────

    @Test
    fun post_ile_kacis_geri_alinir() {
        val bizim = sunucuAc()
        val yabanci = sunucuAc()
        yabanci.koy("/kacis", "<script>window.__sayfa='kacis';</script>")

        // İLK servis: kendini POST'la dışarı atan form. Sonraki servisler düz sayfa — yoksa
        // kurtarma sonsuz döngüye girer ve test hiçbir zaman durulmazdı.
        val ilkMi = java.util.concurrent.atomic.AtomicBoolean(true)
        bizim.koy(hostYolu) {
            if (ilkMi.getAndSet(false)) {
                hostSayfasi(
                    """<form id="f" method="post" action="${yabanci.kok}/kacis"><input name="x" value="1"></form>
                       <script>window.__sayfa='form';document.getElementById('f').submit();</script>"""
                )
            } else {
                hostSayfasi("<script>window.__sayfa='duz';</script>")
            }
        }

        val k = ac(bizim)

        // `shouldOverrideUrlLoading` POST için ÇAĞRILMAZ (javadoc); kaçış ancak `onPageStarted`
        // katmanında yakalanır. Sonuç: belge kendi sayfamıza geri döner.
        bekle("POST kaçışından geri dönülmedi") { jsMetin(k.wv, "window.__sayfa") == "duz" }
        assertEquals("${bizim.kok}$hostYolu", adres(k.wv))
        // POST gerçekten gönderilmişti — yani senaryo taklit değil.
        assertTrue(yabanci.istekler.toString(), yabanci.istekler.contains("POST /kacis"))
    }

    // ── 4) reset() WebView deposunu GERÇEKTEN siler ──────────────────────────────────────────

    @Test
    fun reset_localStorage_i_gercekten_siler() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim, jeton = "A-jetonu")
        sayfaBekle(k.wv, bizim)

        // Jeton VARKEN temizleme olmamalı: script sayfanın deposuna dokunmadan kimliği verir.
        assertEquals("v", jsMetin(k.wv, "localStorage.setItem('k','v'); localStorage.getItem('k')"))

        val oncekiDamga = bizim.sonDamga(hostYolu)
        anaIsParcaciginda { k.chat.reset() }

        bekle("reset sonrası sayfa yeniden yüklenmedi") { bizim.sonDamga(hostYolu) > oncekiDamga }
        sayfaBekle(k.wv, bizim)

        assertEquals(listOf<String?>(null), k.depo.yazilanlar)
        // Jeton diskteki localStorage'da duruyordu; `clearHistory` tek başına onu SİLMEZDİ.
        assertNull(jsMetin(k.wv, "localStorage.getItem('k')"))
        val kimlik = jsMetin(k.wv, "window.__kimlik") ?: ""
        assertTrue(kimlik, kimlik.contains("\"visitorToken\":null"))
    }

    @Test
    fun jeton_yazilinca_script_tazelenir_ve_depoyu_SILMEZ() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim, jeton = null)
        sayfaBekle(k.wv, bizim)

        // Jeton yokken açılışta depo temizlenir; temizlikten SONRA yazdığımız değer, tazeleme
        // doğru çalışırsa yaşamalı.
        assertEquals("v", jsMetin(k.wv, "localStorage.setItem('k','v'); localStorage.getItem('k')"))

        js(k.wv, "NsuppNative.postMessage(JSON.stringify({type:'visitorToken',token:'YENI'})); 1")
        bekle("jeton kabuğa ulaşmadı") { k.depo.yazilanlar == listOf<String?>("YENI") }
        anaKuyruguBosalt() // tazeleme `view.post` ile kuyruğa alınır; yeniden yüklemeden ÖNCE koşmalı

        val oncekiDamga = bizim.sonDamga(hostYolu)
        anaIsParcaciginda { k.wv.loadUrl(k.chat.hostUrl) }
        bekle("yeniden yükleme olmadı") { bizim.sonDamga(hostYolu) > oncekiDamga }
        sayfaBekle(k.wv, bizim)

        // Script tazelenmeseydi BAYAT script koşardı: "temizle + visitorToken:null" — yeni oturum
        // ve sayfa deposu birlikte silinirdi.
        val kimlik = jsMetin(k.wv, "window.__kimlik") ?: ""
        assertTrue(kimlik, kimlik.contains("YENI"))
        assertEquals("v", jsMetin(k.wv, "localStorage.getItem('k')"))
    }

    @Test
    fun bayat_depo_bir_sonraki_acilista_silinir() {
        // Süreç ölümü / uygulama silinip kurulma yolu: kabukta jeton YOK → WebView'ın diskteki
        // deposu BAYATTIR ve sayfa onu OKUMADAN silinmeli.
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())

        val ilk = ac(bizim, jeton = "A-jetonu")
        sayfaBekle(ilk.wv, bizim)
        assertEquals("v", jsMetin(ilk.wv, "localStorage.setItem('k','v'); localStorage.getItem('k')"))
        anaIsParcaciginda {
            (ilk.wv.parent as? ViewGroup)?.removeView(ilk.wv) // üretimin sözleşmesi: önce ağaçtan çıkar
            ilk.chat.destroyWebView(ilk.wv)
        }

        // Aynı origin, YENİ kabuk, jeton yok.
        val ikinci = ac(bizim, jeton = null)
        sayfaBekle(ikinci.wv, bizim)
        assertNull(jsMetin(ikinci.wv, "localStorage.getItem('k')"))
    }

    // ── 5) Yüzey kapanınca WebView YOK EDİLİR ────────────────────────────────────────────────

    @Test
    fun yok_etme_yalniz_SAHIBININ_alanini_birakir() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim, jeton = "jeton-1")
        sayfaBekle(k.wv, bizim)

        // İkinci yüzey açıldı: `webView` alanı ARTIK ona ait.
        val ikinci = webViewAc { k.chat.createWebView(k.baglam) }
        sayfaBekle(ikinci, bizim)

        anaIsParcaciginda {
            (k.wv.parent as? ViewGroup)?.removeView(k.wv) // üretimin sözleşmesi: önce ağaçtan çıkar
            k.chat.destroyWebView(k.wv) // kapanan BİRİNCİ yüzey
        }

        // Yok edilmiş WebView artık hiçbir şey bildirmez — `destroy()` gerçekten çağrıldı.
        assertNull(adres(k.wv))

        // Komutlar hâlâ CANLI yüzeye gidiyor. Kimlik karşılaştırması olmasaydı alan koşulsuz
        // null'lanır ve açık duran sohbet SESSİZCE komutsuz kalırdı.
        anaIsParcaciginda { k.chat.openConversation("konusma-1") }
        bekle("komut canlı yüzeye ulaşmadı") { js(ikinci, "(window.__itilenler||[]).length") == "1" }
        val itilen = jsMetin(ikinci, "JSON.stringify(window.__itilenler)") ?: ""
        assertTrue(itilen, itilen.contains("chat:open") && itilen.contains("konusma-1"))
    }
}
