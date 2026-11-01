@file:Suppress("RestrictedApi", "DEPRECATION")

package ch.broillet.veloce.hosting

import androidx.car.app.model.*
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.navigation.model.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ch.broillet.veloce.ui.GlassSurface
import ch.broillet.veloce.ui.GlassIconButton
import ch.broillet.veloce.ui.CarSymbol
import ch.broillet.veloce.ui.CarGlyph
import ch.broillet.veloce.ui.CarTextButton
import ch.broillet.veloce.ui.LocalGlassOpacity
import ch.broillet.veloce.ui.carEntrance
import ch.broillet.veloce.ui.HostNotice
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

@Composable
internal fun CarAppContent(host: CarAppHost, home: () -> Unit, modifier: Modifier, mapInsets: PaddingValues = PaddingValues(12.dp),
    headerInToolbar: Boolean = false, bottomOverlay: Dp = 0.dp, mapScene: MapScene? = null) {
    val configuration = android.content.res.Configuration(LocalConfiguration.current)
    LaunchedEffect(host, configuration) { host.updateConfiguration(configuration) }
    CompositionLocalProvider(LocalGlassOpacity provides .96f) {
        Box(modifier) {
            val failure = host.error
            val template = host.template
            when {
                failure != null -> Column(Modifier.fillMaxSize().padding(mapInsets).padding(16.dp), verticalArrangement = Arrangement.Center) {
                    Text("Could not open ${host.app.label}", style = MaterialTheme.typography.titleLarge)
                    Text(failure, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Text("Check the app’s host-validation patch and Car App Library compatibility. Details are in the connection log.")
                    CarTextButton("Home", home)
                }
                template == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> TemplateContent(template, host, Modifier.fillMaxSize(), mapInsets, !headerInToolbar, bottomOverlay, mapScene)
            }
        }
        host.alert?.let { alert ->
            Dialog(onDismissRequest = { host.dismissAlert(AlertCallback.REASON_USER_ACTION) }) {
                GlassSurface(opacity = .99f) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            CarGlyph(CarSymbol.Warning, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
                            Text(alert.title.text(), style = MaterialTheme.typography.headlineSmall)
                        }
                        if (alert.subtitle.text().isNotBlank()) Text(alert.subtitle.text(), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (alert.actions.isEmpty()) CarTextButton("OK", { host.dismissAlert() })
                            else alert.actions.forEach { action ->
                                ActionButton(action, host) { host.click(action); host.dismissAlert() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
internal fun TemplateContent(template: Template, host: CarAppHost, modifier: Modifier, mapInsets: PaddingValues = PaddingValues(12.dp),
    showHeader: Boolean = true, bottomOverlay: Dp = 0.dp, mapScene: MapScene? = null) {
    if (template.hasMapSurface()) {
        MapTemplateContent(template, host, modifier, mapInsets, bottomOverlay, mapScene)
        return
    }
    when (template) {
        is ListTemplate -> Column(modifier) {
            if (showHeader) TemplateHeader(template.header, template.title, template.headerAction, host)
            Loading(template.isLoading)
            if (template.singleList != null) ItemListContent(template.singleList, host, Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f)) {
                template.sectionedLists.forEach { section ->
                    item { Text(section.header.text(), Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium) }
                    itemsIndexed(section.itemList.items) { index, item -> ListItemContent(item, section.itemList, index, host) }
                }
            }
            Actions(footerActions(template.header, template.actionStrip, template.actions), host)
        }
        is GridTemplate -> Column(modifier) {
            if (showHeader) TemplateHeader(template.header, template.title, template.headerAction, host)
            Loading(template.isLoading)
            LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                items(template.singleList?.items.orEmpty()) { item ->
                    if (item is GridItem) GlassSurface(onClick = { host.click(item.onClickDelegate) }) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CarImage(item.image, host, Modifier.size(40.dp))
                            Loading(item.isLoading)
                            Text(item.title.text(), maxLines = 2, style = MaterialTheme.typography.titleMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
                            if (item.text.text().isNotBlank()) Text(item.text.text(), maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Actions(footerActions(template.header, template.actionStrip, template.actions), host)
        }
        is PaneTemplate -> Column(modifier) {
            if (showHeader) TemplateHeader(template.header, template.title, template.headerAction, host)
            PaneContent(template.pane, host, Modifier.weight(1f), template.header?.endHeaderActions.orEmpty())
            Actions(footerActions(template.header, template.actionStrip), host)
        }
        is MessageTemplate -> Column(modifier) {
            if (showHeader) TemplateHeader(template.header, template.title, template.headerAction, host)
            Loading(template.isLoading)
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.Center) {
                CarImage(template.icon, host)
                Text(template.message.text(), style = MaterialTheme.typography.titleLarge)
            }
            Actions(footerActions(template.header, template.actionStrip, template.actions), host)
        }
        is LongMessageTemplate -> Column(modifier) {
            if (showHeader) TemplateHeader(null, template.title, template.headerAction, host)
            LazyColumn(Modifier.weight(1f)) { item { Text(template.message.text(), Modifier.padding(16.dp)) } }
            Actions(template.actions + template.actionStrip?.actions.orEmpty(), host)
        }
        is SearchTemplate -> SearchContent(template, host, modifier, showHeader)
        is TabTemplate -> Column(modifier) {
            if (showHeader) Actions(listOfNotNull(template.headerAction), host)
            Loading(template.isLoading)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                template.tabs.forEach { tab ->
                    GlassSurface(onClick = { host.event { template.tabCallbackDelegate.sendTabSelected(tab.contentId, it) } },
                        selected = tab.contentId == template.activeTabContentId) {
                        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CarImage(tab.icon, host)
                            Text(tab.title.text(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            if (!template.isLoading) TemplateContent(template.tabContents.template, host, Modifier.weight(1f), showHeader = showHeader)
        }
        is SignInTemplate -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            if (showHeader) Text(template.title.text(), style = MaterialTheme.typography.titleLarge)
            Text(template.instructions.text())
            Text("Complete sign-in on your phone while parked, then reopen this app.")
        }
        else -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text("Unsupported car template: ${template.javaClass.simpleName}")
            Text("This app needs host functionality that Veloce has not implemented yet.")
        }
    }
}

internal fun footerActions(header: Header?, strip: ActionStrip?, actions: List<Action> = emptyList()): List<Action> =
    (actions + strip?.actions.orEmpty()).distinct().filterNot { it in header?.endHeaderActions.orEmpty() }

@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
internal fun Template?.hasMapSurface(): Boolean = when (this) {
    is NavigationTemplate, is MapWithContentTemplate, is MapTemplate, is PlaceListNavigationTemplate, is RoutePreviewNavigationTemplate -> true
    else -> false
}

@Composable
@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
private fun MapTemplateContent(template: Template, host: CarAppHost, modifier: Modifier, insets: PaddingValues, bottomOverlay: Dp, mapScene: MapScene?) {
    val navigation = template as? NavigationTemplate
    val content = (template as? MapWithContentTemplate)?.contentTemplate
    val controller = when (template) {
        is MapWithContentTemplate -> template.mapController
        is MapTemplate -> template.mapController
        else -> null
    }
    val actions = when (template) {
        is NavigationTemplate -> template.actionStrip
        is MapWithContentTemplate -> template.actionStrip
        is MapTemplate -> template.actionStrip
        is PlaceListNavigationTemplate -> template.actionStrip
        is RoutePreviewNavigationTemplate -> template.actionStrip
        else -> null
    }?.actions.orEmpty()
    val mapActions = when (template) {
        is NavigationTemplate -> template.mapActionStrip
        is PlaceListNavigationTemplate -> template.mapActionStrip
        is RoutePreviewNavigationTemplate -> template.mapActionStrip
        else -> controller?.mapActionStrip
    }?.actions.orEmpty()
    val pan = when (template) {
        is NavigationTemplate -> template.panModeDelegate
        is PlaceListNavigationTemplate -> template.panModeDelegate
        is RoutePreviewNavigationTemplate -> template.panModeDelegate
        else -> controller?.panModeDelegate
    }
    val panToggle = navigation?.panModeToggle
    val travel = navigation?.destinationTravelEstimate
    var panning by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val primary = actions.filter { it.type != Action.TYPE_PAN }.distinct()
    val mapButtons = mapActions.filter { it.type != Action.TYPE_PAN && it !in primary }.distinct()
    val hasPan = (actions + mapActions).any { it.type == Action.TYPE_PAN }
    val setPanning: (Boolean) -> Unit = { enabled ->
        if (panning != enabled) {
            panning = enabled
            if (panToggle != null) host.event { panToggle.onCheckedChangeDelegate.sendCheckedChange(enabled, it) }
            else pan?.let { delegate -> host.event { delegate.sendPanModeChanged(enabled, it) } }
        }
    }
    val finishPan by rememberUpdatedState { setPanning(false) }
    LaunchedEffect(panning, touching) {
        if (panning && !touching) { delay(8_000); finishPan() }
    }
    DisposableEffect(host, mapScene) { onDispose {
        finishPan()
        mapScene?.panStart = {}
        mapScene?.panEnd = {}
    } }
    val noticeSpace = if (host.notice != null) 76.dp else 0.dp
    val topHeight = noticeSpace + if (primary.isNotEmpty()) 64.dp else 0.dp
    var infoWidth by remember { mutableStateOf(0.dp) }
    var travelWidth by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(modifier) {
        val start = insets.calculateStartPadding(direction)
        val end = insets.calculateEndPadding(direction)
        val availableWidth = (maxWidth - start - end).coerceAtLeast(0.dp)
        val navigationWidth = minOf(360.dp, availableWidth * .56f)
        val actionWidth = if (navigation != null) minOf(320.dp, (availableWidth - navigationWidth - 12.dp).coerceAtLeast(64.dp))
            else minOf(maxWidth * .5f, 320.dp)
        val panelWidth = if (navigation == null) minOf(availableWidth * .42f, 340.dp) else 0.dp
        val panelStart = start + if (panelWidth > 0.dp) panelWidth + 12.dp else 0.dp
        val sideWidth = if (navigation != null) maxOf(infoWidth, if (travel != null) travelWidth else 0.dp) else 0.dp
        val visibleInsets = PaddingValues(
            start = panelStart + if (sideWidth > 0.dp) sideWidth + 12.dp else 0.dp,
            top = insets.calculateTopPadding() + topHeight,
            end = end + if (mapButtons.isNotEmpty()) 80.dp else 0.dp,
            bottom = insets.calculateBottomPadding() + bottomOverlay,
        )
        val stableStart = if (navigation != null) maxOf(visibleInsets.calculateStartPadding(direction), start + navigationWidth + 12.dp)
            else visibleInsets.calculateStartPadding(direction)
        val stableInsets = PaddingValues(start = stableStart, top = insets.calculateTopPadding() + maxOf(topHeight, 64.dp),
            end = end + 80.dp, bottom = insets.calculateBottomPadding() + bottomOverlay)
        val startPan = { touching = true; if (hasPan && panToggle?.isEnabled != false) setPanning(true) }
        val endPan = { touching = false }
        if (mapScene == null) MapSurface(host, Modifier.fillMaxSize(), visibleInsets, stableInsets, startPan, endPan)
        else SideEffect {
            mapScene.visibleInsets = visibleInsets
            mapScene.stableInsets = stableInsets
            mapScene.panStart = startPan
            mapScene.panEnd = endPan
        }
        Row(Modifier.fillMaxSize().padding(insets).carEntrance(content?.javaClass ?: template.javaClass),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (panelWidth > 0.dp) GlassSurface(Modifier.width(panelWidth).padding(bottom = bottomOverlay).fillMaxHeight(), RoundedCornerShape(28.dp)) {
                Box(Modifier.padding(8.dp)) {
                    MapPanel(template, host)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        Box(Modifier.widthIn(max = navigationWidth).onSizeChanged { infoWidth = with(density) { it.width.toDp() } }) {
                            if (navigation != null) NavigationInfo(navigation, host)
                        }
                    }
                    val actionOffset by animateDpAsState(noticeSpace, tween(220), label = "Map notice space")
                    Box(Modifier.widthIn(max = actionWidth).height(topHeight)) {
                        host.notice?.let { message ->
                            HostNotice(message, host::dismissNotice, Modifier.fillMaxWidth().carEntrance(message))
                        }
                        Row(Modifier.align(Alignment.TopEnd).graphicsLayer { translationY = actionOffset.toPx() }
                            .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            primary.forEach { ActionButton(it, host, compact = true) }
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomEnd).padding(top = topHeight + 12.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    mapButtons.forEach { ActionButton(it, host, compact = true) }
                }
                if (travel != null) Box(Modifier.align(Alignment.BottomStart).padding(bottom = bottomOverlay).widthIn(max = 340.dp)
                    .onSizeChanged { travelWidth = with(density) { it.width.toDp() } }) { TravelInfo(travel) }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
private fun MapPanel(template: Template, host: CarAppHost) {
    Column(Modifier.fillMaxSize()) {
        when (template) {
            is MapWithContentTemplate -> TemplateContent(template.contentTemplate, host, Modifier.fillMaxSize())
            is MapTemplate -> {
                TemplateHeader(template.header, null, null, host)
                template.pane?.let { PaneContent(it, host, Modifier.weight(1f), template.header?.endHeaderActions.orEmpty()) }
                template.itemList?.let { ItemListContent(it, host, Modifier.weight(1f)) }
            }
            is PlaceListNavigationTemplate -> {
                TemplateHeader(template.header, template.title, template.headerAction, host)
                Loading(template.isLoading)
                template.onContentRefreshDelegate?.let { delegate ->
                    CarTextButton("Refresh", { host.event { delegate.sendContentRefreshRequested(it) } })
                }
                ItemListContent(template.itemList, host, Modifier.weight(1f))
            }
            is RoutePreviewNavigationTemplate -> {
                TemplateHeader(template.header, template.title, template.headerAction, host)
                Loading(template.isLoading)
                ItemListContent(template.itemList, host, Modifier.weight(1f))
                template.navigateAction?.let { ActionButton(it, host) }
            }
        }
    }
}

@Composable
private fun NavigationInfo(template: NavigationTemplate, host: CarAppHost) {
    if (template.navigationInfo == null) return
    GlassSurface(Modifier.widthIn(max = 360.dp), RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val info = template.navigationInfo) {
                is RoutingInfo -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Loading(info.isLoading)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CarImage(info.currentStep?.maneuver?.icon, host, Modifier.size(52.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (info.currentDistance != null) Text(info.currentDistance.distance(), style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Text(info.currentStep?.cue.text(), fontSize = 21.sp, lineHeight = 27.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    info.currentStep?.lanesImage?.let { CarImage(it, host, Modifier.fillMaxWidth().height(48.dp)) }
                    info.currentStep?.road?.let { Text(it.text(), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                is MessageInfo -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.title.text(), style = MaterialTheme.typography.titleLarge)
                    Text(info.text.text(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun TravelInfo(estimate: TravelEstimate) {
    GlassSurface {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (estimate.remainingTimeSeconds >= 0) Text("${(estimate.remainingTimeSeconds + 59) / 60} min",
                    style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(estimate.remainingDistance.distance(), style = MaterialTheme.typography.titleLarge)
            }
            if (estimate.tripText.text().isNotBlank()) Text(estimate.tripText.text(), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TemplateHeader(header: Header?, title: CarText?, action: Action?, host: CarAppHost) {
    val label = header?.title ?: title
    val leading = header?.startHeaderAction ?: action
    if (label == null && leading == null && header?.endHeaderActions.isNullOrEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.let { ActionButton(it, host) }
        Text(label.text(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        header?.endHeaderActions?.forEach { ActionButton(it, host) }
    }
}

@Composable
private fun ItemListContent(list: ItemList?, host: CarAppHost, modifier: Modifier) {
    if (list == null) { Spacer(modifier); return }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(list, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }.collect { visible ->
            if (visible.isNotEmpty()) list.onItemVisibilityChangedDelegate?.let { delegate ->
                host.event { delegate.sendItemVisibilityChanged(visible.first(), visible.last(), it) }
            }
        }
    }
    LazyColumn(modifier, state = state) {
        if (list.items.isEmpty()) item { Text(list.noItemsMessage.text(), Modifier.padding(16.dp)) }
        itemsIndexed(list.items) { index, item -> ListItemContent(item, list, index, host) }
    }
}

@Composable
private fun ListItemContent(item: Item, list: ItemList?, index: Int, host: CarAppHost) {
    if (item !is Row) { Text("Unsupported item: ${item.javaClass.simpleName}"); return }
    val selection = list?.onSelectedDelegate
    val clickable = selection != null || item.onClickDelegate != null
    GlassSurface(onClick = {
        if (selection != null) host.event { selection.sendSelected(index, it) } else host.click(item.onClickDelegate)
    }, enabled = item.isEnabled && clickable, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).heightIn(min = 80.dp),
        shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (selection != null) RadioButton(selected = list.selectedIndex == index, onClick = null)
            CarImage(item.image, host, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title.text(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.texts.forEach { Text(it.text(), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            item.toggle?.let { toggle ->
                key(host.template.pageTitle(), item.title.text()) {
                    var checked by remember(toggle.isChecked) { mutableStateOf(toggle.isChecked) }
                    Switch(checked, { value ->
                        checked = value
                        host.event { toggle.onCheckedChangeDelegate.sendCheckedChange(value, it) }
                    }, enabled = toggle.isEnabled)
                }
            }
            item.actions.forEach { ActionButton(it, host) }
        }
    }
}

@Composable
private fun PaneContent(pane: Pane, host: CarAppHost, modifier: Modifier, headerActions: List<Action> = emptyList()) {
    Column(modifier) {
        Loading(pane.isLoading)
        LazyColumn(Modifier.weight(1f)) {
            item { CarImage(pane.image, host) }
            itemsIndexed(pane.rows) { index, row -> ListItemContent(row, null, index, host) }
        }
        Actions(pane.actions.filterNot { it in headerActions }, host)
    }
}

@Composable
private fun SearchContent(template: SearchTemplate, host: CarAppHost, modifier: Modifier, showToolbar: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showToolbar) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            template.headerAction?.let { ActionButton(it, host) }
            HostedSearch(template, host)
        }
        Loading(template.isLoading)
        ItemListContent(template.itemList, host, Modifier.weight(1f))
        Actions(template.actionStrip?.actions.orEmpty(), host)
    }
}

@Composable
private fun Actions(actions: List<Action>, host: CarAppHost) {
    if (actions.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        actions.forEach { ActionButton(it, host) }
    }
}

@Composable
internal fun ActionButton(action: Action, host: CarAppHost, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val click = onClick ?: { host.click(action) }
    val opacity = if (compact) .78f else LocalGlassOpacity.current
    if (action.type == Action.TYPE_BACK) {
        GlassIconButton(CarSymbol.Back, "Back", click, enabled = action.isEnabled, opacity = opacity)
        return
    }
    val title = action.title.text()
    GlassSurface(onClick = click, enabled = action.isEnabled, opacity = opacity,
        modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 64.dp).semantics {
            if (title.isNotBlank()) contentDescription = title
        }, shape = RoundedCornerShape(if (compact) 22.dp else 24.dp)) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = if (compact && title.isEmpty()) 12.dp else 16.dp,
            vertical = if (compact && title.isNotEmpty()) 16.dp else 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) { ActionLabel(action, host) }
    }
}

@Composable
private fun ActionLabel(action: Action, host: CarAppHost) {
    CarImage(action.icon, host, Modifier.size(36.dp))
    val title = action.title.text().ifEmpty {
        when (action.type) { Action.TYPE_BACK -> "Back"; Action.TYPE_APP_ICON -> "Home"; Action.TYPE_PAN -> "Pan"; else -> "" }
    }
    if (title.isNotEmpty()) Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun CarImage(icon: CarIcon?, host: CarAppHost, modifier: Modifier = Modifier) {
    if (icon == null) return
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, icon) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = if (icon.type == CarIcon.TYPE_APP_ICON) context.packageManager.getApplicationIcon(host.app.component.packageName)
                    else icon.icon?.loadDrawable(context)
                drawable?.toBitmap(64, 64)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val tint = icon.tint?.let { color ->
        when (color.type) {
            CarColor.TYPE_CUSTOM -> Color(color.colorDark)
            CarColor.TYPE_PRIMARY -> MaterialTheme.colorScheme.primary
            CarColor.TYPE_SECONDARY -> MaterialTheme.colorScheme.secondary
            CarColor.TYPE_RED -> Color(0xFFEE675C)
            CarColor.TYPE_GREEN -> Color(0xFF61AC70)
            CarColor.TYPE_BLUE -> Color(0xFF669DF6)
            CarColor.TYPE_YELLOW -> Color(0xFFE9A240)
            else -> MaterialTheme.colorScheme.onSurface
        }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier.size(32.dp), colorFilter = tint?.let(ColorFilter::tint)) }
        ?: when (icon.type) {
            CarIcon.TYPE_BACK -> Text("←")
            CarIcon.TYPE_ALERT -> Text("!")
            CarIcon.TYPE_ERROR -> Text("!")
            CarIcon.TYPE_PAN -> Text("↔")
            else -> Unit
        }
}

@Composable
private fun Loading(loading: Boolean) { if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
