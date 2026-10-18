package ir.jirjirak.sms.ui

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
