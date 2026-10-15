package ir.jirjirak.sms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    AppCard(padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(if (unreadable) Icons.Filled.Warning else Icons.Filled.Info, if (unreadable) Tone.warn else Tone.bad, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (unreadable) "پیامک بانکی خوانده نشد" else "واریز بدون فاکتور", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(listOfNotNull(d.sender?.let { "از " + Persian.sender(it) }, Persian.day(d.at, now) + " " + Persian.clock(d.at)).joinToString(" · "), color = Brand.Muted, fontSize = 12.sp)
            }
            StatusBadge("مغایرت", if (unreadable) Tone.warn else Tone.bad)
        }
        if (d.amount != null) Text(Money.toman(d.amount), color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        d.note?.let { Text(Persian.digits(it), color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp) }
        d.raw?.let { SmsText(it, maxLines = 3) }
        error?.let { Text(it, color = Brand.Bad, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                !unreadable && store.supports("assign") ->
                    Button(onClick = onAssign, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("اتصال به رسید", fontSize = 13.sp) }
                unreadable && d.raw != null && d.numbers.isNotEmpty() && store.supports("formats") ->
                    Button(onClick = onTeach, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("آموزش قالب", fontSize = 13.sp) }
            }
            if (store.supports("assign")) {
                OutlinedButton(onClick = { asking = true }, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text(if (busy) "…" else "فروش نیست", fontSize = 13.sp)
                }
            }
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("کنار گذاشتن این واریز") },
            text = {
                Text(
                    "این مورد از فهرست مغایرت‌ها بیرون می‌رود و هیچ سفارشی با آن تأیید نمی‌شود — مثلاً انتقال خودتان یا برگشت وجه. " +
                        "در ربات ثبت می‌شود که از اپ کنار گذاشته شد.",
                    lineHeight = 22.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Receipts.ignore(context.applicationContext, d.id) }
                        busy = false
                        when (r) {
                            is Receipts.Result.Ok -> onChanged("✅ این مورد از فهرست مغایرت‌ها کنار گذاشته شد.")
                            Receipts.Result.AlreadyDecided -> onChanged(null)
                            Receipts.Result.NeedKey -> store.revokeManageKey()
                            is Receipts.Result.Failed -> error = r.message
                        }
                    }
                }) { Text("کنار بگذار") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("انصراف") } },
        )
    }
}

/** A bank SMS as the bank wrote it: right-to-left text with left-to-right numbers, on a soft panel. */
@Composable
private fun SmsText(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        Persian.digits(text.trim()),
        color = Brand.Ink, fontSize = 13.sp, lineHeight = 20.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(textDirection = TextDirection.ContentOrRtl),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.Canvas).padding(10.dp),
    )
}

@Composable
private fun ScreenTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "بازگشت", tint = Brand.Ink) }
        Text(title, color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

// ── tie a deposit to a receipt ──────────────────────────────────────────

@Composable
internal fun AssignScreen(store: Store, deposit: Receipts.Item, now: Long, onBack: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Receipts.Item>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<Receipts.Item?>(null) }
    // The receipt the seller chose and the bot's two amounts, when they differ: asked once more.
    var differs by remember { mutableStateOf<Pair<Receipts.Item, Receipts.Assign.AmountDiffers>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        error = null
        when (val r = withContext(Dispatchers.IO) { Receipts.candidates(context.applicationContext, deposit.id) }) {
            is Receipts.Result.Ok -> items = r.value.second
            Receipts.Result.NeedKey -> store.revokeManageKey()
            is Receipts.Result.Failed -> error = r.message
            Receipts.Result.AlreadyDecided -> error = "این واریز دیگر آزاد نیست؛ به فهرست برگردید."
        }
    }

    fun send(p: Receipts.Item, accept: Boolean) {
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { Receipts.assign(context.applicationContext, p.id, deposit.id, accept) }
            busy = false
            when (r) {
                Receipts.Assign.Done -> {
                    val said = "✅ واریز " + (deposit.amount?.let { Money.toman(it) } ?: "") + " به رسید " + (p.customer ?: "مشتری") + " وصل و سفارش تأیید شد."
                    store.event("system", "ok", said.removePrefix("✅ "))
                    onDone(said)
                }
                is Receipts.Assign.AmountDiffers -> differs = p to r
                is Receipts.Assign.Refused -> error = r.message
                Receipts.Assign.NeedKey -> store.revokeManageKey()
            }
        }
    }

    ScreenTitle("اتصال واریز به رسید", onBack)
    AppCard {
        SectionHeader("واریز")
        Text(deposit.amount?.let { Money.toman(it) } ?: "—", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(
            listOfNotNull(deposit.sender?.let { "از " + Persian.sender(it) }, Persian.day(deposit.at, now) + " " + Persian.clock(deposit.at)).joinToString(" · "),
            color = Brand.Muted, fontSize = 12.sp,
        )
        Text("رسیدی را که این پول برای آن است انتخاب کنید. همان رسید تأیید و سفارشش تحویل می‌شود.", color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    error?.let { ErrorState(it) { attempt++ } }
    val list = items
    when {
        list == null && error == null -> repeat(3) { Skeleton(Modifier.fillMaxWidth(), height = 76) }
        list != null && list.isEmpty() -> AppCard { EmptyState(Icons.Filled.CheckCircle, "رسید منتظری نیست", "رسیدی در انتظار بررسی نیست که این واریز به آن وصل شود.") }
        list != null -> AppCard {
            SectionHeader("رسیدهای منتظر")
            list.forEach { p ->
                val gap = (deposit.amount ?: 0) - (p.amount ?: 0)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Brand.Line, RoundedCornerShape(14.dp))
                        .clickable(enabled = !busy) { chosen = p }.padding(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.customer ?: "مشتری", color = Brand.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(p.tracking?.let { Persian.latin(it) }, p.title, Persian.day(p.at, now) + " " + Persian.clock(p.at)).joinToString(" · "),
                            color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(p.amount?.let { Money.toman(it) } ?: "—", color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (gap == 0L) StatusBadge("همان مبلغ", Tone.good)
                        // Whose amount differs is the question: it is the DEPOSIT that is more or less.
                        else StatusBadge("واریز " + Money.toman(kotlin.math.abs(gap)) + (if (gap > 0) " بیشتر" else " کمتر"), Tone.warn)
                    }
                }
            }
        }
    }

    chosen?.let { p ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text("اتصال و تأیید") },
            text = {
                Text(
                    "واریز " + (deposit.amount?.let { Money.toman(it) } ?: "") + " به رسید " + (p.customer ?: "مشتری") +
                        " (" + (p.amount?.let { Money.toman(it) } ?: "") + ") وصل و سفارش تأیید و تحویل شود؟",
                    lineHeight = 22.sp,
                )
            },
            confirmButton = { TextButton(onClick = { chosen = null; send(p, false) }) { Text("وصل و تأیید کن") } },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("انصراف") } },
        )
    }
    differs?.let { (p, d) ->
        AlertDialog(
            onDismissRequest = { differs = null },
            title = { Text("مبلغ‌ها یکی نیست") },
            text = {
                Text(
                    "واریز " + Money.toman(d.deposit) + " است ولی رسید " + Money.toman(d.expected) + ". " +
                        "فقط اگر مطمئنید این پول برای همین سفارش است (مثلاً مشتری رقم را گرد کرده) ادامه دهید.",
                    lineHeight = 22.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { differs = null; send(p, true) }, colors = ButtonDefaults.textButtonColors(contentColor = Brand.Warn)) {
                    Text("با همین مبلغ تأیید کن")
                }
            },
            dismissButton = { TextButton(onClick = { differs = null }) { Text("انصراف") } },
        )
    }
}

