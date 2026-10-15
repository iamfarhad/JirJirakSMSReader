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
        const val TAB_HOME = 0
        const val TAB_REPORT = 1
        const val TAB_MORE = 2
        const val TAB_RECEIPTS = 3
    }

    /** Bumped on every return to the app, so the screen re-reads permissions and status. */
    private val resumed = mutableIntStateOf(0)

    /** The tab asked for by the intent that opened (or re-opened) the app. */
    private val wantedTab = mutableIntStateOf(TAB_HOME)

    /**
     * Bumped with every intent that names a tab. A second notification naming the SAME tab must
     * still switch to it after the seller wandered off — and a state set to its current value does
     * not recompose anything.
     */
    private val tabRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store(this).endpoint != null) {
            SyncJob.schedulePeriodic(this)
            Beat.schedule(this)
            KeepAliveService.start(this)
        }
        wantedTab.intValue = intent?.getIntExtra(TAB, TAB_HOME) ?: TAB_HOME
        setContent {
            JirjirakTheme {
                App(resumed.intValue, wantedTab.intValue, tabRequest.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(TAB)) {
            wantedTab.intValue = intent.getIntExtra(TAB, TAB_HOME)
            tabRequest.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }
}
