package ir.jirjirak.sms.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ir.jirjirak.sms.BuildConfig
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** «گزارش»: the shop's figures from the bot, then every SMS and what became of it, by day. */
@Composable
fun ReportTab(store: Store, now: Long) {
    watchStore()
    val context = LocalContext.current
    val nav = LocalNav.current
    val clipboard = LocalClipboardManager.current
    val all = store.events()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(40) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    when {
        all.isEmpty() -> Hero("گزارش رویدادها", "هر پیامک بانکی با نتیجهٔ ارسالش اینجا ثبت خواهد شد.", Tone.info, icon = Ico.Chart)
        filter == 3 -> Hero("پیگیری مشکلات", "رویدادهای نیازمند توجه، برای عیب‌یابی", Tone.warn, icon = Ico.Alert)
        else -> Hero("گزارش واریزها و پیامک‌ها", "وضعیت پردازش و ارسال در یک نگاه", Tone.info, icon = Ico.Chart)
    }

    ReportAnalytics(store)

    val events = when (filter) {
        1 -> all.filter { it.optString("kind") == "sms" }
        2 -> all.filter { it.optString("kind") == "ignored" }
        3 -> all.filter { it.optString("status") in setOf("warn", "failed", "retry") }
        else -> all
    }

    AppCard(title = "گزارش فنی گوشی", trailing = { StatusBadge(Persian.number(events.size) + " رویداد", Tone.muted) }) {
        Segmented(listOf("همه", "پیامک‌ها", "فرستاده‌نشده", "مشکل‌ها"), filter) { filter = it; shown = 40 }
        if (events.isEmpty()) {
            EmptyState(
                when (filter) {
                    1 -> "هنوز پیامکی فرستاده نشده است"
                    2 -> "هیچ پیامک بانکی‌ای نادیده گرفته نشده است"
                    3 -> "مشکلی ثبت نشده است"
                    else -> "هنوز رویدادی ندارید"
                },
                if (filter == 0) "پس از اولین پیامک بانک، نتیجهٔ ارسال در این بخش نمایش داده می‌شود." else null,
                if (filter == 0) Art.Report else null,
            )
            if (filter == 0) SecondaryButton("بررسی سلامت سیستم", { nav.home("health") })
        }
        var lastDay: String? = null
        events.take(shown).forEach { e ->
            val day = Persian.day(e.optLong("at"), now)
            if (day != lastDay) {
                lastDay = day
                Text(day, style = Type.badge, color = Brand.Muted, modifier = Modifier.padding(top = 6.dp))
                Divider()
            }
            val id = e.optString("id")
            EventRow(e, expanded == id) { expanded = if (expanded == id) null else id }
        }
        if (events.size > shown) SecondaryButton("نمایش بیشتر", { shown += 40 })
        // A password SMS leaves only its sender and the reason in the log: no text, no fingerprint.
        if (events.any { it.optString("kind") == "ignored" && it.optString("title").contains("رمز") }) {
            Notice("پیامک‌های رمز روی گوشی ماندند", "فقط نام فرستنده و دلیل عدم ارسال ثبت می‌شود؛ متن آن‌ها نه ذخیره می‌شود و نه ارسال. تشخیص رمز بر اساس الگوست و تضمین صددرصد نیست.", Tone.good, Ico.Shield)
        }
    }

    AppCard(title = "برای پشتیبانی") {
        Text(
            "اگر چیزی درست کار نمی‌کند، این گزارش را کپی کنید و برای پشتیبانی جیرجیرک بفرستید: نسخه، وضعیت اجازه‌ها، آخرین ارتباط و رویدادهای اخیر — بدون متن پیامک‌ها و بدون هیچ کدی.",
            style = Type.supporting, color = Brand.Muted,
        )
        SecondaryButton(if (copied) "کپی شد" else "کپی گزارش امن برای پشتیبانی", {
            clipboard.setText(AnnotatedString(diagnostics(context, store, now))); copied = true
        }, icon = Ico.Copy)
        if (all.isNotEmpty()) LinkButton("پاک کردن گزارش", { clearing = true }, color = Brand.Bad, modifier = Modifier.fillMaxWidth())
    }

    if (clearing) {
        ConfirmDialog(
            "پاک کردن گزارش؟", "همهٔ رویدادهای ثبت‌شده پاک می‌شود، اما پیامک‌هایی که هنوز فرستاده نشده‌اند در صف باقی می‌مانند.",
            "پاک کردن گزارش", { store.clearEvents(); clearing = false }, { clearing = false }, danger = true,
        )
    }
}

