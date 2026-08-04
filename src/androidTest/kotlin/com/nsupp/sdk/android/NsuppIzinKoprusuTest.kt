package com.nsupp.sdk.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nsupp.sdk.NsuppConfig
import com.nsupp.sdk.NsuppTokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MEDYA İZNİ + DOSYA SEÇİCİ KÖPRÜSÜ — GERÇEK `WebView`, GERÇEK `WebChromeClient`, GERÇEK emülatör.
 *
 * ── KAPATILAN KUSUR ──────────────────────────────────────────────────────────────────────────
 * Kabukta `WebChromeClient` HİÇ YOKTU. Sonucu ölçülebilirdi: (a) `onPermissionRequest`in
 * varsayılan gövdesi `deny()` çağırdığı için widget'ın sesli mesaj düğmesi hiç çalışmıyordu;
 * (b) `onShowFileChooser` olmadığı için `<input type="file">` HİÇ açılmıyordu — Android'de görsel
 * eki gönderilememesinin sebebi buydu. İkisi de webde çalışan özelliklerdi.
 *
 * ── BU DOSYA NEYİ KANITLIYOR ─────────────────────────────────────────────────────────────────
 *  1. Mikrofon isteği KENDİ origin'imizden ve uygulamanın RECORD_AUDIO izni varken AÇILIR —
 *     üstelik gerçek `getUserMedia({audio:true})` çağrısı `NotAllowedError` almıyor.
 *  2. YABANCI origin'den gelen mikrofon isteği REDDEDİLİR.
 *  3. Mikrofon DIŞINDAKİ her kaynak (kamera, kamera+mikrofon, MIDI, korumalı medya) REDDEDİLİR —
 *     gerçek `getUserMedia({audio:true,video:true})` çağrısı `NotAllowedError` alıyor.
 *  4. Uygulamanın Android RECORD_AUDIO izni yoksa istek REDDEDİLİR ve SEBEBİ bildirilir.
 *  5. Her reddin sebebi `onPermissionDenied` kanalına düşer (sessiz kilit yok).
 *  6. Dosya seçici kendi belgemizde kabuk Activity'sini BAŞLATIR; yabancı belgede AÇMAZ ve
 *     geri-çağrımı çağırmaz (`false` döner → WebView varsayılanı iptal eder).
 *  7. Kabuk Activity'si geri-çağrımı TAM BİR KEZ çağırır — seçici hiç açılamasa bile. Çağırmasaydı
 *     dosya girişi kalıcı kilitlenirdi.
 *
 * ── BU DOSYA NEYİ KANITLAMIYOR (dürüst sınır) ────────────────────────────────────────────────
 *  · "İstek ANA ÇERÇEVEDEN mi geldi": `PermissionRequest` bu bilgiyi TAŞIMAZ (yalnız origin,
 *    kaynak listesi, grant/deny). Kabuk da bu kapıyı iddia etmiyor; kendi origin'imizdeki bir alt
 *    çerçeve de geçer. Yabancı origin'li çerçeve geçmez ve madde 2 bunu ölçüyor.
 *  · GERÇEK bir dosya seçiminin sonucu: seçici DocumentsUI'dır, testten deterministik
 *    sürülemez. Ölçülen şey kabuğun sözleşmesi — Activity başlatılıyor mu ve geri-çağrım tam bir
 *    kez kapanıyor mu.
 *  · `<input type="file">` tıklaması gerçek dokunuşla ÜRETİLMİYOR: Chromium dosya seçiciyi
 *    yalnız kullanıcı etkileşimiyle açar, `evaluateJavascript` etkileşim sayılmaz. Bu yüzden
 *    `onShowFileChooser` WebView'DAN GERİ OKUNAN gerçek `WebChromeClient` üzerinden doğrudan
 *    çağrılıyor (bu paketteki gezinme matrisiyle aynı yöntem ve aynı sınır).
 */
@RunWith(AndroidJUnit4::class)
class NsuppIzinKoprusuTest {

    private val acilanlar = mutableListOf<WebView>()
    private val sunucular = mutableListOf<YerelSunucu>()
    private lateinit var senaryo: ActivityScenario<BosAktivite>

