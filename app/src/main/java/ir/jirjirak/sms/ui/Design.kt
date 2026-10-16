package ir.jirjirak.sms.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.R
import kotlinx.coroutines.delay

/*
 * Jirjirak's design system (handoff v2). Every screen is built from these, so a card, a badge or a
 * status looks and behaves the same wherever it appears. A state is never said by colour alone:
 * every badge, hero and notice carries its words.
 */

/**
 * The 34 icons of the handoff, by meaning. 24-unit, 1.8 stroke; tinted from the theme. [mirrored]
 * flips an arrow drawn for left-to-right; «back» is not one — its file is autoMirrored already.
 */
enum class Ico(@param:DrawableRes val res: Int, val mirrored: Boolean = false) {
    Alert(R.drawable.jrs_ic_alert), Back(R.drawable.jrs_ic_back), Bank(R.drawable.jrs_ic_bank),
    Battery(R.drawable.jrs_ic_battery), Bell(R.drawable.jrs_ic_bell), Chart(R.drawable.jrs_ic_chart),
    Check(R.drawable.jrs_ic_check), Chevron(R.drawable.jrs_ic_chevron, mirrored = true), Clock(R.drawable.jrs_ic_clock),
    Close(R.drawable.jrs_ic_close), Copy(R.drawable.jrs_ic_copy), Download(R.drawable.jrs_ic_download),
    External(R.drawable.jrs_ic_external), Eye(R.drawable.jrs_ic_eye), Filter(R.drawable.jrs_ic_filter),
    Home(R.drawable.jrs_ic_home), Image(R.drawable.jrs_ic_image), Inbox(R.drawable.jrs_ic_inbox),
    Info(R.drawable.jrs_ic_info), Layers(R.drawable.jrs_ic_layers), Lock(R.drawable.jrs_ic_lock),
    Mail(R.drawable.jrs_ic_mail), More(R.drawable.jrs_ic_more), Phone(R.drawable.jrs_ic_phone),
    Receipt(R.drawable.jrs_ic_receipt), Refresh(R.drawable.jrs_ic_refresh), Search(R.drawable.jrs_ic_search),
    Settings(R.drawable.jrs_ic_settings), Shield(R.drawable.jrs_ic_shield), Sparkles(R.drawable.jrs_ic_sparkles),
    Sun(R.drawable.jrs_ic_sun), Sync(R.drawable.jrs_ic_sync), User(R.drawable.jrs_ic_user), Wifi(R.drawable.jrs_ic_wifi),
}

/** The 26 illustrations. Decorative: the copy beside each one says the state. */
enum class Art(@param:DrawableRes val res: Int) {
    Connection(R.drawable.jrs_ill_connection), Error(R.drawable.jrs_ill_error), Offline(R.drawable.jrs_ill_offline),
    Permissions(R.drawable.jrs_ill_permissions), SetupComplete(R.drawable.jrs_ill_setup_complete), Bank(R.drawable.jrs_ill_bank),
    Dashboard(R.drawable.jrs_ill_dashboard), Security(R.drawable.jrs_ill_security), Queue(R.drawable.jrs_ill_queue),
    Health(R.drawable.jrs_ill_health), HealthWarning(R.drawable.jrs_ill_health_warning), ReceiptsEmpty(R.drawable.jrs_ill_receipts_empty),
    Receipts(R.drawable.jrs_ill_receipts), Success(R.drawable.jrs_ill_success), ImagePlaceholder(R.drawable.jrs_ill_image_placeholder),
    Learning(R.drawable.jrs_ill_learning), Report(R.drawable.jrs_ill_report), Notifications(R.drawable.jrs_ill_notifications),
    Update(R.drawable.jrs_ill_update), Settings(R.drawable.jrs_ill_settings), Widget(R.drawable.jrs_ill_widget),
    Subscription(R.drawable.jrs_ill_subscription), OtpPrivate(R.drawable.jrs_ill_otp_private), Storage(R.drawable.jrs_ill_storage),
    Reconcile(R.drawable.jrs_ill_reconcile), General(R.drawable.jrs_ill_general),
}

/**
 * An icon, tinted from the theme. Arrows point the RTL way: «back» to the right, a row's «forward»
 * chevron to the left. Bank marks and numbers are never mirrored.
 */
@Composable
fun JIcon(icon: Ico, tint: Color, size: Int = 22, label: String? = null, modifier: Modifier = Modifier) {
    Icon(
        painterResource(icon.res), contentDescription = label, tint = tint,
        modifier = modifier.size(size.dp).then(if (icon.mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier),
    )
}

@Composable
fun ArtImage(art: Art, modifier: Modifier = Modifier) {
    Image(painterResource(art.res), contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}

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
            "muted", "neutral" -> muted
            else -> info
        }
    }
}

