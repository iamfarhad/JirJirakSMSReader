package ir.jirjirak.sms.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.Beat
import ir.jirjirak.sms.BuildConfig
import ir.jirjirak.sms.KeepAliveService
import ir.jirjirak.sms.R
import ir.jirjirak.sms.StatusWidget
import ir.jirjirak.sms.Store
import ir.jirjirak.sms.SyncJob
import ir.jirjirak.sms.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «بیشتر»: a list of places (42), each its own screen. */
@Composable
fun MoreTab(store: Store, now: Long, route: String) {
    watchStore()
    when (route) {
        "security" -> SecurityScreen(store)
        "notify" -> NotificationsScreen(store)
        "formats" -> FormatsScreen(store)
        "update" -> UpdateScreen(store, now)
        "appearance" -> AppearanceScreen(store, now)
        "connection" -> ConnectionScreen(store, now)
        "subscription" -> SubscriptionScreen(store, now)
        // Off, the feature has no screens: a stale route (a rotation, a test) lands on the hub.
        "hooks" -> if (store.hooksOn) HooksScreen(store, now) else MoreHub(store, now)
        else -> if (route.startsWith("hook:") && store.hooksOn) HookEditScreen(store, route.removePrefix("hook:")) else MoreHub(store, now)
    }
}

@Composable
private fun MoreHub(store: Store, now: Long) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var enablingHooks by remember { mutableStateOf(false) }
    val alive = States.online(store.lastPing, now)
    val summary = SummaryCache.value.takeIf { store.manageKey != null }
    val latest = Updates.available(context)
    val sub = store.shopInfo.optJSONObject("subscription")

    Hero(store.shop ?: "فروشگاه", "اتصال این گوشی و تنظیمات آن · " + (if (alive) "متصل" else "بی‌خبر از ربات"), if (alive) Tone.good else Tone.warn, icon = Ico.Bank)
    AppCard(title = "دسترسی سریع") {
        ListRow(
            Ico.Receipt, Tone.warn, "رسیدهای منتظر",
            summary?.let { Persian.number(it.metrics.needsReview) + " مورد نیازمند تصمیم" } ?: if (store.manageKey == null) "نیازمند کد مدیریت" else "تعداد هنوز دریافت نشده",
            badge = summary?.let { Persian.number(it.metrics.needsReview) }, onClick = { nav.receipts(1) },
        )
        Divider()
        ListRow(
            Ico.Alert, Tone.warn, "مغایرت‌ها",
            summary?.let { Persian.number(it.openMismatch) + " واریز بی‌فاکتور یا پیامک خوانده‌نشده" } ?: "واریز بی‌فاکتور و پیامک خوانده‌نشده",
            badge = summary?.let { Persian.number(it.openMismatch) }, onClick = { nav.receipts(3) },
        )
        Divider()
        ListRow(Ico.Copy, Tone.info, "گزارش پشتیبانی", "بدون متن پیامک‌ها و بدون هیچ کدی", badge = if (copied) "کپی شد" else "کپی", onClick = {
            clipboard.setText(AnnotatedString(diagnostics(context, store, now)))
            copied = true
        })
    }
    AppCard(title = "تنظیمات") {
        Rows(
            { ListRow(Ico.Shield, Tone.muted, "امنیت و دسترسی‌ها", "کد اتصال و کد مدیریت، جدا از هم", onClick = { nav.more("security") }) },
            { ListRow(Ico.Bell, Tone.muted, "اعلان‌ها", "رسید منتظر، مغایرت، سکوت شبانه", onClick = { nav.more("notify") }) },
            { ListRow(Ico.Sun, Tone.muted, "ظاهر و ویجت", "روشن، تاریک، مانند گوشی", onClick = { nav.more("appearance") }) },
            { ListRow(Ico.Sync, if (latest != null) Tone.good else Tone.muted, "به‌روزرسانی", latest?.let { "نسخهٔ " + Persian.sender(it.version) + " آماده است" } ?: "آخرین نسخهٔ برنامه", badge = latest?.let { "جدید" }, onClick = { nav.more("update") }) },
            { ListRow(Ico.Bank, Tone.muted, "قالب‌های بانک", "قالب‌های آموزش‌داده‌شده", onClick = { nav.more("formats") }) },
            {
                ListRow(
                    Ico.Clock, when (sub?.optString("state")) { "expired" -> Tone.bad; "grace" -> Tone.warn; else -> Tone.muted }, "اشتراک جیرجیرک",
                    sub?.optString("label")?.ifEmpty { null } ?: "سطح و تاریخ انقضای اشتراک فروشگاه",
                    badge = when (sub?.optString("state")) { "expired" -> "منقضی"; "grace" -> "مهلت تسویه"; else -> null },
                    onClick = { nav.more("subscription") },
                )
            },
            {
                // Off by default: the row only offers the feature; turned on, it is the way in.
                val hooks = store.hooks
                if (!store.hooksOn) {
                    ListRow(Ico.External, Tone.muted, "وب‌هوک‌های دیگر", "ارسال پیامک یک شماره به آدرس دلخواه شما · خاموش",
                        badge = "روشن کردن", badgeTone = Tone.info, onClick = { enablingHooks = true })
                } else {
                    ListRow(
                        Ico.External, if (hooks.any { it.on && it.lastOk == false }) Tone.warn else Tone.good, "وب‌هوک‌های دیگر",
                        if (hooks.isEmpty()) "روشن · هنوز وب‌هوکی اضافه نشده"
                        else Persian.number(hooks.size) + " وب‌هوک · " + Persian.number(hooks.count { it.on }) + " روشن",
                        badge = "روشن", onClick = { nav.more("hooks") },
                    )
                }
            },
            { ListRow(Ico.Info, Tone.muted, "اتصال فروشگاه", "مدیریت اتصال این گوشی", onClick = { nav.more("connection") }) },
        )
    }
    JirjirakLinks()
    VersionLine()
    if (enablingHooks) {
        ConfirmDialog(
            "«وب‌هوک‌های دیگر» روشن شود؟",
            "با روشن کردن، می‌توانید آدرس وب‌هوک خودتان را وارد کنید و پیامک بانکِ یک شمارهٔ مشخص، جدا از ربات، به آن آدرس هم فرستاده شود. " +
                "تا وب‌هوکی اضافه نکنید چیزی فرستاده نمی‌شود و هر وقت بخواهید خاموشش می‌کنید.",
            "روشن کن", {
                store.hooksOn = true
                store.event("system", "info", "قابلیت «وب‌هوک‌های دیگر» روشن شد")
                enablingHooks = false
                nav.more("hooks")
            }, { enablingHooks = false }, cancel = "نه، فعلاً",
        )
    }
}

