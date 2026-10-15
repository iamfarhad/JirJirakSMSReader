package ir.jirjirak.sms.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.jirjirak.sms.Autostart
import ir.jirjirak.sms.Beat
import ir.jirjirak.sms.Inbox
import ir.jirjirak.sms.KeepAliveService
import ir.jirjirak.sms.Net
import ir.jirjirak.sms.Rules
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.SyncJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── pairing ─────────────────────────────────────────────────────────────

@Composable
fun PairScreen(store: Store) {
    watchStore()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(raw: String?) {
        val endpoint = Rules.parseCode(raw)
        if (endpoint == null) {
            error = "این کد اتصال جیرجیرک نیست. از ربات «📋 کپی کد اتصال» را بزنید و دوباره امتحان کنید."
            return
        }
        busy = true
        error = null
        scope.launch {
            // Ask the CANDIDATE first and save it only on success: saved first, the screen switched
            // to «paired» mid-request, which cancelled this very coroutine — a failed ping then left
            // a dead endpoint saved, and a good one never started the service.
            val result = withContext(Dispatchers.IO) { Net.ping(context.applicationContext, endpoint) }
            busy = false
            when (result) {
                Net.Ping.OK -> {
                    store.endpoint = endpoint
                    store.event("system", "ok", "به فروشگاه وصل شد", store.shop?.let { "فروشگاه $it" })
                    val app = context.applicationContext
                    SyncJob.schedulePeriodic(app)
                    Beat.schedule(app)
                    KeepAliveService.start(app)
                    // The reply carried the shop's details; keep them now that the pairing is real.
                    withContext(Dispatchers.IO) { Net.ping(app) }
                }
                Net.Ping.REVOKED -> error = "این کد اتصال دیگر معتبر نیست؛ احتمالاً آدرس در ربات عوض شده. «کپی کد اتصال» تازه را از ربات بزنید."
                Net.Ping.UNREACHABLE -> error = "به ربات وصل نشد: " + (Net.lastPingReason ?: "اینترنت گوشی را بررسی کنید") + "."
            }
        }
    }

    Section("اتصال به فروشگاه", Icons.Filled.Lock) {
        Steps(
            "در ربات فروشگاه بروید به: مدیریت ربات ← پرداخت‌ها ← تأیید خودکار رسید",
            "دکمهٔ «📋 کپی کد اتصال» را بزنید",
            "به همین برنامه برگردید و «چسباندن کد اتصال» را بزنید",
        )
        Button(
            onClick = { pair(clipboard.getText()?.text) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        ) {
            Text(if (busy) "در حال اتصال…" else "📋 چسباندن کد اتصال", fontSize = 16.sp)
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("یا کد را اینجا بچسبانید") },
            singleLine = true,
            // The code carries the shop's secret: no autocorrect, no keyboard learning it.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (typed.isNotBlank()) {
            OutlinedButton(onClick = { pair(typed) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("اتصال با این کد")
            }
        }
        error?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
    }
    JirjirakCard()
    VersionLine()
}

@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Brand.GreenSoft), contentAlignment = Alignment.Center) {
                Text(Persian.number(i + 1), color = Brand.GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(s, color = Brand.Ink, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

// ── status ──────────────────────────────────────────────────────────────

// ── the shop's own subscription, as the bot reported it ─────────────────

@Composable
internal fun SubscriptionCard(store: Store, now: Long, onMore: () -> Unit) {
    watchStore()
    val info = store.shopInfo
    val sub = info.optJSONObject("subscription") ?: return
    val stats = info.optJSONObject("stats")
    val context = LocalContext.current
    val expires = sub.optLong("expires_at", 0) * 1000
    val days = sub.optInt("days_left", 0)
    val state = sub.optString("state")
    val (pillBg, pillFg, pillText) = when (state) {
        "expired" -> Triple(Brand.BadSoft, Brand.Bad, "منقضی شده")
        "grace" -> Triple(Brand.WarnSoft, Brand.Warn, "در مهلت تسویه")
        "unlimited" -> Triple(Brand.GreenSoft, Brand.GreenDark, "بدون انقضا")
        else -> Triple(if (days <= 5) Brand.WarnSoft else Brand.GoodSoft, if (days <= 5) Brand.Warn else Brand.Good, "فعال")
    }
    Section("اشتراک جیرجیرک", Icons.Filled.Star, Brand.Warn, trailing = { Pill(pillText, pillBg, pillFg) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("سطح", sub.optString("label").ifEmpty { "—" }, Modifier.weight(1f))
            StatTile(
                "انقضا",
                when {
                    state == "unlimited" || expires <= 0 -> "ندارد"
                    expires < now -> Persian.date(expires)
                    else -> Persian.number(days) + " روز دیگر"
                },
                Modifier.weight(1f),
                if (state == "expired" || (state == "active" && days <= 5)) Brand.Warn else Brand.Ink,
            )
            if (stats != null) {
                StatTile("رسید در انتظار", Persian.number(stats.optInt("pending_receipts")), Modifier.weight(1f), if (stats.optInt("pending_receipts") > 0) Brand.Warn else Brand.Ink)
            }
        }
        if (expires > 0 && state != "unlimited") {
            Text("تاریخ انقضا: " + Persian.date(expires), color = Brand.Muted, fontSize = 12.sp)
        }
        if (state == "expired" || state == "grace" || (state == "active" && days <= 5)) {
            Notice(
                if (state == "expired") "اشتراک فروشگاه تمام شده و ربات برای مشتری‌ها کار نمی‌کند. برای تمدید به ربات جیرجیرک بروید."
                else "اشتراک فروشگاه به‌زودی تمام می‌شود. برای تمدید به ربات جیرجیرک بروید.",
                Brand.WarnSoft, Brand.Warn,
            )
            OutlinedButton(onClick = { openTelegram(context, "JirjirakPremiumBot") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text("تمدید اشتراک در ربات جیرجیرک")
            }
        }
        info.optString("bot").takeIf { it.isNotEmpty() }?.let { bot ->
            LinkRow("ربات فروشگاه شما", "@" + bot.removePrefix("@"), onClick = { openTelegram(context, bot.removePrefix("@")) })
        }
    }
}

// ── what the phone needs ────────────────────────────────────────────────

@SuppressLint("BatteryLife")
@Composable
internal fun ChecklistCard(store: Store, resumed: Int) {
    watchStore()
    val context = LocalContext.current
    // Re-read on every return to the app: the permission was granted in Settings, the battery
    // exemption in a dialog — none of it writes to the Store.
    @Suppress("UNUSED_VARIABLE") val again = resumed
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    val batteryFree = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    val notificationsOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    // Asked once and still refused: Android will not show the dialog again, and on recent versions a
    // sideloaded app's SMS permission sits behind «Allow restricted settings» in App info.
    var smsAsked by remember { mutableStateOf(false) }
    var notificationsAsked by remember { mutableStateOf(false) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsAsked = true; Store.revision.intValue++ }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAsked = true
        KeepAliveService.start(context)
        Store.revision.intValue++
    }
    // Autostart has no API to read back: nothing on the phone says whether the seller switched it
    // on. So the seller is asked on the way back from that screen, and the answer is remembered.
    var autostartOpenedAt by remember { mutableIntStateOf(-1) }
    var autostartAsking by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        if (autostartOpenedAt in 0 until resumed) {
            autostartOpenedAt = -1
            autostartAsking = true
        }
    }
    val sendersOk = store.senders.isNotEmpty()
    val autostartOk = !Autostart.needed || store.autostartConfirmed
    val steps = listOf(smsGranted, notificationsOk, batteryFree, autostartOk, sendersOk)
    val done = steps.count { it }
    val allDone = done == steps.size
    var open by remember(allDone) { mutableStateOf(!allDone) }

    Section(
        "آماده‌سازی گوشی", Icons.Filled.Settings,
        trailing = {
            if (allDone) {
                TextButton(onClick = { open = !open }) { Text(if (open) "بستن" else "جزئیات") }
            } else {
                Pill(Persian.number(done) + " از " + Persian.number(steps.size), Brand.GreenSoft, Brand.GreenDark)
            }
        },
    ) {
        if (allDone && !open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Brand.GoodSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Brand.Good, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("همه‌چیز آماده است. گوشی پیامک بانک را می‌شنود و به ربات می‌فرستد.", color = Brand.Ink, fontSize = 13.sp, lineHeight = 20.sp)
            }
            return@Section
        }
        if (!allDone) {
            LinearProgressIndicator(
                progress = { done.toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = Brand.Green, trackColor = Brand.Line,
            )
        }
        CheckRow(
            state = if (smsGranted) Step.Done else if (smsAsked) Step.Problem else Step.Todo,
            index = 1,
            title = "اجازهٔ دریافت پیامک",
            hint = if (smsAsked && !smsGranted) {
                "اجازه داده نشد. در صفحه‌ای که باز می‌شود «پیامک» را مجاز کنید. اگر خاموش و غیرقابل تغییر بود، از منوی ⋮ بالای همان صفحه «Allow restricted settings» را بزنید و دوباره بیایید."
            } else {
                "فقط پیامک‌های تازه، و فقط از شماره‌هایی که خودتان مجاز می‌کنید، به ربات می‌رود."
            },
            action = if (smsAsked) "تنظیمات" else "اجازه بده",
        ) {
            if (smsAsked) openAppSettings(context) else askSms.launch(Manifest.permission.RECEIVE_SMS)
        }
        if (smsAsked && !smsGranted) RestrictedGuide()
        HorizontalDivider(color = Brand.Line)
        CheckRow(
            state = if (notificationsOk) Step.Done else if (notificationsAsked) Step.Problem else Step.Todo,
            index = 2,
            title = "اعلان «فعال است»",
            hint = "یک اعلان کوچک و ثابت که نمی‌گذارد گوشی برنامه را ببندد. بدون آن، برنامه بعد از چند دقیقه می‌خوابد.",
            action = if (notificationsAsked) "تنظیمات" else "اجازه بده",
        ) {
            if (notificationsAsked) openAppSettings(context)
            else if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        HorizontalDivider(color = Brand.Line)
        CheckRow(
            state = if (batteryFree) Step.Done else Step.Todo,
            index = 3,
            title = "کار در پس‌زمینه",
            hint = "تا گوشی برای صرفه‌جویی باتری برنامه را نخواباند و پیامک‌ها جا نمانند. در پنجره‌ای که باز می‌شود «اجازه» را بزنید.",
            action = "تنظیم",
        ) {
            val direct = runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
            }.isSuccess
            if (!direct) runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        if (Autostart.needed) {
            HorizontalDivider(color = Brand.Line)
            CheckRow(
                state = if (store.autostartConfirmed) Step.Done else Step.Todo,
                index = 4,
                title = "اجرای خودکار (" + Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + ")",
                hint = "در این گوشی‌ها «اجرای خودکار» یا «Autostart» برای برنامه‌های تازه خاموش است. در صفحه‌ای که باز می‌شود، آن را برای «جیرجیرک پیامک» روشن کنید.",
                action = "باز کن",
            ) {
                autostartOpenedAt = resumed
                Autostart.open(context)
            }
        }
        HorizontalDivider(color = Brand.Line)
        CheckRow(
            state = if (sendersOk) Step.Done else Step.Todo,
            index = 5,
            title = "شمارهٔ پیامک بانک",
            hint = "تا وقتی دست‌کم یک شماره مجاز نکنید، هیچ پیامکی از گوشی خارج نمی‌شود. در کارت بعدی اضافه کنید.",
            action = null,
        ) {}
    }

    if (autostartAsking) {
        AlertDialog(
            onDismissRequest = { autostartAsking = false },
            title = { Text("اجرای خودکار") },
            text = { Text("«اجرای خودکار» را برای «جیرجیرک پیامک» روشن کردید؟", lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = { store.autostartConfirmed = true; autostartAsking = false }) { Text("بله، روشن شد") }
            },
            dismissButton = { TextButton(onClick = { autostartAsking = false }) { Text("نه هنوز") } },
        )
    }
}

/**
 * Android 13+ refuses the SMS permission to an app installed from a file until «Allow restricted
 * settings» — and says only «App was denied access», in English. Most sellers stopped there. The
 * way through, in their words, with the one button that gets them to the right screen.
 */
@Composable
private fun RestrictedGuide() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Brand.WarnSoft).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("اندروید اجازهٔ پیامک را قفل کرده — این‌طوری بازش کنید:", color = Brand.Warn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        listOf(
            "دکمهٔ «باز کردن تنظیمات برنامه» را بزنید.",
            "بالای صفحه، منوی ⋮ (سه نقطه) را بزنید و «Allow restricted settings» را انتخاب کنید. اگر نبود، یک بار «Permissions ← SMS» را بزنید تا پیغام «App was denied access» بیاید و «Close» کنید؛ حالا ⋮ آن گزینه را دارد.",
            "با اثر انگشت یا رمز گوشی تأیید کنید.",
            "«Permissions ← SMS ← Allow» را بزنید و به همین برنامه برگردید؛ تیک سبز می‌خورد.",
        ).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text(Persian.number(i + 1) + ".", color = Brand.Warn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(step, color = Brand.Ink, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        Button(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text("باز کردن تنظیمات برنامه")
        }
    }
}

/**
 * «🔎 پیدا کردن خودکار»: read the inbox once (asking for READ_SMS if needed), and if exactly one
 * sender looks like a bank, ask «is this yours?» — the commonest setup becomes one tap. Several:
 * the picker. None: say so. Nothing read here leaves the phone.
 */
@Composable
private fun FindBankSender(store: Store, onSeveral: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<Inbox.Sender?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scope.launch {
            val bankish = withContext(Dispatchers.IO) { Inbox.group(Inbox.read(context), store.senders) }.filter { it.bankish }
            when (bankish.size) {
                0 -> message = "در پیامک‌های گوشی پیامک بانکی پیدا نشد. اگر سیم‌کارت حساب در گوشی دیگری است، اپ را آنجا نصب کنید؛ وگرنه «نوشتن شماره» را بزنید."
                1 -> found = bankish.first()
                else -> onSeveral()
            }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else message = "بدون اجازهٔ خواندن پیامک، پیدا کردن خودکار ممکن نیست؛ «نوشتن شماره» را بزنید."
    }

    OutlinedButton(
        onClick = {
            message = null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) scan()
            else ask.launch(Manifest.permission.READ_SMS)
        },
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
    ) { Text("🔎 پیدا کردن خودکار شمارهٔ بانک") }
    message?.let { Text(it, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp) }

    found?.let { s ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("شمارهٔ بانک شما؟") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("شمارهٔ پیامک بانک شما «" + Persian.sender(s.from) + "» است؟", lineHeight = 22.sp)
                    s.preview?.let { Text(Persian.digits(it), color = Brand.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.addSender(s.from)
                    store.event("system", "info", "شمارهٔ " + s.from + " مجاز شد")
                    found = null
                }) { Text("بله، مجاز کن") }
            },
            dismissButton = { TextButton(onClick = { found = null; onSeveral() }) { Text("نه، از فهرست انتخاب می‌کنم") } },
        )
    }
}

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
    }
}

