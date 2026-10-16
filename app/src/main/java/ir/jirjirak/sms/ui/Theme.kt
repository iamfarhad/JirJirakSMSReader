package ir.jirjirak.sms.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.jirjirak.sms.R
import ir.jirjirak.sms.Store

/**
 * Jirjirak's semantic colours (design tokens v2): a cricket's green on a calm, slightly green canvas
 * — or on a deep green-black at night. Every colour reads [dark], which is Compose state, so a
 * screen redraws the moment the phone (or the seller's choice) switches.
 *
 * Every foreground token reads at 4.5:1 or better on its own soft background and on the surface, in
 * both themes. The night primary is a LIGHT green: text on it is the deep [OnPrimary], never white.
 */
object Brand {
    var dark by mutableStateOf(false)

    private fun pick(light: Long, night: Long) = Color(if (dark) night else light)

    val Canvas get() = pick(0xFFF5F8F7, 0xFF101F1A)
    /** Cards, the tab bar, dialogs. */
    val Surface get() = pick(0xFFFFFFFF, 0xFF182D25)
    val Ink get() = pick(0xFF16332D, 0xFFEDF7F2)
    val Muted get() = pick(0xFF586F69, 0xFFB0C4BA)
    /** Dividers and card borders. */
    val Line get() = pick(0xFFDCE7E3, 0xFF304C3E)
    /** Outlines of controls (secondary buttons, unselected chips). */
    val Control get() = pick(0xFF7C938A, 0xFF829E8E)
    val Green get() = pick(0xFF067552, 0xFF79E1B2)
    val OnPrimary get() = pick(0xFFFFFFFF, 0xFF062B1D)
    val GreenSoft get() = pick(0xFFE5F5ED, 0xFF173C2B)
    /** Kept for older call sites: the same primary. */
    val GreenDark get() = Green
    val Emerald get() = Green
    val Good get() = pick(0xFF067552, 0xFF79E1B2)
    val GoodSoft get() = pick(0xFFE5F5ED, 0xFF173C2B)
    val Warn get() = pick(0xFF8A4B08, 0xFFFFD18A)
    val WarnSoft get() = pick(0xFFFFF5E5, 0xFF3D2E18)
    val Bad get() = pick(0xFFB42318, 0xFFFFB4A9)
    val BadSoft get() = pick(0xFFFFF0ED, 0xFF43261F)
    val Blue get() = pick(0xFF1E56A0, 0xFFACCAFF)
    val BlueSoft get() = pick(0xFFEEF5FF, 0xFF203449)
    val Disabled get() = pick(0xFFE7ECE9, 0xFF31473C)
    val DisabledInk get() = pick(0xFF60736B, 0xFFBED0C5)
}

/**
 * Vazirmatn in its three real weights — 400, 500 and 700. There is no 600 file, and a «SemiBold»
 * would be faked by the renderer; [FontWeight.SemiBold] is not used anywhere.
 */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Vazirmatn, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp,
)

/** The type scale of the design, by role. Sizes are sp: they grow with the phone's font scale. */
object Type {
    val display = style(23, 35, FontWeight.Bold)
    val page = style(18, 27, FontWeight.Bold)
    val section = style(15, 26, FontWeight.Bold)
    val body = style(14, 24)
    val bodyStrong = style(14, 24, FontWeight.Medium)
    val button = style(14, 23, FontWeight.Medium)
    val supporting = style(12, 22)
    val badge = style(11, 18, FontWeight.Medium)
    val amount = style(26, 39, FontWeight.Bold)
}

private val JrsTypography = Typography(
    displayLarge = style(32, 48, FontWeight.Bold),
    displayMedium = style(28, 42, FontWeight.Bold),
    displaySmall = Type.amount,
    headlineLarge = Type.amount,
    headlineMedium = Type.display,
    headlineSmall = style(20, 30, FontWeight.Bold),
    titleLarge = Type.page,
    titleMedium = Type.section,
    titleSmall = Type.bodyStrong,
    bodyLarge = Type.body,
    bodyMedium = Type.body,
    bodySmall = Type.supporting,
    labelLarge = Type.button,
    labelMedium = style(12, 20, FontWeight.Medium),
    labelSmall = Type.badge,
)

@Composable
fun JirjirakTheme(content: @Composable () -> Unit) {
    // The seller's choice in «بیشتر ← ظاهر», or the phone's own setting by default.
    watchStore()
    val choice = Store(androidx.compose.ui.platform.LocalContext.current).theme
    Brand.dark = when (choice) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val dark = Brand.dark
    // The bars follow the app's choice, not only the phone's: a dark app under a pale status bar
    // looks broken.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val bar = if (dark) 0xFF101F1A.toInt() else 0xFFF5F8F7.toInt()
        @Suppress("DEPRECATION")
        window.statusBarColor = bar
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (dark) 0xFF182D25.toInt() else 0xFFFFFFFF.toInt()
        window.decorView.setBackgroundColor(bar)
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = Brand.Green,
            onPrimary = Brand.OnPrimary,
            primaryContainer = Brand.GreenSoft,
            onPrimaryContainer = Brand.Green,
            secondary = Brand.Green,
            onSecondary = Brand.OnPrimary,
            background = Brand.Canvas,
            onBackground = Brand.Ink,
            surface = Brand.Surface,
            surfaceContainer = Brand.Surface,
            surfaceContainerLow = Brand.Surface,
            surfaceContainerHigh = Brand.Surface,
            surfaceContainerHighest = Brand.Canvas,
            onSurface = Brand.Ink,
            onSurfaceVariant = Brand.Muted,
            outline = Brand.Control,
            outlineVariant = Brand.Line,
            error = Brand.Bad,
            errorContainer = Brand.BadSoft,
        ),
        // Vazirmatn for EVERY text style — buttons, dialogs, fields and labels take theirs from here.
        typography = JrsTypography,
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(26.dp),
        ),
    ) {
        // The whole app is Persian: right to left whatever the phone's language is.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            // The font only: a colour in the style would beat every button's own content colour.
            ProvideTextStyle(Type.body, content = content)
        }
    }
}
