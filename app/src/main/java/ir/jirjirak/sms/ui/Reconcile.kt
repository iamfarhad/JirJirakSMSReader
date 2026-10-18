package ir.jirjirak.sms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reconciliation centre: what to do with a bank deposit no invoice claimed (tie it to the receipt
 * it paid, or set it aside as not a sale) and with a bank SMS the bot could not read (teach its
 * format). The bot does the deciding and the locking; these screens only ask and show.
 *
 * «Unread» and «no invoice» are different things and look different: the first has no amount
 * (teach the format), the second has one that matched nothing (tie it, or set it aside).
 */

/** A bank deposit no invoice claimed, or an SMS that could not be read — with what can be done about it. */
@Composable
internal fun DepositCard(store: Store, d: Receipts.Item, now: Long, onAssign: () -> Unit, onTeach: () -> Unit, onChanged: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unreadable = d.state == "unparsed"
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showText by remember { mutableStateOf(false) }
    val when_ = Persian.day(d.at, now) + " " + Persian.clock(d.at)
    val from = d.sender?.let { Persian.sender(it) } ?: "بانک"
    val canTeach = unreadable && d.raw != null && d.numbers.isNotEmpty() && store.supports("formats")

    AppCard(title = if (unreadable) "پیامک خوانده‌نشده" else "واریز بدون فاکتور") {
        if (unreadable) {
            ListRow(Ico.Mail, Tone.warn, from, "مبلغ از این پیامک خوانده نشد · $when_", badge = "قالب نامشخص")
            d.raw?.let {
                if (showText) SmsText(it, maxLines = 6) else LinkButton("نمایش متن پیامک", { showText = true }, icon = Ico.Eye)
            }
            if (canTeach) Notice("قالب این بانک را یک بار نشان دهید", "بعد از آموزش، پیامک‌های هم‌شکل خودکار خوانده می‌شوند.", Tone.info)
        } else {
            ListRow(Ico.Bank, Tone.warn, d.amount?.let { Money.toman(it) } ?: "—", "$from · $when_", badge = "بی‌فاکتور")
            Notice("مبلغ خوانده شده است", "فقط فاکتوری با این مبلغ پیدا نشد. «آموزش قالب» برای پیامک خوانده‌نشده است، نه برای این مورد.", Tone.warn)
        }
        d.note?.let { Text(Persian.digits(it), style = Type.supporting, color = Brand.Muted) }
        error?.let { Notice("انجام نشد", Persian.digits(it), Tone.bad) }
        when {
            !unreadable && store.supports("assign") -> PrimaryButton("انتخاب رسید", onAssign, enabled = !busy)
            canTeach -> SecondaryButton("آموزش قالب", onTeach, enabled = !busy, icon = Ico.Sparkles)
        }
        if (store.supports("assign")) DangerButton("کنار گذاشتن این " + (if (unreadable) "پیامک" else "واریز"), { asking = true }, enabled = !busy, busy = busy)
    }

    if (asking) {
        ConfirmDialog(
            "این " + (if (unreadable) "پیامک" else "واریز") + " کنار گذاشته شود؟",
            (d.amount?.let { "واریز " + Money.toman(it) + " از " } ?: "مورد ") + from + " از فهرست مغایرت‌ها بیرون می‌رود و هیچ سفارشی با آن تأیید نمی‌شود — مثلاً انتقال خودتان یا برگشت وجه. " +
                "این تصمیم فقط وضعیت آن را در ربات تغییر می‌دهد؛ تراکنش بانکی برگشت نمی‌خورد و پولی جابه‌جا نمی‌شود.",
            "کنار گذاشتن", {
                busy = true
                error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Receipts.ignore(context.applicationContext, d.id) }
                    busy = false
                    asking = false
                    when (r) {
                        Receipts.Decision.Done -> onChanged("این مورد از فهرست مغایرت‌ها کنار گذاشته شد.")
                        Receipts.Decision.AlreadyDecided -> onChanged("این مورد قبلاً تعیین‌تکلیف شده بود؛ فهرست تازه شد.")
                        Receipts.Decision.NeedKey -> store.revokeManageKey()
                        // It may have gone through: the list is read again rather than guessed.
                        is Receipts.Decision.Unknown -> onChanged("پاسخ نهایی نرسید؛ فهرست از ربات تازه شد. اگر مورد هنوز هست، کنار گذاشته نشده است.")
                        is Receipts.Decision.Failed -> error = r.message
                    }
                }
            },
            { asking = false }, danger = true, busy = busy,
        )
    }
}