// ── keys on this phone (43, 93) ─────────────────────────────────────────

/**
 * What this phone can do on the shop's behalf. Says whether a key is held — never the key itself,
 * which would turn a screenshot sent to support into the power to approve payments.
 */
@Composable
private fun SecurityScreen(store: Store) {
    val nav = LocalNav.current
    var forgetting by remember { mutableStateOf(false) }
    ScreenHeader("امنیت و دسترسی‌ها", "بیشتر") { nav.more() }
    Hero("امنیت و دسترسی‌ها", "کنترل جداگانهٔ ارسال پیامک و مدیریت رسیدها", Tone.info, Art.Security)
    AppCard(title = "دسترسی‌های این گوشی") {
        Rows(
            {
                ListRow(Ico.Check, if (store.endpoint != null) Tone.good else Tone.muted, "ارسال پیامک‌های بانکی",
                    if (store.endpoint != null) "کد اتصال روی گوشی ذخیره است" else "به فروشگاهی وصل نیست",
                    badge = if (store.endpoint != null) "فعال" else "غیرفعال")
            },
            {
                ListRow(Ico.Lock, if (store.manageKey != null) Tone.good else if (store.manageKeyRevoked) Tone.bad else Tone.muted, "تأیید و رد رسید از اپ",
                    when { store.manageKey != null -> "کد مدیریت جدا ثبت شده است"; store.manageKeyRevoked -> "ربات کد قبلی را نپذیرفت"; else -> "کد مدیریت ثبت نشده" },
                    badge = when { store.manageKey != null -> "فعال"; store.manageKeyRevoked -> "باطل"; else -> "ثبت نشده" },
                    onClick = if (store.manageKey == null) ({ nav.receipts() }) else null)
            },
        )
        // Said exactly: the two codes are sealed with the phone's Keystore; the queue's text is not
        // encrypted on its own — it sits in the app's private storage.
        Notice(
            "کدها با کلید امن گوشی (Keystore) رمز می‌شوند و در برنامه نمایش داده نمی‌شوند.",
            "متن پیامک‌های در صف جداگانه رمز نشده و فقط در حافظهٔ خصوصی همین برنامه نگه داشته می‌شود. اگر گوشی را به کسی می‌دهید، کد مدیریت را پاک کنید؛ ارسال پیامک‌ها ادامه پیدا می‌کند.",
            Tone.good, Ico.Shield,
        )
    }
    if (store.manageKey != null) DangerButton("پاک کردن کد مدیریت از این گوشی", { forgetting = true })
    if (forgetting) {
        ConfirmDialog(
            "کد مدیریت پاک شود؟",
            "تأیید و رد رسید از این گوشی غیرفعال می‌شود تا دوباره کد را از ربات بگیرید. ارسال پیامک‌های مجاز و صف آن‌ها ادامه پیدا می‌کند.",
            "پاک کردن کد مدیریت", {
                store.manageKey = null
                store.manageKeyRevoked = false
                SummaryCache.clear()
                store.event("system", "info", "کد مدیریت رسیدها از این گوشی پاک شد")
                forgetting = false
            }, { forgetting = false }, cancel = "حفظ دسترسی", danger = true,
        )
    }
}

