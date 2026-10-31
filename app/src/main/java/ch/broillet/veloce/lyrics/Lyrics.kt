package ch.broillet.veloce.lyrics

import java.text.Normalizer
import java.util.Locale

data class LyricsTrack(val title: String, val artist: String, val album: String = "", val durationMs: Long = 0) {
    val key: String get() = listOf(title, artist, album, (durationMs / 1_000).toString()).joinToString("\u0000") { normalize(it) }
}

data class LyricWord(val startMs: Long, val endMs: Long, val from: Int, val until: Int)
data class LyricLine(val text: String, val startMs: Long? = null, val endMs: Long? = null, val words: List<LyricWord> = emptyList())
data class LyricsDocument(val lines: List<LyricLine>, val source: String, val instrumental: Boolean = false) {
    val synced: Boolean = lines.isNotEmpty() && lines.all { it.startMs != null }
    val quality: Int = when {
        instrumental -> 1
        synced && lines.any { it.words.size > 1 } -> 3
        synced -> 2
        lines.any { it.text.isNotBlank() } -> 1
        else -> 0
    }

    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        var left = 0
        var right = lines.lastIndex
        var found = -1
        while (left <= right) {
            val middle = (left + right) ushr 1
            if (checkNotNull(lines[middle].startMs) <= positionMs) { found = middle; left = middle + 1 } else right = middle - 1
        }
        return found
    }
}

internal fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

internal fun finishLyrics(lines: List<LyricLine>, source: String, durationMs: Long = 0): LyricsDocument? {
    if (lines.none { it.text.isNotBlank() }) return null
    val timed = lines.all { it.startMs != null }
    val ordered = if (timed) lines.sortedBy { it.startMs } else lines
    val complete = ordered.mapIndexed { index, line ->
        val start = line.startMs
        val end = line.endMs?.takeIf { start != null && it >= start }
            ?: ordered.getOrNull(index + 1)?.startMs
            ?: durationMs.takeIf { start != null && it > start }
        line.copy(endMs = end)
    }
    return LyricsDocument(complete, source)
}
