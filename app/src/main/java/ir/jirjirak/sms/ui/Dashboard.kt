package ir.jirjirak.sms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.HealthCheck
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Net
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bot's last summary, kept for the whole run: leaving the tab does not throw today's figures
 * away, and a failed refresh keeps the older answer — labelled with its age, never passed off as now.
 */
object SummaryCache {
    var value by mutableStateOf<Receipts.Summary?>(null)
    var at by mutableLongStateOf(0L)
    var failed by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    fun clear() { value = null; at = 0; failed = null }

    suspend fun refresh(context: android.content.Context, store: Store) {
        if (store.manageKey == null || loading) return
        loading = true
        when (val r = withContext(Dispatchers.IO) { Receipts.summary(context.applicationContext) }) {
            is Receipts.Result.Ok -> { value = r.value; at = System.currentTimeMillis(); failed = null }
            Receipts.Result.NeedKey -> { store.revokeManageKey(); clear() }
            is Receipts.Result.Failed -> failed = r.message
            Receipts.Result.AlreadyDecided -> {}
        }
        loading = false
    }
}

/** «خانه» and its sub-screens, by route. */
@Composable
fun HomeTab(store: Store, now: Long, resumed: Int, route: String) {
    when (route) {
        "setup" -> SetupScreen(store, resumed)
        "sms_permission" -> SmsPermissionScreen()
        "restricted" -> RestrictedScreen()
        "setup_done" -> SetupDoneScreen(store)
        "notify_denied" -> NotifyDeniedScreen()
        "senders" -> SendersScreen(store)
        "pick" -> PickSenderScreen(store)
        "manual" -> ManualSenderScreen(store)
        "health" -> HealthScreen(store)
        "queue" -> QueueScreen(store, now)
        else -> Dashboard(store, now, resumed)
    }
}

/**
 * The dashboard — in a few seconds: is it working, what did the bot approve, what is asked of me.
 * The figures are the bot's own (…/receipts/summary); without the manage code it says what the
 * phone itself knows and offers the code, never an invented number.
 */
