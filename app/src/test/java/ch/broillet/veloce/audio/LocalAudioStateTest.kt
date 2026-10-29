package ch.broillet.veloce.audio

import android.Manifest
import android.app.Application
import android.media.AudioManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@Suppress("DEPRECATION") // Legacy shadows keep these non-rendering tests portable to Linux ARM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class LocalAudioStateTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val setting = "disable_screen_share_protections_for_apps_and_notifications"

    @Test fun cleanupAndProcessRestartRestoreAcquiredMuteAndSharingProtection() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        for (restart in listOf(false, true)) {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            Settings.Global.putInt(context.contentResolver, setting, 0)
            val state = LocalAudioState(context) { fail(it) }
            state.acquire()
            assertTrue(manager.isStreamMute(AudioManager.STREAM_MUSIC))
            assertEquals(1, Settings.Global.getInt(context.contentResolver, setting))
            if (restart) LocalAudioState.recover(context) { fail(it) } else state.close()
            state.close()
            assertFalse(manager.isStreamMute(AudioManager.STREAM_MUSIC))
            assertEquals(0, Settings.Global.getInt(context.contentResolver, setting))
        }
    }

    @Test fun cleanupPreservesExistingUserSettings() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        Settings.Global.putInt(context.contentResolver, setting, 1)
        val state = LocalAudioState(context) { fail(it) }
        state.acquire()
        state.close()
        assertTrue(manager.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, setting))
    }
}
