package ir.jirjirak.sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * The SMS waiting for a connection, in a database instead of one JSON string in SharedPreferences.
 *
 * The JSON queue was rewritten whole on every enqueue and every delivery: fine for three SMS,
 * quadratic for three hundred after a day offline, and a crash mid-write could lose the lot. Here
 * every row is its own insert and delete, a full disk refuses one SMS instead of all of them, and
 * nothing is trimmed: the old 500-row cap silently dropped the oldest SMS of a long outage.
 *
 * Plain SQLiteOpenHelper rather than Room: two tables, a handful of queries, and no annotation
 * processor to keep in step with the Kotlin compiler.
 *
 * `seen` is every SMS this phone has already queued, by fingerprint (Rules.fingerprint) — what lets
 * [Recovery] read the inbox again without sending anything twice. Kept a week.
 */
class Outbox private constructor(context: Context) : SQLiteOpenHelper(context, "outbox.db", null, 1) {

    companion object {
        @Volatile private var instance: Outbox? = null

        fun get(context: Context): Outbox =
            instance ?: synchronized(this) { instance ?: Outbox(context.applicationContext).also { instance = it } }

        private const val SEEN_DAYS = 7L
    }

    private val writes = java.util.concurrent.atomic.AtomicInteger()

    /** Bumped on every change to the queue — what Store keys its cached count by. */
    val version: Int get() = writes.get()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE outbox (id TEXT PRIMARY KEY, body TEXT NOT NULL, fingerprint TEXT, " +
                "attempts INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE seen (fingerprint TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /**
     * Queue one SMS. False when this exact SMS (by [fingerprint]) was queued before — the receiver
     * and a recovery pass racing for the same message, or the inbox read twice.
     */
    fun enqueue(item: JSONObject, fingerprint: String?): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (fingerprint != null) {
                val fresh = db.insertWithOnConflict(
                    "seen", null,
                    ContentValues().apply { put("fingerprint", fingerprint); put("at", System.currentTimeMillis()) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (fresh == -1L) return false
            }
            db.insertOrThrow("outbox", null, ContentValues().apply {
                put("id", item.optString("id"))
                put("body", item.toString())
                put("fingerprint", fingerprint)
                put("created_at", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
            writes.incrementAndGet()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Oldest first — the order they arrived, the order they are sent. */
    fun all(): List<JSONObject> =
        readableDatabase.rawQuery("SELECT body FROM outbox ORDER BY created_at, rowid", null).use { c ->
            val out = ArrayList<JSONObject>(c.count)
            while (c.moveToNext()) runCatching { JSONObject(c.getString(0)) }.getOrNull()?.let(out::add)
            out
        }

    fun size(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun remove(id: String) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id))
        writes.incrementAndGet()
    }

    /** One more failed try, for the log and for support. */
    fun bump(id: String) {
        writableDatabase.execSQL("UPDATE outbox SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
    }

    fun seen(fingerprint: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM seen WHERE fingerprint = ?", arrayOf(fingerprint)).use { it.moveToFirst() }

    /** Remember an SMS as handled without queueing it — an OTP left on the phone, for instance. */
    fun markSeen(fingerprint: String) {
        writableDatabase.insertWithOnConflict(
            "seen", null,
            ContentValues().apply { put("fingerprint", fingerprint); put("at", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        writableDatabase.delete("seen", "at < ?", arrayOf((now - SEEN_DAYS * 86_400_000L).toString()))
    }

    /** Unpaired: what was waiting was for that shop. What was seen stays — it is still on this phone. */
    fun clear() {
        writableDatabase.delete("outbox", null, null)
        writes.incrementAndGet()
    }

    /**
     * The 1.1 queue (a JSON array in SharedPreferences), moved in once. Each SMS keeps its id, so the
     * event log rows that point at them keep moving from «در صف» to «فرستاده شد». True when every
     * SMS is now in the database — only then may the JSON go.
     */
    fun migrate(json: String?): Boolean {
        if (json.isNullOrBlank()) return true
        // Unreadable JSON is not coming back by trying again.
        val rows = runCatching { JSONArray(json) }.getOrNull() ?: return true
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            if (item.optString("id").isEmpty()) continue
            val fp = Rules.fingerprint(item.optString("from"), item.optString("message"), item.optLong("sent_at", item.optLong("received_at") * 1000))
            // Already in (a previous, interrupted migration) is fine; a database error is not.
            if (runCatching { enqueue(item, fp) || seen(fp) }.getOrDefault(false).not()) return false
        }
        return true
    }
}
