package ch.broillet.veloce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import androidx.car.app.model.SearchTemplate
import ch.broillet.veloce.media.ActiveMedia
import ch.broillet.veloce.media.NowPlaying
import ch.broillet.veloce.media.MediaLibrary
import ch.broillet.veloce.projection.ScreenSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val mapSource = maps.firstOrNull { it.component == host?.app?.component } ?: maps.singleOrNull()
    val audioSource = library?.app?.takeIf { it in audioApps && it.component.packageName == currentTrack?.packageName }
        ?: audioApps.firstOrNull { it.component.packageName == currentTrack?.packageName } ?: audioApps.singleOrNull()
    val openMap: () -> Unit = {
        choosingDashboard = false
        if (mapSource != null) workspace.open(mapSource) else choosingApp = AppKind.MAP
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
        if (audioSource != null) workspace.open(audioSource) else choosingApp = AppKind.AUDIO
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
    val album = rememberArtwork((if (playerVisible) playerTrack else currentTrack)?.artwork)
    val titlePackage = if (navigation.player) playerTrack?.packageName else selected?.component?.packageName
    val notice = selectedHost?.notice?.takeIf { appVisible }
    val browser = library?.takeIf { selected?.kind == AppKind.AUDIO && appVisible && !navigation.dashboard }
    val hostedPage = selectedHost?.takeIf { appVisible && !mapVisible }
    val searchInToolbar = browser?.supportsSearch == true || hostedPage?.template is SearchTemplate
    var mediaQuery by remember(library) { mutableStateOf("") }
    val browserState = rememberSaveableStateHolder()
    val browsePaging = browser?.page?.let { (it.folder?.page ?: 0) > 0 || it.more } == true
    val lyricsVisible = playerVisible && navigation.lyrics
    var appPageCount by remember { mutableIntStateOf(1) }
    val appPager = rememberPagerState { appPageCount }
    AlbumSceneTheme(album.takeUnless { mapBackdrop }, denseChrome) {
        Box(Modifier.fillMaxSize().cabinBackground()) {
            if (!mapBackdrop && LocalAppSettings.current.artworkBackground && album != null) {
                AlbumBackdrop(album, Modifier.fillMaxSize(), radius = if (playerVisible) 48.dp else 80.dp, subdued = !playerVisible)
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = horizontal, vertical = vertical)) {
                val splitVisible = canSplit && navigation.dashboard && mapVisible
                val mediaWidth = ((maxWidth - 112.dp) * .32f).coerceIn(264.dp, 340.dp)
                val showPill = !playerVisible && currentTrack != null && !splitVisible
                val mapFrame = if (splitVisible) Modifier.fillMaxSize()
                    .padding(start = 88.dp, top = 8.dp, end = mediaWidth + 16.dp, bottom = 8.dp)
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
                            mapInsets = if (splitVisible) PaddingValues(12.dp) else PaddingValues(start = 96.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                            bottomOverlay = if (showPill) 72.dp else 0.dp,
                            mapScene = mapScene.takeIf { mapBackdrop }, mapOverview = splitVisible)
                    }
                }
                if (splitVisible) CompactMediaPlayer(currentTrack, if (currentTrack != null) openPlayer else openAudio, openAudio, miniControl,
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp, top = 8.dp, bottom = 8.dp).width(mediaWidth).fillMaxHeight())
                val pageEntrance = Modifier.carEntrance(listOf(navigation.notificationsOpen, navigation.player, selected?.component))
                Column(Modifier.fillMaxSize().padding(start = 96.dp, top = 12.dp, end = 16.dp, bottom = 8.dp)
                    .then(if (mapVisible) Modifier else pageEntrance), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!mapVisible) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (selected != null || navigation.player || navigation.notificationsOpen) {
                                GlassIconButton(CarSymbol.Back, stringResource(R.string.back), { navigation.back(exit) })
                            }
                            if (titlePackage != null && !navigation.notificationsOpen) PackageIcon(titlePackage, Modifier.size(32.dp))
                            Column(if (searchInToolbar) Modifier.widthIn(max = 128.dp) else Modifier.weight(1f)) {
                                if (playerVisible) Text(if (lyricsVisible) playerTrack?.artist.orEmpty() else stringResource(R.string.now_playing),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(when {
                                    navigation.notificationsOpen -> stringResource(R.string.notifications)
                                    lyricsVisible -> playerTrack?.title ?: stringResource(R.string.lyrics)
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
                            if (selected == null && !navigation.player && !navigation.notificationsOpen) LauncherPageIndicator(appPager)
                            if (playerVisible) {
                                if (libraryPlayer != null && libraryPlayer.app in audioApps) GlassIconButton(CarSymbol.Library, stringResource(R.string.media_library),
                                    { workspace.open(libraryPlayer.app) })
                                GlassIconButton(if (lyricsVisible) CarSymbol.Close else CarSymbol.Lyrics,
                                    stringResource(if (lyricsVisible) R.string.close_lyrics else R.string.lyrics), { navigation.lyrics = !navigation.lyrics },
                                    selected = lyricsVisible, enabled = !playerTrack?.title.isNullOrBlank())
                            }
                        }
                        when {
                            navigation.notificationsOpen -> CarNotificationsContent(notifications, Modifier.fillMaxWidth().weight(1f))
                            lyricsVisible -> LyricsContent(playerTrack, app.lyrics, control,
                                { libraryPlayer?.seekTo(it) ?: media.seekTo(it, playerTrack?.packageName) }, Modifier.fillMaxWidth().weight(1f))
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
                            else -> CarAppPages(apps, workspace::open, exit, appPager, { appPageCount = it }, Modifier.fillMaxWidth().weight(1f))
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (!playerVisible && !splitVisible && (showPill || browsePaging)) MediaFooter(currentTrack.takeIf { showPill }, browser, openPlayer, miniControl,
                        Modifier.align(Alignment.CenterHorizontally), if (denseChrome) .9f else .72f)
                }
                CarSidebar(navigation, notices.size, openMap, openAudio, denseChrome, Modifier
                    .align(Alignment.CenterStart).padding(vertical = 8.dp).width(76.dp).fillMaxHeight(), splitVisible,
                    if (canSplit && maps.isNotEmpty()) toggleDashboard else null,
                    mapSource?.component?.packageName, audioSource?.component?.packageName)
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
                            Surface(onClick = {
                                choosingApp = null
                                if (choosingDashboard) workspace.openDashboard(choice) else workspace.open(choice)
                            }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.heightIn(min = 68.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    PackageIcon(choice.component.packageName, Modifier.size(44.dp))
                                    Text(choice.label, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        } }
                        TextButton(onClick = { choosingApp = null }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarSidebar(navigation: CarNavigation, count: Int, openMap: () -> Unit, openAudio: () -> Unit, dense: Boolean, modifier: Modifier,
    split: Boolean, toggleSplit: (() -> Unit)?, mapPackage: String?, audioPackage: String?) {
    GlassSurface(modifier, RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        opacity = if (dense) .88f else .72f, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 420.dp
            val buttonSize = if (compact) 48.dp else 56.dp
            val spacing = if (compact) 4.dp else 8.dp
            Column(Modifier.fillMaxSize().padding(vertical = if (compact) 8.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CarSidebarStatus(compact)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(spacing)) {
                        CarRailButton(CarSymbol.Home, stringResource(R.string.home), navigation::home,
                            selected = navigation.selected == null && !navigation.player && !navigation.notificationsOpen && !navigation.dashboard, size = buttonSize)
                        CarRailButton(CarSymbol.Navigation, stringResource(R.string.navigation), openMap,
                            selected = (navigation.dashboard || navigation.selected?.kind == AppKind.MAP && !navigation.player) && !navigation.notificationsOpen,
                            size = buttonSize, packageName = mapPackage)
                        CarRailButton(CarSymbol.Music, stringResource(R.string.media_apps), openAudio,
                            selected = (navigation.player || navigation.selected?.kind == AppKind.AUDIO) && !navigation.notificationsOpen && !navigation.dashboard,
                            size = buttonSize, packageName = audioPackage)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(spacing)) {
                    Box {
                        CarRailButton(CarSymbol.Bell, stringResource(R.string.notification_count, count),
                            { navigation.notificationsOpen = !navigation.notificationsOpen }, selected = navigation.notificationsOpen, size = buttonSize)
                        if (count > 0) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(7.dp)
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape))
                    }
                    if (toggleSplit != null) CarRailButton(if (split) CarSymbol.Fullscreen else CarSymbol.SplitScreen,
                        stringResource(if (split) R.string.fullscreen_view else R.string.split_screen), toggleSplit, selected = split, size = buttonSize)
                }
            }
        }
    }
}

@Composable
private fun CarRailButton(symbol: CarSymbol, label: String, click: () -> Unit, selected: Boolean, size: Dp, packageName: String? = null) {
    NavigationRailItem(selected = selected, onClick = click,
        modifier = Modifier.size(size).semantics { contentDescription = label },
        icon = {
            if (packageName != null) PackageIcon(packageName, Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)), unmasked = true)
            else CarGlyph(symbol, Modifier.size(28.dp), LocalContentColor.current)
        },
        colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
private fun MediaFooter(track: NowPlaying?, browser: MediaLibrary?, open: () -> Unit, control: (Long) -> Boolean, modifier: Modifier, opacity: Float) {
    val page = browser?.page
    val paging = page != null && ((page.folder?.page ?: 0) > 0 || page.more)
    Row(modifier.widthIn(max = if (paging) 620.dp else 440.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (paging) GlassIconButton(CarSymbol.PagePrevious, stringResource(R.string.previous_page), { browser.changePage(-1) },
            enabled = !page.loading && (page.folder?.page ?: 0) > 0)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (track != null) MiniPlayer(track, open, control, Modifier.fillMaxWidth(), opacity)
            else Text(stringResource(R.string.media_page, (page?.folder?.page ?: 0) + 1), style = MaterialTheme.typography.titleMedium)
        }
        if (paging) GlassIconButton(CarSymbol.PageNext, stringResource(R.string.next_page), { browser.changePage(1) },
            enabled = !page.loading && page.more)
    }
}

@Composable
private fun MiniPlayer(track: NowPlaying, open: () -> Unit, control: (Long) -> Boolean, modifier: Modifier, opacity: Float) {
    val label = stringResource(R.string.now_playing)
    val artwork = rememberArtwork(track.artwork)
    AlbumSceneTheme(artwork, dense = false) {
        GlassSurface(modifier.widthIn(max = 440.dp).fillMaxWidth().height(68.dp)
            .semantics { contentDescription = "$label: ${track.title.orEmpty()}" }, onClick = open, shape = CircleShape,
            opacity = opacity, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            if (LocalAppSettings.current.artworkBackground && artwork != null) AlbumBackdrop(artwork, Modifier.matchParentSize(), radius = 24.dp)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val showArtwork = maxWidth >= 340.dp
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showArtwork) ArtworkImage(artwork, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                    Text(track.title ?: label, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PlaybackControls(track, { control(it) }, size = 48.dp, playSize = 52.dp, plain = true)
                }
            }
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
