package ir.jirjirak.sms.ui

import android.os.Build
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
fun MoreTab(store: Store, now: Long) {
    watchStore()
    UpdateCard(store, now)
    JirjirakCard()
    ConnectionCard(store)
    VersionLine()
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
        HorizontalDivider(color = Brand.Line)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(result ?: ("آخرین بررسی: " + Persian.ago(store.lastUpdateCheck, now)), color = Brand.Muted, fontSize = 12.sp)
            }
            TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    val found = withContext(Dispatchers.IO) { Updates.check(context.applicationContext) }
                    checking = false
                    result = when {
                        found == null -> "بررسی ممکن نشد؛ اینترنت را بررسی کنید یا کمی بعد دوباره بزنید."
                        SemVer.isNewer(found.version, BuildConfig.VERSION_NAME) -> "نسخهٔ " + Persian.digits(found.version) + " آماده است."
                        else -> "نسخهٔ شما به‌روز است."
                    }
                }
            }) { Text(if (checking) "در حال بررسی…" else "بررسی کن", fontWeight = FontWeight.SemiBold) }
        }
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
