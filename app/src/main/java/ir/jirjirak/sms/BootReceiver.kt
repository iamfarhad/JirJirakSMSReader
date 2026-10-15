package ir.jirjirak.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot, or an update of the app itself, the heartbeat is armed again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = Store(context)
        if (store.endpoint != null) {
            store.event(
                "system", "info",
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) "گوشی روشن شد" else "برنامه به‌روز شد: نسخهٔ " + BuildConfig.VERSION_NAME,
                "ارسال پیامک‌ها دوباره راه افتاد.",
            )
            Beat.schedule(context)
            SyncJob.schedulePeriodic(context)
            SyncJob.scheduleRetry(context)
            // Starting a foreground service from BOOT_COMPLETED is one of Android's allowed cases.
            KeepAliveService.start(context)
            // An SMS that arrived during the reboot never reached the receiver: look for it.
            val app = context.applicationContext
            Thread { runCatching { Recovery.scan(app, force = true) } }.start()
        }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Whatever an older version downloaded is of no further use.
            Updates.forgetOldDownloads(context)
        }
    }
}
