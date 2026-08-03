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
        try {
            val r = api.openSession(store.read())
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
        return try {
            val r = api.sendMessage(token, clean, state.conversationId, newConversation = yeniKonuBekliyor)
            yeniKonuBekliyor = false
            var next = state.copy(error = null, conversationId = r.conversationId ?: state.conversationId)
            if (r.message != null) next = apply(next, listOf(r.message), advanceCursor = false)
            emit(next)
            true
        } catch (e: Exception) {
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
        try {
            val r = api.poll(token, state.conversationId, lastTs)
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
            if (e.isPermanent) {
                stopped = true
                emit(state.copy(error = e.message ?: "Oturum geçersiz"))
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
            bekleyenKimlik = k
            return true // kuyruğa alındı; start() gönderecek
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
     */
    fun reset() {
        stopped = true
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
        return try {
            val r = api.identify(token, k.email, k.name, k.signature, k.attributes.ifEmpty { null })
            kimlikEmail = k.email
            kimlikImza = k.signature
            emit(state.copy(identityStatus = r.identitySource ?: r.identity))
            true
        } catch (e: Exception) {
            // Kimlik hatası SESSİZ DÜŞMEZ: zorunluluk açıkken sunucu 403 döner ve entegrasyonu
            // kuran geliştirici sebebi göremezse "neden anonim görünüyor" sorusu çözümsüz kalır.
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
