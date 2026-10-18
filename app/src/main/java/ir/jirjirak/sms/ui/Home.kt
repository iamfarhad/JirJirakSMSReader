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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ir.jirjirak.sms.Autostart
import ir.jirjirak.sms.Beat
import ir.jirjirak.sms.KeepAliveService
import ir.jirjirak.sms.Net
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Rules
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.SyncJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── pairing (states 01–04, 66) ──────────────────────────────────────────

/**
 * Paste the bot's «jirjirak-sms:» code. The CANDIDATE is pinged first and kept only on a valid
 * reply: a code that does not parse, a bot that does not answer and a revoked code each have their
 * own state, and none of them leaves the phone looking paired.
 */
@Composable
fun PairScreen(store: Store) {
    watchStore()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(States.Pair.EMPTY) }
    // The other code pasted by mistake: said in so many words, it is the commonest slip.
    var wasManageCode by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var lastTried by remember { mutableStateOf<String?>(null) }

    fun pair(raw: String?) {
        val endpoint = Rules.parseCode(raw)
        if (endpoint == null) {
            wasManageCode = Receipts.parseCode(raw) != null
            state = States.Pair.INVALID
            return
        }
        lastTried = raw
        state = States.Pair.CHECKING
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { Net.ping(context.applicationContext, endpoint) }
            if (result != Net.Ping.OK) {
                reason = Net.lastPingReason
                state = States.pairAfter(result)
                return@launch
            }
            store.endpoint = endpoint
            store.event("system", "ok", "به فروشگاه وصل شد", store.shop?.let { "فروشگاه $it" })
            // The code carries the shop's secret: it does not stay on the clipboard for the next app.
            if (Rules.parseCode(clipboard.getText()?.text) == endpoint) clipboard.setText(AnnotatedString(""))
            val app = context.applicationContext
            SyncJob.schedulePeriodic(app)
            Beat.schedule(app)
            KeepAliveService.start(app)
            // The reply carried the shop's details; keep them now that the pairing is real. On its
            // own thread: the screen switches to «paired» at once, which ends this composable's scope.
            Thread { runCatching { Net.ping(app) } }.start()
        }
    }

    when (state) {
        States.Pair.EMPTY -> Hero("پیامک بانک، مستقیم به جیرجیرک", "فقط فرستنده‌هایی که خودتان مجاز می‌کنید.", Tone.good, Art.Connection)
        States.Pair.CHECKING -> Hero("در حال بررسی کد اتصال", "فروشگاه فقط بعد از دریافت پاسخ معتبر ذخیره می‌شود.", Tone.info, Art.Connection)
        States.Pair.INVALID -> Hero(
            "این کد اتصال نیست",
            if (wasManageCode) "این «کد مدیریت رسیدها» است؛ کد اتصال کد دیگری است." else "کد را کامل و بدون تغییر از ربات کپی کنید.",
            Tone.bad, Art.Error,
        )
        States.Pair.OFFLINE -> Hero("ارتباط برقرار نشد", "به ربات دسترسی نداریم؛ اتصال اینترنت را بررسی کنید.", Tone.bad, Art.Offline)
        States.Pair.REVOKED -> Hero("این کد دیگر معتبر نیست", "احتمالاً آدرس در ربات عوض شده؛ از ربات فروشگاه کد اتصال تازه بگیرید.", Tone.bad, Art.Security)
    }

    if (state == States.Pair.OFFLINE) {
        Notice("تغییری ذخیره نشده است", "تا زمانی که ربات اتصال را تأیید نکند، گوشی به فروشگاه وصل نمی‌شود.", Tone.warn)
        AppCard(title = "رفع مشکل") {
            Rows(
                { ListRow(Ico.Wifi, Tone.muted, "اینترنت گوشی", "شبکه یا VPN را بررسی کنید.") },
                { ListRow(Ico.Refresh, Tone.muted, "پاسخ ربات", reason?.let { Persian.digits(it) } ?: "دوباره امتحان کنید.") },
            )
            PrimaryButton("تلاش دوباره", { pair(lastTried) }, icon = Ico.Refresh)
            SecondaryButton("بازگشت", { state = States.Pair.EMPTY })
        }
    } else {
        AppCard(title = if (state == States.Pair.EMPTY) "اتصال به فروشگاه" else if (state == States.Pair.CHECKING) "اتصال امن" else "کد اتصال تازه") {
            if (state == States.Pair.EMPTY) {
                Steps(listOf(
                    "در ربات فروشگاه بروید به: مدیریت ربات ← پرداخت‌ها ← تأیید خودکار رسید.",
                    "«کپی کد اتصال» را بزنید و به این برنامه برگردید.",
                ))
            }
            if (state != States.Pair.CHECKING) {
                PrimaryButton(if (state == States.Pair.EMPTY) "چسباندن کد اتصال" else "چسباندن کد تازه", { pair(clipboard.getText()?.text) }, icon = Ico.Copy)
            }
            SecureField(typed, { typed = it; if (state == States.Pair.INVALID) state = States.Pair.EMPTY },
                label = "کد اتصال", isError = state == States.Pair.INVALID, enabled = state != States.Pair.CHECKING, onDone = { if (typed.isNotBlank()) pair(typed) })
            if (state == States.Pair.INVALID) Notice("ساختار کد معتبر نیست", "کد با «" + Persian.latin("jirjirak-sms:") + "» شروع می‌شود. آن را کامل و بدون تغییر از ربات کپی کنید.", Tone.bad)
            if (state == States.Pair.CHECKING) Waiting("منتظر پاسخ ربات…")
            SecondaryButton(if (state == States.Pair.CHECKING) "در حال اتصال" else "بررسی و اتصال", { pair(typed) }, enabled = typed.isNotBlank(), busy = state == States.Pair.CHECKING)
        }
        if (state == States.Pair.CHECKING) {
            SecondaryButton("بازگشت به ورود کد", { job?.cancel(); state = States.Pair.EMPTY })
        } else {
            Notice("کنترل دست شماست", "تا شمارهٔ بانک را وارد نکنید، هیچ پیامکی ارسال نمی‌شود. پیامک رمز و کد تأیید هرگز فرستاده نمی‌شود.", Tone.info)
        }
    }
    JirjirakLinks()
    VersionLine()
}

