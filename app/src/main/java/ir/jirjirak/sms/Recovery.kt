package ir.jirjirak.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

/**
 * «بازیابی پیامک‌های جامانده»: the bank SMS that reached the phone while the app could not hear
 * them — killed by a battery saver, mid-update, the phone rebooting — found in the inbox and sent
 * like any other. Opt-in (it needs READ_SMS), and bounded three ways:
 *
 *  - never before the moment the seller turned it on, and never more than [WINDOW_MS] back;
 *  - only allowed senders, never a password (the same [Rules] as the receiver);
 *  - never an SMS this phone already handled ([Rules.fingerprint] in [Outbox]'s `seen`).
 *
 * The bot dedupes too (the SMS's own day, BankSmsController::smsDay), so even a slip here cannot
 * make one deposit two.
 */
object Recovery {

    /** Two days: an outage longer than that is one the seller has noticed, and old money has no open invoice. */
    const val WINDOW_MS = 48 * 3600_000L

    data class Sms(val from: String, val body: String, val date: Long, val dateSent: Long)

    /**
     * Which inbox messages to send. Pure: unit-tested. [seen] answers for a fingerprint; [since] is
     * the later of the opt-in moment and the window.
     */
    fun pick(
        inbox: List<Sms>, allowed: Collection<String>, since: Long,
        logged: Set<String> = emptySet(), seen: (String) -> Boolean,
    ): List<Pair<Sms, String>> =
        inbox.asSequence()
            .filter { it.date > since && Rules.isAllowed(it.from, allowed) && !Rules.isSecret(it.body) }
            // An SMS app that keeps no SMS-centre stamp (date_sent 0) gives the receiver and the inbox
            // different fingerprints; what the event log already holds is the second check.
            .filter { it.dateSent > 0 || logKey(it.from, it.body) !in logged }
            .map { it to Rules.fingerprint(it.from, it.body, it.dateSent) }
            .filter { (_, fp) -> !seen(fp) }
            .distinctBy { it.second }
            .sortedBy { it.first.date }
            .toList()

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** One pass: queue what was missed and send it. Returns how many were recovered. */
    /** One SMS as the event log can recognise it: sender (any spelling) and text. */
    fun logKey(from: String, body: String): String = (Rules.variants(from).minOrNull() ?: from) + "\n" + Rules.canon(body).trim()

    /** Not more often than this unless asked: Home redraws on every tab switch, the inbox need not be read. */
    private const val MIN_GAP_MS = 60_000L

    @Synchronized
    fun scan(context: Context, now: Long = System.currentTimeMillis(), force: Boolean = false): Int {
        val store = Store(context)
        if (!store.recoveryOn || store.endpoint == null || !permitted(context)) return 0
        if (!force && now - store.lastRecovery in 0 until MIN_GAP_MS) return 0
        val since = maxOf(store.recoveryFrom, now - WINDOW_MS)
        val logged = store.events().filter { it.optString("kind") == "sms" }.map { logKey(it.optString("from"), it.optString("body")) }.toSet()
        val found = pick(read(context, since), store.senders, since, logged) { store.seen(it) }
        var queued = 0
        for ((sms, fp) in found) {
            val id = UUID.randomUUID().toString()
            val fresh = store.enqueue(
                JSONObject()
                    .put("id", id)
                    .put("message", sms.body)
                    .put("from", sms.from)
                    // The day the phone got it — the bot dedupes on that day, not on today.
                    .put("received_at", sms.date / 1000)
                    .put("sent_at", sms.dateSent)
                    .put("recovered", true)
                    .put("source", "jirjirak-sms/" + BuildConfig.VERSION_NAME),
                fp,
            )
            if (fresh) {
                queued++
                store.event("sms", "queued", "پیامک جامانده بازیابی شد", "این پیامک وقتی برنامه در دسترس نبود رسیده بود؛ حالا فرستاده می‌شود.", sms.from, sms.body, id)
            }
        }
        store.lastRecovery = now
        store.pruneSeen()
        if (queued > 0) Net.flush(context)
        return queued
    }

    /** Inbox messages newer than [since], the newest 300 at most. Empty without READ_SMS. */
    private fun read(context: Context, since: Long): List<Sms> = runCatching {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            Telephony.Sms.DATE + " > ?", arrayOf(since.toString()),
            // No «LIMIT» here: the SMS provider rejects anything but a plain ORDER BY on newer Android,
            // and the query silently came back empty. The cap is applied while reading instead — and
            // newest first, so a busy phone's last 300 are what is read, not its oldest 300 for ever.
            Telephony.Sms.DATE + " DESC",
        )?.use { c ->
            val out = ArrayList<Sms>()
            while (out.size < 300 && c.moveToNext()) out += Sms(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2), c.getLong(3))
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())
}
