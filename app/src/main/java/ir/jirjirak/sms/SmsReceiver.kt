package ir.jirjirak.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject
import java.util.UUID

/**
 * A new SMS arrived. The bot receives it only if the seller allowed its sender and it contains no
 * password. Other bank messages can suggest their sender without exposing their text.
 * The decision itself is [Forward.accept].
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
        // The SMS centre's stamp of each message: part of its fingerprint (Rules.fingerprint), so the
        // same SMS delivered twice is sent once, and two deposits with the same text stay two.
        val sentAt = hashMapOf<String, Long>()
        for (p in parts) {
            val from = p?.displayOriginatingAddress ?: continue
            bySender.getOrPut(from) { StringBuilder() }.append(p.messageBody ?: "")
            sentAt.putIfAbsent(from, p.timestampMillis)
        }

        var queued = false
        for ((from, text) in bySender) {
            if (Forward.accept(store, from, text.toString(), sentAt[from] ?: 0L)) queued = true
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

/** What happens to one arrived SMS — the receiver's decision, apart from Android's intent so it is tested. */
object Forward {

    /**
     * Queue [body] from [from] for the bot or log why it stayed on the phone. True when queued.
     * [sentAt] is the SMS centre's stamp used for the fingerprint (Rules.fingerprint).
     */
    fun accept(store: Store, from: String, body: String, sentAt: Long): Boolean {
        if (!Rules.isAllowed(from, store.senders)) {
            val bankish = Rules.looksLikeBank(body) && !Rules.isSecret(body)
            if (bankish) store.suggest(from)
            // Only bank-looking SMS are logged: the seller's private messages are none of the
            // app's business, not even as a line saying one arrived.
            if (bankish) {
                store.event("ignored", "info", "پیامک بانکی از شماره‌ای که مجاز نیست", "فرستاده نشد. اگر این شمارهٔ بانک شماست، در «شماره‌های مجاز» اضافه‌اش کنید.", from)
            }
            return false
        }
        if (Rules.isSecret(body)) {
            // Not even its fingerprint is kept: a hash of a 6-digit code is a 6-digit code.
            store.event("ignored", "info", "رمز یکبار مصرف — روی گوشی ماند", "پیامک رمز و کد تأیید هرگز فرستاده نمی‌شود، حتی از شمارهٔ مجاز.", from)
            return false
        }
        val fingerprint = Rules.fingerprint(from, body, sentAt)
        val id = UUID.randomUUID().toString()
        val item = JSONObject()
            .put("id", id)
            .put("message", body)
            .put("from", from)
            .put("received_at", System.currentTimeMillis() / 1000)
            .put("sent_at", sentAt)
            .put("source", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
        val fresh = runCatching { store.enqueue(item, fingerprint) }.getOrElse {
            // A full disk or a broken database: the SMS is still in the phone's inbox, and the log
            // says why it did not go — the seller frees space and confirms that deposit in the bot.
            store.event("sms", "failed", "پیامک بانک ذخیره نشد", "حافظهٔ گوشی پر است یا خطای پایگاه‌داده؛ پیامک در صندوق گوشی هست.", from, body)
            return false
        }
        // This very SMS was delivered before (a broadcast repeated): already on its way.
        if (!fresh) return false
        store.event("sms", "queued", "پیامک بانک", "در صف ارسال به ربات", from, body, id)
        return true
    }
}