// ── setup (states 05–08, 55) ────────────────────────────────────────────

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun smsGranted(context: android.content.Context) = granted(context, Manifest.permission.RECEIVE_SMS)

fun notificationsGranted(context: android.content.Context) = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

fun batteryFree(context: android.content.Context) =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

@SuppressLint("BatteryLife")
fun askBattery(context: android.content.Context) {
    val direct = runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
    }.isSuccess
    if (!direct) runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

/** «آماده‌سازی گوشی»: the two essentials, then what only makes it steadier. */
@Composable
internal fun SetupScreen(store: Store, resumed: Int) {
    watchStore()
    val context = LocalContext.current
    val nav = LocalNav.current
    @Suppress("UNUSED_VARIABLE") val again = resumed
    val sms = smsGranted(context)
    val notifications = notificationsGranted(context)
    val battery = batteryFree(context)
    val setup = States.setup(sms, store.senders.size, notifications, battery, Autostart.needed, store.autostartConfirmed)
    // Autostart has no API to read back: the seller is asked on the way back from that screen.
    var autostartOpenedAt by remember { mutableIntStateOf(-1) }
    var autostartAsking by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        if (autostartOpenedAt in 0 until resumed) {
            autostartOpenedAt = -1
            autostartAsking = true
        }
    }

    ScreenHeader("آماده‌سازی گوشی", "خانه") { nav.home() }
    Hero(
        if (setup.ready) "موارد ضروری آماده است" else "آماده‌سازی گوشی",
        if (setup.ready) "تنظیمات تکمیلی فقط پایداری و اطلاع‌رسانی را بهتر می‌کنند."
        else Persian.number(setup.essentialTotal - setup.essentialDone) + " مورد ضروری مانده؛ تنظیمات تکمیلی جدا هستند.",
        if (setup.ready) Tone.good else Tone.info, icon = Ico.Settings,
    )
    AppCard(title = "برای شروع ضروری است", trailing = { StatusBadge(Persian.number(setup.essentialDone) + " از " + Persian.number(setup.essentialTotal), if (setup.ready) Tone.good else Tone.warn) }) {
        Rows(
            {
                ListRow(
                    Ico.Mail, if (sms) Tone.good else Tone.warn, "دریافت پیامک",
                    if (sms) "اجازه داده شده است." else "بدون این اجازه، پیامک تازه دریافت نمی‌شود.",
                    badge = if (sms) "آماده" else "اجازه بده",
                    onClick = if (sms) null else ({ nav.home("sms_permission") }),
                )
            },
            {
                ListRow(
                    Ico.Bank, if (store.senders.isNotEmpty()) Tone.good else Tone.warn, "فرستندهٔ بانک",
                    if (store.senders.isNotEmpty()) Persian.number(store.senders.size) + " فرستندهٔ مجاز" else "حداقل یک شماره یا شناسه را وارد کنید.",
                    badge = if (store.senders.isNotEmpty()) "آماده" else "افزودن",
                    onClick = { nav.home("senders") },
                )
            },
        )
    }
    AppCard(title = "برای پایداری و اطلاع‌رسانی") {
        ListRow(
            Ico.Bell, if (notifications) Tone.good else Tone.warn, "اعلان‌ها",
            if (notifications) "اعلان وضعیت و هشدارها" else "اعلان وضعیت و هشدارها دیده نمی‌شوند.",
            badge = if (notifications) "فعال" else "غیرفعال", onClick = if (notifications) null else ({ nav.home("notify_denied") }),
        )
        Divider()
        ListRow(
            Ico.Battery, if (battery) Tone.good else Tone.warn, "محدودیت باتری",
            if (battery) "اندروید برنامه را برای صرفه‌جویی نمی‌خواباند." else "ممکن است کار پس‌زمینه را به تأخیر بیندازد.",
            badge = if (battery) "آزاد" else "تنظیم", onClick = if (battery) null else ({ askBattery(context) }),
        )
        if (Autostart.needed) {
            Divider()
            ListRow(
                Ico.Refresh, if (store.autostartConfirmed) Tone.good else Tone.info,
                "اجرای خودکار (" + Persian.latin(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }) + ")",
                "طبق تأیید شما؛ گوشی راهی برای تشخیص خودکار آن ندارد.",
                badge = if (store.autostartConfirmed) "تأیید شما" else "باز کن",
                onClick = { autostartOpenedAt = resumed; Autostart.open(context) },
            )
        }
    }
    if (autostartAsking) {
        ConfirmDialog(
            "اجرای خودکار", "«اجرای خودکار» را برای «جیرجیرک پیامک» روشن کردید؟ گوشی این را به برنامه نمی‌گوید؛ پاسخ شما ذخیره می‌شود.",
            "بله، روشن شد", { store.autostartConfirmed = true; autostartAsking = false }, { autostartAsking = false }, cancel = "نه هنوز",
        )
    }
}

