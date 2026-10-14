package ir.jirjirak.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot, or an update of the app itself, the heartbeat is armed again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Store(context).endpoint != null) {
            Beat.schedule(context)
            SyncJob.schedulePeriodic(context)
            SyncJob.scheduleRetry(context)
            // Starting a foreground service from BOOT_COMPLETED is one of Android's allowed cases.
            KeepAliveService.start(context)
        }
    }
}
