package ir.jirjirak.sms.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FILTERS = listOf("all" to "همه", "pending" to "نیازمند بررسی", "approved" to "تأیید شده", "mismatch" to "مغایرت")

/**
 * «رسیدها» — the receipts centre: the week in four figures, a filter, the receipts themselves
 * (waiting first), and a receipt's own page to decide it on. Nothing here is computed on the
 * phone: the figures, the score and the decision are the bot's.
 */
@Composable
fun ReceiptsTab(store: Store, now: Long, filter: Int, onFilter: (Int) -> Unit) {
    watchStore()
    if (store.manageKey == null) {
        ManageKeyCard(store, revoked = store.manageKeyRevoked)
        return
    }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    // A deposit being tied to a receipt, or an unread SMS being taught: the whole row travels, it
    // carries the SMS text and its numbers.
    var assigning by remember { mutableStateOf<Receipts.Item?>(null) }
    var teaching by remember { mutableStateOf<Receipts.Item?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // What just happened, said once on the list — a deposit tied, an SMS set aside — then gone.
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(5000); flash = null } }
    val route: Route = when {
        assigning != null -> Route.Assign(assigning!!)
        teaching != null -> Route.Teach(teaching!!)
        open != null -> Route.Receipt(open!!)
        else -> Route.List
    }

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

    // Each pane is drawn from ITS route — the one leaving keeps its own screen while it animates out,
    // instead of reading today's state and drawing (and loading) a second list.
    AnimatedContent(targetState = route, contentKey = { it.key }, label = "receipts") { r ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (r) {
                is Route.Assign -> {
                    BackHandler { assigning = null }
                    AssignScreen(store, r.deposit, now, onBack = { assigning = null }, onDone = { msg -> assigning = null; flash = msg; refreshTick++ })
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
    val scope = rememberCoroutineScope()
    var week by remember { mutableStateOf<Receipts.Summary?>(null) }
    var items by remember(filter, refreshTick) { mutableStateOf<List<Receipts.Item>?>(null) }
    var page by remember(filter, refreshTick) { mutableIntStateOf(1) }
    var more by remember(filter, refreshTick) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load(next: Int) {
        loading = true
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

    // The week in four figures.
    AppCard(padding = 12) {
        val m = week?.metrics
        Row(Modifier.fillMaxWidth()) {
            SummaryCell("کل رسیدها", m?.let { it.autoApproved + it.manualApproved + it.rejected + it.needsReview }, "۷ روز اخیر", Tone.info, Modifier.weight(1f))
            SummaryCell("تأیید خودکار", m?.autoApproved, "۷ روز اخیر", Tone.good, Modifier.weight(1f))
            SummaryCell("نیازمند بررسی", m?.needsReview, "در صف انتظار", Tone.warn, Modifier.weight(1f))
            SummaryCell("مغایرت", week?.openMismatch, "در فهرست مغایرت", Tone.bad, Modifier.weight(1f))
        }
    }
    Segmented(FILTERS.map { it.second }, filter, onFilter)
    flash?.let { Notice(it, Brand.GoodSoft, Brand.Good) }

    error?.let { ErrorState(it) { load(1) } }
    val list = items
    when {
        list == null && error == null -> repeat(3) { Skeleton(Modifier.fillMaxWidth(), height = 110) }
        list != null && list.isEmpty() -> AppCard {
            EmptyState(
                Icons.Filled.CheckCircle,
                when (FILTERS[filter].first) {
                    "pending" -> "رسیدی در انتظار بررسی نیست"
                    "mismatch" -> "مغایرتی پیدا نشد"
                    "approved" -> "هنوز رسیدی تأیید نشده"
                    else -> "هنوز رسیدی ثبت نشده"
                },
                if (FILTERS[filter].first == "pending") "هر رسیدی که خودکار تأیید نشود، اینجا می‌آید." else null,
            )
        }
        list != null -> {
            list.forEach { item ->
                if (item.isDeposit) DepositCard(store, item, now, onAssign = { onAssign(item) }, onTeach = { onTeach(item) }, onChanged = onChanged)
                else ReceiptRow(item, now) { onOpen(item.id) }
            }
            if (more) {
                OutlinedButton(onClick = { load(page + 1) }, enabled = !loading, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Text(if (loading) "در حال دریافت…" else "نمایش بیشتر")
                }
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: Int?, sub: String, tone: Tone, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IconChip(
            when (tone) {
                Tone.good -> Icons.Filled.Check
                Tone.warn -> Icons.Filled.DateRange
                Tone.bad -> Icons.Filled.Warning
                else -> Icons.Filled.Info
            },
            tone, 32,
        )
        if (value == null) Skeleton(Modifier.width(28.dp), height = 22) else Text(Persian.number(value), color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = Brand.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Brand.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun stateTone(state: String, via: String?): Pair<String, String> = when (state) {
    "approved" -> (if (via == "auto") "تأیید خودکار" else "تأیید شده") to "good"
    "rejected" -> "رد شده" to "bad"
    else -> "نیازمند بررسی" to "warn"
}

/** One receipt in the list: who, how much, when, where it stands and why. */
@Composable
private fun ReceiptRow(r: Receipts.Item, now: Long, onOpen: () -> Unit) {
    val (label, toneName) = stateTone(r.state, r.via)
    val tone = Tone.of(toneName)
    AppCard(padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            IconChip(Icons.Filled.AccountCircle, tone, 44)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.customer ?: "مشتری", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(r.tracking?.let { "سفارش " + Persian.latin(it) }, r.title).joinToString(" · "), color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusBadge(label, tone)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(r.amount?.let { Money.toman(it) } ?: "—", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(Persian.day(r.at, now) + " " + Persian.clock(r.at), color = Brand.Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(tone.fg))
            Spacer(Modifier.width(6.dp))
            Text(
                Persian.digits(r.reason ?: if (r.score != null) "امتیاز " + r.score + " از " + (r.threshold ?: 70) else r.rail ?: ""),
                color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onOpen, shape = RoundedCornerShape(12.dp)) { Text("مشاهده", fontSize = 13.sp) }
        }
    }
}

// ── one receipt ─────────────────────────────────────────────────────────

@Composable
private fun ReceiptDetailScreen(store: Store, id: Long, now: Long, onBack: () -> Unit, onDecided: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<Receipts.Detail?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var reasons by remember { mutableStateOf<List<Receipts.Reason>>(emptyList()) }
    var busy by remember(id) { mutableStateOf(false) }
    var confirmApprove by remember(id) { mutableStateOf(false) }
    var rejecting by remember(id) { mutableStateOf(false) }
    var image by remember(id) { mutableStateOf<Bitmap?>(null) }
    var showImage by remember(id) { mutableStateOf(false) }
    var imageFailed by remember(id) { mutableStateOf(false) }
    var imageAttempt by remember(id) { mutableIntStateOf(0) }

    fun load() {
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { Receipts.detail(context.applicationContext, id) }) {
                is Receipts.Result.Ok -> { detail = r.value; error = null }
                Receipts.Result.NeedKey -> store.revokeManageKey()
                is Receipts.Result.Failed -> error = r.message
                Receipts.Result.AlreadyDecided -> {}
            }
        }
    }
    LaunchedEffect(id) {
        load()
        (withContext(Dispatchers.IO) { Receipts.reasons(context.applicationContext) } as? Receipts.Result.Ok)?.let { reasons = it.value }
    }
    LaunchedEffect(id, showImage, imageAttempt) {
        if (showImage && image == null) {
            imageFailed = false
            image = withContext(Dispatchers.IO) { Receipts.image(context.applicationContext, id) }
            imageFailed = image == null
        }
    }

    fun decide(action: suspend () -> Receipts.Result<Unit>, done: String) {
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            busy = false
            when (result) {
                // The page shows what the SERVER now says, not what the tap hoped for.
                is Receipts.Result.Ok -> { store.event("system", "ok", done); onDecided(); load() }
                Receipts.Result.AlreadyDecided -> { onDecided(); load() }
                Receipts.Result.NeedKey -> store.revokeManageKey()
                is Receipts.Result.Failed -> error = result.message
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "بازگشت", tint = Brand.Ink) }
        Text("جزئیات رسید", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
    error?.let { ErrorState(it) { load() } }
    val d = detail
    if (d == null) {
        if (error == null) repeat(3) { Skeleton(Modifier.fillMaxWidth(), height = 120) }
        return
    }
    val r = d.item
    val (label, toneName) = stateTone(r.state, r.via)
    val tone = Tone.of(toneName)

    // The amount and where it stands.
    HeroStatus(
        title = r.amount?.let { Money.toman(it) } ?: "—",
        text = label + (r.reason?.let { " — " + Persian.digits(it) } ?: ""),
        tone = tone,
        icon = when (r.state) { "approved" -> Icons.Filled.CheckCircle; "rejected" -> Icons.Filled.Close; else -> Icons.Filled.DateRange },
        alive = false,
    )

    AppCard {
        SectionHeader("اطلاعات سفارش و مشتری")
        InfoRow("نام مشتری", r.customer ?: "—")
        InfoRow("شمارهٔ سفارش", r.tracking?.let { Persian.latin(it) } ?: "—", raw = true)
        r.title?.let { InfoRow("محصول", it) }
        InfoRow("روش پرداخت", r.rail ?: "—")
        InfoRow("زمان ثبت", Persian.date(r.at) + "، " + Persian.clock(r.at))
        d.deposit?.let { dep ->
            HorizontalDivider(color = Brand.Line)
            InfoRow("پیامک بانک از", dep.sender?.let { Persian.sender(it) } ?: "—", raw = true)
            InfoRow("مبلغ واریز", dep.amount?.let { Money.toman(it) } ?: "—")
            InfoRow("زمان واریز", Persian.date(dep.at) + "، " + Persian.clock(dep.at))
        }
        if (r.hasImage) {
            when {
                !showImage -> TextButton(onClick = { showImage = true }) { Text("🖼 دیدن عکس رسید") }
                imageFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("عکس دریافت نشد.", color = Brand.Bad, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { imageAttempt++ }) { Text("دوباره") }
                }
                image == null -> Skeleton(Modifier.fillMaxWidth(), height = 200)
                else -> Image(
                    image!!.asImageBitmap(), contentDescription = "عکس رسید (برای بستن بزنید)", contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).clickable { showImage = false; image = null },
                )
            }
        }
    }

    // The engine's own reading — or, when there is none, saying so instead of a made-up zero.
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("امتیاز و نشانه‌های بررسی", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (d.score != null) StatusBadge(Persian.number(d.score) + " از " + Persian.number(d.threshold ?: 70) + " لازم", if (d.enough) Tone.good else Tone.warn)
        }
        if (d.score == null) {
            Text("اطلاعات کافی نیست: هنوز پیامک بانکی با مبلغ این فاکتور نرسیده و ربات امتیازی نداده است.", color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
        } else {
            // The «why» is the suggestion below, said once.
            d.signals.forEach { ScoreBar(Persian.digits(it.label), null, it.earned, it.weight, it.ok) }
        }
    }

    val sugTone = Tone.of(d.suggestionTone)
    AppCard {
        SectionHeader("پیشنهاد سیستم")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(sugTone.bg).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(d.suggestionTitle, color = sugTone.fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            d.suggestionText?.let { Text(Persian.digits(it), color = Brand.Ink, fontSize = 13.sp, lineHeight = 20.sp) }
        }
        Text("این فقط پیشنهاد است؛ تصمیم با شماست و ربات دوباره بررسی می‌کند.", color = Brand.Muted, fontSize = 11.sp)
    }

    if (d.timeline.isNotEmpty()) {
        AppCard {
            SectionHeader("تاریخچه")
            d.timeline.forEachIndexed { i, (at, entry) ->
                TimelineRow(Persian.digits(entry.first), Persian.day(at, now) + " " + Persian.clock(at), Tone.of(entry.second), i == d.timeline.lastIndex)
            }
        }
    }

    if (r.state == "pending") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { confirmApprove = true }, enabled = !busy, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "…" else "تأیید پرداخت", fontSize = 15.sp)
            }
            OutlinedButton(
                onClick = { rejecting = true }, enabled = !busy, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).height(54.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Bad), border = dangerBorder(),
            ) { Text("رد رسید", fontSize = 15.sp) }
        }
    }

    if (confirmApprove) {
        AlertDialog(
            onDismissRequest = { confirmApprove = false },
            title = { Text("تأیید پرداخت") },
            text = { Text((r.amount?.let { Money.toman(it) } ?: "") + (r.customer?.let { " از $it" } ?: "") + " تأیید و سفارش تحویل شود؟", lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmApprove = false
                    decide({ Receipts.approve(context.applicationContext, id) }, "رسید " + (r.amount?.let { Money.toman(it) } ?: "") + " از اپ تأیید شد")
                }) { Text("تأیید") }
            },
            dismissButton = { TextButton(onClick = { confirmApprove = false }) { Text("انصراف") } },
        )
    }
    if (rejecting) {
        AlertDialog(
            onDismissRequest = { rejecting = false },
            title = { Text("دلیل رد") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("دلیل برای مشتری هم فرستاده می‌شود.", color = Brand.Muted, fontSize = 12.sp)
                    reasons.ifEmpty { listOf(Receipts.Reason("not_found", "تراکنش در حساب مقصد یافت نشد")) }.forEach { reason ->
                        Text(
                            reason.label, color = Brand.Ink, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                rejecting = false
                                decide({ Receipts.reject(context.applicationContext, id, reason.code) }, "رسید " + (r.amount?.let { Money.toman(it) } ?: "") + " از اپ رد شد: " + reason.label)
                            }.padding(vertical = 12.dp, horizontal = 6.dp),
                        )
                        HorizontalDivider(color = Brand.Line)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { rejecting = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, raw: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Brand.Muted, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
        Text(if (raw) value else Persian.digits(value), color = Brand.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.58f))
    }
}

/** Paste the bot's «jirjirak-manage:» code — once, or again after the address was changed. */
@Composable
private fun ManageKeyCard(store: Store, revoked: Boolean) {
    val clipboard = LocalClipboardManager.current
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun take(raw: String?) {
        val key = Receipts.parseCode(raw)
        if (key == null) {
            error = "این کد مدیریت رسید نیست. در ربات «🔑 کپی کد مدیریت رسیدها» را بزنید و دوباره امتحان کنید."
            return
        }
        error = null
        store.manageKey = key
        store.manageKeyRevoked = false
        store.event("system", "ok", "تأیید و رد رسید از اپ فعال شد")
        // The code can approve payments: it does not stay on the clipboard for the next app to read.
        if (clipboard.getText()?.text?.let { Receipts.parseCode(it) } == key) clipboard.setText(AnnotatedString(""))
    }

    Section("تأیید و رد رسید از همین اپ", Icons.Filled.Lock) {
        if (revoked) Notice("کد مدیریت قبلی دیگر معتبر نیست (احتمالاً آدرس در ربات عوض شده). کد تازه را بچسبانید.", Brand.WarnSoft, Brand.Warn)
        Text(
            "رسیدهایی که خودکار تأیید نشده‌اند را اینجا می‌بینید — با عکس رسید و دلیل ربات — و با یک لمس تأیید یا رد می‌کنید، بدون رفتن به تلگرام.",
            color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp,
        )
        Text(
            "برای امنیت، این کار یک کد جدا لازم دارد: در ربات «مدیریت ربات ← پرداخت‌ها ← تأیید خودکار رسید ← 🔑 کپی کد مدیریت رسیدها» را بزنید و اینجا بچسبانید.",
            color = Brand.Ink, fontSize = 13.sp, lineHeight = 20.sp,
        )
        Button(
            onClick = { take(clipboard.getText()?.text) },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        ) { Text("📋 چسباندن کد مدیریت") }
        // Masked, and kept out of the keyboard's learning: it is a key, not a word.
        OutlinedTextField(
            value = typed, onValueChange = { typed = it }, label = { Text("یا کد را اینجا بچسبانید") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (typed.isNotBlank()) {
            OutlinedButton(onClick = { take(typed) }, modifier = Modifier.fillMaxWidth()) { Text("فعال کن") }
        }
        error?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("🔒 کد اتصال فقط اجازهٔ «فرستادن پیامک» دارد؛ این کد اجازهٔ «تأیید و رد» را.", color = Brand.Muted, fontSize = 11.sp)
        }
    }
}
