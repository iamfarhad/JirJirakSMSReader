package ir.jirjirak.sms.ui

import ir.jirjirak.sms.Net

/**
 * The presentation states of the redesign, decided from the app's REAL data — pure, so each one is
 * unit-tested and none of them can be reached by a sample value. The handoff's 100 pictures are
 * these states (and the routes in App), not 100 screens.
 */
object States {

    /** A ping older than this and the bot is «not heard from». The same 40 minutes as the widget. */
    const val ONLINE_MS = 40 * 60 * 1000L

    /** Figures older than this are labelled «last known», not shown as today's truth. */
    const val STALE_MS = 15 * 60 * 1000L

    // ── the dashboard's one sentence (states 13–19, 52) ──────────────────

    enum class HomeHero { REVOKED, NO_SMS, NO_SENDERS, OFFLINE, QUEUE, AUTO_ON, AUTO_OFF, FORWARDING }

    /**
     * Worst first: a revoked pairing or a missing permission hides everything else. «Auto-approves»
     * is said only when the bot itself says real approval is on ([autoOn] true); unknown is not on.
     */
    fun homeHero(revoked: Boolean, smsGranted: Boolean, senders: Int, online: Boolean, pending: Int, autoOn: Boolean?): HomeHero = when {
        revoked -> HomeHero.REVOKED
        !smsGranted -> HomeHero.NO_SMS
        senders == 0 -> HomeHero.NO_SENDERS
        !online -> HomeHero.OFFLINE
        pending > 0 -> HomeHero.QUEUE
        autoOn == true -> HomeHero.AUTO_ON
        autoOn == false -> HomeHero.AUTO_OFF
        else -> HomeHero.FORWARDING
    }

    fun online(lastPing: Long, now: Long): Boolean = lastPing > 0 && now - lastPing < ONLINE_MS

    // ── the bot's figures (states 14, 15, 71, 72) ────────────────────────

    enum class Stats { NO_KEY, LOADING, FRESH, STALE, ERROR }

    /**
     * Loading is not zero, and a failed request is not «no records»: with nothing ever fetched a
     * failure is ERROR (figures «—»); with an older answer in hand it is STALE, said with its age.
     */
    fun stats(hasKey: Boolean, hasSummary: Boolean, lastFailed: Boolean, fetchedAt: Long, now: Long): Stats = when {
        !hasKey -> Stats.NO_KEY
        !hasSummary && lastFailed -> Stats.ERROR
        !hasSummary -> Stats.LOADING
        lastFailed || now - fetchedAt > STALE_MS -> Stats.STALE
        else -> Stats.FRESH
    }

    // ── pairing (states 01–04, 66) ───────────────────────────────────────

    enum class Pair { EMPTY, CHECKING, INVALID, OFFLINE, REVOKED }

    fun pairAfter(ping: Net.Ping): Pair = when (ping) {
        Net.Ping.OK -> Pair.EMPTY
        Net.Ping.REVOKED -> Pair.REVOKED
        Net.Ping.UNREACHABLE -> Pair.OFFLINE
    }

    // ── setup (05–08, 55) ────────────────────────────────────────────────

    /** The two things without which nothing is sent, apart from what only makes it steadier. */
    data class Setup(val essentialDone: Int, val essentialTotal: Int, val extrasDone: Int, val extrasTotal: Int) {
        val ready get() = essentialDone == essentialTotal
    }

    fun setup(smsGranted: Boolean, senders: Int, notifications: Boolean, batteryFree: Boolean, autostartNeeded: Boolean, autostartConfirmed: Boolean): Setup {
        val essential = listOf(smsGranted, senders > 0)
        val extras = listOf(notifications, batteryFree) + if (autostartNeeded) listOf(autostartConfirmed) else emptyList()
        return Setup(essential.count { it }, essential.size, extras.count { it }, extras.size)
    }

    /**
     * Android's «restricted settings» lock exists from Android 13 for an app installed from a file.
     * Its guide is shown only there, and only once the SMS permission was asked and still refused.
     */
    fun showRestrictedGuide(sdk: Int, asked: Boolean, granted: Boolean): Boolean = sdk >= 33 && asked && !granted

    // ── the queue (18, 73–75) ────────────────────────────────────────────

    enum class QueueRow { QUEUED, RETRY, THROTTLED }

    /** A queued SMS's line, from its event: waiting, retrying after a fault, or held back by a 429. */
    fun queueRow(status: String, detail: String?): QueueRow = when {
        status == "retry" && (detail?.contains("آهسته‌تر") == true || detail?.contains("429") == true) -> QueueRow.THROTTLED
        status == "retry" -> QueueRow.RETRY
        else -> QueueRow.QUEUED
    }

    // ── recovery (90–92) ─────────────────────────────────────────────────

    /** «found and queued» — never «sent»: whether the bot took them is the report's to say. */
    fun recoveryResult(queued: Int): String =
        if (queued > 0) Persian.number(queued) + " پیامک جامانده پیدا شد و به صف ارسال اضافه شد. نتیجهٔ ارسال هر کدام در «گزارش» می‌آید."
        else "در بازهٔ مجاز، پیامک تازه‌ای برای افزودن به صف پیدا نشد."

    // ── the unpair warning (49) ──────────────────────────────────────────

    fun unpairText(queued: Int): String =
        (if (queued > 0) Persian.number(queued) + " پیامک این فروشگاه هنوز ارسال نشده است. با قطع اتصال، این صف و کدها از برنامه حذف می‌شوند و به فروشگاه دیگری منتقل نمی‌شوند. "
        else "صف ارسال خالی است. با قطع اتصال، کد اتصال و کد مدیریت از برنامه حذف می‌شوند. ") +
            "شماره‌های مجاز و گزارش باقی می‌مانند. این کار قابل بازگشت نیست؛ برای وصل شدن دوباره کد تازه لازم است."
}
