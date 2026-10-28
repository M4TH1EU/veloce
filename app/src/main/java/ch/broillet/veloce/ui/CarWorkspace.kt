package ch.broillet.veloce.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import ch.broillet.veloce.hosting.CarAppHost
import ch.broillet.veloce.media.MediaLibrary
import java.io.Closeable

class CarWorkspace(
    private val context: Context,
    private val navigation: CarNavigation,
    private val log: (String) -> Unit,
) : Closeable {
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
        if (app.kind != AppKind.AUDIO && (host?.app?.component != app.component || host?.error != null)) {
            host?.close()
            lateinit var opened: CarAppHost
            opened = CarAppHost(context, app, {
                if (host === opened) {
                    host = null
                    opened.close()
                    if (navigation.selected?.component == app.component) navigation.home()
                }
            }, log, navigation::home)
            host = opened
            opened.setVisible(navigation.projected)
            opened.connect()
        }
        navigation.selected = app
        navigation.player = false
        updateVisibility()
    }

    fun updateVisibility() {
        host?.setVisible(navigation.projected && navigation.selected?.component == host?.app?.component && !navigation.player)
    }

    override fun close() {
        if (closed) return
        closed = true
        host?.close()
        library?.close()
        host = null
        library = null
        navigation.appBack = null
        navigation.home()
    }
}
