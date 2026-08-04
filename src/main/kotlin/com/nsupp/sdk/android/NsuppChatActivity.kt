package com.nsupp.sdk.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Hazır sohbet ekranı — içinde **web widget'ının kendisi** çalışır.
 *
 * ÜÇ YOLDAN BİRİ, HİÇBİRİ DAYATILMAZ: bu Activity hazır TAM EKRAN yoldur; köşede ikon/kayan panel
 * isteyen [NsuppChatPresenter]'ı, kendi düzenine gömmek isteyen [NsuppWebChatView]'i kullanır.
 * Balon HİÇBİR kipte şeffaf tam-ekran katman değildir (satıcının kendi arayüzünü tıklanamaz
 * bırakırdı); yalnız kapladığı 60 dp'lik daire dokunma yakalar.
 */
class NsuppChatActivity : Activity() {
    /** Ekran kapanınca yok edilecek WebView — bkz. [onDestroy]. */
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chat = Nsupp.webChat
        if (chat == null) {
            // `Nsupp.init` çağrılmadan açıldı — sessiz boş ekran yerine kapan (teşhis edilebilir).
            finish()
            return
        }
        val kok = FrameLayout(this)
        kok.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // UYGULAMA bağlamı: `createWebView` verilen bağlamı dış bağlantıları açan geri-çağrımda
        // TUTAR; Activity bağlamı verilseydi bu ekran kapandıktan sonra da canlı kalırdı. Dış
        // bağlantı `FLAG_ACTIVITY_NEW_TASK` ile açıldığı için uygulama bağlamı yeterlidir.
        val wv = chat.createWebView(applicationContext)
        webView = wv
        kok.addView(wv)
        setContentView(kok)
        // Bildirimden gelindiyse doğrudan o konuşma açılır (derin bağlantı). Sayfa yüklendikten
        // SONRA çağrılır; yoksa komut kuyruğu henüz yoktur.
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { id ->
            // Kanca KENDİNİ SİLER: kalsaydı sonraki her yeniden yükleme (ör. çıkışta sıfırlama)
            // bildirimden gelen o ESKİ konuşmayı tekrar açardı.
            chat.onLoaded = {
                chat.onLoaded = null
                chat.openConversation(id)
            }
        }
    }

    /**
     * Ekran kapanınca WebView YOK EDİLİR.
     *
     * Yok edilmezse `Nsupp.webChat` içindeki alan bu Activity'nin görünüm ağacını canlı tutar VE
     * sohbet sayfası (widget.js) kendi yoklamasını sürdürür — kapanmış bir ekran için pil, veri ve
     * sunucu yükü. Önce ağaçtan çıkar, sonra yok et (`WebView.destroy` javadoc'unun istediği sıra).
     */
    override fun onDestroy() {
        val wv = webView
        webView = null
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            Nsupp.webChat?.destroyWebView(wv)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "nsupp.conversationId"

        fun start(context: Context, conversationId: String? = null) {
            val i = Intent(context, NsuppChatActivity::class.java)
            if (conversationId != null) i.putExtra(EXTRA_CONVERSATION_ID, conversationId)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}
