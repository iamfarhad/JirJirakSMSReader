package ir.jirjirak.sms.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.jirjirak.sms.Autostart
import ir.jirjirak.sms.Beat
import ir.jirjirak.sms.BuildConfig
import ir.jirjirak.sms.KeepAliveService
import ir.jirjirak.sms.Net
import ir.jirjirak.sms.R
import ir.jirjirak.sms.Inbox
import ir.jirjirak.sms.Rules
import ir.jirjirak.sms.SemVer
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.SyncJob
import ir.jirjirak.sms.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app on one screen, top to bottom in the order a seller needs it: pair with the shop,
 * grant the SMS permission, choose the bank numbers, then watch it work.
 */
@Composable
fun App(resumed: Int) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    // Bumped after every change, and every few seconds while open, so the screen re-reads the store.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick++
        }
    }
    val refresh: () -> Unit = { tick++ }
    // Handed to every card. The cards read the Store, which Compose cannot observe, and their other
    // arguments (the same Store, the same lambda) never change — so with strong skipping each card
    // was SKIPPED on every recomposition: «مجاز کن» saved the number and the list did not show it,
    // a permission granted in Settings still read ⚠️, until the app was closed and opened again.
    val rev = resumed * 1_000_000 + tick

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        LaunchedEffect(Unit) {
            // On opening, at most every 6 hours: the background beat asks too.
            if (System.currentTimeMillis() - store.lastUpdateCheck > 6 * 60 * 60 * 1000L) {
                withContext(Dispatchers.IO) { Updates.check(context.applicationContext) }
                refresh()
            }
        }
        Updates.available(context)?.let { UpdateCard(it.version) { Updates.open(context, it.url) } }
        if (store.endpoint == null) {
            PairCard(store, refresh)
        } else {
            StatusCard(store, rev)
            ChecklistCard(context, store, refresh, rev)
            SendersCard(store, refresh, rev)
            LogCard(store, rev)
            UnpairButton(context, store, refresh)
        }
        UpdateCheckRow(store, refresh, rev)
        Text(
            "نسخهٔ " + Persian.digits(BuildConfig.VERSION_NAME),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .size(56.dp)
                .shadow(10.dp, RoundedCornerShape(18.dp), spotColor = Brand.Green.copy(alpha = 0.5f))
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF22C55E), Brand.Green, Brand.GreenDark))),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_cricket), contentDescription = null, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("جیرجیرک پیامک", fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Brand.Ink)
            Text("تأیید خودکار رسید کارت‌به‌کارت", color = Brand.Muted, fontSize = 13.sp)
        }
    }
}

/** A white card on the soft canvas: a coloured icon chip and a title, then its content. */
@Composable
private fun Section(title: String, icon: ImageVector, tint: Color = Brand.Green, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, MaterialTheme.shapes.large, ambientColor = Color(0x14000000), spotColor = Color(0x1A16A34A)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Brand.Ink)
            }
            content()
        }
    }
}

// ── updates ─────────────────────────────────────────────────────────────

@Composable
private fun UpdateCard(version: String, onDownload: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Brand.GreenSoft),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, Brand.Green.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("نسخهٔ تازه آمده: " + Persian.digits(version), fontWeight = FontWeight.Bold, color = Brand.GreenDark, fontSize = 15.sp)
                Text("نسخهٔ فعلی شما " + Persian.digits(BuildConfig.VERSION_NAME.substringBefore('-')) + " است. نصب نسخهٔ تازه تنظیمات شما را پاک نمی‌کند.", color = Brand.Ink, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDownload, shape = MaterialTheme.shapes.medium) { Text("دریافت") }
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun UpdateCheckRow(store: Store, refresh: () -> Unit, rev: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(enabled = !checking, onClick = {
            checking = true
            scope.launch {
                val latest = withContext(Dispatchers.IO) { Updates.check(context.applicationContext) }
                checking = false
                result = when {
                    latest == null -> "بررسی ممکن نشد؛ اینترنت را بررسی کنید."
                    SemVer.isNewer(latest.version, BuildConfig.VERSION_NAME) -> null   // the card says it
                    else -> "نسخهٔ شما به‌روز است."
                }
                refresh()
            }
        }) { Text(if (checking) "در حال بررسی…" else "بررسی به‌روزرسانی") }
        Text(
            result ?: ("آخرین بررسی: " + Persian.ago(store.lastUpdateCheck)),
            color = Brand.Muted, fontSize = 12.sp,
        )
    }
}

