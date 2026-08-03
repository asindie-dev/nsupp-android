package com.nsupp.sdk

/**
 * nsupp aktarım katmanı — sunucunun `/widget/:publicKey/…` uçlarını konuşur.
 *
 * SAF KOTLIN, ANDROID API'Sİ YOK. Sebep iki katlı:
 *  ① Bu katman JVM'de derlenip test edilebilir → mantığın çoğu Android SDK'sı olmadan kanıtlanır.
 *  ② Android'e bağımlı kısım (Context/SharedPreferences/Compose/FCM) ince bir kabuk olarak ayrı
 *    durur; iş mantığı arayüz çerçevesine yapışmaz.
 *
 * PROTOKOL YENİDEN YAZILMADI: web widget'ı ve iOS SDK ile AYNI uçlar. Ayrı bir mobil API açsaydık
 * üç uygulama er geç ayrışırdı.
 *
 * HTTP enjekte edilebilir (`http`) → testte sahte yanıt verilir, ağa çıkılmaz.
 */
data class NsuppConfig(
    val apiBase: String,
    val publicKey: String,
    /**
     * Sunucuya bildirilen istemci türü (`x-nsupp-sdk-platform`). React Native köprüsü bunu
     * "react-native" yapar; başka türlü bu paketi saran katman "android" gibi görünürdü.
     */
    val sdkPlatform: String = "android",
) {
    /** Sondaki '/' kırpılır: "https://api.test//widget/..." bazı ters-proxy'lerde 404 verir. */
    val base: String get() = apiBase.trimEnd('/')
}

/** Mesaj eki (görsel, dosya, ses…). Sunucu DTO'suyla aynı alanlar. */
data class NsuppAttachment(
    /** `image` / `video` / `audio` / `file` / `sticker` / `share` / `location` / `unknown`. */
    val type: String,
    val url: String? = null,
    val previewUrl: String? = null,
    val name: String? = null,
)

/** Bot'un sunduğu tek-seçimlik şık (Crisp message-type: picker). */
data class NsuppPickerChoice(
    val label: String,
    val value: String? = null,
    val selected: Boolean = false,
) {
    /** Seçildiğinde gönderilecek metin — widget ile AYNI kural (önce etiket, yoksa değer). */
    val replyText: String get() = if (label.isEmpty()) (value ?: "") else label
}

data class NsuppMessage(
    val id: String,
    val senderType: String,
    val senderName: String?,
    val body: String,
    val createdAt: String,
    /** LiveTranslate çevirisi; yoksa null → [body] gösterilir. */
    val translatedBody: String? = null,
    /** Ekler. Eskiden hiç okunmuyordu → ek-yalnız mesaj BOŞ balon olarak çiziliyordu. */
    val attachments: List<NsuppAttachment> = emptyList(),
    /**
     * Zengin içerik türü (`picker`/`field`/`carousel`/`call`) — yoksa null. Şimdilik YALNIZ
     * `picker` modellenir; diğerlerinde arayüz gövdeye düşer: tanımadığı bir şeyi çizmiş gibi
     * yapmaktansa metni göstermek dürüsttür.
     */
    val contentType: String? = null,
    val pickerChoices: List<NsuppPickerChoice> = emptyList(),
) {
    /** Gösterilecek metin: çeviri varsa o. ORİJİNAL KAYBOLMAZ — [body] erişilebilir kalır. */
    val displayBody: String get() = translatedBody ?: body
    val isFromVisitor: Boolean get() = senderType == "visitor"

    /** Balon gerçekten boş mu — hiçbir şey çizilemiyorsa arayüz nötr bir yer tutucu gösterir. */
    val isEmptyBubble: Boolean
        get() = displayBody.isBlank() && attachments.isEmpty() && pickerChoices.isEmpty()
}

data class NsuppConversation(
    val id: String,
    val status: String,
    val preview: String? = null,
    val lastAt: String? = null,
)

/** Yardım merkezi makalesi (public KB DTO'suyla aynı alanlar). */
data class NsuppArticle(
    val id: String,
    val title: String,
    val slug: String,
    /**
     * Makale gövdesi (sunucunun ürettiği biçim). Uygulama nasıl çizeceğine kendisi karar verir —
     * SDK bir render motoru dayatmaz.
     */
    val body: String,
    val locale: String? = null,
    val categoryId: String? = null,
    val updatedAt: String? = null,
)

