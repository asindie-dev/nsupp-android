package com.nsupp.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Sahte HTTP — ağa çıkmadan sözleşmeyi doğrular; hangi başlıkların gittiğini de kaydeder. */
private class SahteHttp(private val yanitlar: MutableList<Pair<Int, String>>) : NsuppHttp {
    val istekler = mutableListOf<Triple<String, String, Map<String, String>>>()
    var songovde: String? = null
    override fun request(url: String, method: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        istekler.add(Triple(url, method, headers))
        songovde = body
        return if (yanitlar.isEmpty()) 500 to "{}" else yanitlar.removeAt(0)
    }
}

class JsonTest {
    @Test fun `kacisli metin cozulur`() {
        val s = """{"body":"satir\nsonu \"tirnak\" ve \\ ters"}"""
        assertEquals("satir\nsonu \"tirnak\" ve \\ ters", Json.string(s, "body"))
    }

    @Test fun `deger icindeki eslesme anahtar sanilmaz`() {
        // "body" metninin İÇİNDE "id": geçiyor — okuyucu bunu anahtar sanmamalı.
        val s = """{"body":"\"id\":\"sahte\"","id":"gercek"}"""
        assertEquals("gercek", Json.string(s, "id"))
    }

    @Test fun `ic ice ayni adli anahtar UST SEVIYEYI golgelemez`() {
        // /session yanıtında `config.widgetConfig.texts` 98 anahtar taşıyor ve `messages`ten ÖNCE
        // geliyor. Çakışan tek bir ad, mesaj listesini sessizce boşaltırdı.
        val s = """{"config":{"texts":{"messages":"Mesajlar"}},"messages":[{"id":"m1"}]}"""
        assertEquals(1, Json.messages(s, "messages").size)
        assertEquals("Mesajlar", Json.string(Json.obj(Json.obj(s, "config")?.let { "{$it}" } ?: "{}", "texts")?.let { "{$it}" } ?: "{}", "messages"))
    }

    @Test fun `ic ice conversation id ust seviyeyi golgelemez`() {
        val s = """{"config":{"conversation":{"id":"YANLIS"}},"conversation":{"id":"DOGRU"}}"""
        assertEquals("DOGRU", Json.obj(s, "conversation")?.let { Json.string("{$it}", "id") })
    }

    @Test fun `null deger null doner`() = assertNull(Json.string("""{"senderName":null}""", "senderName"))

    @Test fun `ic ice nesne butun halinde alinir`() {
        val s = """{"data":{"a":{"b":1},"c":"}"},"x":2}"""
        assertEquals("""{"a":{"b":1},"c":"}"}""", "{" + Json.obj(s, "data") + "}")
    }

    @Test fun `dizi ogeleri ayrilir`() {
        val a = Json.arr("""{"m":[{"id":"1"},{"id":"2","t":"}"}]}""", "m")
        assertEquals(listOf("""{"id":"1"}""", """{"id":"2","t":"}"}"""), Json.items(a))
    }

    @Test fun `kacis uretimi geri okunabilir`() {
        val ham = "a\"b\\c\nd\te"
        assertEquals(ham, Json.string("""{"k":${Json.quote(ham)}}""", "k"))
    }
}

class NsuppApiTest {
    private val cfg = NsuppConfig("https://api.test/", "pk_1")

    @Test fun `sondaki bolu kirpilir`() = assertEquals("https://api.test", cfg.base)

