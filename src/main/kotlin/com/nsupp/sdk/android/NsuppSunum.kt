package com.nsupp.sdk.android

/**
 * Sohbetin ekranda NASIL durduğu — ölçüler ve kip. **Saf Kotlin, Android API'si YOK.**
 *
 * ── NİÇİN AYRI VE SAF ────────────────────────────────────────────────────────────────────────
 * Ölçü kararı (panel ne kadar geniş, dar telefonda neye kısılır, balonun neresinden başlar)
 * bir çizim ayrıntısı değil, web widget'ıyla PARİTE sözleşmesidir. Android'e dokunmadığı için
 * emülatörsüz test edilebilir; `View` içine gömülseydi yalnız Gradle derlemesiyle "tipler tutuyor"
 * denebilir, sayıların web ile aynı kaldığı hiçbir yerde KANITLANAMAZDI.
 *
 * ── SAYILARIN KAYNAĞI ────────────────────────────────────────────────────────────────────────
 * Hepsi `packages/widget/widget.js` içindeki `.ph-win` / launcher CSS'inden BİREBİR alınmıştır:
 *   `.ph-win{bottom:76px; …:0; width:360px; max-width:calc(100vw - 40px);
 *            height:520px; max-height:calc(100vh - 120px); border-radius:16px}`
 * Aynı sayılar macOS kabuğunda `NsuppMacOlculer` altında durur. Elle senkron: widget CSS'i
 * değişirse iki taraf da değişmeli — dağıtılmış sihirli sayılar sessizce AYRIŞIR, tek yerde
 * toplananlar en azından görülebilir.
 */

/**
 * Mobil sunum — satıcının seçebileceği İKİ kip.
 *
 * `EKRAN` → sohbet TAM EKRAN açılır (`NsuppChatActivity`); satıcı kendi düğmesinden açar,
 *           köşede hiçbir şey durmaz.
 * `BALON` → köşede bir ikon durur; dokununca kayan paneli açar/kapatır (web'deki launcher).
 *
 * ── KİP ADI PLATFORM SINIFINA GÖRE DEĞİŞİR (bilinçli) ────────────────────────────────────────
 * Masaüstü kabuklarında (macOS, Electron) balon-olmayan kipin adı `panel`dir ve gerçekten
 * KÖŞEDE KAYAN BİR PENCERE açar. Mobilde aynı ad kullanılsaydı YALAN olurdu: telefonda
 * balon-olmayan kip TAM EKRAN açar. Aynı adı iki davranışa vermek, satıcıya kodun yapmadığı
 * bir şeyi vaat etmektir; bu yüzden mobildeki ad `EKRAN`.
 *
 * Telefonda "satıcının kendi düğmesinden açılan köşe penceresi" diye bir kip YOK: 360×520 dp'lik
 * bir pencere telefon ekranının neredeyse tamamıdır ve kenar boşlukları yalnız okuma alanını
 * daraltır. Kayan panel YALNIZ [BALON] kipinde vardır — orada gerekçesi launcher paritesidir
 * (balona dokunup açılan, tekrar dokununca kapanan pencere).
 *
 * Hiçbiri DAYATILMAZ: satıcı ikisini de kullanmayıp [NsuppWebChatView]'i kendi arayüzüne
 * (sekme, yan panel, ayrı ekran) GÖMEBİLİR.
 */
enum class NsuppSunumKipi {
    EKRAN,
    BALON,
}

/** Web widget'ının Android'deki ölçüleri (dp). Bkz. dosya başındaki "SAYILARIN KAYNAĞI". */
object NsuppOlculer {
    const val PANEL_GENISLIK_DP = 360
    const val PANEL_YUKSEKLIK_DP = 520
    const val BALON_CAP_DP = 60

    /** Balonun/panelin ekran kenarına uzaklığı (web: `right:20px; bottom:20px`). */
    const val KENAR_BOSLUK_DP = 20

    /** Panelin balonun ÜSTÜNDE başladığı yükseklik (web: `.ph-win{bottom:76px}`). */
    const val PANEL_ALT_BOSLUK_DP = 76

    /** Panel köşe yarıçapı (web: `.ph-win{border-radius:16px}`). */
    const val PANEL_KOSE_DP = 16

    /** Web'in `max-width:calc(100vw - 40px)` kuralı = iki yandan kenar boşluğu. */
    private const val YATAY_DUSUM_DP = 2 * KENAR_BOSLUK_DP

    /** Web'in `max-height:calc(100vh - 120px)` kuralı. */
    private const val DIKEY_DUSUM_DP = 120

    /**
     * Panelin genişliği. Telefonda 360 dp'lik sabit bir panel ekrandan TAŞARDI; web de aynı
     * durumda `max-width` ile kısıyor — parite tam olarak bu kısıtla kurulur.
     *
     * Girdi KULLANILABİLİR alandır (sistem çubukları çıkarılmış), ham ekran ölçüsü değil —
     * bkz. `NsuppChatPresenter.kullanilabilirDp`.
     *
     * `maxOf(0, …)`: kullanılabilir alan 40 dp'den darsa (bölünmüş ekranın ince yarısı, katlanır
     * cihazın kapak ekranı) çıkarma NEGATİF döner ve `FrameLayout.LayoutParams`a negatif genişlik
     * yazmak düzeni bozar. iOS `max(0,…)`, masaüstü `Math.max(0,…)` kullanıyor; taban burada da
     * olmalı, yoksa aynı formülün üç uygulaması bu kenarda AYRIŞIR.
     */
    fun panelGenisligiDp(kullanilabilirGenislikDp: Int): Int =
        minOf(PANEL_GENISLIK_DP, maxOf(0, kullanilabilirGenislikDp - YATAY_DUSUM_DP))

    /** Panelin yüksekliği; web'in `max-height` kuralıyla aynı. Taban gerekçesi yukarıdaki gibi. */
    fun panelYuksekligiDp(kullanilabilirYukseklikDp: Int): Int =
        minOf(PANEL_YUKSEKLIK_DP, maxOf(0, kullanilabilirYukseklikDp - DIKEY_DUSUM_DP))
}