@Composable
private fun Dashboard(store: Store, now: Long, resumed: Int) {
    watchStore()
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(store.manageKey, resumed) { SummaryCache.refresh(context, store) }
    // Back in the app: anything that arrived while it could not hear is looked for now.
    LaunchedEffect(resumed) { withContext(Dispatchers.IO) { runCatching { ir.jirjirak.sms.Recovery.scan(context.applicationContext) } } }

    val summary = SummaryCache.value.takeIf { store.manageKey != null }
    val sms = smsGranted(context)
    val online = States.online(store.lastPing, now)
    val pending = store.queueSize()
    val hero = States.homeHero(store.lastError == "revoked", sms, store.senders.size, online, pending, summary?.autoOn)

    when (hero) {
        States.HomeHero.REVOKED -> {
            Hero("ربات گوشی را نمی‌شناسد", "احتمالاً کد اتصال فروشگاه در ربات تغییر کرده است.", Tone.bad, Art.Security)
            if (pending > 0) Notice("قبل از قطع اتصال، صف را ببینید", "قطع اتصال در این نسخه " + Persian.number(pending) + " پیامک در صف این فروشگاه را حذف می‌کند؛ این صف به فروشگاه دیگری منتقل نمی‌شود.", Tone.warn)
            Notice("نیاز به کد تازه", "کد قبلی قابل استفاده نیست؛ از ربات کد جدید دریافت کنید.", Tone.bad)
            AppCard(title = "بازگشت به حالت فعال") {
                Steps(listOf("در ربات فروشگاه کد اتصال جدید را کپی کنید.", "اتصال قبلی را از «بیشتر ← اتصال فروشگاه» قطع کنید.", "کد تازه را بچسبانید."))
                PrimaryButton("رفتن به اتصال فروشگاه", { nav.more("connection") })
            }
        }
        States.HomeHero.NO_SMS -> {
            Hero("دسترسی پیامک غیرفعال است", "برنامه بدون این اجازه، پیامک بانکی تازه را دریافت نمی‌کند.", Tone.bad, icon = Ico.Lock)
            AppCard(title = "اقدامات موردنیاز") {
                ListRow(Ico.Shield, Tone.warn, "مجوز دریافت پیامک", "درخواست دوباره یا رفتن به تنظیمات", badge = "اجازه بده", onClick = { nav.home("sms_permission") })
                if (pending > 0) {
                    Divider()
                    ListRow(Ico.Inbox, Tone.info, "پیامک‌های در صف", "ارسال پیامک‌هایی که قبلاً رسیده بودند ادامه پیدا می‌کند.", badge = Persian.number(pending), onClick = { nav.home("queue") })
                }
            }
        }
        States.HomeHero.NO_SENDERS -> {
            Hero("راه‌اندازی کامل نیست", "هنوز شمارهٔ بانکی مجاز نشده؛ تا انتخاب نشود هیچ پیامکی ارسال نمی‌شود.", Tone.warn, Art.Bank)
            PrimaryButton("انتخاب فرستندهٔ بانک", { nav.home("senders") })
        }
        States.HomeHero.OFFLINE -> {
            Hero(
                "ارتباط با ربات قطع است",
                if (pending > 0) Persian.number(pending) + " پیامک ذخیره‌شده منتظر اتصال هستند." else "پیامک‌های تازه روی گوشی صف می‌شوند و بعد فرستاده می‌شوند.",
                Tone.warn, Art.Offline,
            )
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("در صف", Persian.number(pending), "پیامک", Modifier.weight(1f).fillMaxHeight())
                MetricTile("آخرین ارتباط", Persian.ago(store.lastPing, now).removeSuffix(" پیش"), if (store.lastPing > 0) "پیش" else "", Modifier.weight(1f).fillMaxHeight())
            }
            Notice("صف را نگه دارید", "با برگشتن ارتباط، ارسال خودکار ادامه پیدا می‌کند. قطع اتصال از فروشگاه یا پاک‌کردن دادهٔ برنامه صف را حذف می‌کند.", Tone.warn)
            PingNow()
        }
        States.HomeHero.QUEUE -> {
            Hero(Persian.number(pending) + " پیامک در صف است", "برای این پیامک‌ها هنوز پاسخ موفق از ربات نرسیده است.", Tone.warn, Art.Queue)
            PrimaryButton("مشاهدهٔ صف ارسال", { nav.home("queue") })
        }
        States.HomeHero.AUTO_ON -> Hero(
            "ارسال و تأیید خودکار فعال است", "پیامک‌های بانک می‌رسند و ربات رسیدهای مطابق را خودکار تأیید می‌کند.",
            Tone.good, Art.Dashboard, filled = true, eyebrow = "آخرین ارتباط: " + Persian.ago(store.lastPing, now),
        )
        States.HomeHero.AUTO_OFF -> {
            Hero("پیامک‌ها به ربات می‌رسند", "دریافت برقرار است، اما «تأیید واقعی سفارش» در ربات خاموش است.", Tone.info, icon = Ico.Mail)
            Notice("تأیید خودکار غیرفعال است", "ربات فقط نظر می‌دهد. از تنظیمات ربات، «تأیید واقعی سفارش» را روشن کنید.", Tone.warn)
        }
        States.HomeHero.FORWARDING -> Hero(
            "پیامک‌ها به ربات می‌رسند",
            if (store.manageKey == null) "برای دیدن آمار و مدیریت رسیدها یک کد جدا لازم است." else if (SummaryCache.loading) "در حال همگام‌سازی آمار با ربات…" else "پیامک‌های بانک برای بررسی سفارش‌ها فرستاده می‌شوند.",
            Tone.good, icon = if (SummaryCache.loading) Ico.Sync else Ico.Mail,
            eyebrow = "آخرین ارتباط: " + Persian.ago(store.lastPing, now),
        )
    }

    SubscriptionNotice(store)
    StatsBlock(store, now) { scope.launch { SummaryCache.refresh(context, store) } }
    ActionsNeeded(store, summary, now)
    HealthStrip(store, summary, now)
    RecentActivity(store, summary, now)
}

