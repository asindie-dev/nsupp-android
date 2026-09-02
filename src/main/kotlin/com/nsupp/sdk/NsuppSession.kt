package com.nsupp.sdk

/**
 * Sohbet oturumu — ziyaretçi kimliği, mesaj listesi ve yoklama döngüsünün MANTIĞI.
 *
 * ── NEDEN BURADA Android YOK ─────────────────────────────────────────────────────────────────
 * Bu sınıf ne Context ne Handler ne Compose bilir; jeton saklama [NsuppTokenStore] arayüzünün,
 * zamanlama ise çağıranın işidir. Sonuç: oturum mantığı JVM'de test edilir, emülatör gerekmez.
 * Android kabuğu (SharedPreferences + coroutine döngüsü) bunun ÜSTÜNE ince bir katman olarak biner.
 *
 * ── ZİYARETÇİ JETONU NEREDE DURMALI ──────────────────────────────────────────────────────────
 * Jeton kimlik doğrulama sırrı değil, ANONİM oturum tanıtıcısıdır (web'de localStorage'ın karşılığı).
 * EncryptedSharedPreferences'a koymak "uygulama silinip kurulunca sohbet geçmişi geri gelsin" demek
 * olurdu — kullanıcı "verilerimi sildim" derken beklediği bu değildir. Sade SharedPreferences,
 * uygulama kaldırılınca gider: doğru davranış budur. (Aynı gerekçe iOS SDK'da da yazılı.)
 */
interface NsuppTokenStore {
    fun read(): String?
    fun write(token: String?)
}

/** Testte ve JVM'de kullanılan bellek-içi saklama. */
class InMemoryTokenStore(private var value: String? = null) : NsuppTokenStore {
    override fun read(): String? = value
    override fun write(token: String?) { value = token }
}

/** Oturumun arayüze verdiği anlık durum. Değişmez (immutable) → arayüz güvenle yeniden çizer. */
data class NsuppState(
    val messages: List<NsuppMessage> = emptyList(),
    val operatorTyping: Boolean = false,
    val operatorsOnline: Boolean = false,
    /** Kullanıcıya gösterilecek hata; null = sorun yok. */
    val error: String? = null,
    val conversationId: String? = null,
    /**
     * Sunucunun son kimlik teşhisi (`valid`/`invalid`/`unsigned`/`no_secret` ya da kaynak adı).
     * Entegrasyonu kuran geliştirici "neden doğrulanmadı"yı buradan görür.
     */
    val identityStatus: String? = null,
    /** Konuşma çözüldü ve puan bekliyor → arayüz CSAT sorar. */
    val pendingRating: Boolean = false,
    /** Yüklenmiş yardım merkezi makaleleri ([NsuppSession.loadArticles] doldurur). */
    val articles: List<NsuppArticle> = emptyList(),
    /**
     * Çalışma alanının widget yapılandırması (renk, metinler, logo) — [NsuppSession.start] doldurur.
     * Arayüz görünümünü BURADAN alır: ayar panelde bir kez yapılır, her yüzeyde aynı görünür.
     */
    val config: NsuppPublicConfig? = null,
) {
    /**
     * Panelde tanımlı metni çöz; yoksa gömülü karşılık. Sıra widget.js ile AYNI — satıcının
     * yazdığı metin her yüzeyde aynı çıkmalı.
     */
    fun t(key: String, tr: String, en: String): String =
        config?.text(key, tr, en) ?: NsuppTexts.t(tr, en)

    /** Marka rengi (`#rrggbb`) — panelde ayarlanan; yoksa null (arayüz kendi vurgusunu kullanır). */
    val brandColor: String? get() = config?.color
}

