package com.nsupp.sdk.android

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Gömülebilir sohbet görünümü — **web widget'ının kendisi**, satıcının kendi düzeninde.
 *
 * İKİ KİPTEN ②: satıcı bunu istediği yere koyabilir (sekme, yan panel, ayrı ekran, bölünmüş
 * görünümün yarısı). Köşede ikon/balon isteyen ① kip için [NsuppChatPresenter] var; hazır tam
 * ekran için `NsuppChatActivity`. Hiçbiri dayatılmaz.
 *
 * XML'den de kullanılabilir:
 *   `<com.nsupp.sdk.android.NsuppWebChatView android:layout_width="match_parent" … />`
 * Compose'dan:
 *   `AndroidView(factory = { NsuppWebChatView(it) }, modifier = Modifier.fillMaxSize())`
 *
 * GÖRÜNÜMÜ BU SINIF BELİRLEMEZ: sohbetin metinleri, bölümleri (Makaleler, ön-sohbet, hamburger,
 * marka satırı) ve renkleri çalışma alanı ayarından gelir; kabuk hiçbirini geçersiz kılmaz —
 * beş platform birebir aynı görünür. Buradaki tek iş WebView'ı doğru anda yaratıp yerleştirmek.
 */
class NsuppWebChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var webView: WebView? = null

    /**
     * WebView pencereye BAĞLANINCA yaratılır, kurucuda değil.
     *
     * Sebep: XML'den şişirilen bir görünüm ölçülmeden/eklenmeden önce de var olabilir; sayfayı o
     * anda yüklemek, hiç gösterilmeyecek bir ekran için ağ isteği demekti. Ayrıca `Nsupp.init`
     * Application.onCreate'te çağrıldığı için bağlanma anında oturum kesinlikle hazırdır.
     *
     * Ayrılınca WebView YOK EDİLDİĞİ için ([onDetachedFromWindow]) burada her zaman YENİSİ kurulur;
     * "eskisini geri tak" yolu YOK — o yol yok edilmiş bir Activity'nin ağacını canlı tutuyordu.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Savunma: attach/detach eşleşir ve detach alanı null'lar. Eşleşmezse ikinci bir WebView
        // kurmak, birincisini sahipsiz (ve yoklaması sürerken) bırakırdı.
        if (webView != null) return
        val chat = Nsupp.webChat
        if (chat == null) {
            // Sessiz kilit YASAK: boş bir dikdörtgen "bozuk" görünür ama sebebini söylemez.
            Log.w(TAG, "NsuppWebChatView was added before Nsupp.init() — chat not loaded")
            return
        }
        // UYGULAMA bağlamı: `createWebView` verilen bağlamı dış bağlantıları açan geri-çağrımda
        // TUTAR. Activity bağlamını verseydik, tek örnekli köprüde yaşayan WebView yok edilmiş
        // Activity'yi canlı tutardı (sızıntı). Dış bağlantı `FLAG_ACTIVITY_NEW_TASK` ile açıldığı
        // için uygulama bağlamı yeterlidir.
        val wv = chat.createWebView(context.applicationContext)
        webView = wv
        addView(wv, tamKaplama())
    }

    /**
     * Görünüm ağaçtan ayrılınca WebView YOK EDİLİR.
     *
     * KAPATILAN KUSUR: burası hiç override edilmiyordu. WebView, uygulama ömrü boyunca yaşayan
     * `Nsupp.webChat` içindeki alandan erişilebilir kalıyordu ve `parent` zinciri üzerinden YOK
     * EDİLMİŞ Activity'nin bütün görünüm ağacını canlı tutuyordu. Üstelik sohbet sayfası (widget.js)
     * kendi yoklamasını sürdürüyordu: ekran kapandıktan sonra da pil, veri ve sunucu yükü.
     *
     * BEDELİ AÇIKÇA: görünüm yeniden eklenirse sayfa BAŞTAN yüklenir — yazılmakta olan taslak ve
     * kaydırma konumu gider. Alternatifi (WebView'ı canlı tutmak) yukarıdaki sızıntıdır; sohbeti
     * ekranlar arasında taşımak isteyen satıcı görünümü ağaçtan hiç çıkarmaz (`View.GONE`).
     *
     * Sıra ÖNEMLİ: önce ağaçtan çıkar, sonra `destroy()` — `WebView.destroy` javadoc'u bunu
     * açıkça söyler ("should be called after this WebView has been removed from the view system").
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val wv = webView ?: return
        webView = null
        removeView(wv)
        Nsupp.webChat?.destroyWebView(wv)
    }

    private fun tamKaplama() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    private companion object {
        const val TAG = "NsuppWebChatView"
    }
}
