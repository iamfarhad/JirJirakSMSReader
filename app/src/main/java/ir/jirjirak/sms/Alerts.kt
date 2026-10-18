package ir.jirjirak.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * What the phone says on its own, without opening Telegram: an order approved by the seller's own
 * bank SMS, the battery about to end the forwarding, the bot out of reach. One notification per
 * event — a phone that nags is a phone whose notifications get switched off.
 */
object Alerts {

    private const val CHANNEL_APPROVED = "approved"
    private const val CHANNEL_WARN = "warnings"
    private const val ID_BATTERY = 10
    private const val ID_OFFLINE = 11

    /** Below this, unplugged, the phone is warned once; above [BATTERY_RESET] the warning re-arms. */
    const val BATTERY_LOW = 15
    const val BATTERY_RESET = 30

    /** The bot unheard from for this long is worth telling the seller about, on the phone itself. */
    const val OFFLINE_MS = 40 * 60 * 1000L

    private const val GROUP_APPROVED = "approved"
    private const val ID_APPROVED_SUMMARY = 12
    private const val CHANNEL_REVIEW = "review"
    private const val ID_REVIEW = 13
    private const val ID_MISMATCH = 14

    /** «سکوت شبانه»: from 23:00 to 08:00 nothing that can wait is said. */
    const val QUIET_FROM = 23
    const val QUIET_UNTIL = 8

    /** What to do about a count the seller may want to hear of: notify, and what is now told. */
    data class Tell(val notify: Boolean, val told: Int, val cancel: Boolean, val refresh: Boolean = false)

    /**
     * Receipts waiting, or deposits nobody claimed. Told when the count goes UP since the last time —
     * the same three receipts are not news every fifteen minutes; told again in the morning if they
     * arrived during quiet hours; withdrawn when there is nothing left. Pure: unit-tested.
     */
    fun tell(count: Int, told: Int, enabled: Boolean, quietNights: Boolean, hour: Int): Tell {
        val quiet = quietNights && (hour >= QUIET_FROM || hour < QUIET_UNTIL)
        return when {
            count == 0 -> Tell(notify = false, told = 0, cancel = told > 0)
            // One decided, some left: the notice still up says the new number, quietly.
            count < told -> Tell(notify = false, told = count, cancel = false, refresh = enabled)
            count > told && enabled && !quiet -> Tell(notify = true, told = count, cancel = false)
            // Held for the morning: `told` stays put, so the first check after quiet hours says it.
            else -> Tell(notify = false, told = told, cancel = !enabled && told > 0)
        }
    }

    /**
     * From the periodic job: the bot's own count of receipts waiting and deposits unplaced, told on the
     * phone. Needs the manage key and a bot that has the dashboard; network — off the main thread.
     */
    fun checkReview(context: Context, now: Long = System.currentTimeMillis()) {
        val store = Store(context)
        if (store.manageKey == null || !store.supports("insights")) return
        val summary = (Receipts.summary(context, "today") as? Receipts.Result.Ok)?.value ?: return
        counts(context, summary.metrics.needsReview, summary.openMismatch, now, fromApp = false)
    }

    /**
     * Counts the app just read from the bot (the receipts list, a summary): the notifications follow
     * at once — one receipt decided in the bot, the «N رسید منتظر» notice says N−1 or goes. The app
     * only ever lowers them ([tellFromApp]); a rise is the background check's to announce.
     * [mismatch] null: not known from this read. Writes prefs and posts: call off the main thread.
     */
    fun seen(context: Context, pending: Int?, mismatch: Int?, now: Long = System.currentTimeMillis()) {
        val store = Store(context)
        if (store.manageKey == null || !store.supports("insights")) return
        counts(context, pending, mismatch, now, fromApp = true)
    }