private val RANGES = listOf("today" to "امروز", "7d" to "۷ روز", "30d" to "۳۰ روز")

/**
 * The shop's figures, from the bot — never counted on the phone, which only sees the SMS it
 * forwarded and not what the bot did with them.
 */
@Composable
private fun ReportAnalytics(store: Store) {
    val context = LocalContext.current
    val nav = LocalNav.current
    var range by rememberSaveable { mutableIntStateOf(1) }
    var summary by remember(range) { mutableStateOf<Receipts.Summary?>(null) }
    var error by remember(range) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    if (store.manageKey == null) {
        AppCard(title = "گزارش فروش و تأیید") {
            Text("برای دیدن آمار رسیدها، واریزها و درصد تأیید خودکار، کد مدیریت رسیدها لازم است.", style = Type.body, color = Brand.Muted)
            SecondaryButton("افزودن کد مدیریت", { nav.receipts() })
        }
        return
    }
    LaunchedEffect(range, attempt) {
        error = null
        when (val r = withContext(Dispatchers.IO) { Receipts.summary(context.applicationContext, RANGES[range].first) }) {
            is Receipts.Result.Ok -> summary = r.value
            Receipts.Result.NeedKey -> store.revokeManageKey()
            is Receipts.Result.Failed -> error = r.message
            Receipts.Result.AlreadyDecided -> {}
        }
    }

    Segmented(RANGES.map { it.second }, range) { range = it }
    error?.let { ErrorState(it) { attempt++ }; return }
    val s = summary
    val stack = LocalDensity.current.fontScale > 1.3f
    if (s == null) {
        Waiting("دریافت آمار از ربات…")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(2) { Skeleton(Modifier.weight(1f), height = 90) } }
        return
    }
    val m = s.metrics
    val decided = m.autoApproved + m.manualApproved

    @Composable
    fun pair(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
        if (stack) { a(Modifier); b(Modifier) }
        else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            a(Modifier.weight(1f).fillMaxHeight()); b(Modifier.weight(1f).fillMaxHeight())
        }
    }
    pair({ MetricTile("تأیید خودکار", Persian.number(m.autoApproved), "رسید", it) }, { MetricTile("تأیید دستی", Persian.number(m.manualApproved), "رسید", it) })
    pair({ MetricTile("رد شده", Persian.number(m.rejected), "رسید", it) }, { MetricTile("در انتظار", Persian.number(m.needsReview), "رسید", it) })
    pair(
        { MetricTile("نرخ تأیید خودکار", Persian.number(m.autoRate) + "٪", Persian.number(m.autoApproved) + " از " + Persian.number(decided), it) },
        { MetricTile("میانگین زمان تأیید", if (m.avgAutoSeconds > 0) duration(m.avgAutoSeconds) else "—", "خودکار", it) },
    )
    MetricTile("جمع واریزها", compactToman(m.depositsTotal).removeSuffix(" تومان"), "تومان", note = Persian.number(m.depositsCount) + " واریز · " + Persian.number(m.unmatched) + " بی‌فاکتور")

    // Day by day — only meaningful over more than one day.
    if (s.series.size > 1) {
        AppCard(title = "روند تأیید پرداخت") {
            LineChart(s.series.map { it.auto.toFloat() }, Brand.Green, second = s.series.map { it.manual.toFloat() }, secondColor = Brand.Blue)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend("خودکار", Brand.Green)
                Legend("دستی", Brand.Blue)
                Spacer(Modifier.weight(1f))
                Text(
                    Persian.digits(s.series.first().date.takeLast(5).replace('-', '/')) + " تا " + Persian.digits(s.series.last().date.takeLast(5).replace('-', '/')),
                    style = Type.badge, color = Brand.Muted,
                )
            }
        }
    }

    AppCard(title = "سهم هر روش") {
        if (decided + m.rejected == 0) {
            Text("در این بازه رسیدی تأیید یا رد نشده است.", style = Type.body, color = Brand.Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(listOf(m.autoApproved.toFloat() to Brand.Green, m.manualApproved.toFloat() to Brand.Blue, m.rejected.toFloat() to Brand.Bad)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Persian.number(decided + m.rejected), style = Type.page, color = Brand.Ink)
                        Text("رسید", style = Type.badge, color = Brand.Muted)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Legend("تأیید خودکار · " + Persian.number(m.autoApproved), Brand.Green)
                    Legend("تأیید دستی · " + Persian.number(m.manualApproved), Brand.Blue)
                    Legend("رد شده · " + Persian.number(m.rejected), Brand.Bad)
                }
            }
        }
    }

    if (s.banks.isNotEmpty()) {
        AppCard(title = "واریز به تفکیک بانک") {
            val top = s.banks.maxOf { it.total }.takeIf { it > 0 } ?: 1L
            s.banks.forEach { b ->
                ProgressRow(Persian.sender(b.sender), Persian.number(b.count) + " واریز · " + compactToman(b.total), b.total.toFloat() / top, Brand.Green)
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.supporting, color = Brand.Ink)
    }
}

