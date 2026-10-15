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
import ir.jirjirak.sms.Store

/**
 * Jirjirak's own palette: a cricket's green on a light, calm surface — or on a deep green-black at
 * night, following the phone. Every colour reads [dark], which is Compose state, so a screen
 * redraws the moment the phone switches; many sellers leave this app open on a desk overnight.
 */
object Brand {
    var dark by mutableStateOf(false)

    private fun pick(light: Long, night: Long) = Color(if (dark) night else light)

    val Green get() = pick(0xFF059669, 0xFF34D399)
    val Emerald get() = pick(0xFF10B981, 0xFF10B981)
    val GreenDark get() = pick(0xFF047857, 0xFF6EE7B7)
    val GreenSoft get() = pick(0xFFE9FAF2, 0xFF0F2E24)
    val Canvas get() = pick(0xFFF7F9FC, 0xFF0B1220)
    /** Cards, the tab bar, dialogs. */
    val Surface get() = pick(0xFFFFFFFF, 0xFF131C2E)
    /** Dividers and the ring of a step not yet done. */
    val Line get() = pick(0xFFE8EDF3, 0xFF22304A)
    val Ink get() = pick(0xFF11243E, 0xFFE6EDF7)
    val Muted get() = pick(0xFF64748B, 0xFF94A3B8)
    val Good get() = pick(0xFF059669, 0xFF34D399)
    val GoodSoft get() = pick(0xFFE9FAF2, 0xFF0F2E24)
    val Warn get() = pick(0xFFD97706, 0xFFFBBF24)
    val WarnSoft get() = pick(0xFFFFF4E0, 0xFF33270C)
    val Bad get() = pick(0xFFEF4444, 0xFFF87171)
    val BadSoft get() = pick(0xFFFEECEC, 0xFF3A1717)
    val Blue get() = pick(0xFF2563EB, 0xFF60A5FA)
    val BlueSoft get() = pick(0xFFEAF1FE, 0xFF13233F)
}

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_medium, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
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
    val base = if (Brand.dark) darkColorScheme() else lightColorScheme()
    // The bars follow the app's choice, not only the phone's: a dark app under a pale status bar
    // looks broken.
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = Brand.dark
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val bar = if (dark) 0xFF0B1220.toInt() else 0xFFF7F9FC.toInt()
        @Suppress("DEPRECATION")
        window.statusBarColor = bar
        @Suppress("DEPRECATION")
        window.navigationBarColor = bar
        window.decorView.setBackgroundColor(bar)
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
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