// ── updates (47, 58, 95, 96) ────────────────────────────────────────────

@Composable
private fun UpdateScreen(store: Store, now: Long) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val latest = Updates.available(context)
    var checking by remember { mutableStateOf(false) }
    // The answer of «بررسی دوباره», said where it cannot be missed.
    var failed by remember { mutableStateOf<Boolean?>(null) }
    val installed = BuildConfig.VERSION_NAME.substringBefore('-')

    fun check() {
        checking = true
        failed = null
        scope.launch {
            val found = withContext(Dispatchers.IO) { Updates.check(context.applicationContext) }
            checking = false
            failed = found == null
        }
    }

    ScreenHeader("به‌روزرسانی", "بیشتر") { nav.more() }
    when {
        checking -> Hero("در حال بررسی نسخه", "نتیجه از ربات یا صفحهٔ انتشار برنامه دریافت می‌شود.", Tone.info, icon = Ico.Download)
        latest != null -> Hero("نسخهٔ جدید آماده است", "نسخهٔ " + Persian.sender(latest.version) + " منتشر شده است.", Tone.good, Art.Update)
        failed == true -> Hero("بررسی نسخه انجام نشد", "نه ربات پاسخ داد و نه صفحهٔ انتشار در دسترس بود.", Tone.warn, Art.Update)
        failed == false -> Hero("نسخهٔ تازه‌ای پیدا نشد", "نسخهٔ نصب‌شده با آخرین انتشار مقایسه شد و به‌روز است.", Tone.good, Art.Update)
        else -> Hero("به‌روزرسانی", "برنامه هر چند ساعت یک بار نسخهٔ تازه را می‌بیند و با اعلان خبر می‌دهد.", Tone.info, Art.Update)
    }
    if (checking) Waiting("بررسی…")
    AppCard(title = "وضعیت برنامه") {
        Rows(
            { ListRow(Ico.Info, Tone.muted, "نسخهٔ نصب‌شده", Persian.sender(installed)) },
            {
                ListRow(Ico.Download, if (latest != null) Tone.good else Tone.muted, "آخرین نسخه",
                    latest?.let { Persian.sender(it.version) } ?: (store.latestVersion?.let { Persian.sender(it) } ?: "هنوز بررسی نشده"),
                    badge = if (latest != null) "جدید" else if (store.lastUpdateCheck > 0) "به‌روز" else null)
            },
            { ListRow(Ico.Clock, if (failed == true) Tone.bad else Tone.muted, "آخرین بررسی", if (failed == true) "ناموفق" else Persian.ago(store.lastUpdateCheck, now), badge = if (failed == true) "خطا" else null) },
        )
        if (latest != null) PrimaryButton("دریافت نسخهٔ " + Persian.digits(latest.version), { Updates.open(context, latest.url) }, icon = Ico.External)
        if (failed == true) PrimaryButton("دوباره بررسی کن", { check() }, icon = Ico.Refresh)
        else SecondaryButton(if (checking) "در حال بررسی" else "بررسی دوباره", { check() }, busy = checking)
    }
    if (failed == true) Notice("نسخهٔ فعلی همچنان قابل استفاده است.", null, Tone.info)
    Notice("نصب خودکار انجام نمی‌شود", "«دریافت» لینک را در مرورگر باز می‌کند؛ بعد از دانلود، فایل را باز کنید و «نصب» را بزنید. تنظیمات و شماره‌ها می‌مانند.", Tone.info)
}

