package ir.jirjirak.sms

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Everything the app remembers. SharedPreferences and JSON, because the data is small: one shop,
 * a handful of senders, the last few hundred events and whatever is waiting for a connection.
 */
class Store(context: Context) {

    companion object {
        /**
         * ONE lock for the whole process. Every caller builds its own Store, so an instance lock
         * (the first version's @Synchronized) guarded nothing: the receiver's enqueue and a flush's
         * remove could interleave their read-modify-write and lose an SMS.
         */
        private val LOCK = Any()

        /**
         * Bumped on every write. A composable that reads it is redrawn on the next change — which
         * is the only way the screen ever learns that a receiver, a job or a button wrote something:
         * SharedPreferences is not observable, and Compose skips a card whose parameters did not
         * change. The first version's cards were skipped exactly like that: «مجاز کن» saved the
         * number and the list did not show it until the app was reopened.
         */
        val revision = mutableIntStateOf(0)

        const val EVENTS_MAX = 200

        /**
         * The event log, parsed once per write instead of once per read: the dashboard, the report
         * and the widget all read it, and every recomposition used to parse 200 JSON rows again.
         *
         * Keyed by the stored string itself (by reference — SharedPreferences hands back the very
         * object it holds), not by [revision]: a counter read before and after a parse let a write in
         * between file the old rows under the new number, and the next event() built on them.
         */
        @Volatile private var eventsCache: Pair<String, List<JSONObject>>? = null

        /** Keyed by the outbox's own write counter, for the same reason. */
        @Volatile private var sizeCache: Pair<Int, Int>? = null

        private val REV = Any()

        /** A Keystore that refused once is not asked again on every read of this run. */
        @Volatile private var sealBroken = false
    }

    private val app: Context = context.applicationContext

    private val prefs: SharedPreferences = app.getSharedPreferences("jirjirak_sms", Context.MODE_PRIVATE)

    private fun commit(edit: SharedPreferences.Editor) {
        edit.apply()
        bump()
    }

    /** One more write, told to the screen. Locked: `++` from two threads at once loses one. */
    private fun bump() = synchronized(REV) { revision.intValue++ }

    // ── pairing ─────────────────────────────────────────────────────────

    /** The shop's ingest address — it carries the secret, so it is kept sealed (Secrets). */
    var endpoint: String?
        get() = secret("endpoint")
        set(v) = putSecret("endpoint", v)

    var shop: String?
        get() = prefs.getString("shop", null)
        set(v) = commit(prefs.edit().putString("shop", v))

    /** The bot's host, for the seller and for support: «bot.example.com». */
    val server: String?
        get() = endpoint?.let { runCatching { java.net.URL(it).host }.getOrNull() }

    /** What the bot said about the shop in its last ping reply (subscription, bot username…). */
    var shopInfo: JSONObject
        get() = runCatching { JSONObject(prefs.getString("shop_info", null) ?: "{}") }.getOrDefault(JSONObject())
        set(v) = commit(prefs.edit().putString("shop_info", v.toString()))

    /** Does the paired bot understand this (its ping reply's `features`)? An older bot says nothing. */
    fun supports(feature: String): Boolean =
        shopInfo.optJSONArray("features")?.let { a -> (0 until a.length()).any { a.optString(it) == feature } } ?: false

    /**
     * Unpaired: nothing more goes anywhere. The queue goes (it was for that shop); the events stay,
     * so the seller can still read what happened.
     */
    fun unpair() = synchronized(LOCK) {
        Outbox.get(app).clear()
        commit(
            prefs.edit().remove("endpoint").remove("shop").remove("shop_info").remove("queue")
                .remove("last_error").remove("latest_from_bot").remove("manage_key").remove("manage_key_revoked")
                .remove("battery_warned").remove("offline_warned")
                // Recovery starts over with the next shop: SMS from while this phone was nobody's
                // must not be sent to whoever it is paired with next.
                .remove("recovery_on").remove("recovery_from").remove("last_recovery")
                .remove("told_pending").remove("told_mismatch"),
        )
    }

    // ── allowed senders: the seller's own list, empty until they add one ─

    var senders: List<String>
        get() = strings("senders")
        set(v) = putStrings("senders", v.map { it.trim() }.filter { Rules.normalize(it).isNotEmpty() }
            .fold(listOf<String>()) { kept, s -> if (Rules.isAllowed(s, kept)) kept else kept + s })

