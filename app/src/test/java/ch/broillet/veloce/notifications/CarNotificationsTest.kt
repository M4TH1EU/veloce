package ch.broillet.veloce.notifications

import android.app.Application
import android.app.Notification
import android.app.Person
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
    private val context get() = RuntimeEnvironment.getApplication()
    private fun notice(id: Int = 1, text: String = "Hello", build: Notification.Builder.() -> Unit = {}): StatusBarNotification {
        val notification = Notification.Builder(context, "messages").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Alice").setContentText(text).apply(build).build()
        return StatusBarNotification("chat.app", "chat.app", id, null, 1000, 0, 0, notification, Process.myUserHandle(), id.toLong())
    }

    @Test fun seedsWithoutAlertingAndTracksPostUpdateRemovalAndDisconnect() {
        val store = CarNotifications(context)
        val initial = notice()
        store.connect(arrayOf(initial))
        assertTrue(store.connected.value)
        assertEquals("Hello", store.notices.value.single().text)
        assertNull(store.alert.value)
        store.posted(notice(text = "Updated"), true)
        assertEquals(1, store.notices.value.size)
        assertEquals("Updated", store.alert.value?.notice?.text)
        store.dismissAlert(store.alert.value!!.sequence)
        store.posted(notice(text = "Updated"), true)
        assertNull(store.alert.value)
        store.remove(initial.key)
        assertTrue(store.notices.value.isEmpty())
        store.posted(notice(2), true)
        store.disconnect()
        assertFalse(store.connected.value)
        assertTrue(store.notices.value.isEmpty())
        assertNull(store.alert.value)
    }

    @Test fun filtersPrivateSystemNoiseButKeepsNormalAndSilentNotifications() {
        val store = CarNotifications(context)
        store.connect(arrayOf(
            notice(1) { setOngoing(true) },
            notice(2) { setVisibility(Notification.VISIBILITY_SECRET) },
            notice(3) { setGroup("chat"); setGroupSummary(true) },
            notice(4) { setLocalOnly(true) },
            notice(5) { extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, android.os.Bundle()) },
            notice(6),
        ))
        assertEquals(1, store.notices.value.size)
        store.posted(notice(7, "Silent"), false)
        assertEquals(2, store.notices.value.size)
        assertNull(store.alert.value)
    }

    @Test fun onlyAlertOnceUpdatesTheInboxWithoutAnotherBanner() {
        val store = CarNotifications(context)
        store.posted(notice() { setOnlyAlertOnce(true) }, true)
        store.dismissAlert(store.alert.value!!.sequence)
        store.posted(notice(text = "New text") { setOnlyAlertOnce(true) }, true)
        assertEquals("New text", store.notices.value.single().text)
        assertNull(store.alert.value)
    }

    @Test fun updatedSecretNotificationsAreRemovedAndAnOldTimerCannotDismissANewerAlert() {
        val store = CarNotifications(context)
        store.posted(notice(), true)
        val old = store.alert.value!!.sequence
        store.posted(notice(2), true)
        store.dismissAlert(old)
        assertEquals(notice(2).key, store.alert.value!!.notice.key)
        store.posted(notice(2) { setVisibility(Notification.VISIBILITY_SECRET) }, true)
        assertNull(store.alert.value)
        assertEquals(notice().key, store.notices.value.single().key)
    }

    @Test fun readsExpandedAndMessagingTextAndBoundsTheInbox() {
        val store = CarNotifications(context)
        store.posted(notice() { setStyle(Notification.BigTextStyle().bigText("Expanded body")) }, false)
        assertEquals("Expanded body", store.notices.value.single().text)
        store.posted(notice(2) {
            setStyle(Notification.MessagingStyle(Person.Builder().setName("Me").build())
                .setConversationTitle("Group").addMessage("Message", 100, Person.Builder().setName("Bob").build()))
        }, false)
        assertEquals("Group", store.notices.value.first().title)
        assertEquals("Bob: Message", store.notices.value.first().text)
        (3..60).forEach { store.posted(notice(it), false) }
        assertEquals(50, store.notices.value.size)
        assertEquals(60L, store.notices.value.first().postedAt)
    }
}
