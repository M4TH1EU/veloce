package ch.broillet.veloce.media

import android.app.Application
import android.content.Context
import android.media.MediaMetadata
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveMediaTest {
    @Test fun hardwareKeysUseTransportControlsAndTogglePlayPauseWithoutRequiringTheToggleAction() {
        val context = RuntimeEnvironment.getApplication()
        val session = MediaSession(context, "music")
        val controller = MediaController(context, session.sessionToken)
        val manager = shadowOf(context.getSystemService(MediaSessionManager::class.java))
        val media = ActiveMedia(context)
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        try {
            shadowOf(controller).setPlaybackState(PlaybackState.Builder().setActions(actions).setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            manager.addController(controller)
            media.connect()
            val controls = shadowOf(controller.transportControls)
            listOf(KeyEvent.KEYCODE_MEDIA_NEXT to PlaybackState.ACTION_SKIP_TO_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS to PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_PLAY to PlaybackState.ACTION_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE to PlaybackState.ACTION_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to PlaybackState.ACTION_PAUSE).forEach { (key, action) ->
                assertTrue(media.key(key))
                assertEquals(action, controls.lastPerformedAction)
            }
            shadowOf(controller).setPlaybackState(PlaybackState.Builder().setActions(actions).setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
            assertTrue(media.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            assertEquals(PlaybackState.ACTION_PLAY, controls.lastPerformedAction)
            assertFalse(media.key(KeyEvent.KEYCODE_ASSIST))
        } finally { media.disconnect(); session.release() }
    }

    @Test fun noSessionAccessUsesAndroidsDefaultMediaKeyRoute() {
        val context = RuntimeEnvironment.getApplication()
        val media = ActiveMedia(context)
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        assertTrue(media.key(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), audio.dispatchedMediaKeyEvents.map { it.action })
        assertTrue(audio.dispatchedMediaKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT })
    }

    @Test fun publishesAllSessionsEvenWhenAnotherAppIsPlaying() {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
        val media = ActiveMedia(context)
        val sessions = listOf(MediaSession(context, "first"), MediaSession(context, "second"))
        try {
            sessions.forEachIndexed { index, session ->
                val controller = MediaController(context, session.sessionToken)
                shadowOf(controller).apply {
                    setPackageName("music.$index")
                    setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Track $index").build())
                    setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                        .setState(if (index == 0) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, 0, 1f).build())
                }
                manager.addController(controller)
            }
            media.connect()
            assertEquals("music.1", media.nowPlaying.value?.packageName)
            assertEquals(listOf("music.0", "music.1"), media.tracks.value.map { it.packageName })
            assertEquals("Track 0", media.tracks.value.first().title)
            assertFalse(media.control(PlaybackState.ACTION_PAUSE, "missing.app"))
            media.disconnect()
            assertTrue(media.tracks.value.isEmpty())
            assertNull(media.nowPlaying.value)
        } finally { media.disconnect(); sessions.forEach { it.release() } }
    }
}
