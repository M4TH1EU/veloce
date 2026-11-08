package ch.broillet.veloce.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

internal val LocalGlassOpacity = staticCompositionLocalOf { .76f }

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
    containerColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = if (onClick != null || interactionSource != null) interactions.collectIsPressedAsState().value else false
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = 1f, stiffness = 650f), label = "Surface press")
    val colors = MaterialTheme.colorScheme
    val fill by androidx.compose.animation.animateColorAsState(when {
        containerColor != Color.Unspecified -> containerColor
        selected -> colors.secondaryContainer
        else -> colors.surfaceContainerHigh
    }.copy(alpha = if (selected) 1f else opacity), tween(180), label = "Surface tone")
    val decorated = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier)
    val body: @Composable () -> Unit = {
        Box(Modifier.graphicsLayer { alpha = if (enabled) 1f else .38f }, content = content)
    }
    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    val edge = BorderStroke(.5.dp, colors.onSurface.copy(alpha = if (selected) .12f else .06f))
    if (onClick == null) Surface(decorated, shape, fill, contentColor = contentColor, border = edge, content = body)
    else Surface(onClick = onClick, modifier = decorated, enabled = enabled, shape = shape,
        interactionSource = interactions, color = fill, contentColor = contentColor, border = edge, content = body)
}

@Composable
internal fun GlassIconButton(
    symbol: CarSymbol,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 68.dp,
    iconSize: Dp = 30.dp,
    opacity: Float = LocalGlassOpacity.current,
    prominent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val corner by animateDpAsState(if (pressed) size * .25f else if (prominent && symbol == CarSymbol.Pause) size * .28f else size / 2,
        spring(1f, 650f), label = "Icon button shape")
    val shape = RoundedCornerShape(corner)
    val fill = when {
        prominent -> colors.primary
        selected -> colors.secondaryContainer
        else -> colors.surfaceContainerHigh.copy(alpha = opacity)
    }
    val ink = when {
        prominent -> colors.onPrimary
        selected -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    FilledIconButton(onClick, modifier.size(size).onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier).semantics {
        contentDescription = label
        role = Role.Button
        this.selected = selected
    }, enabled = enabled, shape = shape, interactionSource = interactions,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = fill, contentColor = ink)) {
        CarGlyph(symbol, Modifier.size(iconSize), LocalContentColor.current)
    }
}

@Composable
internal fun Modifier.cabinBackground(): Modifier {
    val colors = MaterialTheme.colorScheme
    return drawWithCache {
        val light = Brush.radialGradient(listOf(colors.primary.copy(alpha = .035f), Color.Transparent),
            Offset(size.width * .8f, 0f), maxOf(size.width, size.height) * .85f)
        val base = Brush.verticalGradient(listOf(colors.surfaceContainerLowest, colors.background))
        onDrawBehind {
            drawRect(base)
            drawRect(light)
        }
    }
}
