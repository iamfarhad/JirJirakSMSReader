package ir.jirjirak.sms.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.jirjirak.sms.MainActivity
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Net
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «خانه» — in a few seconds: is it working, how many were approved, how many wait, is anything
 * asked of me. The figures are the bot's own (…/receipts/summary); without the manage code the
 * dashboard shows what the phone itself knows and offers the code, never invented numbers.
 */
@Composable
fun HomeTab(store: Store, now: Long, resumed: Int, onNavigate: (tab: Int, filter: Int) -> Unit) {
    watchStore()
    val context = LocalContext.current
    var summary by remember { mutableStateOf<Receipts.Summary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (store.manageKey == null) return
        loading = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { Receipts.summary(context.applicationContext) }) {
                is Receipts.Result.Ok -> { summary = r.value; error = null }
                Receipts.Result.NeedKey -> store.revokeManageKey()
                is Receipts.Result.Failed -> error = r.message
                Receipts.Result.AlreadyDecided -> {}
            }
            loading = false
        }
    }
    LaunchedEffect(store.manageKey, resumed) { load() }

    // The setup steps open under «سلامت سیستم» on a tap — and on their own while any is undone.
    var healthOpen by remember { mutableStateOf(false) }
    val power = context.getSystemService(android.os.PowerManager::class.java)
    val setupDone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
        store.senders.isNotEmpty() && (power?.isIgnoringBatteryOptimizations(context.packageName) ?: true)

    DashboardHero(store, summary, now)
    DashboardMetrics(store, summary, loading, error, onRetry = { load() }, onNavigate = onNavigate)
    ActionsNeeded(store, summary, now, onNavigate)
    HealthCard(store, now, healthOpen) { healthOpen = !healthOpen }
    if (healthOpen || !setupDone) ChecklistCard(store, resumed)
    if (store.senders.isEmpty() || healthOpen) SendersCard(store)
    RecentActivity(store, summary, now, onNavigate)
}

// ── the one sentence ────────────────────────────────────────────────────

@Composable
private fun DashboardHero(store: Store, summary: Receipts.Summary?, now: Long) {
    val context = LocalContext.current
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val online = now - store.lastPing < 40 * 60 * 1000L
    val pending = store.queue().size
    // Only what is TRUE: «auto-approves» is said only when the server says real approval is on.
    val state = when {
        store.lastError == "revoked" -> Triple("کد اتصال باطل شده", "ربات این گوشی را دیگر نمی‌شناسد؛ در «بیشتر» دوباره وصل شوید.", Tone.bad)
        !smsGranted -> Triple("اجازهٔ پیامک داده نشده", "تا اجازه داده نشود، پیامک بانک به ربات نمی‌رسد.", Tone.bad)
        store.senders.isEmpty() -> Triple("راه‌اندازی کامل نیست", "شمارهٔ پیامک بانک را مجاز کنید تا ارسال شروع شود.", Tone.warn)
        !online -> Triple("ارتباط با ربات قطع است", "پیامک‌ها روی گوشی صف می‌شوند و با وصل شدن فرستاده می‌شوند.", Tone.bad)
        pending > 0 -> Triple(Persian.number(pending) + " پیامک منتظر ارسال", "به‌محض وصل شدن اینترنت فرستاده می‌شوند.", Tone.warn)
        summary?.autoOn == true -> Triple("سیستم تأیید پرداخت فعال است", "پیامک‌های بانکی دریافت و رسیدها به‌صورت خودکار تأیید می‌شوند.", Tone.good)
        summary != null -> Triple("پیامک‌ها به ربات می‌رسند", "«تأیید واقعی سفارش» در ربات خاموش است؛ ربات فقط نظر می‌دهد.", Tone.info)
        else -> Triple("پیامک‌ها به ربات می‌رسند", "پیامک‌های بانک برای بررسی سفارش‌ها فرستاده می‌شوند.", Tone.good)
    }
    AnimatedContent(targetState = state, label = "hero") { (title, text, tone) ->
        HeroStatus(
            title, text, tone,
            icon = if (tone == Tone.good) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            alive = tone == Tone.good,
        )
    }
}

