package ch.broillet.veloce.ui

import android.media.session.PlaybackState
import android.graphics.Bitmap
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import ch.broillet.veloce.R
import ch.broillet.veloce.media.NowPlaying

@Composable
fun MediaPlayer(track: NowPlaying?, control: (Long) -> Boolean, modifier: Modifier = Modifier, error: String? = null,
    customAction: (String) -> Boolean = { false }, setSpeed: (Float) -> Boolean = { false }, bitmap: Bitmap? = null,
    seekTo: (Long) -> Boolean = { false }) {
    var failed by remember(track?.packageName) { mutableStateOf(false) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 520.dp
        val compact = maxHeight < 320.dp
        val titleSize = if (compact) 22.sp else if (maxWidth >= 660.dp) 32.sp else 28.sp
        val coverSize = if (wide) minOf(maxHeight - 28.dp, maxWidth * .36f, 360.dp).coerceAtLeast(96.dp)
            else minOf(maxWidth * .48f, 180.dp)
        val cover: @Composable () -> Unit = {
            ArtworkTransition(bitmap, Modifier.size(coverSize).clip(RoundedCornerShape(24.dp))) { art, layer ->
                ArtworkImage(art, layer)
            }
        }
        val details: @Composable (Modifier) -> Unit = { area ->
            Column(area.then(if (wide && compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = if (wide) Arrangement.SpaceBetween else Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                    Text(track?.title ?: stringResource(R.string.start_playback), Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        style = MaterialTheme.typography.headlineLarge, fontSize = titleSize, lineHeight = (titleSize.value + 6).sp,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    track?.artist?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.titleLarge, fontSize = if (compact) 16.sp else 22.sp,
                            lineHeight = if (compact) 20.sp else 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (failed) Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.error, maxLines = 1)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                track?.let {
                    Box(Modifier.fillMaxWidth().height(if (compact) 60.dp else 64.dp)) {
                        PlaybackTimeline(it, { position -> seekTo(position).also { success -> failed = !success } }, compact)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                        PlaybackControls(it, { failed = !control(it) }, playSize = if (compact) 76.dp else 88.dp)
                    }
                    if (it.hasSecondaryControls()) Box(Modifier.fillMaxWidth().height(if (compact) 60.dp else 68.dp)) {
                        PlayerSecondaryControls(it, { failed = !control(it) }, { failed = !customAction(it) }, { failed = !setSpeed(it) },
                            if (compact) 60.dp else 68.dp)
                    }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            cover()
            details(Modifier.weight(1f).fillMaxHeight().padding(vertical = if (compact) 0.dp else 8.dp))
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            cover()
            details(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun PlaybackControls(track: NowPlaying?, control: (Long) -> Unit, size: Dp = 68.dp, playSize: Dp = size,
    plain: Boolean = false) {
    @Composable fun Button(symbol: CarSymbol, label: Int, action: Long, primary: Boolean = false) {
        val enabled = track?.supports(action) == true
        val description = stringResource(label)
        val iconSize = if (primary && playSize > size) 38.dp else if (size < 64.dp) 24.dp else 30.dp
        if (plain && !primary) IconButton({ control(action) }, Modifier.size(size), enabled = enabled) {
            CarGlyph(symbol, Modifier.size(iconSize).semantics { contentDescription = description }, LocalContentColor.current)
        } else GlassIconButton(symbol, description, { control(action) }, enabled = enabled,
            size = if (primary) playSize else size, iconSize = iconSize, prominent = primary)
    }
    Button(CarSymbol.Previous, R.string.previous_track, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
    Button(if (track?.playing == true) CarSymbol.Pause else CarSymbol.Play,
        if (track?.playing == true) R.string.pause else R.string.play, track?.toggleAction ?: PlaybackState.ACTION_PLAY_PAUSE, primary = true)
    Button(CarSymbol.Next, R.string.next_track, PlaybackState.ACTION_SKIP_TO_NEXT)
}

@Composable
internal fun CompactMediaPlayer(track: NowPlaying?, open: () -> Unit, browse: () -> Unit, control: (Long) -> Boolean, modifier: Modifier) {
    val artwork = rememberArtwork(track?.artwork)
    var failed by remember(track?.packageName) { mutableStateOf(false) }
    AlbumSceneTheme(artwork, dense = false) {
        BoxWithConstraints(modifier) {
            val compact = maxHeight < 400.dp
            GlassSurface(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .22f), RoundedCornerShape(28.dp)),
                opacity = 1f, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                if (LocalAppSettings.current.artworkBackground && artwork != null) AlbumBackdrop(artwork, Modifier.matchParentSize(), radius = 32.dp)
                Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.now_playing), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        GlassIconButton(CarSymbol.Library, stringResource(R.string.media_library), browse, size = 56.dp, iconSize = 28.dp, opacity = 0f)
                    }
                    Column(Modifier.fillMaxWidth().weight(1f)
                        .clickable(role = Role.Button, onClickLabel = stringResource(if (track == null) R.string.choose_audio_app else R.string.open_player), onClick = open),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            ArtworkTransition(artwork, Modifier.sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
                                .aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(24.dp))) { art, layer -> ArtworkImage(art, layer) }
                        }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(track?.title?.takeIf { it.isNotBlank() } ?: stringResource(if (track == null) R.string.no_playback else R.string.now_playing),
                                style = MaterialTheme.typography.titleLarge,
                                fontSize = if (compact) 20.sp else 22.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                failed -> stringResource(R.string.media_unavailable)
                                track == null -> stringResource(R.string.choose_audio_app)
                                else -> track.artist?.takeIf { it.isNotBlank() } ?: track.album.orEmpty()
                            },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaybackControls(track, { failed = !control(it) }, size = 68.dp, playSize = 76.dp)
                    }
                }
            }
        }
    }
}

private fun NowPlaying.hasSecondaryControls(): Boolean = supports(PlaybackState.ACTION_REWIND) ||
    supports(PlaybackState.ACTION_FAST_FORWARD) || supports(PlaybackState.ACTION_SET_PLAYBACK_SPEED) || customActions.isNotEmpty()

@Composable
private fun PlayerSecondaryControls(track: NowPlaying, control: (Long) -> Unit, customAction: (String) -> Unit, setSpeed: (Float) -> Unit, size: Dp) {
    val rewind = track.supports(PlaybackState.ACTION_REWIND)
    val forward = track.supports(PlaybackState.ACTION_FAST_FORWARD)
    val speed = track.supports(PlaybackState.ACTION_SET_PLAYBACK_SPEED)
    if (!rewind && !forward && !speed && track.customActions.isEmpty()) return
    var speedPicker by remember(track.packageName) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        if (rewind) GlassIconButton(CarSymbol.Rewind, stringResource(R.string.rewind), { control(PlaybackState.ACTION_REWIND) }, size = size)
        if (forward) GlassIconButton(CarSymbol.Forward, stringResource(R.string.fast_forward), { control(PlaybackState.ACTION_FAST_FORWARD) }, size = size)
        track.customActions.forEach { action ->
            GlassSurface(Modifier.size(size).semantics { contentDescription = action.label },
                onClick = { customAction(action.id) }, enabled = action.enabled, shape = RoundedCornerShape(24.dp)) {
                MediaActionIcon(action, Modifier.size(32.dp).align(Alignment.Center))
            }
        }
        if (speed) GlassSurface(Modifier.size(size).semantics { contentDescription = "${track.speed}×" },
            onClick = { speedPicker = true }, shape = RoundedCornerShape(24.dp)) {
            Text("${track.speed}×", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (speedPicker) Dialog(onDismissRequest = { speedPicker = false }) {
        GlassSurface(opacity = .98f) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.playback_speed_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    GlassIconButton(CarSymbol.Close, stringResource(R.string.cancel), { speedPicker = false })
                }
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(164.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { value ->
                        CarTextButton("$value×", { speedPicker = false; setSpeed(value) }, selected = value == track.speed)
                    }
                }
            }
        }
    }
}
