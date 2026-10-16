package ir.jirjirak.sms.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FILTERS = listOf("all" to "همه", "pending" to "نیازمند بررسی", "approved" to "تأییدشده", "mismatch" to "مغایرت")

/**
 * «رسیدها» — the receipts centre: the week in figures, a filter, the receipts themselves (waiting
 * first), and a receipt's own page to decide it on. Nothing here is computed on the phone: the
 * figures, the score and the decision are the bot's.
 */
@Composable
fun ReceiptsTab(store: Store, now: Long, filter: Int, onFilter: (Int) -> Unit) {
    watchStore()
    if (store.manageKey == null) {
        SideEffect { ReceiptsInner.value = false }
        ManageKeyScreen(store)
        return
    }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    // A deposit being tied to a receipt, or an unread SMS being taught: the whole row travels.
    var assigning by remember { mutableStateOf<Receipts.Item?>(null) }
    var teaching by remember { mutableStateOf<Receipts.Item?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // What just happened, said once on the list — a deposit set aside — then gone.
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(6000); flash = null } }
    val route: Route = when {
        assigning != null -> Route.Assign(assigning!!)
        teaching != null -> Route.Teach(teaching!!)
        open != null -> Route.Receipt(open!!)
        else -> Route.List
    }
    // Told after composition, never during it: App reads this to decide whether to draw the header.
    SideEffect { ReceiptsInner.value = route != Route.List }
    DisposableEffect(Unit) { onDispose { ReceiptsInner.value = false } }

    // A screen opens at its top; the list comes back where it was left.
    val scroll = LocalScroll.current
    var listAt by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<Route>(Route.List) }
    LaunchedEffect(route) {
        if (scroll != null && route != shown) {
            if (shown == Route.List) listAt = scroll.value
            scroll.scrollTo(if (route == Route.List) listAt else 0)
        }
        shown = route
    }

    // Each pane is drawn from ITS route — the one leaving keeps its own screen while it animates out.
    AnimatedContent(targetState = route, contentKey = { it.key }, label = "receipts") { r ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (r) {
                is Route.Assign -> {
                    BackHandler { assigning = null }
                    AssignScreen(store, r.deposit, now, onBack = { assigning = null }, onDone = { assigning = null; refreshTick++ })
                }
                is Route.Teach -> {
                    BackHandler { teaching = null }
                    TeachScreen(store, r.sms, now, onBack = { teaching = null }, onDone = { teaching = null; refreshTick++ })
                }
                is Route.Receipt -> {
                    BackHandler { open = null }
                    ReceiptDetailScreen(store, r.id, now, onBack = { open = null }, onDecided = { refreshTick++ })
                }
                Route.List -> ReceiptsCentre(
                    store, now, filter, onFilter, refreshTick, flash,
                    onOpen = { open = it },
                    onAssign = { assigning = it },
                    onTeach = { teaching = it },
                    onChanged = { msg -> flash = msg; refreshTick++ },
                )
            }
        }
    }
}

/** Where the receipts tab is: the list, one receipt, tying a deposit, teaching an SMS. */
private sealed interface Route {
    val key: String

    data object List : Route { override val key = "list" }
    data class Receipt(val id: Long) : Route { override val key = "receipt:$id" }
    data class Assign(val deposit: Receipts.Item) : Route { override val key = "assign:" + deposit.id }
    data class Teach(val sms: Receipts.Item) : Route { override val key = "teach:" + sms.id }
}

