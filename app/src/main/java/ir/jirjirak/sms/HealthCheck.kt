package ir.jirjirak.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * «تست سلامت»: the road one real bank SMS takes, walked end to end with nothing recorded — the
 * phone's permissions, the queue, the bot's address and secret, the sender gate, the parser and
 * whether an open invoice would claim it. The bot answers a `dry_run` post without storing a
 * deposit (BankSmsController::dryRun).
 *
 * The sample is the newest real SMS from an allowed sender still in the event log, so the parser is
 * tested on this shop's own bank; without one, a minimal deposit text.
 */
object HealthCheck {

    enum class State { OK, WARN, BAD, INFO }

    /** One line of the result. [key] marks the steps a summary depends on: «read», «dry_run_missing». */
    data class Step(val title: String, val state: State, val detail: String, val key: String = "")

    /** What the phone knows before asking the bot. */
    data class Local(
        val paired: Boolean,
        val smsPermission: Boolean,
        val batteryFree: Boolean,
        val senders: Int,
        val queued: Int,
        val recoveryOn: Boolean,
        val readSms: Boolean,
        /** False in a copy that cannot read the inbox at all (Bazaar): the step is left out. */
        val recoveryAvailable: Boolean = true,
    )

    /** The bot's answer: null when it could not be reached; [code] is the HTTP status. */
    data class Remote(val code: Int, val reason: String?, val body: JSONObject?)

    /** Every step and its verdict, in the order a seller would fix them. Pure: unit-tested. */
    fun steps(local: Local, remote: Remote?, sampleIsReal: Boolean): List<Step> {
        val out = mutableListOf<Step>()
        out += if (local.smsPermission) Step("اجازهٔ دریافت پیامک", State.OK, "برنامه پیامک‌های تازه را می‌شنود.")
        else Step("اجازهٔ دریافت پیامک", State.BAD, "بدون این اجازه هیچ پیامکی به ربات نمی‌رسد. از «خانه» اجازه را بدهید.")
        out += when {
            local.senders == 0 -> Step("شماره‌های مجاز", State.BAD, "هیچ شمارهٔ بانکی مجاز نشده؛ تا اضافه نشود چیزی فرستاده نمی‌شود.")
            else -> Step("شماره‌های مجاز", State.OK, ir.jirjirak.sms.ui.Persian.number(local.senders) + " شمارهٔ بانک مجاز است.")
        }
        out += if (local.batteryFree) Step("بهینه‌سازی باتری", State.OK, "اندروید برنامه را برای صرفه‌جویی نمی‌خواباند.")
        else Step("بهینه‌سازی باتری", State.WARN, "ممکن است اندروید برنامه را بخواباند و پیامک دیر برسد. «آزاد کردن باتری» را بزنید.")
        out += when {
            local.queued == 0 -> Step("صف گوشی", State.OK, "پیامکی منتظر ارسال نیست.")
            else -> Step("صف گوشی", State.WARN, ir.jirjirak.sms.ui.Persian.number(local.queued) + " پیامک منتظر اینترنت است.")
        }
        if (local.recoveryAvailable) out += when {
            local.recoveryOn && local.readSms -> Step("بازیابی پیامک‌های جامانده", State.OK, "پیامکی که در نبودِ برنامه برسد، از صندوق پیامک پیدا و فرستاده می‌شود.")
            local.recoveryOn -> Step("بازیابی پیامک‌های جامانده", State.WARN, "روشن است ولی اجازهٔ خواندن پیامک داده نشده.")
            else -> Step("بازیابی پیامک‌های جامانده", State.INFO, "خاموش است. اگر گوشی گاهی برنامه را می‌بندد، از «بیشتر» روشنش کنید.")
        }
        if (!local.paired) {
            out += Step("اتصال به ربات", State.BAD, "گوشی به فروشگاهی وصل نیست.")
            return out
        }
        if (remote == null || remote.code == 0) {
            out += Step("اتصال به ربات", State.BAD, remote?.reason ?: "ربات جواب نداد. اینترنت گوشی را بررسی کنید.")
            return out
        }
        if (remote.code == 404 || remote.code == 403) {
            out += Step("اتصال به ربات", State.BAD, "ربات این گوشی را نمی‌شناسد (کد اتصال باطل شده). کد تازه را از ربات بچسبانید.")
            return out
        }
        if (remote.code !in 200..299) {
            out += Step("اتصال به ربات", State.BAD, "ربات خطا داد (" + remote.code + ").")
            return out
        }
        out += Step("اتصال به ربات", State.OK, "آدرس و کد اتصال معتبر است.")
        if (remote.body == null) {
            // Connected, but the bot has no dry run yet: the SMS test is skipped, not faked.
            out += Step("آزمون خواندن پیامک", State.INFO, "اجرا نشد: این نسخهٔ ربات آزمون بدون ثبت را اعلام نکرده، و نمونه‌ای به مسیر واقعی فرستاده نشد. بعد از به‌روزرسانی ربات فعال می‌شود.", key = "dry_run_missing")
            return out
        }
        val b = remote.body
        out += if (b.optBoolean("sender_allowed", true)) Step("فرستندهٔ پیامک در ربات", State.OK, "ربات پیامک این شماره را می‌پذیرد.")
        else Step("فرستندهٔ پیامک در ربات", State.BAD, "ربات این شماره را در فهرست مجاز گوشی نمی‌بیند. یک بار «همگام‌سازی» کنید.")
        val parsed = b.optBoolean("parsed")
        out += when {
            parsed -> Step(
                "خواندن پیامک بانک", State.OK,
                (if (sampleIsReal) "آخرین پیامک بانک شما" else "پیامک نمونه") + " خوانده شد: " +
                    Money.toman(b.optLong("amount")) + (if (b.optBoolean("learned")) " (با قالب آموزش‌داده‌شده)" else ""),
                key = "read",
            )
            sampleIsReal -> Step("خواندن پیامک بانک", State.BAD, "ربات آخرین پیامک بانک شما را نخواند: " + b.optString("note").ifEmpty { "قالب ناشناخته" } + ". از «رسیدها ← مغایرت» قالبش را آموزش دهید.", key = "read")
            else -> Step("خواندن پیامک بانک", State.WARN, "هنوز پیامک واقعی بانک نرسیده که آزمایش شود.", key = "read")
        }
        out += if (b.optBoolean("auto_on")) Step("تأیید خودکار سفارش", State.OK, "روشن است؛ واریز با امتیاز کافی خودکار تأیید می‌شود.")
        else Step("تأیید خودکار سفارش", State.INFO, "خاموش است؛ ربات فقط نظر می‌دهد. از ربات «تأیید واقعی سفارش» را روشن کنید.")
        return out
    }

