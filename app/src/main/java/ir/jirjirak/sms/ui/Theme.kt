package ir.jirjirak.sms.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.jirjirak.sms.R

/**
 * Jirjirak's own palette: a cricket's green on a light, calm surface — or on a deep green-black at
 * night, following the phone. Every colour reads [dark], which is Compose state, so a screen
 * redraws the moment the phone switches; many sellers leave this app open on a desk overnight.
 */
object Brand {
    var dark by mutableStateOf(false)

    private fun pick(light: Long, night: Long) = Color(if (dark) night else light)

    val Green get() = pick(0xFF16A34A, 0xFF22C55E)
    val GreenDark get() = pick(0xFF166534, 0xFF86EFAC)
    val GreenSoft get() = pick(0xFFDCFCE7, 0xFF14351F)
    val Canvas get() = pick(0xFFF2FAF5, 0xFF0B1410)
    /** Cards, the tab bar, dialogs. */
    val Surface get() = pick(0xFFFFFFFF, 0xFF13201A)
    /** Dividers and the ring of a step not yet done. */
    val Line get() = pick(0xFFE3EDE7, 0xFF24342B)
    val Ink get() = pick(0xFF14231A, 0xFFE6F1EA)
    val Muted get() = pick(0xFF5F7166, 0xFF94A89C)
    val Good get() = pick(0xFF1F9D57, 0xFF4ADE80)
    val GoodSoft get() = pick(0xFFE5F6EC, 0xFF143022)
    val Warn get() = pick(0xFFB7791F, 0xFFF5B94A)
    val WarnSoft get() = pick(0xFFFFF4DE, 0xFF33280F)
    val Bad get() = pick(0xFFD64545, 0xFFF87171)
    val BadSoft get() = pick(0xFFFDE8E8, 0xFF3A1717)
}

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_medium, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

@Composable
fun JirjirakTheme(content: @Composable () -> Unit) {
    Brand.dark = isSystemInDarkTheme()
    val base = if (Brand.dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = Brand.Green,
            // White on the night green is 2.3:1 — unreadable in sunlight; a deep green is 7:1.
            onPrimary = if (Brand.dark) Color(0xFF052E16) else Color.White,
            primaryContainer = Brand.GreenSoft,
            onPrimaryContainer = Brand.GreenDark,
            background = Brand.Canvas,
            onBackground = Brand.Ink,
            surface = Brand.Surface,
            surfaceContainerHigh = Brand.Surface,
            onSurface = Brand.Ink,
            onSurfaceVariant = Brand.Muted,
            error = Brand.Bad,
        ),
        // Vazirmatn (the current Vazir) for EVERY text style — buttons, dialogs and text fields take
        // theirs from here, not from the ProvideTextStyle below, and would otherwise fall back to
        // the system font.
        typography = Typography().let { t ->
            Typography(
                displayLarge = t.displayLarge.copy(fontFamily = Vazirmatn),
                displayMedium = t.displayMedium.copy(fontFamily = Vazirmatn),
                displaySmall = t.displaySmall.copy(fontFamily = Vazirmatn),
                headlineLarge = t.headlineLarge.copy(fontFamily = Vazirmatn),
                headlineMedium = t.headlineMedium.copy(fontFamily = Vazirmatn),
                headlineSmall = t.headlineSmall.copy(fontFamily = Vazirmatn),
                titleLarge = t.titleLarge.copy(fontFamily = Vazirmatn),
                titleMedium = t.titleMedium.copy(fontFamily = Vazirmatn),
                titleSmall = t.titleSmall.copy(fontFamily = Vazirmatn),
                bodyLarge = t.bodyLarge.copy(fontFamily = Vazirmatn),
                bodyMedium = t.bodyMedium.copy(fontFamily = Vazirmatn),
                bodySmall = t.bodySmall.copy(fontFamily = Vazirmatn),
                labelLarge = t.labelLarge.copy(fontFamily = Vazirmatn),
                labelMedium = t.labelMedium.copy(fontFamily = Vazirmatn),
                labelSmall = t.labelSmall.copy(fontFamily = Vazirmatn),
            )
        },
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
    ) {
        // The whole app is Persian: right to left whatever the phone's language is, in Vazirmatn —
        // the system font draws Persian too, but stiffly, and it is the first thing that reads as
        // «not designed».
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            // The font only: a colour here would override every button's own (white on green).
            ProvideTextStyle(TextStyle(fontFamily = Vazirmatn), content = content)
        }
    }
}
