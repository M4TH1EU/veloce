package ch.broillet.veloce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.R
import ch.broillet.veloce.VeloceApplication
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.hosting.CarAppContent
import ch.broillet.veloce.hosting.hasMapSurface
import ch.broillet.veloce.hosting.pageTitle
import ch.broillet.veloce.hosting.HostedToolbarActions
import ch.broillet.veloce.hosting.MapScene
import ch.broillet.veloce.hosting.MapSurface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import androidx.car.app.model.SearchTemplate
import ch.broillet.veloce.media.ActiveMedia
import ch.broillet.veloce.media.NowPlaying
import ch.broillet.veloce.media.MediaLibrary
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
    var lyrics by mutableStateOf(false)
    var appBack: (() -> Unit)? = null
    var mediaKey: ((Int) -> Unit)? = null
    fun home() { selected = null; player = false; notificationsOpen = false; lyrics = false }
    fun back(exit: () -> Unit) {
        if (notificationsOpen) { notificationsOpen = false; return }
        if (lyrics) { lyrics = false; return }
        if (player) { player = false; return }
        val hostedBack = appBack
        if (selected != null && hostedBack != null) hostedBack() else if (selected != null) home() else exit()
    }
}

@Composable
fun CarLauncher(spec: ScreenSpec, apps: List<CarApp>, navigation: CarNavigation, media: ActiveMedia, exit: () -> Unit) {
    CarTheme { CarLauncherContent(spec, apps, navigation, media, exit) }
}

