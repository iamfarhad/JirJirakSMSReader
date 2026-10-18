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
                ?: "ربات جواب نداد (" + ir.jirjirak.sms.ui.Persian.number(code) + ")",
        )
    }

    /**
     * What came of a decision that moves money or service — approve, reject, set a deposit aside.
     *
     * [Unknown] is the honest answer to a request that left the phone and got no clear reply (a
     * timeout, a dropped connection, a gateway error): the bot may well have done it. The screen then
     * reads the receipt again from the bot instead of retrying blind or showing a hopeful success.
     */
    sealed interface Decision {
        data object Done : Decision
        data object AlreadyDecided : Decision
        data object NeedKey : Decision
        data class Unknown(val message: String) : Decision
        data class Failed(val message: String) : Decision
    }

    /** What a decision's reply means. Code 0 is «nothing came back». Pure: unit-tested. */
    fun decisionOutcome(code: Int, text: String): Decision = when {
        code in 200..299 -> Decision.Done
        code == 409 -> Decision.AlreadyDecided
        code == 401 -> Decision.NeedKey
        // No reply, or a gateway / server fault after the request was sent: it may have gone through.
        code == 0 || code >= 500 -> Decision.Unknown(
            if (code == 0) text.ifBlank { "پاسخ ربات نرسید" } else "ربات خطا داد (" + ir.jirjirak.sms.ui.Persian.number(code) + ")",
        )
        else -> Decision.Failed((outcome(code, text) { } as? Result.Failed)?.message ?: "انجام نشد (" + ir.jirjirak.sms.ui.Persian.number(code) + ")")
    }

    /**
     * A pasted manage code, as the bot judged it — asked BEFORE the code is kept, so «فعال شد» is
     * said only after the bot accepted it. 401 is the code, 403 the shop's subscription, 404 the
     * pairing or an old bot: three different fixes, never one «bad key».
     */
    enum class KeyCheck { OK, WRONG, FORBIDDEN, NOT_FOUND, OFFLINE, FAILED }

    /** Pure: unit-tested. */
    fun keyCheck(code: Int): KeyCheck = when {
        code in 200..299 -> KeyCheck.OK
        code == 401 -> KeyCheck.WRONG
        code == 403 -> KeyCheck.FORBIDDEN
        code == 404 -> KeyCheck.NOT_FOUND
        code == 0 -> KeyCheck.OFFLINE
        else -> KeyCheck.FAILED
    }

    /**
     * Ask the bot whether [key] opens this shop's receipts — the plain list, the one page every bot
     * with a manage key has had since 1.1, so an older bot is not mistaken for a lost pairing.
     * Network: call off the main thread.
     */
    fun verify(context: Context, key: String): KeyCheck {
        val (code, _) = raw(context, "GET", "", null, key) ?: return KeyCheck.NOT_FOUND
        return keyCheck(code)
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

    /** One phone of the shop, as the bot last heard it. [mine] is this phone. */
    data class Device(val model: String, val at: Long, val quiet: Boolean, val senders: Int, val version: String, val mine: Boolean)

    data class Summary(
        val autoOn: Boolean,
        val levelLabel: String,
        val metrics: Metrics,
        val previous: Metrics,
        val actions: List<Action>,
        val recent: List<Activity>,
        val series: List<Day>,
        val banks: List<Bank>,
        val devices: List<Device> = emptyList(),
        /** Everything in the «مغایرت» list right now — deposits no invoice claimed and unread SMS. */
        val openMismatch: Int = 0,
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
        /** An unread bank SMS: its text and its numbers, for «آموزش قالب». Null on everything else. */
        val raw: String? = null,
        val numbers: List<Number> = emptyList(),
        /** Read by a format the seller taught. */
        val learned: Boolean = false,
    )

    /** One number in an SMS, by its place — what the seller taps as the amount or the balance. */
    data class Number(val slot: Int, val text: String)

    data class Format(val id: Long, val sender: String, val sample: String, val unit: String, val amountSlot: Int, val balanceSlot: Int?, val hits: Int, val lastHit: Long)

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
            devices = o.optJSONObject("health")?.list("devices") {
                Device(it.optString("model"), it.optLong("at") * 1000, it.optBoolean("quiet"), it.optInt("senders"), it.optString("version"), it.optBoolean("this"))
            } ?: emptyList(),
            openMismatch = o.optJSONObject("open")?.optInt("mismatch")
                ?: o.list("actions") { if (it.optString("kind") in setOf("unmatched", "unreadable")) it.optInt("count") else 0 }.sum(),
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
            raw = o.str("raw"),
            numbers = o.list("numbers") { Number(it.optInt("slot"), it.optString("text")) },
            learned = o.optBoolean("learned"),
        )
    }

    /** Parse `…/candidates`: the deposit and the receipts it could pay. Pure: unit-tested. */
    fun parseCandidates(body: String): Pair<Item, List<Item>> {
        val o = JSONObject(body)
        return item(o.optJSONObject("deposit")?.put("kind", "deposit") ?: JSONObject()) to o.list("items") { item(it) }
    }

    /** Parse `…/formats`. Pure: unit-tested. */
    fun parseFormats(body: String): List<Format> = JSONObject(body).list("formats") {
        Format(
            it.optLong("id"), it.optString("sender"), it.optString("sample"), it.optString("unit"), it.optInt("amount_slot"),
            if (it.isNull("balance_slot")) null else it.optInt("balance_slot"), it.optInt("hits"), it.optLong("last_hit_at") * 1000,
        )
    }

    /** «اتصال به رسید»: done, or the amounts differ (ask again), or refused with the bot's reason. */
    sealed interface Assign {
        data object Done : Assign
        data class AmountDiffers(val deposit: Long, val expected: Long) : Assign
        data class Refused(val message: String) : Assign
        data object NeedKey : Assign
        /** No clear reply: the bot may have tied it. Read the deposit again before anything else. */
        data class Unknown(val message: String) : Assign
        /** 409: the deposit or the receipt was settled already — in the bot, or on another phone. */
        data object AlreadyDecided : Assign
    }

    /** What an assign reply means. Pure: unit-tested. */
    fun assignOutcome(code: Int, text: String): Assign {
        val j = runCatching { JSONObject(text) }.getOrNull()
        return when {
            code in 200..299 -> Assign.Done
            code == 401 -> Assign.NeedKey
            code == 409 -> Assign.AlreadyDecided
            code == 0 || code >= 500 -> Assign.Unknown(if (code == 0) text.ifBlank { "پاسخ ربات نرسید" } else "ربات خطا داد (" + ir.jirjirak.sms.ui.Persian.number(code) + ")")
            code == 422 && j?.optString("error") == "amount_differs" -> Assign.AmountDiffers(j.optLong("deposit_amount"), j.optLong("expected"))
            else -> Assign.Refused(
                j?.optString("message")?.takeIf { m -> m.any { it in '\u0600'..'\u06FF' } }
                    ?: (outcome(code, text) { } as? Result.Failed)?.message ?: "انجام نشد (" + ir.jirjirak.sms.ui.Persian.number(code) + ")",
            )
        }
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

    fun candidates(context: Context, deposit: Long): Result<Pair<Item, List<Item>>> =
        call(context, "GET", "/candidates?deposit=$deposit") { parseCandidates(it) }

    fun assign(context: Context, payment: Long, deposit: Long, acceptDifference: Boolean): Assign {
        val (code, text) = raw(context, "POST", "/$payment/assign", JSONObject().put("deposit", deposit).put("accept_difference", acceptDifference))
            ?: return Assign.NeedKey
        return assignOutcome(code, text)
    }

    fun ignore(context: Context, deposit: Long): Decision = decide(context, "/deposits/$deposit/ignore", null)

    fun formats(context: Context): Result<List<Format>> = call(context, "GET", "/formats") { parseFormats(it) }

    /** Teach a format; the answer is how many recent unread SMS it read again. */
    fun teach(context: Context, deposit: Long, amountSlot: Int, balanceSlot: Int?, unit: String): Result<Int> =
        call(context, "POST", "/formats", JSONObject().put("deposit", deposit).put("amount_slot", amountSlot)
            .put("balance_slot", balanceSlot ?: JSONObject.NULL).put("unit", unit)) { JSONObject(it).optInt("reread") }

    fun forgetFormat(context: Context, id: Long): Result<Unit> = call(context, "DELETE", "/formats/$id") { }

    fun approve(context: Context, id: Long): Decision = decide(context, "/$id/approve", null)

    fun reject(context: Context, id: Long, reason: String): Decision =
        decide(context, "/$id/reject", JSONObject().put("reason", reason))

    private fun decide(context: Context, path: String, body: JSONObject?): Decision {
        val (code, text) = raw(context, "POST", path, body) ?: return Decision.NeedKey
        return decisionOutcome(code, text)
    }

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

    private fun target(context: Context, candidate: String? = null): Pair<String, String>? {
        val store = Store(context)
        val endpoint = store.endpoint ?: return null
        val key = candidate ?: store.manageKey ?: return null
        return "$endpoint/receipts" to key
    }

    private fun <T> call(context: Context, method: String, path: String, body: JSONObject? = null, parse: (String) -> T): Result<T> {
        val (code, text) = raw(context, method, path, body) ?: return Result.NeedKey
        if (code == 0) return Result.Failed(text)
        return runCatching { outcome(code, text, parse) }.getOrElse { Result.Failed("پاسخ ربات خوانده نشد.") }
    }

    /** The status and body; code 0 with the reason when nothing came back; null without a key. */
    private fun raw(context: Context, method: String, path: String, body: JSONObject?, candidate: String? = null): Pair<Int, String>? {
        val (base, key) = target(context, candidate) ?: return null
        return try {
            val con = URL(base + path).openConnection() as HttpURLConnection
            try {
                con.connectTimeout = 15_000
                con.readTimeout = 30_000
                con.requestMethod = method
                con.setRequestProperty("Accept", "application/json")
                con.setRequestProperty("X-Manage-Key", key)
                con.setRequestProperty("X-Device", Store(context).deviceId)
                con.setRequestProperty("User-Agent", "jirjirak-sms/" + BuildConfig.VERSION_NAME)
                if (body != null) {
                    con.doOutput = true
                    con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = con.responseCode
                val text = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                code to text
            } finally {
                con.disconnect()
            }
        } catch (e: Exception) {
            0 to Net.describe(e)
        }
    }
}