/**
 * Before Android's own dialog (06): what the permission is for, and what never leaves the phone.
 * The dialog itself is Android's — never imitated here.
 */
@Composable
internal fun SmsPermissionScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Store.revision.intValue++
        // Android still willing to ask (a rationale is due) means a plain «no» — the seller can try
        // again. Not willing, on 13+, is the restricted-settings lock, and that needs its guide.
        val willAskAgain = (context as? android.app.Activity)?.let { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECEIVE_SMS) } ?: true
        when {
            ok -> nav.home("setup_done")
            States.showRestrictedGuide(Build.VERSION.SDK_INT, asked = true, granted = false) && !willAskAgain -> nav.home("restricted")
            else -> refused = true
        }
    }
    ScreenHeader("درخواست دسترسی", "خانه") { nav.home() }
    Hero("اجازهٔ دریافت پیامک", "برای ارسال خودکار پیامک‌های تازهٔ فرستنده‌های مجاز لازم است.", Tone.info, Art.Permissions)
    AppCard(title = "قبل از دادن اجازه") {
        Rows(
            { ListRow(Ico.Bank, Tone.muted, "فهرست مجاز", "هر شماره را خودتان وارد می‌کنید؛ تا وارد نکنید چیزی ارسال نمی‌شود.") },
            { ListRow(Ico.Shield, Tone.muted, "حفاظت از رمزها", "پیامک‌هایی که شبیه رمز یکبار مصرف یا کد تأیید باشند ارسال نمی‌شوند.") },
            { ListRow(Ico.Inbox, Tone.muted, "صندوق پیامک خوانده نمی‌شود", "فقط پیامک‌های تازه شنیده می‌شوند؛ پیامک‌های قبلی گوشی دست‌نخورده می‌مانند.") },
        )
    }
    if (refused) {
        Notice("اجازه داده نشد", "برای دادن اجازه از تنظیمات برنامه، «" + Persian.latin("Permissions → SMS") + "» را مجاز کنید.", Tone.warn)
        PrimaryButton("باز کردن تنظیمات برنامه", { openAppSettings(context) })
    } else {
        PrimaryButton("ادامه و درخواست اجازه", { ask.launch(Manifest.permission.RECEIVE_SMS) })
    }
    SecondaryButton("فعلاً نه", { nav.home() })
    Notice("پنجرهٔ بعدی متعلق به اندروید است", "ظاهر و عبارت‌های آن به نسخهٔ گوشی بستگی دارد.", Tone.info)
}