// ── the seller's own list of bank numbers ───────────────────────────────

@Composable
internal fun SendersCard(store: Store) {
    watchStore()
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val senders = store.senders
    val suggestions = store.suggestions

    Section("شماره‌های مجاز بانک", Icons.Filled.Phone, trailing = {
        if (senders.isNotEmpty()) Pill(Persian.number(senders.size) + " شماره", Brand.GreenSoft, Brand.GreenDark)
    }) {
        Text(
            "فقط پیامک این شماره‌ها به ربات فرستاده می‌شود. پیامک رمز یکبار مصرف و کد تأیید حتی از این شماره‌ها هم فرستاده نمی‌شود.",
            color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp,
        )
        if (senders.isEmpty()) {
            Notice("هنوز شماره‌ای اضافه نکرده‌اید. «🔎 پیدا کردن خودکار» پیامک‌های گوشی را می‌گردد و شمارهٔ بانک را پیشنهاد می‌دهد.", Brand.WarnSoft, Brand.Warn)
            FindBankSender(store, onSeveral = { picking = true })
        }
        senders.forEach { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Brand.Canvas).padding(start = 12.dp),
            ) {
                Text("🏦 " + Persian.sender(s), modifier = Modifier.weight(1f), color = Brand.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { removing = s }) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = Brand.Bad)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { picking = true }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                Text("انتخاب از پیامک‌ها")
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("نوشتن شماره")
            }
        }

        // Senders whose SMS looked like a bank's — offered, never added on their own.
        if (suggestions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Brand.GreenSoft.copy(alpha = 0.5f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("پیشنهاد", color = Brand.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("این شماره‌ها پیامکی شبیه پیامک بانک فرستاده‌اند ولی مجاز نیستند؛ تا مجازشان نکنید چیزی فرستاده نمی‌شود.", color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                suggestions.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(Persian.sender(s), modifier = Modifier.weight(1f), color = Brand.Ink, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { store.addSender(s); store.event("system", "info", "شمارهٔ " + s + " مجاز شد") }) { Text("مجاز کن", fontWeight = FontWeight.Bold) }
                        IconButton(onClick = { store.dismissSuggestion(s) }) {
                            Icon(Icons.Filled.Close, contentDescription = "نادیده", tint = Brand.Muted)
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        PickSenderDialog(store, onDone = { value ->
            if (value != null) { store.addSender(value); store.event("system", "info", "شمارهٔ " + value + " مجاز شد") }
            picking = false
        })
    }
    if (adding) {
        AddSenderDialog(onDone = { value ->
            if (value != null) { store.addSender(value); store.event("system", "info", "شمارهٔ " + value + " مجاز شد") }
            adding = false
        })
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("حذف شماره") },
            text = { Text("پیامک‌های «" + Persian.sender(s) + "» دیگر به ربات فرستاده نمی‌شود.") },
            confirmButton = {
                TextButton(onClick = { store.removeSender(s); store.event("system", "info", "شمارهٔ " + s + " از فهرست مجاز حذف شد"); removing = null }) { Text("حذف", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("انصراف") } },
        )
    }
}

/**
 * The phone's own SMS senders, bank-looking ones first. Asks for READ_SMS the first time; nothing
 * read here leaves the phone, and a password SMS shows no preview.
 */
@Composable
private fun PickSenderDialog(store: Store, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var senders by remember { mutableStateOf<List<Inbox.Sender>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(granted) {
        if (!granted) {
            if (!asked) ask.launch(Manifest.permission.READ_SMS)
        } else {
            senders = withContext(Dispatchers.IO) { Inbox.group(Inbox.read(context), store.senders) }
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("انتخاب از پیامک‌ها") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !granted -> {
                        Text("برای نشان دادن فرستنده‌های پیامک، اجازهٔ خواندن پیامک لازم است. این فهرست فقط روی گوشی شماست و به جایی فرستاده نمی‌شود.", fontSize = 13.sp, lineHeight = 20.sp, color = Brand.Muted)
                        if (asked) {
                            OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("باز کردن تنظیمات برنامه") }
                        }
                    }
                    senders == null -> Text("در حال خواندن…", color = Brand.Muted)
                    senders!!.isEmpty() -> Text("فرستندهٔ تازه‌ای پیدا نشد.", color = Brand.Muted)
                    else -> senders!!.take(40).forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .background(if (s.bankish) Brand.GreenSoft else Brand.Canvas).padding(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text((if (s.bankish) "🏦 " else "") + Persian.sender(s.from), fontWeight = FontWeight.SemiBold, color = Brand.Ink, fontSize = 14.sp)
                                Text(
                                    s.preview?.let { Persian.digits(it) } ?: "پیامک رمز — نمایش داده نمی‌شود",
                                    color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onDone(s.from) }) { Text("افزودن") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(null) }) { Text("بستن") } },
    )
}

@Composable
private fun AddSenderDialog(onDone: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("افزودن شمارهٔ بانک") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "شماره یا نام فرستندهٔ پیامک بانک را دقیقاً همان‌طور که در پیامک‌ها می‌بینید وارد کنید؛ مثل 9999 یا +98200 یا BankMellat.",
                    fontSize = 13.sp, lineHeight = 20.sp, color = Brand.Muted,
                )
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(value.trim().ifEmpty { null }) }, enabled = value.isNotBlank()) { Text("افزودن") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("انصراف") } },
    )
}

