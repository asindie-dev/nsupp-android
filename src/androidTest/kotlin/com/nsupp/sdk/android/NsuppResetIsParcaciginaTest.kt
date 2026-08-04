package com.nsupp.sdk.android

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * `Nsupp.reset()` — ÜRETİM KABLOLAMASININ TAMAMI, gerçek emülatörde.
 *
 * ── KAPATILAN KUSUR (A-T1) ───────────────────────────────────────────────────────────────────
 * `Nsupp.init` şunu YAZILI olarak vaat ediyor: `NsuppSession`ın korumasız durumu
 * (`state`/`seen`/`lastTs`) `limitedParallelism(1)` ile SERİ tutulur, bu yüzden `send`, `identify`,
 * `rate`, `trackEvent`… hepsi `scope.launch` ile o kanala girer. `reset()` TEK İSTİSNAYDI:
 * çağıranın iş parçacığında doğrudan koşuyordu. İki somut sonucu vardı:
 *
 *  ① SIRA BOZULUYORDU. `pollJob.cancel()` BLOKLAYAN bir `HttpURLConnection` okumasını KESMEZ;
 *     uçuştaki istek tamamlanır. Sıfırlama o isteğin ÖNÜNDE koştuğu için son sözü biten istek
 *     söylüyordu. Kanala girince sıfırlama isteğin ARKASINA dizilir.
 *  ② ÇÖKÜYORDU. `reset()` içindeki `webChat.reset()` WebView'a dokunur; `WebView` yanlış iş
 *     parçacığından çağrılınca çalışma-zamanı istisnası atar. Satıcı çıkışı bir arka plan işinden
 *     (ör. "oturumu kapat" ağ çağrısının geri-çağrımı) tetiklediğinde SDK çöküyordu.
 *
 * ── TAMAMLANMA SİNYALİ (A-T4) ────────────────────────────────────────────────────────────────
 * `reset()` tamamen asenkron olduğu için satıcı "çıkış bitti" anını gözleyemiyordu: hemen ardından
 * okunan `Nsupp.current?.visitorToken` hâlâ ESKİ jetonu döndürüyordu ve "çıkışta jeton silindi"
 * vaadi satıcı tarafında doğrulanamıyordu. İsteğe bağlı geri-çağrım burada, uçuştaki isteğin
 * açtığı pencerede ölçülüyor: jeton silinmeden ÖNCE çalışmıyor, sonra ANA iş parçacığında ve jeton
 * silinmiş hâlde çalışıyor.
 *
 * ── NİÇİN SAF FONKSİYON TESTİ OLMAZ ──────────────────────────────────────────────────────────
 * Kanıtlanan şey `Nsupp` nesnesinin GERÇEK kablolamasıdır: gerçek `SharedPreferences` deposu,
 * gerçek `HttpURLConnection` üzerinden gerçek yerel sunucu ve gerçek `WebView`. Sahte bir HTTP
 * ya da sahte bir sohbet yüzeyi tam da kanıtlanmak istenen şeyi (bloklayan istek + WebView'ın
 * iş parçacığı denetimi) ortadan kaldırırdı.
 *
 * ── TEK TEST METODU, BİLİNÇLİ ────────────────────────────────────────────────────────────────
 * `Nsupp` bir `object`tir ve `init` ikinci çağrıda erken döner (`if (session != null) return`) —
 * süreç başına TEK oturum. Bu yüzden bütün iddialar tek bir akışta ölçülüyor; ikinci bir metot
 * ilkinin oturumunu devralır ve neyi ölçtüğü belirsizleşirdi.
 */
@RunWith(AndroidJUnit4::class)
class NsuppResetIsParcaciginaTest {

    private val sunucular = mutableListOf<YerelSunucu>()
    private lateinit var senaryo: ActivityScenario<BosAktivite>

    @After
    fun temizle() {
        if (::senaryo.isInitialized) senaryo.close()
        sunucular.forEach { it.kapat() }
        sunucular.clear()
    }

