package com.nsupp.sdk.android

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Enstrümanlı testlerin WebView'ı GERÇEKTEN gösterdiği boş Activity. Yalnız test APK'sındadır.
 *
 * ── NİÇİN GEREKLİ (kanıtla öğrenildi) ────────────────────────────────────────────────────────
 * WebView'ı görünüm ağacına EKLEMEDEN test etmek, üretimde hiç var olmayan bir yapılandırmayı
 * test etmektir. Somut sonucu şuydu: kabuk jeton gelince kimlik script'ini `view.post { … }` ile
 * tazeler; `View.post` javadoc'una göre görünüm bir pencereye BAĞLI DEĞİLSE runnable çalıştırılmaz,
 * bağlanana kadar bekletilir. Bağlanmayan WebView'da tazeleme HİÇ koşmadı ve test, üretimde var
 * olmayan bir "kusur" gösterdi. Üretimde WebView her zaman eklenir (`NsuppChatActivity.kok.addView`,
 * `NsuppWebChatView.addView`), testler de öyle yapar.
 */
class BosAktivite : Activity() {
    lateinit var kok: FrameLayout
        private set

    override fun onCreate(kayitliDurum: Bundle?) {
        super.onCreate(kayitliDurum)
        kok = FrameLayout(this)
        setContentView(kok)
    }
}
