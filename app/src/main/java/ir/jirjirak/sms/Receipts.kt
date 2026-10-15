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
        /** Somebody — the bot, another admin — decided this receipt first. */
        data object AlreadyDecided : Result<Nothing>
        data class Failed(val message: String) : Result<Nothing>
    }

    /**
     * What a reply means, in the seller's words. Pure: unit-tested.
     *
     * Only 401 is «paste the key again». 404 is the PAIRING the bot no longer knows (the address
     * was changed) or a bot too old to have this page — a new manage key fixes neither, and mapping
     * it to NeedKey sent sellers round a loop. Laravel's own English («Too Many Attempts.», «Tenant
     * not active») never reaches the screen; the bot's Persian messages do.
     */
    fun <T> outcome(code: Int, text: String, parse: (String) -> T): Result<T> = when {
        code in 200..299 -> Result.Ok(parse(text))
        code == 401 -> Result.NeedKey
        code == 409 -> Result.AlreadyDecided
        code == 404 -> Result.Failed("ربات این گوشی را نمی‌شناسد (آدرس عوض شده) یا نسخهٔ ربات قدیمی است. اتصال را از «بیشتر» قطع و با کد تازه دوباره وصل کنید.")
        code == 403 -> Result.Failed("اشتراک فروشگاه فعال نیست.")
        code == 429 -> Result.Failed("درخواست‌ها زیاد بود؛ یک دقیقهٔ دیگر دوباره امتحان کنید.")
        else -> Result.Failed(
            runCatching { JSONObject(text).optString("message") }.getOrNull()
                ?.takeIf { m -> m.any { it in '\u0600'..'\u06FF' } }
                ?: "ربات جواب نداد ($code)",
        )
    }

    /** Halve until the photo fits a phone screen — a receipt photo can be 4000px tall. Pure. */
    fun sampleSize(width: Int, height: Int, maxWidth: Int = 1080, maxHeight: Int = 1920): Int {
        var sample = 1
        while (width / sample > maxWidth || height / sample > maxHeight) sample *= 2
        return sample
    }

    /** Parse the reasons endpoint's body. Pure: unit-tested. */
    fun parseReasons(body: String): List<Reason> {
        val arr = JSONObject(body).optJSONArray("reasons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Reason(it.optString("code"), it.optString("label")) }
            .filter { it.code.isNotBlank() }
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

    fun reasons(context: Context): Result<List<Reason>> = call(context, "GET", "/reasons") { parseReasons(it) }

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
                val sample = sampleSize(bounds.outWidth, bounds.outHeight)
                // RGB_565: half the memory of ARGB for a photo that has no transparency.
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
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
                outcome(code, text, parse)
            } finally {
                con.disconnect()
            }
        } catch (e: Exception) {
            Result.Failed(Net.describe(e))
        }
    }
}
