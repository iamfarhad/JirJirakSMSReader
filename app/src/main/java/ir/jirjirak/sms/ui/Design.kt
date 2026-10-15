package ir.jirjirak.sms.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Jirjirak's design system. Every screen is built from these, so a card, a badge or a status
 * colour looks and behaves the same wherever it appears — the mockups' fintech look comes from
 * consistency far more than from any single component.
 */

/** A state's colours: foreground and its soft background. */
data class Tone(val fg: Color, val bg: Color) {
    companion object {
        val good @Composable get() = Tone(Brand.Good, Brand.GoodSoft)
        val warn @Composable get() = Tone(Brand.Warn, Brand.WarnSoft)
        val bad @Composable get() = Tone(Brand.Bad, Brand.BadSoft)
        val info @Composable get() = Tone(Brand.Blue, Brand.BlueSoft)
        val muted @Composable get() = Tone(Brand.Muted, Brand.Canvas)

        @Composable
        fun of(name: String): Tone = when (name) {
            "good" -> good
            "warn" -> warn
            "bad" -> bad
            "muted" -> muted
            else -> info
        }
    }
}

/** A plain card on the canvas: soft shadow, large corners. */
@Composable
fun AppCard(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Brand.Surface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x10000000), spotColor = Color(0x14000000)),
    ) {
        Column(Modifier.padding(padding.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** A section's title row: a title, an optional count badge, and an optional «مشاهده همه». */
@Composable
fun SectionHeader(title: String, count: Int? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(CircleShape).background(Brand.Bad).padding(horizontal = 8.dp, vertical = 1.dp)) {
                Text(Persian.number(count), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action, color = Brand.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, tint = Brand.Blue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A round soft icon chip. */
@Composable
fun IconChip(icon: ImageVector, tone: Tone, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(tone.bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tone.fg, modifier = Modifier.size((size * 0.5).dp))
    }
}

/** «✓ تأیید شده», «⏱ نیازمند بررسی»… */
@Composable
fun StatusBadge(text: String, tone: Tone, icon: ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(tone.bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tone.fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tone.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The top of the dashboard: one sentence for the whole system, coloured by its state, with the
 * shield breathing while all is well — the thing a seller should understand in a second.
 */
@Composable
fun HeroStatus(title: String, text: String, tone: Tone, icon: ImageVector, alive: Boolean) {
    val pulse by rememberInfiniteTransition(label = "hero").animateFloat(
        initialValue = 0.92f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "scale",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(tone.bg, Brand.Surface)))
            .border(1.dp, tone.fg.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tone.fg))
                    Spacer(Modifier.width(8.dp))
                    Text(title, color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Text(text, color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            val scale = if (alive) pulse else 1f
            Box(
                Modifier.size((64 * scale).dp).clip(RoundedCornerShape(20.dp)).background(tone.fg.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tone.fg, modifier = Modifier.size(34.dp))
            }
        }
    }
}

/** One figure: label, a big number, a unit, and its change against the previous period. */
@Composable
fun MetricCard(
    label: String, value: String, unit: String, icon: ImageVector, tone: Tone,
    modifier: Modifier = Modifier, delta: String? = null, deltaGood: Boolean = true,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Brand.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.shadow(6.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x10000000), spotColor = Color(0x12000000)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(icon, tone, 30)
                Spacer(Modifier.width(6.dp))
                Text(label, color = Brand.Muted, fontSize = 11.sp, maxLines = 2, lineHeight = 14.sp)
            }
            Text(value, color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(unit, color = Brand.Muted, fontSize = 11.sp)
            if (delta != null) {
                Text(delta, color = if (deltaGood) Brand.Good else Brand.Bad, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A row in «اقدامات موردنیاز» or «فعالیت‌های اخیر»: icon, title, a line under it, a time. */
@Composable
fun ActionRow(icon: ImageVector, tone: Tone, title: String, subtitle: String?, trailing: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
    ) {
        IconChip(icon, tone, 38)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Brand.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
            if (subtitle != null) Text(subtitle, color = Brand.Muted, fontSize = 12.sp, maxLines = 2, lineHeight = 17.sp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(trailing, color = Brand.Muted, fontSize = 12.sp)
        }
        if (onClick != null) Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, tint = Brand.Muted, modifier = Modifier.size(18.dp))
    }
}

/** One tile of «بررسی سلامت سیستم». */
@Composable
fun HealthTile(icon: ImageVector, label: String, state: String, ok: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tone = if (ok) Tone.good else Tone.warn
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.Canvas)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconChip(icon, tone, 34)
        Text(label, color = Brand.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tone.fg))
            Spacer(Modifier.width(4.dp))
            Text(state, color = tone.fg, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** The four-up filter of the receipts centre. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface).border(1.dp, Brand.Line, RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) Brand.GreenSoft else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Brand.GreenDark else Brand.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** A labelled bar: what a signal could earn and what it did. */
@Composable
fun ScoreBar(label: String, sub: String?, earned: Int, weight: Int, ok: Boolean) {
    val fraction by animateFloatAsState(if (weight > 0) earned.toFloat() / weight else 0f, tween(600), label = "bar")
    val tone = if (ok) Tone.good else Tone.warn
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Brand.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Brand.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brand.Line)) {
                Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(CircleShape).background(tone.fg))
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(tone.bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(Persian.number(earned) + "/" + Persian.number(weight), color = tone.fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A dot on a vertical line, a title, a time. */
@Composable
fun TimelineRow(title: String, time: String, tone: Tone, last: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
            Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(tone.fg))
            if (!last) Box(Modifier.width(2.dp).height(34.dp).background(Brand.Line))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Brand.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(time, color = Brand.Muted, fontSize = 12.sp)
    }
}

/** Nothing to show — said kindly, not left blank. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconChip(icon, Tone.good, 52)
        Text(title, color = Brand.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (text != null) Text(text, color = Brand.Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

/** A request failed: what happened in plain words, and «دوباره». */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.BadSoft).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Bad, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("دوباره")
        }
    }
}

