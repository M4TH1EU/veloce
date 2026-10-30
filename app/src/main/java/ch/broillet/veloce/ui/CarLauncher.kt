package ch.broillet.veloce.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.R
import ch.broillet.veloce.VeloceApplication
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.hosting.CarAppContent
import ch.broillet.veloce.media.ActiveMedia
import ch.broillet.veloce.projection.ScreenSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

class CarNavigation {
    var selected by mutableStateOf<CarApp?>(null)
    var player by mutableStateOf(false)
    var projected by mutableStateOf(true)
    var notificationsOpen by mutableStateOf(false)
    var appBack: (() -> Unit)? = null
    var mediaKey: ((Int) -> Unit)? = null
    fun home() { selected = null; player = false; notificationsOpen = false }
    fun back(exit: () -> Unit) {
        if (notificationsOpen) { notificationsOpen = false; return }
        if (player) { player = false; return }
        val hostedBack = appBack
        if (selected != null && hostedBack != null) hostedBack() else if (selected != null) home() else exit()
    }
}

@Composable
fun CarLauncher(spec: ScreenSpec, apps: List<CarApp>, navigation: CarNavigation, media: ActiveMedia, exit: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VeloceApplication
    val notifications = app.notifications
    val notices by notifications.notices.collectAsStateWithLifecycle()
    val workspace = remember(context, navigation) {
        CarWorkspace(context, navigation, app.connections::log)
    }
    DisposableEffect(workspace) { onDispose { workspace.close() } }
    val selected = navigation.selected
    val host = workspace.host
    val library = workspace.library
    val active by media.nowPlaying.collectAsStateWithLifecycle()
    val tracks by media.tracks.collectAsStateWithLifecycle()
    val currentTrack = active?.takeIf { it.playing } ?: library?.nowPlaying?.takeIf { it.playing } ?: active ?: library?.nowPlaying
    val playerTrack = if (selected?.kind == AppKind.AUDIO) {
        library?.nowPlaying?.takeIf { it.title != null }
            ?: tracks.firstOrNull { it.packageName == selected.component.packageName && it.playing }
            ?: tracks.firstOrNull { it.packageName == selected.component.packageName }
            ?: library?.nowPlaying
    } else currentTrack
    val libraryPlayer = library?.takeIf { playerTrack != null && playerTrack === it.nowPlaying }
    val control: (Long) -> Boolean = { action ->
        libraryPlayer?.control(action) ?: media.control(action, playerTrack?.packageName)
    }
    LaunchedEffect(host, selected, navigation.player, navigation.projected, navigation.notificationsOpen) { workspace.updateVisibility() }
    SideEffect {
        navigation.mediaKey = { media.key(it, workspace.library) }
        navigation.appBack = when {
            selected?.kind == AppKind.AUDIO -> ({ if (library?.back() != true) navigation.home() })
            selected != null && selected.component == host?.app?.component -> host::back
            else -> null
        }
    }
    val clock by produceState("") {
        val format = android.text.format.DateFormat.getTimeFormat(context)
        while (true) { value = format.format(Date()); delay(10_000) }
    }
    val horizontal = with(LocalDensity.current) { (spec.cropX / 2).toDp() }
    val vertical = with(LocalDensity.current) { (spec.cropY / 2).toDp() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = horizontal, vertical = vertical).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (navigation.notificationsOpen) stringResource(R.string.notifications)
                        else navigation.selected?.label ?: stringResource(if (navigation.player) R.string.now_playing else R.string.app_name),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(clock, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                    TextButton(onClick = { navigation.notificationsOpen = !navigation.notificationsOpen }) {
                        Text(stringResource(R.string.notification_count, notices.size))
                    }
                }
                if (host?.navigating == true && (selected?.component != host.app.component || navigation.player || navigation.notificationsOpen)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { workspace.open(host.app) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.resume_navigation, host.app.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = host::stopNavigation) { Text(stringResource(R.string.stop_navigation)) }
                    }
                }
                when {
                    navigation.notificationsOpen -> CarNotificationsContent(notifications, Modifier.fillMaxWidth().weight(1f))
                     navigation.player -> MediaPlayer(playerTrack, control, Modifier.fillMaxWidth().weight(1f),
                         if (selected?.kind == AppKind.AUDIO || libraryPlayer != null) library?.playbackError else null,
                         customAction = { libraryPlayer?.customAction(it) ?: media.customAction(it, playerTrack?.packageName) },
                         setSpeed = { libraryPlayer?.setSpeed(it) ?: media.setSpeed(it, playerTrack?.packageName) })
                    selected?.kind == AppKind.AUDIO && library != null -> MediaLibraryContent(library, { navigation.player = true }, Modifier.fillMaxWidth().weight(1f))
                    selected != null && host != null -> key(host) { CarAppContent(host, navigation::home, Modifier.fillMaxWidth().weight(1f)) }
                    apps.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_apps)) }
                    else -> LazyVerticalGrid(GridCells.Adaptive(136.dp), Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(apps, key = { it.component.flattenToString() }) { app ->
                            var focused by remember { mutableStateOf(false) }
                            Card(onClick = { workspace.open(app) }, modifier = Modifier
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
                        if (selected != null || navigation.player || navigation.notificationsOpen) TextButton(onClick = { navigation.back(exit) }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.back))
                        }
                        TextButton(onClick = {
                            if (library != null && library.app.component.packageName == currentTrack?.packageName) workspace.open(library.app)
                            else navigation.home()
                            navigation.notificationsOpen = false
                            navigation.player = true
                        }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text(currentTrack?.title ?: stringResource(R.string.now_playing), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = exit, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.return_to_car)) }
                    }
                }
            }
            if (navigation.projected && !navigation.notificationsOpen) {
                CarNotificationBanner(notifications, { navigation.notificationsOpen = true },
                    Modifier.align(Alignment.TopCenter).padding(horizontal = horizontal + 16.dp, vertical = vertical + 16.dp))
            }
        }
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
