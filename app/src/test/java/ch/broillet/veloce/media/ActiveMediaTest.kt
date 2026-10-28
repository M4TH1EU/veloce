package ch.broillet.veloce.media

import android.app.Application
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
