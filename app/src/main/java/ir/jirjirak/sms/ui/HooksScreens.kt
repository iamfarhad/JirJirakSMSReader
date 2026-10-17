package ir.jirjirak.sms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.Hook
import ir.jirjirak.sms.Hooks
import ir.jirjirak.sms.Rules
import ir.jirjirak.sms.Store
import java.util.UUID

/**
 * «وب‌هوک‌های دیگر»: the seller's own addresses, each with the one sender whose SMS go there — a
 * card-holder panel's link for the card's bank, say. A list with a prominent add button; a tap opens
 * the same one-page form for editing, switching off and deleting.
 */
@Composable
internal fun HooksScreen(store: Store, now: Long) {
    val nav = LocalNav.current
    val hooks = store.hooks
    val queued = store.hookQueueSize()
    ScreenHeader("وب‌هوک‌های دیگر", "بیشتر") { nav.more() }
    Hero(
        "وب‌هوک‌های دیگر",
        "پیامک بانکِ یک شمارهٔ مشخص، جدا از ربات، به آدرس دلخواه شما هم فرستاده می‌شود.",
        Tone.info, icon = Ico.External,
    )
    AppCard(title = "وب‌هوک‌ها", trailing = { StatusBadge(Persian.number(hooks.size), Tone.muted) }) {
        if (hooks.isEmpty()) Text("هنوز وب‌هوکی اضافه نشده است.", style = Type.supporting, color = Brand.Muted)
        hooks.forEachIndexed { i, h ->
            if (i > 0) Divider()
            ListRow(
                Ico.External,
                when {
                    !h.on -> Tone.muted
                    h.lastOk == false -> Tone.warn
                    else -> Tone.good
                },
                Persian.latin(h.host),
                "فرستنده: " + Persian.sender(h.sender) + " · " + lastLine(h, now),
                badge = if (h.on) "روشن" else "خاموش",
                onClick = { nav.more("hook:" + h.id) },
            )
        }
        PrimaryButton("افزودن وب‌هوک", { nav.more("hook:new") })
    }
    if (queued > 0) {
        Notice(
            Persian.number(queued) + " پیامک منتظر ارسال به وب‌هوک‌هاست",
            "با وصل شدن اینترنت یا پاسخ سرور وب‌هوک، خودکار فرستاده می‌شوند.", Tone.warn,
        )
    }
    var disabling by remember { mutableStateOf(false) }
    Notice(
        "متن پیامک به همین آدرس می‌رود",
        "پیامک بانکیِ شمارهٔ انتخاب‌شده با روش POST و در فیلد Message به آدرس وب‌هوک فرستاده می‌شود؛ " +
            "رمز یکبار مصرف هرگز. آن سرویس ربطی به جیرجیرک ندارد؛ فقط آدرسی را وارد کنید که به آن اعتماد دارید.",
        Tone.info, Ico.Shield,
    )
    // Off again: hidden and silent. The list stays, for the day it is turned back on.
    SecondaryButton("خاموش کردن این قابلیت", { disabling = true })
    if (disabling) {
        ConfirmDialog(
            "«وب‌هوک‌های دیگر» خاموش شود؟",
            "هیچ پیامکی به وب‌هوک‌ها فرستاده نمی‌شود و این بخش از «بیشتر» پنهان می‌شود. وب‌هوک‌های ثبت‌شده" +
                (if (queued > 0) " و " + Persian.number(queued) + " پیامکِ در صف" else "") + " می‌مانند تا دوباره روشنش کنید.",
            "خاموش کن", {
                store.hooksOn = false
                store.event("system", "info", "قابلیت «وب‌هوک‌های دیگر» خاموش شد")
                disabling = false
                nav.more()
            }, { disabling = false }, cancel = "روشن بماند",
        )
    }
}

/** The last delivery in a few words: «هنوز پیامکی نرفته», «آخرین ارسال ۳ دقیقه پیش», or the error. */
internal fun lastLine(h: Hook, now: Long): String = when {
    h.lastAt == 0L -> "هنوز پیامکی نرفته"
    h.lastOk == true -> "آخرین ارسال " + Persian.ago(h.lastAt, now)
    else -> "خطا " + Persian.ago(h.lastAt, now) + ": " + Persian.bidi(h.lastNote ?: "نامشخص")
}

