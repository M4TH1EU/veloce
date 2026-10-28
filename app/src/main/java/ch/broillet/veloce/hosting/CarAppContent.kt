@file:Suppress("RestrictedApi", "DEPRECATION")

package ch.broillet.veloce.hosting

import androidx.car.app.model.*
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.navigation.model.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import ch.broillet.veloce.VeloceApplication
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.ui.CarNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CarAppContent(app: CarApp, navigation: CarNavigation, modifier: Modifier) {
    val context = LocalContext.current
    val host = remember(app.component) {
        CarAppHost(context, app, navigation::home, (context.applicationContext as VeloceApplication).connections::log)
    }
    DisposableEffect(host) {
        navigation.appBack = host::back
        host.connect()
        onDispose { navigation.appBack = null; host.close() }
    }
    LaunchedEffect(host, navigation.projected) { host.setVisible(navigation.projected) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        host.notice?.let { message ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = host::dismissNotice) { Text("Dismiss") }
                }
            }
        }
        val failure = host.error
        val template = host.template
        when {
            failure != null -> Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.Center) {
                Text("Could not open ${app.label}", style = MaterialTheme.typography.titleLarge)
                Text(failure, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text("Check the app’s host-validation patch and Car App Library compatibility. Details are in the connection log.")
                TextButton(onClick = navigation::home) { Text("Home") }
            }
            template == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> TemplateContent(template, host, Modifier.fillMaxWidth().weight(1f))
        }
    }
    host.alert?.let { alert ->
        AlertDialog(onDismissRequest = { host.dismissAlert(AlertCallback.REASON_USER_ACTION) },
            title = { Text(alert.title.text()) }, text = { Text(alert.subtitle.text()) },
            confirmButton = {
                if (alert.actions.isEmpty()) TextButton(onClick = { host.dismissAlert() }) { Text("OK") }
                else Row { alert.actions.forEach { action ->
                    TextButton(onClick = { host.click(action); host.dismissAlert() }) { ActionLabel(action, host) }
                } }
            })
    }
}