/**
 * Android 13+ refuses the SMS permission to an app installed from a file until «Allow restricted
 * settings» — and says only «" + Persian.latin("App was denied access") + "», in English. The way through, in their words.
 */
@Composable
internal fun RestrictedScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    ScreenHeader("دسترسی مسدود", "خانه") { nav.home() }
    Hero("اندروید دسترسی را محدود کرده", "روی اندروید ۱۳ به بالا، برای برنامه‌ای که از فایل نصب شده یک تنظیم دیگر لازم است.", Tone.warn, Art.Permissions)
    AppCard(title = "راهنمای قدم‌به‌قدم") {
        Steps(listOf(
            "«باز کردن تنظیمات برنامه» را بزنید.",
            "از منوی ⋮ بالای صفحه «" + Persian.latin("Allow restricted settings") + "» را بزنید. اگر نبود، یک بار «" + Persian.latin("Permissions → SMS") + "» را بزنید تا پیغام «" + Persian.latin("App was denied access") + "» بیاید و ببندید؛ حالا منو آن گزینه را دارد.",
            "با اثر انگشت یا رمز گوشی تأیید کنید.",
            "«" + Persian.latin("Permissions → SMS → Allow") + "» را بزنید و به همین برنامه برگردید.",
        ))
        PrimaryButton("باز کردن تنظیمات برنامه", { openAppSettings(context) })
        SecondaryButton("بعداً انجام می‌دهم", { nav.home() })
    }
    Notice("هنوز ارسال خودکار فعال نشده است", "تا اجازهٔ پیامک داده نشود، پیامک بانک به ربات نمی‌رسد.", Tone.warn)
}

