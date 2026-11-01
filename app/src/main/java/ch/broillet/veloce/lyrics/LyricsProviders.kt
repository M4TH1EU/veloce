package ch.broillet.veloce.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal fun interface LyricsHttp { suspend fun get(url: String): String? }
internal fun interface LyricsProvider { suspend fun find(track: LyricsTrack): LyricsDocument? }

internal class NetworkLyricsHttp : LyricsHttp {
    private class Host { val lock = Mutex(); var last = 0L; var blockedUntil = 0L }
    private val hosts = ConcurrentHashMap<String, Host>()
    override suspend fun get(url: String): String? {
        val host = hosts.getOrPut(URI(url).host) { Host() }
        return host.lock.withLock {
            if (System.currentTimeMillis() < host.blockedUntil) return@withLock null
            delay((350 - (System.currentTimeMillis() - host.last)).coerceAtLeast(0))
            host.last = System.currentTimeMillis()
            runInterruptible(Dispatchers.IO) {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 3_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("User-Agent", "Veloce/0.2 (https://git.broillet.ch/mathieub/veloce)")
                connection.setRequestProperty("Accept", "application/json")
                try {
                    val code = connection.responseCode
                    if (code == 429 || code == 503) {
                        val retry = connection.getHeaderField("Retry-After")
                        val wait = retry?.toLongOrNull()?.times(1_000) ?: runCatching {
                            ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()
                        }.getOrDefault(60_000)
                        host.blockedUntil = System.currentTimeMillis() + wait.coerceAtLeast(1_000) + 200
                    }
                    if (code != 200) null else connection.inputStream.use { input ->
                        val bytes = input.readNBytes(1_048_577)
                        check(bytes.size <= 1_048_576) { "Lyrics response too large" }
                        String(bytes, StandardCharsets.UTF_8)
                    }
                } finally { connection.disconnect() }
            }
        }
    }
}

private fun endpoint(base: String, parameters: Map<String, String>): String = base + "?" + parameters.entries.joinToString("&") {
    "${URLEncoder.encode(it.key, StandardCharsets.UTF_8)}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
}

internal class BetterLyricsProvider(private val http: LyricsHttp) : LyricsProvider {
    override suspend fun find(track: LyricsTrack): LyricsDocument? {
        val url = endpoint("https://api.betterlyrics.org/getLyrics", mapOf("s" to track.title, "a" to track.artist,
            "al" to track.album, "d" to (track.durationMs / 1_000).toString()))
        val response = http.get(url)?.let(::JSONObject) ?: return null
        if (response.has("score") && response.optDouble("score") < .65) return null
        val ttml = response.optString("ttml").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return LyricsParsers.ttml(ttml, "Better Lyrics", track.durationMs)
    }
}

internal class LrclibProvider(private val http: LyricsHttp) : LyricsProvider {
    override suspend fun find(track: LyricsTrack): LyricsDocument? {
        val exact = buildMap {
            put("track_name", track.title)
            put("artist_name", track.artist)
            if (track.album.isNotBlank()) put("album_name", track.album)
            (track.durationMs / 1_000).takeIf { it in 1..3_600 }?.let { put("duration", it.toString()) }
        }
        http.get(endpoint("https://lrclib.net/api/get", exact))?.let { parse(JSONObject(it), track.durationMs) }?.let { return it }
        val response = http.get(endpoint("https://lrclib.net/api/search", mapOf("track_name" to track.title, "artist_name" to track.artist)))
            ?.let(::JSONArray) ?: return null
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        val album = normalize(track.album)
        fun match(row: JSONObject): Int? {
            val candidate = normalize(row.optString("artistName"))
            if (normalize(row.optString("trackName")) != title || candidate.isEmpty() ||
                !(candidate.contains(artist) || artist.contains(candidate))) return null
            val duration = (row.optDouble("duration", 0.0) * 1_000).toLong()
            if (duration > 0 && track.durationMs > 0 && abs(duration - track.durationMs) > 8_000) return null
            return if (normalize(row.optString("albumName")) == album) 1 else 0
        }
        return (0 until response.length()).map { response.getJSONObject(it) }
            .mapNotNull { row -> match(row)?.let { score -> score to row } }.sortedByDescending { it.first }
            .mapNotNull { (_, row) -> parse(row, track.durationMs) }.maxByOrNull { it.quality }
    }

