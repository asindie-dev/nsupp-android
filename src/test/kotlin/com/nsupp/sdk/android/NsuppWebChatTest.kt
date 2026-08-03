package com.nsupp.sdk.android

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nsupp.sdk.NsuppConfig
import com.nsupp.sdk.NsuppTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android kabuğunun GÜVENLİK SINIRI testleri.
 *
 * ── NİÇİN GERÇEK `createWebView` ÇAĞRILIYOR ──────────────────────────────────────────────────
 * Bu depoda en sık hatamız "saf yardımcıyı test edip ÜRETİM KABLOLAMASINI test etmemek" oldu.
 * Bu yüzden testler bir yardımcı fonksiyona değil, sınıfın kendisine bakıyor: `createWebView`
 * çağrılır, WebView'a ATANMIŞ gerçek `WebViewClient` ve gerçek `WebMessageListener` yakalanır ve
 * çağrılır. Android API'leri `tools/Shim*.kt` altındaki saplamalarla temsil edilir (Android SDK'sı
 * bu depoda kurulu değil); saplamalar sıralı çağrı günlüğü tuttuğu için "köprü `loadUrl`den ÖNCE
 * kuruldu mu" gibi SIRA iddiaları da kanıtlanabiliyor.
 */
class NsuppWebChatTest {
    private val temelUrl = "https://api.nsupp.com"
    private val beklenenKural = setOf("https://api.nsupp.com")

    /** Yazılan her değeri tutar — `reset()`in gerçekten null yazdığı iddiası buna dayanır. */
    private class KayitliDepo(private var deger: String? = null) : NsuppTokenStore {
        val yazilanlar = mutableListOf<String?>()
        override fun read(): String? = deger
        override fun write(token: String?) {
            deger = token
            yazilanlar.add(token)
        }
    }

    private class Istek(
        override val url: Uri,
        override val isForMainFrame: Boolean,
    ) : WebResourceRequest

    private fun kur(jeton: String? = null, ozellikVar: Boolean = true, scriptFirlat: Boolean = false): Ucgen {
        WebViewCompat.sifirla()
        WebViewFeature.destekli = ozellikVar
        WebViewCompat.scriptFirlat = scriptFirlat
        val depo = KayitliDepo(jeton)
        val config = NsuppConfig(apiBase = temelUrl, publicKey = "pk_test", appKey = "nsupp_app_123")
        val chat = NsuppWebChat(config, depo)
        val ctx = Context()
        val wv = chat.createWebView(ctx)
        return Ucgen(chat, wv, ctx, depo)
    }

    private class Ucgen(
        val chat: NsuppWebChat,
        val wv: WebView,
        val ctx: Context,
        val depo: KayitliDepo,
    )

    private fun istek(url: String, anaCerceve: Boolean = true) = Istek(Uri.parse(url), anaCerceve)

    // ── B1/B6/B12 (a): köprü ORIGIN'e bağlanır ───────────────────────────────────────────────

