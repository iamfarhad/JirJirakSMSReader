package ir.jirjirak.sms.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Recovery
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One setting: what it does in a line, and its switch. The whole row is one target and one node. */
@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.bodyStrong, color = if (enabled) Brand.Ink else Brand.DisabledInk)
            if (subtitle != null) Text(subtitle, style = Type.supporting, color = Brand.Muted)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Brand.Green, checkedThumbColor = Brand.OnPrimary,
                uncheckedTrackColor = Brand.Canvas, uncheckedBorderColor = Brand.Control, uncheckedThumbColor = Brand.Control,
            ),
        )
    }
}

// ── recovery (44, 56, 90–92) ────────────────────────────────────────────

/**
 * «بازیابی پیامک‌های جامانده»: turning it on asks for READ_SMS (Android shows its own dialog) and
 * sets the starting point — nothing from before this moment is ever sent. What it finds is QUEUED;
 * whether the bot took it is the report's to say.
 */
@Composable
internal fun RecoveryScreen(store: Store, now: Long) {
    watchStore()
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Int?>(null) }
    var denied by remember { mutableStateOf(false) }
    val permitted = Recovery.permitted(context)

    fun enable() {
        store.recoveryOn = true
        // From now, every time: SMS from while it was off are not «" + Persian.latin("missed") + "», they were declined.
        store.recoveryFrom = System.currentTimeMillis()
        store.event("system", "info", "بازیابی پیامک‌های جامانده روشن شد")
        denied = false
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else denied = true
        Store.revision.intValue++
    }

    ScreenHeader("بازیابی پیامک‌ها", "بیشتر") { nav.more() }
    val on = store.recoveryOn && permitted
    when {
        scanning -> Hero("در حال جست‌وجوی صندوق", "فقط بازهٔ مجاز بعد از روشن‌کردن بازیابی بررسی می‌شود.", Tone.info, Art.Queue)
        found != null && found!! > 0 -> Hero(Persian.number(found!!) + " پیامک به صف اضافه شد", "پیدا شدن و صف شدن، به معنی ارسال موفق نیست.", Tone.info, Art.Queue)
        found == 0 -> Hero("پیامک جامانده‌ای نبود", "در بازهٔ مجاز، پیامک تازه‌ای برای افزودن به صف پیدا نشد.", Tone.good, Art.SetupComplete)
        denied || (store.recoveryOn && !permitted) -> Hero("بازیابی روشن نشد", "برای جست‌وجوی صندوق پیامک، اجازهٔ خواندن پیامک لازم است.", Tone.warn, Art.Permissions)
        else -> Hero("بازیابی پیامک‌های جامانده", "برای وقت‌هایی که گوشی خاموش یا برنامه بسته بوده است.", Tone.info, Art.Queue)
    }
    if (scanning) Waiting("جست‌وجو در صندوق پیامک…")
    AppCard(title = "بازیابی خودکار") {
        SettingSwitch(
            "پیدا کردن پیامک‌های جامانده",
            "فقط از زمان روشن‌کردن این گزینه؛ حداکثر دو روز، فقط فرستنده‌های مجاز و بدون پیامک‌هایی که شبیه رمز باشند.",
            checked = on, enabled = !scanning,
        ) { want ->
            found = null
            when {
                !want -> { store.recoveryOn = false; store.event("system", "info", "بازیابی پیامک‌های جامانده خاموش شد") }
                permitted -> enable()
                else -> ask.launch(Manifest.permission.READ_SMS)
            }
        }
        if (denied || (store.recoveryOn && !permitted)) {
            Notice("اجازهٔ خواندن صندوق پیامک داده نشده است.", "اگر اندروید دوباره نپرسید: تنظیمات ← برنامه‌ها ← جیرجیرک پیامک ← اجازه‌ها ← پیامک.", Tone.warn)
            PrimaryButton("اجازهٔ خواندن پیامک", { ask.launch(Manifest.permission.READ_SMS) })
            SecondaryButton("باز کردن تنظیمات برنامه", { openAppSettings(context) })
        }
        if (on) {
            Divider()
            ListRow(Ico.Clock, Tone.muted, "آخرین جست‌وجو", Persian.ago(store.lastRecovery, now) + " · از " + Persian.date(store.recoveryFrom) + " " + Persian.clock(store.recoveryFrom))
            SecondaryButton(if (scanning) "در حال جست‌وجو" else "همین حالا جست‌وجو کن", {
                scanning = true
                found = null
                scope.launch {
                    found = withContext(Dispatchers.IO) { Recovery.scan(context.applicationContext, force = true) }
                    scanning = false
                }
            }, busy = scanning, icon = Ico.Search)
        }
    }
    found?.let { n ->
        Notice(States.recoveryResult(n), null, if (n > 0) Tone.info else Tone.good)
        if (n > 0) PrimaryButton("مشاهدهٔ صف ارسال", { nav.home("queue") })
    }
    Notice(
        "این قابلیت اختیاری است",
        "پیامک‌های قبل از روشن‌کردن بازیابی پیدا نمی‌شوند. پیامک شخصی، فرستندهٔ نامجاز و پیامک رمز ارسال نمی‌شود و صندوق پیامک شما تغییری نمی‌کند.",
        Tone.info,
    )
}

// ── notifications (45) ──────────────────────────────────────────────────