    @Test fun `oturum jetonu ve mesajlar okunur`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt_1","messages":[{"id":"m1","senderType":"operator","body":"Merhaba","createdAt":"2026-01-01T00:00:00Z"}]}}"""))
        val r = NsuppApi(cfg, http).openSession(null)
        assertEquals("vt_1", r.visitorToken)
        assertEquals(1, r.messages.size)
        assertEquals("Merhaba", r.messages[0].body)
        assertEquals("https://api.test/widget/pk_1/session", http.istekler[0].first)
    }

    @Test fun `yoklamada jeton BASLIKTA gider sorgu dizesinde DEGIL`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"messages":[]}}"""))
        NsuppApi(cfg, http).poll("vt_gizli", "c1", null)
        val (url, _, headers) = http.istekler[0]
        assertEquals("vt_gizli", headers["x-nsupp-visitor-token"])
        assertTrue(!url.contains("vt_gizli"), "oturum jetonu URL'ye SIZDI: $url")
    }

    @Test fun `sunucu hatasi sebebiyle firlar`() {
        val http = SahteHttp(mutableListOf(403 to """{"error":"Bu sayfada destek kapalı"}"""))
        val e = assertFailsWith<NsuppServerException> { NsuppApi(cfg, http).openSession(null) }
        assertEquals("Bu sayfada destek kapalı", e.message)
    }

    @Test fun `tek mesaj nesnesi cozulur ve ceviri gosterilir`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"conversationId":"c9","message":{"id":"m2","senderType":"visitor","body":"hi","translatedBody":"selam","createdAt":"t"}}}"""))
        val r = NsuppApi(cfg, http).sendMessage("vt", "hi", null)
        assertEquals("c9", r.conversationId)
        assertEquals("selam", r.message!!.displayBody)
        assertEquals("hi", r.message.body) // ORİJİNAL KAYBOLMAZ
    }
}

class NsuppSessionTest {
    private val cfg = NsuppConfig("https://api.test", "pk_1")

