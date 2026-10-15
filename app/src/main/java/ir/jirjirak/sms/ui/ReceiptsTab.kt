package ir.jirjirak.sms.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.jirjirak.sms.Money
import ir.jirjirak.sms.Receipts
import ir.jirjirak.sms.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «رسیدها»: what waits for the seller, with the receipt photo and the bot's reasoning, decided here. */
@Composable
fun ReceiptsTab(store: Store, now: Long) {
    watchStore()
    if (store.manageKey == null) {
        ManageKeyCard(store, revoked = false)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var receipts by remember { mutableStateOf<List<Receipts.Receipt>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needKey by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reasons by remember { mutableStateOf<List<Receipts.Reason>>(emptyList()) }

    fun load() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Receipts.list(context.applicationContext) }
            loading = false
            when (r) {
                is Receipts.Result.Ok -> { receipts = r.value; error = null }
                Receipts.Result.NeedKey -> needKey = true
                is Receipts.Result.Failed -> error = r.message
            }
            if (reasons.isEmpty()) {
                (withContext(Dispatchers.IO) { Receipts.reasons(context.applicationContext) } as? Receipts.Result.Ok)?.let { reasons = it.value }
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    if (needKey) {
        ManageKeyCard(store, revoked = true)
        return
    }

    Section(
        "رسیدهای در انتظار", Icons.Filled.Check,
        trailing = {
            if (loading) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp, color = Brand.Green)
            else IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, contentDescription = "تازه کردن", tint = Brand.Green) }
        },
    ) {
        error?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
        when {
            receipts == null && error == null -> Text("در حال دریافت…", color = Brand.Muted, fontSize = 13.sp)
            receipts?.isEmpty() == true -> Text("🎉 رسیدی در انتظار بررسی نیست. هر رسیدی که خودکار تأیید نشود، اینجا می‌آید.", color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
    receipts?.forEach { r ->
        ReceiptCard(r, now, reasons, onDecided = { message ->
            receipts = receipts?.filter { it.id != r.id }
            store.event("system", "ok", message)
        })
    }
}

@Composable
private fun ReceiptCard(r: Receipts.Receipt, now: Long, reasons: List<Receipts.Reason>, onDecided: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember(r.id) { mutableStateOf<Bitmap?>(null) }
    var showImage by remember(r.id) { mutableStateOf(false) }
    var busy by remember(r.id) { mutableStateOf(false) }
    var error by remember(r.id) { mutableStateOf<String?>(null) }
    var confirmApprove by remember(r.id) { mutableStateOf(false) }
    var rejecting by remember(r.id) { mutableStateOf(false) }

    LaunchedEffect(r.id, showImage) {
        if (showImage && image == null && r.hasImage) image = withContext(Dispatchers.IO) { Receipts.image(context.applicationContext, r.id) }
    }

    fun decide(action: suspend () -> Receipts.Result<Unit>, done: String) {
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            busy = false
            when (result) {
                is Receipts.Result.Ok -> onDecided(done)
                Receipts.Result.NeedKey -> error = "کد مدیریت باطل شده؛ کد تازه را از ربات بچسبانید."
                is Receipts.Result.Failed -> error = result.message
            }
        }
    }

    Section(
        Money.toman(r.amount), Icons.Filled.Lock, Brand.Warn,
        trailing = { Pill(Persian.ago(r.createdAt, now), Brand.WarnSoft, Brand.Warn) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(r.customer, r.username?.let { "@$it" }).joinToString(" · ").ifEmpty { "مشتری" },
                color = Brand.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            )
            Text(listOfNotNull(r.title, r.rail, r.tracking?.let { "کد پیگیری $it" }).joinToString(" · "), color = Brand.Muted, fontSize = 12.sp)
        }
        if (r.score != null) {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Brand.Canvas).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("🤖 امتیاز ربات: " + Persian.number(r.score) + " از " + Persian.number(r.threshold ?: 70) + " لازم", color = Brand.Ink, fontSize = 13.sp)
                r.why.forEach { Text(it, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp) }
            }
        }
        if (r.hasImage) {
            if (!showImage) {
                TextButton(onClick = { showImage = true }) { Text("🖼 دیدن عکس رسید") }
            } else if (image == null) {
                Text("در حال دریافت عکس…", color = Brand.Muted, fontSize = 12.sp)
            } else {
                Image(
                    image!!.asImageBitmap(), contentDescription = "عکس رسید",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(MaterialTheme.shapes.small).clickable { showImage = false },
                )
            }
        }
        error?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { confirmApprove = true }, enabled = !busy,
                modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
            ) { Text(if (busy) "…" else "✅ تأیید") }
            OutlinedButton(
                onClick = { rejecting = true }, enabled = !busy,
                modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Bad), border = dangerBorder(),
            ) { Text("❌ رد") }
        }
    }

    if (confirmApprove) {
        AlertDialog(
            onDismissRequest = { confirmApprove = false },
            title = { Text("تأیید رسید") },
            text = { Text(Money.toman(r.amount) + (r.customer?.let { " از $it" } ?: "") + " تأیید و سفارش تحویل شود؟", lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmApprove = false
                    decide({ Receipts.approve(context.applicationContext, r.id) }, "رسید " + Money.toman(r.amount) + " از اپ تأیید شد")
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
                    (reasons.ifEmpty { listOf(Receipts.Reason("not_found", "تراکنش در حساب مقصد یافت نشد")) }).forEach { reason ->
                        Text(
                            reason.label, color = Brand.Ink, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable {
                                rejecting = false
                                decide({ Receipts.reject(context.applicationContext, r.id, reason.code) }, "رسید " + Money.toman(r.amount) + " از اپ رد شد: " + reason.label)
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
        store.event("system", "ok", "تأیید و رد رسید از اپ فعال شد")
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
        OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("یا کد را اینجا بچسبانید") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (typed.isNotBlank()) {
            OutlinedButton(onClick = { take(typed) }, modifier = Modifier.fillMaxWidth()) { Text("فعال کن") }
        }
        error?.let { Notice(it, Brand.BadSoft, Brand.Bad) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("🔒 کد اتصال فقط اجازهٔ «فرستادن پیامک» دارد؛ این کد اجازهٔ «تأیید و رد» را.", color = Brand.Muted, fontSize = 11.sp)
        }
    }
}