// ── page structure ──────────────────────────────────────────────────────

/**
 * The top of a state: what is going on in one sentence, in its tone, with its illustration.
 * [filled] is the dashboard's «all is well» — the one hero drawn in solid primary.
 */
@Composable
fun Hero(title: String, detail: String?, tone: Tone, art: Art? = null, icon: Ico? = null, filled: Boolean = false, eyebrow: String? = null) {
    val bg = if (filled) Brand.Green else tone.bg
    val titleColor = if (filled) Brand.OnPrimary else tone.fg
    val textColor = if (filled) Brand.OnPrimary else Brand.Ink
    // At a large font the words need the whole width: the decorative picture steps aside rather
    // than the title breaking one word per line beside it.
    val roomy = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).padding(18.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (eyebrow != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(titleColor))
                    Spacer(Modifier.width(6.dp))
                    Text(eyebrow, style = Type.supporting, color = textColor)
                }
            }
            Text(title, style = Type.display, color = titleColor, modifier = Modifier.semantics { heading() })
            if (detail != null) Text(detail, style = Type.body, color = textColor)
        }
        when {
            !roomy -> {}
            art != null -> {
                Spacer(Modifier.width(10.dp))
                ArtImage(art, Modifier.size(84.dp))
            }
            icon != null -> {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(if (filled) Brand.OnPrimary.copy(alpha = 0.14f) else Brand.Surface),
                    contentAlignment = Alignment.Center,
                ) { JIcon(icon, titleColor, 28) }
            }
        }
    }
}

/** A plain card on the canvas: a hairline border, large corners, no shadow. */
@Composable
fun AppCard(modifier: Modifier = Modifier, padding: Int = 16, title: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brand.Surface)
            .border(1.dp, Brand.Line, RoundedCornerShape(20.dp)).padding(padding.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (title != null) Text(title, style = Type.section, color = Brand.Ink, modifier = Modifier.weight(1f).semantics { heading() })
                else Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        content()
    }
}

/** A section's title row: a title, an optional count badge, and an optional action. */
@Composable
fun SectionHeader(title: String, count: Int? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = Type.section, color = Brand.Ink, modifier = Modifier.semantics { heading() })
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(CircleShape).background(Brand.Bad).padding(horizontal = 8.dp)) {
                Text(Persian.number(count), color = Brand.Surface, style = Type.badge)
            }
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onAction).heightIn(min = 48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, color = Brand.Green, style = Type.button)
                JIcon(Ico.Chevron, Brand.Green, 18)
            }
        }
    }
}

/** A sub-screen's title: back (pointing right, as RTL reads), the title and the tab it sits in. */
@Composable
fun ScreenHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.page, color = Brand.Ink, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = Type.supporting, color = Brand.Muted)
        }
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Surface).clickable(onClick = onBack)
                .semantics { contentDescription = "بازگشت" },
            contentAlignment = Alignment.Center,
        ) { JIcon(Ico.Back, Brand.Ink, 22) }
    }
}

// ── rows ────────────────────────────────────────────────────────────────

/** A square soft chip holding an icon. */
@Composable
fun IconTile(icon: Ico, tone: Tone, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(tone.bg), contentAlignment = Alignment.Center) {
        JIcon(icon, tone.fg, (size * 0.55f).toInt())
    }
}

/** «فعال», «در صف»… a short state in words, in its tone. */
@Composable
fun StatusBadge(text: String, tone: Tone, icon: Ico? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(tone.bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            JIcon(icon, tone.fg, 14)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tone.fg, style = Type.badge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One row of a list: icon, title, a line under it, a badge and — when it leads somewhere — a chevron.
 * The whole row is one touch target and one spoken node; it never holds a button of its own.
 */
@Composable
fun ListRow(
    icon: Ico?, tone: Tone, title: String, detail: String? = null,
    badge: String? = null, badgeTone: Tone = tone, onClick: (() -> Unit)? = null, enabled: Boolean = true,
    detailStyle: TextStyle = Type.supporting, merge: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else if (merge) Modifier.semantics(mergeDescendants = true) {} else Modifier)
            .padding(vertical = 6.dp),
    ) {
        if (icon != null) {
            IconTile(icon, tone, 40)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = Brand.Ink)
            if (detail != null) Text(detail, style = detailStyle, color = Brand.Muted)
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            StatusBadge(badge, badgeTone)
        }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            JIcon(Ico.Chevron, Brand.Green, 18)
        }
    }
}

