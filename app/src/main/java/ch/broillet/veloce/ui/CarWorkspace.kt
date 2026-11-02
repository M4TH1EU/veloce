package ch.broillet.veloce.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.hosting.CarAppHost
import ch.broillet.veloce.media.MediaLibrary
import java.io.Closeable

class CarWorkspace(
    private val context: Context,
    private val navigation: CarNavigation,
    private val log: (String) -> Unit,
    private val canSplit: Boolean = true,
) : Closeable {
    private val preferences = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    var splitScreen by mutableStateOf(preferences.getBoolean("split_screen", true))
        private set
    var host by mutableStateOf<CarAppHost?>(null)
        private set
    var library by mutableStateOf<MediaLibrary?>(null)
        private set
    private var closed = false

    fun open(app: CarApp) {
        check(!closed)
        if (app.kind == AppKind.AUDIO && (library?.app?.component != app.component || library?.page?.error != null)) {
            library?.close()
            library = MediaLibrary(context, app, log).also { it.connect() }
        }
        if (app.kind != AppKind.AUDIO) openHost(app)
        navigation.selected = app
        navigation.player = false
        navigation.notificationsOpen = false
        navigation.lyrics = false
        navigation.dashboard = canSplit && app.kind == AppKind.MAP && splitScreen
        updateVisibility()
    }

    private fun openHost(app: CarApp) {
        if (host?.app?.component != app.component || host?.error != null) {
            host?.close()
            lateinit var opened: CarAppHost
            opened = CarAppHost(context, app, {
                if (host === opened) {
                    log("Car app ${app.label} finished (player=${navigation.player}, lyrics=${navigation.lyrics}, dashboard=${navigation.dashboard})")
                    host = null
                    opened.close()
                    navigation.dashboard = false
                    if (navigation.selected?.component == app.component) {
                        if (!navigation.player && !navigation.notificationsOpen) navigation.home()
                        else navigation.selected = null
                    }
                }
            }, log, navigation::home, onNavigationStarted = {
                if (host === opened && navigation.selected?.component == app.component && !navigation.player && !navigation.notificationsOpen) setSplitLayout(false)
            })
            host = opened
            opened.setVisible(navigation.projected)
            opened.connect()
        }
    }

    fun openDashboard(map: CarApp) {
        check(!closed && canSplit && map.kind == AppKind.MAP)
        openHost(map)
        navigation.notificationsOpen = false
        navigation.lyrics = false
        setSplitLayout(true)
        updateVisibility()
    }

    fun updateVisibility() {
        host?.setVisible(navigation.projected && !navigation.notificationsOpen &&
            (navigation.dashboard || (navigation.selected?.component == host?.app?.component && !navigation.player)))
    }

    fun setSplitLayout(value: Boolean) {
        splitScreen = value
        navigation.dashboard = value && canSplit && host?.app?.kind == AppKind.MAP
        preferences.edit { putBoolean("split_screen", value) }
    }

    override fun close() {
        if (closed) return
        closed = true
        host?.close()
        library?.close()
        host = null
        library = null
        navigation.appBack = null
        navigation.mediaKey = null
        navigation.home()
    }
}
