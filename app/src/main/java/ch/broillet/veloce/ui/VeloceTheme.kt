package ch.broillet.veloce.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.broillet.veloce.settings.AppPalette
import ch.broillet.veloce.settings.AppSettings
import ch.broillet.veloce.settings.ThemeMode

internal val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

internal fun AppPalette.swatch(): Color = when (this) {
    AppPalette.GRAPHITE -> Color(0xFFCBD0D8)
    AppPalette.DYNAMIC -> Color(0xFFD0BCFF)
    AppPalette.SAGE -> Color(0xFFC8DDB8)
    AppPalette.OCEAN -> Color(0xFFB2D4F0)
    AppPalette.EMBER -> Color(0xFFF2C3A7)
}

@Composable
private fun paletteColors(palette: AppPalette, dark: Boolean): ColorScheme {
    if (palette == AppPalette.DYNAMIC) return if (dark) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
    val accent = palette.swatch()
    val tint = if (palette == AppPalette.GRAPHITE) Color(0xFF808080) else accent
    val base = if (dark) Color(0xFF101010) else Color(0xFFFCFAF8)
    fun tone(amount: Float) = lerp(base, tint, amount)
    val primary = if (dark) accent else when (palette) {
        AppPalette.SAGE -> Color(0xFF426137)
        AppPalette.OCEAN -> Color(0xFF315F7E)
        AppPalette.EMBER -> Color(0xFF875132)
        else -> Color(0xFF485361)
    }
    val onSurface = if (dark) Color(0xFFF3F2F0) else Color(0xFF1C1C1E)
    val container = if (dark) tone(.23f) else lerp(accent, Color.White, .5f)
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    return colors.copy(
        primary = primary, onPrimary = if (dark) Color(0xFF171C20) else Color.White,
        primaryContainer = container, onPrimaryContainer = onSurface,
        secondary = primary, onSecondary = if (dark) Color(0xFF171C20) else Color.White,
        secondaryContainer = tone(if (dark) .20f else .22f), onSecondaryContainer = onSurface,
        tertiary = primary, onTertiary = if (dark) Color(0xFF171C20) else Color.White,
        tertiaryContainer = container, onTertiaryContainer = onSurface,
        background = tone(.025f), onBackground = onSurface,
        surface = tone(.025f), onSurface = onSurface,
        surfaceDim = tone(.04f), surfaceBright = tone(.26f),
        surfaceContainerLowest = base, surfaceContainerLow = tone(.08f),
        surfaceContainer = tone(.13f), surfaceContainerHigh = tone(.20f), surfaceContainerHighest = tone(.27f),
        surfaceVariant = tone(.20f), onSurfaceVariant = if (dark) Color(0xFFC1C3C7) else Color(0xFF56595E),
        outline = if (dark) Color(0xFF8B8D92) else Color(0xFF74777B),
        outlineVariant = tone(if (dark) .36f else .52f), surfaceTint = primary,
        inverseSurface = if (dark) Color(0xFFE5E2DF) else Color(0xFF303033),
        inverseOnSurface = if (dark) Color(0xFF252528) else Color(0xFFF3F2F0),
        inversePrimary = if (dark) Color(0xFF485361) else accent,
    )
}

private val phoneTypography = Typography().let { base -> base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1.3).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-.3).sp),
    bodyLarge = base.bodyLarge.copy(lineHeight = 25.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 1.4.sp),
) }

@Composable
fun VeloceTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    CompositionLocalProvider(LocalAppSettings provides settings) {
        MaterialTheme(colorScheme = paletteColors(settings.palette, dark), typography = phoneTypography,
            shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(32.dp), extraLarge = RoundedCornerShape(36.dp))) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
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
internal fun CarTheme(settings: AppSettings = LocalAppSettings.current, content: @Composable () -> Unit) {
    val colors = paletteColors(settings.palette, dark = true)
    MaterialTheme(colorScheme = colors.copy(surface = colors.surfaceContainerHigh), typography = carTypography,
        shapes = Shapes(small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(32.dp))) {
        CompositionLocalProvider(LocalAppSettings provides settings, LocalContentColor provides colors.onSurface, content = content)
    }
}
