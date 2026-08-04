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
 *  6b. `reset()` KABUK deposuna hiç dokunmuyor (ne okuyor ne yazıyor): silme seri kanalın işidir,
 *     bu yüzden geç drenaj olan bir çıkış YENİ oturumun taze jetonunu EZEMİYOR.
 *  6c. Kurtarma bayrağı YÜZEY BAŞINA: bir yüzeyin kaçış hakkını tüketmesi diğerininkini
 *     tüketmiyor.
 *  7. Kabukta jeton yokken bir sonraki açılışta ÖNCEKİ oturumun deposu siliniyor.
 *  8. Yüzey kapanınca WebView `destroy()` ediliyor ve kayıt yalnız SAHİBİ tarafından düşürülüyor.
 *  9. NESİL KAPISI: `reset()` sonrası, yeni belge henüz commit olmamışken ESKİ belgenin postaladığı
 *     jeton REDDEDİLİYOR — ama yeni belge commit olunca yazma yeniden AÇILIYOR.
 * 10. YÜZEY KAYDI: iki yüzey açıkken `reset()` İKİSİNİ birden sıfırlıyor ve her yüzeyin kimlik
 *     script'i AYRI tutamaçla tazeleniyor.
 * 11. Şema allowlist'i GERÇEK TIKLAMADA da geçirmiyor (izinli şema pozitif kontrolüyle birlikte).
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
 *  · NESİL DAMGASI HANGİ GERİ-ÇAĞRIMDAN GELDİ: damga iki yerden konuyor — `onPageCommitVisible`
 *    (dokümante commit anı) ve `onPageFinished` (çizilmeyen yüzeyler için emniyet ağı). Madde 9
 *    "yeni belge yazabiliyor"u kanıtlar, hangisinin damgaladığını AYIRT ETMEZ. Ayırt etmek için
 *    "commit oldu ama yükleme bitmedi" anında mesaj postalamak gerekirdi; o an testten
 *    deterministik olarak yakalanamıyor (geri-çağrımın ana iş parçacığına düşmesi sayfanın
 *    script'iyle sıralı değil) ve tek çare tekrar denemek olurdu — o da kusuru maskelerdi.
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

    /** Yazılan her değeri SIRAYLA tutar — "kim depoya ne yazdı" iddiaları buna dayanır. */
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

        // KABUK deposuna DOKUNULMAZ. Silme seri kanalın işidir (`NsuppSession.reset()`); buradan
        // yazmak, geç drenaj olan çıkışın YENİ oturumun jetonunu ezmesi demekti (bkz. aşağıdaki
        // `reset_kabuk_deposuna_DOKUNMAZ…` testi).
        assertEquals(emptyList<String?>(), k.depo.yazilanlar)
        assertEquals("A-jetonu", k.depo.read())
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

    /**
     * A-K1 — ÇIKIŞTAN SONRA UÇUŞTA KALAN ESKİ BELGE JETONU GERİ YAZAMAZ.
     *
     * Kusur ölçülebilir bir pencerede yaşıyordu: `reset()` yeniden yüklemeyi BAŞLATIR ama yeni
     * belge COMMIT olana kadar eski belge ekranda kalır ve JS'i koşar. O aralıkta düşen bir
     * `/session` yanıtı köprüye jetonu postalıyor, kabuk da onu koşulsuz `store.write(jeton)`
     * yapıyordu — çıkış yapan kullanıcının oturumu geri geliyordu.
     *
     * Pencere burada TAHMİNLE değil sunucuyu askıya alarak açılıyor: yeniden yükleme isteği
     * sunucuya ulaşır, yanıt VERİLMEZ, dolayısıyla eski belge kesin olarak hâlâ ekrandadır.
     */
    @Test
    fun reset_ucustaki_ESKI_belgenin_jetonunu_kabul_etmez() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim, jeton = "A-jetonu")
        sayfaBekle(k.wv, bizim)

        bizim.bekletmeAc(hostYolu)
        val oncekiIstek = bizim.istekSayisi("GET $hostYolu")
        anaIsParcaciginda { k.chat.reset() }
        bekle("sıfırlamanın yeniden yükleme isteği sunucuya ulaşmadı") {
            bizim.istekSayisi("GET $hostYolu") == oncekiIstek + 1
        }

        // ESKİ belge gerçekten ayakta ve köprüyü GÖRÜYOR — senaryo taklit değil.
        assertEquals("object", jsMetin(k.wv, "window.__native"))
        js(k.wv, "NsuppNative.postMessage(JSON.stringify({type:'visitorToken',token:'ESKI'})); 1")
        anaKuyruguBosalt()
        // Depoya HİÇ yazılmamış olmalı: ESKİ belgenin jetonu nesil kapısında reddedildi,
        // sıfırlamanın kendisi ise depoya artık dokunmuyor (silme seri kanalın işi).
        assertEquals(emptyList<String?>(), k.depo.yazilanlar)

        // KAPI SÜREKLİ KAPALI DEĞİL. Bu ikinci yarı olmadan `onPostMessage`in başına konmuş düz bir
        // `return` de testi geçerdi — yani test kapattığını iddia ettiği kusuru kanıtlamazdı.
        //
        // BARİYER `onLoaded` — damga ile AYNI geri-çağrımdan gelir, dolayısıyla "yeni belge artık
        // güncel nesle ait" anı kesin olarak GEÇMİŞTİR. Sunucu damgasını beklemek YETMİYORDU:
        // sayfanın kendi script'i koştuğunda kabuğun geri-çağrımı henüz ana iş parçacığına
        // düşmemiş olabiliyor ve test, kusur olmadığı hâlde düşüyordu (ölçüldü).
        val yuklendi = CountDownLatch(1)
        anaIsParcaciginda { k.chat.onLoaded = { yuklendi.countDown() } }
        bizim.bekletmeSurdur(hostYolu)
        assertTrue("askı kalkınca yeni belge yüklenmedi", yuklendi.await(20, TimeUnit.SECONDS))
        sayfaBekle(k.wv, bizim)

        js(k.wv, "NsuppNative.postMessage(JSON.stringify({type:'visitorToken',token:'YENI'})); 1")
        bekle("commit olmuş YENİ belge jeton yazamadı") {
            k.depo.yazilanlar == listOf<String?>("YENI")
        }
    }

    /**
     * A-T2 — SIFIRLAMA KABUK DEPOSUNA DOKUNMAZ: GEÇ DRENAJ YENİ OTURUMU EZEMEZ.
     *
     * `Nsupp.reset()` iki yarıya ayrılır ve yarılar AYRI kuyruklardadır: oturum sıfırlaması seri IO
     * kanalında, sayfa sıfırlaması ANA iş parçacığında. İkisi de depoya yazdığı sürece şu kayıp
     * gerçekti: ana iş parçacığı ekran geçişinde 100-500 ms meşgulken yeni kullanıcı sohbeti açar,
     * `start()` seri kanalda taze jetonu yazar, SONRA bekleyen sayfa sıfırlaması drenaj olup
     * `store.write(null)` ile onu SİLERDİ — yeni kullanıcı oturumunu ve geçmişini kaybeder,
     * sunucuda öksüz ziyaretçi kalırdı.
     *
     * Yarış burada TAHMİNLE değil SIRAYLA kuruluyor: "yeni oturumun jetonu" sıfırlama çağrısından
     * ÖNCE depoya yazılıyor, yani sıfırlama tam da geç drenaj olan yarıyı temsil ediyor.
     */
    @Test
    fun reset_kabuk_deposuna_DOKUNMAZ_yeni_oturumun_jetonunu_ezmez() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim, jeton = "A-jetonu")
        sayfaBekle(k.wv, bizim)

        // ÇIKIŞ BAŞLADI ve arada YENİ oturum açıldı (seri kanal jetonu yazdı).
        k.depo.write("vt_YENI")
        val yazilanSayisi = k.depo.yazilanlar.size

        val oncekiDamga = bizim.sonDamga(hostYolu)
        anaIsParcaciginda { k.chat.reset() } // …ve sayfa sıfırlaması ANCAK ŞİMDİ drenaj oldu
        bekle("reset sonrası sayfa yeniden yüklenmedi") { bizim.sonDamga(hostYolu) > oncekiDamga }
        sayfaBekle(k.wv, bizim)

        assertEquals("YENİ oturumun jetonu silindi", "vt_YENI", k.depo.read())
        assertEquals("reset depoya yazdı", yazilanSayisi, k.depo.yazilanlar.size)

        // Depo OKUNMUYOR da: hayalet yüzey yeni kullanıcının jetonunu taşımaz, temiz açılır.
        // (Okusaydık yarışın hangi tarafta olduğuna göre ya bayat ya da yabancı oturum yüklenirdi.)
        val kimlik = jsMetin(k.wv, "window.__kimlik") ?: ""
        assertTrue(kimlik, kimlik.contains("\"visitorToken\":null"))
    }

    /**
     * A-T3 — KURTARMA BAYRAĞI YÜZEY BAŞINADIR (iki yönü de ölçülür).
     *
     * Ana belge kendi origin'imizin dışına kaçarsa (POST ile — `shouldOverrideUrlLoading` o yolda
     * ÇAĞRILMAZ) kabuk yüklemeyi durdurup host sayfasına geri döner; bayrak bu kurtarmanın yalnız
     * BİR KEZ denenmesini sağlar (kalıcı yanlış yapılandırmada sonsuz tur olmasın). Bayrak tekil
     * bir alanken çok yüzeyli — DESTEKLENEN — yapılandırmada iki yönlü bozuluyordu:
     *  (b) A hakkını tükettiyse B, İLK kaçışında hiç kurtarma denemeden hata basıyordu;
     *  (a) yeni bir yüzeyin açılması bayrağı KÜRESEL olarak `false`a çekiyor, "yalnız bir deneme"
     *      garantisini sınırsız "durdur → yeniden yükle" turuna çeviriyordu.
     *
     * ── ÖLÇÜT NİÇİN "HATA BASILDI MI", "İSTEK GİTTİ Mİ" DEĞİL ────────────────────────────────
     * Kurtarmanın ağa çıkması PLATFORMA bağlı: `stopLoading()` yabancı belge COMMIT olmadan
     * yetişirse hemen ardındaki `loadUrl` iptal oluyor (emülatörde ölçüldü — ki bu zararsızdır,
     * belge zaten kendi sayfamızda kalır). Bayrağın KAPSAMI ise `onLoadFailed`ten deterministik
     * okunur: hak varsa hata YOK, hak tükendiyse hata VAR.
     *
     * Kaçış burada gerçek gezinmeyle değil, WebView'DAN GERİ OKUNAN gerçek `WebViewClient`e
     * doğrudan çağrıyla üretiliyor (bu dosyadaki gezinme matrisiyle aynı yöntem, sınırı da orada
     * yazılı): gerçek POST kaçışının geri alındığını `post_ile_kacis_geri_alinir` gösteriyor,
     * burada kanıtlanan şey bayrağın hangi nesneye AİT olduğu.
     *
     * Host yolu ASKIYA alınır: kurtarma yüklemesi tamamlansaydı `onPageFinished` bayrağı meşru
     * olarak sıfırlar ve test hiçbir şeyi ayırt edemezdi.
     */
    @Test
    fun kurtarma_bayragi_yuzey_basinadir() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())

        val k = ac(bizim, jeton = null)
        sayfaBekle(k.wv, bizim)
        val ikinci = webViewAc { k.chat.createWebView(k.baglam) }
        sayfaBekle(ikinci, bizim)

        val hatalar = mutableListOf<String>()
        anaIsParcaciginda { k.chat.onLoadFailed = { hatalar.add(it) } }
        bizim.bekletmeAc(hostYolu)

        val disAdres = "https://saldirgan.example/kacis"
        val istemciA = istemci(k.wv)
        val istemciB = istemci(ikinci)
        fun kacir(wv: WebView, istemci: WebViewClient) =
            anaIsParcaciginda { istemci.onPageStarted(wv, disAdres, null) }

        // A hakkını TÜKETİR: ilk kaçış kurtarılır, hata yok.
        kacir(k.wv, istemciA)
        assertEquals("ilk kaçış kurtarılmalıydı", emptyList<String>(), hatalar)

        // (b) B'nin İLK kaçışı: kendi hakkı DURUYOR. Tekil bayrakta burada hata basılıyordu.
        kacir(ikinci, istemciB)
        assertEquals("B, A'nın tükettiği hakla cezalandırıldı", emptyList<String>(), hatalar)

        // (a) YENİ yüzey açmak A'nın tükenmiş hakkını GERİ VERMEZ. Tekil bayrakta `createWebView`
        // bayrağı küresel olarak sıfırlıyordu → A ikinci kez kurtarma deniyor, hata basılmıyordu.
        webViewAc { k.chat.createWebView(k.baglam) }
        kacir(k.wv, istemciA)
        assertEquals(listOf(DIS_YONLENDIRME), hatalar)

        // Döngü koruması yüzey başına da AYNEN duruyor: B'nin ikinci kaçışı da hata basar.
        kacir(ikinci, istemciB)
        assertEquals(listOf(DIS_YONLENDIRME, DIS_YONLENDIRME), hatalar)
    }

    /**
     * A-K3 — SIFIRLAMA TÜM YÜZEYLERE GİDER, HER YÜZEYİN SCRIPT'İ AYRI TAZELENİR.
     *
     * İki yüzey aynı anda canlı olabilir (satıcının düzenine gömülü görünüm + balon paneli).
     * Kabuk tek bir `webView` alanı tuttuğunda `reset()` yalnız SONUNCUSUNU yeniden yüklüyordu:
     * hayalet kalan yüzey çıkıştan sonra da eski oturumu gösteriyordu. `scriptHandler` de tekil
     * olduğu için birinci yüzeyin kimlik script'i kurulduğu andaki hâlinde donuyordu.
     */
    @Test
    fun reset_TUM_yuzeyleri_sifirlar_ve_her_yuzeyin_scripti_tazelenir() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim, jeton = "A-jetonu")
        sayfaBekle(k.wv, bizim)
        val ikinci = webViewAc { k.chat.createWebView(k.baglam) }
        sayfaBekle(ikinci, bizim)

        // İki belge de eski jetonla açıldı.
        assertTrue(jsMetin(k.wv, "window.__kimlik") ?: "", (jsMetin(k.wv, "window.__kimlik") ?: "").contains("A-jetonu"))
        assertTrue(jsMetin(ikinci, "window.__kimlik") ?: "", (jsMetin(ikinci, "window.__kimlik") ?: "").contains("A-jetonu"))

        anaIsParcaciginda { k.chat.reset() }

        // Damgayla beklenemez: iki belge iki AYRI damga alır, `sonDamga` yalnız sonuncusunu bilir.
        // Sorulan şey zaten "her yüzeyde jetonsuz YENİ belge var mı".
        bekle("BİRİNCİ yüzey sıfırlanmadı (hayalet yüzey eski oturumu gösteriyor)") {
            (jsMetin(k.wv, "window.__kimlik") ?: "").contains("\"visitorToken\":null")
        }
        bekle("İKİNCİ yüzey sıfırlanmadı") {
            (jsMetin(ikinci, "window.__kimlik") ?: "").contains("\"visitorToken\":null")
        }

        // Yeni jeton İKİNCİ yüzeyden gelsin…
        js(ikinci, "NsuppNative.postMessage(JSON.stringify({type:'visitorToken',token:'YENI'})); 1")
        bekle("jeton kabuğa ulaşmadı") { k.depo.yazilanlar.lastOrNull() == "YENI" }
        anaKuyruguBosalt() // tazeleme `view.post` ile kuyruğa alınır

        // …BİRİNCİ yüzey yeniden yüklendiğinde onu GÖRMELİ. Tutamaç tekil bir alanda tutulsaydı
        // ikinci `createWebView` birincininkini üzerine yazar ve birinci yüzey sonsuza kadar
        // "temizle + jetonsuz" diyen script'le kalırdı.
        anaIsParcaciginda { k.wv.loadUrl(k.chat.hostUrl) }
        bekle("BİRİNCİ yüzeyin kimlik script'i tazelenmemiş") {
            (jsMetin(k.wv, "window.__kimlik") ?: "").contains("YENI")
        }
    }

    /**
     * ŞEMA ALLOWLIST'İ — GERÇEK TIKLAMA.
     *
     * `sema_allowlisti_disariya_da_iceriye_de_gecirmez` istemciyi DOĞRUDAN çağırıyor; bu test
     * WebView'ın süzgeci gerçekten çağırdığını gösterir. Sıra tesadüf değil: tıklamalar aynı
     * belgede sırayla üretilir ve gezinme kararları da aynı sırada ana iş parçacığına düşer,
     * dolayısıyla izinli bağlantının niyeti geldiğinde yasak olanlar ÇOKTAN değerlendirilmiştir.
     *
     * `javascript:` BU TESTTE YOK — bilerek: `<a href="javascript:…">` tıklaması bir GEZİNME
     * değildir, betik sayfanın kendi bağlamında koşar ve `shouldOverrideUrlLoading`e hiç uğramaz.
     * Kabuğun sınırı orada değil, ORIGIN'dedir (betik yalnız kendi sayfamızda koşabilir); şema
     * kapısının `javascript:`i eleyişi matris testinde ölçülüyor.
     *
     * DÜRÜST SINIR — `file:`: logcat ölçümü gösterdi ki gerçek tıklamada `intent:` ve özel şema
     * bizim süzgecimize ULAŞIYOR ("izin verilmeyen şema, açılmadı: intent / bankauygulamasi"),
     * `file:` ise ULAŞMIYOR — http belgesinden `file:`e gezinmeyi Chromium zaten kendisi reddediyor.
     * Yani buradaki `file:` satırı SONUCU (açılmadı, belge değişmedi) doğrular; allowlist'imizin
     * `file:`i elediğini matris testi gösteriyor.
     */
    @Test
    fun sema_allowlisti_GERCEK_tiklamada_da_gecirmez() {
        val bizim = sunucuAc()
        bizim.koy(
            hostYolu,
            hostSayfasi(
                """<a id="niyet" href="intent://x#Intent;scheme=http;end">i</a>
                   <a id="ozel" href="bankauygulamasi://transfer?to=x">b</a>
                   <a id="dosya" href="file:///data/data/com.nsupp.sdk.test/x">f</a>
                   <a id="posta" href="mailto:a@b.c">m</a>"""
            )
        )
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        for (id in listOf("niyet", "ozel", "dosya")) {
            js(k.wv, "document.getElementById('$id').click(); 1")
        }
        // POZİTİF KONTROL: izinli şema gerçek tıklamada GEÇMELİ. Olmasaydı "hiç niyet çıkmadı"
        // iddiası boş olurdu — tıklama süzgece hiç ulaşmıyor da olabilirdi.
        js(k.wv, "document.getElementById('posta').click(); 1")
        bekle("izinli şema gerçek tıklamada sisteme gitmedi") { k.baglam.niyetler.isNotEmpty() }
        anaKuyruguBosalt()

        assertEquals(listOf("mailto:a@b.c"), k.baglam.niyetler.map { it.data.toString() })
        // Yasak şemalar WebView'ın İÇİNE de alınmadı: belge hâlâ kendi sayfamız.
        assertEquals("${bizim.kok}$hostYolu", adres(k.wv))
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

    private companion object {
        /** Kurtarma hakkı tükendiğinde kabuğun bastığı sebep — metni üretimden kopyalanmıştır. */
        const val DIS_YONLENDIRME = "sohbet adresi kendi sunucumuzun dışına yönlendiriyor"
    }
}
