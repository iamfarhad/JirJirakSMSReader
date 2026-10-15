package ir.jirjirak.sms.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Recovery
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One setting: what it does in a line, and its switch. The whole row is the touch target. */
@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) Brand.Ink else Brand.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Green, checkedThumbColor = Brand.Surface),
        )
    }
}

/**
 * «بازیابی پیامک‌های جامانده»: turning it on asks for READ_SMS (Android shows its own dialog) and
 * sets the starting point — nothing from before this moment is ever sent.
 */
@Composable
internal fun RecoveryCard(store: Store, now: Long) {
    watchStore()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val permitted = Recovery.permitted(context)

    fun enable() {
        store.recoveryOn = true
        // From now, every time: SMS from while it was off are not «missed», they were declined.
        store.recoveryFrom = System.currentTimeMillis()
        store.event("system", "info", "بازیابی پیامک‌های جامانده روشن شد")
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else result = "بدون اجازهٔ خواندن پیامک، بازیابی کار نمی‌کند. اگر اندروید اجازه نداد: تنظیمات ← برنامه‌ها ← جیرجیرک پیامک ← اجازه‌ها ← پیامک."
        Store.revision.intValue++
    }

    Section("بازیابی پیامک‌های جامانده", Icons.Filled.Refresh, Brand.Blue) {
        SettingSwitch(
            "پیامک‌های جامانده را پیدا و ارسال کن",
            "اگر برنامه بسته یا گوشی خاموش بوده، پیامک بانکِ همان فاصله از صندوق پیامک پیدا و فرستاده می‌شود — فقط از شماره‌های مجاز، هیچ‌وقت رمز یکبار مصرف، حداکثر ۲ روز.",
            checked = store.recoveryOn && permitted,
        ) { on ->
            result = null
            when {
                !on -> { store.recoveryOn = false; store.event("system", "info", "بازیابی پیامک‌های جامانده خاموش شد") }
                permitted -> enable()
                else -> ask.launch(Manifest.permission.READ_SMS)
            }
        }
        if (store.recoveryOn && permitted) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "آخرین جست‌وجو: " + Persian.ago(store.lastRecovery, now),
                    color = Brand.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !scanning, onClick = {
                    scanning = true
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { Recovery.scan(context.applicationContext, force = true) }
                        scanning = false
                        result = if (n > 0) "✅ " + Persian.number(n) + " پیامک جامانده پیدا و فرستاده شد." else "✅ پیامک جامانده‌ای نبود."
                    }
                }) { Text(if (scanning) "در حال جست‌وجو…" else "الان جست‌وجو کن") }
            }
        }
        result?.let { Notice(it, if (it.startsWith("✅")) Brand.GoodSoft else Brand.WarnSoft, if (it.startsWith("✅")) Brand.Good else Brand.Warn) }
    }
}

/** Which counts the phone tells about, and the quiet nights. Approvals and warnings are not here: they always speak. */
@Composable
internal fun NotificationsCard(store: Store) {
    watchStore()
    Section("اعلان‌ها", Icons.Filled.Notifications, Brand.Warn) {
        val ready = store.manageKey != null
        if (!ready) {
            Text("برای اعلان رسیدهای منتظر و مغایرت‌ها، کد مدیریت رسیدها را در تب «رسیدها» وارد کنید.", color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        SettingSwitch("رسید منتظر بررسی", "وقتی رسیدی خودکار تأیید نشد و تصمیم شما را می‌خواهد.", store.notifyReview, ready) { store.notifyReview = it }
        HorizontalDivider(color = Brand.Line)
        SettingSwitch("مغایرت تازه", "واریزی که به هیچ فاکتوری نخورد، یا پیامکی که خوانده نشد.", store.notifyMismatch, ready) { store.notifyMismatch = it }
        HorizontalDivider(color = Brand.Line)
        SettingSwitch("سکوت شبانه", "از ساعت ۲۳ تا ۸ صبح این دو اعلان نمی‌آیند و صبح یک‌جا خبر می‌دهند. تأیید سفارش و هشدار قطعی همیشه می‌آید.", store.quietNights) { store.quietNights = it }
    }
}

/** The bank formats this shop taught — a list with a delete, like every collection in the bot. */
@Composable
internal fun FormatsCard(store: Store) {
    watchStore()
    if (store.manageKey == null || !store.supports("formats")) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var formats by remember { mutableStateOf<List<Receipts.Format>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Receipts.Format?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        when (val r = withContext(Dispatchers.IO) { Receipts.formats(context.applicationContext) }) {
            is Receipts.Result.Ok -> { formats = r.value; error = null }
            Receipts.Result.NeedKey -> store.revokeManageKey()
            is Receipts.Result.Failed -> error = r.message
            Receipts.Result.AlreadyDecided -> {}
        }
    }

    Section("قالب‌های آموزش‌داده‌شدهٔ بانک", Icons.Filled.Edit, Brand.Green) {
        error?.let { Text(it, color = Brand.Bad, fontSize = 12.sp) }
        val list = formats
        when {
            list == null && error == null -> Skeleton(Modifier.fillMaxWidth(), height = 48)
            list == null -> {}
            list.isEmpty() -> Text(
                "هنوز قالبی آموزش داده نشده. وقتی پیامک بانکی خوانده نشد، در «رسیدها ← مغایرت» دکمهٔ «آموزش قالب» را بزنید.",
                color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
            else -> list.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(color = Brand.Line)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(Persian.sender(f.sender) + " · " + (if (f.unit == "toman") "تومان" else "ریال"), color = Brand.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            Persian.number(f.hits) + " پیامک خوانده شده" + (if (f.lastHit > 0) " · آخرین بار " + Persian.ago(f.lastHit, System.currentTimeMillis()) else ""),
                            color = Brand.Muted, fontSize = 12.sp,
                        )
                        Text(Persian.digits(f.sample.replace('\n', ' ')), color = Brand.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { deleting = f }) { Icon(Icons.Filled.Delete, contentDescription = "حذف قالب", tint = Brand.Bad) }
                }
            }
        }
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("حذف قالب") },
            text = { Text("از این به بعد پیامک‌های این شکل از " + Persian.sender(f.sender) + " دوباره «خوانده نشد» می‌شوند. واریزهای قبلی دست نمی‌خورند.", lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        when (val r = withContext(Dispatchers.IO) { Receipts.forgetFormat(context.applicationContext, f.id) }) {
                            is Receipts.Result.Ok -> attempt++
                            Receipts.Result.NeedKey -> store.revokeManageKey()
                            is Receipts.Result.Failed -> error = r.message
                            Receipts.Result.AlreadyDecided -> attempt++
                        }
                    }
                }) { Text("حذف کن", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("انصراف") } },
        )
    }
}
