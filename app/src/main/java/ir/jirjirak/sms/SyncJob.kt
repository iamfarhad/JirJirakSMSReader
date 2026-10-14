package ir.jirjirak.sms

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * The background half: every 15 minutes (Android's shortest period), and once whenever a send
 * failed, deliver what is queued and ping the bot. Both wait for a network, so a phone with no
 * internet costs nothing until it has some.
 */
class SyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Net.flush(this)
            Net.ping(this)
            // The retry job asks to be run again (with its backoff) while anything is still queued;
            // `false` here made the backoff dead config and a failed retry the last one.
            val again = params.jobId == RETRY && Store(this).queue().isNotEmpty() && Store(this).endpoint != null
            jobFinished(params, again)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val PERIODIC = 1
        private const val RETRY = 2

        fun schedulePeriodic(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            // A job that cannot be scheduled must never take the app down with it: the foreground
            // service beats on its own, this is the second line.
            runCatching { js.schedule(
                JobInfo.Builder(PERIODIC, ComponentName(c, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            ) }
        }

        /** Unpaired: nothing should wake the phone for this app any more. */
        fun cancelAll(c: Context) {
            c.getSystemService(JobScheduler::class.java)?.cancelAll()
        }

        fun scheduleRetry(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            runCatching { js.schedule(
                JobInfo.Builder(RETRY, ComponentName(c, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(5_000)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build(),
            ) }
        }
    }
}