@Composable
@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
internal fun TemplateContent(template: Template, host: CarAppHost, modifier: Modifier) {
    when (template) {
        is NavigationTemplate -> MapLayout(host, modifier, actions = template.actionStrip,
            mapActions = template.mapActionStrip, pan = template.panModeDelegate,
            panToggle = template.panModeToggle,
            information = { NavigationInfo(template, host) })
        is MapWithContentTemplate -> MapLayout(host, modifier, template.contentTemplate, template.actionStrip,
            template.mapController?.mapActionStrip, template.mapController?.panModeDelegate)
        is MapTemplate -> MapLayout(host, modifier, actions = template.actionStrip,
            mapActions = template.mapController?.mapActionStrip, pan = template.mapController?.panModeDelegate,
            panel = { Column(Modifier.fillMaxSize()) {
                TemplateHeader(template.header, null, null, host)
                template.pane?.let { PaneContent(it, host, Modifier.weight(1f)) }
                template.itemList?.let { ItemListContent(it, host, Modifier.weight(1f)) }
            } })
        is PlaceListNavigationTemplate -> MapLayout(host, modifier, actions = template.actionStrip,
            mapActions = template.mapActionStrip, pan = template.panModeDelegate, panel = {
                Column(Modifier.fillMaxSize()) {
                    TemplateHeader(template.header, template.title, template.headerAction, host)
                    Loading(template.isLoading)
                    template.onContentRefreshDelegate?.let { delegate ->
                        TextButton(onClick = { host.event { delegate.sendContentRefreshRequested(it) } }) { Text("Refresh") }
                    }
                    ItemListContent(template.itemList, host, Modifier.weight(1f))
                }
            })
        is RoutePreviewNavigationTemplate -> MapLayout(host, modifier, actions = template.actionStrip,
            mapActions = template.mapActionStrip, pan = template.panModeDelegate, panel = {
                Column(Modifier.fillMaxSize()) {
                    TemplateHeader(template.header, template.title, template.headerAction, host)
                    Loading(template.isLoading)
                    ItemListContent(template.itemList, host, Modifier.weight(1f))
                    template.navigateAction?.let { ActionButton(it, host) }
                }
            })
        is ListTemplate -> Column(modifier) {
            TemplateHeader(template.header, template.title, template.headerAction, host)
            Loading(template.isLoading)
            if (template.singleList != null) ItemListContent(template.singleList, host, Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f)) {
                template.sectionedLists.forEach { section ->
                    item { Text(section.header.text(), Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium) }
                    itemsIndexed(section.itemList.items) { index, item -> ListItemContent(item, section.itemList, index, host) }
                }
            }
            Actions(template.actionStrip?.actions.orEmpty() + template.actions, host)
        }
        is GridTemplate -> Column(modifier) {
            TemplateHeader(template.header, template.title, template.headerAction, host)
            Loading(template.isLoading)
            LazyVerticalGrid(GridCells.Adaptive(128.dp), Modifier.weight(1f)) {
                items(template.singleList?.items.orEmpty()) { item ->
                    if (item is GridItem) Card(onClick = { host.click(item.onClickDelegate) }, Modifier.padding(4.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CarImage(item.image, host)
                            Loading(item.isLoading)
                            Text(item.title.text(), maxLines = 2)
                            Text(item.text.text(), maxLines = 1)
                        }
                    }
                }
            }
            Actions(template.actionStrip?.actions.orEmpty() + template.actions, host)
        }
        is PaneTemplate -> Column(modifier) {
            TemplateHeader(template.header, template.title, template.headerAction, host)
            PaneContent(template.pane, host, Modifier.weight(1f))
            Actions(template.actionStrip?.actions.orEmpty(), host)
        }
        is MessageTemplate -> Column(modifier) {
            TemplateHeader(template.header, template.title, template.headerAction, host)
            Loading(template.isLoading)
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.Center) {
                CarImage(template.icon, host)
                Text(template.message.text(), style = MaterialTheme.typography.titleLarge)
            }
            Actions(template.actions + template.actionStrip?.actions.orEmpty(), host)
        }
        is LongMessageTemplate -> Column(modifier) {
            TemplateHeader(null, template.title, template.headerAction, host)
            LazyColumn(Modifier.weight(1f)) { item { Text(template.message.text(), Modifier.padding(16.dp)) } }
            Actions(template.actions + template.actionStrip?.actions.orEmpty(), host)
        }
        is SearchTemplate -> SearchContent(template, host, modifier)
        is TabTemplate -> Column(modifier) {
            Actions(listOfNotNull(template.headerAction), host)
            Loading(template.isLoading)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                template.tabs.forEach { tab ->
                    TextButton(onClick = { host.event { template.tabCallbackDelegate.sendTabSelected(tab.contentId, it) } }) {
                        CarImage(tab.icon, host)
                        Text(tab.title.text())
                    }
                }
            }
            if (!template.isLoading) TemplateContent(template.tabContents.template, host, Modifier.weight(1f))
        }
        is SignInTemplate -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(template.title.text(), style = MaterialTheme.typography.titleLarge)
            Text(template.instructions.text())
            Text("Complete sign-in on your phone while parked, then reopen this app.")
        }
        else -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text("Unsupported car template: ${template.javaClass.simpleName}")
            Text("This app needs host functionality that Veloce has not implemented yet.")
        }
    }
}

@Composable
private fun MapLayout(host: CarAppHost, modifier: Modifier, content: Template? = null, actions: ActionStrip? = null,
    mapActions: ActionStrip? = null, pan: PanModeDelegate? = null, panToggle: Toggle? = null,
    information: @Composable () -> Unit = {}, panel: (@Composable () -> Unit)? = null) {
    var panning by remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (content != null || panel != null) Surface(Modifier.fillMaxHeight().fillMaxWidth(.42f), shape = MaterialTheme.shapes.medium) {
            if (content != null) TemplateContent(content, host, Modifier.fillMaxSize()) else panel?.invoke()
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            information()
            MapSurface(host, Modifier.fillMaxWidth().weight(1f))
            val buttons = actions?.actions.orEmpty() + mapActions?.actions.orEmpty()
            if (buttons.isNotEmpty() || panToggle != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                panToggle?.let { toggle ->
                    Text("Pan", Modifier.align(Alignment.CenterVertically))
                    Switch(toggle.isChecked, { checked -> host.event { toggle.onCheckedChangeDelegate.sendCheckedChange(checked, it) } }, enabled = toggle.isEnabled)
                }
                buttons.forEach { action ->
                    if (action.type == Action.TYPE_PAN) FilledTonalButton(onClick = {
                        panning = !panning
                        val enabled = panning
                        pan?.let { delegate -> host.event { delegate.sendPanModeChanged(enabled, it) } }
                    }) { Text(if (panning) "Done" else "Pan") }
                    else ActionButton(action, host)
                }
            }
        }
    }
}