// ── look and the home-screen widget (48, 51, 63) ────────────────────────

@Composable
private fun AppearanceScreen(store: Store, now: Long) {
    val context = LocalContext.current
    val nav = LocalNav.current
    var unsupported by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    ScreenHeader("ظاهر و ویجت", "بیشتر") { nav.more() }
    Hero("ظاهر و ویجت", "نمایی ساده و خوانا، روز و شب", Tone.info, Art.Settings)
    AppCard(title = "حالت رنگ") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("system" to "مانند گوشی", "light" to "روشن", "dark" to "تاریک").forEach { (key, label) ->
                Chip(label, store.theme == key, { store.theme = key }, Modifier.weight(1f))
            }
        }
    }
    AppCard(title = "ویجت صفحهٔ اصلی") {
        Text("وضعیت ارتباط و شمار پیامک‌های امروز را بدون باز کردن برنامه ببینید.", style = Type.supporting, color = Brand.Muted)
        WidgetPreview(store, now)
        if (unsupported) {
            Notice("افزودن مستقیم ممکن نیست", "لانچر این گوشی افزودن مستقیم ویجت را پشتیبانی نمی‌کند؛ از روش دستی استفاده کنید.", Tone.info)
            Steps(listOf("روی یک جای خالی صفحهٔ اصلی گوشی انگشت را نگه دارید.", "«ابزارک‌ها» یا «" + Persian.latin("Widgets") + "» را انتخاب کنید.", "ویجت «جیرجیرک پیامک» را پیدا کنید و روی صفحه بکشید."))
            SecondaryButton("متوجه شدم", { unsupported = false })
        } else {
            PrimaryButton("افزودن ویجت به صفحهٔ اصلی", {
                if (StatusWidget.requestPin(context)) asked = true else unsupported = true
            }, icon = Ico.Phone)
            if (asked) Text("گوشی می‌پرسد ویجت کجا قرار بگیرد؛ «افزودن» را بزنید.", style = Type.supporting, color = Brand.Muted)
        }
    }
}

/** The widget as it would look now — its two lines are the widget's own, from the Store. */
@Composable
private fun WidgetPreview(store: Store, now: Long) {
    val (title, sub) = StatusWidget.lines(store.endpoint != null, store.senders.size, store.lastPing, store.sentToday(now), store.queueSize(), now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brand.Canvas).border(1.dp, Brand.Line, RoundedCornerShape(20.dp)).padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.jrs_mascot), contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.Green))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = Brand.Ink)
            Text(sub, style = Type.supporting, color = Brand.Muted)
        }
    }
    Text("پیش‌نمایش با وضعیت واقعی همین حالا", style = Type.badge, color = Brand.Muted)
}

// ── the shop's own subscription (53, 54) ────────────────────────────────

