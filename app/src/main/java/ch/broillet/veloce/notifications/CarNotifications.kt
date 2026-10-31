package ch.broillet.veloce.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CarNotice(val key: String, val app: String, val title: String, val text: String, val postedAt: Long, val packageName: String = "")
data class NoticeAlert(val notice: CarNotice, val sequence: Long, val createdAt: Long = SystemClock.elapsedRealtime())

class CarNotifications(private val context: Context) {
    private val current = MutableStateFlow<List<CarNotice>>(emptyList())
    val notices = current.asStateFlow()
    private val latest = MutableStateFlow<NoticeAlert?>(null)
    val alert = latest.asStateFlow()
    private val available = MutableStateFlow(false)
    val connected = available.asStateFlow()
    private var sequence = 0L

    fun connect(notifications: Array<StatusBarNotification>) {
        current.value = notifications.mapNotNull(::read).sortedByDescending { it.postedAt }.take(50)
        latest.value = null
        available.value = true
    }

    fun posted(notification: StatusBarNotification, interrupt: Boolean) {
        val notice = read(notification)
        val previous = current.value.firstOrNull { it.key == notification.key }
        if (notice == null) { remove(notification.key); return }
        current.value = (listOf(notice) + current.value.filterNot { it.key == notice.key }).sortedByDescending { it.postedAt }.take(50)
        val changed = previous == null || previous.title != notice.title || previous.text != notice.text
        if (changed && latest.value?.notice?.key == notice.key) latest.value = null
        if (interrupt && changed && (previous == null || notification.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)) {
            latest.value = NoticeAlert(notice, ++sequence)
        }
    }

    fun remove(key: String) {
        current.value = current.value.filterNot { it.key == key }
        if (latest.value?.notice?.key == key) latest.value = null
    }

    fun dismissAlert(sequence: Long) {
        if (latest.value?.sequence == sequence) latest.value = null
    }

    fun disconnect() {
        current.value = emptyList()
        latest.value = null
        available.value = false
    }

    private fun read(sbn: StatusBarNotification): CarNotice? {
        val notification = sbn.notification
        if (sbn.packageName == context.packageName || sbn.isOngoing || notification.visibility == Notification.VISIBILITY_SECRET ||
            notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_LOCAL_ONLY) != 0 ||
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val extras = notification.extras
        val message = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)).lastOrNull()
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = if (message != null) listOfNotNull(message.senderPerson?.name, message.text).joinToString(": ") else
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull())?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val app = try {
            context.packageManager.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(context.packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) { sbn.packageName }
        return CarNotice(sbn.key, app.take(100), title.take(200), text.take(1000), sbn.postTime, sbn.packageName)
    }
}