// ── the three figures ───────────────────────────────────────────────────

@Composable
private fun DashboardMetrics(store: Store, summary: Receipts.Summary?, loading: Boolean, error: String?, onRetry: () -> Unit, onNavigate: (Int, Int) -> Unit) {
    if (store.manageKey == null) {
        AppCard {
            Text("آمار پرداخت‌ها", color = Brand.Ink, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("برای دیدن تأییدهای امروز، رسیدهای در انتظار و جمع واریزی‌ها، «کد مدیریت رسیدها» را یک بار از ربات بچسبانید.", color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
            OutlinedButton(onClick = { onNavigate(MainActivity.TAB_RECEIPTS, 0) }, modifier = Modifier.fillMaxWidth()) { Text("افزودن کد مدیریت") }
        }
        return
    }
    if (error != null && summary == null) {
        ErrorState(error, onRetry)
        return
    }
    val m = summary?.metrics
    val p = summary?.previous
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m == null) {
            repeat(3) { Skeleton(Modifier.weight(1f), height = 118) }
        } else {
            MetricCard(
                "تأیید خودکار امروز", Persian.number(m.autoApproved), "رسید", Icons.Filled.Check, Tone.good, Modifier.weight(1f),
                delta = delta(m.autoApproved, p?.autoApproved ?: 0),
            )
            MetricCard(
                "نیازمند بررسی", Persian.number(m.needsReview), "رسید", Icons.Filled.DateRange, Tone.warn,
                Modifier.weight(1f).let { it },
            )
            MetricCard(
                "واریزی‌های امروز", Money.toman(m.depositsTotal).removeSuffix(" تومان"), "تومان", Icons.Filled.ShoppingCart, Tone.info, Modifier.weight(1f),
                delta = delta(m.depositsTotal, p?.depositsTotal ?: 0),
            )
        }
    }
    if (loading && summary != null) Skeleton(Modifier.fillMaxWidth(), height = 4)
}

// ── what is asked of the seller ─────────────────────────────────────────

@Composable
private fun ActionsNeeded(store: Store, summary: Receipts.Summary?, now: Long, onNavigate: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    data class Row(val icon: androidx.compose.ui.graphics.vector.ImageVector, val tone: String, val title: String, val hint: String, val go: () -> Unit)
    val rows = buildList {
        if (!smsGranted) add(Row(Icons.Filled.Lock, "bad", "اجازهٔ دریافت پیامک غیرفعال است", "بدون آن هیچ پیامکی به ربات نمی‌رسد") { onNavigate(MainActivity.TAB_HOME, 0) })
        if (store.lastPing > 0 && now - store.lastPing > 40 * 60 * 1000L) add(Row(Icons.Filled.Warning, "bad", "ارتباط گوشی با ربات قطع است", "اینترنت گوشی را بررسی کنید") { onNavigate(MainActivity.TAB_MORE, 0) })
        summary?.actions?.forEach { a ->
            when (a.kind) {
                "review" -> add(Row(Icons.Filled.Email, "warn", Persian.digits(a.title), a.hint) { onNavigate(MainActivity.TAB_RECEIPTS, 1) })
                "unmatched" -> add(Row(Icons.Filled.Info, "info", Persian.digits(a.title), a.hint) { onNavigate(MainActivity.TAB_RECEIPTS, 3) })
                "unreadable" -> add(Row(Icons.Filled.Warning, "warn", Persian.digits(a.title), a.hint) { onNavigate(MainActivity.TAB_REPORT, 0) })
            }
        }
    }
    AppCard {
        SectionHeader("اقدامات موردنیاز", count = rows.size.takeIf { it > 0 })
        if (rows.isEmpty()) {
            EmptyState(Icons.Filled.CheckCircle, "همه‌چیز مرتب است", null)
        } else {
            rows.forEach { r -> ActionRow(r.icon, Tone.of(r.tone), r.title, r.hint, onClick = r.go) }
        }
    }
}