@Composable
private fun ReceiptsCentre(
    store: Store, now: Long, filter: Int, onFilter: (Int) -> Unit, refreshTick: Int, flash: String?,
    onOpen: (Long) -> Unit, onAssign: (Receipts.Item) -> Unit, onTeach: (Receipts.Item) -> Unit, onChanged: (String?) -> Unit,
) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var week by remember { mutableStateOf<Receipts.Summary?>(null) }
    var items by remember(filter, refreshTick) { mutableStateOf<List<Receipts.Item>?>(null) }
    var page by remember(filter, refreshTick) { mutableIntStateOf(1) }
    var more by remember(filter, refreshTick) { mutableStateOf(false) }
    var error by remember(filter, refreshTick) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load(next: Int) {
        loading = true
        if (next == 1) error = null
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { Receipts.history(context.applicationContext, FILTERS[filter].first, next) }) {
                is Receipts.Result.Ok -> {
                    items = if (next == 1) r.value.items else (items ?: emptyList()) + r.value.items
                    more = r.value.hasMore
                    page = next
                    error = null
                }
                Receipts.Result.NeedKey -> store.revokeManageKey()
                is Receipts.Result.Failed -> error = r.message
                Receipts.Result.AlreadyDecided -> {}
            }
            loading = false
        }
    }
    LaunchedEffect(filter, refreshTick) { load(1) }
    LaunchedEffect(refreshTick) {
        (withContext(Dispatchers.IO) { Receipts.summary(context.applicationContext, "7d") } as? Receipts.Result.Ok)?.let { week = it.value }
    }

    // The week in figures — a skeleton while unknown, never a zero.
    val m = week?.metrics
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricTile("کل هفته", m?.let { Persian.number(it.autoApproved + it.manualApproved + it.rejected + it.needsReview) }, "رسید", Modifier.weight(1f).fillMaxHeight())
        MetricTile("خودکار", m?.let { Persian.number(it.autoApproved) }, "رسید", Modifier.weight(1f).fillMaxHeight())
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricTile("منتظر", m?.let { Persian.number(it.needsReview) }, "رسید", Modifier.weight(1f).fillMaxHeight())
        MetricTile("مغایرت", week?.let { Persian.number(it.openMismatch) }, "مورد", Modifier.weight(1f).fillMaxHeight())
    }
    Segmented(FILTERS.map { it.second }, filter, onFilter)
    when (FILTERS[filter].first) {
        "pending" -> Hero("رسیدهای منتظر تصمیم", "رسیدهایی که ربات خودکار تأیید نکرد و تصمیم شما را می‌خواهند.", Tone.warn, icon = Ico.Receipt)
        "mismatch" -> Hero("مغایرت‌ها", "واریزهایی که به فاکتوری وصل نشده‌اند و پیامک‌هایی که خوانده نشدند.", Tone.warn, icon = Ico.Alert)
    }
    flash?.let { Notice(it, null, Tone.good) }

    val list = items
    when {
        // A failed request is not «no receipts».
        error != null && list == null -> {
            Hero("دریافت رسیدها ممکن نشد", "ارتباط با ربات قطع شده یا درخواست با خطا روبه‌رو شده است.", Tone.bad, Art.Offline)
            AppCard(title = "آخرین تلاش") {
                ListRow(Ico.Alert, Tone.bad, "درخواست فهرست رسیدها", Persian.digits(error!!), badge = "خطا")
                PrimaryButton("تلاش دوباره", { load(1) }, busy = loading, icon = Ico.Refresh)
                SecondaryButton("بررسی اتصال", { nav.home("health") })
            }
        }
        list == null -> {
            Waiting("دریافت رسیدها از ربات…")
            repeat(3) { Skeleton(Modifier.fillMaxWidth(), height = 120) }
        }
        list.isEmpty() -> AppCard {
            EmptyState(
                when (FILTERS[filter].first) {
                    "pending" -> "رسیدی منتظر بررسی نیست"
                    "mismatch" -> "مغایرتی پیدا نشد"
                    "approved" -> "هنوز رسیدی تأیید نشده"
                    else -> "هنوز رسیدی ثبت نشده"
                },
                if (FILTERS[filter].first == "pending") "وقتی پرداختی به تصمیم شما نیاز داشته باشد، اینجا نمایش داده می‌شود." else null,
                Art.ReceiptsEmpty,
            )
            if (filter != 0) SecondaryButton("نمایش همهٔ رسیدها", { onFilter(0) })
        }
        else -> {
            if (FILTERS[filter].first == "approved") Notice("منبع وضعیت: ربات", "تأیید دستی و خودکار با برچسب جدا نمایش داده می‌شوند.", Tone.info)
            list.forEach { item ->
                if (item.isDeposit) DepositCard(store, item, now, onAssign = { onAssign(item) }, onTeach = { onTeach(item) }, onChanged = onChanged)
                else ReceiptRow(item, now) { onOpen(item.id) }
            }
            error?.let { ErrorState(it) { load(page + 1) } }
            if (more) SecondaryButton(if (loading) "در حال دریافت…" else "نمایش موارد بیشتر", { load(page + 1) }, busy = loading)
            else if (list.size > 5) Text("پایان فهرست", style = Type.supporting, color = Brand.Muted, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun stateLabel(state: String, via: String?): Pair<String, String> = when (state) {
    "approved" -> (if (via == "auto") "تأیید خودکار" else "تأیید دستی") to "good"
    "rejected" -> "رد شده" to "bad"
    "pending" -> "نیازمند بررسی" to "warn"
    else -> "نامشخص" to "info"
}

/** One receipt in the list: who, how much, where it stands and why — one touch target. */
@Composable
private fun ReceiptRow(r: Receipts.Item, now: Long, onOpen: () -> Unit) {
    val (label, toneName) = stateLabel(r.state, r.via)
    val tone = Tone.of(toneName)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brand.Surface).border(1.dp, Brand.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(r.customer ?: "مشتری", style = Type.section, color = Brand.Ink)
                Text(listOfNotNull(r.tracking?.let { "سفارش " + Persian.latin(it) }, r.title).joinToString(" · "), style = Type.supporting, color = Brand.Muted)
            }
            StatusBadge(label, tone)
        }
        Text(r.amount?.let { Money.toman(it) } ?: "—", style = Type.amount, color = Brand.Ink)
        Divider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Persian.digits(r.reason ?: if (r.score != null) "امتیاز " + r.score + " از " + (r.threshold ?: 70) else r.rail ?: "") +
                    " · " + Persian.day(r.at, now) + " " + Persian.clock(r.at),
                style = Type.supporting, color = Brand.Muted, modifier = Modifier.weight(1f),
            )
            JIcon(Ico.Chevron, Brand.Green, 20)
        }
    }
}

