package ch.broillet.veloce.audio

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.Manifest
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalAudioStateTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(AudioManager::class.java)

    @Test fun restoresOnlyTheMuteAcquiredForCapture() {
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        val state = LocalAudioState(context) { fail(it) }
        state.acquire()
        assertTrue(manager.isStreamMute(AudioManager.STREAM_MUSIC))
        state.close()
        state.close()
        assertFalse(manager.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test fun preservesTheUsersExistingMute() {
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        val state = LocalAudioState(context) { fail(it) }
        state.acquire()
        state.close()
        assertTrue(manager.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test fun recoversAnOwnedMuteAfterProcessDeath() {
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        LocalAudioState(context) { fail(it) }.acquire()
        assertTrue(context.getSharedPreferences("audio_recovery", Context.MODE_PRIVATE).getBoolean("muted", false))
        LocalAudioState.recover(context) { fail(it) }
        assertFalse(manager.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test @Config(sdk = [35]) fun restoresOnlyTheScreenShareSettingItChanged() {
        val setting = "disable_screen_share_protections_for_apps_and_notifications"
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putInt(context.contentResolver, setting, 0)
        val state = LocalAudioState(context) { fail(it) }
        state.acquire()
        assertEquals(1, Settings.Global.getInt(context.contentResolver, setting))
        LocalAudioState.recover(context) { fail(it) }
        assertEquals(0, Settings.Global.getInt(context.contentResolver, setting))
        Settings.Global.putInt(context.contentResolver, setting, 1)
        state.acquire()
        state.close()
        assertEquals(1, Settings.Global.getInt(context.contentResolver, setting))
    }
}
