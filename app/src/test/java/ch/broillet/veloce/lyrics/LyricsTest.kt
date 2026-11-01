package ch.broillet.veloce.lyrics

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LyricsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val track = LyricsTrack("Example", "Singer", "Album", 120_000)
    private val words = LyricsDocument(listOf(LyricLine("Hello world", 1_000, 3_000,
        listOf(LyricWord(1_000, 2_000, 0, 5), LyricWord(2_000, 3_000, 6, 11)))), "Words")

    @Test fun ttmlKeepsSyllablesAndSpacesButOmitsBackgroundAndTranslatedVocals() {
        val text = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
            <p begin="1s" end="4s"><span begin="1s" end="1.5s">Hel</span><span begin="1.5s" end="2s">lo</span> <span><span begin="2s" end="3s">world</span></span><span ttm:role="x-bg"><span begin="2s" end="3s">background</span></span></p>
            <p begin="5s" end="6s"><span ttm:role="x-translation">translation</span>Next line</p>
            </div></body></tt>""".trimIndent()
        val document = LyricsParsers.ttml(text, "TTML")!!
        assertEquals("Hello world", document.lines[0].text)
        assertEquals(listOf("Hel", "lo", "world"), document.lines[0].words.map { document.lines[0].text.substring(it.from, it.until) })
        assertEquals(3, document.quality)
        assertEquals(-1, document.lineAt(999))
        assertEquals(0, document.lineAt(2_000))
        assertEquals(1, document.lineAt(5_000))
        assertEquals("Next line", document.lines[1].text)
    }

    @Test fun lrcUnderstandsRepeatedStampsOffsetsGapsAndEnhancedWordTiming() {
        val repeated = LyricsParsers.lrc("[ar:Singer]\n[offset:100]\n[00:01.00][00:04.00]Example\n[00:02.40]", "LRC", 10_000)!!
        assertEquals(listOf(1_100L, 2_500L, 4_100L), repeated.lines.map { it.startMs })
        assertEquals("", repeated.lines[1].text)
        val enhanced = LyricsParsers.lrc("[00:01.00]<00:01.00>Hello <00:02.00>world<00:03.00>", "LRC", 10_000)!!
        assertEquals("Hello world", enhanced.lines.single().text)
        assertEquals(3, enhanced.quality)
        assertEquals(listOf(2_000L, 3_000L), enhanced.lines.single().words.map { it.endMs })
    }

    @Test fun lyricsfilePreservesWordRangesAndYaml12Text() {
        val raw = """
            version: '1.0'
            lines:
            - text: no more
              start_ms: 1000
              end_ms: 3000
              words:
              - text: no
                start_ms: 1000
                end_ms: 2000
              - text: more
                start_ms: 2000
                end_ms: 3000
        """.trimIndent()
        val document = LyricsParsers.lyricsfile(raw, "LRCLIB")!!
        assertEquals("no more", document.lines.single().text)
        assertEquals(listOf(0, 3), document.lines.single().words.map { it.from })
        assertEquals(3, document.quality)
        assertEquals(3_723_125L, LyricsParsers.time("01:02:03.125"))
        assertEquals(250L, LyricsParsers.time("250ms"))
        assertNull(LyricsParsers.time("NaN"))
    }

    @Test fun xmlExternalDeclarationsAndYamlAliasesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LyricsParsers.ttml("<!DOCTYPE tt [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><tt><p>&leak;</p></tt>", "TTML")
        }
        assertThrows(Exception::class.java) { LyricsParsers.lyricsfile("value: &a [1]\nlines: [*a]", "YAML") }
    }

    @Test fun cacheSurvivesRestartWithoutLosingWordTimingsAndBoundsDiskGrowth() {
        val directory = temporary.newFolder()
        val cache = LyricsCache(directory)
        cache.write(track.key, CachedLyrics(words, 123))
        assertEquals(CachedLyrics(words, 123), LyricsCache(directory).read(track.key))
        repeat(130) { cache.write("song-$it", CachedLyrics(words, it.toLong())) }
        assertTrue(directory.listFiles()!!.count { it.extension == "json" } <= 128)
        assertNull(LyricsCache(directory).read(track.key))
    }

    @Test fun aSlowerLookupCannotDowngradeTheSharedWordTimingCache() {
        val cache = LyricsCache(temporary.newFolder())
        cache.write(track.key, CachedLyrics(words, 100))
        val line = words.copy(lines = words.lines.map { it.copy(words = emptyList()) })
        assertEquals(words, cache.write(track.key, CachedLyrics(line, 200)).document)
        assertEquals(words, cache.read(track.key)!!.document)
        assertEquals(line, cache.write(track.key, CachedLyrics(line, 300), replace = true).document)
    }

    @Test fun clearingLyricsEvictsDiskMemoryAndMisses() = runBlocking {
        val directory = temporary.newFolder()
        val cache = LyricsCache(directory)
        cache.write(track.key, CachedLyrics(words, 100))
        val missing = track.copy(title = "Missing")
        val calls = AtomicInteger()
        val repository = LyricsRepository(cache, listOf(provider { calls.incrementAndGet(); null })) { 1_000 }
        repository.lyrics(missing).toList()
        assertEquals(1, repository.cacheInfo().count)
        assertTrue(repository.cacheInfo().bytes > 0)
        repository.clearCache()
        assertEquals(LyricsCacheInfo(), repository.cacheInfo())
        assertNull(cache.read(track.key))
        assertNull(LyricsCache(directory).read(track.key))
        repository.lyrics(missing).toList()
        assertEquals(2, calls.get())
    }

    @Test fun anInFlightLookupCannotRepopulateAClearedCache() = runBlocking {
        val cache = LyricsCache(temporary.newFolder())
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<LyricsDocument>()
        val repository = LyricsRepository(cache, listOf(provider { started.complete(Unit); answer.await() })) { 1_000 }
        val lookup = async { repository.lyrics(track).toList() }
        started.await()
        repository.clearCache()
        answer.complete(words)
        assertEquals(words, lookup.await().last().document)
        assertEquals(LyricsCacheInfo(), repository.cacheInfo())
        assertNull(cache.read(track.key))
    }

    @Test fun repositoryShowsAnEarlyResultThenUpgradesAndUsesPersistentCache() = runBlocking {
        val directory = temporary.newFolder()
        val cache = LyricsCache(directory)
        val calls = AtomicInteger()
        val line = LyricsDocument(listOf(LyricLine("Hello world", 1_000, 3_000)), "Lines")
        val providers = listOf(provider { calls.incrementAndGet(); line }, provider { delay(30); calls.incrementAndGet(); words })
        val repository = LyricsRepository(cache, providers) { 1_000 }
        val states = repository.lyrics(track).toList()
        assertTrue(states.any { it.document == line })
        assertEquals(LyricsState(words, false), states.last())
        val cached = LyricsRepository(LyricsCache(directory), listOf(provider { fail("Cache should avoid network"); null })) { 2_000 }
        assertEquals(words, cached.lyrics(track).toList().last().document)
        assertEquals(2, calls.get())
    }

    @Test fun closingAfterAPartialAnswerDoesNotBlockTheNextTimingUpgrade() = runBlocking {
        val cache = LyricsCache(temporary.newFolder())
        val line = words.copy(lines = words.lines.map { it.copy(words = emptyList()) }, source = "Lines")
        val first = LyricsRepository(cache, listOf(provider { line }, provider { delay(100); words })) { 1_000 }
        first.lyrics(track).take(2).toList()
        assertEquals(0L, cache.read(track.key)!!.checkedAt)
        val next = LyricsRepository(cache, listOf(provider { words })) { 2_000 }
        assertEquals(words, next.lyrics(track).toList().last().document)
    }

    @Test fun anInstrumentalFlagDoesNotOverrideActualTimedLyrics() = runBlocking {
        val repository = LyricsRepository(LyricsCache(temporary.newFolder()), listOf(
            provider { LyricsDocument(emptyList(), "No vocals", instrumental = true) }, provider { delay(20); words }))
        assertEquals(words, repository.lyrics(track).toList().last().document)
    }

    @Test fun missesAreShortLivedInMemoryOnlyAndRefreshCanReplaceEqualQualityLyrics() = runBlocking {
        val cache = LyricsCache(temporary.newFolder())
        val calls = AtomicInteger()
        var result: LyricsDocument? = null
        val source = provider { calls.incrementAndGet(); result }
        val repository = LyricsRepository(cache, listOf(source)) { 1_000 }
        assertNull(repository.lyrics(track).toList().last().document)
        repository.lyrics(track).toList()
        assertEquals(1, calls.get())
        assertNull(cache.read(track.key))
        result = words
        assertEquals(words, repository.lyrics(track, refresh = true).toList().last().document)
        val replacement = words.copy(source = "Updated")
        result = replacement
        assertEquals(replacement, repository.lyrics(track, refresh = true).toList().last().document)
    }

    private fun provider(find: suspend () -> LyricsDocument?) = LyricsProvider { find() }
}
