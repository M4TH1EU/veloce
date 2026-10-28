package ch.broillet.veloce.ui

import android.media.session.PlaybackState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R
import ch.broillet.veloce.media.MediaLibrary
import ch.broillet.veloce.media.NowPlaying

@Composable
fun MediaLibraryContent(library: MediaLibrary, showPlayer: () -> Unit, modifier: Modifier) {
    val page = library.page
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(page.folder?.title ?: library.app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (page.folder != null) TextButton(onClick = library::refresh, enabled = !page.loading) { Text(stringResource(R.string.refresh)) }
            TextButton(onClick = showPlayer) { Text(stringResource(R.string.now_playing)) }
        }
        if (page.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val failure = page.error
        when {
            failure != null -> Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.Center) {
                Text(failure, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.media_browse_denied))
                TextButton(onClick = showPlayer) { Text(stringResource(R.string.now_playing)) }
                if (page.folder != null) TextButton(onClick = library::refresh) { Text(stringResource(R.string.retry)) }
            }
            page.entries.isEmpty() && !page.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.media_empty))
            }
            else -> key(page.folder) {
                LazyColumn(Modifier.weight(1f)) {
                    items(page.entries) { entry ->
                        Surface(onClick = {
                            if (entry.browsable) library.open(entry) else if (library.play(entry)) showPlayer()
                        }, enabled = entry.browsable || entry.playable, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (entry.browsable && entry.playable) TextButton(onClick = { if (library.play(entry)) showPlayer() }) {
                                    Text(stringResource(R.string.play))
                                }
                                if (entry.browsable) Text("›", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
        if (page.folder != null && (page.folder.page > 0 || page.more)) Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { library.changePage(-1) }, enabled = !page.loading && page.folder.page > 0) { Text(stringResource(R.string.previous)) }
            Text(stringResource(R.string.media_page, page.folder.page + 1))
            TextButton(onClick = { library.changePage(1) }, enabled = !page.loading && page.more) { Text(stringResource(R.string.next)) }
        }
        library.playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2) }
    }
}

@Composable
fun MediaPlayer(track: NowPlaying?, control: (Long) -> Boolean, modifier: Modifier = Modifier, error: String? = null) {
    var failed by remember(track?.packageName) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(track?.title ?: stringResource(R.string.start_playback), style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            track?.let {
                Text(it.artist ?: it.packageName, style = MaterialTheme.typography.bodyLarge)
                val playPause = if (it.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                val toggle = if (it.actions and playPause != 0L) playPause else PlaybackState.ACTION_PLAY_PAUSE
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(R.string.previous to PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                        (if (it.playing) R.string.pause else R.string.play) to toggle,
                        R.string.next to PlaybackState.ACTION_SKIP_TO_NEXT).forEach { (label, action) ->
                        FilledTonalButton(onClick = { failed = !control(action) }, enabled = it.actions and action != 0L,
                            modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(label)) }
                    }
                }
            }
            if (failed) Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}