/** The shop's own subscription ran out or is in grace (53/54): said on Home, fixed in «بیشتر». */
@Composable
private fun SubscriptionNotice(store: Store) {
    val nav = LocalNav.current
    val state = store.shopInfo.optJSONObject("subscription")?.optString("state") ?: return
    when (state) {
        "expired" -> AppCard { ListRow(Ico.Alert, Tone.bad, "اشتراک فروشگاه منقضی شد", "تا تمدید، ربات ممکن است سفارش‌ها را پردازش نکند.", badge = "منقضی", onClick = { nav.more("subscription") }) }
        "grace" -> AppCard { ListRow(Ico.Clock, Tone.warn, "اشتراک در مهلت تسویه است", "برای جلوگیری از وقفه، اشتراک را تمدید کنید.", badge = "نیازمند اقدام", onClick = { nav.more("subscription") }) }
    }
}

// ── the bot's figures ───────────────────────────────────────────────────

@Composable
private fun StatsBlock(store: Store, now: Long, onRetry: () -> Unit) {
    val nav = LocalNav.current
    val s = SummaryCache.value
    val state = States.stats(store.manageKey != null, s != null, SummaryCache.failed != null, SummaryCache.at, now)
    // At a large font two tiles side by side would squeeze each number: one column instead.
    val stack = LocalDensity.current.fontScale > 1.3f
    @Composable
    fun tiles(approved: String?, waiting: String?, deposits: String?, labelSuffix: String = "", unknownUnit: Boolean = false) {
        if (stack) {
            MetricTile("تأیید امروز$labelSuffix", approved, if (unknownUnit) "نامشخص" else "رسید")
            MetricTile("نیازمند بررسی$labelSuffix", waiting, if (unknownUnit) "نامشخص" else "رسید")
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("تأیید امروز$labelSuffix", approved, if (unknownUnit) "نامشخص" else "رسید", Modifier.weight(1f).fillMaxHeight())
                MetricTile("نیازمند بررسی$labelSuffix", waiting, if (unknownUnit) "نامشخص" else "رسید", Modifier.weight(1f).fillMaxHeight())
            }
        }
        MetricTile("واریزی امروز$labelSuffix", deposits, if (unknownUnit) "نامشخص" else "تومان")
    }
    when (state) {
        States.Stats.NO_KEY -> AppCard(title = "آمار پرداخت‌ها") {
            EmptyState("کد مدیریت هنوز اضافه نشده", "آمار دقیق و تأیید دستی فقط بعد از اتصال امن نمایش داده می‌شود. ارسال پیامک‌ها به آن نیازی ندارد.", Art.Security)
            PrimaryButton("افزودن کد مدیریت", { nav.receipts() })
        }
        States.Stats.LOADING -> {
            Waiting("دریافت آمار واقعی از ربات…")
            tiles(null, null, null)
        }
        States.Stats.FRESH, States.Stats.STALE -> {
            val m = s!!.metrics
            if (state == States.Stats.STALE) {
                Notice(
                    "آخرین آمار مربوط به " + Persian.ago(SummaryCache.at, now) + " است",
                    "وضعیت فعلی تأیید نشده" + (SummaryCache.failed?.let { "؛ " + Persian.digits(it) } ?: "") + ". برای تصمیم دربارهٔ هر رسید، وضعیت تازه را از ربات بگیرید.",
                    Tone.warn, Ico.Clock,
                )
            }
            tiles(
                Persian.number(m.autoApproved + m.manualApproved), Persian.number(m.needsReview),
                Money.toman(m.depositsTotal).removeSuffix(" تومان"),
                labelSuffix = if (state == States.Stats.STALE) " (آخرین آمار)" else "",
            )
            if (state == States.Stats.STALE) PrimaryButton("دریافت وضعیت تازه", onRetry, busy = SummaryCache.loading, icon = Ico.Refresh)
        }
        States.Stats.ERROR -> {
            tiles("—", "—", "—", unknownUnit = true)
            AppCard {
                EmptyState("هنوز آماری دریافت نشده", "نداشتن پاسخ به معنی صفر بودن پرداخت‌ها نیست. ارسال پیامک و دریافت آمار دو مسیر جدا هستند.", Art.Report)
                ErrorState(SummaryCache.failed ?: "ربات جواب نداد.", onRetry)
            }
        }
    }
}

// ── what is asked of the seller ─────────────────────────────────────────

