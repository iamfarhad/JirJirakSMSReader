package ir.jirjirak.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject
import java.util.UUID

/**
 * A new SMS arrived. Forwarded only if the seller allowed its sender — for the bot, or for one of
 * their webhooks — and it is not a password; otherwise, if it looks like a bank's, its SENDER (never
 * its text) is offered as a suggestion. The decision itself is [Forward.accept].
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
     * Queue [body] from [from] for the bot (an allowed sender) and for every switched-on webhook of
     * that sender; log why when it goes nowhere. True when anything was queued. [sentAt] is the SMS
     * centre's stamp, what makes the fingerprint (Rules.fingerprint).
     */
    fun accept(store: Store, from: String, body: String, sentAt: Long): Boolean {
        val toBot = Rules.isAllowed(from, store.senders)
        val toHooks = Hooks.matching(from, store.activeHooks)
        if (!toBot && toHooks.isEmpty()) {
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
        var queued = false
        if (toBot) queued = queue(store, from, body, sentAt, fingerprint, null) or queued
        for (hook in toHooks) queued = queue(store, from, body, sentAt, Hooks.seenKey(fingerprint, hook.id), hook) or queued
        return queued
    }

    /** One row for the bot ([hook] null) or for one webhook, and its line in the event log. */
    fun queue(store: Store, from: String, body: String, sentAt: Long, seenKey: String, hook: Hook?, receivedAt: Long = System.currentTimeMillis()): Boolean {
        val id = UUID.randomUUID().toString()
        val item = JSONObject()
            .put("id", id)
            .put("message", body)
            .put("from", from)
            .put("received_at", receivedAt / 1000)
            .put("sent_at", sentAt)
            .put("source", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
        val fresh = runCatching { store.enqueue(item, seenKey, hook?.id ?: Hooks.BOT) }.getOrElse {
            // A full disk or a broken database: the SMS is still in the phone's inbox, and the log
            // says why it did not go — the seller frees space and confirms that deposit in the bot.
            store.event(if (hook == null) "sms" else "hook", "failed", "پیامک بانک ذخیره نشد", "حافظهٔ گوشی پر است یا خطای پایگاه‌داده؛ پیامک در صندوق گوشی هست.", from, body)
            return false
        }
        // This very SMS was delivered before (a broadcast repeated): already on its way.
        if (!fresh) return false
        when {
            hook != null -> store.event("hook", "queued", "وب‌هوک · " + hook.host, "در صف ارسال", from, body, id)
            else -> store.event("sms", "queued", "پیامک بانک", "در صف ارسال به ربات", from, body, id)
        }
        return true
    }
}
