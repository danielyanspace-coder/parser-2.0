package com.checkout.alfasignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log

/**
 * Listens for every incoming SMS. When a message (from any number) contains the
 * trigger word ("символ" by default), it fires the signal webhook by HTTP GET.
 */
class SmsWatcher : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.enabled(context)) return

        // Reassemble the full message body (multipart SMS arrive as several parts).
        val body = try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            val sender = msgs.firstOrNull()?.originatingAddress ?: ""
            val text = buildString { for (m in msgs) append(m.messageBody ?: "") }
            Pair(sender, text)
        } catch (e: Exception) {
            Log.e("AlfaSignal", "parse error", e); return
        }
        val sender = body.first
        val text = body.second

        val word = Prefs.word(context)
        if (!text.contains(word, ignoreCase = true)) return

        val url = Prefs.url(context)
        Log.i("AlfaSignal", "Trigger '$word' from '$sender' -> firing webhook")

        // Fire on a background thread; keep the broadcast alive until it finishes.
        val pending = goAsync()
        Thread {
            try {
                val code = Webhook.fireGet(url)
                val ok = code in 200..299
                Prefs.addLog(
                    context,
                    "${Webhook.now()}  ${if (ok) "✅" else "⚠️"} «$word» от ${sender.ifBlank { "?" }} → " +
                        (if (code >= 0) "HTTP $code" else "нет сети")
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notifier.show(context, ok, code)
                }
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }
}
