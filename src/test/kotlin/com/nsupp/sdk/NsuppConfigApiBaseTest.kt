package com.nsupp.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `apiBase` ŞEMA KAPISI — "düz metin bir API kökü kabul ediliyor mu?"
 *
 * ── NİÇİN VAR (ölçülen kusur, 2026-09-01) ────────────────────────────────────────────────────
 * `NsuppConfig` HERHANGİ bir dizeyi kabul ediyordu; tek işlem sondaki `/` kırpmaktı. Yani
 * `http://destek.satici.com` verildiğinde ziyaretçi konuşmaları, ekleri ve UYGULAMA ANAHTARI ağda
 * düz metin gidiyordu; şemasız bir dize verildiğinde ise köprü hiç kurulmuyor, satıcı yalnız
 * logcat'te tek satır görüyordu (sessiz kilit).
 *
 * 🔴 KAPI KAYNAK OKUMUYOR, DAVRANIŞ ÖLÇÜYOR: gerçek yapıcı çağrılır ve SONUCU ölçülür.
 * 🔴 iOS İKİZİ AYNI TABLOYU KOŞAR (`NsuppConfigApiBaseTests.swift`); tabloların AYNI kaldığını
 *    `apps/android/sdkApiBaseKapisi.test.ts` ölçer.
 */
class NsuppConfigApiBaseTest {
    /** Karar tablosu — iOS ikizindeki `kabul` listesiyle BİREBİR aynı. */
    private val kabul = listOf(
        "https://api.nsupp.com",
        "https://api.nsupp.com/",
        "https://destek.satici.com:8443",
        "HTTPS://API.NSUPP.COM",
        "http://localhost:8788",
        "http://127.0.0.1:5173",
        "http://[::1]:8788",
    )

    /** Karar tablosu — iOS ikizindeki `red` listesiyle BİREBİR aynı. */
    private val red = listOf(
        "http://destek.satici.com",
        "http://192.168.1.10:8788",
        "http://localhost.satici.com",
        "api.nsupp.com",
        "",
        "   ",
        "ftp://api.nsupp.com",
        "javascript:alert(1)",
        "https://",
    )

    @Test fun `kabul edilenler yapiciyi gecer`() {
        for (deger in kabul) {
            assertTrue(NsuppConfig.isValidApiBase(deger), "kabul edilmeliydi: $deger")
            assertEquals(deger.trimEnd('/'), NsuppConfig(deger, "pk_1").base, deger)
        }
    }

    @Test fun `reddedilenler firlatir`() {
        for (deger in red) {
            assertFalse(NsuppConfig.isValidApiBase(deger), "reddedilmeliydi: $deger")
            val hata = assertFailsWith<IllegalArgumentException>(deger) { NsuppConfig(deger, "pk_1") }
            assertTrue(hata.message!!.contains("apiBase"), hata.message!!)
        }
    }

    /** ÖLÜ-KAPI KORUMASI: tablolar boşalırsa yukarıdaki iddialar SIFIR şey ölçerdi. */
    @Test fun `tablolar bos degil`() {
        assertTrue(kabul.size >= 7)
        assertTrue(red.size >= 9)
    }

    /** Hata metni SEBEBİ söyler — satıcı ne yapacağını bilmeli. */
    @Test fun `hata metni sebebi soyler`() {
        val hata = assertFailsWith<IllegalArgumentException> { NsuppConfig("http://x.example", "pk_1") }
        val m = hata.message!!
        assertTrue(m.contains("https://"), m)
        assertTrue(m.contains("loopback"), m)
        assertTrue(m.contains("http://x.example"), m)
    }
}
