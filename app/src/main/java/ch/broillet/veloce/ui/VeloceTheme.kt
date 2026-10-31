package ch.broillet.veloce.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val colors = darkColorScheme(
    primary = Color(0xFFC8F2A4),
    onPrimary = Color(0xFF183807),
    primaryContainer = Color(0xFF293D23),
    onPrimaryContainer = Color(0xFFD9F7C6),
    secondary = Color(0xFFB9CCC0),
    background = Color(0xFF101713),
    onBackground = Color(0xFFE6EDE5),
    surface = Color(0xFF101713),
    onSurface = Color(0xFFE6EDE5),
    surfaceContainer = Color(0xFF1A231D),
    surfaceContainerHigh = Color(0xFF242E27),
    onSurfaceVariant = Color(0xFFB6C2B5),
    outlineVariant = Color(0xFF3A473B),
)

@Composable
fun VeloceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

private val carTypography = Typography().let { base -> base.copy(
    headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-.3).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 1.5.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
) }

@Composable
internal fun CarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors.copy(
        primary = Color(0xFFF3F5F6),
        onPrimary = Color(0xFF151515),
        primaryContainer = Color(0xFF383838),
        onPrimaryContainer = Color(0xFFF3F5F6),
        secondary = Color(0xFFE0E0E0),
        secondaryContainer = Color(0xFF383838),
        onSecondaryContainer = Color(0xFFF3F5F6),
        background = Color(0xFF101010),
        surface = Color(0xFF242424),
        surfaceContainer = Color(0xFF292929),
        surfaceContainerHigh = Color(0xFF363636),
        onSurface = Color(0xFFF3F5F6),
        onBackground = Color(0xFFF3F5F6),
        onSurfaceVariant = Color(0xFFC2C2C2),
        outlineVariant = Color(0xFF4A4A4A),
    ), typography = carTypography, shapes = Shapes(
        small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(32.dp),
    )) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}
