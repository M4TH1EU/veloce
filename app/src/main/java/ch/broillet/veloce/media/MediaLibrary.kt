package ch.broillet.veloce.media

import android.content.Context
import android.media.session.PlaybackState
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.broillet.veloce.apps.CarApp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import java.io.Closeable

data class MediaEntry(val id: String, val title: String, val subtitle: String, val browsable: Boolean, val playable: Boolean)
data class MediaFolder(val id: String, val title: String, val page: Int = 0)
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
            if (!closed && parentId == path.lastOrNull()?.id) refresh()
        }
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
        if (subscribed != folder.id) {
            if (client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)) subscribed?.let { client.unsubscribe(it) }
            subscribed = folder.id
            if (client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)) {
                val params = LibraryParams.Builder().setExtras(Bundle().apply {
                    putInt("android.media.browse.extra.PAGE", folder.page)
                    putInt("android.media.browse.extra.PAGE_SIZE", PAGE_SIZE)
                }).build()
                client.subscribe(folder.id, params)
            }
        }
        load = scope.launch {
            try {
                val items = withTimeout(8_000) { client.getChildren(folder.id, folder.page, PAGE_SIZE, null).await() }.valueOrThrow()
                page = LibraryPage(folder, items.take(PAGE_SIZE).map { item ->
                    val metadata = item.mediaMetadata
                    MediaEntry(item.mediaId, (metadata.displayTitle ?: metadata.title)?.toString() ?: item.mediaId,
                        (metadata.subtitle ?: metadata.artist)?.toString().orEmpty(), metadata.isBrowsable == true, metadata.isPlayable == true)
                }, loading = false, more = items.size >= PAGE_SIZE)
            } catch (timeout: TimeoutCancellationException) { libraryFailure(IllegalStateException("Library request timed out")) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { libraryFailure(failure) }
        }
    }

    fun play(entry: MediaEntry): Boolean {
        val client = browser ?: return false
        if (!entry.playable || closed) return false
        if (!client.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM) ||
            !client.isCommandAvailable(Player.COMMAND_PREPARE) || !client.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            playbackError = "This app does not allow playback from its library"
            return false
        }
        return try {
            playbackError = null
            client.setMediaItem(MediaItem.Builder().setMediaId(entry.id).build())
            client.prepare()
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
            }
            true
        } catch (failure: Exception) { playbackError = failure.message; false }
    }

    private fun publish() {
        val client = browser ?: return
        var actions = 0L
        if (client.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) actions = actions or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
        if (client.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (client.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        val metadata = client.mediaMetadata
        nowPlaying = NowPlaying(app.component.packageName, (metadata.displayTitle ?: metadata.title)?.toString(),
            metadata.artist?.toString(), client.isPlaying || (client.playWhenReady && client.playbackState == Player.STATE_BUFFERING), actions)
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
        nowPlaying = null
    }

    companion object { const val PAGE_SIZE = 40 }
}