@Composable
internal fun SetupDoneScreen(store: Store) {
    watchStore()
    val nav = LocalNav.current
    val context = LocalContext.current
    ScreenHeader("آماده‌سازی", "خانه") { nav.home() }
    val ok = smsGranted(context)
    Hero(
        if (ok) "اجازهٔ پیامک آماده است" else "اجازهٔ پیامک هنوز داده نشده",
        if (store.senders.isEmpty()) "برای شروع ارسال، حالا شمارهٔ بانک را وارد کنید." else "فرستنده‌های مجاز آماده‌اند.",
        if (ok) Tone.good else Tone.warn, Art.SetupComplete,
    )
    AppCard(title = "قدم آخر") {
        Rows(
            { ListRow(Ico.Mail, if (ok) Tone.good else Tone.warn, "دریافت پیامک", if (ok) "اجازه داده شده است." else "اجازه داده نشده است.", badge = if (ok) "آماده" else "لازم") },
            {
                ListRow(
                    Ico.Bank, if (store.senders.isEmpty()) Tone.warn else Tone.good, "فرستنده‌های مجاز",
                    if (store.senders.isEmpty()) "هنوز شماره‌ای وارد نشده است." else Persian.number(store.senders.size) + " فرستنده",
                    badge = if (store.senders.isEmpty()) "صفر" else Persian.number(store.senders.size),
                )
            },
        )
    }
    if (store.senders.isEmpty()) PrimaryButton("افزودن شمارهٔ بانک", { nav.home("senders") }, icon = Ico.Plus) else PrimaryButton("بازگشت به خانه", { nav.home() })
}

/** POST_NOTIFICATIONS refused (55): two separate permissions, and refusing this one does not stop the SMS. */
@Composable
internal fun NotifyDeniedScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        KeepAliveService.start(context)
        Store.revision.intValue++
    }
    watchStore()
    val on = notificationsGranted(context)
    ScreenHeader("اعلان‌ها", "خانه") { nav.home() }
    Hero(
        if (on) "اعلان‌ها فعال است" else "اعلان وضعیت فعال نیست",
        if (on) "هشدارها و اعلان ثابت دیده می‌شوند." else "اعلان ثابت و هشدارها ممکن است دیده نشوند.",
        if (on) Tone.good else Tone.warn, Art.Notifications,
    )
    AppCard(title = "اجازه‌های گوشی") {
        Rows(
            { ListRow(Ico.Bell, if (on) Tone.good else Tone.warn, "اعلان‌ها", if (on) "مجاز" else "دسترسی داده نشده", badge = if (on) "فعال" else "غیرفعال") },
            { ListRow(Ico.Mail, if (smsGranted(context)) Tone.good else Tone.warn, "دریافت پیامک", if (smsGranted(context)) "مجاز" else "داده نشده", badge = if (smsGranted(context)) "فعال" else "غیرفعال") },
        )
        if (!on) {
            if (Build.VERSION.SDK_INT >= 33 && !asked) PrimaryButton("درخواست اجازهٔ اعلان", { ask.launch(Manifest.permission.POST_NOTIFICATIONS) })
            else PrimaryButton("باز کردن تنظیمات اعلان‌ها", { openNotificationSettings(context) })
        }
    }
    Notice("دریافت پیامک و اعلان دو دسترسی متفاوت هستند.", "بدون اجازهٔ اعلان هم پیامک‌های بانک دریافت و فرستاده می‌شوند؛ فقط اعلان‌ها دیده نمی‌شوند.", Tone.info)
}

// ── the seller's own list of bank numbers (states 09, 11, 12, 60, 67) ──

/**
 * The allowed senders: the list, a delete on each one, and one way to add — the seller types the
 * number. The app never reads the inbox (no READ_SMS); a bank-looking sender the receiver heard is
 * offered under «پیشنهاد», its number only.
 */
