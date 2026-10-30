package ch.broillet.veloce.media

import android.content.Context
import android.media.session.PlaybackState
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommands
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.broillet.veloce.apps.CarApp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import java.io.Closeable

data class MediaEntry(val id: String, val title: String, val subtitle: String, val browsable: Boolean, val playable: Boolean,
    val artwork: MediaArtwork? = null)
data class MediaFolder(val id: String, val title: String, val page: Int = 0, val query: String? = null)
data class LibraryPage(
    val folder: MediaFolder? = null,
    val entries: List<MediaEntry> = emptyList(),
    val loading: Boolean = true,
    val more: Boolean = false,
    val error: String? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaLibrary(context: Context, val app: CarApp, private val log: (String) -> Unit) : Closeable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var future: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private var load: Job? = null
    private val path = ArrayDeque<MediaFolder>()
    private var subscribed: String? = null
    private var closed = false
    private var searchReady: Pair<String, CompletableDeferred<Unit>>? = null
    var supportsSearch by mutableStateOf(false)
        private set
    var page by mutableStateOf(LibraryPage())
        private set
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    private val listener = object : MediaBrowser.Listener {
        override fun onError(controller: MediaController, sessionError: SessionError) {
            if (!closed) playbackError = sessionError.message
        }
        override fun onDisconnected(controller: MediaController) {
            if (!closed) {
                page = page.copy(loading = false, error = "The media app disconnected. Reopen it to reconnect.")
                close()
            }
        }
        override fun onChildrenChanged(browser: MediaBrowser, parentId: String, itemCount: Int, params: LibraryParams?) {
            if (!closed && path.lastOrNull()?.query == null && parentId == path.lastOrNull()?.id) refresh()
        }
        override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: LibraryParams?) {
            if (!closed && searchReady?.first == query) searchReady?.second?.complete(Unit)
        }
        override fun onCustomLayoutChanged(controller: MediaController, layout: List<CommandButton>) { publish() }
        override fun onMediaButtonPreferencesChanged(controller: MediaController, mediaButtonPreferences: List<CommandButton>) { publish() }
        override fun onAvailableSessionCommandsChanged(controller: MediaController, commands: SessionCommands) { publish() }
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
        override fun onPlayerError(error: PlaybackException) { if (!closed) playbackError = error.message ?: "Playback failed" }
    }

    fun connect() {
        check(future == null && !closed)
        scope.launch {
            try {
                val pending = MediaBrowser.Builder(context, SessionToken(context, app.component))
                    .setListener(listener).buildAsync().also { future = it }
                val connected = withTimeout(10_000) { pending.await() }
                browser = connected
                connected.addListener(playerListener)
                publish()
                log("Media library connected: ${app.component.flattenToShortString()}")
                val root = withTimeout(8_000) { connected.getLibraryRoot(null).await() }.valueOrThrow()
                check(root.mediaId.isNotEmpty()) { "The media app returned an empty library root" }
                path.add(MediaFolder(root.mediaId, app.label))
                refresh()
            } catch (timeout: TimeoutCancellationException) {
                log("Media library ${app.label}: connection/root request timed out")
                page = page.copy(loading = false, error = "The media app did not respond. Reopen it to retry.")
                close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                libraryFailure(failure)
                if (browser == null) close()
            }
        }
    }

    fun open(entry: MediaEntry) {
        if (closed || !entry.browsable || path.size >= 32 || path.any { it.id == entry.id }) return
        path.add(MediaFolder(entry.id, entry.title))
        refresh()
    }

    fun back(): Boolean {
        if (path.size <= 1 || closed) return false
        path.removeLast()
        refresh()
        return true
    }

    fun search(query: String) {
        val text = query.trim().take(200)
        if (closed || !supportsSearch || text.isEmpty() || path.size >= 32) return
        path.add(MediaFolder("search:$text", "Search: $text", query = text))
        refresh()
    }

    fun changePage(delta: Int) {
        val folder = path.lastOrNull() ?: return
        if (page.loading || (delta > 0 && !page.more) || folder.page + delta < 0) return
        path.removeLast()
        path.add(folder.copy(page = folder.page + delta))
        refresh()
    }

    fun refresh() {
        val client = browser ?: return
        val folder = path.lastOrNull() ?: return
        if (closed) return
        load?.cancel()
        page = if (page.folder == folder) page.copy(loading = true, error = null) else LibraryPage(folder = folder)
        val subscription = folder.id.takeIf { folder.query == null }
        if (subscribed != subscription) {
            if (client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)) subscribed?.let { client.unsubscribe(it) }
            subscribed = subscription
            if (subscription != null && client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)) {
                val params = LibraryParams.Builder().setExtras(Bundle().apply {
                    putInt("android.media.browse.extra.PAGE", folder.page)
                    putInt("android.media.browse.extra.PAGE_SIZE", PAGE_SIZE)
                }).build()
                client.subscribe(folder.id, params)
            }
        }
        load = scope.launch {
            val ready = folder.query?.let { it to CompletableDeferred<Unit>() }
            try {
                val items = withTimeout(8_000) {
                    if (ready == null) client.getChildren(folder.id, folder.page, PAGE_SIZE, null).await()
                    else {
                        searchReady = ready
                        val result = client.search(ready.first, null).await()
                        check(result.resultCode == LibraryResult.RESULT_SUCCESS) { "Search refused (code ${result.resultCode})" }
                        ready.second.await()
                        client.getSearchResult(ready.first, folder.page, PAGE_SIZE, null).await()
                    }
                }.valueOrThrow()
                page = LibraryPage(folder, items.take(PAGE_SIZE).map { item ->
                    val metadata = item.mediaMetadata
                    MediaEntry(item.mediaId, (metadata.displayTitle ?: metadata.title)?.toString() ?: item.mediaId,
                        (metadata.subtitle ?: metadata.artist)?.toString().orEmpty(), metadata.isBrowsable == true, metadata.isPlayable == true,
                        MediaArtwork(metadata.artworkUri, metadata.artworkData))
                }, loading = false, more = items.size >= PAGE_SIZE)
            } catch (timeout: TimeoutCancellationException) { libraryFailure(IllegalStateException("Library request timed out")) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { libraryFailure(failure) }
            finally { if (searchReady === ready) searchReady = null }
        }
    }

    fun play(entry: MediaEntry): Boolean {
        val client = browser ?: return false
        if (!entry.playable || closed) return false
        if (!client.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM) || !client.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            playbackError = "This app does not allow playback from its library"
            return false
        }
        return try {
            playbackError = null
            client.setMediaItem(MediaItem.Builder().setMediaId(entry.id).build())
            if (client.isCommandAvailable(Player.COMMAND_PREPARE)) client.prepare()
            client.play()
            true
        } catch (failure: Exception) { playbackError = failure.message; false }
    }

    fun control(action: Long): Boolean {
        val client = browser ?: return false
        val command = when (action) {
            PlaybackState.ACTION_PLAY, PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY_PAUSE -> Player.COMMAND_PLAY_PAUSE
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> Player.COMMAND_SEEK_TO_PREVIOUS
            PlaybackState.ACTION_SKIP_TO_NEXT -> Player.COMMAND_SEEK_TO_NEXT
            PlaybackState.ACTION_REWIND -> Player.COMMAND_SEEK_BACK
            PlaybackState.ACTION_FAST_FORWARD -> Player.COMMAND_SEEK_FORWARD
            else -> return false
        }
        if (closed || !client.isCommandAvailable(command)) return false
        return try {
            playbackError = null
            when (action) {
                PlaybackState.ACTION_PLAY -> client.play()
                PlaybackState.ACTION_PAUSE -> client.pause()
                PlaybackState.ACTION_PLAY_PAUSE -> if (client.playWhenReady) client.pause() else client.play()
                PlaybackState.ACTION_SKIP_TO_PREVIOUS -> client.seekToPrevious()
                PlaybackState.ACTION_SKIP_TO_NEXT -> client.seekToNext()
                PlaybackState.ACTION_REWIND -> client.seekBack()
                PlaybackState.ACTION_FAST_FORWARD -> client.seekForward()
            }
            true
        } catch (failure: Exception) { playbackError = failure.message; false }
    }

    fun customAction(id: String): Boolean {
        val client = browser ?: return false
        val command = buttons(client).firstOrNull { it.sessionCommand?.customAction == id }?.sessionCommand ?: return false
        if (closed || !client.isSessionCommandAvailable(command)) return false
        playbackError = null
        scope.launch {
            try {
                val result = withTimeout(8_000) { client.sendCustomCommand(command, command.customExtras).await() }
                if (result.resultCode != 0) playbackError = "Media action refused (code ${result.resultCode})"
            } catch (timeout: TimeoutCancellationException) { playbackError = "Media action timed out" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { playbackError = failure.message }
        }
        return true
    }

    fun setSpeed(speed: Float): Boolean {
        val client = browser ?: return false
        if (closed || !speed.isFinite() || speed !in .25f..3f || !client.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return false
        return try { client.setPlaybackSpeed(speed); true }
        catch (failure: Exception) { playbackError = failure.message; false }
    }

    private fun buttons(client: MediaBrowser): List<CommandButton> = (client.mediaButtonPreferences + client.customLayout)
        .filter { it.isEnabled && it.sessionCommand?.commandCode == SessionCommand.COMMAND_CODE_CUSTOM }
        .distinctBy { it.sessionCommand!!.customAction }.take(12)

    private fun publish() {
        val client = browser ?: return
        var actions = 0L
        if (client.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) actions = actions or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
        if (client.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (client.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (client.isCommandAvailable(Player.COMMAND_SEEK_BACK)) actions = actions or PlaybackState.ACTION_REWIND
        if (client.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) actions = actions or PlaybackState.ACTION_FAST_FORWARD
        if (client.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) actions = actions or PlaybackState.ACTION_SET_PLAYBACK_SPEED
        supportsSearch = client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH) &&
            client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
        val metadata = client.mediaMetadata
        nowPlaying = NowPlaying(app.component.packageName, (metadata.displayTitle ?: metadata.title)?.toString(),
            metadata.artist?.toString(), client.isPlaying || (client.playWhenReady && client.playbackState == Player.STATE_BUFFERING), actions,
            MediaArtwork(metadata.artworkUri, metadata.artworkData),
            buttons(client).map { MediaAction(it.sessionCommand!!.customAction, it.displayName.toString()) }, client.playbackParameters.speed)
    }

    private fun libraryFailure(failure: Exception) {
        if (closed) return
        log("Media library ${app.label}: ${failure.message ?: failure.javaClass.simpleName}")
        page = page.copy(loading = false, error = failure.message ?: "Library browsing unavailable")
    }

    private fun <T> LibraryResult<T>.valueOrThrow(): T {
        check(resultCode == LibraryResult.RESULT_SUCCESS) { "Library access refused (code $resultCode). Check sign-in and the app’s access policy." }
        return checkNotNull(value) { "The media app returned no library content" }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        browser?.removeListener(playerListener)
        future?.let(MediaController::releaseFuture)
        browser = null
        supportsSearch = false
        searchReady = null
        nowPlaying = null
    }

    companion object { const val PAGE_SIZE = 40 }
}
