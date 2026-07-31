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
            emit(apply(state.copy(error = null, conversationId = r.conversationId), r.messages))
        } catch (e: Exception) {
            emit(state.copy(error = e.message ?: "Bağlantı kurulamadı"))
        }
    }

    /** Mesaj gönder. Boş/boşluk metin GÖNDERİLMEZ (sunucu da reddeder; kullanıcıyı bekletme). */
    fun send(text: String) {
        val clean = text.trim()
        val token = store.read()
        if (clean.isEmpty() || token == null) return
        try {
            val r = api.sendMessage(token, clean, state.conversationId)
            var next = state.copy(error = null, conversationId = r.conversationId ?: state.conversationId)
            if (r.message != null) next = apply(next, listOf(r.message))
            emit(next)
        } catch (e: Exception) {
            emit(state.copy(error = e.message ?: "Mesaj gönderilemedi"))
        }
    }

    /**
     * Bir yoklama turu. Zamanlayıcı ÇAĞIRANDA — bu sınıf iş parçacığı yönetmez.
     * Hata SESSİZ: geçici ağ kesintisinde ekrana hata basmak gürültüdür, gönderim hatası zaten görünür.
     */
    fun pollOnce() {
        val token = store.read() ?: return
        try {
            val r = api.poll(token, state.conversationId, lastTs)
            var next = state.copy(
                conversationId = r.conversationId ?: state.conversationId,
                operatorTyping = r.operatorTyping,
                operatorsOnline = r.operatorsOnline ?: state.operatorsOnline,
            )
            next = apply(next, r.messages)
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

    /** Yeni bir konu başlat (önceki konular KAPANMAZ — çoklu konuşma). */
    fun startNewConversation() {
        seen.clear(); lastTs = null
        emit(state.copy(messages = emptyList(), conversationId = null, error = null))
    }

    /** Var olan bir konuya geç. */
    fun openConversation(id: String) {
        if (id == state.conversationId) return
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

    private fun apply(base: NsuppState, incoming: List<NsuppMessage>): NsuppState {
        if (incoming.isEmpty()) return base
        val list = base.messages.toMutableList()
        for (m in incoming) {
            if (!seen.add(m.id)) continue
            list.add(m)
            if (lastTs == null || m.createdAt > lastTs!!) lastTs = m.createdAt
        }
        return base.copy(messages = list)
    }

    private fun emit(next: NsuppState) {
        state = next
        onState?.invoke(next)
    }
}
