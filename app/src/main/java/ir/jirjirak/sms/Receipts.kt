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

    // ── the dashboard, the receipts centre, a receipt's detail ──────────

    data class Metrics(
        val autoApproved: Int = 0,
        val manualApproved: Int = 0,
        val rejected: Int = 0,
        val needsReview: Int = 0,
        val depositsCount: Int = 0,
        val depositsTotal: Long = 0,
        val unmatched: Int = 0,
        val autoRate: Int = 0,
        val avgAutoSeconds: Int = 0,
    )

    data class Action(val kind: String, val count: Int, val title: String, val hint: String)

    data class Activity(val kind: String, val at: Long, val amount: Long, val customer: String?, val sender: String?, val id: Long)

    data class Day(val date: String, val auto: Int, val manual: Int, val total: Long)

    data class Bank(val sender: String, val count: Int, val total: Long)

    data class Summary(
        val autoOn: Boolean,
        val levelLabel: String,
        val metrics: Metrics,
        val previous: Metrics,
        val actions: List<Action>,
        val recent: List<Activity>,
        val series: List<Day>,
        val banks: List<Bank>,
    )

    /** One row of the receipts centre: a receipt (a payment) or, under «مغایرت», a bank deposit. */
    data class Item(
        val id: Long,
        val isDeposit: Boolean,
        val state: String,
        val via: String?,
        val amount: Long?,
        val customer: String?,
        val title: String?,
        val tracking: String?,
        val rail: String?,
        val sender: String?,
        val at: Long,
        val reason: String?,
        val score: Int?,
        val threshold: Int?,
        val hasImage: Boolean,
        val note: String?,
    )

    data class Page(val items: List<Item>, val hasMore: Boolean)

    data class Signal(val label: String, val ok: Boolean, val weight: Int, val earned: Int)

    data class Detail(
        val item: Item,
        val deposit: Item?,
        val score: Int?,
        val threshold: Int?,
        val enough: Boolean,
        val signals: List<Signal>,
        val why: List<String>,
        val suggestionTone: String,
        val suggestionTitle: String,
        val suggestionText: String?,
        val timeline: List<Pair<Long, Pair<String, String>>>,
    )

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" && !isNull(key) }

    private fun metrics(o: JSONObject?): Metrics = if (o == null) Metrics() else Metrics(
        autoApproved = o.optInt("auto_approved"), manualApproved = o.optInt("manual_approved"), rejected = o.optInt("rejected"),
        needsReview = o.optInt("needs_review"), depositsCount = o.optInt("deposits_count"), depositsTotal = o.optLong("deposits_total"),
        unmatched = o.optInt("unmatched"), autoRate = o.optInt("auto_rate"), avgAutoSeconds = o.optInt("avg_auto_seconds"),
    )

    private fun <T> JSONObject.list(key: String, map: (JSONObject) -> T): List<T> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(map)
    }

    /** Parse `…/receipts/summary`. Pure: unit-tested. */
    fun parseSummary(body: String): Summary {
        val o = JSONObject(body)
        return Summary(
            autoOn = o.optBoolean("auto_on"),
            levelLabel = o.optJSONObject("level")?.str("label") ?: "",
            metrics = metrics(o.optJSONObject("metrics")),
            previous = metrics(o.optJSONObject("previous")),
            actions = o.list("actions") { Action(it.optString("kind"), it.optInt("count"), it.optString("title"), it.optString("hint")) },
            recent = o.list("recent") { Activity(it.optString("kind"), it.optLong("at") * 1000, it.optLong("amount"), it.str("customer"), it.str("sender"), it.optLong("id")) },
            series = o.list("series") { Day(it.optString("date"), it.optInt("auto"), it.optInt("manual"), it.optLong("total")) },
            banks = o.list("banks") { Bank(it.optString("sender"), it.optInt("count"), it.optLong("total")) },
        )
    }

    private fun item(o: JSONObject): Item {
        val deposit = o.optString("kind") == "deposit"
        val auto = o.optJSONObject("auto")
        return Item(
            id = o.optLong("id"),
            isDeposit = deposit,
            state = o.optString("state"),
            via = o.str("via"),
            amount = if (o.isNull("amount")) null else o.optLong("amount"),
            customer = o.str("customer"),
            title = o.str("title"),
            tracking = o.str("tracking"),
            rail = o.str("rail"),
            sender = o.str("sender"),
            at = (if (deposit) o.optLong("created_at") else o.optLong("created_at")) * 1000,
            reason = o.str("reason") ?: o.str("note"),
            score = auto?.optInt("score"),
            threshold = auto?.optInt("threshold"),
            hasImage = o.optBoolean("has_image"),
            note = o.str("note"),
        )
    }

    /** Parse `…/receipts/history`. Pure: unit-tested. */
    fun parsePage(body: String): Page {
        val o = JSONObject(body)
        return Page(o.list("items") { item(it) }, o.optBoolean("has_more"))
    }

    /** Parse `…/receipts/{id}`. Pure: unit-tested. */
    fun parseDetail(body: String): Detail {
        val o = JSONObject(body)
        val a = o.optJSONObject("analysis")
        val sug = o.optJSONObject("suggestion")
        return Detail(
            item = item(o.optJSONObject("receipt") ?: JSONObject()),
            deposit = o.optJSONObject("deposit")?.let { item(it.put("kind", "deposit")) },
            score = a?.optInt("score"),
            threshold = a?.optInt("threshold"),
            enough = a?.optBoolean("enough") ?: false,
            signals = a?.list("signals") { Signal(it.optString("label"), it.optBoolean("ok"), it.optInt("weight"), it.optInt("earned")) } ?: emptyList(),
            why = a?.optJSONArray("why")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            suggestionTone = sug?.optString("tone") ?: "info",
            suggestionTitle = sug?.optString("title") ?: "",
            suggestionText = sug?.str("text"),
            timeline = o.list("timeline") { (it.optLong("at") * 1000) to (it.optString("title") to it.optString("tone")) },
        )
    }

    fun summary(context: Context, range: String = "today"): Result<Summary> = call(context, "GET", "/summary?range=$range") { parseSummary(it) }

    fun history(context: Context, filter: String, page: Int): Result<Page> = call(context, "GET", "/history?filter=$filter&page=$page") { parsePage(it) }

    fun detail(context: Context, id: Long): Result<Detail> = call(context, "GET", "/$id") { parseDetail(it) }

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
