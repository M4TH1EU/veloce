@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package ch.broillet.veloce.media

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.session.PlaybackState
import android.os.Looper
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import ch.broillet.veloce.apps.AppKind
import ch.broillet.veloce.apps.CarApp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaLibraryTest {
    private lateinit var service: ServiceController<LibraryService>
    private lateinit var library: MediaLibrary

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        service = Robolectric.buildService(LibraryService::class.java).create()
        val component = ComponentName(context, LibraryService::class.java)
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
            name = component.className
            packageName = component.packageName
            applicationInfo = context.applicationInfo
            exported = true
        } }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(MediaLibraryService.SERVICE_INTERFACE).setPackage(context.packageName), info)
        shadowOf(context).setComponentNameAndServiceForBindService(component, service.get().onBind(Intent(MediaLibraryService.SERVICE_INTERFACE)))
        library = MediaLibrary(context, CarApp(component, "Music", AppKind.AUDIO), {})
    }

    @After fun cleanup() { library.close(); service.destroy() }

    @Test fun connectsAndBrowsesFoldersWithBackNavigation() {
        library.connect()
        await { !library.page.loading }
        assertNull(library.page.error)
        assertEquals("root", library.page.folder?.id)
        assertEquals(MediaLibrary.PAGE_SIZE, library.page.entries.size)
        assertTrue(library.page.more)
        library.open(library.page.entries.first())
        await { !library.page.loading }
        assertEquals("folder", library.page.folder?.id)
        assertEquals("track", library.page.entries.single().id)
        assertTrue(library.back())
        await { !library.page.loading }
        assertEquals("root", library.page.folder?.id)
        assertFalse(library.back())
    }

    @Test fun requestsBoundedPagesAndRestoresPreviousPage() {
        library.connect()
        await { !library.page.loading }
        library.changePage(1)
        await { !library.page.loading }
        assertEquals(1, library.page.folder?.page)
        assertEquals(1, library.page.entries.size)
        assertFalse(library.page.more)
        assertTrue(service.get().requests.all { it.third == MediaLibrary.PAGE_SIZE })
        library.changePage(-1)
        await { !library.page.loading }
        assertEquals(0, library.page.folder?.page)
    }

    @Test fun libraryPlaybackAndControlsUseTheBrowsedSession() {
        library.connect()
        await { !library.page.loading }
        val entry = library.page.entries[1]
        assertTrue(library.play(entry))
        await { service.get().player.playWhenReady }
        assertEquals(entry.id, service.get().player.currentMediaItem?.mediaId)
        assertTrue(library.control(PlaybackState.ACTION_PAUSE))
        await { !service.get().player.playWhenReady }
        assertFalse(library.control(PlaybackState.ACTION_SET_RATING))
    }

    @Test fun hardwareKeysControlTheLibraryWithoutNotificationAccess() {
        library.connect()
        await { !library.page.loading }
        val media = ActiveMedia(RuntimeEnvironment.getApplication())
        assertTrue(media.key(KeyEvent.KEYCODE_MEDIA_PLAY, library))
        await { service.get().player.playWhenReady }
        assertTrue(media.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, library))
        await { !service.get().player.playWhenReady }
    }

    @Test fun browseRefusalDoesNotDiscardPlaybackControls() {
        service.get().rejectRoot = true
        library.connect()
        await { !library.page.loading }
        assertTrue(library.page.error!!.contains("refused"))
        assertNotNull(library.nowPlaying)
        assertTrue(library.control(PlaybackState.ACTION_PLAY))
    }

    @Test fun lateFolderResultCannotReplaceThePageAfterBack() {
        library.connect()
        await { !library.page.loading }
        service.get().delayFolder = true
        library.open(library.page.entries.first())
        await { service.get().pendingFolder != null }
        assertTrue(library.back())
        await { !library.page.loading }
        service.get().pendingFolder!!.set(LibraryResult.ofItemList(listOf(item("late")), null))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("root", library.page.folder?.id)
        assertFalse(library.page.entries.any { it.id == "late" })
    }

    @Test fun childNotificationsRefreshTheOpenFolder() {
        library.connect()
        await { !library.page.loading }
        val count = service.get().requests.size
        service.get().session.notifyChildrenChanged("root", 41, null)
        await { service.get().requests.size > count && !library.page.loading }
        assertNull(library.page.error)
    }

    @Test fun asynchronousPlaybackRejectionIsVisible() {
        library.connect()
        await { !library.page.loading }
        service.get().session.sendError(SessionError(SessionError.ERROR_PERMISSION_DENIED, "Account needs sign-in"))
        await { library.playbackError != null }
        assertEquals("Account needs sign-in", library.playbackError)
    }

    @Test fun closeWhileConnectingCannotReviveTheLibrary() {
        library.connect()
        library.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(library.nowPlaying)
        assertFalse(library.control(PlaybackState.ACTION_PLAY))
    }

    @Test fun folderTimeoutIsAnErrorRatherThanAnEndlessSpinner() {
        library.connect()
        await { !library.page.loading }
        service.get().delayFolder = true
        library.open(library.page.entries.first())
        await { service.get().pendingFolder != null }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        await { !library.page.loading }
        assertTrue(library.page.error!!.contains("timed out"))
    }

    @Test fun sameBrowserAlsoConnectsToLegacyFrameworkServices() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = Robolectric.buildService(LegacyLibraryService::class.java).create()
        try {
            val component = ComponentName(context, LegacyLibraryService::class.java)
            val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                name = component.className
                packageName = component.packageName
                applicationInfo = context.applicationInfo
                exported = true
            } }
            shadowOf(context.packageManager).addResolveInfoForIntent(Intent("android.media.browse.MediaBrowserService").setPackage(context.packageName), info)
            shadowOf(context).setComponentNameAndServiceForBindService(component, legacy.get().onBind(Intent("android.media.browse.MediaBrowserService")))
            library.close()
            library = MediaLibrary(context, CarApp(component, "Legacy music", AppKind.AUDIO), {})
            library.connect()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            await { !library.page.loading }
            assertNull(library.page.error)
            assertEquals("legacy-track", library.page.entries.single().id)
            assertNotNull(library.nowPlaying)
        } finally { library.close(); legacy.destroy() }
    }

    private fun await(condition: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out: ${library.page}")
    }

    class LibraryService : MediaLibraryService() {
        lateinit var player: TestPlayer
        lateinit var session: MediaLibrarySession
        var rejectRoot = false
        var delayFolder = false
        var pendingFolder: SettableFuture<LibraryResult<ImmutableList<MediaItem>>>? = null
        val requests = mutableListOf<Triple<String, Int, Int>>()

        override fun onCreate() {
            super.onCreate()
            player = TestPlayer()
            session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
                override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
                    Futures.immediateFuture(if (rejectRoot) LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED)
                        else LibraryResult.ofItem(item("root", true), params))
                override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                    parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    requests.add(Triple(parentId, page, pageSize))
                    if (parentId == "folder" && delayFolder) return SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>().also { pendingFolder = it }
                    val entries = if (parentId == "root") listOf(item("folder", true)) + (1..40).map { item("track-$it") } else listOf(item("track"))
                    return Futures.immediateFuture(LibraryResult.ofItemList(entries.drop(page * pageSize).take(pageSize), params))
                }
                override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?) =
                    Futures.immediateFuture(LibraryResult.ofVoid(params))
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>) =
                    Futures.immediateFuture(mediaItems)
            }).build()
        }
        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
        override fun onDestroy() { session.release(); player.release(); super.onDestroy() }
    }

    class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var snapshot = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build()).build()
        override fun getState() = snapshot
        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlaylist(mediaItems.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).build() }).build()
            return Futures.immediateVoidFuture()
        }
        override fun handlePrepare(): ListenableFuture<*> {
            if (snapshot.playlist.isNotEmpty()) snapshot = snapshot.buildUpon().setPlaybackState(Player.STATE_READY).build()
            return Futures.immediateVoidFuture()
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            return Futures.immediateVoidFuture()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }

    class LegacyLibraryService : android.service.media.MediaBrowserService() {
        private lateinit var session: android.media.session.MediaSession
        override fun onCreate() {
            super.onCreate()
            session = android.media.session.MediaSession(this, "Legacy library")
            session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
            sessionToken = session.sessionToken
        }
        override fun onGetRoot(packageName: String, uid: Int, hints: Bundle?) = BrowserRoot("legacy-root", null)
        override fun onLoadChildren(parentId: String, result: Result<MutableList<android.media.browse.MediaBrowser.MediaItem>>) {
            val description = android.media.MediaDescription.Builder().setMediaId("legacy-track").setTitle("Legacy track").build()
            result.sendResult(mutableListOf(android.media.browse.MediaBrowser.MediaItem(description, android.media.browse.MediaBrowser.MediaItem.FLAG_PLAYABLE)))
        }
        override fun onDestroy() { session.release(); super.onDestroy() }
    }

    companion object {
        private fun item(id: String, folder: Boolean = false) = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setIsBrowsable(folder).setIsPlayable(!folder)
                .setMediaType(if (folder) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
    }
}
