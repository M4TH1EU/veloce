package ch.broillet.veloce.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AlbumSceneTheme(bitmap: Bitmap?, dense: Boolean, content: @Composable () -> Unit) {
    val fallback = MaterialTheme.colorScheme.primary
    val extracted by produceState(fallback, bitmap, fallback) {
        value = if (bitmap == null) fallback else withContext(Dispatchers.Default) {
            runCatching {
                artworkAccent(bitmap) ?: fallback
            }.getOrDefault(fallback)
        }
    }
    val accent by animateColorAsState(extracted, tween(450), label = "Album scene")
    val surface = MaterialTheme.colorScheme.surface
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent, secondary = accent,
        surface = if (bitmap != null) lerp(surface, accent, .06f) else surface)) {
        CompositionLocalProvider(LocalGlassOpacity provides if (dense) .78f else .48f, content = content)
    }
}

internal fun artworkAccent(bitmap: Bitmap): Color? {
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    return try {
        val swatches = Palette.from(readable).resizeBitmapArea(4_096).maximumColorCount(24).clearFilters().generate().swatches
        val population = swatches.sumOf { it.population }.takeIf { it > 0 } ?: return null
        fun average(channel: (Int) -> Int) = (swatches.sumOf { channel(it.rgb).toLong() * it.population } / population).toInt()
        val red = average(android.graphics.Color::red)
        val green = average(android.graphics.Color::green)
        val blue = average(android.graphics.Color::blue)
        val hsl = FloatArray(3)
        ColorUtils.RGBToHSL(red, green, blue, hsl)
        val chroma = (maxOf(red, green, blue) - minOf(red, green, blue)) / 255f
        hsl[1] = minOf(hsl[1], chroma * 2, .55f)
        hsl[2] = .80f
        Color(ColorUtils.HSLToColor(hsl))
    } finally { if (readable !== bitmap) readable.recycle() }
}
