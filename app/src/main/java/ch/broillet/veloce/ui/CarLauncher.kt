package ch.broillet.veloce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.R
import ch.broillet.veloce.VeloceApplication
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.apps.visibleInCar
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
import android.text.format.DateFormat
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
    var dashboard by mutableStateOf(false)
    var appBack: (() -> Unit)? = null
    var dismissKeyboard: (() -> Unit)? = null
    var mediaKey: ((Int) -> Unit)? = null
    fun home() { dismissKeyboard?.invoke(); selected = null; player = false; notificationsOpen = false; lyrics = false; dashboard = false }
    fun back(exit: () -> Unit) {
        dismissKeyboard?.let { it(); return }
        if (notificationsOpen) { notificationsOpen = false; return }
        if (dashboard) { dashboard = false; return }
        if (lyrics) { lyrics = false; return }
        if (player) { player = false; return }
        val hostedBack = appBack
        if (selected != null && hostedBack != null) hostedBack() else if (selected != null) home() else exit()
    }
}

@Composable
fun CarLauncher(spec: ScreenSpec, apps: List<CarApp>, navigation: CarNavigation, media: ActiveMedia, exit: () -> Unit) {
    val app = LocalContext.current.applicationContext as VeloceApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val visibleApps = remember(apps, settings.hiddenAppPackages) { apps.visibleInCar(settings) }
    CarTheme(settings) { CarKeyboardHost(navigation) { CarLauncherContent(spec, visibleApps, navigation, media, exit) } }
}

