package ch.broillet.veloce.media

import android.app.Application
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.os.Bundle
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

    @Test fun customActionsUseOnlyTheSelectedSessionsAdvertisedCommandsAndExtras() {
        val context = RuntimeEnvironment.getApplication()
        val session = MediaSession(context, "audiobook")
        val controller = MediaController(context, session.sessionToken)
        val media = ActiveMedia(context)
        val extras = Bundle().apply { putLong("amount", 30_000) }
        try {
            shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1.5f)
                .addCustomAction(PlaybackState.CustomAction.Builder("jump", "Jump", 1).setExtras(extras).build()).build())
            shadowOf(context.getSystemService(MediaSessionManager::class.java)).addController(controller)
            media.connect()
            assertEquals(listOf(MediaAction("jump", "Jump", 1, context.packageName)), media.nowPlaying.value!!.customActions)
            assertEquals(1.5f, media.nowPlaying.value!!.speed)
            assertFalse(media.customAction("unknown", null))
            assertTrue(media.customAction("jump", null))
            assertEquals("jump", shadowOf(controller.transportControls).customAction)
            assertEquals(30_000L, shadowOf(controller.transportControls).customActionArgs!!.getLong("amount"))
            assertFalse(media.setSpeed(Float.NaN, null))
            assertFalse(media.setSpeed(1.5f, null))
        } finally { media.disconnect(); session.release() }
    }

    @Test fun seekingTargetsTheRequestedAppChecksSupportAndClampsToItsDuration() {
        val context = RuntimeEnvironment.getApplication()
        val target = MediaSession(context, "target")
        val other = MediaSession(context, "other")
        val controller = MediaController(context, target.sessionToken)
        val otherController = MediaController(context, other.sessionToken)
        val media = ActiveMedia(context)
        try {
            shadowOf(controller).setPackageName("target.app")
            shadowOf(otherController).setPackageName("other.app")
            shadowOf(controller).setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_SEEK_TO)
                .setState(PlaybackState.STATE_PAUSED, 30_000, 1f).build())
            shadowOf(otherController).setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_SEEK_TO)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(controller).setMetadata(MediaMetadata.Builder().putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000).build())
            val manager = shadowOf(context.getSystemService(MediaSessionManager::class.java))
            manager.addController(otherController)
            manager.addController(controller)
            media.connect()
            assertTrue(media.seekTo(90_000, "target.app"))
            assertEquals(90_000L, shadowOf(controller.transportControls).seekToPositionMs)
            assertNotEquals(PlaybackState.ACTION_SEEK_TO, shadowOf(otherController.transportControls).lastPerformedAction)
            assertTrue(media.seekTo(300_000, "target.app"))
            assertEquals(180_000L, shadowOf(controller.transportControls).seekToPositionMs)
            assertFalse(media.seekTo(-1, "target.app"))
            assertFalse(media.seekTo(10_000, "missing.app"))
            shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
            assertFalse(media.seekTo(10_000, "target.app"))
        } finally { media.disconnect(); target.release(); other.release() }
    }
}