/** Which counts the phone tells about, and the quiet nights. Approvals and warnings always speak. */
@Composable
internal fun NotificationsScreen(store: Store) {
    watchStore()
    val context = LocalContext.current
    val nav = LocalNav.current
    val ready = store.manageKey != null
    val allowed = notificationsGranted(context)
    ScreenHeader("اعلان‌ها", "بیشتر") { nav.more() }
    Hero("اعلان‌ها", "زمان و نوع اطلاع‌رسانی را تعیین کنید.", Tone.info, Art.Notifications)
    if (!allowed) {
        Notice("اجازهٔ اعلان داده نشده", "این تنظیمات ذخیره می‌شوند ولی تا اجازه داده نشود اعلانی دیده نمی‌شود. دریافت پیامک به این اجازه بستگی ندارد.", Tone.warn)
        SecondaryButton("اجازهٔ اعلان", { nav.home("notify_denied") })
    }
    AppCard(title = "اعلان‌های رسید") {
        SettingSwitch("رسید نیازمند بررسی", "وقتی ربات نتوانست خودکار تأیید کند و تصمیم شما را می‌خواهد.", store.notifyReview, ready) { store.notifyReview = it }
        Divider()
        SettingSwitch("مغایرت تازه", "واریز بدون فاکتور، یا پیامکی که خوانده نشد.", store.notifyMismatch, ready) { store.notifyMismatch = it }
        Divider()
        SettingSwitch("سکوت شبانه", "از ساعت ۲۳ تا ۸ صبح این دو اعلان نمی‌آیند و صبح یک‌جا خبر می‌دهند. تأیید سفارش و هشدار قطعی همیشه می‌آید.", store.quietNights) { store.quietNights = it }
    }
    if (!ready) {
        Notice("برای دو اعلان اول، کد مدیریت معتبر لازم است.", null, Tone.info)
        SecondaryButton("افزودن کد مدیریت", { nav.receipts() })
    }
}

// ── taught bank formats (46, 59, 94) ────────────────────────────────────

/** The bank formats this shop taught — a list with a delete, like every collection in the bot. */
@Composable
internal fun FormatsScreen(store: Store) {
    watchStore()
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var formats by remember { mutableStateOf<List<Receipts.Format>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Receipts.Format?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenHeader("قالب‌های بانک", "بیشتر") { nav.more() }
    if (store.manageKey == null || !store.supports("formats")) {
        Hero("قالب‌های بانک", "قالب‌هایی که برای خواندن پیامک بانک آموزش داده‌اید.", Tone.info, Art.Learning)
        if (store.manageKey == null) {
            Notice("برای دیدن قالب‌ها کد مدیریت لازم است.", null, Tone.info)
            SecondaryButton("افزودن کد مدیریت", { nav.receipts() })
        } else {
            Notice("ربات این فروشگاه هنوز آموزش قالب را پشتیبانی نمی‌کند.", "بعد از به‌روزرسانی ربات اینجا فعال می‌شود.", Tone.info)
        }
        return
    }
    LaunchedEffect(attempt) {
        error = null
        when (val r = withContext(Dispatchers.IO) { Receipts.formats(context.applicationContext) }) {
            is Receipts.Result.Ok -> formats = r.value
            Receipts.Result.NeedKey -> store.revokeManageKey()
            is Receipts.Result.Failed -> error = r.message
            Receipts.Result.AlreadyDecided -> {}
        }
    }
    val list = formats
    when {
        error != null && list == null -> ErrorState(error!!) { attempt++ }
        list == null -> { Waiting("دریافت قالب‌ها از ربات…"); Skeleton(Modifier.fillMaxWidth(), height = 64) }
        list.isEmpty() -> {
            Hero("قالب‌های بانک", "قالب‌هایی که برای خواندن پیامک بانک آموزش داده‌اید.", Tone.info, icon = Ico.Bank)
            EmptyState("هنوز قالبی آموزش نداده‌اید", "اگر ربات پیامک واریز را نخواند، از «رسیدها ← مغایرت» قالب جدید را آموزش دهید.", Art.Learning)
            SecondaryButton("رفتن به مغایرت‌ها", { nav.receipts(3) })
        }
        else -> {
            Hero("قالب‌های آموزش‌داده‌شده", "بانک‌هایی که ربات روش خواندن پیامکشان را یاد گرفته است.", Tone.info, icon = Ico.Bank)
            error?.let { Notice("انجام نشد", Persian.digits(it), Tone.bad) }
            AppCard(title = "قالب‌های فعال") {
                list.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    ListRow(
                        Ico.Bank, Tone.good, Persian.sender(f.sender),
                        (if (f.unit == "toman") "تومان" else "ریال") + " · " + Persian.number(f.hits) + " پیامک خوانده شد" +
                            (if (f.lastHit > 0) " · آخرین بار " + Persian.ago(f.lastHit) else "") + " — برای حذف بزنید",
                        badge = "فعال", onClick = { deleting = f }, enabled = !busy,
                    )
                }
            }
            Notice("حذف قالب، واریزهای قبلی را تغییر نمی‌دهد.", null, Tone.warn)
            SecondaryButton("رفتن به مغایرت‌ها", { nav.receipts(3) })
        }
    }

    deleting?.let { f ->
        ConfirmDialog(
            "قالب حذف شود؟",
            "پیامک‌های هم‌شکل از " + Persian.sender(f.sender) + " ممکن است دوباره «خوانده‌نشده» شوند. واریزهای قبلی و فرستندهٔ مجاز حذف نمی‌شوند.",
            "حذف قالب", {
                busy = true
                scope.launch {
                    when (val r = withContext(Dispatchers.IO) { Receipts.forgetFormat(context.applicationContext, f.id) }) {
                        is Receipts.Result.Ok, Receipts.Result.AlreadyDecided -> { deleting = null; attempt++ }
                        Receipts.Result.NeedKey -> store.revokeManageKey()
                        is Receipts.Result.Failed -> { error = r.message; deleting = null }
                    }
                    busy = false
                }
            },
            { deleting = null }, danger = true, busy = busy,
        )
    }
}
