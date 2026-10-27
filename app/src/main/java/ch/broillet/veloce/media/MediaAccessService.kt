package ch.broillet.veloce.media

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import ch.broillet.veloce.VeloceApplication

class MediaAccessService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val media get() = (application as VeloceApplication).media

    override fun onListenerConnected() {
        main.post { media.connect() }
    }

    override fun onListenerDisconnected() {
        main.post { media.disconnect() }
    }

    override fun onDestroy() {
        main.post { media.disconnect() }
        super.onDestroy()
    }
}
