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
    const val OFFLINE_MS = 30 * 60 * 1000L

    /** «۱۷۵٬۰۱۳ تومان — سفارش سارا تأیید شد». */
    fun approved(context: Context, amount: Long, customer: String?) {
        val text = Money.toman(amount) + (customer?.let { " — سفارش $it" } ?: "")
        notify(context, CHANNEL_APPROVED, "سفارش‌های تأییدشده", (System.currentTimeMillis() % 100_000).toInt() + 1000,
            "✅ سفارش خودکار تأیید شد", text, MainActivity.TAB_REPORT, importance = NotificationManager.IMPORTANCE_DEFAULT)
    }

    /** From the beat: battery and connection, each told once until it recovers. */
    fun check(context: Context) {
        val store = Store(context)
        val (level, charging) = battery(context)
        if (level in 0 until BATTERY_LOW && !charging && !store.batteryWarned) {
            store.batteryWarned = true
            notify(context, CHANNEL_WARN, "هشدارها", ID_BATTERY,
                "🔋 شارژ گوشی کم است (${ir.jirjirak.sms.ui.Persian.number(level)}٪)",
                "اگر گوشی خاموش شود، پیامک‌های بانک به ربات نمی‌رسد. گوشی را به شارژ بزنید.", MainActivity.TAB_HOME)
        } else if (level >= BATTERY_RESET || charging) {
            store.batteryWarned = false
        }

        val offline = store.endpoint != null && store.lastPing > 0 && System.currentTimeMillis() - store.lastPing > OFFLINE_MS
        if (offline && !store.offlineWarned) {
            store.offlineWarned = true
            notify(context, CHANNEL_WARN, "هشدارها", ID_OFFLINE,
                "📵 ارتباط با ربات قطع است",
                "بیش از نیم ساعت است که ربات جواب نداده. اینترنت گوشی را بررسی کنید؛ پیامک‌ها روی گوشی صف می‌شوند و گم نمی‌شوند.", MainActivity.TAB_HOME)
        } else if (!offline && store.offlineWarned) {
            store.offlineWarned = false
            context.getSystemService(NotificationManager::class.java)?.cancel(ID_OFFLINE)
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
                    .build(),
            )
        }
    }
}
