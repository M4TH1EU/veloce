package ch.broillet.veloce.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.HomeModel
import ch.broillet.veloce.R
import ch.broillet.veloce.connection.ConnectionState

@Composable
internal fun PhoneHome(model: HomeModel, mediaAccess: Boolean, connectUsb: () -> Unit, connectWireless: () -> Unit,
    openMediaAccess: () -> Unit, enableAudio: () -> Unit, openBluetooth: () -> Unit, preview: () -> Unit,
    settings: () -> Unit, setup: () -> Unit, modifier: Modifier) {
    val connection by model.connections.state.collectAsStateWithLifecycle()
    val apps by model.apps.collectAsStateWithLifecycle()
    val installedApps = remember(apps) { apps.distinctBy { it.component.packageName } }
    val settingsState = LocalAppSettings.current
    val shown = installedApps.count { settingsState.isAppVisible(it.component.packageName) }
    var connecting by rememberSaveable { mutableStateOf(false) }
    var appList by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CarGlyph(CarSymbol.Veloce, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FilledTonalIconButton(onClick = settings) {
                    Icon(painterResource(R.drawable.ic_rounded_settings), stringResource(R.string.settings))
                }
            }
            PhoneHeader(stringResource(if (connection.projecting) R.string.home_connected_title else R.string.home_title),
                stringResource(if (connection.projecting) R.string.home_connected_detail else R.string.home_description))
        }
        item {
            ConnectionCard(connection, { connecting = true }, model.connections::disconnect, model::prepareIdentity)
        }
        if (connection.active) item {
            PhonePanel {
                SettingsRow(stringResource(R.string.car_audio), connection.audioDetail, CarSymbol.Music)
                Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connection.audioCapturing) FilledTonalButton(onClick = model.connections::stopAudio) { Text(stringResource(R.string.stop_audio)) }
                    else if (connection.audioRequest != null) Button(onClick = enableAudio) { Text(stringResource(R.string.enable_audio)) }
                    else TextButton(onClick = openBluetooth) { Text(stringResource(R.string.bluetooth_settings)) }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.your_cockpit)) }
        item {
            Surface(onClick = preview, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CabinIllustration()
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.take_a_look), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.preview_home_detail), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            CarGlyph(CarSymbol.ChevronRight, Modifier.size(28.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
        item {
            PhonePanel {
                SettingsRow(stringResource(R.string.car_apps), pluralStringResource(R.plurals.car_apps_summary, installedApps.size, shown, installedApps.size),
                    CarSymbol.Library, { appList = true })
                if (!mediaAccess) {
                    PanelDivider()
                    SettingsRow(stringResource(R.string.complete_your_setup), stringResource(R.string.setup_access_detail), CarSymbol.Bell, openMediaAccess)
                }
            }
        }
        if (!connection.identityReady) item {
            TextButton(onClick = setup, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.review_setup)) }
        }
    }
    if (connecting) ConnectionSheet({ connecting = false }, {
        connecting = false; connectUsb()
    }, { connecting = false; connectWireless() }, openBluetooth)
    if (appList) CarAppsSheet(model) { appList = false }
}

