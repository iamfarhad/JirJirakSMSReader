package ir.jirjirak.sms.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.jirjirak.sms.Store
import org.json.JSONObject

/**
 * Read this first in any composable that shows something from the Store: the composable is then
 * redrawn whenever the Store changes — from a button, a receiver or a background job alike.
 */
@Composable
fun watchStore(): Int = Store.revision.intValue

/** A white card on the soft canvas: a coloured icon chip and a title, then its content. */
@Composable
fun Section(
    title: String,
    icon: ImageVector,
    tint: Color = Brand.Green,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, MaterialTheme.shapes.large, ambientColor = Color(0x14000000), spotColor = Color(0x1A16A34A)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Brand.Ink, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun Notice(text: String, background: Color, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(background).padding(12.dp),
    )
}

/** A small rounded label: a state, a count, a name. */
@Composable
fun Pill(text: String, background: Color, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(CircleShape).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** One figure with its label, three to a row on the status card. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, tint: Color = Brand.Ink) {
    Column(
        modifier.clip(MaterialTheme.shapes.small).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, color = Brand.Muted, fontSize = 11.sp)
        Text(value, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A tappable row with a title, a line under it and a chevron — settings and links. */
@Composable
fun LinkRow(title: String, subtitle: String? = null, icon: ImageVector? = null, tint: Color = Brand.Green, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Brand.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Brand.Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, tint = Brand.Muted)
    }
}

/** One line of the setup list: done, still to do, or a problem the seller has to go and fix. */
enum class Step { Done, Todo, Problem }

@Composable
fun CheckRow(state: Step, index: Int, title: String, hint: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        when (state) {
            Step.Done -> Box(Modifier.size(30.dp).clip(CircleShape).background(Brand.GoodSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Brand.Good, modifier = Modifier.size(18.dp))
            }
            // A numbered step, not a warning: nothing is wrong yet, it is simply next.
            Step.Todo -> Box(Modifier.size(30.dp).clip(CircleShape).border(2.dp, Brand.Line, CircleShape), contentAlignment = Alignment.Center) {
                Text(Persian.number(index), color = Brand.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Step.Problem -> Box(Modifier.size(30.dp).clip(CircleShape).background(Brand.WarnSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Brand.Warn, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Brand.Ink, fontSize = 14.sp)
            Text(hint, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (state != Step.Done && action != null) {
            TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** How an event is drawn: its icon, colour and the word for its state. */
object EventLook {
    data class Look(val icon: ImageVector, val tint: Color, val soft: Color, val state: String)

    fun of(e: JSONObject): Look {
        val kind = e.optString("kind")
        val status = e.optString("status")
        return when {
            kind == "sms" && status == "sent" -> Look(Icons.Filled.Check, Brand.Good, Brand.GoodSoft, "فرستاده شد")
            kind == "sms" && status == "queued" -> Look(Icons.Filled.Refresh, Brand.Warn, Brand.WarnSoft, "در صف")
            kind == "sms" && status == "retry" -> Look(Icons.Filled.Refresh, Brand.Warn, Brand.WarnSoft, "تلاش دوباره")
            kind == "sms" -> Look(Icons.Filled.Close, Brand.Bad, Brand.BadSoft, "رد شد")
            kind == "ignored" && e.optString("title").contains("رمز") -> Look(Icons.Filled.Lock, Brand.Muted, Brand.Canvas, "روی گوشی ماند")
            kind == "ignored" -> Look(Icons.Filled.Close, Brand.Muted, Brand.Canvas, "فرستاده نشد")
            status == "warn" -> Look(Icons.Filled.Warning, Brand.Warn, Brand.WarnSoft, "هشدار")
            status == "ok" -> Look(Icons.Filled.Check, Brand.Good, Brand.GoodSoft, "")
            kind == "system" -> Look(Icons.Filled.Settings, Brand.Muted, Brand.Canvas, "")
            else -> Look(Icons.Filled.Info, Brand.Green, Brand.GreenSoft, "")
        }
    }
}

/** One event: icon, title, sender and time; tapped, the detail and the SMS itself unfold. */
@Composable
fun EventRow(e: JSONObject, expanded: Boolean, onClick: () -> Unit) {
    val look = EventLook.of(e)
    val from = e.optString("from").takeIf { it.isNotEmpty() }
    val detail = e.optString("verdict").takeIf { it.isNotEmpty() } ?: e.optString("detail").takeIf { it.isNotEmpty() }
    val body = e.optString("body").takeIf { it.isNotEmpty() }
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(look.soft), contentAlignment = Alignment.Center) {
                Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.optString("title"), color = Brand.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    Text(Persian.clock(e.optLong("at")), color = Brand.Muted, fontSize = 12.sp)
                }
                val sub = listOfNotNull(from?.let { Persian.sender(it) }, look.state.takeIf { it.isNotEmpty() }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = Brand.Muted, fontSize = 12.sp)
                if (detail != null) {
                    Text(detail, color = if (look.tint == Brand.Muted) Brand.Muted else look.tint, fontSize = 12.sp, lineHeight = 18.sp, maxLines = if (expanded) 10 else 2, overflow = TextOverflow.Ellipsis)
                }
                if (expanded && body != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Persian.digits(body),
                        color = Brand.Ink, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Brand.Canvas).padding(10.dp),
                    )
                }
                if (expanded) {
                    Text(Persian.date(e.optLong("at")) + "، " + Persian.clock(e.optLong("at")), color = Brand.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (body != null || (detail?.length ?: 0) > 60) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Brand.Line, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** An outlined, full-width, destructive-looking button border. */
fun dangerBorder() = BorderStroke(1.dp, Brand.Bad.copy(alpha = 0.4f))
