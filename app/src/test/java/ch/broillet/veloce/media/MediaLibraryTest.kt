@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package ch.broillet.veloce.media

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Looper
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.legacy.MediaBrowserServiceCompat as CompatBrowserService
import androidx.media3.session.legacy.MediaBrowserCompat as CompatBrowser
import androidx.media3.session.legacy.MediaSessionCompat as CompatSession
import androidx.media3.session.legacy.PlaybackStateCompat as CompatState
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTransportControls
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
        registerService(component, MediaLibraryService.SERVICE_INTERFACE, service.get().onBind(Intent(MediaLibraryService.SERVICE_INTERFACE)))
        library = MediaLibrary(context, CarApp(component, "Music", AppKind.AUDIO), {})
    }

    @After fun cleanup() { library.close(); service.destroy() }

    @Test fun browsesBoundedPagesAndFoldersAndPlaysThroughTheBrowsedSession() {
        library.connect()
        await { !library.page.loading }
        assertNull(library.page.error)
        assertEquals("root", library.page.folder?.id)
        assertEquals(MediaLibrary.PAGE_SIZE, library.page.entries.size)
        assertTrue(library.page.more)
        library.changePage(1)
        await { !library.page.loading }
        assertEquals(1, library.page.entries.size)
        assertFalse(library.page.more)
        assertTrue(service.get().requests.all { it.third == MediaLibrary.PAGE_SIZE })
        library.changePage(-1)
        await { !library.page.loading }
        library.open(library.page.entries.first())
        await { !library.page.loading }
        assertEquals("folder", library.page.folder?.id)
        assertEquals("track", library.page.entries.single().id)
        assertTrue(library.play(library.page.entries.single()))
        await { service.get().player.playWhenReady }
        assertEquals("track", service.get().player.currentMediaItem?.mediaId)
        val media = ActiveMedia(RuntimeEnvironment.getApplication())
        assertTrue(media.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, library))
        await { !service.get().player.playWhenReady }
        assertTrue(library.back())
        await { !library.page.loading }
        assertEquals("root", library.page.folder?.id)
        assertFalse(library.back())
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

    @Test fun aFullLastPageOrRepeatedPageDoesNotAdvertiseANonworkingNextButton() {
        service.get().rootCount = MediaLibrary.PAGE_SIZE
        library.connect()
        await { !library.page.loading }
        assertEquals(MediaLibrary.PAGE_SIZE, library.page.entries.size)
        assertFalse(library.page.more)
        service.get().rootCount = MediaLibrary.PAGE_SIZE + 5
        service.get().repeatPage = true
        library.refresh()
        await { !library.page.loading }
        assertFalse(library.page.more)
        library.changePage(1)
        assertEquals(0, library.page.folder!!.page)
        assertTrue(service.get().requests.any { it.second == 1 })
    }

    @Test fun appProvidedCoverAndChildLayoutHintsSurviveOpeningAFolder() {
        service.get().folderHints = true
        library.connect()
        await { !library.page.loading }
        assertEquals(MediaPresentation.CATEGORY, library.page.entries.first().presentation)
        assertEquals(MediaPresentation.LIST, library.page.entries[1].presentation)
        library.open(library.page.entries.first())
        await { !library.page.loading }
        assertEquals(MediaPresentation.LIST, library.page.entries.single().presentation)
    }

    @Test fun nativePlaybackPreservesTheBrowsedRequestInsteadOfRebuildingATrackId() {
        service.get().playbackContext = true
        library.connect()
        await { !library.page.loading }
        library.open(library.page.entries.first())
        await { !library.page.loading }
        assertTrue(library.play(library.page.entries.single()))
        await { service.get().receivedItems.isNotEmpty() }
        val request = service.get().receivedItems.last()
        assertEquals("original-context", request.requestMetadata.extras!!.getString("test.playback.context"))
        assertEquals("track", request.mediaId)
    }

    @Test fun unpagedLegacyFoldersAreSlicedLocallyWithoutRefetchingEveryPage() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = Robolectric.buildService(UnpagedLibraryService::class.java).create()
        try {
            val component = ComponentName(context, UnpagedLibraryService::class.java)
            val action = "android.media.browse.MediaBrowserService"
            registerService(component, action, legacy.get().onBind(Intent(action)))
            library.close()
            library = MediaLibrary(context, CarApp(component, "Legacy playlists", AppKind.AUDIO), {})
            library.connect()
            await { !library.page.loading }
            assertEquals(MediaLibrary.PAGE_SIZE, library.page.entries.size)
            assertEquals("track-0", library.page.entries.first().id)
            val requests = legacy.get().requests
            library.changePage(1)
            await { !library.page.loading }
            assertEquals("track-40", library.page.entries.first().id)
            library.changePage(1)
            await { !library.page.loading }
            assertEquals("track-80", library.page.entries.first().id)
            assertEquals(15, library.page.entries.size)
            assertFalse(library.page.more)
            library.changePage(-1)
            await { !library.page.loading }
            assertEquals("track-40", library.page.entries.first().id)
            assertEquals(requests, legacy.get().requests)
        } finally { library.close(); legacy.destroy() }
    }

    @Test fun sameBrowserAlsoConnectsToLegacyFrameworkServices() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = Robolectric.buildService(LegacyLibraryService::class.java).create()
        try {
            val component = ComponentName(context, LegacyLibraryService::class.java)
            val action = "android.media.browse.MediaBrowserService"
            registerService(component, action, legacy.get().onBind(Intent(action)))
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

    @Test fun searchWaitsForResultsPaginatesAndReturnsToTheLibrary() {
        service.get().delaySearch = true
        library.connect()
        await { !library.page.loading }
        assertTrue(library.supportsSearch)
        library.search(" books ")
        await { service.get().searches.containsKey("books") }
        assertTrue(library.page.loading)
        assertTrue(service.get().searchPages.isEmpty())
        service.get().completeSearch("books")
        await { !library.page.loading }
        assertEquals(MediaLibrary.PAGE_SIZE, library.page.entries.size)
        assertEquals("books-0", library.page.entries.first().id)
        assertEquals(Uri.parse("content://test/art/books-0"), library.page.entries.first().artwork?.uri)
        service.get().delaySearch = false
        library.changePage(1)
        await { !library.page.loading }
        assertEquals(1, library.page.entries.size)
        assertEquals(1, service.get().searchPages.last())
        assertTrue(library.play(library.page.entries.single()))
        await { service.get().player.playWhenReady }
        assertTrue(library.back())
        await { !library.page.loading }
        assertEquals("root", library.page.folder?.id)
    }

    @Test fun supersededSearchCannotReplaceTheCurrentResults() {
        service.get().delaySearch = true
        library.connect()
        await { !library.page.loading }
        library.search("first")
        await { service.get().searches.containsKey("first") }
        library.search("second")
        await { service.get().searches.containsKey("second") }
        service.get().completeSearch("first")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(library.page.loading)
        service.get().completeSearch("second")
        await { !library.page.loading }
        assertEquals("second-0", library.page.entries.first().id)
    }

    @Test fun exposesCustomActionsSpeedAndNativeSeekCommands() {
        library.connect()
        await { !library.page.loading }
        val action = library.nowPlaying!!.customActions.single()
        assertEquals("jump", action.id)
        assertEquals("Jump", action.label)
        assertTrue(action.iconResource != 0)
        assertEquals(RuntimeEnvironment.getApplication().packageName, action.iconPackage)
        assertFalse(library.customAction("not-advertised"))
        assertTrue(library.customAction("jump"))
        await { service.get().customActions.isNotEmpty() }
        assertEquals("jump", service.get().customActions.single())
        library.open(library.page.entries.first())
        await { !library.page.loading }
        assertTrue(library.play(library.page.entries.single()))
        await { service.get().player.playWhenReady }
        assertTrue(library.setSpeed(1.5f))
        await { service.get().player.playbackParameters.speed == 1.5f }
        assertFalse(library.setSpeed(Float.NaN))
        assertTrue(library.control(PlaybackState.ACTION_FAST_FORWARD))
        await { service.get().player.seeks.contains(Player.COMMAND_SEEK_FORWARD) }
        assertTrue(library.control(PlaybackState.ACTION_REWIND))
        await { service.get().player.seeks.contains(Player.COMMAND_SEEK_BACK) }
    }

    @Test fun absoluteSeekingUsesTheBrowsedSessionAndStopsAfterDisconnect() {
        library.connect()
        await { !library.page.loading }
        library.open(library.page.entries.first())
        await { !library.page.loading }
        assertTrue(library.play(library.page.entries.single()))
        await { service.get().player.playWhenReady }
        assertTrue(library.nowPlaying!!.actions and PlaybackState.ACTION_SEEK_TO != 0L)
        assertFalse(library.seekTo(-1))
        assertTrue(library.seekTo(90_000))
        await { service.get().player.seekTargets.contains(90_000) }
        assertTrue(service.get().player.seeks.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        library.close()
        assertFalse(library.seekTo(30_000))
    }

    @Test @Config(shadows = [RecordingTransport::class])
    fun legacyCompatSessionsSupportSearchPlayFromIdAndCustomActions() {
        RecordingTransport.played = null
        RecordingTransport.action = null
        RecordingTransport.playExtras = null
        RecordingTransport.prepared = 0
        val context = RuntimeEnvironment.getApplication()
        val legacy = Robolectric.buildService(CompatLibraryService::class.java).create()
        try {
            val component = ComponentName(context, CompatLibraryService::class.java)
            val action = "android.media.browse.MediaBrowserService"
            registerService(component, action, legacy.get().onBind(Intent(action)))
            library.close()
            library = MediaLibrary(context, CarApp(component, "Audiobooks", AppKind.AUDIO), {})
            library.connect()
            await { !library.page.loading }
            assertNull(library.page.error)
            assertTrue(library.supportsSearch)
            assertEquals("jump", library.nowPlaying!!.customActions.single().id)
            library.search("book")
            await { !library.page.loading }
            assertEquals("found-book", library.page.entries.single().id)
            assertTrue(library.play(library.page.entries.single()))
            await { RecordingTransport.played == "found-book" }
            assertEquals("playlist-context", RecordingTransport.playExtras?.getString("test.playback.context"))
            assertEquals(0, RecordingTransport.prepared)
            assertTrue(library.customAction("jump"))
            await { RecordingTransport.action == "jump" }
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

    private fun registerService(component: ComponentName, action: String, binder: IBinder?) {
        val context = RuntimeEnvironment.getApplication()
        val packages = shadowOf(context.packageManager)
        packages.addServiceIfNotPresent(component).exported = true
        packages.addIntentFilterForService(component, IntentFilter(action))
        shadowOf(context).setComponentNameAndServiceForBindService(component, binder)
    }

    class LibraryService : MediaLibraryService() {
        lateinit var player: TestPlayer
        lateinit var session: MediaLibrarySession
        var rejectRoot = false
        var delayFolder = false
        var rootCount = 41
        var repeatPage = false
        var folderHints = false
        var playbackContext = false
        val receivedItems = mutableListOf<MediaItem>()
        var pendingFolder: SettableFuture<LibraryResult<ImmutableList<MediaItem>>>? = null
        val requests = mutableListOf<Triple<String, Int, Int>>()
        var delaySearch = false
        val searches = mutableMapOf<String, MediaSession.ControllerInfo>()
        val searchPages = mutableListOf<Int>()
        val customActions = mutableListOf<String>()

        fun completeSearch(query: String) { session.notifySearchResultChanged(checkNotNull(searches[query]), query, 41, null) }

        override fun onCreate() {
            super.onCreate()
            player = TestPlayer()
            session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val command = SessionCommand("jump", Bundle.EMPTY)
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(command).build())
                        .setCustomLayout(listOf(CommandButton.Builder(CommandButton.ICON_FAST_FORWARD).setSessionCommand(command).setDisplayName("Jump").build()))
                        .build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    customActions.add(command.customAction)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
                    searches[query] = browser
                    if (!delaySearch) session.notifySearchResultChanged(browser, query, 41, params)
                    return Futures.immediateFuture(LibraryResult.ofVoid(params))
                }
                override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int,
                    params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    searchPages.add(page)
                    return Futures.immediateFuture(LibraryResult.ofItemList((0..40).map { item("$query-$it") }.drop(page * pageSize).take(pageSize), params))
                }
                override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
                    Futures.immediateFuture(if (rejectRoot) LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED)
                        else LibraryResult.ofItem(item("root", true), params))
                override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                    parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                    requests.add(Triple(parentId, page, pageSize))
                    if (parentId == "folder" && delayFolder) return SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>().also { pendingFolder = it }
                    var folder = item("folder", true)
                    if (folderHints) folder = folder.buildUpon().setMediaMetadata(folder.mediaMetadata.buildUpon().setExtras(Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM)
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    }).build()).build()
                    var track = item("track")
                    if (playbackContext) track = track.buildUpon().setRequestMetadata(MediaItem.RequestMetadata.Builder()
                        .setExtras(Bundle().apply { putString("test.playback.context", "original-context") }).build()).build()
                    val entries = if (parentId == "root") listOf(folder) + (1 until rootCount).map { item("track-$it") } else listOf(track)
                    return Futures.immediateFuture(LibraryResult.ofItemList(entries.drop(if (repeatPage) 0 else page * pageSize).take(pageSize), params))
                }
                override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?) =
                    Futures.immediateFuture(LibraryResult.ofVoid(params))
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    receivedItems.addAll(mediaItems)
                    return Futures.immediateFuture(mediaItems)
                }
            }).build()
        }
        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
        override fun onDestroy() { session.release(); player.release(); super.onDestroy() }
    }

    class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Int>()
        val seekTargets = mutableListOf<Long>()
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
        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            snapshot = snapshot.buildUpon().setPlaybackParameters(playbackParameters).build()
            return Futures.immediateVoidFuture()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks.add(seekCommand)
            seekTargets.add(positionMs)
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

    class CompatLibraryService : CompatBrowserService() {
        private lateinit var session: CompatSession
        override fun onCreate() {
            super.onCreate()
            session = CompatSession(this, "Compat library", null, null, null, null)
            session.setPlaybackState(CompatState.Builder().setActions(CompatState.ACTION_PLAY or CompatState.ACTION_PAUSE or CompatState.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(CompatState.STATE_PAUSED, 0, 1f).addCustomAction("jump", "Jump", 1).build())
            session.setCallback(object : CompatSession.Callback() {}, android.os.Handler(Looper.getMainLooper()))
            setSessionToken(session.sessionToken)
        }
        override fun onGetRoot(packageName: String?, uid: Int, hints: Bundle?) = BrowserRoot("compat-root",
            Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) })
        private fun entry(id: String) = CompatBrowser.MediaItem(androidx.media3.session.legacy.MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(id).setExtras(Bundle().apply { putString("test.playback.context", "playlist-context") }).build(), CompatBrowser.MediaItem.FLAG_PLAYABLE)
        override fun onLoadChildren(parentId: String?, result: Result<MutableList<CompatBrowser.MediaItem>>) = result.sendResult(mutableListOf(entry("book")))
        override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<CompatBrowser.MediaItem>>) = result.sendResult(mutableListOf(entry("found-$query")))
        override fun onDestroy() { session.release(); super.onDestroy() }
    }

    class UnpagedLibraryService : CompatBrowserService() {
        private lateinit var session: CompatSession
        var requests = 0
        override fun onCreate() {
            super.onCreate()
            session = CompatSession(this, "unpaged", null, null, null, null).also { setSessionToken(it.sessionToken) }
        }
        override fun onGetRoot(packageName: String?, uid: Int, hints: Bundle?) = BrowserRoot("all-tracks", null)
        override fun onLoadChildren(parentId: String?, result: Result<MutableList<CompatBrowser.MediaItem>>) = children(result)
        override fun onLoadChildren(parentId: String?, result: Result<MutableList<CompatBrowser.MediaItem>>, options: Bundle?) = children(result)
        private fun children(result: Result<MutableList<CompatBrowser.MediaItem>>) {
            requests++
            result.sendResult((0 until 95).map { index ->
                CompatBrowser.MediaItem(androidx.media3.session.legacy.MediaDescriptionCompat.Builder()
                    .setMediaId("track-$index").setTitle("Track $index").build(), CompatBrowser.MediaItem.FLAG_PLAYABLE)
            }.toMutableList())
        }
        override fun onDestroy() { session.release(); super.onDestroy() }
    }

    @Implements(android.media.session.MediaController.TransportControls::class)
    class RecordingTransport : ShadowTransportControls() {
        companion object { var played: String? = null; var action: String? = null; var playExtras: Bundle? = null; var prepared = 0 }
        @Implementation protected fun playFromMediaId(mediaId: String?, extras: Bundle?) { played = mediaId; playExtras = extras }
        @Implementation protected fun prepareFromMediaId(mediaId: String?, extras: Bundle?) { prepared++ }
        @Implementation override fun sendCustomAction(customAction: String, extras: Bundle?) { action = customAction }
    }

    companion object {
        private fun item(id: String, folder: Boolean = false) = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setIsBrowsable(folder).setIsPlayable(!folder).setArtworkUri(Uri.parse("content://test/art/$id"))
                .setMediaType(if (folder) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
    }
}
