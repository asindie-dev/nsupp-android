package com.nsupp.sdk.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import com.nsupp.sdk.NsuppTexts

/**
 * Köşe balonu + kayan panel, ya da tam ekran — mobilde "hazır yol".
 *
 * İKİ KİPTEN ①: [NsuppSunumKipi.BALON] köşeye bir ikon koyar, dokununca kayan panel açılır/kapanır;
 * [NsuppSunumKipi.EKRAN] hiçbir şey çizmez, satıcı kendi düğmesinden [present] çağırır ve sohbet
 * TAM EKRAN (`NsuppChatActivity`) açılır. Kip adının niçin masaüstündeki `panel`den farklı olduğu
 * [NsuppSunumKipi] belgesinde. Arayüze GÖMÜLÜ ② kip için [NsuppWebChatView] var — hiçbiri dayatılmaz.
 *
 * ── BALON ŞEFFAF TAM-EKRAN KATMAN DEĞİLDİR ───────────────────────────────────────────────────
 * Balon, Activity'nin içerik kökünde (`android.R.id.content`) yalnız 60 dp'lik yer kaplayan bir
 * görünümdür. Ekranı kaplayan şeffaf bir katman olsaydı — mobil SDK'larda en sık görülen hata —
 * satıcının KENDİ arayüzü dokunulamaz hâle gelirdi. Kaplamadığı hiçbir piksel dokunma yakalamaz.
 *
 * ── PANEL AÇILINCA ODAK ALIR (macOS'tan BİLİNÇLİ SAPMA) ──────────────────────────────────────
 * macOS'ta sohbet penceresi odak ÇALMAZ; orada panel kullanıcının yazmakta olduğu alanı bölmemeli.
 * Android'de geri tuşu yalnız ODAKLI görünüm zincirine ulaşır: panel odak almazsa geri tuşu
 * paneli değil Activity'yi kapatır — kullanıcı sohbeti açtı diye satıcının ekranından çıkardı.
 * Bu yüzden panel açılırken odağı alır, ama klavyeyi AÇMAZ (`showSoftInput` çağrılmaz): yazma
 * niyeti kullanıcınındır, panelin belirmesi değil.
 *
 * ── AYNI ANDA TEK SOHBET YÜZEYİ ──────────────────────────────────────────────────────────────
 * Panel, gömülü görünüm ve `NsuppChatActivity` aynı [NsuppWebChat] köprüsünü paylaşır; ikisi
 * birden canlıyken komutlar (bildirimden konuşma açma, çıkışta sıfırlama) yalnız EN SON yaratılan
 * WebView'a gider. İkincisi açıldığında logcat'e uyarı düşer.
 */
class NsuppChatPresenter(private val kip: NsuppSunumKipi = NsuppSunumKipi.EKRAN) {
    private var kok: FrameLayout? = null
    private var balon: View? = null
    private var panel: FrameLayout? = null

    /** Uygulama geneli balon için kayıtlı dinleyici — iki kez kaydolmayı engeller. */
    private var yasamDongusu: Application.ActivityLifecycleCallbacks? = null