    @Test
    fun kopru_yalniz_kendi_origininde_kurulur() {
        val k = kur(jeton = "jeton-1")

        // (a) `addJavascriptInterface` ARTIK KULLANILMIYOR: o API nesneyi sayfanın TÜM
        //     çerçevelerine enjekte eder ve origin kısıtı yoktur.
        assertEquals(emptyList(), k.wv.jsArayuzleri)

        // (b) Kimlik iki origin-kurallı yoldan veriliyor ve kural JOKER İÇERMİYOR.
        assertEquals(1, WebViewCompat.eklenenDinleyiciler.size)
        val (ad, kural, _) = WebViewCompat.eklenenDinleyiciler.single()
        assertEquals("NsuppNative", ad)
        assertEquals(beklenenKural, kural)
        assertEquals(1, WebViewCompat.eklenenScriptler.size)
        assertEquals(beklenenKural, WebViewCompat.eklenenScriptler.single().second)

        // (c) İKİSİ DE `loadUrl`den ÖNCE: document-start script'i yalnız çağrıdan SONRA yüklenmeye
        //     başlayan çerçevelerde çalışır.
        val g = k.wv.gunluk
        val yukleme = g.indexOfFirst { it.startsWith("loadUrl:") }
        assertTrue(yukleme >= 0, "loadUrl hiç çağrılmadı")
        assertTrue(g.indexOf("addDocumentStartJavaScript") in 0 until yukleme, "script loadUrl'den SONRA eklendi")
        assertTrue(g.indexOf("addWebMessageListener:NsuppNative") in 0 until yukleme, "dinleyici loadUrl'den SONRA eklendi")

        // (d) Script iOS ile AYNI yüzeyi kuruyor (`window.NsuppApp`) ve gerçek anahtarı taşıyor.
        val script = WebViewCompat.eklenenScriptler.single().first
        assertTrue(script.contains("window.NsuppApp"), script)
        assertTrue(script.contains("nsupp_app_123"), script)
        assertTrue(script.contains("jeton-1"), script)
    }

    @Test
    fun ozellik_yoksa_kopru_hic_kurulmaz() {
        val k = kur(ozellikVar = false)
        // FAIL-CLOSED: origin'e bağlanamıyorsak köprüyü ESKİ yoldan kurmak, saldırgan belgesine
        // açmak demektir. Hiçbir köprü kurulmaz.
        assertEquals(emptyList(), k.wv.jsArayuzleri)
        assertEquals(0, WebViewCompat.eklenenDinleyiciler.size)
        assertEquals(0, WebViewCompat.eklenenScriptler.size)
        // Sayfa yine de yüklenir (kilit kapalı kiracıda sohbet çalışmaya devam eder).
        assertEquals(listOf("$temelUrl/widget/pk_test/app"), k.wv.yuklenenler)
    }

    @Test
    fun alt_cerceveden_gelen_mesaj_jetonu_ezmez() {
        val k = kur(jeton = "kurban")
        val dinleyici = WebViewCompat.eklenenDinleyiciler.single().third
        dinleyici.onPostMessage(
            k.wv,
            WebMessageCompat("{\"type\":\"visitorToken\",\"token\":\"SALDIRGAN\"}"),
            Uri.parse(temelUrl),
            false, // alt çerçeve
            JavaScriptReplyProxy(),
        )
        assertEquals(emptyList(), k.depo.yazilanlar)
    }

    // ── B1/B6/B12 (b): "içeride mi" kararı ORIGIN karşılaştırması ────────────────────────────

    @Test
    fun gezinme_origin_karsilastirir() {
        val k = kur()
        val client = k.wv.webViewClient!!
        k.wv.yuklenenler.clear()

        for (icerde in listOf("$temelUrl/widget/pk_test/app", "$temelUrl/x?q=1#f")) {
            assertFalse(client.shouldOverrideUrlLoading(k.wv, istek(icerde)), icerde)
        }

        val disarida = listOf(
            "https://api.nsupp.com@evil.example/x",      // userinfo — Uri.getHost bunu ELER
            "https://api.nsupp.com.evil.example/x",      // alt alan adı öneki
            "https://API.NSUPP.COM.evil.example/",       // büyük harf varyantı
            "http://api.nsupp.com/x",                    // şema düşürme
            "https://api.nsupp.com:8443/x",              // farklı port
            "https://api.nsupp.com./x",                  // sondaki nokta
            "https://xn--pi-xmc.nsupp.com/",             // IDN/punycode
        )
        for (url in disarida) {
            assertTrue(client.shouldOverrideUrlLoading(k.wv, istek(url)), url)
        }
        // Hiçbiri WebView'ın İÇİNDE yüklenmedi.
        assertEquals(emptyList(), k.wv.yuklenenler)
        // Hepsi http(s) olduğu için sistem tarayıcısına gitti — ama içeri ALINMADI.
        assertEquals(disarida.size, k.ctx.baslatilanIntentler.size)
    }