@Composable
private fun SubscriptionScreen(store: Store, now: Long) {
    val context = LocalContext.current
    val nav = LocalNav.current
    ScreenHeader("اشتراک جیرجیرک", "بیشتر") { nav.more() }
    val sub = store.shopInfo.optJSONObject("subscription")
    if (sub == null) {
        Hero("اشتراک جیرجیرک", "ربات هنوز اطلاعات اشتراک را نفرستاده است.", Tone.info, Art.Subscription)
        Notice("بعد از ارتباط بعدی با ربات اینجا نمایش داده می‌شود.", null, Tone.info)
        return
    }
    val state = sub.optString("state")
    val expires = sub.optLong("expires_at", 0) * 1000
    val days = sub.optInt("days_left", 0)
    when {
        state == "expired" -> Hero("اشتراک فروشگاه منقضی شد", "ربات فروشگاه برای ادامهٔ کار نیازمند تمدید است.", Tone.bad, Art.Subscription)
        state == "grace" -> Hero("اشتراک در مهلت تسویه است", "برای جلوگیری از وقفه، اشتراک را تمدید کنید.", Tone.warn, Art.Subscription)
        state == "active" && days <= 5 -> Hero("اشتراک به‌زودی تمام می‌شود", Persian.number(days) + " روز دیگر مانده است.", Tone.warn, Art.Subscription)
        else -> Hero("اشتراک فعال است", sub.optString("label").ifEmpty { "اشتراک جیرجیرک" }, Tone.good, Art.Subscription)
    }
    AppCard(title = "اشتراک جیرجیرک") {
        Rows(
            { ListRow(Ico.Info, Tone.muted, "سطح اشتراک", sub.optString("label").ifEmpty { "—" }) },
            {
                ListRow(
                    Ico.Clock, when (state) { "expired" -> Tone.bad; "grace" -> Tone.warn; else -> Tone.good }, "وضعیت",
                    when {
                        state == "unlimited" || expires <= 0 -> "بدون انقضا"
                        expires < now -> "منقضی شده در " + Persian.date(expires)
                        else -> "انقضا " + Persian.date(expires) + " · " + Persian.number(days) + " روز دیگر"
                    },
                    badge = when (state) { "expired" -> "منقضی"; "grace" -> "نیازمند اقدام"; "unlimited" -> "بدون انقضا"; else -> "فعال" },
                )
            },
        )
        if (state == "expired") Notice("تا زمان تمدید، ربات ممکن است سفارش‌ها را پردازش نکند.", null, Tone.bad)
        if (state == "expired" || state == "grace" || (state == "active" && days <= 5)) {
            PrimaryButton("تمدید اشتراک در ربات جیرجیرک", { openTelegram(context, "JirjirakPremiumBot") }, icon = Ico.External)
        }
        store.shopInfo.optString("bot").takeIf { it.isNotEmpty() }?.let { bot ->
            Divider()
            ListRow(Ico.Mail, Tone.info, "ربات فروشگاه شما", Persian.latin("@" + bot.removePrefix("@")), onClick = { openTelegram(context, bot.removePrefix("@")) })
        }
    }
}

// ── this connection (49) ────────────────────────────────────────────────

@Composable
private fun ConnectionScreen(store: Store, now: Long) {
    val context = LocalContext.current
    val nav = LocalNav.current
    var asking by remember { mutableStateOf(false) }
    val alive = States.online(store.lastPing, now)
    val queued = store.queueSize()
    ScreenHeader("اتصال فروشگاه", "بیشتر") { nav.more() }
    Hero(store.shop ?: "فروشگاه", (store.server?.let { Persian.latin(it) } ?: "—") + " · " + (if (alive) "اتصال فعال" else "بی‌خبر از ربات"), if (alive) Tone.good else Tone.warn, icon = Ico.Bank)
    AppCard(title = "این گوشی") {
        Rows(
            { ListRow(Ico.Wifi, if (alive) Tone.good else Tone.warn, "وضعیت", "آخرین ارتباط: " + Persian.ago(store.lastPing, now) + " · آخرین پیامک: " + Persian.ago(store.lastSent, now), badge = if (alive) "متصل" else "بی‌خبر") },
            { ListRow(Ico.Bank, Tone.info, "فرستنده‌های مجاز", store.senders.takeIf { it.isNotEmpty() }?.joinToString("، ") { Persian.sender(it) } ?: "هیچ", badge = Persian.number(store.senders.size)) },
            { ListRow(Ico.Inbox, if (queued > 0) Tone.warn else Tone.good, "صف ارسال", if (queued > 0) "هنوز پاسخ موفق ندارند." else "خالی", badge = Persian.number(queued) + " پیامک") },
            { ListRow(Ico.Phone, Tone.muted, "گوشی", Persian.latin(Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE)) },
        )
    }
    Text("قطع اتصال، فرستادن پیامک به این فروشگاه را متوقف می‌کند. برای عوض کردن گوشی یا وصل شدن به فروشگاه دیگر لازم است.", style = Type.supporting, color = Brand.Muted)
    DangerButton("قطع اتصال از این فروشگاه", { asking = true })
    if (asking) {
        ConfirmDialog(
            "اتصال قطع شود؟", States.unpairText(store.queueSize()),
            if (store.queueSize() > 0) "قطع اتصال و حذف صف" else "قطع اتصال",
            {
                // Everything that wakes the phone for this app stops with the pairing.
                store.event("system", "info", "اتصال از فروشگاه قطع شد", if (queued > 0) Persian.number(queued) + " پیامک صف حذف شد" else null)
                store.unpair()
                SummaryCache.clear()
                ir.jirjirak.sms.Alerts.clear(context)
                StatusWidget.refresh(context)
                KeepAliveService.stop(context)
                Beat.cancel(context)
                SyncJob.cancelAll(context)
                asking = false
                nav.home()
            },
            { asking = false }, cancel = "حفظ اتصال", danger = true,
        )
    }
}
