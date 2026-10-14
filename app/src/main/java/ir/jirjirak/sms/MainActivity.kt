package ir.jirjirak.sms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import ir.jirjirak.sms.ui.App
import ir.jirjirak.sms.ui.JirjirakTheme

class MainActivity : ComponentActivity() {

    /** Bumped on every return to the app, so the screen re-reads permissions and status. */
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store(this).endpoint != null) {
            SyncJob.schedulePeriodic(this)
            Beat.schedule(this)
            KeepAliveService.start(this)
        }
        setContent {
            JirjirakTheme {
                App(resumed.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }
}
