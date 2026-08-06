package com.nsupp.sdk.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * `<input type="file">` seçicisinin GÖRÜNMEZ kabuğu — sohbette dosya/görsel eki bunun üzerinden gider.
 *
 * ── NİÇİN AYRI BİR ACTIVITY (kolaya kaçma değil, tek çalışan yol) ─────────────────────────────
 * `WebChromeClient.onShowFileChooser` bize bir `Intent` ve bir geri-çağrım verir; sonucu almak için
 * `Activity.startActivityForResult` gerekir ve sonuç, çağıran **Activity'nin** `onActivityResult`una
 * düşer. Denenebilecek üç yol vardı:
 *
 *  1. `createWebView(context)`e verilen bağlamı Activity'ye çevirmek. ÇALIŞMAZ: üretimde iki çağrı
 *     yerinin ikisi de bilerek `applicationContext` veriyor (`NsuppChatActivity`,
 *     `NsuppWebChatView` — sebebi orada yazılı: bağlam dış-bağlantı geri-çağrımında TUTULUYOR,
 *     Activity verilse yok edilmiş ekranın ağacı canlı kalırdı).
 *  2. Satıcının Activity'sinde `startActivityForResult` çağırmak. ÇALIŞMAZ: sonuç SATICININ
 *     `onActivityResult`una düşer, biz o metodu göremeyiz. Görmek için satıcıdan "şu çağrıyı bize
 *     yönlendir" diye kod istemek gerekirdi — SDK'nın işini satıcıya yıkmak olurdu.
 *  3. Kendi görünmez Activity'miz (burası). Sonucu KENDİ `onActivityResult`umuzda alırız; satıcı
 *     hiçbir şey yazmaz, hangi bağlamla açıldığımızın önemi kalmaz.
 *
 * Görünmezdir (`Theme.Translucent.NoTitleBar`, manifest'te) ve seçici kapanır kapanmaz kendini
 * kapatır: kullanıcı yalnız dosya seçiciyi görür.
 *
 * ── GERİ-ÇAĞRIM TAM BİR KEZ ÇAĞRILIR ─────────────────────────────────────────────────────────
 * `filePathCallback` çağrılmazsa WebView'ın dosya girişi KALICI kilitlenir: kullanıcı düğmeye
 * basar, hiçbir şey olmaz ve sebebi hiçbir yerde görünmez ("sessiz kilit = teşhis edilemez hata").
 * Bu yüzden geri-çağrım [onCreate]'te statik slottan ALINIR (slot boşalır, ikinci bir istek onu
 * ezemez) ve [onDestroy]'da hâlâ teslim edilmemişse `null` ile kapatılır — kullanıcı seçiciyi
 * geri tuşuyla kapatsa da, sistem Activity'yi öldürse de girişin kilidi açılır.
 *
 * ── İŞ PARÇACIĞI ─────────────────────────────────────────────────────────────────────────────
 * [slot] KORUMASIZDIR ve öyle kalmalıdır: `onShowFileChooser` ve Activity geri-çağrımlarının hepsi
 * ana (UI) iş parçacığındadır.
 */
class NsuppFileChooserActivity : Activity() {

    /** Bu Activity'nin SAHİPLENDİĞİ geri-çağrım — statik slottan alınır, oraya geri konmaz. */
    private var geriCagrim: ValueCallback<Array<Uri>>? = null

    override fun onCreate(kayitliDurum: Bundle?) {
        super.onCreate(kayitliDurum)
        // Yeniden yaratılma (süreç ölümü): statik slot süreçle birlikte gitti, WebView de öyle.
        // Teslim edilecek bir geri-çağrım yok; sessizce kapan.
        geriCagrim = slotuAl()
        val secici = intentiOku()
        if (geriCagrim == null || secici == null) {
            Log.w(TAG, "no pending file chooser request, closing")
            finish()
            return
        }
        try {
            startActivityForResult(secici, ISTEK_KODU)
        } catch (e: Exception) {
            // Cihazda dosya seçebilecek uygulama yok. Girişi kilitli bırakmıyoruz: null teslim.
            bitir(null, "file chooser could not be opened: " + e)
        }
    }

    override fun onActivityResult(istekKodu: Int, sonucKodu: Int, veri: Intent?) {
        super.onActivityResult(istekKodu, sonucKodu, veri)
        if (istekKodu != ISTEK_KODU) return
        // İptal de (RESULT_CANCELED) buraya düşer ve `parseResult` null döner — doğru davranış:
        // geri-çağrım null ile kapanır, giriş yeniden kullanılabilir olur.
        val sonuc = try {
            WebChromeClient.FileChooserParams.parseResult(sonucKodu, veri)
        } catch (e: Exception) {
            Log.w(TAG, "chooser result could not be read: " + e)
            null
        }
        bitir(sonuc, null)
    }

