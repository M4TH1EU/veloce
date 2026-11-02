package ch.broillet.veloce.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.broillet.veloce.R
import ch.broillet.veloce.media.MediaEntry
import ch.broillet.veloce.media.MediaLibrary
import ch.broillet.veloce.media.MediaPresentation

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
                val numbered = page.entries.all { !it.browsable && it.presentation == MediaPresentation.LIST }
                LazyVerticalGrid(GridCells.Adaptive(124.dp), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(page.entries, key = { index, entry -> "$index:${entry.id}" }, span = { _, entry ->
                        GridItemSpan(if (entry.presentation == MediaPresentation.LIST) maxLineSpan else 1)
                    }) { index, entry ->
                        if (entry.presentation == MediaPresentation.LIST) MediaTrackRow(entry,
                            if (numbered) (page.folder?.page ?: 0) * MediaLibrary.PAGE_SIZE + index + 1 else null,
                            library.nowPlaying?.let { it.playing && it.mediaId == entry.id } == true,
                            { open(entry) }, { play(entry) })
                        else MediaCover(entry, { open(entry) }, { play(entry) })
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
                Modifier.align(Alignment.BottomEnd).padding(6.dp), size = 48.dp, iconSize = 24.dp)
        }
        Text(entry.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, lineHeight = 20.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (entry.subtitle.isNotBlank()) Text(entry.subtitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MediaTrackRow(entry: MediaEntry, number: Int?, playing: Boolean, open: () -> Unit, play: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().heightIn(min = 68.dp), RoundedCornerShape(20.dp), selected = playing,
        onClick = open, enabled = entry.browsable || entry.playable) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (number != null) Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (playing) CarGlyph(CarSymbol.Music, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                else Text(number.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Artwork(entry.artwork, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.browsable && entry.playable) IconButton(play, Modifier.size(48.dp)) {
                CarGlyph(CarSymbol.Play, Modifier.size(26.dp), MaterialTheme.colorScheme.primary)
            } else if (entry.browsable) CarGlyph(CarSymbol.ChevronRight, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