    @Test
    fun alt_cerceve_disa_acmaz() {
        val k = kur()
        val client = k.wv.webViewClient!!
        // Alt çerçeve dışarı çıkamaz AMA uygulama da açılmaz: gizli bir iframe kullanıcı
        // DOKUNMADAN başka uygulamayı tetiklerdi.
        assertTrue(client.shouldOverrideUrlLoading(k.wv, istek("https://evil.example/x", anaCerceve = false)))
        assertEquals(0, k.ctx.baslatilanIntentler.size)
    }

    @Test
    fun post_ile_kacis_geri_alinir() {
        val k = kur()
        val client = k.wv.webViewClient!!
        k.wv.yuklenenler.clear()

        // Kendi adresimiz: hiçbir şey olmaz.
        client.onPageStarted(k.wv, "$temelUrl/widget/pk_test/app", null)
        assertEquals(0, k.wv.durdurmaSayisi)
        assertEquals(emptyList(), k.wv.yuklenenler)

        // `shouldOverrideUrlLoading` POST için ÇAĞRILMAZ — kaçış ancak burada yakalanır.
        client.onPageStarted(k.wv, "https://evil.example/x", null)
        assertEquals(1, k.wv.durdurmaSayisi)
        assertEquals(listOf("$temelUrl/widget/pk_test/app"), k.wv.yuklenenler)

        // İkinci kaçış: sonsuz döngü yerine kullanıcıya hata.
        var hata: String? = null
        k.chat.onLoadFailed = { hata = it }
        client.onPageStarted(k.wv, "https://evil.example/y", null)
        assertEquals(2, k.wv.durdurmaSayisi)
        assertEquals(1, k.wv.yuklenenler.size)
        assertTrue(hata != null, "ikinci kaçışta hata bildirilmedi")
    }

    // ── B10: dış bağlantıda şema allowlist'i ─────────────────────────────────────────────────

    @Test
    fun sema_allowlisti() {
        val k = kur()
        val client = k.wv.webViewClient!!
        val yasak = listOf(
            "bankauygulamasi://transfer?to=x",
            "intent://x#Intent;scheme=http;end",
            "javascript:alert(1)",
            "file:///data/data/x/y",
            "content://com.x/y",
        )
        for (url in yasak) {
            assertTrue(client.shouldOverrideUrlLoading(k.wv, istek(url)), url)
        }
        assertEquals(0, k.ctx.baslatilanIntentler.size)
        assertEquals(emptyList(), k.wv.yuklenenler.filter { it != k.chat.hostUrl })
    }

    @Test
    fun izinli_semalar_disa_acilir() {
        val k = kur()
        val client = k.wv.webViewClient!!
        val izinli = listOf("https://haber.example/x", "mailto:a@b.c", "tel:+905550000000")
        for (url in izinli) {
            assertTrue(client.shouldOverrideUrlLoading(k.wv, istek(url)), url)
        }
        assertEquals(izinli, k.ctx.baslatilanIntentler.map { it.data.toString() })
        for (i in k.ctx.baslatilanIntentler) {
            assertTrue(i.kategoriler.contains(android.content.Intent.CATEGORY_BROWSABLE), i.data.toString())
        }
    }

    // ── B9/B11: reset() WebView deposunu gerçekten temizler ──────────────────────────────────

