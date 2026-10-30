package ch.broillet.veloce.ui

import android.media.session.PlaybackState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R
import ch.broillet.veloce.media.MediaLibrary
import ch.broillet.veloce.media.NowPlaying

@Composable
fun MediaLibraryContent(library: MediaLibrary, showPlayer: () -> Unit, modifier: Modifier) {
    val page = library.page
    var query by remember(library) { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(page.folder?.title ?: library.app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (page.folder != null) TextButton(onClick = library::refresh, enabled = !page.loading) { Text(stringResource(R.string.refresh)) }
            TextButton(onClick = showPlayer) { Text(stringResource(R.string.now_playing)) }
        }
        if (library.supportsSearch) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it.take(200) }, Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.search)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { library.search(query) }))
            TextButton(onClick = { library.search(query) }, enabled = query.isNotBlank()) { Text(stringResource(R.string.search)) }
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
                                Artwork(entry.artwork, Modifier.size(48.dp).padding(end = 8.dp))
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
fun MediaPlayer(track: NowPlaying?, control: (Long) -> Boolean, modifier: Modifier = Modifier, error: String? = null,
    customAction: (String) -> Boolean = { false }, setSpeed: (Float) -> Boolean = { false }) {
    var failed by remember(track?.packageName) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(track?.artwork, Modifier.size(96.dp))
            Text(track?.title ?: stringResource(R.string.start_playback), style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            track?.let {
                Text(it.artist ?: it.packageName, style = MaterialTheme.typography.bodyLarge)
                val playPause = if (it.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                val toggle = if (it.actions and playPause != 0L) playPause else PlaybackState.ACTION_PLAY_PAUSE
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.previous to PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                        R.string.rewind to PlaybackState.ACTION_REWIND,
                        (if (it.playing) R.string.pause else R.string.play) to toggle,
                        R.string.fast_forward to PlaybackState.ACTION_FAST_FORWARD,
                        R.string.next to PlaybackState.ACTION_SKIP_TO_NEXT).forEach { (label, action) ->
                        FilledTonalButton(onClick = { failed = !control(action) }, enabled = it.actions and action != 0L,
                            modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(label)) }
                    }
                }
                if (it.customActions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    it.customActions.forEach { action ->
                        FilledTonalButton(onClick = { failed = !customAction(action.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (it.actions and PlaybackState.ACTION_SET_PLAYBACK_SPEED != 0L) Box {
                    var expanded by remember { mutableStateOf(false) }
                    TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.playback_speed, it.speed)) }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            DropdownMenuItem(text = { Text("$speed×") }, onClick = { expanded = false; failed = !setSpeed(speed) })
                        }
                    }
                }
            }
            if (failed) Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}
