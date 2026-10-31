package ch.broillet.veloce.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaArtwork(val uri: Uri? = null, val data: ByteArray? = null, val bitmap: Bitmap? = null, val cacheKey: String? = null)
data class MediaAction(val id: String, val label: String, val iconResource: Int = 0,
    val iconPackage: String? = null, val iconUri: Uri? = null, val enabled: Boolean = true)

data class NowPlaying(
    val packageName: String,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val actions: Long,
    val artwork: MediaArtwork? = null,
    val customActions: List<MediaAction> = emptyList(),
    val speed: Float = 1f,
    val positionMs: Long = -1,
    val durationMs: Long = 0,
    val updatedAtMs: Long = 0,
    val advancing: Boolean = playing,
    val mediaId: String? = null,
    val album: String? = null,
) {
    fun positionAt(elapsedRealtime: Long): Long {
        if (positionMs < 0) return positionMs
        val elapsed = if (advancing && updatedAtMs > 0) (elapsedRealtime - updatedAtMs).coerceAtLeast(0) else 0
        return (positionMs + (elapsed * speed).toLong()).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}

class ActiveMedia(context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    val listener = ComponentName(context, MediaAccessService::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<MediaController>()
    private val current = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = current.asStateFlow()
    private val allTracks = MutableStateFlow<List<NowPlaying>>(emptyList())
    val tracks = allTracks.asStateFlow()
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
        val snapshots = controllers.map(::platformNowPlaying)
        allTracks.value = snapshots
        current.value = snapshots.getOrNull(controllers.indexOf(active))
    }

    private fun controller(packageName: String?): MediaController? = if (packageName == null) selected else
            controllers.firstOrNull { it.packageName == packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull { it.packageName == packageName }

    fun customAction(id: String, packageName: String?): Boolean {
        val controller = controller(packageName) ?: return false
        val action = controller.playbackState?.customActions?.firstOrNull { it.action == id } ?: return false
        return try {
            controller.transportControls.sendCustomAction(action.action, action.extras ?: Bundle.EMPTY)
            true
        } catch (_: SecurityException) { refresh(); false }
    }

    fun setSpeed(speed: Float, packageName: String?): Boolean {
        val controller = controller(packageName) ?: return false
        if (!speed.isFinite() || speed !in .25f..3f || (controller.playbackState?.actions ?: 0) and PlaybackState.ACTION_SET_PLAYBACK_SPEED == 0L) return false
        return try { controller.transportControls.setPlaybackSpeed(speed); true }
        catch (_: SecurityException) { refresh(); false }
    }

    fun seekTo(positionMs: Long, packageName: String? = null): Boolean {
        val controller = controller(packageName) ?: return false
        if (positionMs < 0 || (controller.playbackState?.actions ?: 0) and PlaybackState.ACTION_SEEK_TO == 0L) return false
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        return try {
            controller.transportControls.seekTo(positionMs.coerceAtMost(duration ?: Long.MAX_VALUE))
            true
        } catch (_: SecurityException) { refresh(); false }
    }

    fun control(action: Long, packageName: String? = null): Boolean {
        val controller = controller(packageName) ?: return false
        val actions = controller.playbackState?.actions ?: 0
        val command = if (action == PlaybackState.ACTION_PLAY_PAUSE) {
            val next = if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
            if (actions and next != 0L) next else action
        } else action
        if (actions and command == 0L) return false
        return try {
            when (command) {
                PlaybackState.ACTION_PLAY -> controller.transportControls.play()
                PlaybackState.ACTION_PAUSE -> controller.transportControls.pause()
                PlaybackState.ACTION_PLAY_PAUSE -> {
                    val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    return down && up
                }
                PlaybackState.ACTION_SKIP_TO_PREVIOUS -> controller.transportControls.skipToPrevious()
                PlaybackState.ACTION_SKIP_TO_NEXT -> controller.transportControls.skipToNext()
                PlaybackState.ACTION_REWIND -> controller.transportControls.rewind()
                PlaybackState.ACTION_FAST_FORWARD -> controller.transportControls.fastForward()
                else -> return false
            }
            true
        } catch (_: SecurityException) {
            refresh()
            false
        }
    }

    fun key(code: Int, library: MediaLibrary? = null): Boolean {
        val action = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackState.ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackState.ACTION_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlaybackState.ACTION_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            KeyEvent.KEYCODE_MEDIA_REWIND -> PlaybackState.ACTION_REWIND
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlaybackState.ACTION_FAST_FORWARD
            else -> return false
        }
        val active = nowPlaying.value
        val browser = library?.nowPlaying
        val track = active?.takeIf { it.playing } ?: browser?.takeIf { it.playing } ?: active ?: browser
        if (track != null) return if (track === browser) library.control(action) else control(action, track.packageName)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }
}