/**
 * Sunucu 4xx/5xx döndü — [message] kullanıcıya gösterilecek sebeptir, kaybedilmez.
 *
 * [status] TAŞINIR: çağıranın "geçici mi kalıcı mı" ayrımını yapabilmesi için şart. Kod olmadan
 * yoklama döngüsü 401'i de ağ kesintisi sanıp sonsuza kadar denemeye devam eder.
 */
class NsuppServerException(val status: Int, message: String) : Exception(message) {
    /** Yeniden denemenin ANLAMSIZ olduğu hatalar: oturum geçersiz/engellenmiş/kayıp. */
    val isPermanent: Boolean get() = status == 401 || status == 403 || status == 404
}

/** Ham HTTP sözleşmesi. Gerçek uygulaması Android kabuğunda (OkHttp/HttpURLConnection). */
interface NsuppHttp {
    /**
     * @param headers ek başlıklar (oturum jetonu BURADA gider, sorgu dizesinde DEĞİL).
     * @return (durum kodu, gövde metni)
     */
    fun request(url: String, method: String, body: String?, headers: Map<String, String>): Pair<Int, String>
}

/**
 * Aktarım. Durum TUTMAZ — ziyaretçi jetonunu [NsuppSession] yönetir.
 *
 * JSON elle ayrıştırılır: SDK'ya bağımlılık eklemek satıcının uygulamasına da o bağımlılığı
 * dayatır (sürüm çakışması = en sık şikâyet edilen SDK sorunu). Yanıt şekli küçük ve sabit.
 */
class NsuppApi(private val config: NsuppConfig, private val http: NsuppHttp) {

    private fun url(path: String) = "${config.base}/widget/${config.publicKey}$path"

    private fun call(path: String, method: String, body: String?, visitorToken: String? = null): String {
        val headers = mutableMapOf(
            "content-type" to "application/json",
            // İstemciyi AÇIKÇA tanıt: alan adı kilidi TARAYICI kontrolüdür ve yerel istemci
            // `Origin` göndermez. Sunucu bu başlığı görünce kilidi uygulamaz.
            "x-nsupp-sdk-platform" to config.sdkPlatform,
        )
        // OTURUM SIRRI BAŞLIKTA: query string erişim/proxy/CDN loglarına düşer ve bu jeton
        // oturumun TEK kimliğidir (ele geçiren konuşmayı okur, ziyaretçi adına yazar).
        if (visitorToken != null) headers["x-nsupp-visitor-token"] = visitorToken
        val (code, text) = http.request(url(path), method, body, headers)
        if (code !in 200..299) throw NsuppServerException(code, Json.string(text, "error") ?: "HTTP $code")
        return text
    }

    /** Oturum aç/sürdür. [token] null ise sunucu YENİ ziyaretçi üretir. */
    fun openSession(token: String?): SessionResult {
        val body = if (token == null) "{}" else """{"token":${Json.quote(token)}}"""
        val text = call("/session", "POST", body)
        val data = Json.obj(text, "data") ?: "{}"
        return SessionResult(
            visitorToken = Json.string(data, "visitorToken"),
            restricted = Json.bool(data, "restricted") ?: false,
            // OKUNMASI ŞART: okumazsak ilk yoklamaya kadar (4 sn) oturum "hiçbir konuşmada değilim"
            // der ama mesajları yüklemiştir — durum kendi kendine yalan söyler.
            conversationId = Json.obj(data, "conversation")?.let { Json.string("{$it}", "id") },
            messages = Json.messages(data, "messages"),
            pendingRating = Json.bool(data, "pendingRating") ?: false,
        )
    }

    data class SessionResult(
        val visitorToken: String?,
        val restricted: Boolean,
        val conversationId: String?,
        val messages: List<NsuppMessage>,
        /**
         * Konuşma çözüldü ve HENÜZ puanlanmadı → arayüz CSAT sorar. Okumazsak mobil kanal
         * memnuniyet ölçümünün TAMAMEN dışında kalır.
         */
        val pendingRating: Boolean = false,
    )

