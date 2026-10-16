package ir.jirjirak.sms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.Store
import org.json.JSONObject

/**
 * Read this first in EVERY composable that shows something from the Store — each card, not only
 * the screen around them: the card is then redrawn whenever the Store changes, from a button, a
 * receiver or a background job alike. A parent's read does not reach a child whose parameters
 * are unchanged: Compose skips that child, which is how the status card kept saying «هنوز نه»
 * after a ping had plainly arrived.
 */
@Composable
fun watchStore(): Int = Store.revision.intValue

/** How an event is drawn: its icon, tone name and the word for its state. */
object EventLook {
    data class Look(val icon: Ico, val tone: String, val state: String)

    fun of(e: JSONObject): Look {
        val kind = e.optString("kind")
        val status = e.optString("status")
        return when {
            // Delivered, but the bot could not read it: a new bank format, not a success.
            kind == "sms" && status == "sent" && unreadable(e) -> Look(Ico.Alert, "warn", "رسید ولی خوانده نشد")
            // Transport only: whether the payment was approved is the bot's verdict under it.
            kind == "sms" && status == "sent" -> Look(Ico.Check, "good", "به ربات رسید")
            kind == "sms" && status == "queued" -> Look(Ico.Clock, "warn", "در صف")
            kind == "sms" && status == "retry" -> Look(Ico.Refresh, "warn", "تلاش دوباره")
            kind == "sms" -> Look(Ico.Close, "bad", "ارسال نشد")
            kind == "ignored" && e.optString("title").contains("رمز") -> Look(Ico.Shield, "info", "روی گوشی ماند")
            kind == "ignored" -> Look(Ico.Close, "muted", "فرستاده نشد")
            status == "warn" -> Look(Ico.Alert, "warn", "هشدار")
            status == "ok" -> Look(Ico.Check, "good", "")
            kind == "net" -> Look(Ico.Wifi, "info", "")
            kind == "update" -> Look(Ico.Download, "info", "")
            kind == "system" -> Look(Ico.Settings, "muted", "")
            else -> Look(Ico.Info, "info", "")
        }
    }
}

/**
 * One event: icon, title, sender and time; tapped, the detail and — only then, on request — the
 * SMS itself unfold. A password SMS has no text stored to unfold.
 */
@Composable
fun EventRow(e: JSONObject, expanded: Boolean, onClick: () -> Unit) {
    val look = EventLook.of(e)
    val tone = Tone.of(look.tone)
    val from = e.optString("from").takeIf { it.isNotEmpty() }
    val detail = e.optString("verdict").takeIf { it.isNotEmpty() } ?: e.optString("detail").takeIf { it.isNotEmpty() }
    val body = e.optString("body").takeIf { it.isNotEmpty() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .semantics { stateDescription = if (expanded) "باز" else "بسته" }
            .heightIn(min = 48.dp).padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(look.icon, tone, 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Persian.bidi(e.optString("title")), style = Type.bodyStrong, color = Brand.Ink, modifier = Modifier.weight(1f), maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    Text(Persian.clock(e.optLong("at")), style = Type.supporting, color = Brand.Muted)
                }
                val sub = listOfNotNull(from?.let { Persian.sender(it) }, look.state.takeIf { it.isNotEmpty() }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = Type.supporting, color = Brand.Muted)
                if (detail != null) {
                    Text(Persian.bidi(Persian.digits(detail)), style = Type.supporting, color = if (look.tone == "muted") Brand.Muted else tone.fg, maxLines = if (expanded) 10 else 2, overflow = TextOverflow.Ellipsis)
                }
                if (expanded && body != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Persian.digits(body), style = Type.supporting.copy(textDirection = TextDirection.ContentOrRtl), color = Brand.Ink,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.Canvas).padding(10.dp),
                    )
                }
                if (expanded) {
                    Text(Persian.date(e.optLong("at")) + "، " + Persian.clock(e.optLong("at")), style = Type.badge, color = Brand.Muted, modifier = Modifier.padding(top = 4.dp))
                    // A bank format the bot cannot read yet is fixed from exactly this text: one tap
                    // shares it with Jirjirak's support — only when the seller asks, never on its own.
                    if (body != null && unreadable(e)) {
                        val context = LocalContext.current
                        LinkButton("ارسال این پیامک برای پشتیبانی جیرجیرک", { shareForSupport(context, e.optString("from"), body) }, icon = Ico.External)
                    }
                }
            }
        }
    }
}

/** The bot said it could not read the amount: a new bank format, worth sending to support. */
fun unreadable(e: JSONObject): Boolean =
    e.optString("kind") == "sms" && e.optString("verdict").contains("نتوانست")

/** Telegram's share sheet with the SMS, so the seller only picks @JirjirakPremiumBot (or any chat). */
fun shareForSupport(context: android.content.Context, from: String, body: String) {
    val text = "پیامک بانک که ربات جیرجیرک نتوانست بخواند (برای پشتیبانی):\nفرستنده: $from\n\n$body"
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    val telegram = android.content.Intent(send).setPackage("org.telegram.messenger")
    val chooser = android.content.Intent.createChooser(send, "ارسال برای پشتیبانی").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(telegram.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
        runCatching { context.startActivity(chooser) }
    }
}

/** Telegram if it is installed, the web otherwise. */
fun openTelegram(context: android.content.Context, username: String) {
    val tg = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=$username")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(tg) }.isFailure) {
        ir.jirjirak.sms.Updates.open(context, "https://t.me/$username")
    }
}

fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Android's own notification settings for this app (8+), or its app page. */
fun openNotificationSettings(context: android.content.Context) {
    val ok = android.os.Build.VERSION.SDK_INT >= 26 && runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!ok) openAppSettings(context)
}

@Composable
fun VersionLine() {
    Text(
        "جیرجیرک پیامک · نسخهٔ " + Persian.sender(ir.jirjirak.sms.BuildConfig.VERSION_NAME),
        style = Type.supporting, color = Brand.Muted, modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