@Composable
private fun ActionsNeeded(store: Store, summary: Receipts.Summary?, now: Long) {
    val nav = LocalNav.current
    data class Item(val icon: Ico, val tone: String, val title: String, val hint: String, val badge: String, val go: () -> Unit)
    // A queued SMS that could not even be stored (a full disk): recent, and not yet recovered.
    val storageFail = store.events().firstOrNull { it.optString("kind") == "sms" && it.optString("status") == "failed" && it.optString("title").contains("ذخیره نشد") && now - it.optLong("at") < 48 * 3600_000L }
    val items = buildList {
        if (storageFail != null) add(Item(Ico.Alert, "bad", "پیامک در صف ذخیره نشد", "حافظهٔ گوشی پر است یا پایگاه‌داده خطا داد.", "بررسی") { nav.more("recovery") })
        summary?.actions?.forEach { a ->
            when (a.kind) {
                "review" -> add(Item(Ico.Receipt, "warn", Persian.digits(a.title), a.hint, "بررسی") { nav.receipts(1) })
                "unmatched" -> add(Item(Ico.Bank, "info", Persian.digits(a.title), a.hint, "مشاهده") { nav.receipts(3) })
                "unreadable" -> add(Item(Ico.Mail, "warn", Persian.digits(a.title), a.hint, "مشاهده") { nav.receipts(3) })
            }
        }
    }
    if (items.isEmpty()) return
    AppCard(title = "نیاز به توجه شما") {
        items.forEachIndexed { i, r ->
            if (i > 0) Divider()
            ListRow(r.icon, Tone.of(r.tone), r.title, r.hint, badge = r.badge, onClick = r.go)
        }
    }
}

// ── health at a glance ──────────────────────────────────────────────────

@Composable
private fun HealthStrip(store: Store, summary: Receipts.Summary?, now: Long) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val online = States.online(store.lastPing, now)
    val sms = smsGranted(context)
    val pending = store.queueSize()
    val setup = States.setup(sms, store.senders.size, notificationsGranted(context), batteryFree(context), ir.jirjirak.sms.Autostart.needed, store.autostartConfirmed)
    AppCard(title = "سلامت ارسال") {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HealthTile(Ico.Wifi, "ارتباط ربات", if (online) "متصل" else "قطع", online, Modifier.weight(1f).fillMaxHeight()) { nav.home("health") }
            HealthTile(Ico.Shield, "دسترسی پیامک", if (sms) "مجاز" else "غیرفعال", sms, Modifier.weight(1f).fillMaxHeight()) { nav.home("setup") }
            HealthTile(Ico.Inbox, "صف ارسال", if (pending == 0) "خالی" else Persian.number(pending) + " پیامک", pending == 0, Modifier.weight(1f).fillMaxHeight()) { nav.home("queue") }
        }
        // Several phones on one shop: which of them is quiet, and which one this is.
        val devices = summary?.devices.orEmpty()
        if (devices.size > 1) {
            Divider()
            Text(Persian.number(devices.size) + " گوشی به این فروشگاه وصل است", style = Type.supporting, color = Brand.Muted)
            devices.forEach { d ->
                ListRow(
                    Ico.Phone, if (d.quiet) Tone.warn else Tone.good,
                    Persian.latin(d.model.ifBlank { "گوشی" }) + (if (d.mine) " · همین گوشی" else ""),
                    if (!d.quiet) "متصل" else if (d.at > 0) "آخرین ارتباط " + Persian.ago(d.at, now) else "بی‌خبر",
                    badge = if (d.quiet) "قطع" else "متصل",
                )
            }
        }
        Divider()
        ListRow(Ico.Settings, if (setup.ready && setup.extrasDone == setup.extrasTotal) Tone.good else Tone.warn, "آماده‌سازی گوشی",
            Persian.number(setup.essentialDone + setup.extrasDone) + " از " + Persian.number(setup.essentialTotal + setup.extrasTotal) + " مورد انجام شده",
            onClick = { nav.home("setup") })
        ListRow(Ico.Bank, if (store.senders.isEmpty()) Tone.warn else Tone.good, "شماره‌های بانک",
            if (store.senders.isEmpty()) "تعیین نشده" else Persian.number(store.senders.size) + " فرستندهٔ مجاز", onClick = { nav.home("senders") })
        ListRow(Ico.Check, Tone.info, "تست سلامت", "مسیر یک پیامک از گوشی تا ربات، بدون ثبت واریز", onClick = { nav.home("health") })
    }
}

