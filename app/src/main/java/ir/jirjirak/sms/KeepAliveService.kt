package ir.jirjirak.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * What keeps the app awake. A foreground service — the small permanent «جیرجیرک پیامک فعال است»
 * notification — is the one thing every Android, and every vendor's battery manager (Xiaomi, Huawei,
 * Oppo, Samsung…), leaves running. Without it the process is killed within minutes of the screen going
 * off on most phones sold in Iran, and a killed app hears no SMS.
 *
 * The beat itself (deliver, ping, every 5 minutes) is an alarm — see Beat — which this service arms.
 *
 * `specialUse` type: the app is sideloaded (no Play listing to justify it to), and `dataSync` is
 * capped at six hours a day from Android 15 — this has to run all day.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notification = notification(this)
        // Android 12+ refuses to put a service in the foreground from the background in some cases —
        // a START_STICKY restart of an app the battery manager still restricts is one. Uncaught, that
        // is a crash Android retries in a loop; caught, the service stops and the beat (an alarm, not
        // this service) and the jobs carry on until the app is next opened.
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        Beat.schedule(this)
        if (!ok) {
            Store(this).event("system", "warn", "اندروید اجازهٔ اجرای پس‌زمینه نداد", "برنامه را یک بار باز کنید و «کار در پس‌زمینه» را در فهرست آماده‌سازی تنظیم کنید.")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Unpaired: nothing to keep alive.
        if (Store(this).endpoint == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY   // killed anyway? Android brings it back.
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "keepalive"
        private const val NOTIFICATION_ID = 1
        private const val BEAT_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            if (Store(context).endpoint == null) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, "فعال بودن برنامه", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "اعلان ثابتی که نمی‌گذارد گوشی برنامه را ببندد"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("جیرجیرک پیامک فعال است")
                .setContentText("پیامک‌های بانک به ربات فروشگاه فرستاده می‌شود")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
