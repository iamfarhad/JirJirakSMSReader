package ir.jirjirak.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot, or an update of the app itself, the heartbeat is armed again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store(context).endpoint != null) {
            SyncJob.schedulePeriodic(context)
            SyncJob.scheduleRetry(context)
        }
    }
}