@Composable
internal fun SendersScreen(store: Store) {
    watchStore()
    val nav = LocalNav.current
    var removing by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val senders = store.senders
    val suggestions = store.suggestions

    ScreenHeader(if (senders.isEmpty()) "شماره‌های بانک" else "شماره‌های مجاز", "خانه") { nav.home() }
    if (senders.isEmpty()) {
        Hero("بانک خود را معرفی کنید", "فقط پیامک شماره‌های بانکی که شما وارد می‌کنید ارسال می‌شوند.", Tone.info, icon = Ico.Bank)
        EmptyState("هنوز شمارهٔ بانکی ندارید", "شمارهٔ فرستندهٔ پیامک بانک را از برنامهٔ پیامک گوشی ببینید و همین‌جا بنویسید.", Art.Bank)
        PrimaryButton("افزودن شمارهٔ بانک", { nav.home("manual") }, icon = Ico.Plus)
        Notice("هیچ پیامکی بدون مجوز شما از گوشی خارج نمی‌شود.", "برنامه صندوق پیامک گوشی را نمی‌خواند؛ فقط پیامک‌های تازهٔ شماره‌هایی که وارد کرده‌اید فرستاده می‌شوند.", Tone.good)
    } else {
        Hero(Persian.number(senders.size) + " فرستنده مجاز است", "ارسال پیامک تنها از همین فرستنده‌ها انجام می‌شود.", Tone.good, icon = Ico.Bank)
        AppCard(title = "شماره‌های مجاز") {
            senders.forEachIndexed { i, s ->
                if (i > 0) Divider()
                ListRow(Ico.Bank, Tone.good, Persian.sender(s), "برای حذف بزنید", badge = "مجاز", onClick = { removing = s })
            }
        }
        PrimaryButton("افزودن شماره", { nav.home("manual") }, icon = Ico.Plus)
        Notice("برای حذف یک شماره، تأیید مجدد لازم است.", null, Tone.info)
    }
    // Senders whose SMS looked like a bank's — offered, never added on their own.
    if (suggestions.isNotEmpty()) {
        AppCard(title = "پیشنهاد") {
            Text("این شماره‌ها پیامکی شبیه پیامک بانک فرستاده‌اند ولی مجاز نیستند؛ تا مجازشان نکنید چیزی فرستاده نمی‌شود.", style = Type.supporting, color = Brand.Muted)
            suggestions.forEachIndexed { i, s ->
                if (i > 0) Divider()
                ListRow(Ico.Bank, Tone.info, Persian.sender(s), "شبیه پیامک بانک", badge = "بررسی", onClick = { confirming = s })
            }
        }
    }

    removing?.let { s ->
        ConfirmDialog(
            "حذف شمارهٔ مجاز؟",
            "پس از حذف، پیامک‌های تازهٔ «" + Persian.sender(s) + "» برای ربات فرستاده نمی‌شوند. سایر شماره‌ها فعال می‌مانند. پیامک‌هایی که پیش‌تر در صف بوده‌اند ممکن است همچنان ارسال شوند.",
            "حذف شماره", { store.removeSender(s); store.event("system", "info", "شمارهٔ " + s + " از فهرست مجاز حذف شد"); removing = null },
            { removing = null }, danger = true,
        )
    }
    confirming?.let { s ->
        ConfirmSenderDialog(store, s, onAdded = { confirming = null }, onDismiss = { confirming = null }, onIgnore = { store.dismissSuggestion(s); confirming = null })
    }
}

/**
 * «این فرستنده مجاز شود؟» (11): the FULL sender id, isolated left-to-right, and the shop it will go
 * to. No SMS text is shown: the app has none but what the receiver heard, and keeps none of it here.
 */
