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
    val extracted by produceState(fallback, bitmap) {
        value = if (bitmap == null) fallback else withContext(Dispatchers.Default) {
            runCatching {
                val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                try {
                    val palette = Palette.from(readable).resizeBitmapArea(4_096).maximumColorCount(12).clearFilters().generate()
                    val swatch = palette.lightVibrantSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
                    swatch?.hsl?.copyOf()?.let { hsl ->
                        hsl[1] = hsl[1].coerceAtMost(.65f)
                        hsl[2] = hsl[2].coerceIn(.76f, .86f)
                        Color(ColorUtils.HSLToColor(hsl))
                    } ?: fallback
                } finally { if (readable !== bitmap) readable.recycle() }
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