// ── system health ───────────────────────────────────────────────────────

@Composable
private fun HealthCard(store: Store, now: Long, open: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val online = now - store.lastPing < 40 * 60 * 1000L
    val ok = smsGranted && online && store.senders.isNotEmpty() && store.lastError != "revoked"

    AppCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("بررسی سلامت سیستم", color = Brand.Ink, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
            StatusBadge(if (ok) "همه موارد سالم است" else "نیاز به توجه", if (ok) Tone.good else Tone.warn, if (ok) Icons.Filled.Check else Icons.Filled.Warning)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HealthTile(Icons.Filled.Refresh, "اتصال سرور", if (online) "متصل است" else "قطع است", online, Modifier.weight(1f)) { onToggle() }
            HealthTile(Icons.Filled.Email, "دسترسی پیامک", if (smsGranted) "فعال است" else "غیرفعال", smsGranted, Modifier.weight(1f)) { onToggle() }
            HealthTile(Icons.Filled.AccountBox, "فرستنده‌ها", if (store.senders.isEmpty()) "تعیین نشده" else Persian.number(store.senders.size) + " مجاز", store.senders.isNotEmpty(), Modifier.weight(1f)) { onToggle() }
            HealthTile(Icons.Filled.Phone, "صف گوشی", if (store.queue().isEmpty()) "خالی" else Persian.number(store.queue().size) + " منتظر", store.queue().isEmpty(), Modifier.weight(1f)) { onToggle() }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("آخرین ارتباط: " + Persian.ago(store.lastPing, now), color = Brand.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    val sent = withContext(Dispatchers.IO) { Net.flush(context.applicationContext) }
                    val pinged = withContext(Dispatchers.IO) { Net.ping(context.applicationContext) }
                    checking = false
                    result = when {
                        pinged && sent > 0 -> "✅ اتصال سرور سالم است و " + Persian.number(sent) + " پیامک فرستاده شد."
                        pinged -> "✅ اتصال سرور سالم است."
                        else -> "❌ " + (Net.lastPingReason ?: "ربات جواب نداد")
                    }
                }
            }) { Text(if (checking) "در حال بررسی…" else "تست سلامت") }
            TextButton(onClick = { onToggle() }) { Text(if (open) "بستن" else "جزئیات") }
        }
        result?.let { Notice(it, if (it.startsWith("✅")) Brand.GoodSoft else Brand.BadSoft, if (it.startsWith("✅")) Brand.Good else Brand.Bad) }
    }
}

// ── recent activity ─────────────────────────────────────────────────────

@Composable
private fun RecentActivity(store: Store, summary: Receipts.Summary?, now: Long, onNavigate: (Int, Int) -> Unit) {
    val recent = summary?.recent
    if (recent.isNullOrEmpty()) {
        RecentEvents(store) { onNavigate(MainActivity.TAB_REPORT, 0) }
        return
    }
    AppCard {
        SectionHeader("فعالیت‌های اخیر", action = "مشاهده همه") { onNavigate(MainActivity.TAB_RECEIPTS, 0) }
        recent.take(6).forEach { a ->
            val (icon, tone, title) = when (a.kind) {
                "auto_approved" -> Triple(Icons.Filled.CheckCircle, Tone.good, "پرداخت " + Money.toman(a.amount) + " خودکار تأیید شد")
                "approved" -> Triple(Icons.Filled.Check, Tone.good, "پرداخت " + Money.toman(a.amount) + " تأیید شد")
                "rejected" -> Triple(Icons.Filled.Close, Tone.bad, "رسید " + Money.toman(a.amount) + " رد شد")
                "unmatched" -> Triple(Icons.Filled.Info, Tone.info, "واریز " + Money.toman(a.amount) + " بدون فاکتور")
                else -> Triple(Icons.Filled.Warning, Tone.warn, "پیامک بانکی خوانده نشد")
            }
            ActionRow(icon, tone, title, a.customer ?: a.sender?.let { "از " + Persian.sender(it) }, trailing = Persian.clock(a.at))
        }
    }
}