    /**
     * Mesaj gönder. [conversationId] verilirse O konuya yazılır (çoklu konuşma).
     *
     * [newConversation] = ziyaretçi AÇIKÇA yeni bir konu açtı. Bu bayrak olmadan sunucu açık
     * konuşmayı yeniden kullanır ve "yeni konu" isteği sessizce eski akışa gömülürdü.
     */
    fun sendMessage(token: String, text: String, conversationId: String?, newConversation: Boolean = false): SendResult {
        val sb = StringBuilder("""{"token":${Json.quote(token)},"body":${Json.quote(text)}""")
        if (conversationId != null) sb.append(""","conversationId":${Json.quote(conversationId)}""")
        if (newConversation) sb.append(""","newConversation":true""")
        sb.append("}")
        val res = call("/messages", "POST", sb.toString())
        val data = Json.obj(res, "data") ?: "{}"
        return SendResult(
            conversationId = Json.string(data, "conversationId"),
            message = Json.obj(data, "message")?.let { Json.message("{$it}") },
        )
    }

    data class SendResult(val conversationId: String?, val message: NsuppMessage?)

    /**
     * Yeni mesajları çek. [after] verilirse yalnız ondan sonrakiler.
     *
     * `open=1&mobile=1` HER ZAMAN gider ve bu DOĞRUDUR: yoklama yalnız sohbet ekranı açıkken
     * çalışır (kapanınca durur), istemci de her zaman mobil. Bunları göndermezsek sunucudaki üç
     * davranış mobilde HİÇ işlemez: okundu makbuzu (✓✓), kapanmış-sohbet okuma penceresi ve
     * mobil-tetikleyici ayrımı.
     */
    fun poll(token: String, conversationId: String?, after: String?): PollResult {
        val q = StringBuilder("/messages?open=1&mobile=1")
        if (conversationId != null) q.append("&conversationId=").append(Url.encode(conversationId))
        if (after != null) q.append("&after=").append(Url.encode(after))
        val text = call(q.toString(), "GET", null, visitorToken = token)
        val data = Json.obj(text, "data") ?: "{}"
        return PollResult(
            conversationId = Json.string(data, "conversationId"),
            operatorTyping = Json.bool(data, "operatorTyping") ?: false,
            operatorsOnline = Json.bool(data, "operatorsOnline"),
            messages = Json.messages(data, "messages"),
            pendingRating = Json.bool(data, "pendingRating") ?: false,
        )
    }

    data class PollResult(
        val conversationId: String?,
        val operatorTyping: Boolean,
        val operatorsOnline: Boolean?,
        val messages: List<NsuppMessage>,
        val pendingRating: Boolean = false,
    )

    fun listConversations(token: String): List<NsuppConversation> {
        // Jeton BAŞLIKTA — sorgu dizesinde DEĞİL (gerekçe `call` içinde yazılı).
        val text = call("/conversations", "GET", null, visitorToken = token)
        val data = Json.obj(text, "data") ?: "{}"
        return Json.conversations(data, "conversations")
    }

    // ── Kimlik / oturum verisi / olay ──

    /**
     * Ziyaretçiyi tanıt.
     *
     * [signature]: sunucunuzda `HMAC-SHA256(email, identity_secret)` ile üretilir — **uygulamanın
     * içinde ASLA üretmeyin**, sır istemciye gömülürse doğrulama anlamını yitirir.
     *
     * [attributes]: özel öznitelikler (plan, kullanıcı-id, `segments`…). `$` ve `_` önekli
     * anahtarlar sunucuda düşürülür (ayrılmış ad alanları).
     */
    fun identify(
        token: String,
        email: String,
        name: String?,
        signature: String?,
        attributes: Map<String, Any?>?,
    ): IdentifyResult {
        val sb = StringBuilder("""{"token":${Json.quote(token)},"email":${Json.quote(email)}""")
        if (name != null) sb.append(""","name":${Json.quote(name)}""")
        if (signature != null) sb.append(""","signature":${Json.quote(signature)}""")
        if (!attributes.isNullOrEmpty()) sb.append(""","data":${Json.encodeMap(attributes)}""")
        sb.append("}")
        val data = Json.obj(call("/identify", "POST", sb.toString()), "data") ?: ""
        val govde = "{$data}"
        return IdentifyResult(
            identity = Json.string(govde, "identity"),
            identitySource = Json.string(govde, "identitySource"),
        )
    }

