package ir.jirjirak.sms.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Jirjirak's own palette: the bot's violet on a light, calm surface. */
object Brand {
    val Violet = Color(0xFF6D5EFC)
    val VioletDark = Color(0xFF4B3FD1)
    val VioletSoft = Color(0xFFEDEBFF)
    val Canvas = Color(0xFFF6F5FF)
    val Ink = Color(0xFF1B1A2E)
    val Muted = Color(0xFF6B6A80)
    val Good = Color(0xFF1F9D57)
    val GoodSoft = Color(0xFFE5F6EC)
    val Warn = Color(0xFFB7791F)
    val WarnSoft = Color(0xFFFFF4DE)
    val Bad = Color(0xFFD64545)
    val BadSoft = Color(0xFFFDE8E8)
}

@Composable
fun JirjirakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand.Violet,
            onPrimary = Color.White,
            primaryContainer = Brand.VioletSoft,
            onPrimaryContainer = Brand.VioletDark,
            background = Brand.Canvas,
            onBackground = Brand.Ink,
            surface = Color.White,
            onSurface = Brand.Ink,
            onSurfaceVariant = Brand.Muted,
            error = Brand.Bad,
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        ),
    ) {
        // The whole app is Persian: right to left whatever the phone's language is.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
