package ch.broillet.veloce.media

import android.content.Context
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
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

enum class MediaPresentation { LIST, COVER, CATEGORY }

data class MediaEntry(val id: String, val title: String, val subtitle: String, val browsable: Boolean, val playable: Boolean,
    val artwork: MediaArtwork? = null, val presentation: MediaPresentation = if (browsable) MediaPresentation.COVER else MediaPresentation.LIST,
    val childBrowsableStyle: Int = 0, val childPlayableStyle: Int = 0, val item: MediaItem? = null)
data class MediaFolder(val id: String, val title: String, val page: Int = 0, val query: String? = null,
    val browsableStyle: Int = 0, val playableStyle: Int = 0)
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
    private var legacy: LegacyPlayback? = null
    private var load: Job? = null
    private val path = ArrayDeque<MediaFolder>()
    private var subscribed: String? = null
    private var closed = false
    private var searchReady: Pair<String, CompletableDeferred<Unit>>? = null
    private data class CachedPage(val entries: List<MediaEntry>, val more: Boolean? = null)
    private val pages = LinkedHashMap<MediaFolder, CachedPage>()
    private var fullFolder: Pair<MediaFolder, List<MediaEntry>>? = null
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
                val token = SessionToken(context, app.component)
                if (token.sessionVersion == SessionToken.PLATFORM_SESSION_VERSION) {
                    legacy = LegacyPlayback(context, app.component, ::publish).also { it.connect() }
                }
                val pending = MediaBrowser.Builder(context, token)
                    .setListener(listener).buildAsync().also { future = it }
                val connected = withTimeout(10_000) { pending.await() }
                browser = connected
                legacy?.let { withTimeoutOrNull(3_000) { it.ready.await() } }
                connected.addListener(playerListener)
                publish()
                log("Media library connected: ${app.component.flattenToShortString()}")
                val rootResult = withTimeout(8_000) { connected.getLibraryRoot(null).await() }
                val root = rootResult.valueOrThrow()
                check(root.mediaId.isNotEmpty()) { "The media app returned an empty library root" }
                path.add(MediaFolder(root.mediaId, app.label,
                    browsableStyle = root.mediaMetadata.extras.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
                        .takeIf { it != 0 } ?: rootResult.params?.extras.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
                    playableStyle = root.mediaMetadata.extras.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
                        .takeIf { it != 0 } ?: rootResult.params?.extras.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)))
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
        val parent = path.lastOrNull()
        path.add(MediaFolder(entry.id, entry.title,
            browsableStyle = entry.childBrowsableStyle.takeIf { it != 0 } ?: parent?.browsableStyle ?: 0,
            playableStyle = entry.childPlayableStyle.takeIf { it != 0 } ?: parent?.playableStyle ?: 0))
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
        val parent = path.lastOrNull()
        path.add(MediaFolder("search:$text", "Search: $text", query = text,
            browsableStyle = parent?.browsableStyle ?: 0, playableStyle = parent?.playableStyle ?: 0))
        refresh()
    }

    fun changePage(delta: Int) {
        val folder = path.lastOrNull() ?: return
        if (page.loading || (delta > 0 && !page.more) || folder.page + delta < 0) return
        path.removeLast()
        path.add(folder.copy(page = folder.page + delta))
        loadPage()
    }

    fun refresh() {
        pages.clear()
        fullFolder = null
        loadPage()
    }

    private fun cache(folder: MediaFolder, page: CachedPage) {
        pages[folder] = page
        while (pages.size > 4) pages.remove(pages.keys.first())
    }

    private fun localPage(folder: MediaFolder): LibraryPage? = fullFolder?.takeIf { it.first == folder.copy(page = 0) }?.second?.let { entries ->
        val start = folder.page.toLong() * PAGE_SIZE
        LibraryPage(folder, entries.drop(start.coerceAtMost(entries.size.toLong()).toInt()).take(PAGE_SIZE),
            loading = false, more = start + PAGE_SIZE < entries.size)
    }

    private fun loadPage() {
        val client = browser ?: return
        val folder = path.lastOrNull() ?: return
        if (closed) return
        load?.cancel()
        localPage(folder)?.let { page = it; return }
        val cached = pages[folder]
        val displayed = cached?.entries ?: page.entries.takeIf { page.folder == folder }.orEmpty()
        page = LibraryPage(folder, displayed, loading = cached?.more == null, more = cached?.more == true)
        if (cached?.more != null) return
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
            val ready = folder.query?.takeIf { cached == null }?.let { it to CompletableDeferred<Unit>() }
            try {
                if (ready != null) withTimeout(8_000) {
                    searchReady = ready
                    val result = client.search(ready.first, null).await()
                    check(result.resultCode == LibraryResult.RESULT_SUCCESS) { "Search refused (code ${result.resultCode})" }
                    ready.second.await()
                }
                suspend fun fetch(index: Int): List<MediaEntry> = withTimeout(8_000) {
                    val result = if (folder.query == null) client.getChildren(folder.id, index, PAGE_SIZE, null).await()
                        else client.getSearchResult(folder.query, index, PAGE_SIZE, null).await()
                    result.valueOrThrow().take(MAX_LOCAL_ITEMS).map { entry(it, folder) }
                }
                fun installFull(entries: List<MediaEntry>) {
                    log("Media library ${app.label}: locally paging ${entries.size} returned items")
                    fullFolder = folder.copy(page = 0) to entries
                    page = checkNotNull(localPage(folder))
                }
                val entries = cached?.entries ?: fetch(folder.page)
                if (entries.size > PAGE_SIZE) { installFull(entries); return@launch }
                if (entries.size < PAGE_SIZE) {
                    cache(folder, CachedPage(entries, false))
                    page = LibraryPage(folder, entries, loading = false)
                    return@launch
                }
                page = LibraryPage(folder, entries, loading = true)
                val nextFolder = folder.copy(page = folder.page + 1)
                val next = try { pages[nextFolder]?.entries ?: fetch(nextFolder.page) }
                    catch (_: TimeoutCancellationException) { emptyList() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                if (next.size > PAGE_SIZE) { installFull(next); return@launch }
                val more = next.isNotEmpty() && next.map { it.id } != entries.map { it.id }
                if (next.isNotEmpty() && !more) log("Media library ${app.label}: service repeated its previous page")
                cache(folder, CachedPage(entries, more))
                if (more) cache(nextFolder, CachedPage(next, if (next.size < PAGE_SIZE) false else null))
                page = LibraryPage(folder, entries, loading = false, more = more)
            } catch (timeout: TimeoutCancellationException) { libraryFailure(IllegalStateException("Library request timed out")) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { libraryFailure(failure) }
            finally { if (searchReady === ready) searchReady = null }
        }
    }

    private fun Bundle?.style(key: String): Int = this?.getInt(key, 0) ?: 0

    private fun entry(item: MediaItem, folder: MediaFolder): MediaEntry {
        val metadata = item.mediaMetadata
        val browsable = metadata.isBrowsable == true
        val style = metadata.extras.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM).takeIf { it != 0 }
            ?: if (browsable) folder.browsableStyle else folder.playableStyle
        val collection = metadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM || metadata.mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST ||
            (browsable && (metadata.artworkUri != null || metadata.artworkData != null))
        val presentation = if (!browsable && !collection) MediaPresentation.LIST
            else if (style == MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM) MediaPresentation.CATEGORY
            else MediaPresentation.COVER
        return MediaEntry(item.mediaId, (metadata.displayTitle ?: metadata.title)?.toString() ?: item.mediaId,
            (metadata.subtitle ?: metadata.artist)?.toString().orEmpty(), browsable, metadata.isPlayable == true,
            MediaArtwork(metadata.artworkUri, metadata.artworkData), presentation,
            metadata.extras.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE), metadata.extras.style(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE), item)
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
            val original = entry.item ?: MediaItem.Builder().setMediaId(entry.id).build()
            legacy?.let {
                val extras = Bundle(original.mediaMetadata.extras ?: Bundle.EMPTY).apply { original.requestMetadata.extras?.let(::putAll) }
                check(it.play(entry.id, extras.takeUnless { it.isEmpty })) { "Playback session is not ready. Reopen the app." }
                return true
            }
            client.setMediaItem(original)
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
        if (closed) return false
        try { legacy?.customAction(id)?.let { return it } }
        catch (failure: Exception) { playbackError = failure.message; return false }
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

    fun seekTo(positionMs: Long): Boolean {
        val client = browser ?: return false
        if (closed || positionMs < 0 || !client.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return false
        return try {
            playbackError = null
            client.seekTo(positionMs.coerceAtMost(client.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
            publish()
            true
        } catch (failure: Exception) { playbackError = failure.message; false }
    }

    private fun buttons(client: MediaBrowser): List<CommandButton> = (client.mediaButtonPreferences + client.customLayout)
        .filter { it.sessionCommand?.commandCode == SessionCommand.COMMAND_CODE_CUSTOM }
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
        if (client.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) actions = actions or PlaybackState.ACTION_SEEK_TO
        supportsSearch = client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH) &&
            client.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
        val metadata = client.mediaMetadata
        legacy?.snapshot()?.takeIf { it.title != null }?.let { nowPlaying = it; return }
        nowPlaying = NowPlaying(app.component.packageName, (metadata.displayTitle ?: metadata.title)?.toString(),
            metadata.artist?.toString(), client.isPlaying || (client.playWhenReady && client.playbackState == Player.STATE_BUFFERING), actions,
            MediaArtwork(metadata.artworkUri, metadata.artworkData, cacheKey = artworkIdentity(app.component.packageName,
                metadata.albumTitle?.toString(), metadata.artist?.toString(), client.currentMediaItem?.mediaId, metadata.title?.toString())),
            buttons(client).map { button ->
                val localIcon = CommandButton.getIconResIdForIconConstant(button.icon)
                MediaAction(button.sessionCommand!!.customAction, button.displayName.toString(),
                    if (localIcon != 0) localIcon else button.iconResId,
                    if (localIcon != 0) context.packageName else app.component.packageName, button.iconUri, button.isEnabled)
            }, client.playbackParameters.speed,
            positionMs = client.currentPosition, durationMs = client.duration.takeIf { it > 0 } ?: 0,
            updatedAtMs = SystemClock.elapsedRealtime(), advancing = client.isPlaying, mediaId = client.currentMediaItem?.mediaId,
            album = metadata.albumTitle?.toString())
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
        legacy?.close()
        legacy = null
        future?.let(MediaController::releaseFuture)
        browser = null
        supportsSearch = false
        searchReady = null
        nowPlaying = null
        pages.clear()
        fullFolder = null
    }

    companion object {
        const val PAGE_SIZE = 40
        private const val MAX_LOCAL_ITEMS = 2_000
    }
}
