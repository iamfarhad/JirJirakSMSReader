package ir.jirjirak.sms.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.jirjirak.sms.MainActivity
import ir.jirjirak.sms.R
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The app: one screen to pair, then three tabs — «خانه» (is it working, what to set up, which
 * numbers), «گزارش» (every SMS and what became of it) and «بیشتر» (updates, links, the connection).
 *
 * Every card reads the Store through [watchStore], so a change from anywhere — a tap, an SMS
 * arriving, a background send — is on the screen the moment it is written.
 */
@Composable
fun App(resumed: Int, wantedTab: Int, tabRequest: Int = 0) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    watchStore()

    // «۳ دقیقه پیش» ages even when nothing else changes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumed) { now = System.currentTimeMillis() }

    var tab by rememberSaveable { mutableIntStateOf(wantedTab) }
    LaunchedEffect(wantedTab, tabRequest) { tab = wantedTab }

    LaunchedEffect(Unit) {
        // On opening, at most every 6 hours: the background beat asks too.
        if (System.currentTimeMillis() - store.lastUpdateCheck > 6 * 60 * 60 * 1000L) {
            withContext(Dispatchers.IO) { Updates.check(context.applicationContext) }
        }
    }

    val paired = store.endpoint != null
    // One scroll state for the three tabs, so a new tab starts at its top rather than wherever the
    // last one was left.
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }

    Scaffold(
        containerColor = Brand.Canvas,
        bottomBar = {
            if (paired) {
                NavigationBar(containerColor = Brand.Surface, tonalElevation = 0.dp) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Brand.GreenDark, selectedTextColor = Brand.GreenDark,
                        indicatorColor = Brand.GreenSoft, unselectedIconColor = Brand.Muted, unselectedTextColor = Brand.Muted,
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_HOME, onClick = { tab = MainActivity.TAB_HOME }, colors = colors,
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("خانه") },
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_RECEIPTS, onClick = { tab = MainActivity.TAB_RECEIPTS }, colors = colors,
                        icon = { Icon(Icons.Filled.Check, contentDescription = null) }, label = { Text("رسیدها") },
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_REPORT, onClick = { tab = MainActivity.TAB_REPORT }, colors = colors,
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }, label = { Text("گزارش") },
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_MORE, onClick = { tab = MainActivity.TAB_MORE }, colors = colors,
                        icon = { Icon(Icons.Filled.Menu, contentDescription = null) }, label = { Text("بیشتر") },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(Brand.Canvas)
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(if (paired) store.shop else null)
            when {
                !paired -> PairScreen(store)
                tab == MainActivity.TAB_REPORT -> ReportTab(store, now)
                tab == MainActivity.TAB_RECEIPTS -> ReceiptsTab(store, now)
                tab == MainActivity.TAB_MORE -> MoreTab(store, now)
                else -> HomeTab(store, now, resumed, onReport = { tab = MainActivity.TAB_REPORT }, onMore = { tab = MainActivity.TAB_MORE })
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun Header(shop: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Box(
            Modifier
                .size(50.dp)
                .shadow(10.dp, RoundedCornerShape(16.dp), spotColor = Brand.Green.copy(alpha = 0.5f))
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF16A34A), Color(0xFF166534)))),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_cricket), contentDescription = null, modifier = Modifier.size(50.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("جیرجیرک پیامک", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Brand.Ink)
            Text(shop ?: "تأیید خودکار رسید کارت‌به‌کارت", color = Brand.Muted, fontSize = 13.sp)
        }
    }
}