class NsuppSession(
    private val api: NsuppApi,
    private val store: NsuppTokenStore,
) {
    /** Durum değişince çağrılır. Android kabuğu bunu ana iş parçacığına taşır. */
    var onState: ((NsuppState) -> Unit)? = null

    var state: NsuppState = NsuppState()
        private set

    /** Mesaj kimlikleri — yoklama örtüşmesinde aynı mesaj iki kez eklenmesin. */
    private val seen = mutableSetOf<String>()
    private var lastTs: String? = null

    /**
     * Döngü durmalı mı — kalıcı hata ya da [reset] sonrası true. Zamanlayıcı ÇAĞIRANDA olduğu için
     * bu sınıf döngüyü kendisi kesemez; bayrağı okuyup duran taraf Android kabuğudur.
     */
    var stopped: Boolean = false
        private set

    /**
     * OTURUM NESLİ (epok). [reset] her çağrıldığında artar.
     *
     * 🔴 NİÇİN [stopped] YETMEZ: `stopped` bir "döngü dursun" bayrağıdır ve [start] girişte onu
     * `false` yapar — yani çıkış, UÇUŞTAKİ bir isteği geçersiz kılmıyordu. Buradaki her ağ çağrısı
     * BLOKLAYAN bir HTTP isteğidir; çağıranın coroutine'ini iptal etmek `HttpURLConnection`ı
     * kesmez, istek tamamlanır ve yanıt geri döner. Kapı olmasaydı o yanıt çıkış yapan kullanıcının
     * verisini geri yazardı:
     *  ① [start] → `store.write(t)`: silinen jeton geri gelir, sonraki açılış ESKİ ziyaretçiyi
     *     sürdürür (jeton deposu `NsuppWebChat` ile PAYLAŞILDIĞI için orada kapatılan aynı kusur
     *     buradan geri geliyordu),
     *  ② [gonderKimlik] → [kimlikEmail]: sonraki kullanıcının [setSessionData] çağrısı ÖNCEKİ
     *     kişinin CRM kaydına yazardı,
     *  ③ [pollOnce]/[send] → `state.messages`: temizlenmiş ekrana öncekinin mesajları geri düşerdi.
     *
     * Atomik: [reset] arayüz (ana) iş parçacığından çağrılır, istekler ise IO'da koşar — sıradan
     * bir `Int` artışının görünürlüğü garanti değildir.
     *
     * Kural: bir ağ çağrısından SONRA ziyaretçiye ait bir alan yazılacaksa, önce çağrı başındaki
     * nesil hâlâ geçerli mi diye BAKILIR.
     */
    private val nesil = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * [startNewConversation] çağrıldı ve HENÜZ mesaj gönderilmedi. Bir sonraki gönderim sunucuya
     * "yeni konu" bayrağını taşır; taşımazsak mesaj eski konuşmaya düşerdi.
     */
    private var yeniKonuBekliyor = false

    /** Oturum açılmadan önce verilen kimlik — [start] sonrası gönderilir. */
    private class Kimlik(
        val email: String,
        val name: String?,
        val signature: String?,
        val attributes: MutableMap<String, Any?>,
    )

    private var bekleyenKimlik: Kimlik? = null

    /**
     * Son tanıtılan e-posta/imza — [setSessionData] bunları yeniden kullanır: sunucu `/identify`
     * ucunda e-posta ister, öznitelik yazmak için kimliği yeniden söylemek gerekir.
     */
    private var kimlikEmail: String? = null
    private var kimlikImza: String? = null

    val visitorToken: String? get() = store.read()

    /** Oturumu aç (ilk açılışta ziyaretçi üretilir) ve geçmişi yükle. */
    fun start() {
        stopped = false
        val n = nesil.get()
        try {
            val r = api.openSession(store.read())
            // Uçuşta çıkış yapıldı (bkz. [nesil]): bu yanıt SİLİNMİŞ ziyaretçiye ait, tek bir alanı
            // bile yazamaz. Hata da basmayız — kullanıcı hata yapmadı, oturumu kendisi kapattı.
            if (n != nesil.get()) return
            // KISITLI (sayfa/ülke/IP kuralı): sunucu sohbeti kapattı. Sessiz boş ekran DEĞİL —
            // "sessiz kilit = teşhis edilemez hata"; arayüz sebebi gösterebilsin.
            if (r.restricted) {
                // Jeton BU DALDA DA saklanır: saklamazsak her açılışta sunucu YENİ ziyaretçi satırı
                // (ve onunla IP/coğrafya kaydı) üretir — kısıtlanmış bir ziyaretçi için sınırsız
                // kayıt biriktirmek veri minimizasyonuna aykırı. (widget.js doğrusunu yapıyor.)
                r.visitorToken?.let { store.write(it) }
                emit(state.copy(error = NsuppTexts.restricted))
                return
            }
            r.visitorToken?.let { store.write(it) }
            emit(apply(state.copy(
                error = null,
                conversationId = r.conversationId,
                pendingRating = r.pendingRating,
                config = r.config ?: state.config,
            ), r.messages, advanceCursor = true))
            // Oturumdan ÖNCE verilen kimlik şimdi gönderilir (yoksa müşteri anonim kalırdı).
            bekleyenKimlik?.let { k -> bekleyenKimlik = null; gonderKimlik(k) }
        } catch (e: Exception) {
            if (n != nesil.get()) return
            emit(state.copy(error = e.message ?: NsuppTexts.connectFailed))
        }
    }

    /**
     * Mesaj gönder. Boş/boşluk metin GÖNDERİLMEZ (sunucu da reddeder; kullanıcıyı bekletme).
     *
     * @return gönderim başarılıysa true. Arayüz bunu okuyup BAŞARISIZSA yazılan metni geri koyar —
     *   aksi halde ağ hatasında kullanıcının yazdığı mesaj buharlaşır ve yeniden yazması gerekir.
     */
    fun send(text: String): Boolean {
        val clean = text.trim()
        val token = store.read()
        if (clean.isEmpty() || token == null) return false
        val n = nesil.get()
        return try {
            val r = api.sendMessage(token, clean, state.conversationId, newConversation = yeniKonuBekliyor)
            // Uçuşta çıkış yapıldı: sunucu mesajı KABUL ETTİ (bu yüzden `true`), ama temizlenmiş
            // ekrana çıkan kullanıcının balonunu geri basmayız (bkz. [nesil]).
            if (n != nesil.get()) return true
            yeniKonuBekliyor = false
            var next = state.copy(error = null, conversationId = r.conversationId ?: state.conversationId)
            if (r.message != null) next = apply(next, listOf(r.message), advanceCursor = false)
            emit(next)
            true
        } catch (e: Exception) {
            if (n != nesil.get()) return false
            emit(state.copy(error = e.message ?: NsuppTexts.sendFailed))
            false
        }
    }

    /**
     * Bir yoklama turu. Zamanlayıcı ÇAĞIRANDA — bu sınıf iş parçacığı yönetmez.
     * Hata SESSİZ: geçici ağ kesintisinde ekrana hata basmak gürültüdür, gönderim hatası zaten görünür.
     */
    fun pollOnce() {
        val token = store.read() ?: return
        // "Yeni konu" bayrağı beklerken yoklama YAPILMAZ: `conversationId` yokken sunucu AÇIK
        // konuşmayı döndürür ve az önce temizlediğimiz eski mesajları geri getirirdi.
        if (yeniKonuBekliyor) return
        val n = nesil.get()
        try {
            val r = api.poll(token, state.conversationId, lastTs)
            // Uçuşta çıkış yapıldı: bu mesajlar ÇIKAN kullanıcınındı, temizlenen ekrana geri
            // dökülemezler (bkz. [nesil]). Coroutine iptali bloklayan isteği kesmediği için bu
            // yanıt reset'ten SONRA döner.
            if (n != nesil.get()) return
            var next = state.copy(
                conversationId = r.conversationId ?: state.conversationId,
                operatorTyping = r.operatorTyping,
                operatorsOnline = r.operatorsOnline ?: state.operatorsOnline,
                pendingRating = r.pendingRating,
            )
            next = apply(next, r.messages, advanceCursor = true)
            emit(next)
        } catch (e: NsuppServerException) {
            // KALICI hata (401/403/404 — oturum geçersiz, ziyaretçi engellendi) sessiz kalamaz:
            // döngü sonsuza kadar aynı hatayı alarak pili ve sunucuyu boşuna yakar, kullanıcı da
            // ekranın neden donduğunu anlamaz. `stopped` çağıranın döngüsünü durdurur.
            if (n != nesil.get()) return
            if (e.isPermanent) {
                stopped = true
                emit(state.copy(error = e.message ?: "Session invalid"))
            }
        } catch (_: Exception) {
            // GEÇİCİ hata SESSİZ: ağ kesintisinde ekrana hata basmak gürültüdür; gönderim hatası
            // zaten görünür.
        }
    }

    // ── Kimlik / oturum verisi / olay ──

    /**
     * Ziyaretçiyi tanıt — operatör kimin yazdığını görsün, VIP/segment yönlendirmesi çalışsın.
     *
     * Oturum henüz açılmadıysa kimlik BEKLETİLİR ve ilk [start] sonrası gönderilir; bu yüzden
     * kullanıcı giriş yaptığı anda güvenle çağırabilirsiniz. Kuyruk olmasaydı çağrı sessizce düşer
     * ve müşteri operatörde ANONİM görünürdü — VIP/segment yönlendirmesi de hiç çalışmazdı.
     *
     * @param signature `HMAC-SHA256(email, identity_secret)` — **sunucunuzda** üretin. Kimlik
     *   doğrulama zorunluysa imzasız çağrı 403 alır ve sebebi [NsuppState.identityStatus] taşır.
     * @param attributes özel öznitelikler. Segment yönlendirmesi için `mapOf("segments" to listOf("vip"))`.
     */
    fun identify(
        email: String,
        name: String? = null,
        signature: String? = null,
        attributes: Map<String, Any?>? = null,
    ): Boolean {
        val k = Kimlik(email, name, signature, (attributes ?: emptyMap()).toMutableMap())
        if (store.read() == null) {
            // 🔴 OTURUMU BURADA BAŞLATIRIZ. Eskiden yalnız kuyruğa alıp `start()`i beklerdik; sohbet
            // arayüzü WebView'a taşındıktan sonra `start()` ARTIK ÇAĞRILMIYOR ve kimlik sonsuza
            // kadar kuyrukta kalıyordu — müşteri operatörde hep anonim görünürdü. Ziyaretçi jetonu
            // WebView ile PAYLAŞILAN depoda tutulduğu için burada üretilen oturum, sohbet açıldığında
            // widget'ın devam ettirdiği oturumun TA KENDİSİDİR.
            bekleyenKimlik = k
            start() // başarılıysa kuyruğu kendisi boşaltır
            return store.read() != null
        }
        return gonderKimlik(k)
    }

    /**
     * Özel öznitelik yaz/güncelle (Crisp'in `session.setString/setInt/setBool` karşılığı).
     * Sunucu MERGE eder: verilmeyen anahtarlar korunur.
     *
     * ⚠️ **Önce [identify] gerekir.** Öznitelikler CRM'deki KİŞİ kaydında yaşar ve kişi e-posta ile
     * doğar; e-posta yokken yazacak bir yer yoktur (web SDK'sı da aynı kuralla çalışır). Kimlik
     * henüz gönderilmediyse öznitelikler bekleyen kimliğe eklenir; hiç verilmediyse `false` döner —
     * sessizce yutulmaz.
     */
    fun setSessionData(attributes: Map<String, Any?>): Boolean {
        bekleyenKimlik?.let { it.attributes.putAll(attributes); return true }
        val email = kimlikEmail ?: run {
            emit(state.copy(error = NsuppTexts.identifyFirst))
            return false
        }
        return gonderKimlik(Kimlik(email, null, kimlikImza, attributes.toMutableMap()))
    }

    /**
     * Segmentleri ayarla (VIP/plan yönlendirmesi). Sunucudaki `attributes.segments` anahtarını
     * DEĞİŞTİRİR (birleştirmez) — istemci mevcut segmentleri bilmediği için birleştirme sözü
     * veremezdik; verseydik yalan olurdu.
     */
    fun setSegments(segments: List<String>): Boolean = setSessionData(mapOf("segments" to segments))

    /**
     * Özel olay bildir. Oturum yoksa `false` döner: olay BİR ANA aittir, kuyruğa alıp sonra
     * göndermek onu yalan yapardı.
     */
    fun trackEvent(name: String): Boolean {
        // Olay TETİKLEYİCİ koşullarını besler ve sohbet açılmadan ÖNCE de anlamlıdır (proaktif
        // mesaj tam da bunun için var). Oturum yoksa üretiriz — yoksa uygulamada tetikleyiciler
        // hiç çalışmazdı.
        if (store.read() == null) start()
        val token = store.read() ?: return false
        return try { api.trackEvent(token, name); true } catch (_: Exception) { false }
    }

    /**
     * Konuşmayı puanla (CSAT, 1–5). [NsuppState.pendingRating] true iken sorulur.
     * Başarılıysa bayrak düşer — aynı konuşma ikinci kez sorulmaz.
     */
    fun rate(score: Int, comment: String? = null): Boolean {
        val token = store.read() ?: return false
        val conv = state.conversationId ?: return false
        return try {
            api.rate(token, conv, score, comment)
            emit(state.copy(pendingRating = false))
            true
        } catch (e: Exception) {
            emit(state.copy(error = e.message))
            false
        }
    }

    /** Bir mesaj tetikleyicisini çalıştır (Crisp'in `runBotScenario` karşılığı). */
    fun runTrigger(identifier: String): Boolean {
        val token = store.read() ?: return false
        return try { api.runTrigger(token, identifier); true } catch (_: Exception) { false }
    }

    // ── Yardım merkezi (KB) ──

    /**
     * Yayınlı makaleleri yükle ([NsuppState.articles] doldurulur).
     *
     * Bunu göstermek, sohbeti hiç açmadan çözülen sorular demektir — self-servis. Mobilde bu yüzey
     * hiç yoktu, yani mobil kanalda yönlendirme (deflection) oranı SIFIRDI.
     *
     * Yardım merkezi şifreliyse hata `kb_locked` taşır: "makale yok" ile "makaleler kilitli" ayrı
     * şeylerdir ve ikincisini boş listeye çevirmek yalan olurdu.
     */
    fun loadArticles(locale: String? = null): Boolean = try {
        emit(state.copy(articles = api.articles(locale), error = null))
        true
    } catch (e: Exception) {
        emit(state.copy(error = e.message))
        false
    }

    /** Makale ara. Sorgu boşsa yüklü liste döner; hata durumunda BOŞ dizi + sebep. */
    fun searchArticles(query: String, locale: String? = null): List<NsuppArticle> {
        val q = query.trim()
        if (q.isEmpty()) return state.articles
        return try {
            api.searchArticles(q, locale)
        } catch (e: Exception) {
            emit(state.copy(error = e.message))
            emptyList()
        }
    }

    /** Tek makaleyi getir (görüntülenme sayacı sunucuda artar). */
    fun article(slug: String, locale: String? = null): NsuppArticle? = try {
        api.article(slug, locale)
    } catch (e: Exception) {
        emit(state.copy(error = e.message))
        null
    }

    /**
     * Oturumu tamamen sıfırla — **kullanıcı uygulamanızdan ÇIKIŞ yaptığında çağırın.**
     *
     * Jetonu siler, ekrandaki her şeyi temizler ve döngünün durmasını işaretler. Bu olmadan
     * paylaşılan bir cihazda bir sonraki kullanıcı, öncekinin sohbet geçmişini açar — jeton
     * cihazda kalıcıdır ve kimliğe değil CİHAZA bağlıdır.
     *
     * UÇUŞTAKİ İSTEKLER DE GEÇERSİZDİR: `stopped` yalnız döngüyü durdurur ve [start] onu girişte
     * `false` yapar; sunucuya çoktan gitmiş bir `/session`/`/identify` çağrısının yanıtı yine de
     * döner. Nesil artışı o yanıtların hiçbir şey yazamamasını sağlar (bkz. [nesil]) — aksi hâlde
     * silinen jeton geri gelirdi.
     *
     * @param pushDeviceToken bu cihazın SUNUCUDA kayıtlı FCM jetonu (varsa). Verilirse çıkış
     *   sunucuya da ulaşır: kayıt satırı düşürülür. Verilmezse yalnız YEREL çıkış yapılır —
     *   çekirdeği doğrudan kullanan (Android kabuğunu kullanmayan) çağıranların davranışı
     *   değişmez. Değeri kabuk tutar ([com.nsupp.sdk.android.Nsupp]); bu sınıf saf Kotlin'dir
     *   ve `SharedPreferences` göremez.
     *
     * 🔴 SUNUCU ADIMI YEREL SİLMEDEN ÖNCE VE BU FONKSİYONUN İÇİNDE — çağrı yerinde DEĞİL:
     *    ① sunucu sahibi ZİYARETÇİ JETONUNDAN bulur; `store.write(null)`dan sonra çağırsaydık
     *       elimizde kimlik kalmaz ve istek 401 alırdı (sessizce hiçbir şey silinmezdi).
     *    ② darboğaza koymak, ileride açılacak ikinci bir çıkış yolunun bu adımı ATLAMASINI
     *       imkânsız kılar (aynı ders operatör düzleminde `upsertOperatorPushDevice`te ödendi).
     */
    fun reset(pushDeviceToken: String? = null) {
        stopped = true
        nesil.incrementAndGet()
        // Ağ adımı bloklar ve BAŞARISIZ OLABİLİR (uçak modunda çıkış); yerel çıkışı ona
        // bağlamayız — jeton her hâlükârda silinir, sunucu satırı için saklama süresi tavandır.
        if (pushDeviceToken != null) {
            val token = store.read()
            if (token != null) try { api.unregisterDevice(token, pushDeviceToken) } catch (_: Exception) {}
        }
        store.write(null)
        seen.clear()
        lastTs = null
        bekleyenKimlik = null
        kimlikEmail = null
        kimlikImza = null
        emit(NsuppState())
    }

    /**
     * Yeni bir konu başlat (önceki konular KAPANMAZ — çoklu konuşma).
     *
     * Konuşma İLK MESAJLA doğar: bu çağrı ekranı temizler ve bir sonraki gönderime "yeni konu"
     * bayrağını iliştirir. Boş bir konuşma satırı üretip operatörün gelen kutusunu kirletmeyiz.
     *
     * ⚠️ Bayrak beklerken yoklama DURAKLAR ([pollOnce] erken döner): `conversationId` yokken sunucu
     * "konuşma yok" sorusuna AÇIK konuşmayı döndürür — yani temizlenen eski mesajlar geri gelirdi.
     */
    fun startNewConversation() {
        yeniKonuBekliyor = true
        seen.clear(); lastTs = null
        emit(state.copy(messages = emptyList(), conversationId = null, error = null))
    }

    /** Var olan bir konuya geç. */
    fun openConversation(id: String) {
        if (id == state.conversationId) return
        // Bekleyen "yeni konu" isteği iptal: kullanıcı fikrini değiştirip var olan bir konuyu açtı.
        yeniKonuBekliyor = false
        seen.clear(); lastTs = null
        emit(state.copy(messages = emptyList(), conversationId = id, error = null))
    }

    fun conversations(): List<NsuppConversation> {
        val token = store.read() ?: return emptyList()
        return try { api.listConversations(token) } catch (_: Exception) { emptyList() }
    }

    /**
     * FCM jetonunu sunucuya bildir. `FirebaseMessagingService.onNewToken` ve uygulama açılışından
     * çağrılır. Ziyaretçi henüz yoksa sessizce atlanır — cihaz sahipsiz kaydedilemez.
     */
    fun registerPushToken(fcmToken: String, bundleId: String? = null) {
        val token = store.read() ?: return
        try { api.registerDevice(token, fcmToken, bundleId) } catch (_: Exception) {}
    }

    // ── iç ──

    private fun gonderKimlik(k: Kimlik): Boolean {
        val token = store.read() ?: return false
        val n = nesil.get()
        return try {
            val r = api.identify(token, k.email, k.name, k.signature, k.attributes.ifEmpty { null })
            // Uçuşta çıkış yapıldı: e-postayı SAKLAMAYIZ. Saklasaydık sonraki kullanıcının
            // [setSessionData] çağrısı ÖNCEKİ kişinin CRM kaydına yazardı (bkz. [nesil]).
            if (n != nesil.get()) return false
            kimlikEmail = k.email
            kimlikImza = k.signature
            emit(state.copy(identityStatus = r.identitySource ?: r.identity))
            true
        } catch (e: Exception) {
            // Kimlik hatası SESSİZ DÜŞMEZ: zorunluluk açıkken sunucu 403 döner ve entegrasyonu
            // kuran geliştirici sebebi göremezse "neden anonim görünüyor" sorusu çözümsüz kalır.
            if (n != nesil.get()) return false
            emit(state.copy(error = e.message, identityStatus = e.message))
            false
        }
    }

    /**
     * 🔴 [advanceCursor] NEDEN VAR: imleç ([lastTs]) YALNIZ sunucudan LİSTE olarak gelen mesajlarla
     * ilerler. Kendi gönderdiğimiz mesajla ilerletirsek şu senaryo mesaj KAYBEDER: operatör T1'de
     * yazar, ziyaretçi yoklamadan önce T2 > T1'de yazar, imleç T2 olur, sonraki yoklama `after=T2`
     * der ve operatörün T1 mesajı sonsuza kadar ATLANIR. Kendi mesajımızı sonraki yoklamada tekrar
     * görmek zararsızdır — [seen] zaten tekilliyor.
     */
    private fun apply(base: NsuppState, incoming: List<NsuppMessage>, advanceCursor: Boolean): NsuppState {
        if (incoming.isEmpty()) return base
        val list = base.messages.toMutableList()
        for (m in incoming) {
            if (!seen.add(m.id)) continue
            list.add(m)
            if (advanceCursor && (lastTs == null || m.createdAt > lastTs!!)) lastTs = m.createdAt
        }
        return base.copy(messages = list)
    }

    private fun emit(next: NsuppState) {
        state = next
        onState?.invoke(next)
    }
}