    @Test
    fun reset_depoyu_temizler() {
        val k = kur(jeton = "A-jetonu")
        val ilkScript = WebViewCompat.eklenenScriptler.single().first
        assertFalse(ilkScript.contains("localStorage.clear"), "jeton VARKEN temizleme olmamalı")
        assertTrue(ilkScript.contains("A-jetonu"))

        k.wv.gunluk.clear()
        k.wv.yuklenenler.clear()
        k.chat.reset()

        assertEquals(listOf<String?>(null), k.depo.yazilanlar)
        assertEquals(1, WebViewCompat.sokulenScriptSayisi)
        assertEquals(2, WebViewCompat.eklenenScriptler.size)
        val yeni = WebViewCompat.eklenenScriptler.last().first
        assertTrue(yeni.contains("localStorage.clear()"), yeni)
        assertTrue(yeni.contains("visitorToken:null"), yeni)

        // Temizleyen script, sayfa yeniden yüklenmeden ÖNCE eklenmiş olmalı.
        val g = k.wv.gunluk
        val ekleme = g.indexOf("addDocumentStartJavaScript")
        val yukleme = g.indexOfFirst { it.startsWith("loadUrl:") }
        assertTrue(ekleme >= 0 && yukleme > ekleme, g.toString())
        assertEquals(listOf(k.chat.hostUrl), k.wv.yuklenenler)
    }

    @Test
    fun bayat_depo_bir_sonraki_acilista_silinir() {
        // Süreç ölümü sonrası yol: kabukta jeton YOK → WebView'daki depo BAYATTIR.
        val k = kur(jeton = null)
        val script = WebViewCompat.eklenenScriptler.single().first
        assertTrue(script.contains("localStorage.clear()"), script)
        assertTrue(script.contains("visitorToken:null"), script)
        assertEquals(0, k.wv.durdurmaSayisi)
    }

    @Test
    fun script_kurulamadiysa_reset_temizligi_ATLAMAZ() {
        // Özellikler DESTEKLİ ama script ekleme istisna attı (kabuk bu ihtimali `catch`liyor).
        // Kimlik script'i hiç kurulamadığı için "script'i tazele" temizliği ORTADA YOK; çıkış
        // yine de temizlemek zorunda, yoksa widget localStorage'daki ÖNCEKİ kullanıcının jetonunu
        // bulur — düzeltmenin kapattığı B9/B11 tam olarak bu.
        val k = kur(jeton = "A-jetonu", scriptFirlat = true)
        assertEquals(0, WebViewCompat.eklenenScriptler.size)
        k.wv.degerlendirilenJs.clear()
        k.wv.yuklenenler.clear()
        k.wv.gunluk.clear() // açılıştaki `loadUrl` sıra iddiasını kirletmesin

        k.chat.reset()

        assertEquals(listOf<String?>(null), k.depo.yazilanlar)
        assertEquals(1, k.wv.degerlendirilenJs.size, "sayfa bağlamında temizlik koşmadı")
        assertTrue(k.wv.degerlendirilenJs.single().contains("localStorage.clear()"), k.wv.degerlendirilenJs.toString())
        // Yeniden yükleme temizlikten SONRA: sıra geri-çağrımdan gelir, tahminden değil.
        val g = k.wv.gunluk
        assertTrue(g.indexOf("evaluateJavascript") in 0 until g.indexOfFirst { it.startsWith("loadUrl:") }, g.toString())
        assertEquals(listOf(k.chat.hostUrl), k.wv.yuklenenler)
    }

    @Test
    fun jeton_yazilinca_script_tazelenir() {
        val k = kur(jeton = null)
        val dinleyici = WebViewCompat.eklenenDinleyiciler.single().third
        dinleyici.onPostMessage(
            k.wv,
            WebMessageCompat("{\"type\":\"visitorToken\",\"token\":\"YENI\"}"),
            Uri.parse(temelUrl),
            true,
            JavaScriptReplyProxy(),
        )
        assertEquals(listOf<String?>("YENI"), k.depo.yazilanlar)
        // Tazelenmezse aynı WebView'daki bir yeniden yükleme "temizle + null" diyen BAYAT script'i
        // koşturur ve yeni oturumu silerdi.
        assertEquals(1, WebViewCompat.sokulenScriptSayisi)
        val yeni = WebViewCompat.eklenenScriptler.last().first
        assertFalse(yeni.contains("localStorage.clear"), yeni)
        assertTrue(yeni.contains("YENI"), yeni)
    }
}
