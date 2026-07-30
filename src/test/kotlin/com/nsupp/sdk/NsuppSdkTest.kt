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

    @Test fun `bos mesaj gonderilmez`() {
        val http = SahteHttp(mutableListOf())
        NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore("vt")).send("   ")
        assertEquals(0, http.istekler.size)
    }

    @Test fun `yeni konusma imleci sifirlar ve eski mesaj geri gelmez`() {
        val m1 = """{"id":"m1","senderType":"operator","body":"a","createdAt":"2026-01-01"}"""
        val http = SahteHttp(mutableListOf(
            200 to """{"data":{"visitorToken":"vt","messages":[$m1]}}""",
            200 to """{"data":{"messages":[$m1]}}""",
        ))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.startNewConversation()
        assertEquals(0, s.state.messages.size)
        assertNull(s.state.conversationId)
        s.pollOnce() // imleç sıfırlandığı için aynı mesaj YENİDEN görünebilir olmalı
        assertEquals(1, s.state.messages.size)
    }

    @Test fun `ziyaretci yokken cihaz kaydi yapilmaz`() {
        val http = SahteHttp(mutableListOf())
        NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore()).registerPushToken("fcm_x")
        assertEquals(0, http.istekler.size)
    }

    @Test fun `yoklama hatasi durumu bozmaz`() {
        val http = SahteHttp(mutableListOf(200 to """{"data":{"visitorToken":"vt","messages":[]}}""", 500 to "{}"))
        val s = NsuppSession(NsuppApi(cfg, http), InMemoryTokenStore())
        s.start()
        s.pollOnce()
        assertNull(s.state.error, "geçici yoklama hatası kullanıcıya gürültü olarak yansıdı")
    }
}