    private fun counts(context: Context, pending: Int?, mismatch: Int?, now: Long, fromApp: Boolean) {
        val store = Store(context)
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        val manager = context.getSystemService(NotificationManager::class.java)
        val decide = if (fromApp) ::tellFromApp else ::tell

        if (pending != null) decide(pending, store.toldPending, store.notifyReview, store.quietNights, hour).let { t ->
            if (t.notify || t.refresh) {
                notify(context, CHANNEL_REVIEW, "رسیدها و مغایرت‌ها", ID_REVIEW,
                    "🧾 " + ir.jirjirak.sms.ui.Persian.number(pending) + " رسید منتظر بررسی",
                    "این رسیدها خودکار تأیید نشدند و تصمیم شما را لازم دارند.", MainActivity.TAB_RECEIPTS,
                    importance = NotificationManager.IMPORTANCE_DEFAULT, filter = 1, quiet = t.refresh)
            }
            if (t.cancel) manager?.cancel(ID_REVIEW)
            if (t.told != store.toldPending) store.toldPending = t.told
        }

        // The same count the «مغایرت» list shows, so a notification never promises more than is there.
        if (mismatch != null) decide(mismatch, store.toldMismatch, store.notifyMismatch, store.quietNights, hour).let { t ->
            if (t.notify || t.refresh) {
                notify(context, CHANNEL_REVIEW, "رسیدها و مغایرت‌ها", ID_MISMATCH,
                    "⚠️ " + ir.jirjirak.sms.ui.Persian.number(mismatch) + " مغایرت تازه",
                    "واریزی که به هیچ فاکتوری نخورد یا پیامکی که خوانده نشد. با یک تپ ببینید و تکلیفش را روشن کنید.", MainActivity.TAB_RECEIPTS,
                    importance = NotificationManager.IMPORTANCE_DEFAULT, filter = 3, quiet = t.refresh)
            }
            if (t.cancel) manager?.cancel(ID_MISMATCH)
            if (t.told != store.toldMismatch) store.toldMismatch = t.told
        }
    }

    /**
     * The same, for a count read while the seller has the app open: a lower count is said at once
     * (refreshed or withdrawn), a higher one is left for the background check — told stays put, so
     * that check still announces it. Pure: unit-tested.
     */
    fun tellFromApp(count: Int, told: Int, enabled: Boolean, quietNights: Boolean, hour: Int): Tell =
        if (count > told) Tell(notify = false, told = told, cancel = false) else tell(count, told, enabled, quietNights, hour)

    /** «۱۷۵٬۰۱۳ تومان — سفارش سارا». Pure: unit-tested. */
    fun approvedText(amount: Long, customer: String?): String =
        listOfNotNull(amount.takeIf { it > 0 }?.let { Money.toman(it) }, customer?.let { "سفارش $it" }).joinToString(" — ").ifEmpty { "یک سفارش" }

    /** One per approval, grouped: a backlog flushed after a night offline is one stack, not twenty. */
    /** Each approval its own id, never reused within a run: a clock remainder collided and wrapped. */
    private val approvedIds = java.util.concurrent.atomic.AtomicInteger(1000)

    fun approved(context: Context, amount: Long, customer: String?) {
        val id = approvedIds.updateAndGet { if (it >= 9_999) 1000 else it + 1 }
        notify(context, CHANNEL_APPROVED, "سفارش‌های تأییدشده", id,
            "✅ سفارش خودکار تأیید شد", approvedText(amount, customer), MainActivity.TAB_REPORT,
            importance = NotificationManager.IMPORTANCE_DEFAULT, group = GROUP_APPROVED)
        notify(context, CHANNEL_APPROVED, "سفارش‌های تأییدشده", ID_APPROVED_SUMMARY,
            "✅ سفارش‌های خودکار تأییدشده", "جیرجیرک پیامک", MainActivity.TAB_REPORT,
            importance = NotificationManager.IMPORTANCE_DEFAULT, group = GROUP_APPROVED, summary = true)
    }

    enum class Decision { WARN, CLEAR, NONE }

