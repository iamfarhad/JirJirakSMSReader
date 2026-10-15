package ir.jirjirak.sms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * «رسیدها»: the receipts waiting for the seller, fetched from the bot and decided from here.
 *
 * Deciding needs the bot's manage key (`X-Manage-Key`), pasted once from the bot's «🔑 کپی کد
 * مدیریت رسیدها». The pairing alone only lets this phone REPORT money; giving service away or
 * turning a customer down is a different power, and a new address in the bot revokes both.
 */
object Receipts {

    private val CODE = Regex("""^jirjirak-manage:([A-Za-z0-9]{20,80})$""")

    /** The key inside a «jirjirak-manage:» code, or null for anything else. Pure: unit-tested. */
    fun parseCode(raw: String?): String? = raw?.trim()?.let { CODE.matchEntire(it)?.groupValues?.get(1) }

    data class Receipt(
        val id: Long,
        val amount: Long,
        val rail: String,
        val customer: String?,
        val username: String?,
        val title: String?,
        val tracking: String?,
        val createdAt: Long,
        val hasImage: Boolean,
        val score: Int?,
        val threshold: Int?,
        val why: List<String>,
    )

    data class Reason(val code: String, val label: String)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        /** The key is wrong or was revoked: the seller has to paste a fresh one. */
        data object NeedKey : Result<Nothing>
        data class Failed(val message: String) : Result<Nothing>
    }

    /** Parse the list endpoint's body. Pure: unit-tested. */
    fun parseList(body: String): List<Receipt> {
        val arr = JSONObject(body).optJSONArray("receipts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val auto = o.optJSONObject("auto")
            val why = auto?.optJSONArray("why")
            Receipt(
                id = o.optLong("id"),
                amount = o.optLong("amount"),
                rail = o.optString("rail"),
                customer = o.optString("customer").takeIf { it.isNotBlank() && it != "null" },
                username = o.optString("username").takeIf { it.isNotBlank() && it != "null" },
                title = o.optString("title").takeIf { it.isNotBlank() && it != "null" },
                tracking = o.optString("tracking").takeIf { it.isNotBlank() && it != "null" },
                createdAt = o.optLong("created_at") * 1000,
                hasImage = o.optBoolean("has_image"),
                score = auto?.optInt("score"),
                threshold = auto?.optInt("threshold"),
                why = (0 until (why?.length() ?: 0)).map { why!!.optString(it) },
            )
        }
    }

    fun list(context: Context): Result<List<Receipt>> = call(context, "GET", "") { parseList(it) }

    fun reasons(context: Context): Result<List<Reason>> = call(context, "GET", "/reasons") { body ->
        val arr = JSONObject(body).optJSONArray("reasons")
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.map { Reason(it.optString("code"), it.optString("label")) }
    }

    fun approve(context: Context, id: Long): Result<Unit> = call(context, "POST", "/$id/approve") { }

    fun reject(context: Context, id: Long, reason: String): Result<Unit> =
        call(context, "POST", "/$id/reject", JSONObject().put("reason", reason)) { }

    fun image(context: Context, id: Long): Bitmap? {
        val (base, key) = target(context) ?: return null
        return runCatching {
            val con = URL("$base/$id/image").openConnection() as HttpURLConnection
            try {
                con.connectTimeout = 15_000
                con.readTimeout = 30_000
                con.setRequestProperty("X-Manage-Key", key)
                if (con.responseCode != 200) return null
                val bytes = con.inputStream.use { it.readBytes() }
                // A phone photo of a receipt can be 4000px tall; a card on screen needs a tenth of it.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 2000) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                con.disconnect()
            }
        }.getOrNull()
    }

    private fun target(context: Context): Pair<String, String>? {
        val store = Store(context)
        val endpoint = store.endpoint ?: return null
        val key = store.manageKey ?: return null
        return "$endpoint/receipts" to key
    }

    private fun <T> call(context: Context, method: String, path: String, body: JSONObject? = null, parse: (String) -> T): Result<T> {
        val (base, key) = target(context) ?: return Result.NeedKey
        return try {
            val con = URL(base + path).openConnection() as HttpURLConnection
            try {
                con.connectTimeout = 15_000
                con.readTimeout = 30_000
                con.requestMethod = method
                con.setRequestProperty("Accept", "application/json")
                con.setRequestProperty("X-Manage-Key", key)
                con.setRequestProperty("User-Agent", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
                if (body != null) {
                    con.doOutput = true
                    con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = con.responseCode
                val text = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                when {
                    code in 200..299 -> Result.Ok(parse(text))
                    code == 401 || code == 404 -> Result.NeedKey
                    else -> Result.Failed(
                        runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
                            ?: "ربات جواب نداد ($code)",
                    )
                }
            } finally {
                con.disconnect()
            }
        } catch (e: Exception) {
            Result.Failed(Net.describe(e))
        }
    }
}