    override fun onDestroy() {
        // SON EMNİYET — bkz. sınıf belgesi: teslim edilmemiş geri-çağrım = kalıcı kilitli dosya
        // girişi. Buradan `finish()` çağrılmaz (zaten yok ediliyoruz), yalnız teslim yapılır.
        teslimEt(null)
        super.onDestroy()
    }

    private fun bitir(sonuc: Array<Uri>?, sebep: String?) {
        if (sebep != null) Log.w(TAG, sebep)
        teslimEt(sonuc)
        finish()
    }

    /** Geri-çağrımı TAM BİR KEZ çağırır; ikinci çağrıda yapacak bir şey kalmaz. */
    private fun teslimEt(sonuc: Array<Uri>?) {
        val cb = geriCagrim ?: return
        geriCagrim = null
        try {
            cb.onReceiveValue(sonuc)
        } catch (e: Exception) {
            Log.w(TAG, "chooser result could not be delivered to the page: " + e)
        }
    }

    /**
     * `Intent` içindeki seçici niyetini oku.
     *
     * Tür KONTROL EDİLİR: bu Activity `exported="false"`tur, yani dışarıdan tetiklenemez; yine de
     * beklenmedik bir yükte çökmek yerine kapanmak doğru davranış.
     */
    @Suppress("DEPRECATION")
    private fun intentiOku(): Intent? = try {
        intent?.getParcelableExtra(EK_SECICI) as? Intent
    } catch (e: Exception) {
        Log.w(TAG, "chooser intent could not be read: " + e)
        null
    }

    companion object {
        private const val TAG = "NsuppFileChooser"
        private const val ISTEK_KODU = 0x6e73 // "ns"
        private const val EK_SECICI = "nsupp.fileChooser.intent"

        /**
         * Geri-çağrımın Activity'ye TESLİM SLOTU.
         *
         * `Intent`e konamaz (Parcelable değil), bu yüzden statik. Slot [slotuAl] ile BİR KEZ alınır
         * ve boşalır: iki isteğin aynı geri-çağrımı teslim etmesi ya da birinin diğerini ezmesi
         * böyle imkânsız olur.
         */
        private var slot: ValueCallback<Array<Uri>>? = null

        private fun slotuAl(): ValueCallback<Array<Uri>>? {
            val c = slot
            slot = null
            return c
        }

        /**
         * Seçiciyi başlat. `true` = geri-çağrımın SAHİPLİĞİ devralındı (WebView'a "ben çağıracağım"
         * denebilir); `false` = hiçbir şey devralınmadı, çağıran WebView'ın varsayılan (iptal)
         * davranışına bırakmalı.
         *
         * `FLAG_ACTIVITY_NEW_TASK` ZORUNLU: bağlam uygulama bağlamıdır. Kütüphane kendi
         * `taskAffinity`sini AYARLAMAZ — varsayılan afinite satıcının uygulama afinitesidir,
         * dolayısıyla bayrak yeni bir görev açmak yerine AÇIK OLAN göreve yerleşir; ayrı bir görev
         * açsaydık seçici kapandığında kullanıcı uygulamaya değil ana ekrana düşerdi.
         */
        fun baslat(
            baglam: Context,
            secici: Intent,
            geriCagrim: ValueCallback<Array<Uri>>,
            teshis: (String) -> Unit,
        ): Boolean {
            // Slotta bekleyen varsa (Activity henüz onCreate'e gelmemiş) onu ASKIDA BIRAKMA:
            // teslim edilmeyen geri-çağrım o girişi kalıcı kilitler.
            slotuAl()?.let {
                try {
                    it.onReceiveValue(null)
                } catch (_: Exception) {
                }
            }
            slot = geriCagrim
            val niyet = Intent(baglam, NsuppFileChooserActivity::class.java)
                .putExtra(EK_SECICI, secici)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                baglam.startActivity(niyet)
                true
            } catch (e: Exception) {
                // Tipik sebep: satıcının manifest'ine bu Activity birleşmemiş. Sessiz kalmıyoruz.
                slotuAl()
                teshis("file chooser host could not be started (the manifest entry may be missing): " + e)
                false
            }
        }
    }
}