    fun addSender(s: String) {
        senders = senders + s
        suggestions = suggestions.filter { !Rules.isAllowed(it, listOf(s)) }
    }

    fun removeSender(s: String) {
        senders = senders.filter { it != s }
    }

    /** Senders whose SMS looked like a bank's but were NOT forwarded — offered, never added on their own. */
    var suggestions: List<String>
        get() = strings("suggestions")
        set(v) = putStrings("suggestions", v.distinctBy { Rules.normalize(it) }.takeLast(10))

    fun suggest(sender: String) {
        if (!Rules.isAllowed(sender, senders) && !Rules.isAllowed(sender, suggestions)) {
            suggestions = suggestions + sender
        }
    }

    fun dismissSuggestion(s: String) {
        suggestions = suggestions.filter { it != s }
    }

    // ── what happened ───────────────────────────────────────────────────

    var lastPing: Long
        get() = prefs.getLong("last_ping", 0)
        set(v) = commit(prefs.edit().putLong("last_ping", v))

    var lastSent: Long
        get() = prefs.getLong("last_sent", 0)
        set(v) = commit(prefs.edit().putLong("last_sent", v))

    /** «revoked», «send», «ping» or null — the last thing that went wrong on the network. */
    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(v) = commit(prefs.edit().putString("last_error", v))

    /** Told once each (Alerts) until the battery recovers / the bot answers again. */
    var batteryWarned: Boolean
        get() = prefs.getBoolean("battery_warned", false)
        set(v) = commit(prefs.edit().putBoolean("battery_warned", v))

    var offlineWarned: Boolean
        get() = prefs.getBoolean("offline_warned", false)
        set(v) = commit(prefs.edit().putBoolean("offline_warned", v))

    /** The bot's second key, for deciding receipts from the app («رسیدها»). Null until pasted. */
    var manageKey: String?
        get() = secret("manage_key")
        set(v) = putSecret("manage_key", v)

    /** The bot refused the manage key (401): the tab asks for a fresh one, and says why. */
    var manageKeyRevoked: Boolean
        get() = prefs.getBoolean("manage_key_revoked", false)
        set(v) = commit(prefs.edit().putBoolean("manage_key_revoked", v))

    fun revokeManageKey() = commit(prefs.edit().remove("manage_key").putBoolean("manage_key_revoked", true))

    /** «ظاهر»: "system" (follow the phone), "light" or "dark". */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = commit(prefs.edit().putString("theme", v))

    /** The seller said they switched on the vendor's «Autostart» — the phone has no way to tell us. */
    var autostartConfirmed: Boolean
        get() = prefs.getBoolean("autostart_confirmed", false)
        set(v) = commit(prefs.edit().putBoolean("autostart_confirmed", v))

