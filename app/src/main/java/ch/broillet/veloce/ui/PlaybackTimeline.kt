package ch.broillet.veloce.ui

import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.broillet.veloce.R
import ch.broillet.veloce.media.NowPlaying
import kotlinx.coroutines.delay
import java.util.Locale

private data class PendingSeek(val positionMs: Long, val requestedAtMs: Long)

@Composable
internal fun rememberPlaybackPosition(track: NowPlaying?, intervalMs: Long = 250): State<Long> =
    produceState(track?.positionAt(SystemClock.elapsedRealtime()) ?: 0, track, intervalMs) {
        while (true) {
            value = track?.positionAt(SystemClock.elapsedRealtime()) ?: 0
            if (track?.advancing != true) break
            delay(intervalMs)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackTimeline(track: NowPlaying, seekTo: (Long) -> Boolean, compact: Boolean = false) {
    if (track.durationMs <= 0 || track.positionMs < 0) return
    val position by rememberPlaybackPosition(track)
    var dragged by remember(track.packageName, track.title, track.durationMs) { mutableStateOf<Float?>(null) }
    var pending by remember(track.packageName, track.title, track.durationMs) { mutableStateOf<PendingSeek?>(null) }
    LaunchedEffect(track.positionMs, track.updatedAtMs) {
        if (pending?.let { track.updatedAtMs >= it.requestedAtMs } == true) pending = null
    }
    LaunchedEffect(pending) {
        if (pending != null) { delay(1_500); pending = null }
    }
    val displayed = (dragged?.let { (it.toDouble() * track.durationMs).toLong() } ?: pending?.positionMs ?: position)
        .coerceIn(0, track.durationMs)
    val progress = displayed.toFloat() / track.durationMs
    val canSeek = track.supports(PlaybackState.ACTION_SEEK_TO)
    val interactions = remember { MutableInteractionSource() }
    val sliderState = rememberSliderState()
    SideEffect { sliderState.value = progress }
    val accent = MaterialTheme.colorScheme.primary
    val colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .2f))
    val label = stringResource(R.string.playback_position)
    val timeStyle = MaterialTheme.typography.bodySmall.let { if (compact) it.copy(fontSize = 12.sp, lineHeight = 16.sp) else it }
    Column {
        Slider(state = sliderState, onValueChange = { sliderState.value = it; dragged = it }, enabled = canSeek,
            onValueChangeFinished = {
                dragged?.let { fraction ->
                    val request = PendingSeek((fraction.toDouble() * track.durationMs).toLong().coerceIn(0, track.durationMs), SystemClock.elapsedRealtime())
                    if (seekTo(request.positionMs)) pending = request
                }
                dragged = null
            }, modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label },
            colors = colors, interactionSource = interactions,
            thumb = { SliderDefaults.Thumb(interactions, colors = colors, enabled = canSeek, thumbSize = DpSize(10.dp, 36.dp)) },
            track = { state -> WavySeekTrack({ state.value }, track.advancing && dragged == null) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime(displayed), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(playbackTime(track.durationMs), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WavySeekTrack(progress: () -> Float, playing: Boolean) {
    val phase = remember { Animatable(0f) }
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(220), label = "Wave amplitude")
    LaunchedEffect(playing) {
        if (playing) {
            phase.snapTo(0f)
            phase.animateTo(1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)))
        }
    }
    val path = remember { Path() }
    val accent = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .2f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        scale(if (rtl) -1f else 1f, 1f) {
            val end = size.width * progress().coerceIn(0f, 1f)
            val center = size.height / 2f
            val gap = 8.dp.toPx()
            val width = 6.dp.toPx()
            if (end + gap < size.width) drawLine(inactive, Offset(end + gap, center),
                Offset(size.width, center), width, cap = StrokeCap.Round)
            if (end < size.width - gap) drawCircle(accent, 2.dp.toPx(), Offset(size.width, center))
            if (end > gap) clipRect(right = end - gap) {
                val wavelength = 28.dp.toPx()
                val height = 4.dp.toPx() * amplitude * 4f / 3f
                var x = (phase.value - 1f) * wavelength
                path.rewind()
                path.moveTo(x, center)
                while (x < end + wavelength) {
                    path.cubicTo(x + wavelength / 4, center - height, x + wavelength / 4, center - height, x + wavelength / 2, center)
                    path.cubicTo(x + wavelength * .75f, center + height, x + wavelength * .75f, center + height, x + wavelength, center)
                    x += wavelength
                }
                drawPath(path, accent, style = Stroke(width, cap = StrokeCap.Round))
            }
        }
    }
}

private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
