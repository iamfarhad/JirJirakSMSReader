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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.jirjirak.sms.Autostart
import ir.jirjirak.sms.BuildConfig
import ir.jirjirak.sms.KeepAliveService
import ir.jirjirak.sms.Net
import ir.jirjirak.sms.R
import ir.jirjirak.sms.Rules
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.SyncJob
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
    // Read so a change to either re-composes.
    @Suppress("UNUSED_VARIABLE") val unused = resumed + tick

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        if (store.endpoint == null) {
            PairCard(store, refresh)
        } else {
            StatusCard(store)
            ChecklistCard(context, store, refresh)
            SendersCard(store, refresh)
            LogCard(store)
            UnpairButton(context, store, refresh)
        }
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brand.Violet),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_cricket), contentDescription = null, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("جیرجیرک پیامک", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Brand.Ink)
            Text("تأیید خودکار رسید کارت‌به‌کارت", color = Brand.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Brand.Ink)
            content()
        }
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
            store.endpoint = endpoint
            val ok = withContext(Dispatchers.IO) { Net.ping(context) }
            busy = false
            if (ok) {
                SyncJob.schedulePeriodic(context)
                KeepAliveService.start(context)
            } else {
                store.unpair()
                error = "به ربات وصل نشد. اینترنت گوشی را بررسی کنید؛ اگر درست است، کد تازه‌ای از ربات کپی کنید."
            }
            refresh()
        }
    }

    Section("اتصال به فروشگاه") {
        Steps(
            "در ربات فروشگاه بروید به: مدیریت ربات ← پرداخت‌ها ← تأیید خودکار رسید",
            "دکمهٔ «📋 کپی کد اتصال» را بزنید",
            "به همین برنامه برگردید و «چسباندن کد اتصال» را بزنید",
        )
        Button(
            onClick = { pair(clipboard.getText()?.text) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium,
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
                Modifier.size(24.dp).clip(CircleShape).background(Brand.VioletSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(Persian.number(i + 1), color = Brand.VioletDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
private fun StatusCard(store: Store) {
    val ready = store.senders.isNotEmpty()
    val online = System.currentTimeMillis() - store.lastPing < 40 * 60 * 1000L
    val (bg, fg, title) = when {
        !ready -> Triple(Brand.WarnSoft, Brand.Warn, "⏸ هنوز هیچ پیامکی فرستاده نمی‌شود")
        !online -> Triple(Brand.BadSoft, Brand.Bad, "📵 ارتباط با ربات برقرار نیست")
        else -> Triple(Brand.GoodSoft, Brand.Good, "✅ فعال است")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("فروشگاه: " + (store.shop ?: "—"), color = Brand.Ink, fontSize = 14.sp)
            Text("آخرین ارتباط با ربات: " + Persian.ago(store.lastPing), color = Brand.Muted, fontSize = 13.sp)
            Text("آخرین پیامک فرستاده‌شده: " + Persian.ago(store.lastSent), color = Brand.Muted, fontSize = 13.sp)
            val pending = store.queue().size
            if (pending > 0) {
                Text("⏳ " + Persian.number(pending) + " پیامک منتظر اینترنت", color = Brand.Warn, fontSize = 13.sp)
            }
            if (!ready) {
                Text("پایین همین صفحه شمارهٔ پیامک بانک را اضافه کنید.", color = Brand.Ink, fontSize = 13.sp)
            }
        }
    }
}

// ── 3. what the phone needs ─────────────────────────────────────────────

@SuppressLint("BatteryLife")
@Composable
private fun ChecklistCard(context: Context, store: Store, refresh: () -> Unit) {
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    val batteryFree = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val notificationsOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        KeepAliveService.start(context)
        refresh()
    }
    var autostartOpened by remember { mutableStateOf(false) }

    Section("آماده‌سازی گوشی") {
        CheckRow(
            done = smsGranted,
            title = "اجازهٔ دریافت پیامک",
            hint = "فقط پیامک‌های تازه، و فقط از شماره‌هایی که خودتان مجاز می‌کنید، به ربات می‌رود.",
            action = "اجازه بده",
        ) { ask.launch(Manifest.permission.RECEIVE_SMS) }
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
                done = autostartOpened,
                title = "اجرای خودکار (" + Build.MANUFACTURER + ")",
                hint = "در این گوشی‌ها «اجرای خودکار» یا «Autostart» برای برنامه‌های تازه خاموش است. در صفحه‌ای که باز می‌شود، آن را برای «جیرجیرک پیامک» روشن کنید.",
                action = "باز کن",
            ) { Autostart.open(context); autostartOpened = true }
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
private fun SendersCard(store: Store, refresh: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    Section("شماره‌های مجاز بانک") {
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
                Text("🏦 " + Persian.digits(s), modifier = Modifier.weight(1f), color = Brand.Ink, fontSize = 15.sp)
                IconButton(onClick = { removing = s }) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = Brand.Bad)
                }
            }
        }
        Button(
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("افزودن شماره")
        }

        // Senders whose SMS looked like a bank's — offered, never added on their own.
        val suggestions = store.suggestions
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("پیشنهاد: این شماره‌ها پیامک بانکی فرستاده‌اند ولی مجاز نیستند", color = Brand.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            suggestions.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(Persian.digits(s), modifier = Modifier.weight(1f), color = Brand.Ink)
                    TextButton(onClick = { store.addSender(s); refresh() }) { Text("مجاز کن") }
                    IconButton(onClick = { store.dismissSuggestion(s); refresh() }) {
                        Icon(Icons.Filled.Close, contentDescription = "نادیده", tint = Brand.Muted)
                    }
                }
            }
        }
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
            text = { Text("پیامک‌های «" + Persian.digits(s) + "» دیگر به ربات فرستاده نمی‌شود.") },
            confirmButton = {
                TextButton(onClick = { store.removeSender(s); removing = null; refresh() }) { Text("حذف", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("انصراف") } },
        )
    }
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
private fun LogCard(store: Store) {
    val log = store.log()
    Section("پیامک‌های فرستاده‌شده") {
        if (log.isEmpty()) {
            Text("هنوز پیامکی فرستاده نشده است. با اولین واریز به حساب، اینجا نشان داده می‌شود.", color = Brand.Muted, fontSize = 13.sp)
        }
        log.take(10).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.optBoolean("ok")) "✅" else "⏳")
                Spacer(Modifier.width(8.dp))
                Text(Persian.digits(item.optString("from")), modifier = Modifier.weight(1f), color = Brand.Ink, fontSize = 14.sp)
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
                TextButton(onClick = { store.unpair(); KeepAliveService.stop(context); asking = false; refresh() }) { Text("قطع اتصال", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("انصراف") } },
        )
    }
}
