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

    /** «۱۷۵٬۰۱۳ تومان — سفارش سارا». Pure: unit-tested. */
    fun approvedText(amount: Long, customer: String?): String =
        listOfNotNull(amount.takeIf { it > 0 }?.let { Money.toman(it) }, customer?.let { "سفارش $it" }).joinToString(" — ").ifEmpty { "یک سفارش" }

    /** One per approval, grouped: a backlog flushed after a night offline is one stack, not twenty. */
    fun approved(context: Context, amount: Long, customer: String?) {
        notify(context, CHANNEL_APPROVED, "سفارش‌های تأییدشده", (System.currentTimeMillis() % 100_000).toInt() + 1000,
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
        group: String? = null, summary: Boolean = false,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel, channelName, importance))
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).putExtra(MainActivity.TAB, tab),
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
                    .apply { if (group != null) setGroup(group).setGroupSummary(summary) }
                    .build(),
            )
        }
    }
}
