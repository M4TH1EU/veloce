package ch.broillet.veloce.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
