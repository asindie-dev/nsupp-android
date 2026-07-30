package com.nsupp.sdk.android

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM köprüsü. Satıcı bunu AndroidManifest'e ekler; kendi servisi varsa `Nsupp.registerPushToken`
 * ve `NsuppPush.isNsupp` çağrılarını kendi servisinden yapabilir (zorunlu miras YOK).
 *
 * BİLDİRİMİ BİZ GÖSTERMEYİZ: kanal/ikon/ses satıcının tasarım kararıdır ve Android 13+ izin akışı
 * uygulamanındır. Yük [NsuppPush] ile ayrıştırılır, gösterim uygulamada kalır.
 */
open class NsuppMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Nsupp.registerPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Varsayılan: hiçbir şey. Satıcı bu sınıftan türetip kendi bildirimini gösterir.
    }
}

/** Gelen FCM yükünün nsupp'a ait olup olmadığını ve hangi konuşmayı açacağını söyler. */
object NsuppPush {
    fun conversationId(data: Map<String, String>): String? = data["conversationId"]
    fun isNsupp(data: Map<String, String>): Boolean = data.containsKey("conversationId")
}
