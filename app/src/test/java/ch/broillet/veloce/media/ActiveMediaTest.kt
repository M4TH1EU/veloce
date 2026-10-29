package ch.broillet.veloce.media

import android.app.Application
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
}
