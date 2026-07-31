package com.nsupp.sdk

import java.util.Locale

/**
 * SDK'nın kendi arayüz metinleri — cihaz diline göre.
 *
 * ── NEDEN BU KADAR KÜÇÜK ─────────────────────────────────────────────────────────────────────
 * Metinler Türkçe SABİTLENMİŞTİ: İngilizce konuşan bir kullanıcı, satıcının uygulaması tamamen
 * İngilizce olsa bile sohbet ekranında Türkçe görüyordu.
 *
 * Kapsam tr/en ile sınırlı ÇÜNKÜ ürünün geri kalanı da öyle (widget metin kataloğu tr/en). Daha
 * fazla dil eklemek, çevirisi olmayan bir dilde sessizce Türkçeye düşmek demekti — var olmayan bir
 * yeteneği varmış gibi göstermek. Satıcının kendi metinlerini dayatması ayrı bir iş: sunucudaki
 * `config.texts` kataloğu (98 anahtar) henüz mobilde okunmuyor, bu bilinçli bir sınır.
 *
 * `java.util.Locale` kullanılır — Android'e bağımlı DEĞİL, JVM'de test edilebilir.
 */
object NsuppTexts {
    /** Test için sabitlenebilir; null = cihaz dili. */
    var dilKodu: String? = null

    private val tr: Boolean get() = (dilKodu ?: Locale.getDefault().language) == "tr"

    fun t(turkce: String, english: String): String = if (tr) turkce else english

    val composerPlaceholder: String get() = t("Mesajınızı yazın…", "Type your message…")
    val unsupportedMessage: String get() = t("Bu mesaj bu sürümde gösterilemiyor.", "This message can’t be shown in this version.")
    val restricted: String get() = t("Destek bu uygulamada şu an kullanılamıyor.", "Support isn’t available in this app right now.")
    val identifyFirst: String get() = t("Öznitelik yazmadan önce identify(email) çağırın.", "Call identify(email) before writing attributes.")
    val connectFailed: String get() = t("Bağlantı kurulamadı", "Couldn’t connect")
    val sendFailed: String get() = t("Mesaj gönderilemedi", "Couldn’t send the message")
    val ratePrompt: String get() = t("Bu görüşmeyi nasıl buldunuz?", "How was this conversation?")
}