/** «بررسی دوباره»: a ping now, its answer said on the button. */
@Composable
private fun PingNow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    PrimaryButton("بررسی دوباره", {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Net.ping(context.applicationContext) }
            if (ok) withContext(Dispatchers.IO) { Net.flush(context.applicationContext) }
            busy = false
            said = if (ok) null else "هنوز وصل نشد: " + (Net.lastPingReason ?: "اینترنت گوشی را بررسی کنید")
        }
    }, busy = busy, icon = Ico.Refresh)
    said?.let { Text(Persian.digits(it), style = Type.supporting, color = Brand.Bad) }
}

// ── recent activity ─────────────────────────────────────────────────────

@Composable
private fun RecentActivity(store: Store, summary: Receipts.Summary?, now: Long) {
    val nav = LocalNav.current
    val recent = summary?.recent
    if (recent.isNullOrEmpty()) {
        val events = store.events()
        var expanded by remember { mutableStateOf<String?>(null) }
        AppCard(title = "آخرین رویدادهای گوشی", trailing = { LinkButton("گزارش کامل", { nav.report() }) }) {
            if (events.isEmpty()) Text("هنوز چیزی ثبت نشده است. با اولین پیامک بانک، اینجا نشان داده می‌شود که چه شد.", style = Type.body, color = Brand.Muted)
            events.take(5).forEachIndexed { i, e ->
                if (i > 0) Divider()
                val id = e.optString("id")
                EventRow(e, expanded == id) { expanded = if (expanded == id) null else id }
            }
        }
        return
    }
    AppCard(title = "فعالیت‌های اخیر", trailing = { LinkButton("مشاهده همه", { nav.receipts(0) }) }) {
        recent.take(6).forEachIndexed { i, a ->
            if (i > 0) Divider()
            // Sending an SMS is not approving a payment: each row says which of the two happened.
            val (icon, tone, title) = when (a.kind) {
                "auto_approved" -> Triple(Ico.Check, Tone.good, "پرداخت " + Money.toman(a.amount) + " خودکار تأیید شد")
                "approved" -> Triple(Ico.Check, Tone.good, "پرداخت " + Money.toman(a.amount) + " تأیید شد")
                "rejected" -> Triple(Ico.Close, Tone.bad, "رسید " + Money.toman(a.amount) + " رد شد")
                "unmatched" -> Triple(Ico.Bank, Tone.info, "واریز " + Money.toman(a.amount) + " بدون فاکتور")
                else -> Triple(Ico.Alert, Tone.warn, "پیامک بانکی خوانده نشد")
            }
            ListRow(icon, tone, title, listOfNotNull(a.customer ?: a.sender?.let { "از " + Persian.sender(it) }, Persian.day(a.at, now) + " " + Persian.clock(a.at)).joinToString(" · "))
        }
    }
}

// ── the health test (20–22, 89) ─────────────────────────────────────────

