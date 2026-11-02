package ch.broillet.veloce

import android.app.Application
import ch.broillet.veloce.media.ActiveMedia
import ch.broillet.veloce.connection.Connections
import ch.broillet.veloce.audio.LocalAudioState
import ch.broillet.veloce.connection.WirelessAuto
import ch.broillet.veloce.notifications.CarNotifications
import ch.broillet.veloce.lyrics.LyricsRepository
import ch.broillet.veloce.settings.SettingsStore

class VeloceApplication : Application() {
    val settings by lazy { SettingsStore(this) }
    val media by lazy { ActiveMedia(this) }
    val notifications by lazy { CarNotifications(this) }
    val connections by lazy { Connections(this) }
    val wirelessAuto by lazy { WirelessAuto(this, connections) }
    val lyrics by lazy { LyricsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        LocalAudioState.recover(this, connections::log)
        wirelessAuto.restore()
    }
}