    /**
     * Sunucunun kimlik teşhisi. [identity] = HMAC yolunun sonucu
     * (`valid`/`invalid`/`unsigned`/`no_secret`); [identitySource] = kimliği hangi yolun kurduğu.
     * ENTEGRASYON TEŞHİSİ: "neden doğrulanmadı" sorusunun tek cevabı burada.
     */
    data class IdentifyResult(val identity: String?, val identitySource: String?)

    /** Özel olay bildir (kampanya/tetikleyici koşulları + kişi zaman-çizelgesi). */
    fun trackEvent(token: String, name: String) {
        call("/event", "POST", """{"token":${Json.quote(token)},"kind":"event","name":${Json.quote(name)}}""")
    }

    // ── Yardım merkezi (KB) ──
    //
    // ⚠️ FARKLI YOL ÖNEKİ: KB uçları `/widget/:key/…` altında DEĞİL, `/cof/kb/public/:key/…`
    // altında yaşar (web yardım merkeziyle aynı yüzey). Ayrı bir mobil KB API'si açmadık —
    // açsaydık makale görünürlüğü/kilidi/dil çözümü iki yerde ayrışırdı.

    private fun kbCall(path: String): String {
        val url = "${config.base}/cof/kb/public/${config.publicKey}$path"
        val (code, text) = http.request(url, "GET", null, mapOf(
            "content-type" to "application/json",
            "x-nsupp-sdk-platform" to config.sdkPlatform,
        ))
        if (code !in 200..299) {
            // Kilitli KB'de sunucu `code: "kb_locked"` der; olduğu gibi taşınır ki uygulama
            // "makale yok" ile "makaleler kilitli"yi ayırt edebilsin.
            val sebep = Json.string(text, "code") ?: Json.string(text, "error") ?: "HTTP $code"
            throw NsuppServerException(code, sebep)
        }
        return text
    }

    /** Yayınlı makaleleri listele. [locale] verilmezse sunucu karar verir. */
    fun articles(locale: String? = null): List<NsuppArticle> {
        val q = if (locale == null) "/articles" else "/articles?locale=${Url.encode(locale)}"
        val data = Json.obj(kbCall(q), "data") ?: return emptyList()
        return Json.articles("{$data}", "articles")
    }

    /** Makale ara. Sunucu bu uçta ZARF İÇİNDE DÜZ DİZİ döner (`{data: [...]}`). */
    fun searchArticles(query: String, locale: String? = null): List<NsuppArticle> {
        var q = "/search?q=${Url.encode(query)}"
        if (locale != null) q += "&locale=${Url.encode(locale)}"
        return Json.articles(kbCall(q), "data")
    }

    /** Tek makaleyi slug ile getir (görüntülenme sayacı sunucuda artar). */
    fun article(slug: String, locale: String? = null): NsuppArticle? {
        var q = "/articles/${Url.encode(slug)}"
        if (locale != null) q += "?locale=${Url.encode(locale)}"
        val data = Json.obj(kbCall(q), "data") ?: return null
        return Json.article("{$data}")
    }

    /** Konuşmayı puanla (CSAT). [score] 1–5; sunucu aralık dışını reddeder. */
    fun rate(token: String, conversationId: String, score: Int, comment: String?) {
        val sb = StringBuilder("""{"token":${Json.quote(token)},"conversationId":${Json.quote(conversationId)},"score":$score""")
        if (!comment.isNullOrBlank()) sb.append(""","comment":${Json.quote(comment)}""")
        sb.append("}")
        call("/rating", "POST", sb.toString())
    }

    /** Bir mesaj tetikleyicisini çalıştır (Crisp'in `runBotScenario` karşılığı). */
    fun runTrigger(token: String, identifier: String) {
        call("/trigger-run", "POST", """{"token":${Json.quote(token)},"identifier":${Json.quote(identifier)}}""")
    }

    /**
     * FCM cihaz jetonunu kaydet. Uygulama HER AÇILIŞTA çağırmalı — jeton yenilenebilir ve
     * sunucu upsert olduğu için satır çoğalmaz.
     */
    fun registerDevice(token: String, deviceToken: String, bundleId: String?) {
        val sb = StringBuilder("""{"token":${Json.quote(token)},"platform":"android","deviceToken":${Json.quote(deviceToken)}""")
        if (bundleId != null) sb.append(""","bundleId":${Json.quote(bundleId)}""")
        sb.append("}")
        call("/devices", "POST", sb.toString())
    }
}