@Composable
private fun HealthScreen(store: Store) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<HealthCheck.Step>?>(null) }
    val queued = store.queueSize()

    ScreenHeader("تست سلامت", "خانه") { nav.home() }
    val list = steps
    val outcome = list?.let { HealthCheck.outcome(it) }
    when {
        running -> Hero("در حال تست سلامت", "مجوزها، فرستنده‌ها، صف و پاسخ ربات بررسی می‌شوند.", Tone.info, Art.Health)
        outcome == HealthCheck.Outcome.GOOD -> Hero("مسیر پیامک سالم است", "همهٔ آزمون‌ها پاسخ معتبر داده‌اند؛ پیامک آزمایشی هیچ واریزی ثبت نکرد.", Tone.good, Art.Health)
        outcome == HealthCheck.Outcome.PARTIAL -> Hero("اتصال سالم؛ آزمون کامل نشده", "این نسخهٔ ربات آزمون بدون ثبت را اعلام نکرده است.", Tone.info, Art.HealthWarning)
        outcome == HealthCheck.Outcome.WARN -> Hero("کار می‌کند، ولی چند مورد بهتر است درست شود", "جزئیات هر مورد پایین آمده است.", Tone.warn, Art.HealthWarning)
        outcome == HealthCheck.Outcome.BAD -> Hero("چند مورد نیاز به اصلاح دارد", "تا رفع نشوند، پیامک‌ها ممکن است به ربات نرسند.", Tone.bad, Art.HealthWarning)
        else -> Hero("تست سلامت", "مسیر یک پیامک بانک از گوشی تا ربات، بدون ثبت هیچ واریزی.", Tone.info, Art.Health)
    }
    if (list == null) {
        // The test sends what is queued first — the real road. Said before it runs, not after.
        Notice(
            "صف واقعی ممکن است ارسال شود",
            (if (queued > 0) Persian.number(queued) + " پیامک در صف است و " else "") + "آزمون ابتدا صف موجود را برای ربات می‌فرستد. پیامک آزمایشی فقط اگر ربات «آزمون بدون ثبت» را اعلام کرده باشد فرستاده می‌شود و واریزی ثبت نمی‌کند.",
            Tone.warn,
        )
    }
    if (running) Waiting("در حال اجرای آزمون…")
    list?.let {
        AppCard(title = "جزئیات") {
            it.forEachIndexed { i, step ->
                if (i > 0) Divider()
                val (tone, badge) = when (step.state) {
                    HealthCheck.State.OK -> Tone.good to "سالم"
                    HealthCheck.State.WARN -> Tone.warn to "توجه"
                    HealthCheck.State.BAD -> Tone.bad to "مشکل"
                    HealthCheck.State.INFO -> Tone.info to if (step.key == "dry_run_missing") "بررسی‌نشده" else "اطلاع"
                }
                ListRow(
                    when (step.state) { HealthCheck.State.OK -> Ico.Check; HealthCheck.State.INFO -> Ico.Info; else -> Ico.Alert },
                    tone, step.title, Persian.digits(step.detail), badge = badge,
                )
            }
        }
        if (it.any { s -> s.key == "dry_run_missing" }) Notice("نیاز به به‌روزرسانی ربات", "تا ربات به‌روز نشود، خواندن پیامک بانک آزمایش نمی‌شود؛ این نتیجه به معنی «کل مسیر سالم است» نیست.", Tone.info)
    }
    if (outcome == HealthCheck.Outcome.BAD || outcome == HealthCheck.Outcome.WARN) PrimaryButton("برطرف کردن مشکل", { nav.home("setup") })
    PrimaryButton(if (list == null) "شروع تست" else "اجرای دوباره", {
        running = true
        steps = null
        scope.launch {
            steps = withContext(Dispatchers.IO) { HealthCheck.run(context.applicationContext) }
            running = false
        }
    }, busy = running, enabled = !running)
    SecondaryButton("بازگشت به خانه", { nav.home() }, enabled = !running)
}

// ── the queue (18, 73–75, 65) ───────────────────────────────────────────