// ── teach the bot a bank's SMS ──────────────────────────────────────────

/**
 * Three choices, each a tap: which number is the amount, rial or toman, which (if any) is the balance.
 * The SMS is shown as it is, its numbers as chips in the order they appear in it.
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

    ScreenTitle("آموزش قالب پیامک بانک", onBack)
    AppCard {
        Text(
            "ربات این پیامک را نخواند. یک بار نشانش دهید کدام عدد مبلغ واریز است؛ از این به بعد پیامک‌های هم‌شکلِ همین بانک خودکار خوانده می‌شوند. " +
                "پیامک برداشت یا مبلغ منفی هرگز با آن خوانده نمی‌شود.",
            color = Brand.Muted, fontSize = 12.sp, lineHeight = 19.sp,
        )
        SmsText(raw)
        Text(listOfNotNull(sms.sender?.let { "از " + Persian.sender(it) }, Persian.day(sms.at, now) + " " + Persian.clock(sms.at)).joinToString(" · "), color = Brand.Muted, fontSize = 12.sp)
    }

    AppCard {
        StepTitle(1, "کدام عدد مبلغ واریز است؟", amount != null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sms.numbers.forEach { n -> NumberChip(n.text, selected = amount == n.slot, enabled = done == null) { amount = n.slot; if (balance == n.slot) balance = null } }
        }
    }
    if (amount != null) {
        AppCard {
            StepTitle(2, "مبلغ در پیامک به چه واحدی است؟", true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberChip("ریال", unit == "rial", enabled = done == null) { unit = "rial" }
                NumberChip("تومان", unit == "toman", enabled = done == null) { unit = "toman" }
            }
            val picked = sms.numbers.firstOrNull { it.slot == amount }?.text?.filter { it.isDigit() }?.toLongOrNull()
            if (picked != null) {
                Text("یعنی واریز " + Money.toman(if (unit == "rial") picked / 10 else picked), color = Brand.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        AppCard {
            StepTitle(3, "کدام عدد ماندهٔ حساب بعد از واریز است؟", balance != null || noBalance)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sms.numbers.filter { it.slot != amount }.forEach { n -> NumberChip(n.text, selected = balance == n.slot, enabled = done == null) { balance = n.slot; noBalance = false } }
                NumberChip("مانده ندارد", selected = noBalance, enabled = done == null) { noBalance = true; balance = null }
            }
            Text("مانده برای «زنجیرهٔ مانده» به کار می‌رود؛ اگر پیامک مانده ندارد «مانده ندارد» را بزنید.", color = Brand.Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
    error?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
    done?.let { n ->
        Notice(
            "قالب ذخیره شد." + if (n > 0) " " + Persian.number(n) + " پیامک اخیر همین بانک دوباره خوانده و بررسی شد." else "",
            Brand.GoodSoft, Brand.Good,
        )
    }
    Button(
        onClick = {
            if (done != null) { onDone(); return@Button }
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
        },
        enabled = !busy && amount != null && (balance != null || noBalance || done != null),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(if (done != null) "تمام" else if (busy) "در حال ذخیره…" else "ذخیرهٔ قالب", fontSize = 15.sp) }
}

@Composable
private fun StepTitle(n: Int, title: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(Persian.number(n) + " از ۳", if (done) Tone.good else Tone.info)
        Spacer(Modifier.width(8.dp))
        Text(title, color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun NumberChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        Persian.digits(text),
        color = if (selected) Brand.Surface else Brand.Ink,
        fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Brand.Green else Brand.Canvas)
            .border(1.dp, if (selected) Brand.Green else Brand.Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}
