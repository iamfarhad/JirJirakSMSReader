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
 * Every decision is written to the event log with its reason — a seller watching «nothing
 * happened» can see that the SMS did arrive and WHY it stayed on the phone.
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
            if (!Rules.isAllowed(from, allowed)) {
                val bankish = Rules.looksLikeBank(body) && !Rules.isSecret(body)
                if (bankish) store.suggest(from)
                // Only bank-looking SMS are logged: the seller's private messages are none of the
                // app's business, not even as a line saying one arrived.
                if (bankish) {
                    store.event("ignored", "info", "پیامک بانکی از شماره‌ای که مجاز نیست", "فرستاده نشد. اگر این شمارهٔ بانک شماست، در «شماره‌های مجاز» اضافه‌اش کنید.", from)
                }
                continue
            }
            if (Rules.isSecret(body)) {
                store.event("ignored", "info", "رمز یکبار مصرف — روی گوشی ماند", "پیامک رمز و کد تأیید هرگز فرستاده نمی‌شود، حتی از شمارهٔ مجاز.", from)
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
            store.event("sms", "queued", "پیامک بانک", "در صف ارسال به ربات", from, body, id)
            queued = true
        }

        if (queued) {
            // Never wait on the network here: a flush already in progress (two timeouts per item)
            // would hold the broadcast past its limit and ANR. The foreground service keeps the
            // process alive for this thread; the retry job is the safety net when there is none.
            SyncJob.scheduleRetry(context)
            val app = context.applicationContext
            Thread { Net.flush(app) }.start()
        }
    }
}