@Composable
private fun QueueScreen(store: Store, now: Long) {
    watchStore()
    val context = LocalContext.current
    val nav = LocalNav.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var copied by remember { mutableStateOf(false) }
    val queue = store.queue()
    val events = store.events().associateBy { it.optString("id") }
    val rows = queue.map { item ->
        val e = events[item.optString("id")]
        Triple(item, States.queueRow(e?.optString("status") ?: "queued", e?.optString("detail")), e?.optString("detail"))
    }
    val throttled = rows.any { it.second == States.QueueRow.THROTTLED }
    // Refused for good: off the queue so the rest could go, and worth a support look.
    val failed = store.events().filter { it.optString("kind") == "sms" && it.optString("status") == "failed" && now - it.optLong("at") < 7 * 86_400_000L }.take(5)

    ScreenHeader("صف ارسال", "خانه") { nav.home() }
    when {
        sending -> Hero("ارسال پیامک‌ها در جریان است", "تا پاسخ ربات نرسد، پیامک موفق حساب نمی‌شود.", Tone.info, Art.Queue)
        throttled -> Hero("ارسال موقتاً عقب افتاد", "ربات محدودیت تعداد درخواست اعلام کرده است؛ پیامک‌ها در صف می‌مانند.", Tone.warn, Art.Queue)
        queue.isNotEmpty() -> Hero(Persian.number(queue.size) + " پیامک در صف است", "برای این پیامک‌ها هنوز پاسخ موفق دریافت نشده است.", Tone.warn, Art.Queue)
        else -> Hero("صف ارسال خالی است", "پیامکی منتظر ارسال نیست.", Tone.good, Art.Queue)
    }
    if (sending) Waiting("به ترتیب رسیدن ارسال می‌شود…")
    if (queue.isNotEmpty()) {
        AppCard(title = "در انتظار ارسال") {
            rows.forEachIndexed { i, (item, row, detail) ->
                if (i > 0) Divider()
                ListRow(
                    when (row) { States.QueueRow.QUEUED -> Ico.Mail; States.QueueRow.RETRY -> Ico.Refresh; States.QueueRow.THROTTLED -> Ico.Clock },
                    Tone.warn, "پیامک " + Persian.sender(item.optString("from")),
                    when (row) {
                        States.QueueRow.QUEUED -> "محتوا پنهان است · به ترتیب ارسال می‌شود."
                        else -> detail?.let { Persian.digits(it) } ?: "پس از خطای موقت دوباره تلاش می‌شود."
                    },
                    badge = when (row) { States.QueueRow.QUEUED -> "در صف"; States.QueueRow.RETRY -> "تلاش مجدد"; States.QueueRow.THROTTLED -> "موقت" },
                )
            }
        }
        if (throttled) Notice("پیامک در صف باقی مانده", "تلاش دوباره طبق برنامهٔ ارسال انجام می‌شود؛ زمان دقیقی برای آن وعده نمی‌دهیم.", Tone.warn)
        PrimaryButton("بررسی اتصال و ارسال", {
            sending = true
            said = null
            scope.launch {
                val n = withContext(Dispatchers.IO) { Net.ping(context.applicationContext); Net.flush(context.applicationContext) }
                sending = false
                val left = store.queueSize()
                said = if (left == 0) true to "همهٔ پیامک‌ها به ربات رسید. نتیجهٔ بررسی پرداخت هر کدام در «گزارش» است."
                else (n > 0) to ((if (n > 0) Persian.number(n) + " پیامک رسید؛ " else "") + Persian.number(left) + " پیامک هنوز در صف است" + (Net.lastPingReason?.let { ": " + Persian.digits(it) } ?: "."))
            }
        }, busy = sending)
        Notice("درصد ارسال نمایش داده نمی‌شود", "هر پیامک فقط با پاسخ موفق ربات از صف خارج می‌شود؛ پیشرفت ساختگی نشان نمی‌دهیم.", Tone.info)
    }
    said?.let { (ok, text) -> Notice(text, null, if (ok) Tone.good else Tone.warn) }
    if (failed.isNotEmpty()) {
        val storage = failed.firstOrNull { it.optString("title").contains("ذخیره نشد") }
        if (storage != null) {
            Hero("پیامک در صف ذخیره نشد", "حافظهٔ گوشی پر است یا پایگاه‌داده با خطا روبه‌رو شده است.", Tone.bad, Art.Storage)
            AppCard(title = "برای رفع مشکل") {
                Rows(
                    { ListRow(Ico.Alert, Tone.bad, "فضای آزاد گوشی", "حافظهٔ دستگاه را بررسی کنید.", badge = "کمبود فضا") },
                    { ListRow(Ico.Refresh, Tone.info, "بازیابی پیامک‌ها", "پیامک هنوز در صندوق گوشی است؛ حذفش نکنید. بازیابی فقط پیامک‌های بعد از روشن‌کردن آن را پیدا می‌کند.", badge = "پیشنهاد") },
                )
                PrimaryButton("رفتن به بازیابی پیامک‌ها", { nav.more("recovery") })
            }
        }
        AppCard(title = "ارسال‌های ناموفق") {
            failed.forEachIndexed { i, e ->
                if (i > 0) Divider()
                ListRow(Ico.Close, Tone.bad, e.optString("title") + (e.optString("from").takeIf { it.isNotEmpty() }?.let { " · " + Persian.sender(it) } ?: ""),
                    Persian.digits(e.optString("detail").ifEmpty { "ربات این پیامک را نپذیرفت." }), badge = "متوقف")
            }
            Text("تلاش خودکار برای این موارد متوقف شد تا بقیهٔ صف معطل نماند.", style = Type.supporting, color = Brand.Muted)
            SecondaryButton(if (copied) "کپی شد" else "کپی گزارش امن برای پشتیبانی", {
                clipboard.setText(AnnotatedString(diagnostics(context, store, now)))
                copied = true
            }, icon = Ico.Copy)
        }
    }
}
