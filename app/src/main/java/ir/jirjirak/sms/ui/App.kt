package ir.jirjirak.sms.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.MainActivity
import ir.jirjirak.sms.R
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Where the seller is: one of the four tabs, and inside «خانه» and «بیشتر» the sub-screen (a route
 * name). Every piece is saveable, so a rotation keeps the place. «رسیدها» keeps its own routes (a
 * receipt, a deposit being tied, an SMS being taught) — they carry server rows, not names.
 */
class Nav(
    private val tabState: MutableIntState,
    private val homeState: MutableState<String>,
    private val moreState: MutableState<String>,
    private val filterState: MutableIntState,
) {
    val tab get() = tabState.intValue
    val home get() = homeState.value
    val more get() = moreState.value
    val receiptsFilter get() = filterState.intValue

    /** A tab from the bar: it opens at its own first screen. */
    fun tab(t: Int) {
        if (t == MainActivity.TAB_HOME) homeState.value = HOME_ROOT
        if (t == MainActivity.TAB_MORE) moreState.value = MORE_ROOT
        tabState.intValue = t
    }

    fun home(route: String = HOME_ROOT) { homeState.value = route; tabState.intValue = MainActivity.TAB_HOME }
    fun more(route: String = MORE_ROOT) { moreState.value = route; tabState.intValue = MainActivity.TAB_MORE }
    fun receipts(filter: Int = 0) { filterState.intValue = filter; tabState.intValue = MainActivity.TAB_RECEIPTS }
    fun setReceiptsFilter(filter: Int) { filterState.intValue = filter }
    fun report() { tabState.intValue = MainActivity.TAB_REPORT }

    companion object {
        const val HOME_ROOT = "dash"
        const val MORE_ROOT = "hub"
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav is provided by App") }

/** The page's one scroll, for a screen that opens at its top and a list that comes back to its place. */
val LocalScroll = staticCompositionLocalOf<ScrollState?> { null }

/**
 * The app: one flow to pair, then four tabs — «خانه» (is it working, what is asked of me),
 * «رسیدها» (deciding receipts), «گزارش» (figures and every SMS's fate) and «بیشتر» (settings).
 *
 * Every card reads the Store through [watchStore], so a change from anywhere — a tap, an SMS
 * arriving, a background send — is on the screen the moment it is written.
 */
@Composable
fun App(resumed: Int, wantedTab: Int, wantedFilter: Int = 0, tabRequest: Int = 0) {
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

    val tabState = rememberSaveable { mutableIntStateOf(if (wantedTab < 0) MainActivity.TAB_HOME else wantedTab) }
    val homeState = rememberSaveable { mutableStateOf(Nav.HOME_ROOT) }
    val moreState = rememberSaveable { mutableStateOf(Nav.MORE_ROOT) }
    val filterState = rememberSaveable { mutableIntStateOf(0) }
    val nav = remember { Nav(tabState, homeState, moreState, filterState) }
    LaunchedEffect(wantedTab, tabRequest) {
        if (wantedTab < 0) return@LaunchedEffect
        if (wantedTab == MainActivity.TAB_RECEIPTS) nav.receipts(wantedFilter) else nav.tab(wantedTab)
    }

    LaunchedEffect(Unit) {
        // On opening, at most every 6 hours: the background beat asks too.
        if (System.currentTimeMillis() - store.lastUpdateCheck > 6 * 60 * 60 * 1000L) {
            withContext(Dispatchers.IO) { Updates.check(context.applicationContext) }
        }
    }

    val paired = store.endpoint != null
    // One scroll state for every screen, so a new tab or sub-screen starts at its top.
    val scroll = rememberScrollState()
    LaunchedEffect(nav.tab, nav.home, nav.more) { scroll.scrollTo(0) }

    // Back from a sub-screen goes to its tab's first screen, not out of the app.
    BackHandler(enabled = paired && nav.tab == MainActivity.TAB_HOME && nav.home != Nav.HOME_ROOT) { nav.home() }
    // A webhook being edited goes back to the webhooks' list, the rest to «بیشتر».
    BackHandler(enabled = paired && nav.tab == MainActivity.TAB_MORE && nav.more != Nav.MORE_ROOT) {
        nav.more(if (nav.more.startsWith("hook:")) "hooks" else Nav.MORE_ROOT)
    }

    val atRoot = !paired || when (nav.tab) {
        MainActivity.TAB_HOME -> nav.home == Nav.HOME_ROOT
        MainActivity.TAB_MORE -> nav.more == Nav.MORE_ROOT
        else -> true
    }

    CompositionLocalProvider(LocalNav provides nav, LocalScroll provides scroll) {
        Scaffold(
            containerColor = Brand.Canvas,
            bottomBar = { if (paired) BottomBar(nav) },
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
                // The receipts tab draws its own header on its inner screens.
                if (atRoot && !(paired && nav.tab == MainActivity.TAB_RECEIPTS && ReceiptsInner.value)) {
                    Header(if (paired) store.shop else null, paired) { nav.more("security") }
                }
                when {
                    !paired -> PairScreen(store)
                    nav.tab == MainActivity.TAB_REPORT -> ReportTab(store, now)
                    nav.tab == MainActivity.TAB_RECEIPTS -> ReceiptsTab(store, now, nav.receiptsFilter) { nav.setReceiptsFilter(it) }
                    nav.tab == MainActivity.TAB_MORE -> MoreTab(store, now, nav.more)
                    else -> HomeTab(store, now, resumed, nav.home)
                }
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

/** Whether «رسیدها» is on an inner screen (a receipt, a deposit, a format): it then has its own title. */
object ReceiptsInner {
    var value by mutableStateOf(false)
}

@Composable
private fun BottomBar(nav: Nav) {
    NavigationBar(containerColor = Brand.Surface, tonalElevation = 0.dp) {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Brand.Green, selectedTextColor = Brand.Green,
            indicatorColor = Brand.GreenSoft, unselectedIconColor = Brand.Muted, unselectedTextColor = Brand.Muted,
        )
        // Written first is drawn rightmost: «خانه» sits where a right-to-left reader starts.
        listOf(
            Triple(MainActivity.TAB_HOME, Ico.Home, "خانه"),
            Triple(MainActivity.TAB_RECEIPTS, Ico.Receipt, "رسیدها"),
            Triple(MainActivity.TAB_REPORT, Ico.Chart, "گزارش"),
            Triple(MainActivity.TAB_MORE, Ico.More, "بیشتر"),
        ).forEach { (tab, icon, label) ->
            val selected = nav.tab == tab
            // At a large font four labels do not fit side by side: only the chosen tab keeps its
            // word on screen, the others are spoken from the icon's description. A label is never
            // broken in the middle of a word.
            val roomy = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
            NavigationBarItem(
                selected = selected, onClick = { nav.tab(tab) }, colors = colors, alwaysShowLabel = roomy,
                icon = { JIcon(icon, if (selected) Brand.Green else Brand.Muted, 24, label = if (roomy) null else label) },
                label = {
                    Text(
                        label, style = if (selected) Type.badge.copy(fontSize = Type.supporting.fontSize) else Type.supporting,
                        // Only the chosen tab's label is drawn at a large font, so it may spill over
                        // its neighbours' empty label row rather than be cut short.
                        maxLines = 1, softWrap = false,
                        overflow = if (roomy) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Visible,
                    )
                },
            )
        }
    }
}

@Composable
private fun Header(shop: String?, paired: Boolean, onSecurity: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Image(
            painterResource(R.drawable.jrs_mascot), contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Green),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("جیرجیرک پیامک", style = Type.page, color = Brand.Ink)
            Text(shop ?: "دستیار پیامک فروشگاه شما", style = Type.supporting, color = Brand.Muted)
        }
        if (paired) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Surface).clickable(onClick = onSecurity)
                    .semantics { contentDescription = "امنیت و دسترسی‌ها" },
                contentAlignment = Alignment.Center,
            ) { JIcon(Ico.Shield, Brand.Green, 24) }
        }
    }
}
