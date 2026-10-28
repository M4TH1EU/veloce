package ch.broillet.veloce.ui

import android.media.session.PlaybackState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import ch.broillet.veloce.R
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.hosting.CarAppContent
import ch.broillet.veloce.media.NowPlaying
import ch.broillet.veloce.projection.ScreenSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

class CarNavigation {
    var selected by mutableStateOf<CarApp?>(null)
    var player by mutableStateOf(false)
    var projected by mutableStateOf(true)
    var appBack: (() -> Unit)? = null
    fun home() { selected = null; player = false }
    fun back(exit: () -> Unit) {
        val hostedBack = appBack
        if (selected != null && hostedBack != null) hostedBack() else if (selected != null || player) home() else exit()
    }
}

@Composable
fun CarLauncher(spec: ScreenSpec, apps: List<CarApp>, track: NowPlaying?, navigation: CarNavigation,
    control: (Long) -> Boolean, exit: () -> Unit) {
    val context = LocalContext.current
    val clock by produceState("") {
        val format = android.text.format.DateFormat.getTimeFormat(context)
        while (true) { value = format.format(Date()); delay(10_000) }
    }
    val horizontal = with(LocalDensity.current) { (spec.cropX / 2).toDp() }
    val vertical = with(LocalDensity.current) { (spec.cropY / 2).toDp() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = horizontal, vertical = vertical).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(navigation.selected?.label ?: stringResource(if (navigation.player) R.string.now_playing else R.string.app_name),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(clock, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            }
            val selected = navigation.selected
            when {
                navigation.player || selected?.kind == AppKind.AUDIO -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Player(if (selected == null || selected.component.packageName == track?.packageName) track else null, control)
                }
                selected != null -> key(selected.component) { CarAppContent(selected, navigation, Modifier.fillMaxWidth().weight(1f)) }
                apps.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_apps)) }
                else -> LazyVerticalGrid(GridCells.Adaptive(136.dp), Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(apps, key = { it.component.flattenToString() }) { app ->
                        var focused by remember { mutableStateOf(false) }
                        Card(onClick = { navigation.selected = app }, modifier = Modifier
                            .onFocusChanged { focused = it.hasFocus }
                            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppIcon(app)
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = navigation::home, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.home)) }
                    if (selected != null || navigation.player) TextButton(onClick = { navigation.back(exit) }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.back))
                    }
                    TextButton(onClick = { navigation.home(); navigation.player = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(track?.title ?: stringResource(R.string.now_playing), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = exit, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.return_to_car)) }
                }
            }
        }
    }
}

@Composable
private fun Player(track: NowPlaying?, control: (Long) -> Boolean) {
    var failed by remember { mutableStateOf(false) }
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
    }
}

@Composable
private fun AppIcon(app: CarApp) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.component) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getApplicationIcon(app.component.packageName).toBitmap(96, 96).asImageBitmap() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }
    }
    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
            ?: Text(app.label.take(1), style = MaterialTheme.typography.titleLarge)
    }
}