@Composable
private fun ConnectionCard(connection: ConnectionState, connect: () -> Unit, disconnect: () -> Unit, prepare: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolTile(CarSymbol.Car, prominent = true)
                Spacer(Modifier.weight(1f))
                StatusLabel(stringResource(when {
                    connection.error -> R.string.needs_attention
                    connection.projecting -> R.string.connected
                    connection.active -> R.string.connecting_short
                    !connection.identityReady -> R.string.setup_needed
                    else -> R.string.ready
                }), connection.active, connection.error)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when {
                    connection.projecting -> R.string.connection_live_title
                    connection.active -> R.string.connecting
                    !connection.identityReady -> R.string.setup_connection_title
                    connection.error -> R.string.connection_error_title
                    else -> R.string.connect_your_car
                }), style = MaterialTheme.typography.headlineSmall)
                Text(if (connection.error || connection.active || connection.identityReady) connection.detail
                    else stringResource(R.string.connection_setup_detail),
                    style = MaterialTheme.typography.bodyLarge, color = if (connection.error) colors.error else colors.onSurfaceVariant)
            }
            if (connection.active && !connection.projecting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.surfaceContainerHighest)
            PhonePrimaryButton(onClick = when {
                connection.active -> disconnect
                !connection.identityReady -> prepare
                else -> connect
            }, enabled = !connection.preparing) {
                if (connection.preparing) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                } else {
                    CarGlyph(if (connection.active) CarSymbol.Close else CarSymbol.Car, Modifier.size(24.dp), colors.onPrimary)
                    Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(when {
                    connection.preparing -> R.string.downloading
                    connection.active -> R.string.disconnect
                    !connection.identityReady -> R.string.setup_certificate
                    else -> R.string.connect_your_car
                }))
            }
            if (!connection.active && connection.identityReady) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CarGlyph(CarSymbol.Connection, Modifier.size(16.dp), colors.onSurfaceVariant)
                Text(stringResource(R.string.usb_and_wireless), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSheet(dismiss: () -> Unit, usb: () -> Unit, wireless: () -> Unit, bluetooth: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 24.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.connect_your_car), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.choose_connection_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
            PhonePanel {
                SettingsRow(stringResource(R.string.usb_connection), stringResource(R.string.usb_instructions), CarSymbol.Connection, usb)
                PanelDivider()
                SettingsRow(stringResource(R.string.wireless_connection), stringResource(R.string.wireless_instructions), CarSymbol.Wireless, wireless)
            }
            TextButton(onClick = bluetooth) { Text(stringResource(R.string.pair_car)) }
        }
    }
}

internal data class PairedCar(val name: String, val address: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairedCarSheet(cars: List<PairedCar>, dismiss: () -> Unit, select: (PairedCar) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.choose_car), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.paired_car_detail), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(cars, key = { it.address }) { car ->
                PhonePanel { SettingsRow(car.name, car.address, CarSymbol.Car, { select(car) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarAppsSheet(model: HomeModel, dismiss: () -> Unit) {
    val apps by model.apps.collectAsStateWithLifecycle()
    val refreshing by model.refreshingApps.collectAsStateWithLifecycle()
    val settings = LocalAppSettings.current
    val installed = remember(apps) { apps.distinctBy { it.component.packageName } }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(installed, query) {
        installed.filter { it.label.contains(query.trim(), ignoreCase = true) || it.component.packageName.contains(query.trim(), ignoreCase = true) }
    }
    val shown = installed.count { settings.isAppVisible(it.component.packageName) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.car_apps), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = model::refresh, enabled = !refreshing) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Icon(painterResource(R.drawable.ic_rounded_refresh), stringResource(R.string.refresh))
                    }
                }
                Text(stringResource(R.string.car_apps_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (installed.isNotEmpty()) item {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
                    label = { Text(stringResource(R.string.search_car_apps)) },
                    leadingIcon = { CarGlyph(CarSymbol.Search, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant) })
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.car_apps_summary, installed.size, shown, installed.size), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (shown < installed.size) TextButton(onClick = { model.settings.update { it.copy(hiddenAppPackages = emptySet()) } }) {
                        Text(stringResource(R.string.show_all_apps))
                    }
                }
            }
            if (installed.isEmpty() && !refreshing) item {
                PhonePanel { Text(stringResource(R.string.no_installed_car_apps), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (installed.isNotEmpty() && matches.isEmpty()) item {
                Text(stringResource(R.string.no_matching_apps), Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(matches, key = { it.component.packageName }) { app ->
                val visible = settings.isAppVisible(app.component.packageName)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .toggleable(visible, role = Role.Switch, onValueChange = { model.settings.setAppVisible(app.component.packageName, it) })
                    .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PackageIcon(app.component.packageName, Modifier.size(48.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (visible) R.string.shown_in_car else R.string.hidden_from_car),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(visible, onCheckedChange = null)
                }
            }
            if (installed.isNotEmpty()) item {
                Text(stringResource(R.string.car_app_visibility_note), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