@Composable
private fun CarLauncherContent(spec: ScreenSpec, apps: List<CarApp>, navigation: CarNavigation, media: ActiveMedia, exit: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VeloceApplication
    val notifications = app.notifications
    val notices by notifications.notices.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val displayWidth = with(density) { (spec.width - spec.cropX).toDp() }
    val displayHeight = with(density) { (spec.height - spec.cropY).toDp() }
    val canSplit = displayWidth >= 800.dp && displayHeight >= 420.dp && displayWidth > displayHeight
    val workspace = remember(context, navigation, canSplit) {
        CarWorkspace(context, navigation, app.connections::log, canSplit)
    }
    DisposableEffect(workspace) { onDispose { workspace.close() } }
    val selected = navigation.selected
    val host = workspace.host
    val selectedHost = host?.takeIf { navigation.dashboard || selected?.component == it.app.component }
    val library = workspace.library
    val playerVisible = navigation.player && !navigation.notificationsOpen && !navigation.dashboard
    val appVisible = (!navigation.player || navigation.dashboard) && !navigation.notificationsOpen
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
    var choosingApp by remember { mutableStateOf<AppKind?>(null) }
    var choosingDashboard by remember { mutableStateOf(false) }
    val maps = remember(apps) { apps.filter { it.kind == AppKind.MAP } }
    val audioApps = remember(apps) { apps.filter { it.kind == AppKind.AUDIO } }
    val openMap: () -> Unit = {
        choosingDashboard = false
        val previous = maps.firstOrNull { it.component == host?.app?.component }
        when {
            previous != null -> workspace.open(previous)
            maps.size == 1 -> workspace.open(maps.first())
            else -> choosingApp = AppKind.MAP
        }
    }
    val openPlayer: () -> Unit = {
        if (selected?.kind != AppKind.MAP) {
            if (library != null && library.app in audioApps && library.app.component.packageName == currentTrack?.packageName) workspace.open(library.app)
            else navigation.home()
        }
        navigation.player = true
        navigation.dashboard = false
    }
    val openAudio: () -> Unit = {
        choosingDashboard = false
        val source = library?.app?.takeIf { it in audioApps && it.component.packageName == currentTrack?.packageName }
            ?: audioApps.firstOrNull { it.component.packageName == currentTrack?.packageName }
        when {
            source != null -> workspace.open(source)
            audioApps.size == 1 -> workspace.open(audioApps.first())
            else -> choosingApp = AppKind.AUDIO
        }
    }
    val toggleDashboard: () -> Unit = {
        if (navigation.dashboard && !navigation.notificationsOpen) workspace.setSplitLayout(false)
        else {
            val map = maps.firstOrNull { it.component == host?.app?.component } ?: maps.singleOrNull()
            if (map != null) workspace.openDashboard(map)
            else { choosingDashboard = true; choosingApp = AppKind.MAP }
        }
    }
    val control: (Long) -> Boolean = { action ->
        libraryPlayer?.control(action) ?: media.control(action, playerTrack?.packageName)
    }
    val miniLibrary = library?.takeIf { currentTrack != null && currentTrack === it.nowPlaying }
    val miniControl: (Long) -> Boolean = { action -> miniLibrary?.control(action) ?: media.control(action, currentTrack?.packageName) }
    LaunchedEffect(host, selected, navigation.player, navigation.dashboard, navigation.projected, navigation.notificationsOpen) { workspace.updateVisibility() }
    LaunchedEffect(navigation) {
        snapshotFlow { "${navigation.selected?.label ?: "Home"} · player=${navigation.player}, lyrics=${navigation.lyrics}, dashboard=${navigation.dashboard}, notifications=${navigation.notificationsOpen}" }
            .collect { app.connections.log("Car screen: $it") }
    }
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
    val mapVisible = appVisible && selectedHost != null && selectedHost.error == null &&
        (selectedHost.template.hasMapSurface() || navigation.dashboard)
    val mapScene = remember(host) { MapScene() }
    val mapBackdrop = appVisible && selectedHost?.app?.kind == AppKind.MAP && selectedHost.error == null
    val denseChrome = mapVisible || mapBackdrop
    val album = rememberArtwork(playerTrack?.artwork.takeIf { playerVisible })
    val titlePackage = if (navigation.player) playerTrack?.packageName else selected?.component?.packageName
    val notice = selectedHost?.notice?.takeIf { appVisible }
    val browser = library?.takeIf { selected?.kind == AppKind.AUDIO && appVisible && !navigation.dashboard }
    val hostedPage = selectedHost?.takeIf { appVisible && !mapVisible }
    val searchInToolbar = browser?.supportsSearch == true || hostedPage?.template is SearchTemplate
    var mediaQuery by remember(library) { mutableStateOf("") }
    val browserState = rememberSaveableStateHolder()
    val browsePaging = browser?.page?.let { (it.folder?.page ?: 0) > 0 || it.more } == true
    val lyricsVisible = playerVisible && navigation.lyrics
    AlbumSceneTheme(album, denseChrome) {
        Box(Modifier.fillMaxSize().cabinBackground()) {
            if (playerVisible && LocalAppSettings.current.artworkBackground) AlbumBackdrop(album, Modifier.fillMaxSize())
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = horizontal, vertical = vertical)) {
                val splitVisible = canSplit && navigation.dashboard && mapVisible
                val mediaWidth = ((maxWidth - 120.dp) * .32f).coerceIn(240.dp, 340.dp)
                val showPill = !playerVisible && currentTrack != null && !splitVisible
                 if (lyricsVisible) LyricsContent(playerTrack, album, app.lyrics, control,
                     { libraryPlayer?.seekTo(it) ?: media.seekTo(it, playerTrack?.packageName) }, { navigation.lyrics = false },
                     split = toggleDashboard.takeIf { canSplit && maps.isNotEmpty() })
                else {
                    val mapFrame = if (splitVisible) Modifier.fillMaxSize()
                        .padding(start = 96.dp, top = 12.dp, end = mediaWidth + 24.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface)
                        else Modifier.fillMaxSize().clipToBounds()
                    Box(mapFrame) {
                        if (mapBackdrop) key(selectedHost) {
                            MapSurface(selectedHost, Modifier.fillMaxSize(), mapScene.visibleInsets, mapScene.stableInsets,
                                onPanStart = { mapScene.panStart() }, onPanEnd = { mapScene.panEnd() }, blurred = !mapVisible,
                                interactive = mapVisible && !splitVisible)
                            if (!mapVisible) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)))
                        }
                        if (splitVisible) {
                            val label = stringResource(R.string.fullscreen_map)
                            Box(Modifier.matchParentSize().semantics { contentDescription = label }
                                .clickable(role = Role.Button, onClickLabel = label) { workspace.open(selectedHost.app); workspace.setSplitLayout(false) })
                        }
                        if (mapVisible) key(selectedHost) {
                            CarAppContent(selectedHost, navigation::home, Modifier.fillMaxSize(),
                                mapInsets = if (splitVisible) PaddingValues(12.dp) else PaddingValues(start = 104.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                                bottomOverlay = if (showPill) 68.dp else 0.dp, mapScene = mapScene.takeIf { mapBackdrop }, mapOverview = splitVisible)
                        }
                    }
                    if (splitVisible) CompactMediaPlayer(currentTrack, if (currentTrack != null) openPlayer else openAudio, openAudio, miniControl,
                        Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 12.dp, bottom = 12.dp).width(mediaWidth).fillMaxHeight())
                    val pageEntrance = Modifier.carEntrance(listOf(navigation.notificationsOpen, navigation.player, selected?.component))
                    Column(Modifier.fillMaxSize().padding(start = 108.dp, top = 16.dp, end = 24.dp, bottom = 16.dp)
                        .then(if (mapVisible) Modifier else pageEntrance),
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
                                if (browser?.supportsSearch == true) {
                                    val search = { browser.search(mediaQuery) }
                                    CarSearchField(mediaQuery, { mediaQuery = it.take(200) }, stringResource(R.string.search), browser::search, Modifier.weight(1f))
                                    GlassIconButton(CarSymbol.Search, stringResource(R.string.search), search, enabled = mediaQuery.isNotBlank())
                                }
                                if (hostedPage != null) HostedToolbarActions(hostedPage)
                                if (notice != null) HostNotice(notice, host::dismissNotice, Modifier.widthIn(max = if (searchInToolbar) 208.dp else 280.dp))
                                if (navigation.notificationsOpen && notices.isNotEmpty()) Text(notices.size.toString(),
                                    style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                if (playerVisible) {
                                    if (libraryPlayer != null && libraryPlayer.app in audioApps) GlassIconButton(CarSymbol.Library, stringResource(R.string.media_library),
                                        { workspace.open(libraryPlayer.app) }, size = 60.dp)
                                    GlassIconButton(CarSymbol.Lyrics, stringResource(R.string.lyrics), { navigation.lyrics = true }, size = 60.dp,
                                        enabled = !playerTrack?.title.isNullOrBlank())
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
                                else -> AppGrid(apps, workspace::open, exit, Modifier.fillMaxWidth().weight(1f))
                            }
                        } else Spacer(Modifier.weight(1f))
                        if (!playerVisible && (showPill || browsePaging)) MediaFooter(currentTrack.takeIf { showPill }, browser, openPlayer, miniControl,
                            Modifier.align(Alignment.CenterHorizontally).padding(end = if (mapVisible) 80.dp else if (browser != null) 64.dp else 0.dp), if (denseChrome) .96f else .64f)
                    }
                    CarSidebar(navigation, notices.size, openMap, denseChrome, Modifier
                        .align(Alignment.CenterStart).padding(vertical = 8.dp).width(84.dp).fillMaxHeight(), splitVisible,
                        if (canSplit && maps.isNotEmpty()) toggleDashboard else null)
                    if (browser != null) CarRefreshButton(browser::refresh,
                        Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 16.dp),
                        enabled = browser.page.folder != null && !browser.page.loading)
                }
                if (navigation.projected && !navigation.notificationsOpen && LocalAppSettings.current.notificationBanners) {
                    CarNotificationBanner(notifications, { navigation.notificationsOpen = true },
                        Modifier.align(Alignment.TopCenter).padding(start = if (lyricsVisible) 24.dp else 108.dp, end = 24.dp, top = 16.dp))
                }
            }
        }
        choosingApp?.let { kind ->
            val choices = if (kind == AppKind.MAP) maps else audioApps
            CarOverlay({ choosingApp = null }) {
                Surface(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(if (kind == AppKind.MAP) R.string.navigation else R.string.choose_audio_app), style = MaterialTheme.typography.headlineSmall)
                        if (choices.isEmpty()) Text(stringResource(if (kind == AppKind.MAP) R.string.no_navigation_apps else R.string.no_audio_apps))
                        else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { choices.forEach { choice ->
                            TextButton(onClick = {
                                choosingApp = null
                                if (choosingDashboard) workspace.openDashboard(choice) else workspace.open(choice)
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(choice.label) }
                        } }
                        TextButton(onClick = { choosingApp = null }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarSidebar(navigation: CarNavigation, count: Int, openMap: () -> Unit, dense: Boolean, modifier: Modifier,
    split: Boolean, toggleSplit: (() -> Unit)?) {
    GlassSurface(modifier, RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 32.dp, bottomEnd = 32.dp),
        opacity = if (dense) .65f else .42f) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 480.dp
            val buttonSize = if (maxHeight < 400.dp) 60.dp else if (compact) 68.dp else 72.dp
            Column(Modifier.fillMaxSize().padding(vertical = if (compact) 8.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
                GlassIconButton(CarSymbol.Home, stringResource(R.string.home), navigation::home,
                    selected = navigation.selected == null && !navigation.player && !navigation.notificationsOpen && !navigation.dashboard, size = buttonSize, iconSize = 32.dp, opacity = .12f)
                GlassIconButton(CarSymbol.Navigation, stringResource(R.string.navigation), openMap,
                    selected = (navigation.dashboard || navigation.selected?.kind == AppKind.MAP && !navigation.player) && !navigation.notificationsOpen, size = buttonSize, iconSize = 32.dp, opacity = .12f)
                Box {
                    GlassIconButton(CarSymbol.Bell, stringResource(R.string.notification_count, count),
                        { navigation.notificationsOpen = !navigation.notificationsOpen }, selected = navigation.notificationsOpen, size = buttonSize, iconSize = 32.dp, opacity = .12f)
                    if (count > 0) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                }
                if (toggleSplit != null) GlassIconButton(if (split) CarSymbol.Fullscreen else CarSymbol.SplitScreen,
                    stringResource(if (split) R.string.fullscreen_view else R.string.split_screen), toggleSplit, size = buttonSize, iconSize = 32.dp, opacity = .12f)
                Spacer(Modifier.weight(1f))
                Column(Modifier.padding(horizontal = 4.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SidebarClock()
                    PhoneStatus()
                }
            }
        }
    }
}

@Composable
private fun SidebarClock() {
    val context = LocalContext.current
    val clock by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    val is24Hour = LocalAppSettings.current.clockFormat.is24Hour(context)
    val label = DateFormat.format(if (is24Hour) "HH:mm" else "h:mm a", clock).toString()
    val digits = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
    Column(Modifier.clearAndSetSemantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(DateFormat.format(if (is24Hour) "HH:mm" else "h:mm", clock).toString(), style = digits,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        if (!is24Hour) Text(DateFormat.format("a", clock).toString(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, lineHeight = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun AppGrid(apps: List<CarApp>, open: (CarApp) -> Unit, exit: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val iconSize = if (maxHeight < 340.dp) 96.dp else 112.dp
        LazyVerticalGrid(GridCells.Adaptive(122.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (apps.isEmpty()) item(key = "no_apps", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.no_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(apps, key = { it.component.flattenToString() }) { app ->
                AppGridItem(app.label, { open(app) }, iconSize) {
                    PackageIcon(app.component.packageName, Modifier.fillMaxSize(), unmasked = true)
                }
            }
            item(key = "exit_to_car") {
                AppGridItem(stringResource(R.string.return_to_car), exit, iconSize) {
                    CarGlyph(CarSymbol.Car, Modifier.size(iconSize * .55f).align(Alignment.Center), MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun AppGridItem(label: String, open: () -> Unit, iconSize: Dp, icon: @Composable BoxScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
        .clickable(interactions, indication = null, role = Role.Button, onClick = open),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassSurface(modifier = Modifier.size(iconSize), shape = RoundedCornerShape(26.dp), selected = focused,
            interactionSource = interactions, content = icon)
        Text(label, maxLines = 2, minLines = 2, lineHeight = 20.sp, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
