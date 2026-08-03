package com.nsupp.sdk.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Hazır sohbet ekranı — içinde **web widget'ının kendisi** çalışır.
 *
 * LAUNCHER BALONU YOK (bilinçli karar): hiçbir lider mobil SDK zorunlu köşe balonu çizmiyor. Balon
 * şeffaf tam-ekran katman + dokunma geçirgenliği ister ve yanlış yapıldığında satıcının KENDİ
 * arayüzünü tıklanamaz bırakır. Satıcı kendi "Destek" düğmesini koyar, SDK sohbeti açar.
 */
class NsuppChatActivity : Activity() {
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
        kok.addView(chat.createWebView(this))
        setContentView(kok)
        // Bildirimden gelindiyse doğrudan o konuşma açılır (derin bağlantı). Sayfa yüklendikten
        // SONRA çağrılır; yoksa komut kuyruğu henüz yoktur.
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { id ->
            chat.onLoaded = { chat.openConversation(id) }
        }
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
