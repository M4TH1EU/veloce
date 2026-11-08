@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
internal fun paletteColors(palette: AppPalette, dark: Boolean): ColorScheme {
    if (palette == AppPalette.DYNAMIC) return if (dark) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
    return customPaletteColors(palette, dark)
}

private fun customPaletteColors(palette: AppPalette, dark: Boolean): ColorScheme {
    val accent = palette.swatch()
    val neutral = lerp(accent, Color(0xFF808080), .88f)
    fun surface(light: Long, night: Long) = lerp(Color(if (dark) night else light), neutral, .045f)
    val strongAccent = when (palette) {
        AppPalette.SAGE -> Color(0xFF426137)
        AppPalette.OCEAN -> Color(0xFF315F7E)
        AppPalette.EMBER -> Color(0xFF875132)
        else -> Color(0xFF485361)
    }
    val tertiary = when (palette) {
        AppPalette.SAGE -> if (dark) Color(0xFFA0D0CE) else Color(0xFF286A68)
        AppPalette.OCEAN -> if (dark) Color(0xFFD0BFE7) else Color(0xFF695380)
        AppPalette.EMBER -> if (dark) Color(0xFFD6CD96) else Color(0xFF69612F)
        else -> if (dark) Color(0xFFC6C2EA) else Color(0xFF5E5B7D)
    }
    val primary = if (dark) accent else strongAccent
    val onSurface = if (dark) Color(0xFFE6E2E0) else Color(0xFF1C1B1B)
    val container = if (dark) lerp(strongAccent, Color.Black, .25f) else lerp(accent, Color.White, .36f)
    val onAccent = if (dark) lerp(strongAccent, Color.Black, .6f) else Color.White
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    return colors.copy(
        primary = primary, onPrimary = onAccent,
        primaryContainer = container, onPrimaryContainer = if (dark) lerp(accent, Color.White, .25f) else lerp(strongAccent, Color.Black, .65f),
        secondary = lerp(primary, onSurface, .25f), onSecondary = onAccent,
        secondaryContainer = if (dark) lerp(container, neutral, .15f) else lerp(accent, Color.White, .58f),
        onSecondaryContainer = if (dark) lerp(accent, Color.White, .4f) else lerp(strongAccent, Color.Black, .6f),
        tertiary = tertiary, onTertiary = if (dark) lerp(tertiary, Color.Black, .8f) else Color.White,
        tertiaryContainer = lerp(tertiary, if (dark) Color.Black else Color.White, if (dark) .7f else .8f),
        onTertiaryContainer = if (dark) lerp(tertiary, Color.White, .3f) else lerp(tertiary, Color.Black, .6f),
        background = surface(0xFFF9F9F9, 0xFF111111), onBackground = onSurface,
        surface = surface(0xFFF9F9F9, 0xFF111111), onSurface = onSurface,
        surfaceDim = surface(0xFFDADADA, 0xFF111111), surfaceBright = surface(0xFFF9F9F9, 0xFF383838),
        surfaceContainerLowest = surface(0xFFFFFFFF, 0xFF0C0C0C), surfaceContainerLow = surface(0xFFF3F3F3, 0xFF1B1B1B),
        surfaceContainer = surface(0xFFEEEEEE, 0xFF202020), surfaceContainerHigh = surface(0xFFE8E8E8, 0xFF2A2A2A),
        surfaceContainerHighest = surface(0xFFE2E2E2, 0xFF353535),
        surfaceVariant = surface(0xFFE2E2E2, 0xFF353535), onSurfaceVariant = if (dark) Color(0xFFC3C6CF) else Color(0xFF44474F),
        outline = if (dark) Color(0xFF8D9199) else Color(0xFF74777F),
        outlineVariant = if (dark) Color(0xFF44474F) else Color(0xFFC4C7CF), surfaceTint = primary,
        inverseSurface = if (dark) Color(0xFFE5E2DF) else Color(0xFF303033),
        inverseOnSurface = if (dark) Color(0xFF252528) else Color(0xFFF3F2F0),
        inversePrimary = if (dark) strongAccent else accent,
        primaryFixed = lerp(accent, Color.White, .25f), primaryFixedDim = accent,
        onPrimaryFixed = lerp(strongAccent, Color.Black, .65f), onPrimaryFixedVariant = strongAccent,
        secondaryFixed = lerp(accent, Color.White, .5f), secondaryFixedDim = lerp(accent, Color.White, .15f),
        onSecondaryFixed = lerp(strongAccent, Color.Black, .65f), onSecondaryFixedVariant = strongAccent,
        tertiaryFixed = if (dark) lerp(tertiary, Color.White, .25f) else lerp(tertiary, Color.White, .8f),
        tertiaryFixedDim = if (dark) tertiary else lerp(tertiary, Color.White, .6f),
        onTertiaryFixed = if (dark) lerp(tertiary, Color.Black, .85f) else lerp(tertiary, Color.Black, .6f),
        onTertiaryFixedVariant = if (dark) lerp(tertiary, Color.Black, .7f) else tertiary,
    )
}

private val phoneTypography = Typography().let { base -> base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = .5.sp),
) }

private val veloceShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp))

@Composable
fun VeloceTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    CompositionLocalProvider(LocalAppSettings provides settings) {
        MaterialExpressiveTheme(colorScheme = paletteColors(settings.palette, dark), typography = phoneTypography,
            shapes = veloceShapes) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}

private val carTypography = Typography().let { base -> base.copy(
    headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = base.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
    labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .5.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
) }

internal fun carColors(base: ColorScheme): ColorScheme {
    val ink = Color(0xFFF1F2F5)
    val raised = Color(0xFF24262B)
    return base.copy(
        background = Color(0xFF0D0E11), onBackground = ink, surface = Color(0xFF111216), onSurface = ink,
        surfaceDim = Color(0xFF0D0E11), surfaceBright = Color(0xFF34373E),
        surfaceContainerLowest = Color(0xFF08090C), surfaceContainerLow = Color(0xFF15171B),
        surfaceContainer = Color(0xFF1B1D22), surfaceContainerHigh = raised, surfaceContainerHighest = Color(0xFF30333A),
        surfaceVariant = raised, onSurfaceVariant = Color(0xFFBEC2CC),
        outline = Color(0xFF777D89), outlineVariant = Color(0xFF383C45),
        primaryContainer = lerp(raised, base.primary, .12f), onPrimaryContainer = ink,
        secondaryContainer = lerp(raised, base.primary, .08f), onSecondaryContainer = ink,
    )
}

@Composable
internal fun CarTheme(settings: AppSettings = LocalAppSettings.current, content: @Composable () -> Unit) {
    val colors = carColors(paletteColors(settings.palette, dark = true))
    MaterialExpressiveTheme(colorScheme = colors, typography = carTypography, shapes = veloceShapes) {
        CompositionLocalProvider(LocalAppSettings provides settings, LocalContentColor provides colors.onSurface, content = content)
    }
}
