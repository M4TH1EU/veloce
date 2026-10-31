package ch.broillet.veloce.lyrics

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

data class LyricsState(val document: LyricsDocument? = null, val loading: Boolean = true)

class LyricsRepository internal constructor(private val cache: LyricsCache, private val providers: List<LyricsProvider>,
    private val clock: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(LyricsCache(File(context.cacheDir, "lyrics-v1")), NetworkLyricsHttp().let {
        listOf(BetterLyricsProvider(it), LrclibProvider(it), KugouProvider(it))
    })
    private val misses = LinkedHashMap<String, Long>()

    fun lyrics(track: LyricsTrack, refresh: Boolean = false): Flow<LyricsState> = flow {
        val cached = withContext(Dispatchers.IO) { cache.read(track.key) }
        var best = if (refresh) null else cached?.document
        val fresh = cached != null && cached.checkedAt > 0 && clock() - cached.checkedAt < 24 * 60 * 60 * 1_000
        val miss = synchronized(misses) { misses[track.key]?.let { clock() - it < 5 * 60 * 1_000 } == true }
        if (!refresh && (fresh || miss)) { emit(LyricsState(cached?.document, false)); return@flow }
        emit(LyricsState(cached?.document))
        if (track.title.isBlank() || track.artist.isBlank()) { emit(LyricsState(best, false)); return@flow }
        coroutineScope {
            val answers = Channel<LyricsDocument?>(providers.size)
            providers.forEach { provider -> launch(Dispatchers.IO) {
                val answer = try { withTimeoutOrNull(12_000) { provider.find(track) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                answers.send(answer)
            } }
            repeat(providers.size) {
                val candidate = answers.receive()
                if (candidate != null && (candidate.quality > (best?.quality ?: 0) ||
                        (best?.instrumental == true && !candidate.instrumental && candidate.quality >= checkNotNull(best).quality))) {
                    best = candidate
                    best = withContext(Dispatchers.IO) { cache.write(track.key, CachedLyrics(candidate, cached?.checkedAt ?: 0), replace = refresh).document }
                    emit(LyricsState(best))
                }
            }
            answers.close()
        }
        best = best ?: cached?.document
        best?.let { result -> best = withContext(Dispatchers.IO) { cache.write(track.key, CachedLyrics(result, clock()), replace = refresh).document } }
            ?: synchronized(misses) {
                misses[track.key] = clock()
                while (misses.size > 64) misses.remove(misses.keys.first())
            }
        emit(LyricsState(best, false))
    }
}
