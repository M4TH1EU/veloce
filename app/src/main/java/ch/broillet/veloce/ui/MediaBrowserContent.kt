package ch.broillet.veloce.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R
import ch.broillet.veloce.media.MediaEntry
import ch.broillet.veloce.media.MediaLibrary
import ch.broillet.veloce.media.MediaPresentation
import kotlinx.coroutines.launch
import java.util.Locale

private val shuffleActionTitles = setOf("shuffle", "shuffle play", "play shuffled", "lecture aléatoire", "lecture aleatoire", "lire en aléatoire")

@Composable
fun MediaLibraryContent(library: MediaLibrary, showPlayer: () -> Unit, modifier: Modifier) {
    val page = library.page
    val open: (MediaEntry) -> Unit = { entry ->
        if (entry.browsable) library.open(entry) else if (library.play(entry)) showPlayer()
    }
    val play: (MediaEntry) -> Unit = { if (library.play(it)) showPlayer() }
    Box(modifier) {
        when {
            page.error != null -> Column(Modifier.align(Alignment.Center).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(page.error, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.media_browse_denied), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CarTextButton(stringResource(R.string.now_playing), showPlayer, symbol = CarSymbol.Music)
                }
            }
            page.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (page.loading) CircularProgressIndicator() else Text(stringResource(R.string.media_empty))
            }
            else -> {
                val shuffleAction = page.entries.firstOrNull()?.takeIf { entry ->
                    page.folder?.page == 0 && page.folder.query == null && !entry.browsable && entry.playable &&
                        entry.title.trim().lowercase(Locale.ROOT) in shuffleActionTitles
                }
                val grid = rememberLazyGridState()
                val scope = rememberCoroutineScope()
                val spacing = with(LocalDensity.current) { 8.dp.toPx() }
                fun scroll(forward: Boolean) {
                    scope.launch {
                        val info = grid.layoutInfo
                        val rows = info.visibleItemsInfo.distinctBy { it.row }
                        val first = rows.firstOrNull() ?: return@launch
                        val stride = rows.getOrNull(1)?.let { it.offset.y - first.offset.y }?.takeIf { it > 0 }?.toFloat()
                            ?: (first.size.height + spacing)
                        val viewport = info.viewportEndOffset - info.viewportStartOffset
                        val step = (((viewport + spacing) / stride).toInt() - 1).coerceAtLeast(1)
                        val distance = (if (forward) step else -step) * stride - grid.firstVisibleItemScrollOffset
                        grid.animateScrollBy(distance, tween(250))
                    }
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyVerticalGrid(GridCells.Adaptive(136.dp), Modifier.weight(1f).fillMaxHeight(), state = grid,
                        contentPadding = PaddingValues(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(page.entries, key = { index, entry -> "$index:${entry.id}" }, span = { index, entry ->
                            GridItemSpan(if (index == 0 && shuffleAction != null || entry.presentation == MediaPresentation.LIST) maxLineSpan else 1)
                        }) { index, entry ->
                            if (index == 0 && shuffleAction != null) CarTextButton(entry.title, { play(entry) },
                                Modifier.fillMaxWidth().height(80.dp), symbol = CarSymbol.Shuffle, selected = true)
                            else if (entry.presentation == MediaPresentation.LIST) MediaTrackRow(entry,
                                entry.item.mediaMetadata.trackNumber?.takeIf { it > 0 },
                                library.nowPlaying?.let { it.playing && it.mediaId == entry.id } == true,
                                { open(entry) }, { play(entry) })
                            else MediaCover(entry, { open(entry) }, { play(entry) })
                        }
                    }
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                        GlassIconButton(CarSymbol.ScrollUp, stringResource(R.string.scroll_up), { scroll(false) },
                            enabled = grid.canScrollBackward && !grid.isScrollInProgress, size = 64.dp)
                        GlassIconButton(CarSymbol.ScrollDown, stringResource(R.string.scroll_down), { scroll(true) },
                            enabled = grid.canScrollForward && !grid.isScrollInProgress, size = 64.dp)
                    }
                }
            }
        }
        if (page.loading && page.entries.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
        library.playbackError?.let { Text(it, Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.error, maxLines = 1) }
    }
}

@Composable
private fun MediaCover(entry: MediaEntry, open: () -> Unit, play: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).onFocusChanged { focused = it.hasFocus }
        .clickable(interactions, indication = null, enabled = entry.browsable || entry.playable, role = Role.Button, onClick = open),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassSurface(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(24.dp), selected = focused,
            interactionSource = interactions) {
            Artwork(entry.artwork, Modifier.align(Alignment.Center).fillMaxSize(if (entry.presentation == MediaPresentation.CATEGORY) .58f else 1f))
            if (entry.browsable && entry.playable) GlassIconButton(CarSymbol.Play, stringResource(R.string.play), play,
                Modifier.align(Alignment.BottomEnd).padding(8.dp), size = 64.dp, iconSize = 28.dp, prominent = true)
        }
        Text(entry.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (entry.subtitle.isNotBlank()) Text(entry.subtitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MediaTrackRow(entry: MediaEntry, number: Int?, playing: Boolean, open: () -> Unit, play: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().heightIn(min = 80.dp), RoundedCornerShape(20.dp), selected = playing,
        onClick = open, enabled = entry.browsable || entry.playable) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (number != null || playing) Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (playing) CarGlyph(CarSymbol.Music, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                else Text(number.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Artwork(entry.artwork, Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.browsable && entry.playable) GlassIconButton(CarSymbol.Play, stringResource(R.string.play), play,
                size = 68.dp, prominent = true)
            else if (entry.browsable) CarGlyph(CarSymbol.ChevronRight, Modifier.size(28.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