    /**
     * İçerik kökü pencereden ayrılınca (Activity yok edildi) referansları BIRAK.
     *
     * Presenter'ı uzun ömürlü bir yerde tutan satıcı, aksi hâlde yok edilmiş bir Activity'nin
     * bütün görünüm ağacını canlı tutardı — sızıntı ve "eski ekrana çizen panel".
     */
    private val kokDinleyici = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = unut()
    }

    /**
     * Balonu UYGULAMA GENELİNDE göster — her ekranda tek tek [start] çağırmaya gerek kalmaz.
     *
     * NİÇİN AYRI BİR YOL: balon, Activity'nin içerik kökünde (`android.R.id.content`) yaşar; o kök
     * Activity ile birlikte gider. iOS'ta balon AYRI bir `UIWindow`dadır, dolayısıyla satıcı bir
     * kez `start()` demekle kurtulur. Android'de aynı ergonomiyi vermenin dokümante yolu bu:
     * `Application.ActivityLifecycleCallbacks` ile her öne gelen ekrana balonu yeniden koymak.
     * Satıcının 40 Activity'sinde tek tek çağrı yazması, kütüphanenin işini satıcıya devretmekti.
     *
     * Yalnız BAZI ekranlarda balon isteyen satıcı bunu kullanmaz: ilgili Activity'de
     * `start(activity)` çağırır.
     *
     * `Application.onCreate` içinde:
     *   `destek.start(this)`  // `this` = Application
     */
    fun start(application: Application) {
        if (kip != NsuppSunumKipi.BALON) return
        // İkinci kez çağrılırsa dinleyici listesi sessizce BÜYÜR (Android kaydı tekilleştirmez) ve
        // her ekran değişiminde aynı iş defalarca koşar. Bayrak bunu bir kere olmaya kilitler.
        if (yasamDongusu != null) return
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // Sohbetin KENDİ tam ekranında balon gösterilmez: sohbetin üstünde duran bir
                // "sohbeti aç" düğmesi anlamsızdır ve kapatma düğmesini örtebilir.
                if (activity is NsuppChatActivity) return
                start(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(cb)
        yasamDongusu = cb
    }

    /** Balon kipinde köşedeki ikonu göster. [NsuppSunumKipi.EKRAN] kipinde hiçbir şey yapmaz. */
    fun start(activity: Activity) {
        if (kip != NsuppSunumKipi.BALON) return
        val k = kokHazirla(activity) ?: return
        if (balon != null) return
        // Bu kökte ZATEN balon olabilir: [unut] yalnız REFERANSI bırakır, görünüm eski Activity'nin
        // kökünde DURUR (Activity yok edilmedi, yalnız arkaya alındı). A→B→A dönüşünde referans
        // null olduğu için üstüne bir yenisi eklenir ve her turda bir tane daha birikirdi; dokunma
        // en üsttekine gider, altındakiler sessizce yer ve bellek tutar. Uygulama-geneli yolda
        // ([start] ile `Application`) her öne gelişte tetiklendiği için bu birikim kaçınılmazdı.
        val varolan = k.findViewWithTag<View>(BALON_ETIKETI)
        if (varolan != null) {
            balon = varolan
            return
        }
        val cap = px(activity, NsuppOlculer.BALON_CAP_DP)
        val b = NsuppBalonGorunumu(activity, vurguRengi(activity))
        b.tag = BALON_ETIKETI
        b.setOnClickListener { toggle(activity) }
        k.addView(b, FrameLayout.LayoutParams(cap, cap, Gravity.BOTTOM or Gravity.END))
        kenarBoslugunuUygula(b, NsuppOlculer.KENAR_BOSLUK_DP)
        balon = b
    }

    /**
     * Sohbeti aç. [conversationId] verilirse (bildirimden gelindiyse) o konuşma açılır.
     */
    fun present(activity: Activity, conversationId: String? = null) {
        if (kip == NsuppSunumKipi.EKRAN) {
            // TAM EKRAN = ayrı Activity. Derin bağlantı da oraya taşınır; burada kendi kancamızı
            // kurmuyoruz, yoksa Activity'nin kancasıyla İKİSİ birden `openConversation` çağırırdı.
            NsuppChatActivity.start(activity, conversationId)
            return
        }
        val k = kokHazirla(activity) ?: return
        val chat = Nsupp.webChat
        if (chat == null) {
            Log.w(TAG, "Nsupp.init() çağrılmadan present() çağrıldı — sohbet açılmadı")
            return
        }
        // Balondaki ile aynı sebep: eski Activity'ye dönüldüğünde referans null'dır ama panel
        // görünümü o kökte hâlâ durur (içindeki WebView de canlıdır). Sahiplenmeseydik ikinci bir
        // panel eklenir, `dismiss()` yalnız yenisini gizler ve eskisi ekranda ASILI kalırdı.
        var p = panel ?: (k.findViewWithTag<View>(PANEL_ETIKETI) as? FrameLayout)?.also { panel = it }
        if (conversationId != null) {
            // Panel HENÜZ YOKSA sayfa daha yüklenmedi: komut kuyruğu oluşmadan `openConversation`
            // çağırmak boşa giderdi → yükleme geri-çağrımına bağlanır. Panel VARSA sayfa çoktan
            // yüklendi ve `onLoaded` bir daha çalışmaz; o hâlde doğrudan çağrılır. (Yalnız
            // geri-çağrımı kursaydık, açık panelde gelen bildirim hiçbir konuşmayı açmazdı.)
            if (p == null) {
                // Kanca KENDİNİ SİLER: kalsaydı sonraki her yeniden yükleme (ör. çıkışta
                // sıfırlama) bildirimden gelen o eski konuşmayı tekrar açardı.
                chat.onLoaded = {
                    chat.onLoaded = null
                    chat.openConversation(conversationId)
                }
            } else {
                chat.openConversation(conversationId)
            }
        }
        if (p == null) {
            p = panelOlustur(activity)
            k.addView(p, panelYerlesimi(activity))
            // Panel YALNIZ balon kipinde var, dolayısıyla alt boşluk her zaman balonun üstüdür.
            kenarBoslugunuUygula(p, NsuppOlculer.PANEL_ALT_BOSLUK_DP)
            panel = p
        }
        p.visibility = View.VISIBLE
        // Balon panelin ALTINDA kalır (web'de de panel launcher'ın üstünde çizilir).
        p.bringToFront()
        // Geri tuşu ancak odaklı görünüm zincirine ulaşır (bkz. sınıf belgesi).
        p.requestFocus()
    }

    /**
     * Paneli gizle. Oturum ve jeton KORUNUR — [reset] ile karıştırmayın.
     *
     * [NsuppSunumKipi.EKRAN] kipinde YAPACAK BİR ŞEY YOKTUR: sohbet ayrı bir Activity'dedir ve onu
     * dışarıdan kapatabilmek için presenter'ın o Activity'ye statik bir referans tutması gerekirdi —
     * yok edilmiş Activity'yi canlı tutan klasik sızıntı. Tam ekran sohbet geri tuşuyla kapanır.
     * Sessizce dönmüyoruz: sebebi bilmeyen satıcı "kapatma çalışmıyor" diye arar.
     */
    fun dismiss() {
        if (kip == NsuppSunumKipi.EKRAN) {
            Log.w(TAG, "EKRAN kipinde dismiss() yok — tam ekran sohbet geri tuşuyla kapanır")
            return
        }
        panel?.visibility = View.GONE
    }

    /** Balon kipinde aç/kapat. [NsuppSunumKipi.EKRAN] kipinde her çağrı sohbeti AÇAR (kapatmaz). */
    fun toggle(activity: Activity) {
        if (panel?.visibility == View.VISIBLE) dismiss() else present(activity)
    }

    /**
     * Çıkışta çağırın — jeton silinir, oturum ve sayfa sıfırlanır, panel kapanır.
     *
     * `Nsupp.reset()`i de KAPSAR: satıcının çıkışta tek bir çağrı yapması yeter. Panel açık
     * bırakılsaydı, sıfırlanmış oturumun üstünde duran pencere paylaşılan cihazda bir sonraki
     * kullanıcıyı karşılardı. Balon KALIR: destek çıkış yaptıktan sonra da kullanılabilir.
     */
    fun reset() {
        Nsupp.reset()
        // Panel YALNIZ balon kipinde var; EKRAN kipinde `dismiss()` yalnızca uyarı basardı.
        if (kip == NsuppSunumKipi.BALON) dismiss()
    }

    // ── Kurulum ──────────────────────────────────────────────────────────────────────────────

    private fun kokHazirla(activity: Activity): FrameLayout? {
        val yeni = activity.findViewById<View>(android.R.id.content) as? FrameLayout
        if (yeni == null) {
            Log.w(TAG, "içerik kökü bulunamadı, sohbet yüzeyi eklenmedi")
            return null
        }
        if (kok !== yeni) {
            // Başka bir Activity'ye geçildi: eski köke eklenmiş görünümler o Activity ile birlikte
            // gider; burada yalnız referansları bırakıyoruz.
            unut()
            kok = yeni
            yeni.addOnAttachStateChangeListener(kokDinleyici)
        }
        return yeni
    }

    private fun unut() {
        kok?.removeOnAttachStateChangeListener(kokDinleyici)
        kok = null
        balon = null
        panel = null
    }

    private fun panelOlustur(activity: Activity): FrameLayout {
        val kap = PanelKabi(activity)
        // Etiket, aynı köke ikinci bir panel eklenmesini engeller (bkz. [present]).
        kap.tag = PANEL_ETIKETI
        kap.background = GradientDrawable().apply {
            cornerRadius = px(activity, NsuppOlculer.PANEL_KOSE_DP).toFloat()
            // Sayfanın kendi arka planı zaten her şeyi boyar; bu zemin YALNIZ yuvarlatılmış köşe
            // ve gölge için var. Rengi temadan geliyor ki koyu kipte beyaz bir kenar parlamasın.
            setColor(temaRengi(activity, android.R.attr.colorBackground, Color.WHITE))
        }
        // Köşeler WebView'ı da kırpsın; kırpmasaydı sayfa köşelerin dışına taşardı.
        kap.clipToOutline = true
        // Web'deki `box-shadow: 0 8px 40px rgba(0,0,0,.18)` yerine platformun kendi gölgesi.
        kap.elevation = px(activity, GOLGE_DP).toFloat()
        kap.addView(
            NsuppWebChatView(activity),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return kap
    }

    private fun panelYerlesimi(activity: Activity): FrameLayout.LayoutParams {
        val (genislikDp, yukseklikDp) = kullanilabilirDp(activity)
        return FrameLayout.LayoutParams(
            px(activity, NsuppOlculer.panelGenisligiDp(genislikDp)),
            px(activity, NsuppOlculer.panelYuksekligiDp(yukseklikDp)),
            Gravity.BOTTOM or Gravity.END,
        )
    }

    /**
     * Panelin kabı — geri tuşunu ve yön değişimini üstlenir.
     *
     * `inner` çünkü ikisi de presenter'ın durumuna bakar; ayrı bir sınıf yapmak yalnızca geri
     * çağrım tutan bir kopya alan demekti.
     */
    private inner class PanelKabi(context: Context) : FrameLayout(context) {
        init {
            isFocusableInTouchMode = true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // Panel açıkken geri = paneli kapat. Yakalamasaydık geri tuşu Activity'yi kapatır ve
            // kullanıcı sohbeti açtığı için satıcının ekranından ÇIKARDI.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            // Satıcı `android:configChanges` bildirdiyse Activity YENİDEN OLUŞMAZ; panel eski
            // yönün ölçüsünde kalır ve yatayda ekrandan taşardı.
            //
            // Ölçü `newConfig`ten OKUNMUYOR — `newConfig` yalnız "bir şey değişti" sinyali.
            // Sebep [kullanilabilirDp]'de: Android 15'te `screenWidthDp/screenHeightDp` sistem
            // çubuklarını ARTIK dışarıda bırakmıyor, ölçünün tek kaynağı WindowInsets'tir.
            val act = context as? Activity ?: return
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return
            val (genislikDp, yukseklikDp) = kullanilabilirDp(act)
            lp.width = px(act, NsuppOlculer.panelGenisligiDp(genislikDp))
            lp.height = px(act, NsuppOlculer.panelYuksekligiDp(yukseklikDp))
            layoutParams = lp
        }
    }

    /**
     * Görünümü sistem çubuklarının DIŞINDA tut — bu KONUM işidir, BOYUT işi değil.
     *
     * Android 15'te (targetSdk 35) pencere kenardan kenara açılır: boşluk eklenmezse balon ve
     * panel gezinme çubuğunun ALTINDA kalır — dokunulamaz bir destek düğmesi.
     * Boşluklar TÜKETİLMEZ (`insets` olduğu gibi döner), yoksa satıcının kendi düzeni boşluk
     * bilgisini alamaz ve onun arayüzü çubukların altına girerdi.
     *
     * ⚠️ Panelin BOYUTU inset'i ZATEN dışarıda bırakmış bir ölçüden gelir ([kullanilabilirDp]);
     * buradaki `alt + sistemAlt` yalnız paneli çubuğun üstüne İTER. İkisi aynı inset'i iki kez
     * saymamalı — kusur tam olarak oydu.
     */
    private fun kenarBoslugunuUygula(v: View, altDp: Int) {
        val alt = px(v.context, altDp)
        val yan = px(v.context, NsuppOlculer.KENAR_BOSLUK_DP)
        // Taban boşluk HEMEN yazılır: boşluğu yalnız dinleyiciye bıraksaydık, sistem çubuğu
        // boşluğu hiç dağıtılmayan bir düzende (kenardan kenara olmayan pencere) balon ekranın
        // köşesine YAPIŞIRDI.
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.bottomMargin = alt
            lp.marginEnd = yan
            v.layoutParams = lp
        }
        v.setOnApplyWindowInsetsListener { gorunum, insets ->
            val lp = gorunum.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                val (sistemAlt, sistemSag) = sistemBosluklari(insets)
                lp.bottomMargin = alt + sistemAlt
                lp.marginEnd = yan + sistemSag
                gorunum.layoutParams = lp
            }
            insets
        }
        v.requestApplyInsets()
    }

    /** Sistem çubuklarının (alt, sağ) boşluğu. */
    private fun sistemBosluklari(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(WindowInsets.Type.systemBars())
            i.bottom to i.right
        } else {
            eskiSistemBosluklari(insets)
        }

    /**
     * API 30 ÖNCESİ yol. Ayrı fonksiyon çünkü `@Suppress` bir ATAMA satırına konamaz; kullanımdan
     * kaldırılmış çağrıyı buraya toplamak, uyarıyı susturmanın tek temiz yolu.
     */
    @Suppress("DEPRECATION")
    private fun eskiSistemBosluklari(insets: WindowInsets): Pair<Int, Int> =
        insets.systemWindowInsetBottom to insets.systemWindowInsetRight

    private companion object {
        const val TAG = "NsuppChatPresenter"
        const val GOLGE_DP = 12

        /**
         * Görünüm etiketleri — "bu köke zaten koyduk mu" sorusunun cevabı.
         *
         * Alan referansı yetmez: referans presenter'a, görünüm ise Activity'nin köküne bağlıdır ve
         * ikisi başka zamanlarda ölür. Ad-alanlı sabit dizeler; satıcının kendi `setTag` kullanımıyla
         * çakışmaz.
         */
        const val BALON_ETIKETI = "nsupp:balon"
        const val PANEL_ETIKETI = "nsupp:panel"
    }
}