    /**
     * Send a dry run to this bot? Only when its last ping reply lists `dry_run`: a bot without it
     * would record the sample as a real deposit — and a real deposit can approve an order. Pure.
     */
    fun shouldDryRun(shopInfo: JSONObject): Boolean =
        shopInfo.optJSONArray("features")?.let { a -> (0 until a.length()).any { a.optString(it) == "dry_run" } } ?: false

    /**
     * The headline of a run. [PARTIAL]: everything that ran is fine, but the SMS-reading test did not
     * run (an older bot) — which must never be shown as «the whole road is healthy». Pure.
     */
    enum class Outcome { GOOD, PARTIAL, WARN, BAD }

    fun outcome(steps: List<Step>): Outcome = when (verdict(steps)) {
        State.BAD -> Outcome.BAD
        State.WARN -> Outcome.WARN
        else -> if (steps.any { it.key == "read" }) Outcome.GOOD else Outcome.PARTIAL
    }

    /** Overall: bad if any step is, else warn if any is, else ok. Pure. */
    fun verdict(steps: List<Step>): State = when {
        steps.any { it.state == State.BAD } -> State.BAD
        steps.any { it.state == State.WARN } -> State.WARN
        else -> State.OK
    }

    /** The newest real SMS from an allowed sender in the log, or a minimal deposit text. */
    fun sample(store: Store): Pair<Pair<String, String>, Boolean> {
        val real = store.events().firstOrNull {
            it.optString("kind") == "sms" && it.optString("body").isNotBlank() && Rules.isAllowed(it.optString("from"), store.senders)
        }
        if (real != null) return (real.optString("from") to real.optString("body")) to true
        return ((store.senders.firstOrNull() ?: "test") to "واریز 10,000 ریال") to false
    }

    /** Run it: flush and ping first (the real road), then the dry run. Network: call off the main thread. */
    fun run(context: Context): List<Step> {
        val store = Store(context)
        Net.flush(context)
        val local = Local(
            paired = store.endpoint != null,
            smsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
            batteryFree = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            senders = store.senders.size,
            queued = store.queueSize(),
            recoveryOn = store.recoveryOn,
            readSms = Recovery.permitted(context),
            recoveryAvailable = Edition.readsInbox,
        )
        val endpoint = store.endpoint ?: return steps(local, null, false)
        val ping = Net.ping(context, endpoint)
        // Only a bot that says it understands a dry run gets one: an older bot would store the sample
        // as a real deposit — and a real deposit can approve an order.
        if (ping != Net.Ping.OK) {
            return steps(local, Remote(if (ping == Net.Ping.REVOKED) 404 else 0, Net.lastPingReason, null), false)
        }
        if (!shouldDryRun(store.shopInfo)) return steps(local, Remote(200, null, null), false)
        val (sms, real) = sample(store)
        val reply = Net.post(endpoint, JSONObject().put("dry_run", true).put("message", sms.second).put("from", sms.first))
        val body = runCatching { JSONObject(reply.body) }.getOrNull()?.takeIf { it.optBoolean("dry_run") }
        return steps(local, Remote(reply.code, reply.error, body), real)
    }
}
