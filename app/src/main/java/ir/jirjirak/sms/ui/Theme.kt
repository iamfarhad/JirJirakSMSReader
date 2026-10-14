package ir.jirjirak.sms.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
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

/** Jirjirak's own palette: a cricket's green on a light, calm surface. */
object Brand {
    val Green = Color(0xFF16A34A)
    val GreenDark = Color(0xFF166534)
    val GreenSoft = Color(0xFFDCFCE7)
    val Canvas = Color(0xFFF2FAF5)
    val Ink = Color(0xFF14231A)
    val Muted = Color(0xFF5F7166)
    val Good = Color(0xFF1F9D57)
    val GoodSoft = Color(0xFFE5F6EC)
    val Warn = Color(0xFFB7791F)
    val WarnSoft = Color(0xFFFFF4DE)
    val Bad = Color(0xFFD64545)
    val BadSoft = Color(0xFFFDE8E8)
}

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_medium, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

@Composable
fun JirjirakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand.Green,
            onPrimary = Color.White,
            primaryContainer = Brand.GreenSoft,
            onPrimaryContainer = Brand.GreenDark,
            background = Brand.Canvas,
            onBackground = Brand.Ink,
            surface = Color.White,
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