/** A grey placeholder that breathes while a request is out. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, height: Int = 18) {
    val alpha by rememberInfiniteTransition(label = "sk").animateFloat(
        initialValue = 0.35f, targetValue = 0.8f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a",
    )
    Box(modifier.height(height.dp).clip(RoundedCornerShape(8.dp)).background(Brand.Line.copy(alpha = alpha)))
}

/** A daily line chart, drawn by hand: no chart library for one line. */
@Composable
fun LineChart(values: List<Float>, color: Color, modifier: Modifier = Modifier, second: List<Float>? = null, secondColor: Color = Color.Gray) {
    val max = (values + (second ?: emptyList())).maxOrNull()?.takeIf { it > 0f } ?: 1f
    val grid = Brand.Line
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val w = size.width
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        fun line(vs: List<Float>, c: Color) {
            if (vs.size < 2) return
            val path = Path()
            vs.forEachIndexed { i, v ->
                // Right to left: the newest day sits where an RTL reader starts.
                val x = w - w * i / (vs.size - 1)
                val y = h - (v / max) * (h - 8f) - 4f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, c, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
        second?.let { line(it, secondColor) }
        line(values, color)
    }
}

/** A donut of shares, with a caption in the middle. */
@Composable
fun Donut(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, center: @Composable () -> Unit) {
    val total = parts.sumOf { it.first.toDouble() }.toFloat().takeIf { it > 0f }
    val track = Brand.Line
    Box(modifier.size(130.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 26f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            if (total == null) {
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            parts.forEach { (v, c) ->
                val sweep = 360f * v / total
                if (sweep > 0f) drawArc(c, start, sweep - 1.5f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}

/** A thin rounded progress bar with a label and a value. */
@Composable
fun ProgressRow(label: String, value: String, fraction: Float, color: Color) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Brand.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = Brand.Muted, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brand.Line)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(color))
        }
    }
}

/** A collapsible block: the summary always, the detail on a tap. */
@Composable
fun Expandable(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = expanded) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}

/** «۱۲٪+ نسبت به دیروز» — or nothing when the previous period had nothing to compare with. */
fun delta(now: Number, before: Number, period: String = "دیروز"): String? {
    val b = before.toDouble()
    val n = now.toDouble()
    if (b <= 0.0) return null
    val pct = ((n - b) * 100 / b).toInt()
    return (if (pct >= 0) "+" else "−") + Persian.number(kotlin.math.abs(pct)) + "٪ نسبت به " + period
}