    private fun parse(row: JSONObject, durationMs: Long): LyricsDocument? {
        if (row.optBoolean("instrumental")) return LyricsDocument(emptyList(), "LRCLIB", instrumental = true)
        val yaml = row.optString("lyricsfile").takeIf { it.isNotBlank() && it != "null" }
        if (row.optBoolean("hasWordSync") && yaml != null) runCatching { LyricsParsers.lyricsfile(yaml, "LRCLIB", durationMs) }
            .getOrNull()?.let { return it }
        row.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?.let { LyricsParsers.lrc(it, "LRCLIB", durationMs) }?.let { return it }
        return row.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { LyricsParsers.plain(it, "LRCLIB") }
    }
}

internal class KugouProvider(private val http: LyricsHttp) : LyricsProvider {
    private val credits = Regex("(?i)^(作词|作曲|编曲|词曲|制作人|lyricist|composer|arranger|lyrics by|written by|producer)\\s*[:：]")

    override suspend fun find(track: LyricsTrack): LyricsDocument? {
        val keyword = "${track.title} ${track.artist}"
        candidate(mapOf("keyword" to keyword, "duration" to track.durationMs.toString()), track)?.let { return it }
        val response = http.get(endpoint("https://mobileservice.kugou.com/api/v3/search/song",
            mapOf("keyword" to keyword, "pagesize" to "5", "version" to "9108", "plat" to "0")))?.let(::JSONObject)
            ?.optJSONObject("data")?.optJSONArray("info") ?: return null
        val title = normalize(track.title)
        for (index in 0 until minOf(response.length(), 5)) {
            val song = response.getJSONObject(index)
            val duration = song.optLong("duration") * 1_000
            if (duration > 0 && track.durationMs > 0 && abs(duration - track.durationMs) > 8_000) continue
            if (normalize(song.optString("songname")) != title) continue
            val hash = song.optString("hash").takeIf { it.isNotBlank() } ?: continue
            candidate(mapOf("hash" to hash), track)?.let { return it }
        }
        return null
    }

    private suspend fun candidate(query: Map<String, String>, track: LyricsTrack): LyricsDocument? {
        val response = http.get(endpoint("https://lyrics.kugou.com/search", query + mapOf("ver" to "1", "man" to "yes", "client" to "pc")))
            ?.let(::JSONObject)?.optJSONArray("candidates") ?: return null
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        val candidates = (0 until minOf(response.length(), 8)).asSequence().map { response.getJSONObject(it) }
            .filter { row -> row.optLong("duration") == 0L || track.durationMs <= 0 || abs(row.optLong("duration") - track.durationMs) <= 8_000 }
            .filter { row -> row.optString("song").let { it.isBlank() || normalize(it) == title } }
            .filter { row -> normalize(row.optString("singer")).let { it.isEmpty() || it.contains(artist) || artist.contains(it) } }
        for (row in candidates.take(2)) {
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: continue
            val access = row.optString("accesskey").takeIf { it.isNotBlank() } ?: continue
            val download = http.get(endpoint("https://lyrics.kugou.com/download", mapOf("id" to id, "accesskey" to access,
                "fmt" to "lrc", "charset" to "utf8", "client" to "pc", "ver" to "1")))?.let(::JSONObject) ?: continue
            val encoded = download.optString("content").takeIf { it.isNotBlank() } ?: continue
            val text = String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
            LyricsParsers.lrc(text, "KuGou", track.durationMs)?.let { lyrics ->
                finishLyrics(lyrics.lines.filterNot { (it.startMs ?: 0) < 15_000 && credits.containsMatchIn(it.text) }, "KuGou", track.durationMs)
                    ?.let { return it }
            }
        }
        return null
    }
}
