package ir.jirjirak.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The two calls the app makes: deliver an SMS, and say «I am here». HttpURLConnection, no
 * library — the app has one job and should not need an update because a dependency did.
 */
object Net {

    /** What came back: the status (0 when nothing did), the body, and why when nothing did. */
    data class Reply(val code: Int, val body: String, val error: String? = null) {
        val ok get() = code in 200..299

        /** The secret is no longer this shop's: the seller changed the address, or the shop is gone. */
        val revoked get() = code == 404 || code == 403

        /**
         * Worth trying again later? No answer, a server fault or a throttle: yes. Any other 4xx is
         * the bot refusing THIS message, and an item the bot will never accept must not sit at the
         * head of the queue blocking every SMS behind it — the first version stopped at the first
         * failure of any kind, and one 413 would have held the whole queue forever.
         */
        val retryable get() = code == 0 || code == 429 || code >= 500

        /** The reason in the seller's words, for the event log. */
        val reason: String
            get() = when {
                error != null -> error
                code == 429 -> "ربات گفت آهسته‌تر (محدودیت تعداد درخواست)"
                code >= 500 -> "خطای سرور ربات ($code)"
                revoked -> "ربات این گوشی را نمی‌شناسد (کد اتصال باطل شده)"
                code >= 400 -> "ربات این پیامک را نپذیرفت ($code)"
                else -> "پاسخ $code"
            }
    }

    /** POST one JSON document. */
    fun post(url: String, body: JSONObject): Reply = try {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.requestMethod = "POST"
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("Accept", "application/json")
            con.setRequestProperty("User-Agent", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            Reply(code, text)
        } finally {
            con.disconnect()
        }
    } catch (e: Exception) {
        Reply(0, "", describe(e))
    }

    /** A network exception as the seller would say it. */
    fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "اینترنت ندارد، یا آدرس ربات پیدا نشد"
        is SocketTimeoutException -> "ربات دیر جواب داد (اینترنت کند یا سرور شلوغ)"
        is SSLException -> "گواهی امن (SSL) سرور ربات مشکل دارد"
        is ConnectException -> "به سرور ربات وصل نشد"
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    /**
     * Send what is queued, oldest first. A temporary failure stops the run so the order holds; a
     * refusal drops that one item and goes on.
     */
    @Synchronized
    fun flush(context: Context): Int {
        val store = Store(context)
        val endpoint = store.endpoint ?: return 0
        var sent = 0
        for (item in store.queue()) {
            val id = item.optString("id")
            val reply = post(endpoint, item)
            if (!reply.ok) {
                store.lastError = if (reply.revoked) "revoked" else "send"
                if (reply.retryable) {
                    store.updateEvent(id, "retry", "فرستاده نشد: " + reply.reason + " — دوباره تلاش می‌شود")
                    break
                }
                if (reply.revoked) {
                    store.updateEvent(id, "retry", reply.reason)
                    break
                }
                store.remove(id)
                store.updateEvent(id, "failed", reply.reason)
                continue
            }
            // Off the queue the moment it is delivered: a kill half-way through re-sends nothing
            // already sent (the bot also ignores a repeat of the same SMS on the same day).
            store.remove(id)
            store.updateEvent(id, "sent", verdict = verdict(reply.body))
            store.lastSent = System.currentTimeMillis()
            store.lastError = null
            sent++
        }
        return sent
    }

    /**
     * The bot's word on a delivered SMS, from its reply — so the log can say «matched the invoice
     * and the order was approved», not just «sent». Pure: unit-tested.
     */
    fun verdict(body: String): String {
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return "رسید"
        val amount = j.optLong("amount", 0).takeIf { it > 0 }?.let { " · " + Money.toman(it) } ?: ""
        return when {
            j.optBoolean("duplicate") -> "تکراری — همین پیامک قبلاً رسیده بود"
            j.has("parsed") && !j.optBoolean("parsed") -> "ربات نتوانست مبلغ واریز را از این پیامک بخواند"
            j.optBoolean("approved") -> "با فاکتور تطبیق داده شد و سفارش تأیید شد ✅$amount"
            j.optString("status") == "matched" -> "با فاکتور تطبیق داده شد — در انتظار بررسی مدیر$amount"
            j.optString("status") == "unmatched" -> "واریز ثبت شد؛ فاکتوری با این مبلغ پیدا نشد$amount"
            else -> "رسید$amount"
        }
    }

    /**
     * «I am here», with the list of allowed senders — so the bot can show the seller exactly which
     * numbers this phone forwards, and say when the phone has gone quiet. Answers with the shop's
     * name, which is how the app shows WHICH shop it is paired with.
     */
    fun ping(context: Context): Boolean {
        val endpoint = Store(context).endpoint ?: return false
        return ping(context, endpoint) == Ping.OK
    }

    enum class Ping { OK, REVOKED, UNREACHABLE }

    /** The reason the last ping failed, for the screen. */
    @Volatile var lastPingReason: String? = null

    /**
     * Ping `endpoint` — the paired one, or a CANDIDATE while pairing, which is saved only once this
     * succeeds (saving it first let the screen switch to «paired» mid-request and cancel the pairing).
     */
    fun ping(context: Context, endpoint: String): Ping {
        val store = Store(context)
        val body = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("pending", store.queue().size)
            .put("senders", JSONArray(store.senders))
            .put("model", Build.MANUFACTURER + " " + Build.MODEL)
            // Can this phone actually hear an SMS? Paired and pinging is not the same as receiving:
            // Android 15 silently refuses the SMS permission to a sideloaded app until «Allow
            // restricted settings», and the bot should say so instead of looking connected.
            .put("sms_permission", ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
            .put("android", Build.VERSION.SDK_INT)
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.let {
            body.put("battery", it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        }
        val reply = post("$endpoint/ping", body)
        val paired = store.endpoint == endpoint
        if (!reply.ok) {
            lastPingReason = reply.reason
            if (paired) {
                // Once per outage, not once per beat: the log is for reading, not for scrolling.
                if (store.lastError == null) {
                    store.event("net", "warn", if (reply.revoked) "کد اتصال باطل شده" else "ارتباط با ربات قطع شد", reply.reason)
                }
                store.lastError = if (reply.revoked) "revoked" else "ping"
            }
            return if (reply.revoked) Ping.REVOKED else Ping.UNREACHABLE
        }
        lastPingReason = null
        val info = runCatching { JSONObject(reply.body) }.getOrNull() ?: JSONObject()
        info.optString("shop").takeIf { it.isNotEmpty() }?.let { store.shop = it }
        if (paired) {
            if (store.lastError != null) store.event("net", "ok", "ارتباط با ربات برقرار شد")
            store.shopInfo = info
            store.lastPing = System.currentTimeMillis()
            store.lastError = null
        }
        return Ping.OK
    }
}

/** Amounts as the seller reads them: «۱۷۵٬۰۱۳ تومان». */
object Money {
    fun toman(amount: Long): String {
        val s = amount.toString().reversed().chunked(3).joinToString("٬").reversed()
        return s.map { if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it }.joinToString("") + " تومان"
    }
}