    /**
     * This install, as the bot tells several phones of one shop apart. Made once, never sent anywhere
     * but the shop's own bot, and not derived from anything about the phone.
     */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: synchronized(LOCK) {
            prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("device_id", it).commit()
            }
        }

    // ── recovery: SMS that arrived while the app could not hear them ────

    /** «بازیابی پیامک‌های جامانده» — off until the seller turns it on (it needs READ_SMS). */
    var recoveryOn: Boolean
        get() = prefs.getBoolean("recovery_on", false)
        set(v) = commit(prefs.edit().putBoolean("recovery_on", v))

    /**
     * Nothing older than this is ever recovered: the moment recovery was turned on. SMS before it were
     * the old app's business, and resending them could make yesterday's deposit look like today's.
     */
    var recoveryFrom: Long
        get() = prefs.getLong("recovery_from", 0)
        set(v) = commit(prefs.edit().putLong("recovery_from", v))

    var lastRecovery: Long
        get() = prefs.getLong("last_recovery", 0)
        set(v) = commit(prefs.edit().putLong("last_recovery", v))

    // ── notifications the seller chose ──────────────────────────────────

    /** «رسید منتظر بررسی» — on by default once the manage key is in. */
    var notifyReview: Boolean
        get() = prefs.getBoolean("notify_review", true)
        set(v) = commit(prefs.edit().putBoolean("notify_review", v))

    /** «واریز بدون فاکتور / پیامک خوانده‌نشده». */
    var notifyMismatch: Boolean
        get() = prefs.getBoolean("notify_mismatch", true)
        set(v) = commit(prefs.edit().putBoolean("notify_mismatch", v))

    /** No review/mismatch notification between 23:00 and 08:00; they wait for the morning. */
    var quietNights: Boolean
        get() = prefs.getBoolean("quiet_nights", false)
        set(v) = commit(prefs.edit().putBoolean("quiet_nights", v))

    /** What the last notification already told — a count told once is not told again. */
    var toldPending: Int
        get() = prefs.getInt("told_pending", 0)
        set(v) = commit(prefs.edit().putInt("told_pending", v))

    var toldMismatch: Int
        get() = prefs.getInt("told_mismatch", 0)
        set(v) = commit(prefs.edit().putInt("told_mismatch", v))

    // ── updates ─────────────────────────────────────────────────────────

    var lastUpdateCheck: Long
        get() = prefs.getLong("update_checked", 0)
        set(v) = commit(prefs.edit().putLong("update_checked", v))

    var latestVersion: String?
        get() = prefs.getString("latest_version", null)
        set(v) = commit(prefs.edit().putString("latest_version", v))

    var latestUrl: String?
        get() = prefs.getString("latest_url", null)
        set(v) = commit(prefs.edit().putString("latest_url", v))

    /** The version a notification was already sent for — one notification per release. */
    var notifiedVersion: String?
        get() = prefs.getString("notified_version", null)
        set(v) = commit(prefs.edit().putString("notified_version", v))

    /** The DownloadManager id of the APK being fetched, and the version it is. */
    var downloadId: Long
        get() = prefs.getLong("download_id", -1)
        set(v) = commit(prefs.edit().putLong("download_id", v))

    var downloadVersion: String?
        get() = prefs.getString("download_version", null)
        set(v) = commit(prefs.edit().putString("download_version", v))

    /** A version whose APK is on the phone, ready to install, and where it is. */
    var downloadedVersion: String?
        get() = prefs.getString("downloaded_version", null)
        set(v) = commit(prefs.edit().putString("downloaded_version", v))

    var downloadedFile: String?
        get() = prefs.getString("downloaded_file", null)
        set(v) = commit(prefs.edit().putString("downloaded_file", v))

    // ── the event log: what the app did and why ─────────────────────────

    /**
     * One row per thing that happened, newest first: an SMS forwarded (and what the bot made of
     * it), one ignored (and why), the connection lost or back, an update, a reboot. This is the
     * answer to «did it work?» — the first version only listed deliveries, and a seller whose SMS
     * went nowhere had nothing to look at.
     *
     * Fields: id, at, kind (sms | ignored | net | update | system), status (queued | sent | failed |
     * retry | ok | warn | info), from, title, detail, body (sms only, first 160 chars — it is the
     * seller's own phone), verdict (the bot's word on an SMS).
     */
    fun events(): List<JSONObject> {
        if (prefs.contains("log")) migrateLog()
        val raw = prefs.getString("events", null) ?: return emptyList()
        eventsCache?.let { (at, rows) -> if (at === raw) return rows }
        return parseEvents(raw).also { eventsCache = raw to it }
    }

    /** New objects every call — for the writers, which change rows and must not touch the cached ones. */
    private fun freshEvents(): List<JSONObject> {
        if (prefs.contains("log")) migrateLog()
        return parseEvents(prefs.getString("events", null))
    }

    private fun parseEvents(raw: String?): List<JSONObject> =
        runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    fun event(
        kind: String, status: String, title: String, detail: String? = null,
        from: String? = null, body: String? = null, id: String = UUID.randomUUID().toString(),
    ): String {
        synchronized(LOCK) {
            val row = JSONObject().put("id", id).put("at", System.currentTimeMillis())
                .put("kind", kind).put("status", status).put("title", title)
            if (detail != null) row.put("detail", detail)
            if (from != null) row.put("from", from)
            if (body != null) row.put("body", body.take(400))
            val items = listOf(row) + freshEvents()
            commit(prefs.edit().putString("events", JSONArray(items.take(EVENTS_MAX)).toString()))
        }
        return id
    }

    /** Move an SMS event along: queued → sent (with the bot's verdict) / retry / failed. */
    fun updateEvent(id: String, status: String, detail: String? = null, verdict: String? = null) = synchronized(LOCK) {
        var found = false
        val items = freshEvents().map {
            if (it.optString("id") == id) {
                found = true
                it.put("status", status)
                if (detail != null) it.put("detail", detail)
                if (verdict != null) it.put("verdict", verdict)
            }
            it
        }
        if (found) commit(prefs.edit().putString("events", JSONArray(items).toString()))
    }

    fun clearEvents() = synchronized(LOCK) { commit(prefs.edit().remove("events").remove("log")) }

    /** Forwarded today, by the phone's clock. */
    fun sentToday(now: Long = System.currentTimeMillis()): Int {
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return events().count { it.optString("kind") == "sms" && it.optString("status") == "sent" && it.optLong("at") >= midnight }
    }

    /** The 1.0 «log» (deliveries only) becomes the oldest rows of the event log, once. */
    private fun migrateLog(): JSONArray {
        val events = array("events")
        val old = prefs.getString("log", null) ?: return events
        synchronized(LOCK) {
            val rows = runCatching { JSONArray(old) }.getOrDefault(JSONArray())
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                events.put(
                    JSONObject().put("id", r.optString("id")).put("at", r.optLong("at")).put("kind", "sms")
                        .put("status", if (r.optBoolean("ok")) "sent" else "queued").put("from", r.optString("from"))
                        .put("title", "پیامک بانک"),
                )
            }
            commit(prefs.edit().remove("log").putString("events", events.toString()))
        }
        return events
    }

    // ── the queue: SMS waiting for a connection ─────────────────────────

    /** The queue's database, with the 1.1 JSON queue moved into it the first time it is opened. */
    private fun outbox(): Outbox {
        val box = Outbox.get(app)
        if (prefs.contains("queue")) synchronized(LOCK) {
            // The JSON goes only once every SMS in it is in the database: a full disk keeps it for
            // the next try instead of losing a day's deposits.
            if (prefs.contains("queue") && box.migrate(prefs.getString("queue", null))) {
                prefs.edit().remove("queue").commit()
            }
        }
        return box
    }

    /** Queue one SMS; false when this same SMS ([fingerprint]) was queued before. */
    fun enqueue(item: JSONObject, fingerprint: String? = null): Boolean =
        outbox().enqueue(item, fingerprint).also { if (it) bump() }

    fun queue(): List<JSONObject> = outbox().all()

    /** Cached per write like the events: the dashboard and the widget ask on every frame. */
    fun queueSize(): Int {
        val box = outbox()
        val version = box.version
        sizeCache?.let { (at, n) -> if (at == version) return n }
        return box.size().also { sizeCache = version to it }
    }

    /** Take one delivered SMS off the queue, by its id — never «the first n». */
    fun remove(id: String) {
        outbox().remove(id)
        bump()
    }

    fun bump(id: String) = outbox().bump(id)

    fun seen(fingerprint: String): Boolean = outbox().seen(fingerprint)

    fun markSeen(fingerprint: String) = outbox().markSeen(fingerprint)

    fun pruneSeen() = outbox().prune()

    // ── helpers ─────────────────────────────────────────────────────────

    /** A sealed value, opened; a 1.1 plaintext one sealed in place the first time it is read. */
    private fun secret(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!Secrets.isSealed(stored) && !sealBroken) {
            runCatching { Secrets.seal(stored) }
                // Written back only if nobody changed it meanwhile — an unpair or a new pairing wins.
                .onSuccess { sealed -> synchronized(LOCK) { if (prefs.getString(key, null) == stored) prefs.edit().putString(key, sealed).apply() } }
                .onFailure { sealBroken = true }
            return stored
        }
        val plain = Secrets.open(stored)
        if (plain == null) {
            // Sealed with a key this phone does not have (data moved from another phone): say so and
            // start over, rather than look paired and drop every SMS without a word.
            synchronized(LOCK) {
                if (prefs.getString(key, null) == stored) {
                    commit(prefs.edit().remove(key))
                    event("system", "warn", if (key == "endpoint") "کد اتصال روی این گوشی باز نشد" else "کد مدیریت روی این گوشی باز نشد",
                        "اطلاعات برنامه از گوشی دیگری منتقل شده است. کد را دوباره از ربات بچسبانید.")
                }
            }
        }
        return plain
    }

    private fun putSecret(key: String, value: String?) {
        val stored = value?.let { v -> if (sealBroken) v else runCatching { Secrets.seal(v) }.getOrElse { sealBroken = true; v } }
        commit(prefs.edit().putString(key, stored))
    }

    private fun array(key: String): JSONArray =
        try { JSONArray(prefs.getString(key, "[]")) } catch (e: Exception) { JSONArray() }

    private fun strings(key: String): List<String> = array(key).let { a -> (0 until a.length()).map { a.optString(it) } }

    private fun putStrings(key: String, values: List<String>) =
        commit(prefs.edit().putString(key, JSONArray(values).toString()))
}