/** Rows with hairlines between them, inside a card. */
@Composable
fun Rows(vararg rows: @Composable () -> Unit) {
    rows.forEachIndexed { i, r ->
        if (i > 0) HorizontalDivider(color = Brand.Line)
        r()
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Brand.Line)

/** A row chosen like a radio button: the whole row is the one target, the radio only shows it. */
@Composable
fun SelectRow(selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onSelect),
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected, onClick = null,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Brand.Green, unselectedColor = Brand.Control),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A soft panel that tells something: a title, a line, an icon by tone. */
@Composable
fun Notice(title: String, detail: String? = null, tone: Tone = Tone.info, icon: Ico? = null) {
    val ico = icon ?: when (tone) {
        Tone.good -> Ico.Check
        Tone.warn, Tone.bad -> Ico.Alert
        else -> Ico.Info
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tone.bg).padding(14.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        JIcon(ico, tone.fg, 20, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = tone.fg)
            if (detail != null) Text(detail, style = Type.supporting, color = Brand.Ink)
        }
    }
}

/** Numbered steps: what to do, in order. */
@Composable
fun Steps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Brand.GreenSoft), contentAlignment = Alignment.Center) {
                    Text(Persian.number(i + 1), color = Brand.Green, style = Type.badge)
                }
                Spacer(Modifier.width(10.dp))
                Text(s, style = Type.body, color = Brand.Ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ── controls ────────────────────────────────────────────────────────────

/**
 * The main action. Busy: disabled (a second tap cannot send it again) and says so in its label —
 * never a made-up percentage.
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: Ico? = null) {
    Button(
        onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.Green, contentColor = Brand.OnPrimary,
            disabledContainerColor = Brand.Disabled, disabledContentColor = Brand.DisabledInk,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.DisabledInk)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            JIcon(icon, if (enabled) Brand.OnPrimary else Brand.DisabledInk, 20)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.button)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: Ico? = null) {
    OutlinedButton(
        onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        border = BorderStroke(1.dp, if (enabled && !busy) Brand.Control else Brand.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Green, disabledContentColor = Brand.DisabledInk),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.DisabledInk)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            JIcon(icon, Brand.Green, 20)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.button)
    }
}

/** A destructive action: red, outlined on a soft red — never the screen's main button colour. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    OutlinedButton(
        onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        border = BorderStroke(1.dp, if (enabled) Brand.Bad.copy(alpha = 0.6f) else Brand.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Brand.BadSoft, contentColor = Brand.Bad, disabledContentColor = Brand.DisabledInk),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.DisabledInk)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.button)
    }
}

/** A quiet text action, 48dp tall. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, color: Color = Brand.Green, enabled: Boolean = true, modifier: Modifier = Modifier, icon: Ico? = null) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        if (icon != null) {
            JIcon(icon, if (enabled) color else Brand.DisabledInk, 18)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.button, color = if (enabled) color else Brand.DisabledInk)
    }
}

/** The process-wide «hide what is sensitive» signal: bumped when the app goes to the background. */
object Privacy {
    val epoch = mutableIntStateOf(0)
}

/**
 * A field for a code that can approve payments or carry a shop's secret: masked by default, no
 * autocorrect and no keyboard learning; shown only on an explicit tap, for 5 seconds, and hidden
 * again the moment the app leaves the screen.
 */
@Composable
fun SecureField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false, enabled: Boolean = true, onDone: (() -> Unit)? = null) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) { if (revealed) { delay(5_000); revealed = false } }
    LaunchedEffect(Privacy.epoch.intValue) { revealed = false }
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, isError = isError,
        label = { Text(label, style = Type.supporting) },
        singleLine = true,
        textStyle = Type.body.copy(textDirection = TextDirection.Ltr),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }, enabled = value.isNotEmpty(), modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                JIcon(Ico.Eye, if (revealed) Brand.Green else Brand.Muted, 22, label = if (revealed) "پنهان کردن کد" else "نمایش موقت کد")
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Brand.Green, unfocusedBorderColor = Brand.Control, errorBorderColor = Brand.Bad,
            focusedLabelColor = Brand.Green, cursorColor = Brand.Green,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A plain field (a sender id, a search). */
@Composable
fun PlainField(value: String, onChange: (String) -> Unit, label: String, ltr: Boolean = false, isError: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, style = Type.supporting) }, singleLine = true, isError = isError,
        textStyle = Type.body.copy(textDirection = if (ltr) TextDirection.Ltr else TextDirection.ContentOrRtl),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Green, unfocusedBorderColor = Brand.Control, focusedLabelColor = Brand.Green, cursorColor = Brand.Green),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A choice chip, 48dp tall: a filter, a unit, a number in an SMS. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Brand.GreenSoft else Brand.Surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Brand.Green else Brand.Control, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = if (selected) Type.button else Type.body, color = if (selected) Brand.Green else Brand.Ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * The receipts centre's filter, the report's ranges: chips side by side that wrap — at a large font
 * the labels stay whole and the row reflows instead of shrinking the text.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = androidx.compose.ui.platform.LocalDensity.current.fontScale.let { if (it > 1.6f) 1 else if (it > 1.3f) 2 else options.size },
    ) {
        options.forEachIndexed { i, label -> Chip(label, i == selected, { onSelect(i) }, Modifier.weight(1f)) }
    }
}

