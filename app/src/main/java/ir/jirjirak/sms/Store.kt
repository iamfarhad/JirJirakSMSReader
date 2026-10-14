package ir.jirjirak.sms

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app remembers. SharedPreferences and JSON, because the data is small: one shop,
 * a handful of senders, the last few deliveries and whatever is waiting for a connection.
 */
class Store(context: Context) {

    companion object {
        /**
         * ONE lock for the whole process. Every caller builds its own Store, so an instance lock
         * (the first version's @Synchronized) guarded nothing: the receiver's enqueue and a flush's
         * remove could interleave their read-modify-write and lose an SMS.
         */
        private val LOCK = Any()
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jirjirak_sms", Context.MODE_PRIVATE)

    // ── pairing ─────────────────────────────────────────────────────────

    var endpoint: String?
        get() = prefs.getString("endpoint", null)
        set(v) = prefs.edit().putString("endpoint", v).apply()

    var shop: String?
        get() = prefs.getString("shop", null)
        set(v) = prefs.edit().putString("shop", v).apply()

    /**
     * Unpaired: nothing more goes anywhere. The queue goes (it was for that shop), and so do the log
     * rows still marked ⏳ — they will never be delivered now.
     */
    fun unpair() = synchronized(LOCK) {
        val delivered = log().filter { it.optBoolean("ok") }
        prefs.edit().remove("endpoint").remove("shop").remove("queue").remove("last_error")
            .putString("log", JSONArray(delivered).toString()).apply()
    }

    // ── allowed senders: the seller's own list, empty until they add one ─

    var senders: List<String>
        get() = strings("senders")
        set(v) = putStrings("senders", v.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Rules.normalize(it) })

    fun addSender(s: String) {
        senders = senders + s
        suggestions = suggestions.filter { Rules.normalize(it) != Rules.normalize(s) }
    }

    fun removeSender(s: String) {
        senders = senders.filter { it != s }
    }

    /** Senders whose SMS looked like a bank's but were NOT forwarded — offered, never added on their own. */
    var suggestions: List<String>
        get() = strings("suggestions")
        set(v) = putStrings("suggestions", v.distinctBy { Rules.normalize(it) }.takeLast(10))

    fun suggest(sender: String) {
        if (!Rules.isAllowed(sender, senders) && suggestions.none { Rules.normalize(it) == Rules.normalize(sender) }) {
            suggestions = suggestions + sender
        }
    }

    fun dismissSuggestion(s: String) {
        suggestions = suggestions.filter { it != s }
    }

    // ── what happened ───────────────────────────────────────────────────

    var lastPing: Long
        get() = prefs.getLong("last_ping", 0)
        set(v) = prefs.edit().putLong("last_ping", v).apply()

    var lastSent: Long
        get() = prefs.getLong("last_sent", 0)
        set(v) = prefs.edit().putLong("last_sent", v).apply()

    // ── updates ─────────────────────────────────────────────────────────

    var lastUpdateCheck: Long
        get() = prefs.getLong("update_checked", 0)
        set(v) = prefs.edit().putLong("update_checked", v).apply()

    var latestVersion: String?
        get() = prefs.getString("latest_version", null)
        set(v) = prefs.edit().putString("latest_version", v).apply()

    var latestUrl: String?
        get() = prefs.getString("latest_url", null)
        set(v) = prefs.edit().putString("latest_url", v).apply()

    /** The version a notification was already sent for — one notification per release. */
    var notifiedVersion: String?
        get() = prefs.getString("notified_version", null)
        set(v) = prefs.edit().putString("notified_version", v).apply()

    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(v) = prefs.edit().putString("last_error", v).apply()

    /** The last deliveries, newest first: sender, time, and whether it reached the bot yet. */
    fun log(): List<JSONObject> = array("log").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    fun addLog(sender: String, at: Long, delivered: Boolean, id: String) = synchronized(LOCK) {
        val items = listOf(JSONObject().put("from", sender).put("at", at).put("ok", delivered).put("id", id)) + log()
        prefs.edit().putString("log", JSONArray(items.take(30)).toString()).apply()
    }

    fun markDelivered(id: String) = synchronized(LOCK) {
        val items = log().map { if (it.optString("id") == id) it.put("ok", true) else it }
        prefs.edit().putString("log", JSONArray(items).toString()).apply()
    }

    // ── the queue: SMS waiting for a connection ─────────────────────────

    fun enqueue(item: JSONObject) = synchronized(LOCK) {
        val q = array("queue")
        q.put(item)
        while (q.length() > 500) q.remove(0)
        prefs.edit().putString("queue", q.toString()).apply()
    }

    fun queue(): List<JSONObject> = synchronized(LOCK) { array("queue") }.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    /**
     * Take one delivered SMS off the queue, by its id — not «the first n»: the 500-cap trim or a
     * re-pair can move the queue under a flush, and dropping by count would then drop unsent ones.
     */
    fun remove(id: String) = synchronized(LOCK) {
        val rest = queue().filter { it.optString("id") != id }
        prefs.edit().putString("queue", JSONArray(rest).toString()).apply()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun array(key: String): JSONArray =
        try { JSONArray(prefs.getString(key, "[]")) } catch (e: Exception) { JSONArray() }

    private fun strings(key: String): List<String> = array(key).let { a -> (0 until a.length()).map { a.optString(it) } }

    private fun putStrings(key: String, values: List<String>) =
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
}