    @Test fun `ilk acilista jeton saklanir`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt_1","messages":[]}}"""))
        val store = InMemoryTokenStore()
        NsuppSession(NsuppApi(cfg, http), store).start()
        assertEquals("vt_1", store.read())
    }

    @Test fun `acik konusma kimligi oturumda okunur`() {
        // Okunmazsa ilk yoklamaya kadar durum "hiçbir konuşmada değilim" der ama mesajlar yüklüdür.
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt","conversation":{"id":"c7","status":"unresolved"},"messages":[]}}"""))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        assertEquals("c7", s.state.conversationId)
    }

    @Test fun `kisitli oturum sessiz kalmaz`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"restricted":true}}"""))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        assertTrue(s.state.error != null, "kısıtlı oturumda sebep gösterilmedi")
    }

    @Test fun `ayni mesaj iki kez eklenmez`() {
        val msg = """{"id":"m1","senderType":"operator","body":"a","createdAt":"2026-01-01"}"""
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","messages":[$msg]}}""",
            200 to """{"data":{"messages":[$msg]}}""",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.pollOnce()
        assertEquals(1, s.state.messages.size)
    }

    @Test fun `kendi mesajim imleci ILERLETMEZ (operator mesaji kaybolmaz)`() {
        // Senaryo: operatör T1'de yazar; ziyaretçi yoklamadan ÖNCE T2 > T1'de yazar. İmleç kendi
        // mesajımızla ilerlerse sonraki yoklama after=T2 der ve operatörün T1 mesajı SONSUZA KADAR
        // atlanır. Bu test o kaybı üretir.
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","messages":[]}}""",
            // ziyaretçinin T2'deki gönderimi
            200 to """{"data":{"conversationId":"c1","message":{"id":"mine","senderType":"visitor","body":"ben","createdAt":"2026-01-01T00:00:02Z"}}}""",
            200 to """{"data":{"messages":[]}}""",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.send("ben")
        s.pollOnce()
        // Yoklama URL'inde `after` HİÇ olmamalı (imleç hâlâ boş) — varsa T1 mesajı elenirdi.
        val pollUrl = http.istekler.last().first
        assertTrue(!pollUrl.contains("after="), "kendi mesajım imleci ilerletti → operatör mesajı kaybolur: $pollUrl")
    }

    @Test fun `yoklama open ve mobile bayraklarini gonderir`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"messages":[]}}"""))
        NsuppApi(cfg, http).poll("vt", null, null)
        val url = http.istekler[0].first
        // Bunlar olmadan okundu makbuzu (✓✓), kapanmış-sohbet penceresi ve mobil-tetikleyici
        // ayrımı mobilde HİÇ işlemez.
        assertTrue(url.contains("open=1"), "open=1 yok: $url")
        assertTrue(url.contains("mobile=1"), "mobile=1 yok: $url")
    }

    @Test fun `yeni konu bayragi GONDERIME islenir ve beklerken yoklama DURAKLAR`() {
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","conversation":{"id":"eski"},"messages":[]}}""",
            200 to """{"data":{"conversationId":"yeni","message":{"id":"m9","senderType":"visitor","body":"iade","createdAt":"t"}}}""",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        assertEquals("eski", s.state.conversationId)

        s.startNewConversation()
        val oncekiIstekSayisi = http.istekler.size
        s.pollOnce()
        assertEquals(oncekiIstekSayisi, http.istekler.size, "yeni konu beklerken yoklandı → eski mesajlar geri gelir")

        s.send("iade")
        assertTrue(http.songovde!!.contains("\"newConversation\":true"), "yeni konu bayrağı gönderilmedi → mesaj ESKİ konuşmaya düşer")
        assertEquals("yeni", s.state.conversationId)
    }

    @Test fun `oturum ACILMADAN verilen kimlik BEKLETILIR ve start sonrasi gonderilir`() {
        // Uygulamalar kimliği giriş anında verir; oturum ise sohbet ekranı ilk açıldığında doğar.
        // Kuyruk olmasaydı identify sessizce düşer, müşteri operatörde ANONİM görünürdü.
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","messages":[]}}""",
            200 to """{"data":{"ok":true,"identity":"valid","identitySource":"signature"}}""",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.identify("a@b.com", name = "Ada", signature = "imza", attributes = mapOf("segments" to listOf("vip")))
        assertEquals(0, http.istekler.size, "oturum yokken ağa çıkıldı")
        s.start()
        assertEquals("/identify", http.istekler.last().first.substringAfterLast("pk_1"))
        val govde = http.songovde!!
        assertTrue(govde.contains("\"email\":\"a@b.com\""), govde)
        assertTrue(govde.contains("\"signature\":\"imza\""), govde)
        assertTrue(govde.contains("\"segments\":[\"vip\"]"), "segment dizisi kodlanmadı: $govde")
        assertEquals("signature", s.state.identityStatus)
    }

    @Test fun `kimliksiz oznitelik yazma SESSIZCE YUTULMAZ`() {
        // Öznitelikler kişi kaydında yaşar; kişi e-posta ile doğar. Sessiz no-op, ayarın yalan
        // söylemesiyle aynı şeydir.
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt","messages":[]}}"""))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        assertTrue(!s.setSessionData(mapOf("plan" to "pro")), "kimliksiz yazma başarılı sayıldı")
        assertTrue(s.state.error!!.contains("identify"), "sebep gösterilmedi: ${s.state.error}")
    }

    @Test fun `oturum yokken olay gonderilmez (olay BIR ANA aittir)`() {
        val http = SahteHttp(mutableListOf())
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        assertTrue(!s.trackEvent("Signup"))
        assertEquals(0, http.istekler.size)
    }

    @Test fun `cikis kimligi de temizler`() {
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","messages":[]}}""",
            200 to """{"data":{"ok":true,"identity":"valid"}}""",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.identify("a@b.com")
        s.reset()
        // reset sonrası öznitelik yazımı kimlik istemeli — eski e-posta taşınmamalı.
        assertTrue(!s.setSessionData(mapOf("plan" to "pro")), "çıkıştan sonra ESKİ kimlik hâlâ kullanıldı")
    }

    @Test fun `bos mesaj gonderilmez`() {
        val http = SahteHttp(mutableListOf())
        NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore("vt")).send("   ")
        assertEquals(0, http.istekler.size)
    }

    @Test fun `yeni konusma EKRANI temizler ve eski konudan ayrilir`() {
        // NOT: bu test eskiden "sıfırlanan imleçle aynı mesaj yeniden görünür" diyordu. O davranış
        // YANLIŞTI: yeni konu isteyen ziyaretçiye eski konunun mesajlarını geri getiriyordu.
        // Bekleme sırasında yoklamanın durakladığı ve bayrağın gönderime işlendiği ayrı testte.
        val m1 = """{"id":"m1","senderType":"operator","body":"a","createdAt":"2026-01-01"}"""
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt","conversation":{"id":"c1"},"messages":[$m1]}}"""))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        assertEquals(1, s.state.messages.size)
        s.startNewConversation()
        assertEquals(0, s.state.messages.size)
        assertNull(s.state.conversationId)
    }

    @Test fun `ziyaretci yokken cihaz kaydi yapilmaz`() {
        val http = SahteHttp(mutableListOf())
        NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore()).registerPushToken("fcm_x")
        assertEquals(0, http.istekler.size)
    }

    @Test fun `cikis sonrasi jeton SILINIR ve gecmis kalmaz`() {
        // Paylaşılan cihazda bir sonraki kullanıcı öncekinin sohbetini açmamalı.
        val m = """{"id":"m1","senderType":"operator","body":"gizli","createdAt":"2026-01-01"}"""
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt","messages":[$m]}}"""))
        val store = InMemoryTokenStore()
        val s = NsuppSession(NsuppApi(cfg, http), store)
        s.start()
        assertEquals(1, s.state.messages.size)
        s.reset()
        assertNull(store.read(), "çıkışta jeton silinmedi → sonraki kullanıcı aynı oturumu açar")
        assertEquals(0, s.state.messages.size)
        assertTrue(s.stopped, "reset sonrası döngü durmuyor")
    }

    @Test fun `kalici hata dongu durdurur ve sebep gosterilir`() {
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","messages":[]}}""",
            403 to """{"error":"Engellendi"}""",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.pollOnce()
        assertTrue(s.stopped, "403 sonrası döngü durmadı → sonsuza kadar aynı hatayı alır")
        assertEquals("Engellendi", s.state.error)
    }

    @Test fun `gecici hata dongu DURDURMAZ`() {
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","messages":[]}}""",
            503 to "{}",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.pollOnce()
        assertTrue(!s.stopped, "tek bir 503 sohbeti kalıcı olarak öldürdü")
        assertNull(s.state.error)
    }

    @Test fun `kisitli oturumda da jeton SAKLANIR`() {
        // Saklamazsak her açılış YENİ ziyaretçi satırı + IP/coğrafya üretir (veri minimizasyonu).
        val http = SahteHttp(mutableListOf(200 to """{"data":{"restricted":true,"visitorToken":"vt_k"}}"""))
        val store = InMemoryTokenStore()
        NsuppSession(NsuppApi(cfg, http), store).start()
        assertEquals("vt_k", store.read())
    }

    @Test fun `konusma listesi jetonu BASLIKTA gonderir`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"conversations":[]}}"""))
        NsuppApi(cfg, http).listConversations("vt_gizli")
        val (url, _, headers) = http.istekler[0]
        assertEquals("vt_gizli", headers["x-nsupp-visitor-token"])
        assertTrue(!url.contains("vt_gizli"), "oturum jetonu URL'ye SIZDI: $url")
    }

    @Test fun `her istek SDK platformunu bildirir (alan adi kilidi tarayici kontrolu)`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{}}"""))
        NsuppApi(cfg, http).openSession(null)
        assertEquals("android", http.istekler[0].third["x-nsupp-sdk-platform"])
    }

    @Test fun `yoklama hatasi durumu bozmaz`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt","messages":[]}}""", 500 to "{}"))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.pollOnce()
        assertNull(s.state.error, "geçici yoklama hatası kullanıcıya gürültü olarak yansıdı")
    }
}
