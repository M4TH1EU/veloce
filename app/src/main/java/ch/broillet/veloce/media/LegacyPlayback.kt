package ch.broillet.veloce.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.MediaMetadata
import android.media.session.PlaybackState
import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable

internal class LegacyPlayback(context: Context, component: ComponentName, private val changed: () -> Unit) : Closeable {
    val ready = CompletableDeferred<Boolean>()
    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
    }
    private val browser: MediaBrowser = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            controller = MediaController(context, browser.sessionToken).also { it.registerCallback(callback, Handler(Looper.getMainLooper())) }
            ready.complete(true)
            changed()
        }
        override fun onConnectionFailed() { ready.complete(false) }
        override fun onConnectionSuspended() { controller?.unregisterCallback(callback); controller = null; changed() }
    }, null)

    fun connect() = browser.connect()

    fun play(id: String, extras: Bundle?): Boolean {
        val controls = controller?.transportControls ?: return false
        controls.playFromMediaId(id, extras)
        return true
    }

    fun snapshot(): NowPlaying? = controller?.let(::platformNowPlaying)

    fun customAction(id: String): Boolean? {
        val session = controller ?: return null
        val state = session.playbackState ?: return null
        val action = state.customActions.firstOrNull { it.action == id } ?: return false
        session.transportControls.sendCustomAction(action.action, action.extras ?: Bundle.EMPTY)
        return true
    }

    override fun close() {
        ready.cancel()
        controller?.unregisterCallback(callback)
        controller = null
        browser.disconnect()
    }
}
