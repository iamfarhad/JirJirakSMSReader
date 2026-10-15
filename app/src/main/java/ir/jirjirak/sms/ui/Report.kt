package ir.jirjirak.sms.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.jirjirak.sms.BuildConfig
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** «گزارش»: every SMS and what became of it, every hiccup, by day — and a copy for support. */
@Composable
fun ReportTab(store: Store, now: Long) {
    watchStore()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val all = store.events()
    var filter by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(40) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    ReportAnalytics(store)

    val events = when (filter) {
        1 -> all.filter { it.optString("kind") == "sms" }
        2 -> all.filter { it.optString("kind") == "ignored" }
        3 -> all.filter { it.optString("status") in setOf("warn", "failed", "retry") }
        else -> all
    }

    Section("گزارش فنی گوشی", Icons.AutoMirrored.Filled.List, trailing = {
        Pill(Persian.number(events.size), Brand.GreenSoft, Brand.GreenDark)
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("همه", "پیامک‌ها", "فرستاده‌نشده", "مشکل‌ها").forEachIndexed { i, label ->
                FilterChip(
                    selected = filter == i,
                    onClick = { filter = i; shown = 40 },
                    label = { Text(label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.GreenSoft, selectedLabelColor = Brand.GreenDark),
                )
            }
        }
        if (events.isEmpty()) {
            Text(
                when (filter) {
                    1 -> "هنوز پیامکی فرستاده نشده است."
                    2 -> "هیچ پیامک بانکی‌ای نادیده گرفته نشده است."
                    3 -> "مشکلی ثبت نشده است."
                    else -> "هنوز چیزی ثبت نشده است. هر پیامک بانک که برسد — فرستاده شود یا نه — اینجا با دلیلش می‌آید."
                },
                color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }
        var lastDay: String? = null
        events.take(shown).forEach { e ->
            val day = Persian.day(e.optLong("at"), now)
            if (day != lastDay) {
                lastDay = day
                Text(day, color = Brand.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                HorizontalDivider(color = Brand.Line)
            }
            val id = e.optString("id")
            EventRow(e, expanded == id) { expanded = if (expanded == id) null else id }
        }
        if (events.size > shown) {
            TextButton(onClick = { shown += 40 }, modifier = Modifier.fillMaxWidth()) { Text("نمایش بیشتر") }
        }
    }

    Section("برای پشتیبانی", Icons.AutoMirrored.Filled.List, Brand.Muted) {
        Text(
            "اگر چیزی درست کار نمی‌کند، این گزارش را کپی کنید و برای پشتیبانی جیرجیرک بفرستید: نسخه، وضعیت اجازه‌ها، آخرین ارتباط و رویدادهای اخیر — بدون متن پیامک‌ها.",
            color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp,
        )
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(diagnostics(context, store, now))); copied = true },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        ) { Text(if (copied) "کپی شد ✓" else "کپی گزارش برای پشتیبانی") }
        TextButton(onClick = { clearing = true }, modifier = Modifier.fillMaxWidth()) { Text("پاک کردن گزارش", color = Brand.Bad) }
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("پاک کردن گزارش") },
            text = { Text("همهٔ رویدادهای ثبت‌شده پاک می‌شود. پیامک‌هایی که هنوز فرستاده نشده‌اند در صف می‌مانند.") },
            confirmButton = { TextButton(onClick = { store.clearEvents(); clearing = false }) { Text("پاک کن", color = Brand.Bad) } },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("انصراف") } },
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
    var range by rememberSaveable { mutableIntStateOf(1) }
    var summary by remember(range) { mutableStateOf<Receipts.Summary?>(null) }
    var error by remember(range) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    if (store.manageKey == null) {
        AppCard {
            SectionHeader("گزارش فروش و تأیید")
            Text(
                "برای دیدن آمار رسیدها، واریزها و درصد تأیید خودکار، کد مدیریت رسیدها را در تب «رسیدها» وارد کنید.",
                color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp,
            )
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
    if (s == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { Skeleton(Modifier.weight(1f), height = 110) }
        }
        Skeleton(Modifier.fillMaxWidth(), height = 200)
        return
    }
    val m = s.metrics
    val p = s.previous
    val period = when (range) { 0 -> "دیروز"; 1 -> "هفتهٔ قبل"; else -> "ماه قبل" }
    val decided = m.autoApproved + m.manualApproved

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(
            "نرخ تأیید خودکار", Persian.number(m.autoRate) + "٪", Persian.number(m.autoApproved) + " از " + Persian.number(decided) + " رسید",
            Icons.Filled.Check, Tone.good, Modifier.weight(1f), delta(m.autoRate, p.autoRate, period), m.autoRate >= p.autoRate,
        )
        MetricCard(
            "جمع واریزها", compactToman(m.depositsTotal), Persian.number(m.depositsCount) + " واریز",
            Icons.Filled.Star, Tone.info, Modifier.weight(1f), delta(m.depositsTotal, p.depositsTotal, period), m.depositsTotal >= p.depositsTotal,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(
            "میانگین زمان تأیید خودکار", if (m.avgAutoSeconds > 0) duration(m.avgAutoSeconds) else "—", "از ثبت فاکتور تا تأیید",
            Icons.Filled.DateRange, Tone.info, Modifier.weight(1f),
        )
        MetricCard(
            "رد شده / مغایرت", Persian.number(m.rejected) + " / " + Persian.number(m.unmatched), "رسید رد شده / واریز بی‌فاکتور",
            Icons.Filled.Info, if (m.rejected + m.unmatched > 0) Tone.warn else Tone.muted, Modifier.weight(1f),
        )
    }

    // Day by day — only meaningful over more than one day.
    if (s.series.size > 1) {
        AppCard {
            SectionHeader("روند تأییدها")
            LineChart(s.series.map { it.auto.toFloat() }, Brand.Green, second = s.series.map { it.manual.toFloat() }, secondColor = Brand.Blue)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend("خودکار", Brand.Green)
                Legend("دستی", Brand.Blue)
                Spacer(Modifier.weight(1f))
                Text(
                    Persian.digits(s.series.first().date.takeLast(5).replace('-', '/')) + " تا " + Persian.digits(s.series.last().date.takeLast(5).replace('-', '/')),
                    color = Brand.Muted, fontSize = 11.sp,
                )
            }
        }
    }

    AppCard {
        SectionHeader("سهم هر روش")
        if (decided + m.rejected == 0) {
            Text("در این بازه رسیدی تأیید یا رد نشده است.", color = Brand.Muted, fontSize = 13.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(listOf(m.autoApproved.toFloat() to Brand.Green, m.manualApproved.toFloat() to Brand.Blue, m.rejected.toFloat() to Brand.Bad)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Persian.number(decided + m.rejected), color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("رسید", color = Brand.Muted, fontSize = 11.sp)
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
        AppCard {
            SectionHeader("واریز به تفکیک بانک")
            val top = s.banks.maxOf { it.total }.takeIf { it > 0 } ?: 1L
            s.banks.forEach { b ->
                ProgressRow(Persian.sender(b.sender), Persian.number(b.count) + " واریز · " + compactToman(b.total), b.total.toFloat() / top, Brand.Emerald)
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Brand.Ink, fontSize = 12.sp)
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
