package ir.jirjirak.sms

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The two calls the app makes: deliver an SMS, and say «I am here». HttpURLConnection, no
 * library — the app has one job and should not need an update because a dependency did.
 */
object Net {

    /** What came back: the status (0 when nothing did) and the body. */
    data class Reply(val code: Int, val body: String) {
        val ok get() = code in 200..299

        /** The secret is no longer this shop's: the seller changed the address, or the shop is gone. */
        val revoked get() = code == 404 || code == 403
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
        Reply(0, "")
    }

    /** Send what is queued, oldest first, stopping at the first failure so the order holds. */
    @Synchronized
    fun flush(context: Context): Int {
        val store = Store(context)
        val endpoint = store.endpoint ?: return 0
        var sent = 0
        for (item in store.queue()) {
            val reply = post(endpoint, item)
            if (!reply.ok) {
                store.lastError = if (reply.revoked) "revoked" else "send"
                break
            }
            // Off the queue the moment it is delivered: a kill half-way through re-sends nothing
            // already sent (the bot also ignores a repeat of the same SMS on the same day).
            store.remove(item.optString("id"))
            store.markDelivered(item.optString("id"))
            store.lastSent = System.currentTimeMillis()
            store.lastError = null
            sent++
        }
        return sent
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
            .put("android", Build.VERSION.SDK_INT)
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.let {
            body.put("battery", it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        }
        val reply = post("$endpoint/ping", body)
        if (!reply.ok) {
            if (store.endpoint == endpoint) store.lastError = if (reply.revoked) "revoked" else "ping"
            return if (reply.revoked) Ping.REVOKED else Ping.UNREACHABLE
        }
        runCatching { JSONObject(reply.body).optString("shop") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { store.shop = it }
        store.lastPing = System.currentTimeMillis()
        store.lastError = null
        return Ping.OK
    }
}