    /** Low battery: warn once below [BATTERY_LOW] unplugged; clear once plugged in or recovered. Pure. */
    fun batteryDecision(level: Int, charging: Boolean, warned: Boolean): Decision = when {
        level in 0 until BATTERY_LOW && !charging && !warned -> Decision.WARN
        warned && (charging || level >= BATTERY_RESET) -> Decision.CLEAR
        else -> Decision.NONE
    }

    /** The bot unheard from past [OFFLINE_MS]: warn once; clear when it answers. Never before a first ping. Pure. */
    fun offlineDecision(paired: Boolean, lastPing: Long, now: Long, warned: Boolean): Decision {
        val offline = paired && lastPing > 0 && now - lastPing > OFFLINE_MS
        return when {
            offline && !warned -> Decision.WARN
            !offline && warned -> Decision.CLEAR
            else -> Decision.NONE
        }
    }

    /** Unpaired: nothing it said is true any more. */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(ID_BATTERY)
        manager.cancel(ID_OFFLINE)
        manager.cancel(ID_REVIEW)
        manager.cancel(ID_MISMATCH)
    }

    /** From the beat: battery and connection, each told once until it recovers. */
    fun check(context: Context) {
        val store = Store(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val (level, charging) = battery(context)
        when (batteryDecision(level, charging, store.batteryWarned)) {
            Decision.WARN -> {
                store.batteryWarned = true
                notify(context, CHANNEL_WARN, "هشدارها", ID_BATTERY,
                    "🔋 شارژ گوشی کم است (${ir.jirjirak.sms.ui.Persian.number(level)}٪)",
                    "اگر گوشی خاموش شود، پیامک‌های بانک به ربات نمی‌رسد. گوشی را به شارژ بزنید.", MainActivity.TAB_HOME)
            }
            Decision.CLEAR -> {
                store.batteryWarned = false
                manager?.cancel(ID_BATTERY)
            }
            Decision.NONE -> {}
        }

        when (offlineDecision(store.endpoint != null, store.lastPing, System.currentTimeMillis(), store.offlineWarned)) {
            Decision.WARN -> {
                store.offlineWarned = true
                notify(context, CHANNEL_WARN, "هشدارها", ID_OFFLINE,
                    "📵 ارتباط با ربات قطع است",
                    "بیش از ۴۰ دقیقه است که ربات جواب نداده. اینترنت گوشی را بررسی کنید؛ پیامک‌ها روی گوشی صف می‌شوند و گم نمی‌شوند.", MainActivity.TAB_HOME)
            }
            Decision.CLEAR -> {
                store.offlineWarned = false
                manager?.cancel(ID_OFFLINE)
            }
            Decision.NONE -> {}
        }
    }

    /** Battery percent (-1 when unknown) and whether it is charging. */
    fun battery(context: Context): Pair<Int, Boolean> {
        val status = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return -1 to false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (if (level < 0) -1 else level * 100 / scale) to plugged
    }

    private fun notify(
        context: Context, channel: String, channelName: String, id: Int,
        title: String, text: String, tab: Int, importance: Int = NotificationManager.IMPORTANCE_HIGH,
        group: String? = null, summary: Boolean = false, filter: Int = 0, quiet: Boolean = false,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel, channelName, importance))
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).putExtra(MainActivity.TAB, tab).putExtra(MainActivity.FILTER, filter),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else @Suppress("DEPRECATION") Notification.Builder(context)
        runCatching {
            manager.notify(
                id,
                builder.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    // A count that changed is not a new alarm.
                    .setOnlyAlertOnce(quiet)
                    // On a locked screen: that something happened, never whose money or how much.
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else @Suppress("DEPRECATION") Notification.Builder(context))
                            .setSmallIcon(R.drawable.ic_notification).setContentTitle("جیرجیرک پیامک").setContentText("یک مورد تازه — برای دیدن، قفل گوشی را باز کنید.").build(),
                    )
                    .apply { if (group != null) setGroup(group).setGroupSummary(summary) }
                    .build(),
            )
        }
    }
}