// ── 1. pairing ──────────────────────────────────────────────────────────

@Composable
private fun PairCard(store: Store, refresh: () -> Unit) {
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
                    val app = context.applicationContext
                    SyncJob.schedulePeriodic(app)
                    Beat.schedule(app)
                    KeepAliveService.start(app)
                }
                Net.Ping.REVOKED -> error = "این کد اتصال دیگر معتبر نیست؛ احتمالاً آدرس در ربات عوض شده. «کپی کد اتصال» تازه را از ربات بزنید."
                Net.Ping.UNREACHABLE -> error = "به ربات وصل نشد. اینترنت گوشی را بررسی کنید و دوباره امتحان کنید."
            }
            refresh()
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
            modifier = Modifier.fillMaxWidth(),
        )
        if (typed.isNotBlank()) {
            OutlinedButton(onClick = { pair(typed) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("اتصال با این کد")
            }
        }
        error?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
    }
}

@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(Brand.GreenSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(Persian.number(i + 1), color = Brand.GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(s, color = Brand.Ink, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun Notice(text: String, background: Color, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(background).padding(12.dp),
    )
}

// ── 2. status ───────────────────────────────────────────────────────────

@Composable
@Suppress("UNUSED_PARAMETER")
private fun StatusCard(store: Store, rev: Int) {
    val ready = store.senders.isNotEmpty()
    val online = System.currentTimeMillis() - store.lastPing < 40 * 60 * 1000L
    val revoked = store.lastError == "revoked"
    val (bg, fg, title) = when {
        revoked -> Triple(Brand.BadSoft, Brand.Bad, "کد اتصال باطل شده")
        !ready -> Triple(Brand.WarnSoft, Brand.Warn, "هنوز هیچ پیامکی فرستاده نمی‌شود")
        !online -> Triple(Brand.BadSoft, Brand.Bad, "ارتباط با ربات برقرار نیست")
        else -> Triple(Brand.GoodSoft, Brand.Good, "فعال است")
    }
    // A dot that breathes while things are well — the one moving thing on the screen, so «it is
    // working» is seen, not read.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "dot",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(fg.copy(alpha = if (ready && online) pulse else 1f)),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text("فروشگاه: " + (store.shop ?: "—"), color = Brand.Ink, fontSize = 14.sp)
            Text("آخرین ارتباط با ربات: " + Persian.ago(store.lastPing), color = Brand.Muted, fontSize = 13.sp)
            Text("آخرین پیامک فرستاده‌شده: " + Persian.ago(store.lastSent), color = Brand.Muted, fontSize = 13.sp)
            val pending = store.queue().size
            if (pending > 0) {
                Text("⏳ " + Persian.number(pending) + " پیامک منتظر اینترنت", color = Brand.Warn, fontSize = 13.sp)
            }
            if (revoked) {
                Text(
                    "ربات این گوشی را دیگر نمی‌شناسد (آدرس در ربات عوض شده). پایین صفحه «قطع اتصال» را بزنید و «کپی کد اتصال» تازه را از ربات بچسبانید.",
                    color = Brand.Ink, fontSize = 13.sp, lineHeight = 20.sp,
                )
            } else if (!ready) {
                Text("پایین همین صفحه شمارهٔ پیامک بانک را اضافه کنید.", color = Brand.Ink, fontSize = 13.sp)
            }
        }
    }
}

// ── 3. what the phone needs ─────────────────────────────────────────────