    @Before
    fun hazirla() {
        senaryo = ActivityScenario.launch(BosAktivite::class.java)
        // OLUMLU DAL ANCAK İZİN GERÇEKTEN VARKEN GÖZLENEBİLİR. İzin test APK'sının manifest'inde
        // beyan edilmiştir (kütüphaneninkinde DEĞİL) ve burada çalışma zamanında veriliyor.
        kabukKomutu(
            "pm grant " + hedefBaglam().packageName + " android.permission.RECORD_AUDIO"
        )
        assertEquals(
            "RECORD_AUDIO çalışma-zamanı izni verilemedi; olumlu dal ölçülemez",
            PackageManager.PERMISSION_GRANTED,
            hedefBaglam().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO),
        )
    }

    @After
    fun temizle() {
        anaIsParcaciginda {
            acilanlar.forEach {
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

    private fun hedefBaglam(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun kabukKomutu(komut: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(komut)
        // Tanımlayıcı KAPATILIR: kapatılmazsa kabuk komutu bitmeyebilir ve sonraki ölçüm yarışır.
        java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
    }

    private class Depo : NsuppTokenStore {
        override fun read(): String? = null
        override fun write(token: String?) = Unit
    }

    /**
     * `startActivity`yi YAKALAYAN ve `checkSelfPermission`ı ZORLAYABİLEN bağlam.
     *
     * Gerçek bir `ContextWrapper`dır — taklit değil; `applicationContext` gerçek uygulama bağlamını
     * döndürür, dolayısıyla WebView gerçektir. İki şey değiştirilir:
     *  · `startActivity` kaydedilir (yoksa test koşarken emülatörde gerçek dosya seçici açılır);
     *  · [izinDurumu] null değilse `checkSelfPermission` onu döndürür. NİÇİN GEREKLİ: "uygulamanın
     *    RECORD_AUDIO izni yok" dalını gözlemenin öbür yolu izni çalışma zamanında GERİ ALMAKTI ve
     *    Android izin geri alındığında süreci öldürür — test koşusunun kendisi biterdi.
     */
    private class KayitliBaglam(temel: Context) : ContextWrapper(temel) {
        val niyetler: MutableList<Intent> = mutableListOf()
        var izinDurumu: Int? = null

        override fun startActivity(intent: Intent) {
            niyetler.add(intent)
        }

        override fun checkSelfPermission(permission: String): Int =
            izinDurumu ?: super.checkSelfPermission(permission)
    }

    /**
     * GERÇEK `android.webkit.PermissionRequest` uygulaması.
     *
     * Saplama değil: sınıf platformun kendisidir, eksik/yanlış imza derlemeyi düşürür. Testin
     * ürettiği tek şey isteğin İÇERİĞİ (origin + kaynak listesi); kararı üretim kodu verir.
     */
    private class Istek(
        private val kaynakAdresi: Uri,
        private val istenenler: Array<String>,
    ) : PermissionRequest() {
        var verilenler: Array<out String>? = null
        var reddedildi = false

        override fun getOrigin(): Uri = kaynakAdresi
        override fun getResources(): Array<String> = istenenler
        override fun grant(resources: Array<out String>) {
            verilenler = resources
        }

        override fun deny() {
            reddedildi = true
        }
    }

    /** GERÇEK `WebChromeClient.FileChooserParams` uygulaması — yine platform sınıfı. */
    private class SeciciParametreleri(private val niyet: Intent) : WebChromeClient.FileChooserParams() {
        override fun getMode(): Int = MODE_OPEN
        override fun getAcceptTypes(): Array<String> = arrayOf("image/*")
        override fun isCaptureEnabled(): Boolean = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent(): Intent = niyet
    }

    private class Kurulum(
        val chat: NsuppWebChat,
        val wv: WebView,
        val baglam: KayitliBaglam,
        val sunucu: YerelSunucu,
        val redler: MutableList<String>,
    )

    private fun anaIsParcaciginda(blok: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(blok)

    private fun sunucuAc(): YerelSunucu = YerelSunucu().also { sunucular.add(it) }

    private val hostYolu = "/widget/pk_test/app"

    /** Kabuğun beklediği host sayfası + gerçek `getUserMedia` çağırabilen küçük yardımcı. */
    private fun hostSayfasi(): String = """
        <!doctype html><html><head><meta charset="utf-8"></head><body>
        <script>
          window.__medya = (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) ? 'var' : 'yok';
          window.iste = function (kisit, ad) {
            window[ad] = 'bekliyor';
            navigator.mediaDevices.getUserMedia(kisit).then(function (s) {
              s.getTracks().forEach(function (t) { t.stop(); });
              window[ad] = 'ok';
            }).catch(function (e) { window[ad] = 'hata:' + e.name; });
          };
        </script>
        </body></html>
    """.trimIndent()

    private fun ac(sunucu: YerelSunucu): Kurulum {
        val config = NsuppConfig(apiBase = sunucu.kok, publicKey = "pk_test", appKey = "nsupp_app_123")
        val chat = NsuppWebChat(config, Depo())
        val baglam = KayitliBaglam(hedefBaglam())
        val redler = mutableListOf<String>()
        val wv = webViewAc {
            chat.onPermissionDenied = { redler.add(it) }
            chat.createWebView(baglam)
        }
        return Kurulum(chat, wv, baglam, sunucu, redler)
    }

    private fun webViewAc(uret: () -> WebView): WebView {
        lateinit var wv: WebView
        senaryo.onActivity { aktivite ->
            wv = uret()
            aktivite.kok.addView(wv)
        }
        acilanlar.add(wv)
        return wv
    }

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

    private fun jsMetin(wv: WebView, kod: String): String? {
        val ham = js(wv, kod)
        if (ham == "null" || ham == "undefined") return null
        return org.json.JSONArray("[$ham]").optString(0, "")
    }

    /** Kabuğun WebView'a GERÇEKTEN ATADIĞI `WebChromeClient` — testin elindeki nesne değil. */
    private fun kabukIstemcisi(wv: WebView): WebChromeClient {
        val kutu = arrayOfNulls<WebChromeClient>(1)
        anaIsParcaciginda { kutu[0] = wv.webChromeClient }
        return kutu[0]!!
    }

    private fun izinSor(wv: WebView, origin: String, vararg kaynaklar: String): Istek {
        val istek = Istek(Uri.parse(origin), arrayOf(*kaynaklar))
        // İstemci ÖNCE okunur: `runOnMainSync` iç içe çağrılamaz (ana iş parçacığından çağrılırsa
        // istisna atar).
        val istemci = kabukIstemcisi(wv)
        anaIsParcaciginda { istemci.onPermissionRequest(istek) }
        return istek
    }

    // ── 1) Mikrofon: kendi origin'imizde AÇILIR ──────────────────────────────────────────────

    @Test
    fun mikrofon_kendi_originimizde_ve_izin_varken_ACILIR() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        val istek = izinSor(k.wv, bizim.kok, PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        assertFalse("mikrofon isteği reddedildi", istek.reddedildi)
        assertEquals(
            listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            istek.verilenler?.toList(),
        )
        assertEquals("olumlu dalda red sebebi bildirildi", emptyList<String>(), k.redler)
    }

    // ── 2) Mikrofon: YABANCI origin'de REDDEDİLİR ────────────────────────────────────────────

    @Test
    fun mikrofon_YABANCI_originde_reddedilir() {
        val bizim = sunucuAc()
        val yabanci = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        // Aynı host, BAŞKA port = başka origin. Önek testi geçen biçimler de dışarıda sayılmalı.
        val disarida = listOf(
            yabanci.kok,
            bizim.kok + "0",
            "https://127.0.0.1",
            "http://127.0.0.1.evil.example",
        )
        for (origin in disarida) {
            val istek = izinSor(k.wv, origin, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            assertTrue(origin, istek.reddedildi)
            assertNull(origin, istek.verilenler)
        }
        assertEquals(disarida.size, k.redler.size)
        assertTrue(k.redler.toString(), k.redler.all { it.contains("YABANCI origin") })
    }

    // ── 3) Mikrofon DIŞINDAKİ her kaynak REDDEDİLİR ──────────────────────────────────────────

    @Test
    fun mikrofon_disindaki_kaynaklar_reddedilir() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        val yasak = listOf(
            arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            // "Hepsini iste, birini ver" de reddedilir: dar kapsam güvenli olandır.
            arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            arrayOf(PermissionRequest.RESOURCE_MIDI_SYSEX),
            arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID),
            // Kaynaksız istek de açılmaz.
            arrayOf(),
        )
        for (kaynaklar in yasak) {
            val istek = izinSor(k.wv, bizim.kok, *kaynaklar)
            assertTrue(kaynaklar.joinToString(), istek.reddedildi)
            assertNull(kaynaklar.joinToString(), istek.verilenler)
        }
        assertEquals(yasak.size, k.redler.size)
        assertTrue(k.redler.toString(), k.redler.all { it.contains("yalnız mikrofon") })
    }

    // ── 4) Android çalışma-zamanı izni yoksa REDDEDİLİR ──────────────────────────────────────

    @Test
    fun calisma_zamani_izni_yoksa_mikrofon_reddedilir_ve_sebep_bildirilir() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        // Satıcının uygulaması RECORD_AUDIO'yu manifest'ine koymamış / kullanıcı vermemiş.
        k.baglam.izinDurumu = PackageManager.PERMISSION_DENIED

        val istek = izinSor(k.wv, bizim.kok, PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        assertTrue("izin yokken mikrofon açıldı", istek.reddedildi)
        assertNull(istek.verilenler)
        assertEquals(1, k.redler.size)
        // Sebep TEŞHİS EDİLEBİLİR olmalı: satıcı bunu okuyunca ne yapacağını bilmeli.
        assertTrue(k.redler.single(), k.redler.single().contains("RECORD_AUDIO"))

        // Kapı SÜREKLİ kapalı değil: izin geri geldiğinde aynı istek AÇILIR. Bu ikinci yarı
        // olmasaydı `deny()`i koşulsuz çağıran bir uygulama da testi geçerdi.
        k.baglam.izinDurumu = null
        val ikinci = izinSor(k.wv, bizim.kok, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        assertFalse(ikinci.reddedildi)
        assertEquals(
            listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            ikinci.verilenler?.toList(),
        )
    }

    // ── 5) GERÇEK getUserMedia: ses AÇILIYOR, kamera REDDEDİLİYOR ────────────────────────────

    /**
     * AYAĞI YERE BASSIN: yukarıdaki testler `WebChromeClient`i doğrudan çağırıyor. Burada gerçek
     * `navigator.mediaDevices.getUserMedia` çağrılıyor — WebView'ın kancayı gerçekten çağırdığı ve
     * kararımızın sayfaya gerçekten yansıdığı böyle görülür.
     *
     * ── ÖLÇÜT NİÇİN "ok" DEĞİL, "NotAllowedError DEĞİL" ──────────────────────────────────────
     * İZİN kararı `NotAllowedError` ile bildirilir; emülatörde ses AYGITININ bulunup bulunmaması
     * ayrı bir mesele ve `NotFoundError` verir. Kabuğun sorumluluğu izindir, aygıt değil: "ok ya da
     * aygıt hatası" doğru ölçüttür, "ok" olsaydı test emülatörün ses donanımına bağlanırdı. Red
     * tarafında ise ölçüt TAM: kamera isteği kesin olarak `NotAllowedError` almalıdır.
     */
    @Test
    fun gercek_getUserMedia_sesi_ACAR_kamerayi_REDDEDER() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        // 127.0.0.1 güvenli bağlamdır; değilse `mediaDevices` hiç olmazdı ve test sessizce
        // hiçbir şey ölçmezdi.
        assertEquals("mediaDevices yok — güvenli bağlam değil", "var", jsMetin(k.wv, "window.__medya"))

        js(k.wv, "iste({audio:true},'__ses'); 1")
        bekle("ses isteği sonuçlanmadı") { jsMetin(k.wv, "window.__ses") != "bekliyor" }
        val ses = jsMetin(k.wv, "window.__ses")
        assertNotNull(ses)
        assertFalse("mikrofon izni sayfaya REDDEDİLDİ olarak yansıdı: $ses", ses == "hata:NotAllowedError")

        js(k.wv, "iste({audio:true,video:true},'__kamera'); 1")
        bekle("kamera isteği sonuçlanmadı") { jsMetin(k.wv, "window.__kamera") != "bekliyor" }
        assertEquals("kamera açıldı", "hata:NotAllowedError", jsMetin(k.wv, "window.__kamera"))
    }

    // ── 6) Dosya seçici ──────────────────────────────────────────────────────────────────────

    @Test
    fun dosya_secici_kendi_belgemizde_kabuk_aktivitesini_baslatir() {
        val bizim = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        val seciciNiyeti = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        val gelenler = mutableListOf<Array<Uri>?>()
        val geriCagrim = ValueCallback<Array<Uri>> { gelenler.add(it) }

        val istemci = kabukIstemcisi(k.wv)
        val kutu = booleanArrayOf(false)
        anaIsParcaciginda {
            kutu[0] = istemci.onShowFileChooser(k.wv, geriCagrim, SeciciParametreleri(seciciNiyeti))
        }

        assertTrue("seçici açılmadı (WebView'a 'ben çağıracağım' denmedi)", kutu[0])
        assertEquals(1, k.baglam.niyetler.size)
        val niyet = k.baglam.niyetler.single()
        assertEquals(
            NsuppFileChooserActivity::class.java.name,
            niyet.component?.className,
        )
        // Uygulama bağlamından açılıyor → NEW_TASK zorunlu, yoksa çalışma zamanında istisna atar.
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK yok",
            (niyet.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
        // Seçici niyeti gerçekten taşınıyor: kabuk onu `startActivityForResult`a verecek.
        @Suppress("DEPRECATION")
        val tasinan = niyet.getParcelableExtra<Intent>("nsupp.fileChooser.intent")
        assertEquals(Intent.ACTION_GET_CONTENT, tasinan?.action)
        assertEquals("image/*", tasinan?.type)
        // Geri-çağrım HENÜZ çağrılmadı: sahipliği kabuk Activity'si devraldı.
        assertEquals(emptyList<Array<Uri>?>(), gelenler)

        // Devralınan geri-çağrım askıda kalmasın (bu test kabuk Activity'sini gerçekten
        // başlatmıyor — `startActivity` yakalandı): slot bir sonraki isteğe devredilirken kapanır.
        anaIsParcaciginda {
            NsuppFileChooserActivity.baslat(k.baglam, seciciNiyeti, ValueCallback { }) { }
        }
        assertEquals("devralınan geri-çağrım askıda bırakıldı", listOf<Array<Uri>?>(null), gelenler)
    }

    @Test
    fun dosya_secici_YABANCI_belgede_acilmaz() {
        val bizim = sunucuAc()
        val yabanci = sunucuAc()
        bizim.koy(hostYolu, hostSayfasi())
        yabanci.koy("/yabanci", "<html><body>yabancı</body></html>")
        val k = ac(bizim)
        sayfaBekle(k.wv, bizim)

        // Ekranda YABANCI bir belge duruyor (kaçış anı). Kabuğun kendi WebView'ı kaçışı geri
        // aldığı için burada ayrı bir WebView kullanılıyor; ölçülen şey `onShowFileChooser`in
        // ana belgenin adresine bakması.
        val yabanciWv = webViewAc { WebView(hedefBaglam()) }
        anaIsParcaciginda {
            yabanciWv.settings.javaScriptEnabled = true
            yabanciWv.loadUrl(yabanci.kok + "/yabanci")
        }
        sayfaBekle(yabanciWv, yabanci, "/yabanci")

        val gelenler = mutableListOf<Array<Uri>?>()
        val istemci = kabukIstemcisi(k.wv)
        val kutu = booleanArrayOf(true)
        anaIsParcaciginda {
            kutu[0] = istemci.onShowFileChooser(
                yabanciWv,
                ValueCallback { gelenler.add(it) },
                SeciciParametreleri(Intent(Intent.ACTION_GET_CONTENT).setType("*/*")),
            )
        }

        assertFalse("yabancı belgede seçici açıldı", kutu[0])
        assertEquals(0, k.baglam.niyetler.size)
        // `false` döndüğümüz için geri-çağrımı ÇAĞIRMAMALIYIZ: WebView varsayılanı iptal eder.
        assertEquals(emptyList<Array<Uri>?>(), gelenler)
        assertEquals(1, k.redler.size)
        assertTrue(k.redler.single(), k.redler.single().contains("YABANCI belgede"))
    }

    /**
     * KABUK ACTIVITY'Sİ GERİ-ÇAĞRIMI TAM BİR KEZ KAPATIR — SEÇİCİ HİÇ AÇILAMASA BİLE.
     *
     * Çağırmasaydı WebView'ın dosya girişi KALICI kilitlenirdi: kullanıcı düğmeye basar, hiçbir şey
     * olmaz ve sebebi görünmez. Senaryo taklit değil: gerçek `Activity` gerçekten başlatılıyor,
     * ona verilen seçici niyeti ise cihazda ÇÖZÜLEMEZ (hiçbir uygulama karşılamıyor) —
     * `startActivityForResult` istisna atar ve kabuk kapanış yolunu işletir.
     */
    @Test
    fun kabuk_aktivitesi_secici_acilamazsa_da_geri_cagrimi_TAM_BIR_KEZ_kapatir() {
        val gelenler = mutableListOf<Array<Uri>?>()
        val kilit = CountDownLatch(1)
        val cozulemez = Intent("com.nsupp.sdk.test.BOYLE_BIR_EYLEM_YOK")

        val basladi = booleanArrayOf(false)
        anaIsParcaciginda {
            basladi[0] = NsuppFileChooserActivity.baslat(
                hedefBaglam(),
                cozulemez,
                ValueCallback {
                    gelenler.add(it)
                    kilit.countDown()
                },
            ) { }
        }

        assertTrue("kabuk Activity'si başlatılamadı", basladi[0])
        assertTrue("geri-çağrım hiç çağrılmadı — dosya girişi kalıcı kilitli kalırdı", kilit.await(20, TimeUnit.SECONDS))
        assertEquals(listOf<Array<Uri>?>(null), gelenler)

        // İKİNCİ KEZ ÇAĞRILMAMALI: Activity yok edilirken emniyet teslimi tekrar tetiklenmemeli.
        Thread.sleep(1500)
        assertEquals("geri-çağrım birden çok kez çağrıldı", 1, gelenler.size)
    }
}