/** A bank SMS as the bank wrote it: right-to-left text with left-to-right numbers, on a soft panel. */
@Composable
private fun SmsText(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        Persian.digits(text.trim()), style = Type.supporting.copy(textDirection = TextDirection.ContentOrRtl), color = Brand.Ink,
        maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.Canvas).padding(10.dp),
    )
}

// ── tie a deposit to a receipt (35, 36, 85, 97–99) ──────────────────────

private sealed interface AssignPhase {
    data object Picking : AssignPhase
    data class Confirm(val receipt: Receipts.Item) : AssignPhase
    data class Differs(val receipt: Receipts.Item, val d: Receipts.Assign.AmountDiffers) : AssignPhase
    data object Saving : AssignPhase
    data class Unknown(val message: String) : AssignPhase
    data class Done(val receipt: Receipts.Item, val accepted: Boolean) : AssignPhase
}

@Composable
internal fun AssignScreen(store: Store, deposit: Receipts.Item, now: Long, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Receipts.Item>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var gone by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<Receipts.Item?>(null) }
    var phase by remember { mutableStateOf<AssignPhase>(AssignPhase.Picking) }
    // A 409: someone settled the deposit or the receipt first — said, and the choices read again.
    var settled by remember { mutableStateOf(false) }
    val depositAmount = deposit.amount?.let { Money.toman(it) } ?: "—"

    LaunchedEffect(attempt) {
        error = null
        when (val r = withContext(Dispatchers.IO) { Receipts.candidates(context.applicationContext, deposit.id) }) {
            is Receipts.Result.Ok -> { items = r.value.second; gone = false }
            Receipts.Result.NeedKey -> store.revokeManageKey()
            is Receipts.Result.Failed -> error = r.message
            Receipts.Result.AlreadyDecided -> { gone = true; items = emptyList() }
        }
    }

    fun send(p: Receipts.Item, accept: Boolean) {
        phase = AssignPhase.Saving
        error = null
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { Receipts.assign(context.applicationContext, p.id, deposit.id, accept) }) {
                Receipts.Assign.Done -> {
                    store.event("system", "ok", "واریز " + depositAmount + " به رسید " + (p.customer ?: "مشتری") + " وصل و سفارش تأیید شد")
                    phase = AssignPhase.Done(p, accept)
                }
                is Receipts.Assign.AmountDiffers -> phase = AssignPhase.Differs(p, r)
                is Receipts.Assign.Refused -> { error = r.message; phase = AssignPhase.Picking }
                Receipts.Assign.NeedKey -> store.revokeManageKey()
                is Receipts.Assign.Unknown -> phase = AssignPhase.Unknown(r.message)
                Receipts.Assign.AlreadyDecided -> { settled = true; chosen = null; phase = AssignPhase.Picking; items = null; attempt++ }
            }
        }
    }

    ScreenHeader("اتصال واریز به رسید", "رسیدها") { onBack() }
    when (val p = phase) {
        AssignPhase.Saving -> {
            Hero("در انتظار پاسخ ربات", "هنوز نتیجهٔ قطعی اتصال دریافت نشده است.", Tone.info, icon = Ico.Sync)
            Waiting("ثبت اتصال…")
            PrimaryButton("در حال ثبت", {}, busy = true)
            return
        }
        is AssignPhase.Unknown -> {
            Hero("پاسخ نهایی دریافت نشد", "ممکن است واریز در ربات به رسید وصل شده باشد.", Tone.warn, Art.HealthWarning)
            Notice("دوباره ارسال نکنید", "ابتدا وضعیت این واریز از ربات خوانده می‌شود. (" + Persian.digits(p.message) + ")", Tone.warn)
            PrimaryButton("بررسی وضعیت واریز", { phase = AssignPhase.Picking; items = null; attempt++ }, icon = Ico.Refresh)
            SecondaryButton("بازگشت به مغایرت‌ها", onDone)
            return
        }
        is AssignPhase.Done -> {
            Hero("اتصال و تأیید انجام شد", "ربات پاسخ موفق داد؛ فهرست مغایرت‌ها تازه می‌شود.", Tone.good, Art.Success)
            AppCard(title = "نتیجهٔ عملیات") {
                Rows(
                    { ListRow(Ico.Bank, Tone.good, "واریز", depositAmount) },
                    {
                        ListRow(
                            Ico.Receipt, Tone.muted, p.receipt.customer ?: "مشتری",
                            (p.receipt.amount?.let { "مبلغ سفارش " + Money.toman(it) } ?: "") + (if (p.accepted) " · اختلاف مبلغ با تأیید صریح شما پذیرفته شد" else ""),
                        )
                    },
                )
            }
            PrimaryButton("بازگشت به مغایرت‌ها", onDone)
            return
        }
        else -> {}
    }

    Hero("واریز $depositAmount", listOfNotNull(deposit.sender?.let { "از " + Persian.sender(it) }, Persian.day(deposit.at, now) + " " + Persian.clock(deposit.at)).joinToString(" · ") + " — رسیدی را که این واریز متعلق به آن است انتخاب کنید.", Tone.info, icon = Ico.Bank)
    if (settled) Notice("در ربات زودتر تعیین‌تکلیف شد", "این واریز یا آن رسید پیش‌تر در ربات (یا گوشی دیگری) تصمیم گرفته شده بود؛ فهرست از ربات تازه شد.", Tone.info)
    error?.let { ErrorState(it) { attempt++ } }
    val list = items
    when {
        list == null && error == null -> { Waiting("دریافت رسیدهای منتظر…"); repeat(2) { Skeleton(Modifier.fillMaxWidth(), height = 64) } }
        gone -> {
            Notice("این واریز دیگر آزاد نیست", "به رسیدی وصل شده یا کنار گذاشته شده است؛ وضعیتش را در فهرست مغایرت‌ها ببینید.", Tone.info)
            SecondaryButton("بازگشت به مغایرت‌ها", onDone)
        }
        list != null && list.isEmpty() -> {
            EmptyState("رسید منتظری پیدا نشد", "هیچ رسید در انتظار بررسی برای اتصال به این واریز وجود ندارد.", Art.ReceiptsEmpty)
            SecondaryButton("دریافت دوباره", { items = null; attempt++ }, icon = Ico.Refresh)
            SecondaryButton("بازگشت به مغایرت‌ها", onBack)
        }
        list != null -> {
            AppCard(title = "رسیدهای منتظر") {
                list.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    val gap = (deposit.amount ?: 0) - (p.amount ?: 0)
                    // One touch target per receipt: the radio and its text are the same choice.
                    SelectRow(selected = chosen?.id == p.id, onSelect = { chosen = p }) {
                        ListRow(
                            Ico.Receipt, if (gap == 0L) Tone.good else Tone.warn, p.customer ?: "مشتری",
                            listOfNotNull(p.amount?.let { "مبلغ سفارش " + Money.toman(it) }, p.tracking?.let { Persian.latin(it) }, Persian.day(p.at, now)).joinToString(" · "),
                            // Whose amount differs is the question: it is the DEPOSIT that is more or less.
                            badge = if (gap == 0L) "همان مبلغ" else "واریز " + Money.toman(kotlin.math.abs(gap)).removeSuffix(" تومان") + (if (gap > 0) " بیشتر" else " کمتر"),
                            merge = false,
                        )
                    }
                }
            }
            val c = chosen
            PrimaryButton(if (c == null) "ابتدا یک رسید انتخاب کنید" else "اتصال به رسید " + (c.customer ?: "مشتری"), { c?.let { phase = AssignPhase.Confirm(it) } }, enabled = c != null)
        }
    }

    when (val p = phase) {
        is AssignPhase.Confirm -> {
            val gap = (deposit.amount ?: 0) - (p.receipt.amount ?: 0)
            ConfirmDialog(
                "واریز به این رسید وصل شود؟",
                "واریز $depositAmount به سفارش " + (p.receipt.customer ?: "مشتری") + (p.receipt.tracking?.let { " " + Persian.latin(it) } ?: "") +
                    " با مبلغ " + (p.receipt.amount?.let { Money.toman(it) } ?: "—") + " متصل و سفارش تأیید و تحویل شود؟" +
                    (if (gap != 0L) " مبلغ‌ها یکی نیستند: واریز " + Money.toman(kotlin.math.abs(gap)) + (if (gap > 0) " بیشتر" else " کمتر") + " است." else ""),
                "اتصال و تأیید سفارش", { send(p.receipt, false) }, { phase = AssignPhase.Picking }, cancel = "بازگشت",
            )
        }
        is AssignPhase.Differs -> {
            val gap = p.d.deposit - p.d.expected
            ConfirmDialog(
                "مبلغ‌ها یکی نیست",
                "واریز " + Money.toman(p.d.deposit) + " و سفارش " + Money.toman(p.d.expected) + " است: واریز " + Money.toman(kotlin.math.abs(gap)) +
                    (if (gap > 0) " بیشتر" else " کمتر") + " است. فقط اگر مطمئنید این پول برای همین سفارش است ادامه دهید.",
                "با همین مبلغ تأیید کن", { send(p.receipt, true) }, { phase = AssignPhase.Picking }, danger = true,
            )
        }
        else -> {}
    }
}