@SuppressLint("BatteryLife")
@Composable
private fun ChecklistCard(context: Context, store: Store, refresh: () -> Unit, rev: Int) {
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    val batteryFree = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    // Asked once and still refused: Android will not show the dialog again, and on recent versions a
    // sideloaded app's SMS permission sits behind «Allow restricted settings» in App info.
    var smsAsked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsAsked = true; refresh() }
    val notificationsOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        KeepAliveService.start(context)
        refresh()
    }
    // Autostart has no API to read back: nothing on the phone says whether the seller switched it on.
    // Opening the page used to tick the row green — a claim the app could not make, and gone again on
    // the next launch. Now the seller is asked on the way back, and the answer is remembered.
    var autostartAsking by remember { mutableStateOf(false) }
    var autostartOpenedAt by remember { mutableIntStateOf(-1) }
    LaunchedEffect(rev) {
        if (autostartOpenedAt >= 0 && rev / 1_000_000 > autostartOpenedAt) {
            autostartOpenedAt = -1
            autostartAsking = true
        }
    }

    Section("آماده‌سازی گوشی", Icons.Filled.Settings) {
        CheckRow(
            done = smsGranted,
            title = "اجازهٔ دریافت پیامک",
            hint = if (smsAsked && !smsGranted) {
                "اجازه داده نشد. در صفحه‌ای که باز می‌شود: «پیامک» را مجاز کنید. اگر غیرفعال بود، از منوی ⋮ بالای همان صفحه «Allow restricted settings» را بزنید و دوباره امتحان کنید."
            } else {
                "فقط پیامک‌های تازه، و فقط از شماره‌هایی که خودتان مجاز می‌کنید، به ربات می‌رود."
            },
            action = if (smsAsked) "تنظیمات" else "اجازه بده",
        ) {
            if (smsAsked) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                }
            } else {
                ask.launch(Manifest.permission.RECEIVE_SMS)
            }
        }
        HorizontalDivider(color = Brand.Canvas)
        CheckRow(
            done = batteryFree,
            title = "کار در پس‌زمینه",
            hint = "تا گوشی برای صرفه‌جویی باتری برنامه را نخواباند و پیامک‌ها جا نمانند.",
            action = "تنظیم",
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)),
                )
            }
        }
        HorizontalDivider(color = Brand.Canvas)
        CheckRow(
            done = notificationsOk,
            title = "اعلان «فعال است»",
            hint = "یک اعلان کوچک و ثابت که نمی‌گذارد گوشی برنامه را ببندد. بدون آن، برنامه بعد از چند دقیقه می‌خوابد.",
            action = "اجازه بده",
        ) { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if (Autostart.needed) {
            HorizontalDivider(color = Brand.Canvas)
            CheckRow(
                done = store.autostartConfirmed,
                title = "اجرای خودکار (" + Build.MANUFACTURER + ")",
                hint = if (store.autostartConfirmed) {
                    "گفتید روشنش کرده‌اید. اگر برنامه بعد از خاموش و روشن کردن گوشی کار نکرد، دوباره بررسی کنید."
                } else {
                    "در این گوشی‌ها «اجرای خودکار» یا «Autostart» برای برنامه‌های تازه خاموش است. در صفحه‌ای که باز می‌شود، آن را برای «جیرجیرک پیامک» روشن کنید."
                },
                action = "باز کن",
            ) {
                autostartOpenedAt = rev / 1_000_000
                Autostart.open(context)
            }
        }
        if (autostartAsking) {
            AlertDialog(
                onDismissRequest = { autostartAsking = false },
                title = { Text("اجرای خودکار") },
                text = { Text("«اجرای خودکار» را برای «جیرجیرک پیامک» روشن کردید؟", lineHeight = 22.sp) },
                confirmButton = {
                    TextButton(onClick = { store.autostartConfirmed = true; autostartAsking = false; refresh() }) { Text("بله، روشن شد") }
                },
                dismissButton = { TextButton(onClick = { autostartAsking = false }) { Text("نه هنوز") } },
            )
        }
        HorizontalDivider(color = Brand.Canvas)
        CheckRow(
            done = store.senders.isNotEmpty(),
            title = "شمارهٔ پیامک بانک",
            hint = "تا وقتی دست‌کم یک شماره مجاز نکنید، هیچ پیامکی از گوشی خارج نمی‌شود.",
            action = null,
        ) {}
    }
}