// ── one receipt (29–33, 62, 78–84) ──────────────────────────────────────

/** Where a decision stands. Success is shown only after the bot's own answer says so. */
private sealed interface Phase {
    data object Idle : Phase
    data object ConfirmApprove : Phase
    data class ChooseReason(val picked: Receipts.Reason?) : Phase
    data class ConfirmReject(val reason: Receipts.Reason) : Phase
    data class Saving(val approve: Boolean) : Phase
    data class Unknown(val message: String, val approve: Boolean) : Phase
    /** The bot answered; the receipt is being read again before anything is claimed. */
    data class Confirming(val approve: Boolean) : Phase
    data object Already : Phase
}

@Composable
private fun ReceiptDetailScreen(store: Store, id: Long, now: Long, onBack: () -> Unit, onDecided: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<Receipts.Detail?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var reasons by remember { mutableStateOf<List<Receipts.Reason>>(emptyList()) }
    var phase by remember(id) { mutableStateOf<Phase>(Phase.Idle) }
    // What a fresh read said after an unclear answer: the decision did not go through.
    var stillPending by remember(id) { mutableStateOf(false) }
    var decided by remember(id) { mutableStateOf(false) }
    var image by remember(id) { mutableStateOf<Bitmap?>(null) }
    var showImage by remember(id) { mutableStateOf(false) }
    var imageFailed by remember(id) { mutableStateOf(false) }
    var imageAttempt by remember(id) { mutableIntStateOf(0) }
    // A photo shown on request is closed when the app leaves the screen.
    LaunchedEffect(Privacy.epoch.intValue) { showImage = false; image = null }

    suspend fun reload(): Receipts.Detail? {
        return when (val r = withContext(Dispatchers.IO) { Receipts.detail(context.applicationContext, id) }) {
            is Receipts.Result.Ok -> { detail = r.value; error = null; r.value }
            Receipts.Result.NeedKey -> { store.revokeManageKey(); null }
            is Receipts.Result.Failed -> { error = r.message; null }
            Receipts.Result.AlreadyDecided -> null
        }
    }
    LaunchedEffect(id) {
        reload()
        (withContext(Dispatchers.IO) { Receipts.reasons(context.applicationContext) } as? Receipts.Result.Ok)?.let { reasons = it.value }
    }
    LaunchedEffect(id, showImage, imageAttempt) {
        if (showImage && image == null) {
            imageFailed = false
            image = withContext(Dispatchers.IO) { Receipts.image(context.applicationContext, id) }
            imageFailed = image == null
        }
    }

    /** After the bot answered: the receipt as the bot NOW has it, before a word of success. */
    fun settle(approve: Boolean, logged: String?) {
        phase = Phase.Confirming(approve)
        scope.launch {
            val fresh = reload()
            onDecided()
            when {
                fresh == null -> phase = Phase.Unknown(error ?: "وضعیت تازه خوانده نشد.", approve)
                fresh.item.state == "pending" -> { stillPending = true; phase = Phase.Idle }
                else -> {
                    if (logged != null) store.event("system", "ok", logged)
                    decided = true
                    phase = Phase.Idle
                }
            }
        }
    }

    fun send(approve: Boolean, reason: Receipts.Reason?) {
        val amount = detail?.item?.amount?.let { Money.toman(it) } ?: ""
        phase = Phase.Saving(approve)
        stillPending = false
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (approve) Receipts.approve(context.applicationContext, id) else Receipts.reject(context.applicationContext, id, reason!!.code)
            }
            when (result) {
                Receipts.Decision.Done -> settle(approve, if (approve) "رسید $amount از اپ تأیید شد" else "رسید $amount از اپ رد شد: " + reason!!.label)
                Receipts.Decision.AlreadyDecided -> { phase = Phase.Already; reload(); onDecided() }
                Receipts.Decision.NeedKey -> store.revokeManageKey()
                is Receipts.Decision.Unknown -> phase = Phase.Unknown(result.message, approve)
                is Receipts.Decision.Failed -> { error = result.message; phase = Phase.Idle }
            }
        }
    }

    ScreenHeader("جزئیات رسید", "رسیدها") { onBack() }
    val d = detail
    if (d == null) {
        if (error != null) ErrorState(error!!) { scope.launch { reload() } }
        else {
            Waiting("دریافت جزئیات رسید از ربات…")
            repeat(2) { Skeleton(Modifier.fillMaxWidth(), height = 120) }
        }
        return
    }
    val r = d.item
    val amount = r.amount?.let { Money.toman(it) } ?: "—"
    val (label, toneName) = stateLabel(r.state, r.via)
    val busy = phase is Phase.Saving || phase is Phase.Confirming

    // ── the headline: where the decision stands ──
    when (val p = phase) {
        is Phase.Saving, is Phase.Confirming -> {
            Hero("در انتظار پاسخ ربات", if (p is Phase.Saving) "درخواست ارسال شده؛ هنوز نتیجهٔ قطعی نداریم." else "پاسخ رسید؛ وضعیت تازهٔ رسید از ربات خوانده می‌شود.", Tone.info, icon = Ico.Sync)
            AppCard(title = "عملیات در جریان") {
                Rows(
                    { ListRow(Ico.Bank, Tone.muted, "مبلغ", amount) },
                    { ListRow(Ico.Receipt, Tone.muted, r.customer ?: "مشتری", r.tracking?.let { "سفارش " + Persian.latin(it) } ?: "—") },
                )
                Waiting(if ((p as? Phase.Saving)?.approve ?: (p as Phase.Confirming).approve) "ثبت تأیید…" else "ثبت رد…")
            }
            Notice("دوباره ارسال نکنید", "تا تعیین نتیجه، تأیید و رد هر دو غیرفعال هستند.", Tone.info)
        }
        is Phase.Unknown -> {
            Hero("پاسخ نهایی دریافت نشد", "ممکن است درخواست شما در ربات ثبت شده باشد.", Tone.warn, Art.HealthWarning)
            Notice("دوباره تأیید یا رد نزنید", "ابتدا وضعیت این رسید را از ربات تازه می‌کنیم؛ موفقیت یا شکست را حدس نمی‌زنیم. (" + Persian.digits(p.message) + ")", Tone.warn)
            PrimaryButton("بررسی وضعیت رسید", { settle(p.approve, null) }, icon = Ico.Refresh)
            SecondaryButton("بازگشت به فهرست", onBack)
            return
        }
        Phase.Already -> Hero("این رسید قبلاً تعیین‌تکلیف شده", "کس دیگری یا خود ربات زودتر تصمیم گرفت؛ وضعیت تازه از ربات خوانده شد: " + label + ".", Tone.info, icon = Ico.Receipt)
        else -> when {
            decided && r.state == "approved" -> Hero("پرداخت تأیید شد", "نتیجه از ربات دریافت شد؛ سفارش «" + (r.customer ?: "مشتری") + "» تأییدشده است.", Tone.good, Art.Success)
            r.state == "rejected" -> Hero("رسید رد شده است", "نتیجهٔ قطعی از ربات دریافت شد.", Tone.bad, icon = Ico.Receipt)
            r.state == "approved" -> Hero(amount, label + (r.reason?.let { " — " + Persian.digits(it) } ?: ""), Tone.good, icon = Ico.Check)
            else -> Hero(amount, "منتظر تصمیم شما" + (r.reason?.let { " — " + Persian.digits(it) } ?: ""), Tone.of(toneName), icon = Ico.Receipt)
        }
    }
    if (stillPending) Notice("تصمیم ثبت نشده است", "ربات هنوز این رسید را «منتظر» نشان می‌دهد. اگر لازم است، دوباره تصمیم بگیرید.", Tone.warn)
    error?.let { Notice("انجام نشد", Persian.digits(it), Tone.bad) }

    AppCard(title = r.title ?: "اطلاعات سفارش") {
        Rows(
            { ListRow(Ico.User, Tone.muted, r.customer ?: "مشتری", r.tracking?.let { Persian.latin(it) } ?: "—") },
            { ListRow(Ico.Bank, Tone.muted, "روش پرداخت", (r.rail ?: "—") + " · " + Persian.day(r.at, now) + " " + Persian.clock(r.at)) },
        )
        d.deposit?.let { dep ->
            Divider()
            ListRow(Ico.Mail, Tone.info, "پیامک بانک", listOfNotNull(dep.sender?.let { "از " + Persian.sender(it) }, dep.amount?.let { Money.toman(it) }, Persian.day(dep.at, now) + " " + Persian.clock(dep.at)).joinToString(" · "))
        }
        if (r.state == "rejected" && r.reason != null) {
            Divider()
            ListRow(Ico.Info, Tone.muted, "دلیل رد", Persian.digits(r.reason))
        }
        if (r.hasImage) {
            when {
                !showImage -> SecondaryButton("نمایش عکس رسید", { showImage = true }, icon = Ico.Image)
                imageFailed -> {
                    EmptyState("عکس دریافت نشد", "احتمالاً اینترنت قطع شده یا فایل در دسترس نیست. این خطا روی تصمیم رسید اثری ندارد.", Art.ImagePlaceholder)
                    SecondaryButton("دریافت دوباره", { imageAttempt++ }, icon = Ico.Refresh)
                }
                image == null -> {
                    Waiting("دریافت عکس رسید…")
                    Skeleton(Modifier.fillMaxWidth(), height = 220)
                }
                else -> {
                    Image(
                        image!!.asImageBitmap(), contentDescription = "عکس رسید", contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(14.dp)),
                    )
                    SecondaryButton("بستن تصویر", { showImage = false; image = null })
                }
            }
        }
    }

    // The bot's own reading — or, when there is none, saying so instead of a made-up zero.
    AppCard(title = "نشانه‌های بررسی", trailing = { if (d.score != null) StatusBadge(Persian.number(d.score) + " از " + Persian.number(d.threshold ?: 70), if (d.enough) Tone.good else Tone.warn) }) {
        if (d.score == null) {
            Text("اطلاعات کافی نیست: هنوز پیامک بانکی با مبلغ این فاکتور نرسیده و ربات امتیازی نداده است.", style = Type.body, color = Brand.Muted)
        } else {
            d.signals.forEach { ScoreBar(Persian.digits(it.label), null, it.earned, it.weight, it.ok) }
            if (!d.enough) Notice("نشانه‌ها کافی نیست", "امتیاز " + Persian.number(d.score) + " است و حد لازم " + Persian.number(d.threshold ?: 70) + "؛ این امتیاز درصد احتمال صحت نیست.", Tone.warn)
        }
    }

    if (d.suggestionTitle.isNotBlank()) {
        val sug = Tone.of(d.suggestionTone)
        AppCard(title = "پیشنهاد ربات") {
            ListRow(Ico.Info, sug, Persian.digits(d.suggestionTitle), d.suggestionText?.let { Persian.digits(it) })
            Text("پیشنهاد ربات جای تصمیم شما را نمی‌گیرد.", style = Type.supporting, color = Brand.Muted)
        }
    }

    if (d.timeline.isNotEmpty()) {
        AppCard(title = "تاریخچه") {
            d.timeline.forEachIndexed { i, (at, entry) ->
                TimelineRow(Persian.digits(entry.first), Persian.day(at, now) + " " + Persian.clock(at), Tone.of(entry.second), i == d.timeline.lastIndex)
            }
        }
    }

    if (r.state == "pending") {
        PrimaryButton("تأیید پرداخت", { phase = Phase.ConfirmApprove }, enabled = !busy, icon = Ico.Check)
        DangerButton("رد رسید", { phase = Phase.ChooseReason(null) }, enabled = !busy)
    } else if (r.state == "rejected" || r.state == "approved") {
        Notice("این رسید دیگر منتظر تصمیم نیست", "عملیات تأیید و رد برای آن غیرفعال است.", Tone.info)
        SecondaryButton("بازگشت به رسیدها", onBack)
    }

    // ── the confirmations ──
    val target = (r.customer ?: "مشتری") + (r.tracking?.let { "، سفارش " + Persian.latin(it) } ?: "")
    when (val p = phase) {
        Phase.ConfirmApprove -> ConfirmDialog(
            "تأیید پرداخت",
            "پرداخت $amount برای $target تأیید و سفارش تحویل شود؟ نتیجهٔ ربات پیش از نمایش موفقیت بررسی می‌شود.",
            "تأیید پرداخت", { send(true, null) }, { phase = Phase.Idle },
        )
        is Phase.ChooseReason -> {
            val options = reasons.ifEmpty { listOf(Receipts.Reason("not_found", "تراکنش در حساب مقصد یافت نشد")) }
            ConfirmDialog(
                "دلیل رد رسید", "یکی از دلایل زیر را انتخاب کنید. این متن به مشتری هم فرستاده می‌شود.",
                "ادامه", { p.picked?.let { phase = Phase.ConfirmReject(it) } }, { phase = Phase.Idle },
                confirmEnabled = p.picked != null,
                extra = {
                    options.forEach { reason ->
                        val on = p.picked?.code == reason.code
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .selectable(selected = on, role = Role.RadioButton) { phase = Phase.ChooseReason(reason) }.padding(horizontal = 4.dp),
                        ) {
                            RadioButton(selected = on, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Brand.Green, unselectedColor = Brand.Control))
                            Spacer(Modifier.width(8.dp))
                            Text(reason.label, style = Type.body, color = Brand.Ink)
                        }
                    }
                    if (p.picked == null) Text("برای ادامه یک دلیل را انتخاب کنید.", style = Type.supporting, color = Brand.Muted)
                },
            )
        }
        is Phase.ConfirmReject -> ConfirmDialog(
            "رسید رد شود؟",
            "مبلغ: $amount ($target). دلیل انتخاب‌شده: «" + p.reason.label + "». این دلیل برای مشتری هم فرستاده می‌شود.",
            "رد رسید و ارسال دلیل", { send(false, p.reason) }, { phase = Phase.ChooseReason(p.reason) },
            cancel = "تغییر دلیل", danger = true,
        )
        else -> {}
    }
}