/**
 * Köşedeki balon ikonu.
 *
 * Rengi çalışma alanından ÇEKMİYORUZ: bu ikon sohbet açılmadan önce görünür, yani widget
 * yapılandırması henüz indirilmemiştir. Rengi ağdan beklemek, balonun geç ve renk atlayarak
 * belirmesi demekti; bu yüzden uygulamanın kendi vurgu rengi kullanılır — satıcının arayüzüne
 * zaten uyar. Kendi ikonunu isteyen satıcı balonu hiç kullanmaz: [NsuppSunumKipi.EKRAN] kipinde
 * kendi düğmesinden `present()` çağırır.
 */
private class NsuppBalonGorunumu(context: Context, renk: Int) : View(context) {
    private val boya = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val govde = RectF()
    private val kuyruk = Path()

    init {
        // Daire ARKA PLANDAN gelir, `onDraw`dan değil: yükseklik gölgesi görünümün ana hattından
        // (outline) türetilir ve ana hattı veren şey arka plan çiziminin kendisidir.
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(renk)
        }
        elevation = px(context, 6).toFloat()
        isClickable = true
        contentDescription = NsuppTexts.openChatLabel
    }

    /** Sohbet baloncuğu — web'deki launcher simgesiyle aynı fikir; ölçüler çapa göre orantılı. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cap = minOf(width, height).toFloat()
        if (cap <= 0f) return
        val g = cap * 0.46f
        val y = g * 0.72f
        val sol = (width - g) / 2f
        val ust = (height - y) / 2f - cap * 0.03f
        govde.set(sol, ust, sol + g, ust + y)
        canvas.drawRoundRect(govde, y * 0.34f, y * 0.34f, boya)
        kuyruk.reset()
        kuyruk.moveTo(sol + g * 0.22f, ust + y)
        kuyruk.lineTo(sol + g * 0.22f, ust + y + g * 0.20f)
        kuyruk.lineTo(sol + g * 0.50f, ust + y)
        kuyruk.close()
        canvas.drawPath(kuyruk, boya)
    }
}

/**
 * Sohbet yüzeyinin sığacağı KULLANILABİLİR alan (dp) — sistem çubukları ÇIKARILMIŞ. TEK kaynak.
 *
 * 🔴 `Configuration.screenWidthDp/screenHeightDp` KULLANILMAZ. Android 15 resmî davranış
 * değişikliği: "`Configuration.screenWidthDp` and `screenHeightDp` sizes no longer exclude the
 * system bars" ve önerilen alternatifler "an appropriate `ViewGroup`, `WindowInsets`, or
 * `WindowMetricsCalculator`"dır (developer.android.com/about/versions/15/behavior-changes-15).
 * `compileSdk = 35` olduğu için bu davranış BİZİ bağlar.
 *
 * KAPATILAN KUSUR — ÇİFT SAYIM: eski kod panelin yüksekliğini `screenHeightDp`ten (Android 15'te
 * çubuklar DAHİL) hesaplıyor, ÜSTÜNE bir de sistem çubuğu boşluğunu `bottomMargin`e ekliyordu.
 * 48 dp'lik gezinme çubuğunda panelin ÜST kenarı ekranın 4 dp DIŞINA çıkıyordu (76 + (yükseklik −
 * 120) + 48 > yükseklik). Artık boyut kullanılabilir alandan, konum inset'ten gelir.
 *
 * `WindowMetricsCalculator` (androidx.window) YERİNE platformun kendi `WindowMetrics`i: aynı sayıyı
 * verir ve bir SDK'nın taşıdığı her bağımlılık satıcının uygulamasına dayatılır (bkz. build.gradle.kts
 * bağımlılık politikası).
 *
 * API 30 ÖNCESİ `Configuration`a düşülür (`WindowMetrics` o sürümlerde YOK): Android 15 davranışı
 * ancak API 30+ cihazlarda görülebilir, daha eskisinde `screenWidthDp/screenHeightDp` çubukları
 * zaten dışarıda bırakır ve kenardan-kenara OLMAYAN pencerede inset de 0 gelir — çift sayım oluşmaz.
 * DÜRÜST SINIR: API 24–29'da satıcı ESKİ bayraklarla (`SYSTEM_UI_FLAG_LAYOUT_*`) penceresini kendisi
 * kenardan kenara açarsa çift sayım orada kalır. Kapatmanın yolu `decorView.rootWindowInsets`tir,
 * ama o alan görünüm pencereye BAĞLANMADAN null döner ve `present()` `onCreate`ten çağrılabilir —
 * güvenilir değil. O yüzden bu sürümlerde davranış DEĞİŞTİRİLMEDİ.
 */
