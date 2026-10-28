package ch.broillet.veloce

import android.app.Application
import ch.broillet.veloce.media.ActiveMedia
import ch.broillet.veloce.connection.Connections
import ch.broillet.veloce.audio.LocalAudioState
import ch.broillet.veloce.connection.WirelessAuto

class VeloceApplication : Application() {
    val media by lazy { ActiveMedia(this) }
    val connections by lazy { Connections(this) }
    val wirelessAuto by lazy { WirelessAuto(this, connections) }

    override fun onCreate() {
        super.onCreate()
        LocalAudioState.recover(this, connections::log)
    }
}