// ── the manage key (23, 24, 61) ─────────────────────────────────────────

/**
 * Paste the bot's «jirjirak-manage:» code. It is tried against the bot BEFORE it is kept — «فعال
 * شد» only on a valid answer — and a refusal says which of three things is wrong.
 */
@Composable
private fun ManageKeyScreen(store: Store) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<Receipts.KeyCheck?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val revoked = store.manageKeyRevoked

    fun take(raw: String?) {
        val key = Receipts.parseCode(raw)
        if (key == null) {
            invalid = true
            check = null
            return
        }
        invalid = false
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { Receipts.verify(context.applicationContext, key) }
            checking = false
            check = result
            if (result == Receipts.KeyCheck.OK) {
                store.manageKey = key
                store.manageKeyRevoked = false
                SummaryCache.clear()
                store.event("system", "ok", "تأیید و رد رسید از اپ فعال شد")
                // The code can approve payments: it does not stay on the clipboard for the next app.
                if (Receipts.parseCode(clipboard.getText()?.text) == key) clipboard.setText(AnnotatedString(""))
                typed = ""
            }
        }
    }

    when {
        check == Receipts.KeyCheck.FORBIDDEN -> {
            Hero("مدیریت رسیدها در دسترس نیست", "ربات اعلام کرد اشتراک فروشگاه فعال نیست.", Tone.bad, Art.Security)
            Notice("اشتراک را تمدید کنید", "کد مدیریت لزوماً باطل نشده؛ ابتدا وضعیت اشتراک بررسی شود. ارسال پیامک‌ها ادامه دارد.", Tone.warn)
            PrimaryButton("رفتن به ربات جیرجیرک", { openTelegram(context, "JirjirakPremiumBot") })
        }
        check == Receipts.KeyCheck.WRONG || (check == null && revoked) ->
            Hero("کد مدیریت باطل شده است", "از ربات، کد تازه را کپی و دوباره فعال کنید.", Tone.bad, Art.Security)
        check == Receipts.KeyCheck.NOT_FOUND ->
            Hero("ربات این گوشی را نمی‌شناسد", "آدرس در ربات عوض شده یا نسخهٔ ربات قدیمی است؛ کد مدیریت این را درست نمی‌کند.", Tone.bad, Art.Security)
        check == Receipts.KeyCheck.OFFLINE || check == Receipts.KeyCheck.FAILED ->
            Hero("بررسی کد ممکن نشد", "ربات پاسخ نداد؛ کد ذخیره نشد. اینترنت را بررسی کنید و دوباره امتحان کنید.", Tone.warn, Art.Offline)
        else -> Hero("مدیریت رسیدها را فعال کنید", "کد مدیریت با کد ارسال پیامک فرق دارد.", Tone.info, Art.Security)
    }
    if (check == Receipts.KeyCheck.WRONG || (check == null && revoked)) Notice("تأیید دستی موقتاً غیرفعال است", "ارسال پیامک‌های بانک ادامه دارد.", Tone.warn)

    AppCard(title = if (revoked || check == Receipts.KeyCheck.WRONG) "اتصال دوباره" else "کد مدیریت رسیدها") {
        if (!revoked && check == null) {
            Steps(listOf(
                "در ربات بروید به: مدیریت ربات ← پرداخت‌ها ← تأیید خودکار رسید.",
                "«کپی کد مدیریت رسیدها» را بزنید و به این برنامه برگردید.",
            ))
        }
        PrimaryButton(if (revoked) "چسباندن کد تازه" else "چسباندن کد مدیریت", { take(clipboard.getText()?.text) }, enabled = !checking, icon = Ico.Copy)
        SecureField(typed, { typed = it; invalid = false }, if (revoked) "کد مدیریت تازه" else "کد مدیریت", isError = invalid, enabled = !checking, onDone = { if (typed.isNotBlank()) take(typed) })
        if (invalid) Notice("این کد مدیریت رسید نیست", "کد با «" + Persian.latin("jirjirak-manage:") + "» شروع می‌شود. «کد اتصال» کد دیگری است.", Tone.bad)
        if (checking) Waiting("بررسی کد با ربات…")
        SecondaryButton(if (checking) "در حال بررسی" else "بررسی دسترسی", { take(typed) }, enabled = typed.isNotBlank(), busy = checking)
    }
    Notice("ارسال پیامک مستقل است", "نداشتن یا پاک‌کردن کد مدیریت، ارسال پیامک‌های بانک را متوقف نمی‌کند. کد اتصال فقط اجازهٔ «فرستادن پیامک» دارد؛ این کد اجازهٔ «تأیید و رد».", Tone.info)
}

