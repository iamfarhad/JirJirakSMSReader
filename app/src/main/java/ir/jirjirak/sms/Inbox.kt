package ir.jirjirak.sms

import android.content.Context
import android.provider.Telephony

/**
 * «انتخاب از پیامک‌ها» — the senders in the phone's own inbox, for the seller to pick from instead of
 * typing a number they would have to copy off a message anyway.
 *
 * Read only when the seller opens the picker, kept only on screen, and never sent anywhere: the list
 * is senders and a short preview, and a password SMS shows no preview at all.
 */
object Inbox {

    data class Sms(val from: String, val body: String, val at: Long)

    data class Sender(val from: String, val count: Int, val lastAt: Long, val bankish: Boolean, val preview: String?)

    /**
     * One row per sender (however its number is written), bank-looking ones first, then the most
     * recent. Pure — the grouping is unit-tested; [read] is the only part that touches Android.
     */
    fun group(messages: List<Sms>, alreadyAllowed: Collection<String>): List<Sender> =
        messages
            .filter { Rules.normalize(it.from).isNotEmpty() && !Rules.isAllowed(it.from, alreadyAllowed) }
            .groupBy { Rules.normalize(it.from) }
            .map { (_, list) ->
                val latest = list.maxBy { it.at }
                val bankish = list.any { Rules.looksLikeBank(it.body) && !Rules.isSecret(it.body) }
                Sender(
                    from = latest.from,
                    count = list.size,
                    lastAt = latest.at,
                    bankish = bankish,
                    preview = if (Rules.isSecret(latest.body)) null else latest.body.replace('\n', ' ').take(70),
                )
            }
            .sortedWith(compareByDescending<Sender> { it.bankish }.thenByDescending { it.lastAt })

    /** The last few hundred inbox messages. Empty when the permission is missing or the read fails. */
    fun read(context: Context, limit: Int = 300): List<Sms> = runCatching {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER,
        )?.use { c ->
            val out = mutableListOf<Sms>()
            while (c.moveToNext() && out.size < limit) {
                out += Sms(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2))
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())
}
