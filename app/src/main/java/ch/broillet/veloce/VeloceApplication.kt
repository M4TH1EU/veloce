package ch.broillet.veloce

import android.app.Application
import ch.broillet.veloce.media.ActiveMedia
import ch.broillet.veloce.connection.Connections

class VeloceApplication : Application() {
    val media by lazy { ActiveMedia(this) }
    val connections by lazy { Connections(this) }
}