// ── teach the bot a bank's SMS (37, 38, 86–88) ──────────────────────────

/** A number the seller may pick as a DEPOSIT amount: never a signed (withdrawn) one. Pure: unit-tested. */
fun teachableAmount(text: String): Boolean {
    val t = text.trim()
    return t.isNotEmpty() && !t.startsWith("-") && !t.startsWith("−") && !t.endsWith("-") && t.any { it.isDigit() }
}

/**
 * Three choices, each a tap: which number is the amount, rial or toman, which (if any) is the balance.
 * The SMS's numbers are chips in the order they appear in it; its text only on request.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeachScreen(store: Store, sms: Receipts.Item, now: Long, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val raw = sms.raw ?: ""
    var amount by remember { mutableStateOf<Int?>(null) }
    // The SMS's own word for the unit, when it has one; rial otherwise — what nearly every bank uses.
    var unit by remember { mutableStateOf(if (raw.contains("تومان") && !raw.contains("ریال") && !raw.contains("ريال")) "toman" else "rial") }
    var balance by remember { mutableStateOf<Int?>(null) }
    var noBalance by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Int?>(null) }
    var showText by remember { mutableStateOf(false) }
    val amounts = sms.numbers.filter { teachableAmount(it.text) }
    val picked = sms.numbers.firstOrNull { it.slot == amount }?.text?.filter { it.isDigit() }?.toLongOrNull()
    val toman = picked?.let { if (unit == "rial") it / 10 else it }

    ScreenHeader("آموزش قالب بانک", "رسیدها") { onBack() }
    if (done != null) {
        Hero("قالب بانک ثبت شد", "پیامک‌های هم‌شکل این بانک از این به بعد خوانده می‌شوند.", Tone.good, Art.Learning)
        AppCard(title = "نتیجه") {
            Rows(
                { ListRow(Ico.Bank, Tone.good, "قالب " + (sms.sender?.let { Persian.sender(it) } ?: "بانک"), (if (unit == "toman") "تومان" else "ریال") + (if (noBalance) " · بدون مانده" else " · با مانده"), badge = "آماده") },
                { ListRow(Ico.Inbox, Tone.good, "پیامک‌های بررسی‌شده", if (done!! > 0) Persian.number(done!!) + " پیامک اخیر دوباره بررسی شد" else "پیامک اخیری برای بررسی دوباره نبود", badge = Persian.number(done!!)) },
            )
        }
        PrimaryButton("تمام", onDone)
        return
    }
    if (error != null) {
        Hero("ذخیرهٔ قالب انجام نشد", "پاسخ خطا دریافت شد؛ انتخاب‌های شما حفظ شده‌اند.", Tone.bad, Art.Learning)
        Notice("مبلغ و واحد را دوباره بررسی کنید", Persian.digits(error!!) + " — پیامک برداشت یا مبلغ منفی با قالب واریز پذیرفته نمی‌شود.", Tone.warn)
        PrimaryButton("بازبینی انتخاب‌ها", { error = null })
        SecondaryButton("بازگشت به مغایرت‌ها", onBack)
        return
    }
    if (busy) {
        Hero("در حال ذخیرهٔ قالب", "گزینه‌ها تا دریافت نتیجه قفل هستند.", Tone.info, icon = Ico.Sync)
        AppCard(title = "خلاصهٔ انتخاب") {
            Rows(
                { ListRow(Ico.Bank, Tone.muted, "مبلغ واریز", toman?.let { Money.toman(it) } ?: "—") },
                { ListRow(Ico.Receipt, Tone.muted, "مانده", if (noBalance) "ندارد" else sms.numbers.firstOrNull { it.slot == balance }?.text?.let { Persian.digits(it) } ?: "—") },
            )
            Waiting("ذخیره در ربات…")
        }
        return
    }

    Hero(
        if (amount == null) "قالب را یک بار آموزش دهید" else "آموزش خواندن پیامک",
        if (amount == null) "اول مبلغ واریز را در پیامک انتخاب کنید." else "با سه انتخاب ساده، ربات شکل پیامک این بانک را یاد می‌گیرد.",
        Tone.info, icon = Ico.Sparkles,
    )
    AppCard(title = "پیامک " + (sms.sender?.let { Persian.sender(it) } ?: "بانک")) {
        Text(Persian.day(sms.at, now) + " " + Persian.clock(sms.at) + " · پیامک برداشت یا مبلغ منفی هرگز با این قالب خوانده نمی‌شود.", style = Type.supporting, color = Brand.Muted)
        if (showText) SmsText(raw) else LinkButton("نمایش متن پیامک", { showText = true }, icon = Ico.Eye)
    }
    AppCard(title = "۱. مبلغ واریز کدام است؟") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            amounts.forEach { n -> Chip(Persian.digits(n.text), amount == n.slot, { amount = n.slot; if (balance == n.slot) balance = null }) }
        }
        if (amounts.size < sms.numbers.size) Text("اعداد منفی (برداشت) برای مبلغ واریز قابل انتخاب نیستند.", style = Type.supporting, color = Brand.Muted)
    }
    if (amount == null) {
        Notice("هنوز مبلغ انتخاب نشده است", "بعد از انتخاب مبلغ، واحد و ماندهٔ حساب پرسیده می‌شود.", Tone.info)
        return
    }
    AppCard(title = "۲. واحد مبلغ چیست؟") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Chip("ریال", unit == "rial", { unit = "rial" }, Modifier.weight(1f))
            Chip("تومان", unit == "toman", { unit = "toman" }, Modifier.weight(1f))
        }
    }
    AppCard(title = "۳. ماندهٔ حساب کدام است؟") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sms.numbers.filter { it.slot != amount }.forEach { n -> Chip(Persian.digits(n.text), balance == n.slot, { balance = n.slot; noBalance = false }) }
            Chip("مانده ندارد", noBalance, { noBalance = true; balance = null })
        }
        Text("مانده برای «زنجیرهٔ مانده» به کار می‌رود؛ اگر پیامک مانده ندارد «مانده ندارد» را بزنید.", style = Type.supporting, color = Brand.Muted)
    }
    toman?.let { Notice("مبلغ نهایی: " + Money.toman(it), "واحد و مانده را کنترل کنید؛ اگر عدد درست نیست، واحد را عوض کنید.", Tone.info) }
    PrimaryButton("ذخیرهٔ قالب", {
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { Receipts.teach(context.applicationContext, sms.id, amount!!, balance, unit) }
            busy = false
            when (r) {
                is Receipts.Result.Ok -> {
                    done = r.value
                    store.event("system", "ok", "قالب پیامک " + (sms.sender ?: "بانک") + " آموزش داده شد")
                }
                Receipts.Result.NeedKey -> store.revokeManageKey()
                is Receipts.Result.Failed -> error = r.message
                Receipts.Result.AlreadyDecided -> error = "این پیامک دیگر خوانده‌نشده نیست."
            }
        }
    }, enabled = balance != null || noBalance)
}
