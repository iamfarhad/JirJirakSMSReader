package ir.jirjirak.sms.ui

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import ir.jirjirak.sms.MainActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.jirjirak.sms.Beat
import ir.jirjirak.sms.BuildConfig
import ir.jirjirak.sms.KeepAliveService
import ir.jirjirak.sms.SemVer
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.SyncJob
import ir.jirjirak.sms.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «بیشتر»: the update, Jirjirak's places, this connection, and the way out. */
@Composable
fun MoreTab(store: Store, now: Long, onNavigate: (tab: Int, filter: Int) -> Unit) {
    watchStore()
    ShopProfileCard(store, now)
    QuickActions(store, now, onNavigate)
    SubscriptionCard(store, now) {}
    SecurityCard(store)
    UpdateCard(store, now)
    AppearanceCard(store)
    JirjirakCard()
    ConnectionCard(store)
    VersionLine()
}

// ── the shop, at a glance ───────────────────────────────────────────────

/** Whose phone this is working for — names only: no pairing code, no key, ever. */
@Composable
private fun ShopProfileCard(store: Store, now: Long) {
    val alive = store.lastPing > 0 && now - store.lastPing < 30 * 60_000L
    AppCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconChip(Icons.Filled.Home, Tone.good, 52)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(store.shop ?: "فروشگاه", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text(store.server?.let { Persian.latin(it) } ?: "—", color = Brand.Muted, fontSize = 12.sp, maxLines = 1)
            }
            StatusBadge(if (alive) "متصل" else "بی‌خبر", if (alive) Tone.good else Tone.warn)
        }
        Text(
            "آخرین ارتباط با ربات: " + Persian.ago(store.lastPing, now) + " · آخرین پیامک: " + Persian.ago(store.lastSent, now),
            color = Brand.Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun QuickActions(store: Store, now: Long, onNavigate: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AppCard {
        SectionHeader("دسترسی سریع")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QuickTile("رسیدهای منتظر", Icons.Filled.DateRange, Tone.warn, Modifier.weight(1f)) { onNavigate(MainActivity.TAB_RECEIPTS, 1) }
            QuickTile("مغایرت‌ها", Icons.Filled.Warning, Tone.bad, Modifier.weight(1f)) { onNavigate(MainActivity.TAB_RECEIPTS, 3) }
            QuickTile("گزارش‌ها", Icons.Filled.Info, Tone.info, Modifier.weight(1f)) { onNavigate(MainActivity.TAB_REPORT, 0) }
            QuickTile(if (copied) "کپی شد ✓" else "گزارش پشتیبانی", Icons.Filled.Share, Tone.muted, Modifier.weight(1f)) {
                clipboard.setText(AnnotatedString(diagnostics(context, store, now)))
                copied = true
            }
        }
    }
}

@Composable
private fun QuickTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, Brand.Line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconChip(icon, tone, 36)
        Text(label, color = Brand.Ink, fontSize = 11.sp, maxLines = 2, lineHeight = 14.sp, textAlign = TextAlign.Center)
    }
}

// ── keys on this phone ──────────────────────────────────────────────────

/**
 * What this phone can do on the shop's behalf. Says whether a key is held — never the key itself,
 * which would turn a screenshot sent to support into the power to approve payments.
 */