/** A confirmation: what happens, with the amount and the target in the text. Busy keeps it open and locked. */
@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    cancel: String = "انصراف", danger: Boolean = false, busy: Boolean = false, confirmEnabled: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Brand.Surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text(title, style = Type.page, color = Brand.Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, style = Type.body, color = Brand.Ink)
                extra?.invoke(this)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (danger) DangerButton(confirm, onConfirm, busy = busy, enabled = confirmEnabled)
                else PrimaryButton(confirm, onConfirm, busy = busy, enabled = confirmEnabled)
                SecondaryButton(cancel, onDismiss, enabled = !busy)
            }
        },
    )
}

// ── figures ─────────────────────────────────────────────────────────────

/** One figure: a label, a big number, its unit. «—» when unknown — never a made-up zero. */
@Composable
fun MetricTile(label: String, value: String?, unit: String, modifier: Modifier = Modifier, tone: Tone? = null, note: String? = null) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brand.Surface).border(1.dp, Brand.Line, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = Type.supporting, color = Brand.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            if (value == null) Skeleton(Modifier.width(56.dp), height = 30)
            else Text(value, style = Type.amount, color = tone?.fg ?: Brand.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text(unit, style = Type.supporting, color = Brand.Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (note != null) Text(note, style = Type.supporting, color = Brand.Muted)
    }
}

/** One tile of the health strip: icon, what, and its state in words. */
@Composable
fun HealthTile(icon: Ico, label: String, state: String, ok: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tone = if (ok) Tone.good else Tone.warn
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .heightIn(min = 48.dp).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        JIcon(icon, Brand.Green, 22)
        Text(label, style = Type.supporting, color = Brand.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(state, style = Type.badge, color = tone.fg, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A labelled bar: what a signal could earn and what it did. */
@Composable
fun ScoreBar(label: String, sub: String?, earned: Int, weight: Int, ok: Boolean) {
    val fraction by animateFloatAsState(if (weight > 0) earned.toFloat() / weight else 0f, tween(600), label = "bar")
    val tone = if (ok) Tone.good else Tone.warn
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.bodyStrong, color = Brand.Ink)
            if (sub != null) Text(sub, style = Type.supporting, color = Brand.Muted)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brand.Line)) {
                Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(CircleShape).background(tone.fg))
            }
        }
        Spacer(Modifier.width(12.dp))
        StatusBadge(Persian.number(earned) + " از " + Persian.number(weight), tone)
    }
}

/** A dot on a vertical line, a title, a time. */
@Composable
fun TimelineRow(title: String, time: String, tone: Tone, last: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) {}) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(tone.fg))
            if (!last) Box(Modifier.width(2.dp).height(34.dp).background(Brand.Line))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = Type.body, color = Brand.Ink, modifier = Modifier.weight(1f))
        Text(time, style = Type.supporting, color = Brand.Muted)
    }
}

/** Nothing to show — said kindly, with its illustration; never confused with a failed request. */
@Composable
fun EmptyState(title: String, text: String?, art: Art? = null, icon: Ico = Ico.Check) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (art != null) ArtImage(art, Modifier.size(132.dp)) else IconTile(icon, Tone.good, 52)
        Text(title, style = Type.section, color = Brand.Ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (text != null) Text(text, style = Type.body, color = Brand.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A request failed: what happened in plain words, and «تلاش دوباره». */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.BadSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            JIcon(Ico.Alert, Brand.Bad, 20, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Text(Persian.digits(message), style = Type.body, color = Brand.Bad, modifier = Modifier.weight(1f))
        }
        SecondaryButton("تلاش دوباره", onRetry, icon = Ico.Refresh)
    }
}

/** A placeholder that breathes while a request is out. It stands for «not known yet», not for zero. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, height: Int = 18) {
    val alpha by rememberInfiniteTransition(label = "sk").animateFloat(
        initialValue = 0.35f, targetValue = 0.8f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a",
    )
    Box(modifier.height(height.dp).clip(RoundedCornerShape(10.dp)).background(Brand.Line.copy(alpha = alpha)))
}

/** Work in progress without a number: an indeterminate line and what is being waited for. */
@Composable
fun Waiting(text: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Brand.Green, trackColor = Brand.Line)
        Text(text, style = Type.supporting, color = Brand.Muted)
    }
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

/** A thin rounded bar with a label and a value: a share of the period's real total. */
@Composable
fun ProgressRow(label: String, value: String, fraction: Float, color: Color) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = Type.body, color = Brand.Ink, modifier = Modifier.weight(1f))
            Text(value, style = Type.supporting, color = Brand.Muted)
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