// ── the last few things that happened ───────────────────────────────────

@Composable
internal fun RecentEvents(store: Store, onReport: () -> Unit) {
    watchStore()
    val events = store.events()
    var expanded by remember { mutableStateOf<String?>(null) }
    Section("آخرین رویدادها", Icons.AutoMirrored.Filled.List, trailing = {
        TextButton(onClick = onReport) { Text("گزارش کامل") }
    }) {
        if (events.isEmpty()) {
            Text("هنوز چیزی ثبت نشده است. با اولین پیامک بانک، اینجا نشان داده می‌شود که چه شد.", color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
        events.take(5).forEach { e ->
            val id = e.optString("id")
            EventRow(e, expanded == id) { expanded = if (expanded == id) null else id }
        }
    }
}

// ── shared bits ─────────────────────────────────────────────────────────

/** Jirjirak's own places: the news channel, the bot that makes shops, the app's source. */
@Composable
fun JirjirakCard() {
    val context = LocalContext.current
    Section("جیرجیرک", Icons.Filled.Star, Brand.Warn) {
        LinkRow("کانال اخبار و آموزش", "@JirjirakPremium — تازه‌های ربات و راهنماها", onClick = { openTelegram(context, "JirjirakPremium") })
        HorizontalDivider(color = Brand.Line)
        LinkRow("ربات جیرجیرک", "@JirjirakPremiumBot — ساخت ربات فروش با ۳ روز تست رایگان", onClick = { openTelegram(context, "JirjirakPremiumBot") })
        HorizontalDivider(color = Brand.Line)
        LinkRow("راهنما و سورس برنامه", "github.com/iamfarhad/JirJirakSMSReader", onClick = {
            ir.jirjirak.sms.Updates.open(context, "https://github.com/iamfarhad/JirJirakSMSReader")
        })
    }
}

@Composable
fun VersionLine() {
    Text(
        "جیرجیرک پیامک · نسخهٔ " + Persian.sender(ir.jirjirak.sms.BuildConfig.VERSION_NAME),
        color = Brand.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** Telegram if it is installed, the web otherwise. */
fun openTelegram(context: android.content.Context, username: String) {
    val tg = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$username")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(tg) }.isFailure) {
        ir.jirjirak.sms.Updates.open(context, "https://t.me/$username")
    }
}
