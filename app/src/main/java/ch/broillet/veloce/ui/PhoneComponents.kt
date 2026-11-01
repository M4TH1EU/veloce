package ch.broillet.veloce.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R
import kotlinx.coroutines.delay
import java.util.Date

@Composable
internal fun PhoneHeader(title: String, description: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PhonePrimaryButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(.8f, 500f), label = "Action press")
    val corner by animateDpAsState(if (pressed) 20.dp else 30.dp, spring(.8f, 500f), label = "Action shape")
    Button(onClick, enabled = enabled, interactionSource = interactions, shape = RoundedCornerShape(corner),
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).graphicsLayer { scaleX = scale; scaleY = scale }, content = content)
}

@Composable
internal fun SectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun PhonePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(content = content)
    }
}

@Composable
internal fun SymbolTile(symbol: CarSymbol, modifier: Modifier = Modifier, prominent: Boolean = false) {
    Box(modifier.size(if (prominent) 64.dp else 44.dp)
        .background(if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            RoundedCornerShape(if (prominent) 24.dp else 16.dp)), contentAlignment = Alignment.Center) {
        CarGlyph(symbol, Modifier.size(if (prominent) 30.dp else 22.dp),
            if (prominent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsRow(title: String, description: String? = null, symbol: CarSymbol? = null,
    onClick: (() -> Unit)? = null, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (symbol != null) SymbolTile(symbol)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .5f))
                if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
            }
            if (trailing != null) trailing()
            else if (onClick != null) CarGlyph(CarSymbol.ChevronRight, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick != null) Surface(onClick, enabled = enabled, color = Color.Transparent, content = content)
    else content()
}

@Composable
internal fun PanelDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
}

@Composable
internal fun StatusLabel(label: String, active: Boolean = false, error: Boolean = false) {
    val color by animateColorAsState(when {
        error -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }, label = "Connection status")
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = .10f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
internal fun rememberClock(): State<Date> = produceState(Date()) {
    while (true) {
        value = Date()
        delay(60_000 - System.currentTimeMillis() % 60_000)
    }
}

@Composable
internal fun CabinIllustration(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val date by rememberClock()
    val is24Hour = settings.clockFormat.is24Hour(LocalContext.current)
    val label = stringResource(R.string.launcher_illustration)
    CarTheme(settings) {
        val colors = MaterialTheme.colorScheme
        Surface(modifier.fillMaxWidth().aspectRatio(1.9f).clearAndSetSemantics { contentDescription = label },
            shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .55f))) {
            Row(Modifier.cabinBackground().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassSurface(Modifier.width(44.dp).fillMaxHeight(), RoundedCornerShape(18.dp), opacity = .4f) {
                    Column(Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween) {
                        CarGlyph(CarSymbol.Veloce, Modifier.size(22.dp), colors.primary)
                        CarGlyph(CarSymbol.Home, Modifier.size(20.dp), colors.onSurfaceVariant)
                        Text(DateFormat.format(if (is24Hour) "HH:mm" else "h:mm", date).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val columns = listOf(0f, .23f, .58f, .84f, 1f)
                        val rows = listOf(0f, .24f, .58f, .84f, 1f)
                        val gutter = 6.dp.toPx()
                        for ((left, right) in columns.zipWithNext()) for ((top, bottom) in rows.zipWithNext()) {
                            val width = (right - left) * size.width - gutter * 2
                            val height = (bottom - top) * size.height - gutter * 2
                            if (width > 0 && height > 0) drawRoundRect(colors.primary.copy(alpha = .055f),
                                Offset(left * size.width + gutter, top * size.height + gutter), Size(width, height), CornerRadius(5.dp.toPx()))
                        }
                        val road = colors.onSurfaceVariant.copy(alpha = .12f)
                        for (x in columns.drop(1).dropLast(1)) {
                            drawLine(road, Offset(x * size.width, 0f), Offset(x * size.width, size.height), 10.dp.toPx())
                        }
                        for (y in rows.drop(1).dropLast(1)) {
                            drawLine(road, Offset(0f, y * size.height), Offset(size.width, y * size.height), 10.dp.toPx())
                        }
                        val start = Offset(size.width * .23f, size.height * .84f)
                        val destination = Offset(size.width * .84f, size.height * .24f)
                        val radius = minOf(size.width, size.height) * .055f
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            lineTo(start.x, size.height * .58f + radius)
                            quadraticTo(start.x, size.height * .58f, start.x + radius, size.height * .58f)
                            lineTo(size.width * .58f - radius, size.height * .58f)
                            quadraticTo(size.width * .58f, size.height * .58f, size.width * .58f, size.height * .58f - radius)
                            lineTo(size.width * .58f, destination.y + radius)
                            quadraticTo(size.width * .58f, destination.y, size.width * .58f + radius, destination.y)
                            lineTo(destination.x, destination.y)
                        }
                        drawPath(path, colors.primary.copy(alpha = .8f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawCircle(colors.surfaceContainer, 5.dp.toPx(), destination)
                        drawCircle(colors.primary, 5.dp.toPx(), destination, style = Stroke(2.dp.toPx()))
                        drawCircle(colors.primary.copy(alpha = .15f), 14.dp.toPx(), start)
                        drawCircle(colors.primary, 10.dp.toPx(), start)
                        val arrow = Path().apply {
                            val unit = 1.dp.toPx()
                            moveTo(start.x, start.y - 6 * unit)
                            lineTo(start.x + 4 * unit, start.y + 4 * unit)
                            lineTo(start.x, start.y + 2 * unit)
                            lineTo(start.x - 4 * unit, start.y + 4 * unit)
                            close()
                        }
                        drawPath(arrow, colors.onPrimary)
                    }
                }
                Column(Modifier.width(78.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassSurface(Modifier.weight(1f).fillMaxWidth(), RoundedCornerShape(20.dp)) {
                        CarGlyph(CarSymbol.Music, Modifier.align(Alignment.Center).size(30.dp), colors.primary)
                    }
                    GlassSurface(Modifier.fillMaxWidth().height(40.dp), RoundedCornerShape(16.dp)) {
                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                CarGlyph(CarSymbol.Previous, Modifier.size(23.dp), colors.onSurfaceVariant)
                            }
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                CarGlyph(CarSymbol.Pause, Modifier.size(20.dp), colors.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SetupProgress(step: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            val progress by animateFloatAsState(if (index <= step) 1f else .12f, spring(), label = "Setup progress")
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = progress)))
        }
    }
}
