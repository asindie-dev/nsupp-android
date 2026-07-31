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

data class NsuppMessage(
    val id: String,
    val senderType: String,
    val senderName: String?,
    val body: String,
    val createdAt: String,
    /** LiveTranslate çevirisi; yoksa null → [body] gösterilir. */
    val translatedBody: String? = null,
) {
    /** Gösterilecek metin: çeviri varsa o. ORİJİNAL KAYBOLMAZ — [body] erişilebilir kalır. */
    val displayBody: String get() = translatedBody ?: body
    val isFromVisitor: Boolean get() = senderType == "visitor"
}

data class NsuppConversation(
    val id: String,
    val status: String,
    val preview: String? = null,
    val lastAt: String? = null,
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
        )
    }

    data class SessionResult(
        val visitorToken: String?,
        val restricted: Boolean,
        val conversationId: String?,
        val messages: List<NsuppMessage>,
    )

    /** Mesaj gönder. [conversationId] verilirse O konuya yazılır (çoklu konuşma). */
    fun sendMessage(token: String, text: String, conversationId: String?): SendResult {
        val sb = StringBuilder("""{"token":${Json.quote(token)},"body":${Json.quote(text)}""")
        if (conversationId != null) sb.append(""","conversationId":${Json.quote(conversationId)}""")
        sb.append("}")
        val res = call("/messages", "POST", sb.toString())
        val data = Json.obj(res, "data") ?: "{}"
        return SendResult(
            conversationId = Json.string(data, "conversationId"),
            message = Json.obj(data, "message")?.let { Json.message("{$it}") },
        )
    }

    data class SendResult(val conversationId: String?, val message: NsuppMessage?)

    /** Yeni mesajları çek. [after] verilirse yalnız ondan sonrakiler. */
    fun poll(token: String, conversationId: String?, after: String?): PollResult {
        val q = StringBuilder("/messages?")
        if (conversationId != null) q.append("conversationId=").append(Url.encode(conversationId)).append('&')
        if (after != null) q.append("after=").append(Url.encode(after)).append('&')
        val text = call(q.toString(), "GET", null, visitorToken = token)
        val data = Json.obj(text, "data") ?: "{}"
        return PollResult(
            conversationId = Json.string(data, "conversationId"),
            operatorTyping = Json.bool(data, "operatorTyping") ?: false,
            operatorsOnline = Json.bool(data, "operatorsOnline"),
            messages = Json.messages(data, "messages"),
        )
    }

    data class PollResult(
        val conversationId: String?,
        val operatorTyping: Boolean,
        val operatorsOnline: Boolean?,
        val messages: List<NsuppMessage>,
    )

    fun listConversations(token: String): List<NsuppConversation> {
        // Jeton BAŞLIKTA — sorgu dizesinde DEĞİL (gerekçe `call` içinde yazılı).
        val text = call("/conversations", "GET", null, visitorToken = token)
        val data = Json.obj(text, "data") ?: "{}"
        return Json.conversations(data, "conversations")
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
