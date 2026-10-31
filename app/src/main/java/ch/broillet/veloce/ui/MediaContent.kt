package ch.broillet.veloce.ui

import android.media.session.PlaybackState
import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
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
            ArtworkTransition(bitmap, Modifier.size(coverSize).clip(RoundedCornerShape(28.dp))
                .border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(28.dp))) { art, layer ->
                ArtworkImage(art, layer)
            }
        }
        val details: @Composable (Modifier) -> Unit = { area ->
            Column(area, verticalArrangement = if (wide) Arrangement.SpaceBetween else Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                    Text(track?.title ?: stringResource(R.string.start_playback), Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        style = MaterialTheme.typography.headlineLarge, fontSize = titleSize, lineHeight = (titleSize.value + 6).sp,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    track?.artist?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.titleLarge, fontSize = if (compact) 16.sp else 22.sp,
                            lineHeight = if (compact) 20.sp else 28.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (failed) Text(stringResource(R.string.media_unavailable), color = MaterialTheme.colorScheme.error, maxLines = 1)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                track?.let {
                    Box(Modifier.fillMaxWidth().height(if (compact) 64.dp else 68.dp)) {
                        PlaybackTimeline(it, { position -> seekTo(position).also { success -> failed = !success } }, compact)
                    }
                    val playPause = if (it.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                    val toggle = if (it.actions and playPause != 0L) playPause else PlaybackState.ACTION_PLAY_PAUSE
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                        GlassIconButton(CarSymbol.Previous, stringResource(R.string.previous),
                            { failed = !control(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }, enabled = it.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
                        GlassIconButton(if (it.playing) CarSymbol.Pause else CarSymbol.Play,
                            stringResource(if (it.playing) R.string.pause else R.string.play), { failed = !control(toggle) },
                            selected = true, enabled = it.actions and toggle != 0L, size = if (compact) 76.dp else 88.dp, iconSize = 38.dp)
                        GlassIconButton(CarSymbol.Next, stringResource(R.string.next),
                            { failed = !control(PlaybackState.ACTION_SKIP_TO_NEXT) }, enabled = it.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
                    }
                    Box(Modifier.fillMaxWidth().height(if (compact) 56.dp else 64.dp)) {
                        PlayerSecondaryControls(it, { failed = !control(it) }, { failed = !customAction(it) }, { failed = !setSpeed(it) },
                            if (compact) 56.dp else 64.dp)
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
private fun PlayerSecondaryControls(track: NowPlaying, control: (Long) -> Unit, customAction: (String) -> Unit, setSpeed: (Float) -> Unit, size: Dp) {
    val rewind = track.actions and PlaybackState.ACTION_REWIND != 0L
    val forward = track.actions and PlaybackState.ACTION_FAST_FORWARD != 0L
    val speed = track.actions and PlaybackState.ACTION_SET_PLAYBACK_SPEED != 0L
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
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(152.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { value ->
                        CarTextButton("$value×", { speedPicker = false; setSpeed(value) }, selected = value == track.speed)
                    }
                }
            }
        }
    }
}
