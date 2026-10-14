package ir.jirjirak.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject
import java.util.UUID

/**
 * A new SMS arrived. Forwarded only if the seller allowed its sender and it is not a password;
 * otherwise, if it looks like a bank's, its SENDER (never its text) is offered as a suggestion.
 *
 * SMS_RECEIVED is still delivered to a manifest receiver with the app closed, which is what lets
 * this work without a running service or a permanent notification.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val store = Store(context)
        if (store.endpoint == null) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives in parts; join them per sender, in order.
        val bySender = linkedMapOf<String, StringBuilder>()
        for (p in parts) {
            val from = p?.displayOriginatingAddress ?: continue
            bySender.getOrPut(from) { StringBuilder() }.append(p.messageBody ?: "")
        }

        val allowed = store.senders
        var queued = false
        for ((from, text) in bySender) {
            val body = text.toString()
            if (!Rules.shouldForward(from, body, allowed)) {
                if (!Rules.isAllowed(from, allowed) && Rules.looksLikeBank(body) && !Rules.isSecret(body)) {
                    store.suggest(from)
                }
                continue
            }
            val id = UUID.randomUUID().toString()
            store.enqueue(
                JSONObject()
                    .put("id", id)
                    .put("message", body)
                    .put("from", from)
                    .put("received_at", System.currentTimeMillis() / 1000)
                    .put("source", "jirjirak-sms/" + BuildConfig.VERSION_NAME),
            )
            store.addLog(from, System.currentTimeMillis(), false, id)
            queued = true
        }

        if (queued) {
            val pending = goAsync()
            Thread {
                try {
                    if (Net.flush(context) == 0) SyncJob.scheduleRetry(context)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