@Composable
private fun SecurityCard(store: Store) {
    watchStore()
    var forgetting by remember { mutableStateOf(false) }
    AppCard {
        SectionHeader("امنیت و دسترسی‌ها")
        SecurityRow("ارسال پیامک‌های بانکی", if (store.endpoint != null) "فعال" else "وصل نیست", store.endpoint != null)
        SecurityRow(
            "تأیید و رد رسید از اپ",
            when {
                store.manageKey != null -> "کد مدیریت ثبت شده"
                store.manageKeyRevoked -> "کد باطل شده"
                else -> "ثبت نشده"
            },
            store.manageKey != null,
        )
        Text(
            "کدها روی همین گوشی می‌مانند و جایی نمایش داده نمی‌شوند. اگر گوشی را به کسی می‌دهید، کد مدیریت را پاک کنید؛ ارسال پیامک‌ها ادامه پیدا می‌کند.",
            color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
        if (store.manageKey != null) {
            OutlinedButton(
                onClick = { forgetting = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Bad), border = dangerBorder(),
            ) { Text("پاک کردن کد مدیریت از این گوشی") }
        }
    }
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text("پاک کردن کد مدیریت") },
            text = { Text("بعد از این، رسیدها را از این گوشی نمی‌توان تأیید یا رد کرد تا دوباره کد را از ربات بگیرید. ارسال پیامک‌ها قطع نمی‌شود.", lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = {
                    store.manageKey = null
                    store.manageKeyRevoked = false
                    store.event("system", "info", "کد مدیریت رسیدها از این گوشی پاک شد")
                    forgetting = false
                }) { Text("پاک کن", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun SecurityRow(label: String, state: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.Lock else Icons.Filled.Warning, contentDescription = null, tint = if (ok) Brand.Good else Brand.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Brand.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StatusBadge(state, if (ok) Tone.good else Tone.muted)
    }
}

// ── updates ─────────────────────────────────────────────────────────────

@Composable
private fun UpdateCard(store: Store, now: Long) {
    watchStore()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = Updates.available(context)
    val installable = latest?.let { Updates.installable(context) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    // The answer of «بررسی کن», said where it cannot be missed: a grey line at the foot of the card
    // changed so little that sellers reported the button as doing nothing.
    var checkOutcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var needsPermission by remember { mutableStateOf(false) }

    // While a download runs, read its progress off DownloadManager every half second.
    LaunchedEffect(store.downloadId) {
        while (store.downloadId >= 0) {
            progress = Updates.progress(context)
            delay(500)
        }
        progress = null
    }

    Section(
        "به‌روزرسانی", Icons.Filled.Refresh,
        trailing = {
            if (latest != null) Pill("نسخهٔ " + Persian.digits(latest.version) + " آماده", Brand.GreenSoft, Brand.GreenDark)
            else Pill("به‌روز", Brand.GoodSoft, Brand.Good)
        },
    ) {
        Text(
            "نسخهٔ نصب‌شده: " + Persian.sender(BuildConfig.VERSION_NAME) +
                (latest?.let { " · نسخهٔ تازه: " + Persian.sender(it.version) } ?: ""),
            color = Brand.Ink, fontSize = 14.sp,
        )
        when {
            latest == null -> Text("برنامه خودش هر چند ساعت یک بار نسخهٔ تازه را می‌بیند، در پس‌زمینه دانلود می‌کند و با اعلان خبر می‌دهد.", color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
            installable != null -> {
                Text("نسخهٔ تازه دانلود شده است. با زدن «نصب»، اندروید تأیید شما را می‌خواهد؛ تنظیمات و شماره‌هایتان می‌ماند.", color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
                if (needsPermission) {
                    Notice("اندروید برای نصب از این برنامه اجازه می‌خواهد. در صفحه‌ای که باز شد «اجازه» را روشن کنید و برگردید، بعد دوباره «نصب» را بزنید.", Brand.WarnSoft, Brand.Warn)
                }
                Button(
                    onClick = {
                        if (Updates.canInstall(context)) {
                            needsPermission = false
                            Updates.install(context, installable)
                        } else {
                            needsPermission = true
                            Updates.openInstallPermission(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = MaterialTheme.shapes.medium,
                ) { Text("نصب نسخهٔ " + Persian.digits(latest.version), fontSize = 15.sp) }
            }
            progress != null -> {
                val (so, total) = progress!!
                LinearProgressIndicator(
                    progress = { if (total > 0) so.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = Brand.Green, trackColor = Brand.Line,
                )
                Text(
                    "در حال دانلود… " + (if (total > 0) Persian.number((so * 100 / total).toInt()) + "٪" else Persian.number((so / 1024).toInt()) + " کیلوبایت"),
                    color = Brand.Muted, fontSize = 13.sp,
                )
            }
            else -> {
                Text("نسخهٔ تازه‌ای آمده است. دانلود آن حدود یک مگابایت است و بعد از آن فقط «نصب» را می‌زنید.", color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (!Updates.download(context, latest)) result = "دانلود شروع نشد؛ از مرورگر دریافت کنید."
                        },
                        modifier = Modifier.weight(1f).height(50.dp), shape = MaterialTheme.shapes.medium,
                    ) { Text("دانلود", fontSize = 15.sp) }
                    OutlinedButton(onClick = { Updates.open(context, latest.url) }, modifier = Modifier.weight(1f).height(50.dp), shape = MaterialTheme.shapes.medium) {
                        Text("با مرورگر")
                    }
                }
            }
        }
        checkOutcome?.let { (ok, text) -> Notice(text, if (ok) Brand.GoodSoft else Brand.BadSoft, if (ok) Brand.Good else Brand.Bad) }
        result?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
        HorizontalDivider(color = Brand.Line)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("آخرین بررسی: " + Persian.ago(store.lastUpdateCheck, now), color = Brand.Muted, fontSize = 12.sp)
            }
            if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Brand.Green)
            OutlinedButton(enabled = !checking, shape = MaterialTheme.shapes.medium, onClick = {
                checking = true
                checkOutcome = null
                scope.launch {
                    val found = withContext(Dispatchers.IO) { Updates.check(context.applicationContext) }
                    checking = false
                    checkOutcome = when {
                        found == null -> false to "بررسی ممکن نشد: نه ربات جواب داد و نه گیت‌هاب. اینترنت گوشی را بررسی کنید و دوباره بزنید."
                        SemVer.isNewer(found.version, BuildConfig.VERSION_NAME) -> true to "نسخهٔ تازه پیدا شد: " + Persian.sender(found.version) + " — دکمهٔ «دانلود» بالای همین کارت است."
                        else -> true to "✅ نسخهٔ شما (" + Persian.sender(BuildConfig.VERSION_NAME.substringBefore('-')) + ") آخرین نسخه است."
                    }
                }
            }) { Text(if (checking) "در حال بررسی…" else "بررسی به‌روزرسانی", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── look and the home-screen widget ─────────────────────────────────────

/**
 * «ظاهر»: light, dark or the phone's own — and the widget, placed from here. Both existed in 1.1
 * but only through the phone's settings and its home-screen long-press, and a seller who looked for
 * them in the app found nothing.
 */
@Composable
private fun AppearanceCard(store: Store) {
    watchStore()
    val context = LocalContext.current
    var widgetNote by remember { mutableStateOf<String?>(null) }
    Section("ظاهر و ویجت", Icons.Filled.Info, Brand.Green) {
        Text("رنگ برنامه", color = Brand.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("system" to "مثل گوشی", "light" to "روشن", "dark" to "تاریک").forEach { (key, label) ->
                FilterChip(
                    selected = store.theme == key,
                    onClick = { store.theme = key },
                    label = { Text(label, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.GreenSoft, selectedLabelColor = Brand.GreenDark),
                )
            }
        }
        HorizontalDivider(color = Brand.Line)
        Text("ویجت صفحهٔ اصلی", color = Brand.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("وضعیت ارسال و تعداد پیامک‌های امروز را بدون باز کردن برنامه، روی صفحهٔ اصلی گوشی ببینید.", color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedButton(
            onClick = {
                widgetNote = if (ir.jirjirak.sms.StatusWidget.requestPin(context)) {
                    "گوشی می‌پرسد ویجت کجا قرار بگیرد؛ «افزودن» را بزنید."
                } else {
                    "این گوشی افزودن مستقیم را پشتیبانی نمی‌کند: روی یک جای خالی صفحهٔ اصلی انگشت را نگه دارید ← «ابزارک‌ها» یا «Widgets» ← «جیرجیرک پیامک» را پیدا کنید و روی صفحه بکشید."
                }
            },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        ) { Text("افزودن ویجت به صفحهٔ اصلی") }
        widgetNote?.let { Text(it, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp) }
    }
}

// ── this connection ─────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(store: Store) {
    watchStore()
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    Section("این اتصال", Icons.Filled.Info, Brand.Muted) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoLine("فروشگاه", store.shop ?: "—")
            InfoLine("سرور ربات", store.server?.let { Persian.latin(it) } ?: "—")
            InfoLine("گوشی", Persian.latin(Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE))
            InfoLine("شماره‌های مجاز", store.senders.takeIf { it.isNotEmpty() }?.joinToString("، ") { Persian.sender(it) } ?: "هیچ")
        }
        HorizontalDivider(color = Brand.Line)
        Text("قطع اتصال، فرستادن پیامک به این فروشگاه را متوقف می‌کند. برای عوض کردن گوشی یا وصل شدن به فروشگاه دیگر لازم است.", color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedButton(
            onClick = { asking = true },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Bad),
            border = dangerBorder(),
        ) { Text("قطع اتصال از این فروشگاه") }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("قطع اتصال") },
            text = { Text("از این به بعد هیچ پیامکی به ربات این فروشگاه فرستاده نمی‌شود. شماره‌های مجاز و گزارش می‌مانند.") },
            confirmButton = {
                TextButton(onClick = {
                    // Everything that wakes the phone for this app stops with the pairing.
                    store.event("system", "info", "اتصال از فروشگاه قطع شد")
                    store.unpair()
                    ir.jirjirak.sms.Alerts.clear(context)
                    ir.jirjirak.sms.StatusWidget.refresh(context)
                    KeepAliveService.stop(context)
                    Beat.cancel(context)
                    SyncJob.cancelAll(context)
                    asking = false
                }) { Text("قطع اتصال", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Brand.Muted, fontSize = 13.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = Brand.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.6f))
    }
}