@Composable
private fun CheckRow(done: Boolean, title: String, hint: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (done) Brand.GoodSoft else Brand.WarnSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (done) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (done) Brand.Good else Brand.Warn,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Brand.Ink, fontSize = 14.sp)
            Text(hint, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (!done && action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

// ── 4. the seller's own list of bank numbers ────────────────────────────

@Composable
@Suppress("UNUSED_PARAMETER")
private fun SendersCard(store: Store, refresh: () -> Unit, rev: Int) {
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    Section("شماره‌های مجاز بانک", Icons.Filled.Phone) {
        Text(
            "فقط پیامک این شماره‌ها به ربات فرستاده می‌شود. پیامک رمز یکبار مصرف و کد تأیید حتی از این شماره‌ها هم فرستاده نمی‌شود.",
            color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp,
        )
        val senders = store.senders
        if (senders.isEmpty()) {
            Notice("هنوز شماره‌ای اضافه نکرده‌اید.", Brand.WarnSoft, Brand.Warn)
        }
        senders.forEach { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Brand.Canvas).padding(start = 12.dp),
            ) {
                Text("🏦 " + Persian.sender(s), modifier = Modifier.weight(1f), color = Brand.Ink, fontSize = 15.sp)
                IconButton(onClick = { removing = s }) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = Brand.Bad)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { picking = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) { Text("انتخاب از پیامک‌ها") }
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("نوشتن شماره")
            }
        }

        // Senders whose SMS looked like a bank's — offered, never added on their own.
        val suggestions = store.suggestions
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("پیشنهاد: این شماره‌ها پیامک بانکی فرستاده‌اند ولی مجاز نیستند", color = Brand.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            suggestions.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(Persian.sender(s), modifier = Modifier.weight(1f), color = Brand.Ink)
                    TextButton(onClick = { store.addSender(s); refresh() }) { Text("مجاز کن") }
                    IconButton(onClick = { store.dismissSuggestion(s); refresh() }) {
                        Icon(Icons.Filled.Close, contentDescription = "نادیده", tint = Brand.Muted)
                    }
                }
            }
        }
    }

    if (picking) {
        PickSenderDialog(store, onDone = { value ->
            if (value != null) store.addSender(value)
            picking = false
            refresh()
        })
    }
    if (adding) {
        AddSenderDialog(onDone = { value ->
            if (value != null) store.addSender(value)
            adding = false
            refresh()
        })
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("حذف شماره") },
            text = { Text("پیامک‌های «" + Persian.sender(s) + "» دیگر به ربات فرستاده نمی‌شود.") },
            confirmButton = {
                TextButton(onClick = { store.removeSender(s); removing = null; refresh() }) { Text("حذف", color = Brand.Bad) }
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
    var senders by remember { mutableStateOf<List<Inbox.Sender>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) {
        if (!granted) {
            ask.launch(Manifest.permission.READ_SMS)
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
                    !granted -> Text("برای نشان دادن فرستنده‌های پیامک، اجازهٔ خواندن پیامک لازم است. این فهرست فقط روی گوشی شماست و به جایی فرستاده نمی‌شود.", fontSize = 13.sp, lineHeight = 20.sp, color = Brand.Muted)
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
                                    color = Brand.Muted, fontSize = 12.sp, maxLines = 1,
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

// ── 5. what was sent ────────────────────────────────────────────────────

@Composable
@Suppress("UNUSED_PARAMETER")
private fun LogCard(store: Store, rev: Int) {
    val log = store.log()
    Section("پیامک‌های فرستاده‌شده", Icons.Filled.Email) {
        if (log.isEmpty()) {
            Text("هنوز پیامکی فرستاده نشده است. با اولین واریز به حساب، اینجا نشان داده می‌شود.", color = Brand.Muted, fontSize = 13.sp)
        }
        log.take(10).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.optBoolean("ok")) "✅" else "⏳")
                Spacer(Modifier.width(8.dp))
                Text(Persian.sender(item.optString("from")), modifier = Modifier.weight(1f), color = Brand.Ink, fontSize = 14.sp)
                Text(Persian.clock(item.optLong("at")), color = Brand.Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun UnpairButton(context: Context, store: Store, refresh: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { asking = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Bad),
    ) { Text("قطع اتصال از این فروشگاه") }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("قطع اتصال") },
            text = { Text("از این به بعد هیچ پیامکی به ربات این فروشگاه فرستاده نمی‌شود. شماره‌های مجاز می‌مانند.") },
            confirmButton = {
                TextButton(onClick = {
                    // Everything that wakes the phone for this app stops with the pairing.
                    store.unpair()
                    KeepAliveService.stop(context)
                    Beat.cancel(context)
                    SyncJob.cancelAll(context)
                    asking = false
                    refresh()
                }) { Text("قطع اتصال", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("انصراف") } },
        )
    }
}
