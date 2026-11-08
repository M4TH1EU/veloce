package ch.broillet.veloce.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R
import ch.broillet.veloce.apps.CarApp

@Composable
internal fun CarAppPages(apps: List<CarApp>, open: (CarApp) -> Unit, exit: () -> Unit, pager: PagerState,
    pageCountChanged: (Int) -> Unit, modifier: Modifier) {
    Column(modifier) {
        if (apps.isEmpty()) Text(stringResource(R.string.no_apps), Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val labelLines = if (maxHeight < 260.dp) 1 else 2
            val labelHeight = with(LocalDensity.current) { (MaterialTheme.typography.titleMedium.lineHeight * labelLines).toDp() }
            val columns = ((maxWidth + 12.dp) / 136.dp).toInt().coerceAtLeast(1)
            val rows = ((maxHeight + 12.dp) / (labelHeight + 64.dp)).toInt().coerceIn(1, if (maxHeight >= 480.dp) 3 else 2)
            val cellHeight = (maxHeight - 12.dp * (rows - 1)) / rows
            val iconSize = minOf(96.dp, cellHeight - labelHeight - 8.dp).coerceAtLeast(32.dp)
            val capacity = columns * rows
            val count = (apps.size + capacity) / capacity
            SideEffect { pageCountChanged(count) }
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 24.dp) { page ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(rows) { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(columns) { column ->
                                val index = page * capacity + row * columns + column
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    val app = apps.getOrNull(index)
                                    if (app != null) AppPageItem(app.label, { open(app) }, iconSize, labelLines) {
                                        PackageIcon(app.component.packageName, Modifier.fillMaxSize(), unmasked = true)
                                    }
                                    else if (index == apps.size) AppPageItem(stringResource(R.string.return_to_car), exit, iconSize, labelLines) {
                                        CarGlyph(CarSymbol.Car, Modifier.size(iconSize * .5f).align(Alignment.Center),
                                            MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LauncherPageIndicator(pager: PagerState) {
    if (pager.pageCount <= 1) return
    val label = stringResource(R.string.launcher_page, pager.currentPage + 1, pager.pageCount)
    val start = (pager.currentPage - 3).coerceIn(0, (pager.pageCount - 7).coerceAtLeast(0))
    Row(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(minOf(pager.pageCount, 7)) { dot ->
            val selected = start + dot == pager.currentPage
            Box(Modifier.size(if (selected) 18.dp else 6.dp, 6.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), CircleShape))
        }
    }
}

@Composable
private fun AppPageItem(label: String, open: () -> Unit, iconSize: Dp, labelLines: Int, icon: @Composable BoxScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().onFocusChanged { focused = it.hasFocus }
        .clickable(interactions, indication = null, role = Role.Button, onClick = open),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        GlassSurface(Modifier.size(iconSize), CircleShape, selected = focused, interactionSource = interactions,
            containerColor = MaterialTheme.colorScheme.primaryContainer, content = icon)
        Text(label, maxLines = labelLines, minLines = labelLines, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}
