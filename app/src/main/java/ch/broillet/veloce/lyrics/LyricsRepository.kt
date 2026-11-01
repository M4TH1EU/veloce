package ch.broillet.veloce.lyrics

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import java.io.File

data class LyricsState(val document: LyricsDocument? = null, val loading: Boolean = true)

class LyricsRepository internal constructor(private val cache: LyricsCache, private val providers: List<LyricsProvider>,
    private val clock: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(LyricsCache(File(context.cacheDir, "lyrics-v1")), NetworkLyricsHttp().let {
        listOf(BetterLyricsProvider(it), LrclibProvider(it), KugouProvider(it))
    })
    private val misses = LruCache<String, Long>(64)

    fun cacheInfo(): LyricsCacheInfo = cache.info()

    fun clearCache() = synchronized(cache) {
        misses.evictAll()
        cache.clear()
    }

    fun lyrics(track: LyricsTrack, refresh: Boolean = false): Flow<LyricsState> = flow {
        val key = track.key
        val generation = cache.generation
        val cached = withContext(Dispatchers.IO) { cache.read(key) }
        var best = if (refresh) null else cached?.document
        val fresh = cached != null && cached.checkedAt > 0 && clock() - cached.checkedAt < 24 * 60 * 60 * 1_000
        val miss = misses[key]?.let { clock() - it < 5 * 60 * 1_000 } == true
        if (!refresh && (fresh || miss)) { emit(LyricsState(cached?.document, false)); return@flow }
        emit(LyricsState(cached?.document))
        if (track.title.isBlank() || track.artist.isBlank()) { emit(LyricsState(best, false)); return@flow }
        channelFlow {
            providers.forEach { provider -> launch(Dispatchers.IO) {
                val answer = try { withTimeoutOrNull(12_000) { provider.find(track) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                send(answer)
            } }
        }.collect { candidate ->
            if (candidate != null && (candidate.quality > (best?.quality ?: 0) ||
                    (best?.instrumental == true && !candidate.instrumental && candidate.quality >= checkNotNull(best).quality))) {
                best = withContext(Dispatchers.IO) { cache.write(key, CachedLyrics(candidate, cached?.checkedAt ?: 0), replace = refresh, generation = generation).document }
                emit(LyricsState(best))
            }
        }
        val result = best ?: cached?.document
        if (result != null) {
            best = withContext(Dispatchers.IO) { cache.write(key, CachedLyrics(result, clock()), replace = refresh, generation = generation).document }
        }
        synchronized(cache) {
            if (cache.generation == generation) {
                if (result != null) misses.remove(key) else misses.put(key, clock())
            }
        }
        emit(LyricsState(best, false))
    }
}
