package com.nsupp.sdk.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nsupp.sdk.NsuppState
import com.nsupp.sdk.NsuppTexts

/**
 * Gömülebilir sohbet ekranı (Compose).
 *
 * TASARIM KARARI: mümkün olduğunca AZ görsel karar. MaterialTheme'den okur → uygulamanın kendi
 * rengi/tipografisi geçerli olur. Marka rengi dayatmak satıcının uygulamasında yabancı durur.
 * (Aynı ilke iOS SDK'daki NsuppChatView'da da yazılı.)
 */
@Composable
fun NsuppChatScreen(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(Nsupp.current?.state ?: NsuppState()) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        Nsupp.onChatOpened { st -> state = st }
        onDispose { Nsupp.onChatClosed() }
    }

    // Yeni mesajda en alta kaydır — kullanıcı elle aramasın.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        // HATA GÖRÜNÜR: sessizce boş ekran bırakmak kullanıcıya "bozuk" dedirtir.
        state.error?.let { err ->
            Text(
                err,
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.isFromVisitor) Arrangement.End else Arrangement.Start,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (m.isFromVisitor) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // ÇEVİRİ VARSA O GÖSTERİLİR — orijinal `m.body` erişilebilir kalır.
                        if (m.displayBody.isNotBlank()) {
                            Text(m.displayBody, style = MaterialTheme.typography.bodyMedium)
                        }
                        // EKLER: eskiden hiç okunmuyordu → ek-yalnız mesaj boş balondu.
                        m.attachments.forEach { a ->
                            Text(
                                (if (a.type == "image") "🖼 " else "📎 ") + (a.name ?: a.type),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        // BOT SEÇİMİ: dokunulabilir şıklar. Seçim NORMAL mesaj olarak gider —
                        // widget ile aynı kural, ayrı bir "cevap" protokolü icat etmiyoruz.
                        m.pickerChoices.forEach { ch ->
                            TextButton(onClick = { Nsupp.send(ch.replyText) }, enabled = !ch.selected) {
                                Text(ch.label)
                            }
                        }
                        if (m.isEmptyBubble) {
                            // Tanımadığımız içerik türü: boş balon çizmek yerine ne olduğunu
                            // söyleriz — sessiz boşluk teşhis edilemez.
                            Text(
                                NsuppTexts.unsupportedMessage,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    }
                }
            }
            if (state.operatorTyping) {
                item { Text("…", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        HorizontalDivider()
        if (state.pendingRating) {
            // CSAT: konuşma çözüldü ve puan bekleniyor. Sormazsak mobil kanal memnuniyet ölçümünün
            // TAMAMEN dışında kalır (web'de sorulur, mobilde sorulmazdı).
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(NsuppTexts.ratePrompt, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                (1..5).forEach { p -> TextButton(onClick = { Nsupp.rate(p) }) { Text("$p") } }
            }
            HorizontalDivider()
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(NsuppTexts.composerPlaceholder) },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val gonderilen = draft
                    draft = ""
                    // Gönderim BAŞARISIZSA metin geri gelir — ama kullanıcı bu arada yeni bir şey
                    // yazdıysa onun yazdığını EZMEYİZ (yazdığını kaybetmek asıl şikâyet konusu).
                    Nsupp.send(gonderilen) { ok -> if (!ok && draft.isEmpty()) draft = gonderilen }
                },
                enabled = draft.isNotBlank(),
            ) { Text("→", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/**
 * Hazır sohbet ekranı — satıcı tek satırla açar: `NsuppChatActivity.start(context)`.
 * Kendi ekranına gömmek isteyen `NsuppChatScreen()`i doğrudan kullanır.
 */
class NsuppChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bildirimden gelindiyse doğrudan o konuşma açılır (derin bağlantı).
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { Nsupp.current?.openConversation(it) }
        setContent { NsuppChatScreen() }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "nsupp.conversationId"

        fun start(context: Context, conversationId: String? = null) {
            val i = Intent(context, NsuppChatActivity::class.java)
            if (conversationId != null) i.putExtra(EXTRA_CONVERSATION_ID, conversationId)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}
