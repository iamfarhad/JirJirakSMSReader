package ir.jirjirak.sms

import java.net.URI
import java.net.URLEncoder

/**
 * A webhook of the seller's own: the bank SMS from [sender] also go to [url], next to the bot and
 * independent of it. A service such as a card-holder panel asks for exactly this — «send this card's
 * SMS to this address» — and the seller should not need a second forwarding app for it.
 *
 * [lastAt]/[lastOk]/[lastNote] are the last delivery's outcome, for the list.
 */
data class Hook(
    val id: String,
    val url: String,
    val sender: String,
    val on: Boolean = true,
    val lastAt: Long = 0,
    val lastOk: Boolean? = null,
    val lastNote: String? = null,
) {
    /** «cardholder.example.com», for the list and the event log. */
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: url
}

/** The rules of webhooks — pure, unit-tested. */
object Hooks {

    /** The queue's target name for the paired bot; a webhook's rows carry its [Hook.id]. */
    const val BOT = "bot"

    /**
     * The address as typed, when it is one: a full https URL with a host. Plain http only for this
     * phone itself (a test server) — anything else would send bank SMS across the internet in clear.
     */
    fun parseUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        return when (uri.scheme?.lowercase()) {
            "https" -> s
            "http" -> s.takeIf { host == "127.0.0.1" || host == "localhost" }
            else -> null
        }
    }

    /** The switched-on webhooks this SMS goes to, by its sender in any spelling. */
    fun matching(from: String, hooks: List<Hook>): List<Hook> =
        hooks.filter { it.on && Rules.isAllowed(from, listOf(it.sender)) }

    /**
     * The request: a form, `Message` = the SMS text as the bank wrote it — the field the common SMS
     * forwarding apps post and card-holder panels read — and `from` = the sender, for a service
     * that wants it. A server that knows only `Message` ignores the other.
     */
    fun form(text: String, from: String): String = "Message=" + enc(text) + "&from=" + enc(from)

    /**
     * The `seen` key of one SMS for one webhook: the bot keeps the bare fingerprint, so the same SMS
     * can be queued once for the bot and once for each webhook, and never twice for any of them.
     */
    fun seenKey(fingerprint: String, hookId: String): String = "$fingerprint#$hookId"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
