package ch.broillet.veloce.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(
    val packageName: String,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val actions: Long,
)

class ActiveMedia(context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    val listener = ComponentName(context, MediaAccessService::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<MediaController>()
    private val current = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = current.asStateFlow()
    private val connected = MutableStateFlow(false)
    val isConnected = connected.asStateFlow()
    private var selected: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { replace(it.orEmpty()) }

    fun hasAccess(): Boolean = notifications.isNotificationListenerAccessGranted(listener)

    fun connect() {
        if (connected.value) return
        try {
            manager.addOnActiveSessionsChangedListener(sessions, listener, main)
            connected.value = true
            refresh()
        } catch (_: SecurityException) {
            disconnect()
        }
    }

    fun disconnect() {
        manager.removeOnActiveSessionsChangedListener(sessions)
        connected.value = false
        replace(emptyList())
    }

    private fun refresh() {
        try {
            replace(manager.getActiveSessions(listener))
        } catch (_: SecurityException) {
            disconnect()
        }
    }

    private fun replace(sessions: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        controllers.addAll(sessions)
        controllers.forEach { it.registerCallback(callback, main) }
        publish()
    }

    private fun publish() {
        val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.sessionToken == selected?.sessionToken }
            ?: controllers.firstOrNull()
        selected = active
        current.value = active?.let {
            val metadata = it.metadata
            NowPlaying(
                it.packageName,
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                it.playbackState?.state == PlaybackState.STATE_PLAYING,
                it.playbackState?.actions ?: 0,
            )
        }
    }

    fun control(action: Long): Boolean {
        val controller = selected ?: return false
        if ((controller.playbackState?.actions ?: 0) and action == 0L) return false
        return try {
            when (action) {
                PlaybackState.ACTION_PLAY -> controller.transportControls.play()
                PlaybackState.ACTION_PAUSE -> controller.transportControls.pause()
                PlaybackState.ACTION_PLAY_PAUSE -> {
                    val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    return down && up
                }
                PlaybackState.ACTION_SKIP_TO_PREVIOUS -> controller.transportControls.skipToPrevious()
                PlaybackState.ACTION_SKIP_TO_NEXT -> controller.transportControls.skipToNext()
                else -> return false
            }
            true
        } catch (_: SecurityException) {
            refresh()
            false
        }
    }
}