/**
 * Add («hook:new») or edit one webhook: its address and its sender on one page, both required;
 * an existing one also switches on/off and deletes. «انصراف» and back leave without saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HookEditScreen(store: Store, id: String) {
    val nav = LocalNav.current
    val existing = store.hooks.firstOrNull { it.id == id }
    var url by rememberSaveable(id) { mutableStateOf(existing?.url ?: "") }
    var sender by rememberSaveable(id) { mutableStateOf(existing?.sender ?: "") }
    var on by rememberSaveable(id) { mutableStateOf(existing?.on ?: true) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var senderError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }

    // An id that is neither «new» nor a webhook any more (deleted elsewhere): back to the list.
    if (id != "new" && existing == null) {
        ScreenHeader("وب‌هوک", "وب‌هوک‌ها") { nav.more("hooks") }
        EmptyState("این وب‌هوک دیگر وجود ندارد", null, icon = Ico.External)
        SecondaryButton("بازگشت به فهرست", { nav.more("hooks") })
        return
    }

    ScreenHeader(if (existing == null) "وب‌هوک تازه" else "ویرایش وب‌هوک", "وب‌هوک‌ها") { nav.more("hooks") }
    AppCard(title = "آدرس وب‌هوک") {
        PlainField(url, { url = it; urlError = null }, "آدرس کامل، با https://", ltr = true, isError = urlError != null)
        urlError?.let { Text(it, style = Type.supporting, color = Brand.Bad) }
        Text("همان آدرسی که سرویس مقصد به شما داده، کامل و با همهٔ پارامترهایش (مثل userId).", style = Type.supporting, color = Brand.Muted)
    }
    AppCard(title = "شمارهٔ فرستندهٔ پیامک") {
        PlainField(sender, { sender = it; senderError = null }, "شماره یا شناسهٔ بانک", ltr = true, isError = senderError != null)
        senderError?.let { Text(it, style = Type.supporting, color = Brand.Bad) }
        val quick = (store.senders + store.suggestions).distinctBy { Rules.normalize(it) }
        if (quick.isNotEmpty()) {
            Text("یا یکی از شماره‌های این گوشی:", style = Type.supporting, color = Brand.Muted)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { s ->
                    Chip(Persian.sender(s), sender.isNotBlank() && Rules.isAllowed(sender, listOf(s)), { sender = s; senderError = null })
                }
            }
        }
        Text("فقط پیامک‌های همین فرستنده به این آدرس می‌رود. لازم نیست این شماره برای ربات هم مجاز باشد.", style = Type.supporting, color = Brand.Muted)
    }
    if (existing != null) {
        AppCard(title = "وضعیت") {
            Segmented(listOf("روشن", "خاموش"), if (on) 0 else 1) { on = it == 0 }
            if (!on) Text("خاموش که باشد، پیامک تازه‌ای برای این آدرس صف نمی‌شود و صف فعلی منتظر می‌ماند.", style = Type.supporting, color = Brand.Muted)
            if (existing.lastAt > 0) Text(lastLine(existing, System.currentTimeMillis()), style = Type.supporting, color = Brand.Muted)
        }
    }

    PrimaryButton(if (existing == null) "ذخیرهٔ وب‌هوک" else "ذخیرهٔ تغییرات", {
        val parsed = Hooks.parseUrl(url)
        val from = sender.trim()
        urlError = if (parsed == null) "آدرس باید کامل باشد و با https:// شروع شود." else null
        senderError = when {
            Rules.normalize(from).isEmpty() -> "شماره یا شناسهٔ فرستنده را بنویسید."
            parsed != null && store.hooks.any { it.id != existing?.id && it.url == parsed && Rules.isAllowed(from, listOf(it.sender)) } ->
                "همین آدرس برای همین شماره از قبل ثبت شده است."
            else -> null
        }
        if (urlError == null && senderError == null && parsed != null) {
            val hook = existing?.copy(url = parsed, sender = from, on = on) ?: Hook(UUID.randomUUID().toString(), parsed, from, on = true)
            store.saveHook(hook)
            store.event(
                "system", "info", if (existing == null) "وب‌هوک اضافه شد" else "وب‌هوک ویرایش شد",
                Persian.latin(hook.host) + " · فرستنده " + Persian.sender(from) + (if (hook.on) "" else " · خاموش"),
            )
            nav.more("hooks")
        }
    })
    SecondaryButton("انصراف", { nav.more("hooks") })
    if (existing != null) DangerButton("حذف این وب‌هوک", { deleting = true })

    if (deleting && existing != null) {
        val waiting = store.hookQueueSize(existing.id)
        ConfirmDialog(
            "وب‌هوک حذف شود؟",
            "پیامک‌های " + Persian.sender(existing.sender) + " دیگر به " + Persian.latin(existing.host) + " فرستاده نمی‌شود." +
                (if (waiting > 0) " " + Persian.number(waiting) + " پیامکِ در صف این آدرس هم حذف می‌شود." else ""),
            "حذف وب‌هوک", {
                store.deleteHook(existing.id)
                store.event("system", "info", "وب‌هوک حذف شد", Persian.latin(existing.host) + " · فرستنده " + Persian.sender(existing.sender))
                deleting = false
                nav.more("hooks")
            }, { deleting = false }, cancel = "نگه داشتن", danger = true,
        )
    }
}