private fun kullanilabilirDp(activity: Activity): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val olcum = activity.windowManager.currentWindowMetrics
        val bosluk = olcum.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val yogunluk = activity.resources.displayMetrics.density
        // `density == 0` yalnız bozuk bir DisplayMetrics'te olur; sıfıra bölmek yerine
        // Configuration'a düşmek, paneli "genişlik 0" ile görünmez yapmaktan iyidir.
        if (yogunluk > 0f) {
            return ((olcum.bounds.width() - bosluk.left - bosluk.right) / yogunluk).toInt() to
                ((olcum.bounds.height() - bosluk.top - bosluk.bottom) / yogunluk).toInt()
        }
    }
    val c = activity.resources.configuration
    return c.screenWidthDp to c.screenHeightDp
}

private fun px(context: Context, dp: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    dp.toFloat(),
    context.resources.displayMetrics,
).toInt()

/** Temadan renk oku; tema o özniteliği tanımlamıyorsa [yedek] (sessizce siyah çizmemek için). */
private fun temaRengi(context: Context, oznitelik: Int, yedek: Int): Int {
    val a = context.theme.obtainStyledAttributes(intArrayOf(oznitelik))
    try {
        return a.getColor(0, yedek)
    } finally {
        a.recycle()
    }
}

private fun vurguRengi(context: Context): Int =
    temaRengi(context, android.R.attr.colorAccent, VARSAYILAN_VURGU)

/** Tema vurgu rengi vermezse: widget'ın varsayılan rengi (`widget.js` → `safeColor` = #4f46e5). */
private const val VARSAYILAN_VURGU = 0xFF4F46E5.toInt()