@Composable
private fun NavigationInfo(template: NavigationTemplate, host: CarAppHost) {
    when (val info = template.navigationInfo) {
        is RoutingInfo -> Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Loading(info.isLoading)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarImage(info.currentStep?.maneuver?.icon, host)
                Text(listOf(info.currentDistance.distance(), info.currentStep?.cue.text()).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2)
            }
            info.currentStep?.lanesImage?.let { CarImage(it, host) }
            info.currentStep?.road?.let { Text(it.text(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        is MessageInfo -> Column(Modifier.padding(8.dp)) { Text(info.title.text()); Text(info.text.text()) }
    }
    template.destinationTravelEstimate?.let { estimate ->
        val minutes = estimate.remainingTimeSeconds.takeIf { it >= 0 }?.let { "${(it + 59) / 60} min" }.orEmpty()
        Text(listOf(minutes, estimate.remainingDistance.distance(), estimate.tripText.text()).filter(String::isNotBlank).joinToString(" · "),
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TemplateHeader(header: Header?, title: CarText?, action: Action?, host: CarAppHost) {
    val label = header?.title ?: title
    val start = header?.startHeaderAction ?: action
    if (label == null && start == null && header?.endHeaderActions.isNullOrEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        start?.let { TextButton(onClick = { host.click(it) }) { ActionLabel(it, host) } }
        Text(label.text(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Surface(onClick = {
        if (selection != null) host.event { selection.sendSelected(index, it) } else host.click(item.onClickDelegate)
    }, enabled = item.isEnabled && clickable, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selection != null) RadioButton(selected = list.selectedIndex == index, onClick = null)
            CarImage(item.image, host)
            Column(Modifier.weight(1f)) {
                Text(item.title.text(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.texts.forEach { Text(it.text(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            item.toggle?.let { toggle ->
                Switch(toggle.isChecked, { checked -> host.event { toggle.onCheckedChangeDelegate.sendCheckedChange(checked, it) } }, enabled = toggle.isEnabled)
            }
            item.actions.forEach { ActionButton(it, host) }
        }
    }
}

@Composable
private fun PaneContent(pane: Pane, host: CarAppHost, modifier: Modifier) {
    Column(modifier) {
        Loading(pane.isLoading)
        LazyColumn(Modifier.weight(1f)) {
            item { CarImage(pane.image, host) }
            itemsIndexed(pane.rows) { index, row -> ListItemContent(row, null, index, host) }
        }
        Actions(pane.actions, host)
    }
}

@Composable
private fun SearchContent(template: SearchTemplate, host: CarAppHost, modifier: Modifier) {
    var text by remember(template.initialSearchText) { mutableStateOf(template.initialSearchText.orEmpty()) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            template.headerAction?.let { ActionButton(it, host) }
            OutlinedTextField(text, { value ->
                text = value
                host.event { template.searchCallbackDelegate.sendSearchTextChanged(value, it) }
            }, Modifier.weight(1f), singleLine = true, placeholder = { Text(template.searchHint ?: "Search") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { host.event { template.searchCallbackDelegate.sendSearchSubmitted(text, it) } }))
            TextButton(onClick = { host.event { template.searchCallbackDelegate.sendSearchSubmitted(text, it) } }) { Text("Search") }
        }
        Loading(template.isLoading)
        ItemListContent(template.itemList, host, Modifier.weight(1f))
        Actions(template.actionStrip?.actions.orEmpty(), host)
    }
}

@Composable
private fun Actions(actions: List<Action>, host: CarAppHost) {
    if (actions.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { ActionButton(it, host) }
    }
}

@Composable
private fun ActionButton(action: Action, host: CarAppHost) {
    FilledTonalButton(onClick = { host.click(action) }, enabled = action.isEnabled, modifier = Modifier.heightIn(min = 48.dp)) { ActionLabel(action, host) }
}

@Composable
private fun ActionLabel(action: Action, host: CarAppHost) {
    CarImage(action.icon, host)
    val title = action.title.text().ifEmpty {
        when (action.type) { Action.TYPE_BACK -> "Back"; Action.TYPE_APP_ICON -> "Home"; Action.TYPE_PAN -> "Pan"; else -> "" }
    }
    if (title.isNotEmpty()) Text(title, maxLines = 1)
}

@Composable
private fun CarImage(icon: CarIcon?, host: CarAppHost) {
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
    bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp), colorFilter = tint?.let(ColorFilter::tint)) }
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
