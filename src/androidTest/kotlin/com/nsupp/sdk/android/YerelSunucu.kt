package com.nsupp.sdk.android

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Enstrümanlı testler için minicik yerel HTTP sunucusu (127.0.0.1, işletim sisteminin verdiği port).
 *
 * ── NİÇİN GERÇEK SUNUCU ──────────────────────────────────────────────────────────────────────
 * Kabuğun güvenlik sınırı ORIGIN'dir. "Köprü yalnız bizim origin'imize açılıyor" iddiası ancak
 * WebView gerçekten İKİ AYRI origin'den belge yüklerse kanıtlanır — tek origin'de her şey zaten
 * eşleşir ve test hiçbir şey söylemez. İki ayrı port = iki ayrı origin.
 *
 * ── NE DEĞİLDİR ──────────────────────────────────────────────────────────────────────────────
 * Bu sınıf hiçbir Android API'sini TAKLİT ETMEZ. `java.net` soketleri üzerinden gerçek HTTP
 * konuşur; WebView'ın gördüğü şey gerçek bir web sunucusudur. (Bu paketin testleri bir kez elle
 * yazılmış Android saplamalarına karşı yazılmıştı; saplamalar gerçek çerçeveden AYRIŞTIĞI için
 * testler yeşil rapor verirken hiçbir şey kanıtlamıyordu. Bir daha yok.)
 */
class YerelSunucu {
    private val soket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** `scheme://host:port` — kabuğun `apiBase`i olarak verilir. */
    val kok: String = "http://127.0.0.1:${soket.localPort}"

    private val sayfalar = ConcurrentHashMap<String, () -> String>()
    private val sayac = AtomicInteger(0)
    private val damgalar = ConcurrentHashMap<String, Int>()

    /** Gelen isteklerin "YÖNTEM /yol" kaydı — POST'un gerçekten geldiğini görmek için. */
    val istekler: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    fun koy(yol: String, html: String) {
        sayfalar[yol] = { html }
    }

    /** Her istekte YENİDEN üretilen sayfa (ör. "ilk çağrıda form, sonra düz sayfa"). */
    fun koy(yol: String, uret: () -> String) {
        sayfalar[yol] = uret
    }

    /**
     * Bir yola EN SON servis edilen belgenin damgası.
     *
     * Testler "sayfa yüklendi mi" sorusunu zamana değil BUNA bağlar: servis edilen her belgenin
     * sonuna `window.__damga` yazılır, test de belgedeki damga sunucunun servis ettiği son damgaya
     * eşitlenene kadar bekler. `document.readyState` bunu ayırt edemezdi — yeniden yüklemede eski
     * belge de "complete" der.
     */
    fun sonDamga(yol: String): Int = damgalar[yol] ?: 0

    fun kapat() {
        try {
            soket.close()
        } catch (_: Exception) {
        }
    }

    init {
        thread(isDaemon = true, name = "yerel-sunucu") {
            while (!soket.isClosed) {
                val istemci = try {
                    soket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { servisEt(istemci) }
            }
        }
    }

    private fun servisEt(istemci: Socket) {
        try {
            istemci.use { s ->
                val giris = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val istekSatiri = giris.readLine() ?: return
                var govdeUzunlugu = 0
                while (true) {
                    val satir = giris.readLine() ?: break
                    if (satir.isEmpty()) break
                    if (satir.startsWith("Content-Length:", ignoreCase = true)) {
                        govdeUzunlugu = satir.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                // Gövde OKUNUR ve atılır: okumazsak POST'u gönderen taraf yanıtı almadan bekleyebilir.
                if (govdeUzunlugu > 0) giris.read(CharArray(govdeUzunlugu), 0, govdeUzunlugu)

                val parcalar = istekSatiri.split(' ')
                val yontem = parcalar.getOrElse(0) { "GET" }
                val yol = parcalar.getOrElse(1) { "/" }.substringBefore('?')
                istekler.add("$yontem $yol")

                val uret = sayfalar[yol]
                val cikis = s.getOutputStream()
                if (uret == null) {
                    cikis.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    val damga = sayac.incrementAndGet()
                    val govde = (uret() + "<script>window.__damga=$damga;</script>").toByteArray(Charsets.UTF_8)
                    cikis.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                // Önbellek YOK: yeniden yükleme gerçekten sunucuya gitsin, yoksa damga ilerlemez.
                                "Cache-Control: no-store\r\n" +
                                "Content-Length: ${govde.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray()
                    )
                    cikis.write(govde)
                    damgalar[yol] = damga
                }
                cikis.flush()
            }
        } catch (_: Exception) {
            // Bağlantı yarıda koptu (ör. `stopLoading`) — test sunucusunun yapacak bir şeyi yok.
        }
    }
}