@Composable
private fun ConfirmSenderDialog(store: Store, sender: String, onAdded: () -> Unit, onDismiss: () -> Unit, onIgnore: (() -> Unit)? = null) {
    ConfirmDialog(
        "این فرستنده مجاز شود؟",
        "پیامک‌های غیررمز از این فرستنده به «" + (store.shop ?: "فروشگاه") + "» ارسال می‌شوند. شناسهٔ فرستنده را بررسی کرده‌اید؟",
        "بله، مجاز شود",
        {
            store.addSender(sender)
            store.event("system", "info", "شمارهٔ " + sender + " مجاز شد")
            onAdded()
        },
        onDismiss, cancel = "بازگشت",
        extra = {
            Text(
                Persian.sender(sender), style = Type.section.copy(textDirection = TextDirection.Ltr), color = Brand.Ink,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Canvas).padding(12.dp),
            )
            if (onIgnore != null) LinkButton("این پیشنهاد را نادیده بگیر", onIgnore, color = Brand.Muted)
        },
    )
}

/**
 * «افزودن شمارهٔ بانک» (67): the sender id exactly as the bank's SMS shows it, typed by the seller —
 * the only way a number is added — then the same confirmation.
 */
@Composable
internal fun ManualSenderScreen(store: Store) {
    val nav = LocalNav.current
    var value by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    ScreenHeader("افزودن شمارهٔ بانک", "شماره‌های بانک") { nav.home("senders") }
    Hero("شماره یا شناسهٔ بانک", "نام نمایشی کافی نیست؛ شناسه را دقیقاً از پیامک بانک بردارید.", Tone.info, Art.Bank)
    AppCard(title = "فرستندهٔ تازه") {
        PlainField(value, { value = it; error = null }, "شماره یا شناسهٔ پیامک", ltr = true, isError = error != null)
        error?.let { Text(it, style = Type.supporting, color = Brand.Bad) }
        Notice("هر دو نوع پذیرفته می‌شود", "شمارهٔ کوتاه بانکی (مثل 9999 یا +98200) یا نام فرستنده (مثل BankMellat)؛ اعداد فارسی و لاتین یکی حساب می‌شوند.", Tone.info)
        PrimaryButton("بررسی فرستنده", {
            val v = value.trim()
            when {
                Rules.normalize(v).isEmpty() -> error = "این شناسهٔ فرستنده نیست."
                Rules.isAllowed(v, store.senders) -> error = "این فرستنده از قبل مجاز است."
                else -> confirming = v
            }
        }, enabled = value.isNotBlank())
        SecondaryButton("انصراف", { nav.home("senders") })
    }
    AppCard(title = "شماره را از کجا پیدا کنم؟") {
        Steps(listOf(
            "برنامهٔ پیامک گوشی را باز کنید و یک پیامک واریز یا برداشت بانک را پیدا کنید.",
            "شماره یا نامی که بالای آن گفتگو نوشته شده، همان شناسهٔ فرستنده است.",
            "همان را بی‌کم‌وکاست در کادر بالا بنویسید.",
        ))
    }
    confirming?.let { s ->
        ConfirmSenderDialog(store, s, onAdded = { confirming = null; value = ""; nav.home("senders") }, onDismiss = { confirming = null })
    }
}

// ── shared bits ─────────────────────────────────────────────────────────

/** Jirjirak's own places: the news channel, the bot that makes shops, the app's source. */
@Composable
fun JirjirakLinks() {
    val context = LocalContext.current
    AppCard(title = "جیرجیرک") {
        Rows(
            { ListRow(Ico.Mail, Tone.info, "کانال اخبار و آموزش", Persian.latin("@JirjirakPremium"), onClick = { openTelegram(context, "JirjirakPremium") }) },
            { ListRow(Ico.Sparkles, Tone.good, "ربات جیرجیرک", "ساخت ربات فروش با ۳ روز تست رایگان", onClick = { openTelegram(context, "JirjirakPremiumBot") }) },
            {
                ListRow(Ico.External, Tone.muted, "راهنما و سورس برنامه", Persian.latin("github.com/iamfarhad/JirJirakSMSReader"), onClick = {
                    ir.jirjirak.sms.Updates.open(context, "https://github.com/iamfarhad/JirJirakSMSReader")
                })
            },
        )
    }
}
