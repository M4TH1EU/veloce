package ch.broillet.veloce.lyrics

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LyricsProvidersTest {
    private val track = LyricsTrack("Example", "Singer", "Album", 120_000)

    @Test fun betterLyricsSuppliesTheCompleteSignatureAndParsesWordTiming() = runBlocking {
        var request = ""
        val provider = BetterLyricsProvider { url ->
            request = url
            JSONObject().put("ttml", "<tt xmlns='http://www.w3.org/ns/ttml'><body><p begin='1s' end='3s'><span begin='1s' end='2s'>Hello </span><span begin='2s' end='3s'>world</span></p></body></tt>").toString()
        }
        val result = provider.find(track)!!
        assertEquals("api.betterlyrics.org", URI(request).host)
        listOf("s=Example", "a=Singer", "al=Album", "d=120").forEach { assertTrue(request.contains(it)) }
        assertEquals(3, result.quality)
    }

    @Test fun lrclibSearchRejectsWrongRecordingsInsteadOfTakingTheFirstHit() = runBlocking {
        fun row(title: String, artist: String, seconds: Int, text: String) = JSONObject().put("trackName", title)
            .put("artistName", artist).put("albumName", "Album").put("duration", seconds).put("syncedLyrics", "[00:01.00]$text")
        val response = JSONArray().put(row("Other song", "Singer", 120, "Wrong title"))
            .put(row("Example", "Other artist", 120, "Wrong artist")).put(row("Example", "Singer", 200, "Wrong recording"))
            .put(row("Example", "Singer", 120, "Correct recording")).toString()
        val provider = LrclibProvider { url -> if (URI(url).path == "/api/get") null else response }
        val result = provider.find(track)!!
        assertEquals("Correct recording", result.lines.single().text)
        assertEquals("LRCLIB", result.source)
    }

    @Test fun kugouUsesTheReturnedCandidateKeyAndDecodesItsTimedPayload() = runBlocking {
        val requests = mutableListOf<String>()
        val provider = KugouProvider { url ->
            requests.add(url)
            when (URI(url).path) {
                "/search" -> JSONObject().put("candidates", JSONArray().put(JSONObject().put("id", 123).put("accesskey", "returned-key")
                    .put("duration", 120_000).put("song", "Example").put("singer", "Singer"))).toString()
                "/download" -> JSONObject().put("content", Base64.getEncoder().encodeToString("[00:01.00]Composer: Test\n[00:05.00]Hello world".toByteArray())).toString()
                else -> null
            }
        }
        val result = provider.find(track)!!
        assertEquals("KuGou", result.source)
        assertEquals("Hello world", result.lines.single().text)
        assertEquals(5_000L, result.lines.single().startMs)
        assertTrue(requests.last().contains("accesskey=returned-key"))
        assertEquals(2, requests.size)
    }
}
