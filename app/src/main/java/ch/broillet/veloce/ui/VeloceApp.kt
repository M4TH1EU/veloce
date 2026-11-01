package ch.broillet.veloce.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.broillet.veloce.HomeModel
import ch.broillet.veloce.R
import kotlinx.coroutines.launch

private enum class PhonePage { HOME, SETTINGS, PREVIEW }

@Composable
fun VeloceApp(model: HomeModel, connectUsb: () -> Unit, connectWireless: () -> Unit,
    openMediaAccess: () -> Unit, shareDiagnostics: () -> Unit, enableAudio: () -> Unit, openBluetooth: () -> Unit,
    configureAutoConnect: () -> Unit) {
    val settings = LocalAppSettings.current
    var page by rememberSaveable { mutableStateOf(PhonePage.HOME) }
    var previewReturn by rememberSaveable { mutableStateOf(PhonePage.HOME) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var mediaAccess by remember { mutableStateOf(model.media.hasAccess()) }
    val onboarding = !settings.setupComplete || setup
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val stateHolder = rememberSaveableStateHolder()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        mediaAccess = model.media.hasAccess()
        model.refreshLyricsCache()
    }
    BackHandler(!onboarding && page != PhonePage.HOME) { page = if (page == PhonePage.PREVIEW) previewReturn else PhonePage.HOME }

    Box(Modifier.fillMaxSize().cabinBackground()) {
        Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!onboarding && page != PhonePage.PREVIEW) NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    listOf(PhonePage.HOME to R.string.drive, PhonePage.SETTINGS to R.string.settings).forEach { (destination, label) ->
                        NavigationBarItem(selected = page == destination, onClick = { page = destination },
                            icon = { CarGlyph(if (destination == PhonePage.HOME) CarSymbol.Car else CarSymbol.Action,
                                Modifier.size(24.dp), if (page == destination) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) },
                            label = { Text(stringResource(label)) })
                    }
                }
            }) { insets ->
            AnimatedContent(targetState = if (onboarding) "setup" else page.name, modifier = Modifier.fillMaxSize().padding(insets),
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(300)) { it / 35 }) togetherWith fadeOut(tween(120))
                }, label = "Phone destination") { destination ->
                if (destination == "setup") PhoneOnboarding(model, mediaAccess, openMediaAccess,
                    finish = { model.settings.update { it.copy(setupComplete = true) }; setup = false; page = PhonePage.HOME },
                    close = if (settings.setupComplete) ({ setup = false }) else null)
                else if (destination == PhonePage.PREVIEW.name) CarPreview(model, Modifier) { page = previewReturn }
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    stateHolder.SaveableStateProvider(destination) {
                        val contentModifier = Modifier.widthIn(max = 680.dp).fillMaxSize()
                        if (destination == PhonePage.HOME.name) PhoneHome(model, mediaAccess, connectUsb, connectWireless,
                            openMediaAccess, enableAudio, openBluetooth, { previewReturn = PhonePage.HOME; page = PhonePage.PREVIEW }, { page = PhonePage.SETTINGS },
                            { setup = true }, contentModifier)
                        else PhoneSettings(model, mediaAccess, openMediaAccess, openBluetooth, configureAutoConnect,
                            shareDiagnostics, { previewReturn = PhonePage.SETTINGS; page = PhonePage.PREVIEW }, { setup = true },
                            { message -> scope.launch { snackbar.showSnackbar(message) } }, contentModifier)
                    }
                }
            }
        }
    }
}
