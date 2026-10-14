package ir.jirjirak.sms.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import ir.jirjirak.sms.Store
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

    val events = when (filter) {
        1 -> all.filter { it.optString("kind") == "sms" }
        2 -> all.filter { it.optString("kind") == "ignored" }
        3 -> all.filter { it.optString("status") in setOf("warn", "failed", "retry") }
        else -> all
    }

    Section("گزارش کارکرد", Icons.AutoMirrored.Filled.List, trailing = {
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
        "آخرین ارتباط: " + Persian.ago(store.lastPing, now) + " · آخرین پیامک: " + Persian.ago(store.lastSent, now) + " · در صف: " + store.queue().size + " · خطای آخر: " + (store.lastError ?: "—"),
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
