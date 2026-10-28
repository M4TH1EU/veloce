package ch.broillet.veloce.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.HomeModel
import ch.broillet.veloce.R

@Composable
fun VeloceApp(model: HomeModel, connectUsb: () -> Unit, connectWireless: () -> Unit,
    openMediaAccess: () -> Unit, shareDiagnostics: () -> Unit, enableAudio: () -> Unit, openBluetooth: () -> Unit,
    configureAutoConnect: () -> Unit) {
    val connection by model.connections.state.collectAsStateWithLifecycle()
    val forwardAudio by model.connections.forwardAudio.collectAsStateWithLifecycle()
    val autoConnect by model.wirelessAuto.enabled.collectAsStateWithLifecycle()
    var mediaAccess by remember { mutableStateOf(model.media.hasAccess()) }
    var preview by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mediaAccess = model.media.hasAccess() }
    BackHandler(preview) { preview = false }

    Scaffold { insets ->
        if (preview) CarPreview(model, Modifier.padding(insets)) { preview = false }
        else Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                        Text(stringResource(R.string.phone_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.car_connection), style = MaterialTheme.typography.titleLarge)
                            Text(connection.detail, color = if (connection.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                            when {
                                !connection.identityReady -> {
                                    Text(stringResource(R.string.certificate_explanation))
                                    Button(onClick = model::prepareIdentity, enabled = !connection.preparing) {
                                        Text(stringResource(if (connection.preparing) R.string.downloading else R.string.setup_certificate))
                                    }
                                }
                                connection.active -> Button(onClick = model.connections::disconnect) { Text(stringResource(R.string.disconnect)) }
                                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = connectUsb) { Text(stringResource(R.string.connect_usb)) }
                                    FilledTonalButton(onClick = connectWireless) { Text(stringResource(R.string.connect_wireless)) }
                                }
                            }
                            if (model.connections.lastWireless != null) TextButton(onClick = configureAutoConnect) {
                                Text(stringResource(if (autoConnect) R.string.disable_auto_connect else R.string.enable_auto_connect))
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.car_audio), style = MaterialTheme.typography.titleLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = forwardAudio, onCheckedChange = model.connections::setForwardAudio, enabled = !connection.active)
                                Text(stringResource(R.string.forward_audio), Modifier.padding(start = 12.dp))
                            }
                            Text(stringResource(if (forwardAudio) R.string.forward_audio_description else R.string.bluetooth_audio_description))
                            if (connection.active) {
                                Text(connection.audioDetail)
                                if (connection.audioCapturing) TextButton(onClick = model.connections::stopAudio) { Text(stringResource(R.string.stop_audio)) }
                                else if (connection.audioRequest != null) FilledTonalButton(onClick = enableAudio) { Text(stringResource(R.string.enable_audio)) }
                            }
                            TextButton(onClick = openBluetooth) { Text(stringResource(R.string.bluetooth_settings)) }
                            if (!forwardAudio && connection.audioRoute.isNotBlank()) Text(connection.audioRoute)
                            if (forwardAudio) {
                                var setup by rememberSaveable { mutableStateOf(false) }
                                TextButton(onClick = { setup = !setup }) { Text(stringResource(R.string.audio_adb_setup)) }
                                if (setup) SelectionContainer { Text(stringResource(R.string.audio_adb_commands)) }
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.car_preview), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.preview_explanation))
                            FilledTonalButton(onClick = { preview = true }) { Text(stringResource(R.string.open_preview)) }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.media_access_label), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.media_description))
                            TextButton(onClick = openMediaAccess) {
                                Text(stringResource(if (mediaAccess) R.string.manage_media else R.string.enable_media))
                            }
                        }
                    }
                }
                item { TextButton(onClick = shareDiagnostics) { Text(stringResource(R.string.share_diagnostics)) } }
            }
        }
    }
}
