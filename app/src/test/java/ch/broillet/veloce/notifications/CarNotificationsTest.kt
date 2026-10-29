package ch.broillet.veloce.notifications

import android.app.Application
import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CarNotificationsTest {
    @Test fun privateNotificationsNeverAppearAndAccessLossClearsStoredContents() {
        val context = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        fun notice(id: Int, configure: Notification.Builder.() -> Unit = {}): StatusBarNotification {
            val notification = Notification.Builder(context, "messages").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alice").setContentText("Hello").apply(configure).build()
            return StatusBarNotification("chat.app", "chat.app", id, null, 1000, 0, 0, notification, Process.myUserHandle(), id.toLong())
        }
        val store = CarNotifications(context)
        store.connect(arrayOf(
            notice(1) { setOngoing(true) },
            notice(2) { setVisibility(Notification.VISIBILITY_SECRET) },
            notice(3) { setGroup("chat"); setGroupSummary(true) },
            notice(4) { setLocalOnly(true) },
            notice(5) { extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, android.os.Bundle()) },
            notice(6),
        ))
        assertEquals("Hello", store.notices.value.single().text)
        assertNull(store.alert.value)
        store.posted(notice(7), true)
        assertNotNull(store.alert.value)
        store.posted(notice(7) { setVisibility(Notification.VISIBILITY_SECRET) }, true)
        assertNull(store.alert.value)
        assertEquals(notice(6).key, store.notices.value.single().key)
        store.disconnect()
        assertFalse(store.connected.value)
        assertTrue(store.notices.value.isEmpty())
        assertNull(store.alert.value)
    }
}
