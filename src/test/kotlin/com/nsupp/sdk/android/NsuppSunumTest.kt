package com.nsupp.sdk.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sunum ölçülerinin WEB PARİTESİ.
 *
 * ── NE KANITLIYOR, NE KANITLAMIYOR (dürüst sınır) ────────────────────────────────────────────
 * Kanıtlar: panelin dar telefonda ekrandan taşmadan kısıldığını (web'in `max-width` /
 * `max-height` kuralının Android karşılığı), kullanılabilir alan çok darken NEGATİF ölçü
 * üretilmediğini ve panelin balonun üstüne oturduğunu.
 * KANITLAMAZ: `NsuppChatPresenter`in görünümleri gerçekten içerik köküne eklediğini, geri tuşunu
 * yakaladığını, sistem çubuğu boşluklarını uyguladığını ya da `kullanilabilirDp`nin gerçek bir
 * Android 15 penceresinden doğru sayıyı okuduğunu. O kablolama Android API'lerine dokunur ve
 * yalnız cihazda/emülatörde koşan enstrümanlı testle doğrulanabilir (`src/androidTest`; şu an
 * orada `NsuppWebChat` var, sunum kablolaması YOK). "Yeşil test = çalışıyor" demiyoruz — burada
 * koşan şey ölçü sözleşmesidir.
 */
class NsuppSunumTest {
    @Test
    fun panel_genis_ekranda_web_olcusunde_kalir() {
        // Tablet/masaüstü genişliği: web'de de panel 360'ta kalır.
        assertEquals(360, NsuppOlculer.panelGenisligiDp(800))
        assertEquals(520, NsuppOlculer.panelYuksekligiDp(900))
    }

    @Test
    fun panel_dar_telefonda_kisilir() {
        // 360 dp'lik tipik telefon: sabit 360 genişlik ekranı TAM kaplar ve kenar boşluğu kalmazdı.
        // Web'in `max-width:calc(100vw - 40px)` kuralı burada da uygulanır.
        assertEquals(320, NsuppOlculer.panelGenisligiDp(360))
        // `max-height:calc(100vh - 120px)`.
        assertEquals(520, NsuppOlculer.panelYuksekligiDp(640))
        assertEquals(520, NsuppOlculer.panelYuksekligiDp(1000)) // tavan 520'de kalır
        assertEquals(400, NsuppOlculer.panelYuksekligiDp(520))
    }

    @Test
    fun cok_dar_alanda_olcu_NEGATIF_donmez() {
        // Bölünmüş ekranın ince yarısı / katlanır cihazın kapak ekranı: kullanılabilir alan
        // çıkarılan paydan KÜÇÜK. Taban olmasaydı `FrameLayout.LayoutParams`a negatif ölçü
        // yazılırdı. iOS `max(0,…)`, masaüstü `Math.max(0,…)` kullanıyor — Android da kullanmalı,
        // yoksa aynı formülün üç uygulaması tam bu kenarda AYRIŞIR.
        assertEquals(0, NsuppOlculer.panelGenisligiDp(20))
        assertEquals(0, NsuppOlculer.panelGenisligiDp(0))
        assertEquals(0, NsuppOlculer.panelYuksekligiDp(100))
        assertEquals(0, NsuppOlculer.panelYuksekligiDp(0))
        // Sınırın hemen üstü hâlâ normal davranır (taban gerçek değeri EZMEZ).
        assertEquals(1, NsuppOlculer.panelGenisligiDp(41))
        assertEquals(1, NsuppOlculer.panelYuksekligiDp(121))
    }

    @Test
    fun panel_kullanilabilir_alandan_TASMAZ() {
        // A1 ÇİFT SAYIM KUSURUNUN SÖZLEŞME TARAFI: panelin alt boşluğu (76) + yüksekliği,
        // kullanılabilir yüksekliği GEÇMEMELİ. Eski kod yüksekliği sistem çubukları DAHİL bir
        // ölçüden hesaplıyor, üstüne bir de çubuk boşluğunu kenar boşluğuna ekliyordu; 48 dp'lik
        // gezinme çubuğunda panelin üst kenarı ekranın DIŞINA çıkıyordu.
        // Burada girdi artık KULLANILABİLİR alandır; formülün kendisi taşmamalı.
        for (kullanilabilirYukseklik in listOf(200, 400, 520, 640, 800, 1200)) {
            val toplam = NsuppOlculer.PANEL_ALT_BOSLUK_DP + NsuppOlculer.panelYuksekligiDp(kullanilabilirYukseklik)
            assertTrue(
                toplam <= kullanilabilirYukseklik,
                "panel kullanılabilir alanı taşıyor: $toplam > $kullanilabilirYukseklik",
            )
        }
        for (kullanilabilirGenislik in listOf(200, 320, 360, 411, 800)) {
            val toplam = NsuppOlculer.KENAR_BOSLUK_DP + NsuppOlculer.panelGenisligiDp(kullanilabilirGenislik)
            assertTrue(
                toplam <= kullanilabilirGenislik,
                "panel yanda taşıyor: $toplam > $kullanilabilirGenislik",
            )
        }
    }

    @Test
    fun panel_balonun_ustune_oturur_bosluk_birakmaz() {
        // Web'deki ilişki: launcher 20'den başlar ve 60 yüksektir (üst kenarı 80), panel 76'dan
        // başlar — yani panel balonun üstünü 4 dp örter. Alt boşluk 80'i GEÇSEYDİ ikisi arasında
        // görünür bir boşluk kalır ve mobil kabuk webden farklı dururdu.
        val balonUstu = NsuppOlculer.KENAR_BOSLUK_DP + NsuppOlculer.BALON_CAP_DP
        val panelAlti = NsuppOlculer.PANEL_ALT_BOSLUK_DP
        assertTrue(panelAlti <= balonUstu, "panel balonun üstünde boşluk bırakıyor: $panelAlti > $balonUstu")
        // …ama balonun altına da inmemeli, yoksa balonu tamamen örter ve kapatma dokunuşu gider.
        assertTrue(panelAlti > NsuppOlculer.KENAR_BOSLUK_DP, "panel balonu tamamen örtüyor")
    }

    @Test
    fun kip_adlari_platform_sinifini_dogru_anlatir() {
        // KİP ADLANDIRMA KARARI: mobilde balon-olmayan kip TAM EKRANdır, dolayısıyla adı `EKRAN`.
        // Masaüstündeki `panel` (köşede kayan pencere) adını mobilde kullanmak, aynı adı iki
        // davranışa vermek — yani satıcıya kodun yapmadığını vaat etmek — olurdu.
        assertEquals(listOf("EKRAN", "BALON"), NsuppSunumKipi.entries.map { it.name })
        // Varsayılan kip: satıcı kip vermezse köşede İSTEMEDİĞİ bir ikon belirmemeli.
        assertEquals(NsuppSunumKipi.EKRAN, NsuppSunumKipi.entries.first())
    }
}
