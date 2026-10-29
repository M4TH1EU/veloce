package ch.broillet.veloce.media

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ch.broillet.veloce.VeloceApplication

class MediaAccessService : NotificationListenerService() {
    private val app get() = application as VeloceApplication

    override fun onListenerConnected() {
        app.media.connect()
        try { app.notifications.connect(activeNotifications ?: emptyArray()) }
        catch (_: SecurityException) { app.notifications.disconnect() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val ranking = Ranking()
        val alert = rankingMap.getRanking(sbn.key, ranking) && ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT &&
            ranking.matchesInterruptionFilter() && ranking.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK == 0
        app.notifications.posted(sbn, alert)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        app.notifications.remove(sbn.key)
    }

    override fun onListenerDisconnected() {
        app.media.disconnect()
        app.notifications.disconnect()
    }

    override fun onDestroy() {
        app.media.disconnect()
        app.notifications.disconnect()
        super.onDestroy()
    }
}
