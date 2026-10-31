package ch.broillet.veloce.ui

import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.broillet.veloce.R
import ch.broillet.veloce.lyrics.*
import ch.broillet.veloce.media.NowPlaying
import kotlinx.coroutines.delay

@Composable
internal fun LyricsContent(track: NowPlaying?, artwork: Bitmap?, repository: LyricsRepository, control: (Long) -> Boolean,
    seek: (Long) -> Boolean, close: () -> Unit, modifier: Modifier = Modifier) {
    val query = LyricsTrack(track?.title.orEmpty(), track?.artist.orEmpty(), track?.album.orEmpty(), track?.durationMs ?: 0)
    key(query.key) {
        var revision by remember { mutableIntStateOf(0) }
        val state by produceState(LyricsState(), query, revision) {
            repository.lyrics(query, refresh = revision > 0).collect { value = it }
        }
        val position by produceState(track?.positionMs ?: 0, track) {
            while (true) {
                value = track?.positionAt(SystemClock.elapsedRealtime())?.coerceAtLeast(0) ?: 0
                if (track?.advancing != true) break
                delay(50)
            }
        }
        Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArtworkImage(artwork, Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
                Text(track?.title ?: stringResource(R.string.now_playing), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val toggle = track?.let {
                    val action = if (it.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                    if (it.actions and action != 0L) action else PlaybackState.ACTION_PLAY_PAUSE
                } ?: PlaybackState.ACTION_PLAY_PAUSE
                GlassIconButton(CarSymbol.Previous, stringResource(R.string.previous_track), { control(PlaybackState.ACTION_SKIP_TO_PREVIOUS) },
                    size = 48.dp, iconSize = 24.dp, enabled = track != null && track.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
                GlassIconButton(if (track?.playing == true) CarSymbol.Pause else CarSymbol.Play,
                    stringResource(if (track?.playing == true) R.string.pause else R.string.play), { control(toggle) }, size = 48.dp, iconSize = 24.dp,
                    enabled = track != null && track.actions and toggle != 0L)
                GlassIconButton(CarSymbol.Next, stringResource(R.string.next_track), { control(PlaybackState.ACTION_SKIP_TO_NEXT) },
                    size = 48.dp, iconSize = 24.dp, enabled = track != null && track.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
                GlassIconButton(CarSymbol.Close, stringResource(R.string.close_lyrics), close, size = 48.dp, iconSize = 24.dp)
            }
            val document = state.document
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val height = maxHeight
                when {
                    document?.instrumental == true -> Text(stringResource(R.string.instrumental), Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineLarge)
                    document == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (state.loading) CircularProgressIndicator()
                        Text(stringResource(if (state.loading) R.string.finding_lyrics else R.string.no_lyrics), style = MaterialTheme.typography.titleLarge)
                        if (!state.loading) GlassIconButton(CarSymbol.Refresh, stringResource(R.string.retry), { revision++ })
                    }
                    else -> {
                        val list = rememberLazyListState()
                        val dragged by list.interactionSource.collectIsDraggedAsState()
                        var follow by remember { mutableStateOf(true) }
                        LaunchedEffect(dragged) { if (dragged) follow = false }
                        val current = document.lineAt(position)
                        val font = if (document.synced) (maxWidth.value / 16f).coerceIn(30f, 48f).sp else 30.sp
                        val anchor = minOf(height * .18f, 48.dp)
                        val currentHeight by remember(list, current) {
                            derivedStateOf { list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }?.size ?: 0 }
                        }
                        val scrollOffset = with(androidx.compose.ui.platform.LocalDensity.current) {
                            val preferred = anchor.roundToPx()
                            val nextLine = (font.value * 1.2f).sp.roundToPx() + 24.dp.roundToPx()
                            val target = minOf(preferred, (height.roundToPx() - currentHeight - nextLine).coerceAtLeast(0))
                            preferred - target
                        }
                        LaunchedEffect(current, follow, document, scrollOffset) {
                            if (follow && current >= 0) list.animateScrollToItem(current, scrollOffset)
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(24.dp),
                            contentPadding = PaddingValues(top = if (document.synced) anchor else 12.dp,
                                bottom = if (document.synced) height - anchor else 12.dp)) {
                            itemsIndexed(document.lines, key = { index, _ -> index }) { index, line ->
                                val active = index == current
                                val normal = MaterialTheme.colorScheme.onSurface.copy(alpha = if (!document.synced || active) 1f else .28f)
                                val text = buildAnnotatedString {
                                    append(line.text.ifBlank { "•••" })
                                    if (active && line.words.isNotEmpty()) line.words.forEach { word ->
                                        val from = word.from.coerceIn(0, line.text.length)
                                        val until = word.until.coerceIn(from, line.text.length)
                                        addStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (position >= word.startMs) 1f else .3f)), from, until)
                                    }
                                }
                                Text(text, Modifier.fillMaxWidth().clickable(enabled = document.synced && line.startMs != null &&
                                    track != null && track.actions and PlaybackState.ACTION_SEEK_TO != 0L) { seek(checkNotNull(line.startMs)); follow = true },
                                    color = normal, fontSize = font, lineHeight = (font.value * 1.2f).sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (document.synced && !follow) CarTextButton(stringResource(R.string.follow_lyrics), { follow = true },
                            Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val kind = when (document?.quality) { 3 -> R.string.word_synced; 2 -> R.string.line_synced; else -> R.string.plain_lyrics }
                Text(document?.let { if (it.instrumental) it.source else "${it.source} · ${stringResource(kind)}" }.orEmpty(), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading && document != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (document != null) IconButton({ revision++ }, Modifier.size(40.dp)) {
                    CarGlyph(CarSymbol.Refresh, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
