package ir.jirjirak.sms

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import ir.jirjirak.sms.ui.App
import ir.jirjirak.sms.ui.JirjirakTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** An intent extra naming the tab to open — the update notification opens «بیشتر». */
        const val TAB = "tab"
        /** With [TAB_RECEIPTS]: which filter of the receipts centre (1 waiting, 3 mismatches). */
        const val FILTER = "filter"
        const val TAB_HOME = 0
        const val TAB_REPORT = 1
        const val TAB_MORE = 2
        const val TAB_RECEIPTS = 3
    }

    /** Bumped on every return to the app, so the screen re-reads permissions and status. */
    private val resumed = mutableIntStateOf(0)

    /** The tab asked for by the intent that opened (or re-opened) the app. */
    private val wantedTab = mutableIntStateOf(TAB_HOME)

    private val wantedFilter = mutableIntStateOf(0)

    /**
     * Bumped with every intent that names a tab. A second notification naming the SAME tab must
     * still switch to it after the seller wandered off — and a state set to its current value does
     * not recompose anything.
     */
    private val tabRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The recent-apps thumbnail would show a receipt, a customer's name or an amount to whoever
        // picks the phone up. Android 13+ can leave it out without blocking the seller's own
        // screenshots for support (FLAG_SECURE would block those too).
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        // The first open of the queue database, the 1.1 queue's move into it and the one-time sealing of
        // the pairing address: done here off the main thread, not during the first frame.
        val app = applicationContext
        Thread { runCatching { Store(app).let { it.queueSize(); it.endpoint; it.manageKey } } }.start()
        if (Store(this).endpoint != null) {
            SyncJob.schedulePeriodic(this)
            Beat.schedule(this)
            KeepAliveService.start(this)
        }
        // A rotation restores the tab the seller was on; only a fresh start obeys the intent's tab.
        wantedTab.intValue = if (savedInstanceState == null) intent?.getIntExtra(TAB, TAB_HOME) ?: TAB_HOME else -1
        wantedFilter.intValue = intent?.getIntExtra(FILTER, 0) ?: 0
        setContent {
            JirjirakTheme {
                App(resumed.intValue, wantedTab.intValue, wantedFilter.intValue, tabRequest.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as the activity's intent: a rotation re-reads it, and must find this one, not the first.
        setIntent(intent)
        if (intent.hasExtra(TAB)) {
            wantedTab.intValue = intent.getIntExtra(TAB, TAB_HOME)
            wantedFilter.intValue = intent.getIntExtra(FILTER, 0)
            tabRequest.intValue++
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen hides what was shown on request: a revealed code, a receipt's photo.
        ir.jirjirak.sms.ui.Privacy.epoch.intValue++
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }
}
