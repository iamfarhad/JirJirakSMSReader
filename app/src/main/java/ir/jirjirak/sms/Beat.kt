package ir.jirjirak.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * The heartbeat: deliver what is queued, ping the bot, and set the next beat.
 *
 * An AlarmManager «allow while idle» alarm, not a Handler: a Handler counts uptime, which stops in
 * deep sleep, so a phone left on a desk overnight stopped pinging and the bot called it quiet. This
 * alarm fires in Doze too (Android spaces such alarms ~9 min apart there — well inside the bot's
 * 40-minute quiet threshold). It needs no exact-alarm permission.
 */
class Beat : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Store(context).endpoint == null) return
        schedule(context)
        val pending = goAsync()
        Thread {
            try {
                Net.flush(context)
                Net.ping(context)
                Alerts.check(context)
                Updates.checkInBackground(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val EVERY_MS = 5 * 60 * 1000L

        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + EVERY_MS,
                    pendingIntent(context),
                )
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, Beat::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