@Composable
private fun CarLauncherContent(spec: ScreenSpec, apps: List<CarApp>, navigation: CarNavigation, media: ActiveMedia, exit: () -> Unit) {
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
    val selectedHost = host?.takeIf { selected?.component == it.app.component }
    val library = workspace.library
    val playerVisible = navigation.player && !navigation.notificationsOpen
    val appVisible = !navigation.player && !navigation.notificationsOpen
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
    var choosingMap by remember { mutableStateOf(false) }
    val maps = remember(apps) { apps.filter { it.kind == AppKind.MAP } }
    val openMap: () -> Unit = {
        val previous = host?.app?.takeIf { it.kind == AppKind.MAP }
        when {
            previous != null -> workspace.open(previous)
            maps.size == 1 -> workspace.open(maps.first())
            else -> choosingMap = true
        }
    }
    val openPlayer: () -> Unit = {
        if (library != null && library.app.component.packageName == currentTrack?.packageName) workspace.open(library.app)
        else navigation.home()
        navigation.player = true
    }
    val control: (Long) -> Boolean = { action ->
        libraryPlayer?.control(action) ?: media.control(action, playerTrack?.packageName)
    }
    val miniLibrary = library?.takeIf { currentTrack != null && currentTrack === it.nowPlaying }
    val miniControl: (Long) -> Boolean = { action -> miniLibrary?.control(action) ?: media.control(action, currentTrack?.packageName) }
    LaunchedEffect(host, selected, navigation.player, navigation.projected, navigation.notificationsOpen) { workspace.updateVisibility() }
    SideEffect {
        navigation.mediaKey = { media.key(it, workspace.library) }
        navigation.appBack = when {
            selected?.kind == AppKind.AUDIO -> ({ if (library?.back() != true) navigation.home() })
            selectedHost != null -> selectedHost::back
            else -> null
        }
    }
    val horizontal = with(LocalDensity.current) { (spec.cropX / 2).toDp() }
    val vertical = with(LocalDensity.current) { (spec.cropY / 2).toDp() }
    val mapVisible = appVisible && selectedHost != null && selectedHost.template.hasMapSurface()
    val mapScene = remember(host) { MapScene() }
    val mapBackdrop = appVisible && selectedHost?.app?.kind == AppKind.MAP && selectedHost.error == null
    val denseChrome = mapVisible || mapBackdrop
    val showPill = !playerVisible && currentTrack != null
    val album = rememberArtwork(playerTrack?.artwork.takeIf { playerVisible })
    val titlePackage = if (navigation.player) playerTrack?.packageName else selected?.component?.packageName
    val notice = selectedHost?.notice?.takeIf { appVisible }
    val browser = library?.takeIf { selected?.kind == AppKind.AUDIO && appVisible }
    val hostedPage = selectedHost?.takeIf { appVisible && !mapVisible }
    val searchInToolbar = browser?.supportsSearch == true || hostedPage?.template is SearchTemplate
    var mediaQuery by remember(library) { mutableStateOf("") }
    val browserState = rememberSaveableStateHolder()
    val browsePaging = browser?.page?.let { (it.folder?.page ?: 0) > 0 || it.more } == true
    val lyricsVisible = playerVisible && navigation.lyrics
    AlbumSceneTheme(album, denseChrome) {
        Box(Modifier.fillMaxSize().cabinBackground()) {
            if (playerVisible) AlbumBackdrop(album, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().padding(horizontal = horizontal, vertical = vertical)) {
                if (lyricsVisible) LyricsContent(playerTrack, album, app.lyrics, control,
                    { libraryPlayer?.seekTo(it) ?: media.seekTo(it, playerTrack?.packageName) }, { navigation.lyrics = false })
                else {
                    if (mapBackdrop) key(selectedHost) {
                        MapSurface(selectedHost, Modifier.fillMaxSize(), mapScene.visibleInsets, mapScene.stableInsets,
                            onPanStart = { mapScene.panStart() }, onPanEnd = { mapScene.panEnd() }, blurred = !mapVisible, interactive = mapVisible)
                        if (!mapVisible) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)))
                    }
                    if (mapVisible) key(selectedHost) {
                        CarAppContent(selectedHost, navigation::home, Modifier.fillMaxSize(),
                            mapInsets = PaddingValues(start = 104.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                            bottomOverlay = if (showPill) 68.dp else 0.dp, mapScene = mapScene.takeIf { mapBackdrop })
                    }
                    val pageModifier = if (mapVisible) Modifier else Modifier.carEntrance(listOf(navigation.notificationsOpen, navigation.player,
                        selected?.component, selectedHost?.template?.javaClass))
                    Column(Modifier.fillMaxSize().padding(start = 108.dp, top = 16.dp, end = 24.dp, bottom = 16.dp).then(pageModifier),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!mapVisible) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (selected != null || navigation.player || navigation.notificationsOpen) {
                                    GlassIconButton(CarSymbol.Back, stringResource(R.string.back), { navigation.back(exit) }, size = 60.dp)
                                }
                                if (titlePackage != null && !navigation.notificationsOpen) PackageIcon(titlePackage, Modifier.size(32.dp))
                                Column(if (searchInToolbar) Modifier.widthIn(max = 128.dp) else Modifier.weight(1f)) {
                                    if (playerVisible) Text(stringResource(R.string.now_playing).uppercase(), style = MaterialTheme.typography.labelSmall)
                                    Text(when {
                                        navigation.notificationsOpen -> stringResource(R.string.notifications)
                                        navigation.player -> apps.firstOrNull { it.component.packageName == playerTrack?.packageName }?.label
                                            ?: stringResource(R.string.now_playing)
                                        browser != null -> browser.page.folder?.title ?: browser.app.label
                                        hostedPage != null -> hostedPage.template.pageTitle().ifBlank {
                                            if (hostedPage.template is SearchTemplate) stringResource(R.string.search) else hostedPage.app.label
                                        }
                                        else -> stringResource(R.string.your_apps)
                                    }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (browser != null) {
                                    if (browser.supportsSearch) {
                                        val search = { browser.search(mediaQuery) }
                                        CarSearchField(mediaQuery, { mediaQuery = it.take(200) }, stringResource(R.string.search), search, Modifier.weight(1f))
                                        GlassIconButton(CarSymbol.Search, stringResource(R.string.search), search, enabled = mediaQuery.isNotBlank())
                                    }
                                    GlassIconButton(CarSymbol.Refresh, stringResource(R.string.refresh), browser::refresh,
                                        enabled = browser.page.folder != null && !browser.page.loading, size = 56.dp)
                                }
                                if (hostedPage != null) HostedToolbarActions(hostedPage)
                                if (notice != null) HostNotice(notice, host::dismissNotice, Modifier.widthIn(max = if (searchInToolbar) 208.dp else 280.dp))
                                if (navigation.notificationsOpen && notices.isNotEmpty()) Text(notices.size.toString(),
                                    style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                if (playerVisible) {
                                    if (libraryPlayer != null) GlassIconButton(CarSymbol.Library, stringResource(R.string.media_library),
                                        { navigation.player = false }, size = 60.dp)
                                    GlassIconButton(CarSymbol.Lyrics, stringResource(R.string.lyrics), { navigation.lyrics = true }, size = 60.dp,
                                        enabled = !playerTrack?.title.isNullOrBlank())
                                }
                            }
                            if (host?.navigating == true) {
                                GlassSurface(onClick = { workspace.open(host.app) }) {
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.resume_navigation, host.app.label), Modifier.weight(1f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                                        TextButton(onClick = host::stopNavigation) { Text(stringResource(R.string.stop_navigation)) }
                                    }
                                }
                            }
                            when {
                                navigation.notificationsOpen -> CarNotificationsContent(notifications, Modifier.fillMaxWidth().weight(1f))
                                navigation.player -> MediaPlayer(playerTrack, control, Modifier.fillMaxWidth().weight(1f),
                                    if (selected?.kind == AppKind.AUDIO || libraryPlayer != null) library?.playbackError else null,
                                    customAction = { libraryPlayer?.customAction(it) ?: media.customAction(it, playerTrack?.packageName) },
                                    setSpeed = { libraryPlayer?.setSpeed(it) ?: media.setSpeed(it, playerTrack?.packageName) }, bitmap = album,
                                    seekTo = { libraryPlayer?.seekTo(it) ?: media.seekTo(it, playerTrack?.packageName) })
                                browser != null -> {
                                    val folder = browser.page.folder
                                    val stateKey = "${browser.app.component.flattenToString()}/${Uri.encode(folder?.id.orEmpty())}/${folder?.page ?: 0}"
                                    browserState.SaveableStateProvider(stateKey) {
                                        MediaLibraryContent(browser, { navigation.player = true }, Modifier.fillMaxWidth().weight(1f))
                                    }
                                }
                                hostedPage != null -> key(hostedPage) {
                                    CarAppContent(hostedPage, navigation::home, Modifier.fillMaxWidth().weight(1f), headerInToolbar = true)
                                }
                                else -> AppGrid(apps, workspace::open, Modifier.fillMaxWidth().weight(1f))
                            }
                        } else Spacer(Modifier.weight(1f))
                        if (!playerVisible) AnimatedVisibility(showPill || browsePaging,
                            Modifier.align(Alignment.CenterHorizontally).padding(end = if (mapVisible) 80.dp else 0.dp),
                            enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + shrinkVertically()) {
                            MediaFooter(currentTrack.takeIf { showPill }, browser, openPlayer, miniControl, Modifier, if (denseChrome) .96f else .64f)
                        }
                    }
                    CarSidebar(navigation, notices.size, openMap, exit, denseChrome, Modifier
                        .align(Alignment.CenterStart).padding(vertical = 8.dp).width(84.dp).fillMaxHeight())
                }
                if (navigation.projected && !navigation.notificationsOpen) {
                    CarNotificationBanner(notifications, { navigation.notificationsOpen = true },
                        Modifier.align(Alignment.TopCenter).padding(start = if (lyricsVisible) 24.dp else 108.dp, end = 24.dp, top = 16.dp))
                }
            }
        }
        if (choosingMap) AlertDialog(onDismissRequest = { choosingMap = false }, title = { Text(stringResource(R.string.navigation)) },
            text = {
                if (maps.isEmpty()) Text(stringResource(R.string.no_navigation_apps))
                else Column { maps.forEach { map ->
                    TextButton(onClick = { choosingMap = false; workspace.open(map) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(map.label) }
                } }
            }, confirmButton = { TextButton(onClick = { choosingMap = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun CarSidebar(navigation: CarNavigation, count: Int, openMap: () -> Unit, exit: () -> Unit, dense: Boolean, modifier: Modifier) {
    GlassSurface(modifier, RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 32.dp, bottomEnd = 32.dp),
        opacity = if (dense) .65f else .42f) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 380.dp
            val buttonSize = if (compact) 56.dp else 60.dp
            Column(Modifier.fillMaxSize().padding(vertical = if (compact) 8.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
                GlassIconButton(CarSymbol.Home, stringResource(R.string.home), navigation::home,
                    selected = navigation.selected == null && !navigation.player && !navigation.notificationsOpen, size = buttonSize, opacity = .12f)
                GlassIconButton(CarSymbol.Navigation, stringResource(R.string.navigation), openMap,
                    selected = navigation.selected?.kind == AppKind.MAP && !navigation.player && !navigation.notificationsOpen, size = buttonSize, opacity = .12f)
                Box {
                    GlassIconButton(CarSymbol.Bell, stringResource(R.string.notification_count, count),
                        { navigation.notificationsOpen = !navigation.notificationsOpen }, selected = navigation.notificationsOpen, size = buttonSize, opacity = .12f)
                    if (count > 0) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                }
                Spacer(Modifier.weight(1f))
                GlassIconButton(CarSymbol.Car, stringResource(R.string.return_to_car), exit, size = buttonSize, opacity = .12f)
                PhoneStatus()
                SidebarClock()
            }
        }
    }
}

@Composable
private fun SidebarClock() {
    val context = LocalContext.current
    val clock by produceState("") {
        while (true) {
            value = android.text.format.DateFormat.getTimeFormat(context).format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(clock, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
}

@Composable
private fun AppGrid(apps: List<CarApp>, open: (CarApp) -> Unit, modifier: Modifier) {
    if (apps.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_apps), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    BoxWithConstraints(modifier) {
        val iconSize = if (maxHeight < 340.dp) 96.dp else 112.dp
        LazyVerticalGrid(GridCells.Adaptive(122.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(apps, key = { it.component.flattenToString() }) { app ->
                val interactions = remember { MutableInteractionSource() }
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
                    .clickable(interactions, indication = null, role = Role.Button) { open(app) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassSurface(modifier = Modifier.size(iconSize), shape = RoundedCornerShape(26.dp), selected = focused,
                        interactionSource = interactions) {
                        PackageIcon(app.component.packageName, Modifier.fillMaxSize(), unmasked = true)
                    }
                    Text(app.label, maxLines = 2, minLines = 2, lineHeight = 20.sp, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun MediaFooter(track: NowPlaying?, browser: MediaLibrary?, open: () -> Unit, control: (Long) -> Boolean, modifier: Modifier, opacity: Float) {
    val page = browser?.page
    val paging = page != null && ((page.folder?.page ?: 0) > 0 || page.more)
    Row(modifier.widthIn(max = if (paging) 620.dp else 440.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (paging) GlassIconButton(CarSymbol.Back, stringResource(R.string.previous_page), { browser.changePage(-1) },
            enabled = !page.loading && (page.folder?.page ?: 0) > 0)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (track != null) MiniPlayer(track, open, control, Modifier.fillMaxWidth(), opacity)
            else Text(stringResource(R.string.media_page, (page?.folder?.page ?: 0) + 1), style = MaterialTheme.typography.titleMedium)
        }
        if (paging) GlassIconButton(CarSymbol.ChevronRight, stringResource(R.string.next_page), { browser.changePage(1) },
            enabled = !page.loading && page.more)
    }
}

@Composable
private fun MiniPlayer(track: NowPlaying, open: () -> Unit, control: (Long) -> Boolean, modifier: Modifier, opacity: Float) {
    val label = stringResource(R.string.now_playing)
    GlassSurface(modifier.widthIn(max = 440.dp).fillMaxWidth().height(64.dp)
        .semantics { contentDescription = "$label: ${track.title.orEmpty()}" }, onClick = open, shape = RoundedCornerShape(30.dp), opacity = opacity) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Artwork(track.artwork, Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)))
            Text(track.title ?: label, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            PlaybackControls(track, { control(it) }, size = 48.dp, plain = true)
        }
    }
}

@Composable
internal fun PackageIcon(packageName: String, modifier: Modifier = Modifier, unmasked: Boolean = false) {
    val context = LocalContext.current
    val icon by produceState<android.graphics.Bitmap?>(null, packageName, unmasked) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val image = if (unmasked && drawable is AdaptiveIconDrawable) LayerDrawable(arrayOf(drawable.background, drawable.foreground)) else drawable
                image.toBitmap(if (unmasked) 192 else 128, if (unmasked) 192 else 128)
            }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }
    }
    if (icon != null) ArtworkImage(icon, if (unmasked) modifier else modifier.clip(RoundedCornerShape(18.dp)))
    else Box(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        CarGlyph(CarSymbol.Veloce, Modifier.fillMaxSize(.6f), MaterialTheme.colorScheme.primary)
    }
}
