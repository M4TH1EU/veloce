package ch.broillet.veloce.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import java.io.Closeable

internal class LocalAudioState(private val context: Context, private val log: (String) -> Unit) : Closeable {
    private val preferences = context.getSharedPreferences("audio_recovery", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(AudioManager::class.java)

    fun acquire() {
        if (!manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            preferences.edit(commit = true) { putBoolean("muted", true) }
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }
        if (Build.VERSION.SDK_INT >= 35 && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            val previous = Settings.Global.getInt(context.contentResolver, PROTECTIONS, 0)
            if (previous == 0) {
                preferences.edit(commit = true) { putInt("protections", previous) }
                Settings.Global.putInt(context.contentResolver, PROTECTIONS, 1)
            }
        }
    }

    override fun close() {
        if (preferences.getBoolean("muted", false)) runCatching {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            preferences.edit(commit = true) { remove("muted") }
        }.onFailure { log("Could not restore media mute: ${it.message}") }
        if (preferences.contains("protections")) runCatching {
            Settings.Global.putInt(context.contentResolver, PROTECTIONS, preferences.getInt("protections", 0))
            preferences.edit(commit = true) { remove("protections") }
        }.onFailure { log("Could not restore screen-share setting: ${it.message}") }
    }

    companion object {
        private const val PROTECTIONS = "disable_screen_share_protections_for_apps_and_notifications"
        fun recover(context: Context, log: (String) -> Unit) = LocalAudioState(context, log).close()
    }
}
