package ch.broillet.veloce.ui

import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.HomeModel
import ch.broillet.veloce.R
import ch.broillet.veloce.settings.AppPalette
import ch.broillet.veloce.settings.ClockFormat
import ch.broillet.veloce.settings.ThemeMode

@Composable
internal fun PhoneSettings(model: HomeModel, mediaAccess: Boolean, openMediaAccess: () -> Unit, openBluetooth: () -> Unit,
    configureAutoConnect: () -> Unit, shareDiagnostics: () -> Unit, preview: () -> Unit, setup: () -> Unit,
    message: (String) -> Unit, modifier: Modifier) {
    val settings = LocalAppSettings.current
    val connection by model.connections.state.collectAsStateWithLifecycle()
    val forwardAudio by model.connections.forwardAudio.collectAsStateWithLifecycle()
    val autoConnect by model.wirelessAuto.enabled.collectAsStateWithLifecycle()
    val cache by model.lyricsCache.collectAsStateWithLifecycle()
    val clearing by model.clearingLyrics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clearedMessage = stringResource(R.string.lyrics_cache_cleared)
    val failedMessage = stringResource(R.string.lyrics_cache_failed)
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var manageApps by rememberSaveable { mutableStateOf(false) }
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
    }
    LaunchedEffect(Unit) { model.refreshLyricsCache() }

    LazyColumn(modifier, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PhoneHeader(stringResource(R.string.settings), stringResource(R.string.settings_description)) }
        item { SectionLabel(stringResource(R.string.appearance)) }
        item {
            PhonePanel {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CabinIllustration()
                    Text(stringResource(R.string.background_colors), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.palette_description), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppPalette.entries.forEach { palette -> item(key = palette.name) {
                            PaletteOption(palette, settings.palette == palette) { model.settings.update { it.copy(palette = palette) } }
                        } }
                    }
                }
                PanelDivider()
                SettingsRow(stringResource(R.string.phone_theme), stringResource(settings.theme.label()), onClick = { dialog = "theme" })
                PanelDivider()
                SettingsRow(stringResource(R.string.clock_format), stringResource(settings.clockFormat.label()), onClick = { dialog = "clock" })
                PanelDivider()
                SettingsToggle(stringResource(R.string.artwork_background), stringResource(R.string.artwork_background_detail),
                    settings.artworkBackground, { checked -> model.settings.update { it.copy(artworkBackground = checked) } })
            }
        }
        item { SectionLabel(stringResource(R.string.connection_and_audio)) }
        item {
            PhonePanel {
                SettingsToggle(stringResource(R.string.wireless_auto_connect), stringResource(
                    if (model.connections.lastWireless == null) R.string.auto_connect_needs_car else R.string.auto_connect_detail),
                    autoConnect, { configureAutoConnect() }, enabled = model.connections.lastWireless != null)
                PanelDivider()
                SettingsToggle(stringResource(R.string.forward_audio), stringResource(when {
                    connection.active -> R.string.audio_change_disconnected
                    forwardAudio -> R.string.forward_audio_description
                    else -> R.string.bluetooth_audio_description
                }), forwardAudio, model.connections::setForwardAudio, enabled = !connection.active)
                PanelDivider()
                SettingsRow(stringResource(R.string.bluetooth_settings), connection.audioRoute.takeIf { it.isNotBlank() },
                    CarSymbol.Wireless, openBluetooth)
            }
        }
        item { SectionLabel(stringResource(R.string.notifications_and_media)) }
        item {
            PhonePanel {
                SettingsRow(stringResource(R.string.car_apps), stringResource(R.string.manage_car_apps_detail), CarSymbol.Library, { manageApps = true })
                PanelDivider()
                SettingsRow(stringResource(R.string.notification_access), stringResource(
                    if (mediaAccess) R.string.access_enabled_detail else R.string.setup_access_detail), CarSymbol.Bell, openMediaAccess)
                PanelDivider()
                SettingsToggle(stringResource(R.string.notification_banners), stringResource(R.string.notification_banners_detail),
                    settings.notificationBanners, { checked -> model.settings.update { it.copy(notificationBanners = checked) } })
                PanelDivider()
                SettingsRow(stringResource(R.string.lyrics_cache),
                    pluralStringResource(R.plurals.lyrics_cache_size, cache.count, cache.count, Formatter.formatShortFileSize(context, cache.bytes)), CarSymbol.Lyrics,
                    onClick = { dialog = "cache" }, enabled = !clearing, trailing = {
                        if (clearing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else CarGlyph(CarSymbol.ChevronRight, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    })
            }
        }
        item { SectionLabel(stringResource(R.string.tools_and_support)) }
        item {
            PhonePanel {
                SettingsRow(stringResource(R.string.car_preview), stringResource(R.string.preview_explanation), CarSymbol.Car, preview)
                PanelDivider()
                SettingsRow(stringResource(R.string.share_diagnostics), stringResource(R.string.diagnostics_description), CarSymbol.Connection, shareDiagnostics)
                PanelDivider()
                SettingsRow(stringResource(R.string.review_setup), symbol = CarSymbol.Refresh, onClick = setup)
                PanelDivider()
                Column(Modifier.animateContentSize()) {
                    SettingsRow(stringResource(R.string.audio_adb_setup), stringResource(R.string.advanced_audio_detail),
                        CarSymbol.Action, { advanced = !advanced })
                    AnimatedVisibility(advanced) {
                        SelectionContainer {
                            Text(stringResource(R.string.audio_adb_commands), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                                style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CarGlyph(CarSymbol.Veloce, Modifier.size(28.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.app_version, version), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.about_veloce), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
    if (manageApps) CarAppsSheet(model) { manageApps = false }
    when (dialog) {
        "theme" -> ChoiceDialog(stringResource(R.string.phone_theme), stringResource(R.string.phone_theme_detail),
            ThemeMode.entries.map { it to stringResource(it.label()) }, settings.theme, { dialog = null }) { choice ->
            model.settings.update { it.copy(theme = choice) }; dialog = null
        }
        "clock" -> ChoiceDialog(stringResource(R.string.clock_format), stringResource(R.string.clock_format_detail),
            ClockFormat.entries.map { it to stringResource(it.label()) }, settings.clockFormat, { dialog = null }) { choice ->
            model.settings.update { it.copy(clockFormat = choice) }; dialog = null
        }
        "cache" -> AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.clear_lyrics_cache)) },
            text = { Text(stringResource(R.string.clear_lyrics_cache_detail)) },
            confirmButton = { TextButton(onClick = {
                dialog = null
                model.clearLyricsCache { success ->
                    message(if (success) clearedMessage else failedMessage)
                }
            }) { Text(stringResource(R.string.clear_cache)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun SettingsToggle(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Box(Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)) {
        SettingsRow(title, detail, enabled = enabled, trailing = { Switch(checked, onCheckedChange = null, enabled = enabled) })
    }
}

@Composable
private fun PaletteOption(palette: AppPalette, selected: Boolean, select: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, spring(.75f, 450f), label = "Palette selection")
    Column(Modifier.width(82.dp).clip(RoundedCornerShape(20.dp))
        .selectable(selected, role = Role.RadioButton, onClick = select).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.border(if (selected) 2.dp else 0.dp,
            if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape).padding(5.dp)
            .background(palette.swatch(), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) CarGlyph(CarSymbol.Check, Modifier.size(22.dp), androidx.compose.ui.graphics.Color(0xFF242424))
        }
        Text(stringResource(palette.label()), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, detail: String, choices: List<Pair<T, String>>, selected: T,
    dismiss: () -> Unit, choose: (T) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column {
            Text(detail, Modifier.padding(bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                choices.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).selectable(value == selected, role = Role.RadioButton,
                        onClick = { choose(value) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value == selected, onClick = null)
                        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.follow_system
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.LIGHT -> R.string.theme_light
}

private fun ClockFormat.label(): Int = when (this) {
    ClockFormat.SYSTEM -> R.string.follow_system
    ClockFormat.TWELVE_HOUR -> R.string.clock_12
    ClockFormat.TWENTY_FOUR_HOUR -> R.string.clock_24
}

private fun AppPalette.label(): Int = when (this) {
    AppPalette.GRAPHITE -> R.string.palette_graphite
    AppPalette.DYNAMIC -> R.string.palette_dynamic
    AppPalette.SAGE -> R.string.palette_sage
    AppPalette.OCEAN -> R.string.palette_ocean
    AppPalette.EMBER -> R.string.palette_ember
}
