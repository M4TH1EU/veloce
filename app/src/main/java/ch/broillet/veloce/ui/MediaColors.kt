package ch.broillet.veloce.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ArtworkColors(val accent: Color, val scrim: Float)
internal val LocalAlbumScrim = staticCompositionLocalOf { .5f }

@Composable
internal fun AlbumSceneTheme(bitmap: Bitmap?, dense: Boolean, content: @Composable () -> Unit) {
    val fallback = MaterialTheme.colorScheme.primary
    val default = ArtworkColors(fallback, .5f)
    val extracted by produceState(default, bitmap, fallback) {
        value = if (bitmap == null) default else withContext(Dispatchers.Default) {
            runCatching {
                artworkColors(bitmap) ?: default
            }.getOrDefault(default)
        }
    }
    val accent by animateColorAsState(extracted.accent, tween(450), label = "Album scene")
    val scrim by animateFloatAsState(extracted.scrim, tween(450), label = "Artwork contrast")
    val colors = MaterialTheme.colorScheme
    val scene = if (bitmap == null) colors else albumColors(colors, accent)
    MaterialTheme(colorScheme = scene) {
        CompositionLocalProvider(LocalGlassOpacity provides if (dense) .92f else .66f, LocalAlbumScrim provides scrim,
            LocalContentColor provides scene.onSurface, content = content)
    }
}

private fun albumColors(base: ColorScheme, accent: Color): ColorScheme {
    val neutral = carColors(base)
    return neutral.copy(
        primary = accent, onPrimary = lerp(Color.Black, accent, .12f),
        primaryContainer = lerp(neutral.surfaceContainerHigh, accent, .18f), onPrimaryContainer = neutral.onSurface,
        secondary = lerp(accent, Color.White, .12f), onSecondary = lerp(Color.Black, accent, .12f),
        secondaryContainer = lerp(neutral.surfaceContainerHigh, accent, .12f), onSecondaryContainer = neutral.onSurface,
        surface = lerp(neutral.surface, accent, .03f),
        surfaceContainerLow = lerp(neutral.surfaceContainerLow, accent, .04f),
        surfaceContainer = lerp(neutral.surfaceContainer, accent, .06f),
        surfaceContainerHigh = lerp(neutral.surfaceContainerHigh, accent, .08f),
        surfaceContainerHighest = lerp(neutral.surfaceContainerHighest, accent, .1f), surfaceTint = accent,
    )
}

internal fun artworkAccent(bitmap: Bitmap): Color? = artworkColors(bitmap)?.accent

private fun artworkColors(bitmap: Bitmap): ArtworkColors? {
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    return try {
        val swatches = Palette.from(readable).resizeBitmapArea(4_096).maximumColorCount(24).clearFilters().generate().swatches
        val population = swatches.sumOf { it.population }.takeIf { it > 0 } ?: return null
        val substantial = swatches.filter { it.population >= population * .08f }
        val colorful = swatches.filter { swatch ->
            val rgb = swatch.rgb
            val chroma = (maxOf(android.graphics.Color.red(rgb), android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb)) -
                minOf(android.graphics.Color.red(rgb), android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb))) / 255f
            chroma >= .08f && swatch.hsl[1] >= .12f
        }
        val representative = colorful.mapNotNull { candidate ->
            val family = colorful.sumOf { swatch ->
                val difference = kotlin.math.abs(candidate.hsl[0] - swatch.hsl[0])
                if (minOf(difference, 360f - difference) <= 30f) swatch.population else 0
            }
            if (family >= population * .08f) candidate to family * (.75f + .25f * candidate.hsl[1]) else null
        }.maxByOrNull { it.second }?.first ?: swatches.maxByOrNull { it.population } ?: return null
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(representative.rgb, hsl)
        hsl[1] = minOf(hsl[1], .7f)
        hsl[2] = .78f
        val brightness = substantial.maxOfOrNull { ColorUtils.calculateLuminance(it.rgb) } ?: 0.0
        ArtworkColors(Color(ColorUtils.HSLToColor(hsl)), when {
            brightness > .65 -> .55f
            brightness > .35 -> .42f
            else -> .26f
        })
    } finally { if (readable !== bitmap) readable.recycle() }
}
