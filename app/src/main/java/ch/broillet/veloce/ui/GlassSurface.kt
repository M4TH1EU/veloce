package ch.broillet.veloce.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalGlassOpacity = staticCompositionLocalOf { .64f }

@Composable
internal fun Modifier.carEntrance(destination: Any?): Modifier {
    val progress = remember(destination) { Animatable(.75f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
    }
}

@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    opacity: Float = LocalGlassOpacity.current,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = if (onClick != null || interactionSource != null) interactions.collectIsPressedAsState().value else false
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .8f, stiffness = 650f), label = "Glass press")
    val backing = animateFloatAsState(opacity, tween(200), label = "Glass backing")
    val highlight = animateFloatAsState(if (selected) .23f else .10f, tween(180), label = "Glass selection")
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val edge = remember(focused, selected, accent) {
        Brush.linearGradient(if (focused) listOf(accent, accent) else listOf(
            Color.White.copy(alpha = if (selected) .5f else .28f),
            Color.White.copy(alpha = .04f),
            Color.White.copy(alpha = .14f),
        ))
    }
    val decorated = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 2.dp else 1.dp, edge, shape)
    val body: @Composable () -> Unit = {
        Box(Modifier.clip(shape).drawWithCache {
            val fill = Brush.linearGradient(listOf(accent.copy(alpha = highlight.value),
                Color.White.copy(alpha = .025f), Color.White.copy(alpha = .07f)))
            onDrawBehind {
                drawRect(surface.copy(alpha = backing.value))
                drawRect(fill)
            }
        }, content = content)
    }
    if (onClick == null) Surface(decorated, shape, Color.Transparent, content = body)
    else Surface(onClick = onClick, modifier = decorated, enabled = enabled, shape = shape,
        interactionSource = interactions, color = Color.Transparent, content = body)
}

@Composable
internal fun GlassIconButton(
    symbol: CarSymbol,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 64.dp,
    iconSize: Dp = 30.dp,
    opacity: Float = LocalGlassOpacity.current,
) {
    GlassSurface(modifier.size(size).semantics {
        contentDescription = label
        role = Role.Button
        this.selected = selected
    }, RoundedCornerShape(size * .38f), selected, onClick, enabled, opacity = opacity) {
        CarGlyph(symbol, Modifier.size(iconSize).align(Alignment.Center),
            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .28f))
    }
}

@Composable
internal fun Modifier.cabinBackground(): Modifier {
    val colors = MaterialTheme.colorScheme
    return drawWithCache {
        val base = Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainerLowest))
        val light = Brush.radialGradient(listOf(colors.primary.copy(alpha = .055f), Color.Transparent),
            Offset(size.width * .45f, 0f), maxOf(size.width, size.height) * .75f)
        onDrawBehind {
            drawRect(base)
            drawRect(light)
        }
    }
}
