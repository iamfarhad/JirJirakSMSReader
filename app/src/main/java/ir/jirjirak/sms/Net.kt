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
 * The calls the app makes: deliver an SMS to the bot, say «I am here», and post an SMS to the
 * seller's own webhooks. HttpURLConnection, no library — the app has one job and should not need
 * an update because a dependency did.
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
    fun post(url: String, body: JSONObject): Reply =
        send(url, "application/json; charset=utf-8", body.toString(), "ربات")

    /** POST a form (`a=1&b=2`, already encoded) — what a webhook expects. */
    fun postForm(url: String, form: String): Reply =
        send(url, "application/x-www-form-urlencoded; charset=utf-8", form, "وب‌هوک")

    private fun send(url: String, type: String, body: String, who: String): Reply = try {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.requestMethod = "POST"
            con.doOutput = true
            con.setRequestProperty("Content-Type", type)
            con.setRequestProperty("Accept", "application/json")
            con.setRequestProperty("User-Agent", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
            con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            Reply(code, text)
        } finally {
            con.disconnect()
        }
    } catch (e: Exception) {
        Reply(0, "", describe(e, who))
    }

    /** A network exception as the seller would say it, about [who] — the bot or a webhook. */
    fun describe(e: Throwable, who: String = "ربات"): String = when (e) {
        is UnknownHostException -> "اینترنت ندارد، یا آدرس $who پیدا نشد"
        is SocketTimeoutException -> "$who دیر جواب داد (اینترنت کند یا سرور شلوغ)"
        is SSLException -> "گواهی امن (SSL) سرور $who مشکل دارد"
        is ConnectException -> "به سرور $who وصل نشد"
        // The line dropped mid-request (an EOF, a reset): the server may or may not have got it.
        is java.io.IOException -> "ارتباط با $who وسط کار قطع شد"
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    /**
     * Why a webhook did not take an SMS, in the seller's words. Only the status means anything: the
     * reply is a stranger's, and a 404 there is a wrong address, not a revoked pairing. Pure.
     */
    fun hookReason(reply: Reply): String = when {
        reply.error != null -> reply.error
        reply.code == 429 -> "سرور وب‌هوک گفت آهسته‌تر (429)"
        reply.code >= 500 -> "خطای سرور وب‌هوک (" + reply.code + ")"
        reply.code == 404 -> "آدرس وب‌هوک پیدا نشد (404) — آدرس را بررسی کنید"
        reply.code == 401 || reply.code == 403 -> "وب‌هوک اجازه نداد (" + reply.code + ") — آدرس یا شناسهٔ حساب را بررسی کنید"
        else -> "وب‌هوک پیامک را نپذیرفت (" + reply.code + ")"
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
                    store.bump(id)
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
            approval(reply.body)?.let { (amount, customer) -> Alerts.approved(context, amount, customer) }
        }
        // After the bot, never instead of it: a stop above (no internet, the bot down) is no reason to
        // keep a webhook waiting, and a webhook down is no reason to hold the bot.
        runCatching { flushHooks(store) }
        StatusWidget.refresh(context)
        return sent
    }

    /**
     * Each webhook's queue on its own, oldest first. The same order rule as the bot's: a temporary
     * failure stops THAT webhook's run, a refusal drops the one SMS. Returns how many went.
     */
    fun flushHooks(store: Store): Int {
        var sent = 0
        // The feature switched off in «بیشتر»: what is queued waits, nothing goes.
        for (hook in store.activeHooks) {
            // Switched off: what is queued waits for it to be switched on again.
            if (!hook.on) continue
            for (item in store.hookQueue(hook.id)) {
                val id = item.optString("id")
                val reply = postForm(hook.url, Hooks.form(item.optString("message"), item.optString("from")))
                if (reply.ok) {
                    store.remove(id)
                    store.updateEvent(id, "sent", "به وب‌هوک رسید")
                    store.hookResult(hook.id, true, null)
                    sent++
                    continue
                }
                val reason = hookReason(reply)
                store.hookResult(hook.id, false, reason)
                if (reply.retryable) {
                    store.bump(id)
                    store.updateEvent(id, "retry", "فرستاده نشد: $reason — دوباره تلاش می‌شود")
                    break
                }
                store.remove(id)
                store.updateEvent(id, "failed", reason)
            }
        }
        return sent
    }

    /** Amount and customer when the bot's reply says the order was approved; null otherwise. Pure. */
    fun approval(body: String): Pair<Long, String?>? {
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!j.optBoolean("approved")) return null
        return j.optLong("amount", 0) to j.optString("customer").takeIf { it.isNotBlank() && it != "null" }
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
            .put("pending", store.queueSize())
            // Which phone this is, when one shop has several (one per bank SIM).
            .put("device", store.deviceId)
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
            StatusWidget.refresh(context)
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
