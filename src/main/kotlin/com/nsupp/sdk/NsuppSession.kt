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
)

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
                emit(state.copy(error = "Destek bu uygulamada şu an kullanılamıyor."))
                return
            }
            r.visitorToken?.let { store.write(it) }
            emit(apply(state.copy(error = null, conversationId = r.conversationId), r.messages, advanceCursor = true))
        } catch (e: Exception) {
            emit(state.copy(error = e.message ?: "Bağlantı kurulamadı"))
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
            emit(state.copy(error = e.message ?: "Mesaj gönderilemedi"))
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
