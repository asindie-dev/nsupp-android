package com.nsupp.sdk

/**
 * Küçük JSON okuyucu — SDK'ya bağımlılık eklememek için.
 *
 * NEDEN HAZIR KÜTÜPHANE DEĞİL: bir SDK'nın taşıdığı her bağımlılık satıcının uygulamasına da
 * dayatılır ve sürüm çakışması (Moshi/Gson/kotlinx sürümleri) SDK'lardan en sık şikâyet edilen
 * konudur. Okuduğumuz yanıt şekli küçük ve SABİT (sunucu sözleşmesi), o yüzden tam bir JSON
 * ayrıştırıcı gerekmiyor.
 *
 * KAPSAM SINIRI DÜRÜSTÇE: bu okuyucu YALNIZ bizim sunucumuzun ürettiği yanıtları hedefler.
 * Genel amaçlı bir ayrıştırıcı DEĞİLDİR (iç içe dizi-içinde-dizi, sayısal kenar durumları vb.
 * kapsam dışıdır) — bu yüzden `internal` ve testlerle sınırları pinlenmiştir.
 */
internal object Json {

    /** Basit `"key":"value"` okuması (kaçışlar çözülür). Yoksa null. */
    fun string(src: String, key: String): String? {
        val i = indexOfKey(src, key) ?: return null
        var p = skipWs(src, i)
        if (p >= src.length) return null
        if (src.startsWith("null", p)) return null
        if (src[p] != '"') return null
        p++
        val sb = StringBuilder()
        while (p < src.length) {
            val c = src[p]
            if (c == '\\' && p + 1 < src.length) {
                when (val n = src[p + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'u' -> {
                        if (p + 5 < src.length) {
                            sb.append(src.substring(p + 2, p + 6).toInt(16).toChar()); p += 4
                        }
                    }
                    else -> sb.append(n)
                }
                p += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c); p++
        }
        return null
    }

    fun bool(src: String, key: String): Boolean? {
        val i = indexOfKey(src, key) ?: return null
        val p = skipWs(src, i)
        return when {
            src.startsWith("true", p) -> true
            src.startsWith("false", p) -> false
            else -> null
        }
    }

    /** `"key":{ … }` gövdesini ham metin olarak döner (parantez dengeli). */
    fun obj(src: String, key: String): String? = block(src, key, '{', '}')

    /** `"key":[ … ]` gövdesini ham metin olarak döner. */
    fun arr(src: String, key: String): String? = block(src, key, '[', ']')

    /** Tek mesaj nesnesi (ham `{…}` gövdesi). Kimliği yoksa mesaj sayılmaz → null. */
    fun message(o: String): NsuppMessage? {
        val id = string(o, "id") ?: return null
        val icerik = obj(o, "content")?.let { "{$it}" }
        return NsuppMessage(
            id = id,
            senderType = string(o, "senderType") ?: "operator",
            senderName = string(o, "senderName"),
            body = string(o, "body") ?: "",
            createdAt = string(o, "createdAt") ?: "",
            translatedBody = string(o, "translatedBody"),
            attachments = items(arr(o, "attachments")).mapNotNull { a ->
                val tur = string(a, "type") ?: return@mapNotNull null
                NsuppAttachment(tur, string(a, "url"), string(a, "previewUrl"), string(a, "name"))
            },
            contentType = icerik?.let { string(it, "type") },
            pickerChoices = if (icerik != null && string(icerik, "type") == "picker")
                items(arr(icerik, "choices")).mapNotNull { ch ->
                    val etiket = string(ch, "label") ?: string(ch, "value") ?: return@mapNotNull null
                    NsuppPickerChoice(etiket, string(ch, "value"), bool(ch, "selected") ?: false)
                }
            else emptyList(),
        )
    }

    fun messages(src: String, key: String): List<NsuppMessage> =
        items(arr(src, key)).mapNotNull { message(it) }

    /** Tek makale nesnesi (ham `{…}` gövdesi). Kimliği/slug'ı yoksa makale sayılmaz → null. */
    fun article(o: String): NsuppArticle? {
        val id = string(o, "id") ?: return null
        val slug = string(o, "slug") ?: return null
        return NsuppArticle(
            id = id,
            title = string(o, "title") ?: "",
            slug = slug,
            body = string(o, "body") ?: "",
            locale = string(o, "locale"),
            categoryId = string(o, "categoryId"),
            updatedAt = string(o, "updatedAt"),
        )
    }

    fun articles(src: String, key: String): List<NsuppArticle> =
        items(arr(src, key)).mapNotNull { article(it) }

    fun conversations(src: String, key: String): List<NsuppConversation> =
        items(arr(src, key)).mapNotNull { o ->
            val id = string(o, "id") ?: return@mapNotNull null
            NsuppConversation(
                id = id,
                status = string(o, "status") ?: "unresolved",
                preview = string(o, "preview"),
                lastAt = string(o, "lastAt"),
            )
        }

    /** Dizi gövdesini üst düzey `{…}` parçalarına böler. */
    fun items(arrayBody: String?): List<String> {
        val s = arrayBody ?: return emptyList()
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (inStr) {
                if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(s.substring(start, i + 1)); start = -1 } }
            }
        }
        return out
    }

    /** Metni JSON dizesi olarak kaçır (gövde üretimi). */
    fun quote(v: String): String {
        val sb = StringBuilder("\"")
        for (c in v) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    /**
     * Öznitelik haritasını JSON nesnesine çevir.
     *
     * KAPSAM SINIRI DÜRÜSTÇE: string / sayı / boolean / null ve bunların LİSTESİ desteklenir
     * (`segments: [..]` bu yüzden gerekli). Başka bir tür `toString()` ile metne düşürülür —
     * sessizce ATILMAZ: kaybolan öznitelik, yanlış tipte öznitelikten daha zor teşhis edilir.
     */
    fun encodeMap(map: Map<String, Any?>): String =
        map.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${encodeValue(v)}" }

    private fun encodeValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Int, is Long, is Short, is Byte -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Iterable<*> -> v.joinToString(",", "[", "]") { encodeValue(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> "${quote(k.toString())}:${encodeValue(x)}" }
        else -> quote(v.toString())
    }

    // ── iç ──


    /**
     * `"key"` sonrası ':' konumunu bulur.
     *
     * 🔴 YALNIZ ÜST SEVİYE: eskiden metinde geçen İLK eşleşme alınıyordu. `/session` yanıtında
     * `config` (içinde 98 metin anahtarı taşıyan `texts`) `messages`ten ÖNCE geliyor — orada
     * çakışan tek bir anahtar adı, üst seviyedeki gerçek alanı gölgeler ve mesaj listesi SESSİZCE
     * boş döner. Bu yüzden derinlik izlenir; iç içe nesne/dizi içindeki eşleşmeler atlanır.
     *
     * Dize İÇİNDEKİ eşleşmeler de sayılmaz (kaçış takibiyle).
     */
    private fun indexOfKey(src: String, key: String): Int? {
        val needle = "\"$key\""
        // Girdi ya tam bir belge (`{…}`) ya da açılmış gövde olabilir (obj() içeriği döner).
        // Üst seviye ilkinde derinlik 1, ikincisinde 0'dır.
        val hedefDerinlik = if (src.trimStart().startsWith("{")) 1 else 0
        var depth = 0
        var i = 0
        var inStr = false
        var esc = false
        while (i < src.length) {
            val c = src[i]
            if (esc) { esc = false; i++; continue }
            if (inStr) {
                if (c == '\\') esc = true
                else if (c == '"') inStr = false
                i++; continue
            }
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    if (depth == hedefDerinlik && src.startsWith(needle, i)) {
                        val after = skipWs(src, i + needle.length)
                        if (after < src.length && src[after] == ':') return after + 1
                    }
                    inStr = true
                }
            }
            i++
        }
        return null
    }

    private fun skipWs(src: String, from: Int): Int {
        var p = from
        while (p < src.length && src[p].isWhitespace()) p++
        return p
    }

    private fun block(src: String, key: String, open: Char, close: Char): String? {
        val i = indexOfKey(src, key) ?: return null
        var p = skipWs(src, i)
        if (p >= src.length || src[p] != open) return null
        var depth = 0
        var inStr = false
        var esc = false
        val start = p
        while (p < src.length) {
            val c = src[p]
            if (esc) { esc = false; p++; continue }
            if (inStr) {
                if (c == '\\') esc = true else if (c == '"') inStr = false
                p++; continue
            }
            when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> { depth--; if (depth == 0) return src.substring(start + 1, p) }
            }
            p++
        }
        return null
    }
}

internal object Url {
    /** Sorgu dizesi kaçışı (Android'e bağımlı olmadan; URLEncoder JVM'de vardır). */
    fun encode(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")
}