/** «۴۸٫۵ میلیون تومان» — a total on a small card, rounded to what a glance can read. */
fun compactToman(amount: Long): String = when {
    amount >= 1_000_000_000 -> Persian.digits(oneDecimal(amount / 1_000_000_000.0)) + " میلیارد تومان"
    amount >= 1_000_000 -> Persian.digits(oneDecimal(amount / 1_000_000.0)) + " میلیون تومان"
    else -> Money.toman(amount)
}

private fun oneDecimal(v: Double): String {
    val r = Math.round(v * 10) / 10.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', '٫')
}

/** «۱۴ ثانیه» / «۳ دقیقه». */
fun duration(seconds: Int): String = when {
    seconds < 60 -> Persian.number(seconds) + " ثانیه"
    seconds < 3600 -> Persian.number(seconds / 60) + " دقیقه"
    else -> Persian.number(seconds / 3600) + " ساعت"
}

/** What support needs to know, in one paste. No SMS text: the sender and the outcome are enough. */
fun diagnostics(context: Context, store: Store, now: Long): String {
    fun yes(b: Boolean) = if (b) "بله" else "خیر"
    val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val notif = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val battery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    val lines = mutableListOf(
        "جیرجیرک پیامک " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL,
        "فروشگاه: " + (store.shop ?: "—") + " · سرور: " + (store.server ?: "—"),
        "اجازهٔ پیامک: " + yes(sms) + " · اعلان: " + yes(notif) + " · باتری آزاد: " + yes(battery) + " · اجرای خودکار: " + yes(store.autostartConfirmed),
        "آخرین ارتباط: " + Persian.ago(store.lastPing, now) + " · آخرین پیامک: " + Persian.ago(store.lastSent, now) + " · در صف: " + store.queueSize() + " · خطای آخر: " + (store.lastError ?: "—"),
        "شماره‌های مجاز: " + (store.senders.takeIf { it.isNotEmpty() }?.joinToString("، ") ?: "هیچ"),
        "رویدادهای اخیر:",
    )
    store.events().take(25).forEach { e: JSONObject ->
        val look = EventLook.of(e)
        lines += "- " + Persian.date(e.optLong("at")) + " " + Persian.clock(e.optLong("at")) + " · " + e.optString("title") +
            (e.optString("from").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") +
            (look.state.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") +
            (e.optString("verdict").ifEmpty { e.optString("detail") }.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "")
    }
    return lines.joinToString("\n")
}
