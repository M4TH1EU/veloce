package ch.broillet.veloce.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.HomeModel
import ch.broillet.veloce.R

@Composable
internal fun PhoneOnboarding(model: HomeModel, mediaAccess: Boolean, openMediaAccess: () -> Unit,
    finish: () -> Unit, close: (() -> Unit)?) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val connection by model.connections.state.collectAsStateWithLifecycle()
    val forwardAudio by model.connections.forwardAudio.collectAsStateWithLifecycle()
    BackHandler(step > 0 || close != null) { if (step > 0) step-- else close?.invoke() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) IconButton(onClick = { step-- }) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_rounded_back), stringResource(R.string.back))
                } else CarGlyph(CarSymbol.Veloce, Modifier.padding(12.dp).size(24.dp), MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (close != null) TextButton(onClick = close) { Text(stringResource(R.string.close_preview)) }
                else Text(stringResource(R.string.setup_step, step + 1), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SetupProgress(step)
            AnimatedContent(step, Modifier.weight(1f), transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (fadeIn(tween(240)) + slideInHorizontally(tween(300)) { direction * it / 8 }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { -direction * it / 12 })
            }, label = "Setup step") { current ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (current == 0) {
                        PhoneHeader(stringResource(R.string.welcome_title), stringResource(R.string.welcome_description))
                        CabinIllustration()
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            SetupFeature(CarSymbol.Navigation, stringResource(R.string.welcome_navigation), stringResource(R.string.welcome_navigation_detail))
                            SetupFeature(CarSymbol.Music, stringResource(R.string.welcome_music), stringResource(R.string.welcome_music_detail))
                            SetupFeature(CarSymbol.Action, stringResource(R.string.welcome_yours), stringResource(R.string.welcome_yours_detail))
                        }
                    } else if (current == 1) {
                        SymbolTile(CarSymbol.Car, prominent = true)
                        PhoneHeader(stringResource(R.string.setup_connection_title), stringResource(R.string.setup_connection_detail))
                        PhonePanel {
                            SettingsRow(stringResource(R.string.setup_certificate_title),
                                stringResource(if (connection.identityReady) R.string.certificate_ready_detail else R.string.certificate_explanation),
                                CarSymbol.Car, trailing = {
                                    if (connection.preparing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else if (connection.identityReady) StatusLabel(stringResource(R.string.ready), active = true)
                                })
                        }
                        if (connection.error) Text(connection.detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        SettingsRow(stringResource(R.string.setup_connect_later),
                            stringResource(R.string.setup_connect_later_detail), CarSymbol.Connection)
                    } else {
                        SymbolTile(CarSymbol.Music, prominent = true)
                        PhoneHeader(stringResource(R.string.setup_media_title), stringResource(R.string.setup_media_detail))
                        PhonePanel {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SymbolTile(CarSymbol.Bell)
                                    Text(stringResource(R.string.notification_access), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                }
                                Text(stringResource(if (mediaAccess) R.string.access_enabled_detail else R.string.setup_access_detail),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                FilledTonalButton(onClick = openMediaAccess) {
                                    Text(stringResource(if (mediaAccess) R.string.manage_access else R.string.enable_access))
                                }
                            }
                        }
                        PhonePanel {
                            SettingsToggle(stringResource(R.string.forward_audio), stringResource(R.string.setup_audio_detail),
                                forwardAudio, model.connections::setForwardAudio, enabled = !connection.active)
                        }
                        Text(stringResource(R.string.setup_change_later), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Column(Modifier.padding(top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhonePrimaryButton(onClick = {
                    when {
                        step == 0 -> step++
                        step == 1 && !connection.identityReady -> model.prepareIdentity()
                        step == 1 -> step++
                        else -> finish()
                    }
                }, enabled = step != 1 || !connection.preparing) {
                    Text(stringResource(when {
                        step == 0 -> R.string.get_started
                        step == 1 && connection.preparing -> R.string.downloading
                        step == 1 && !connection.identityReady -> if (connection.error) R.string.retry else R.string.setup_certificate
                        step == 1 -> R.string.continue_label
                        else -> R.string.finish_setup
                    }))
                    Spacer(Modifier.width(8.dp))
                    CarGlyph(CarSymbol.ChevronRight, Modifier.size(20.dp), MaterialTheme.colorScheme.onPrimary)
                }
                if (step == 1 && !connection.identityReady && !connection.preparing) TextButton(
                    onClick = { step++ }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_later)) }
            }
        }
    }
}

@Composable
private fun SetupFeature(symbol: CarSymbol, title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SymbolTile(symbol)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