    private fun anaIsParcaciginda(blok: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(blok)

    private fun bekle(mesaj: String, saniye: Long = 20, kosul: () -> Boolean) {
        val bitis = System.currentTimeMillis() + saniye * 1000
        while (System.currentTimeMillis() < bitis) {
            if (kosul()) return
            Thread.sleep(50)
        }
        throw AssertionError("zaman aşımı: $mesaj")
    }

    @Test
    fun reset_seri_kanala_dizilir_ve_WebView_ana_is_parcaciginda_sifirlanir() {
        val sunucu = YerelSunucu().also { sunucular.add(it) }
        val hostYolu = "/widget/$PK/app"
        val oturumYolu = "/widget/$PK/session"
        sunucu.koy(hostYolu, "<!doctype html><html><body>host</body></html>")
        // `/session` ASKIDA: `identify` seri kanalda BLOKLAYAN bir HTTP okumasında kalsın.
        sunucu.bekletmeAc(oturumYolu)
        sunucu.koyHam(oturumYolu, "application/json") { """{"data":{"visitorToken":"vt_ESKI"}}""" }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        // Depo GERÇEK `SharedPreferences`tır ve koşular arası yaşar; önceki koşunun jetonu
        // "identify oturum açtı" sanılmasına yol açardı.
        ctx.getSharedPreferences("nsupp.$PK", Context.MODE_PRIVATE).edit().clear().commit()

        Nsupp.init(ctx, apiBase = sunucu.kok, publicKey = PK)
        val oturum = Nsupp.current ?: throw AssertionError("Nsupp.init oturum kurmadı")

        // ÜRETİM YÜZEYİ: `NsuppWebChatView` kendi içinde `Nsupp.webChat.createWebView` çağırır —
        // testin kendi kurduğu bir WebView değil, satıcının koyduğu görünümün TA KENDİSİ.
        senaryo = ActivityScenario.launch(BosAktivite::class.java)
        senaryo.onActivity { it.kok.addView(NsuppWebChatView(it)) }
        bekle("sohbet yüzeyi host sayfasını yüklemedi") { sunucu.istekSayisi("GET $hostYolu") == 1 }

        // `identify` seri kanala girer ve askıdaki `/session` isteğinde BLOKLANIR.
        Nsupp.identify("a@b.c")
        bekle("identify /session isteğini göndermedi") { sunucu.istekSayisi("POST $oturumYolu") == 1 }

        // ÇIKIŞ ARKA PLAN İŞ PARÇACIĞINDAN — üretimde satıcının yapabileceği şey.
        val hata = AtomicReference<Throwable?>(null)
        // TAMAMLANMA SİNYALİ: satıcı "çıkış bitti" anını gözleyebilmeli — yoksa `reset()`in hemen
        // ardından okunan `visitorToken` hâlâ ESKİ jetonu döndürür ve "çıkışta jeton silindi"
        // vaadi satıcı tarafında DOĞRULANAMAZDI.
        val bitti = CountDownLatch(1)
        val bitisAnaIsParcaciginda = AtomicReference<Boolean?>(null)
        val bitisJetonu = AtomicReference<String?>("HENÜZ-ÇALIŞMADI")
        val t = thread {
            try {
                Nsupp.reset {
                    bitisAnaIsParcaciginda.set(Looper.myLooper() == Looper.getMainLooper())
                    bitisJetonu.set(oturum.visitorToken)
                    bitti.countDown()
                }
            } catch (e: Throwable) {
                hata.set(e)
            }
        }
        t.join(10_000)
        assertFalse("Nsupp.reset() arka plan iş parçacığında dönmedi", t.isAlive)
        // ② WebView'a çağıranın iş parçacığından dokunulsaydı burada istisna dururdu.
        assertNull("Nsupp.reset() arka plan iş parçacığında istisna attı: " + hata.get(), hata.get())

        // ① SIRA: uçuştaki `identify` hâlâ bloklu olduğu için sıfırlama HENÜZ koşmamış olmalı.
        //    Çağıranın iş parçacığında koşan eski sürümde `stopped` bu satırda ZATEN true'ydu.
        assertFalse(
            "reset() seri kanalı atladı: uçuştaki istek beklenmeden oturumu sıfırladı",
            oturum.stopped,
        )
        // Geri-çağrım da HENÜZ çalışmamış olmalı — sinyal "jeton silindi" demek; erken çalışsaydı
        // satıcıya YALAN söylerdi. Beklemeye gerek yok: seri kanalın bloklu olduğu bir üstteki
        // satırda ölçüldü.
        assertEquals("çıkış geri-çağrımı jeton silinmeden ÖNCE çalıştı", 1L, bitti.count)

        // WebView sıfırlaması ANA iş parçacığına postalanır ve isteğe bağlı DEĞİLDİR: sayfa
        // yeniden yüklenmeli.
        bekle("çıkışta sohbet yüzeyi yeniden yüklenmedi") { sunucu.istekSayisi("GET $hostYolu") == 2 }

        // Askı kalkınca: önce `identify` biter (ve jetonu yazar), SONRA sıfırlama koşar.
        sunucu.bekletmeSurdur(oturumYolu)
        bekle("sıfırlama seri kanalda hiç koşmadı") { oturum.stopped }
        assertNull("son söz sıfırlamanın olmalıydı", oturum.visitorToken)

        // …ve satıcı bunu geri-çağrımdan gözleyebilir: ANA iş parçacığında (arayüz oradan
        // güncellenir) ve jeton O AN çoktan silinmiş olarak.
        assertTrue("çıkış geri-çağrımı hiç çalışmadı", bitti.await(20, TimeUnit.SECONDS))
        assertEquals("geri-çağrım ana iş parçacığında çalışmadı", true, bitisAnaIsParcaciginda.get())
        assertNull("geri-çağrım çalıştığında jeton hâlâ duruyordu", bitisJetonu.get())
    }

    private companion object {
        /** Bu sınıfa özel: aynı süreçteki başka bir testin `SharedPreferences` dosyasına girmesin. */
        const val PK = "pk_reset_test"
    }
}
