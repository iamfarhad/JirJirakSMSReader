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

    /** POST one JSON document; the response body, or null on any failure. */
    fun post(url: String, body: JSONObject): String? = try {
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
            if (code in 200..299) text else null
        } finally {
            con.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** Send what is queued, oldest first, stopping at the first failure so the order holds. */
    @Synchronized
    fun flush(context: Context): Int {
        val store = Store(context)
        val endpoint = store.endpoint ?: return 0
        var sent = 0
        for (item in store.queue()) {
            if (post(endpoint, item) == null) {
                store.lastError = "send"
                break
            }
            sent++
            store.lastSent = System.currentTimeMillis()
            store.lastError = null
            store.markDelivered(item.optString("id"))
        }
        if (sent > 0) store.dropFirst(sent)
        return sent
    }

    /**
     * «I am here», with the list of allowed senders — so the bot can show the seller exactly which
     * numbers this phone forwards, and say when the phone has gone quiet. Answers with the shop's
     * name, which is how the app shows WHICH shop it is paired with.
     */
    fun ping(context: Context): Boolean {
        val store = Store(context)
        val endpoint = store.endpoint ?: return false
        val body = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("pending", store.queue().size)
            .put("senders", JSONArray(store.senders))
            .put("model", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.SDK_INT)
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.let {
            body.put("battery", it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        }
        val res = post("$endpoint/ping", body) ?: run {
            store.lastError = "ping"
            return false
        }
        runCatching { JSONObject(res).optString("shop") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { store.shop = it }
        store.lastPing = System.currentTimeMillis()
        store.lastError = null
        return true
    }
}
