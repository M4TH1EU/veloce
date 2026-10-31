package ch.broillet.veloce.lyrics

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class CachedLyrics(val document: LyricsDocument, val checkedAt: Long)

internal class LyricsCache(private val directory: File) {
    private val memory = object : LinkedHashMap<String, CachedLyrics>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?) = size > 64
    }

    @Synchronized fun read(key: String): CachedLyrics? {
        memory[key]?.let { return it }
        return runCatching {
            val file = file(key)
            val bytes = AtomicFile(file).openRead().use { it.readNBytes(1_048_577) }
            check(bytes.size <= 1_048_576)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            check(json.getInt("version") == 1)
            val rows = json.getJSONArray("lines")
            val lines = (0 until minOf(rows.length(), 4_000)).map { index ->
                val row = rows.getJSONObject(index)
                val words = row.optJSONArray("words")
                val text = row.getString("text")
                LyricLine(text, row.optLong("start", -1).takeIf { it >= 0 }, row.optLong("end", -1).takeIf { it >= 0 },
                    (0 until minOf(words?.length() ?: 0, 2_000)).map { word ->
                        val item = words!!.getJSONArray(word)
                        LyricWord(item.getLong(0), item.getLong(1), item.getInt(2), item.getInt(3))
                    }.filter { it.startMs >= 0 && it.endMs >= it.startMs && it.from >= 0 && it.until > it.from && it.until <= text.length })
            }
            CachedLyrics(LyricsDocument(lines, json.getString("source"), json.optBoolean("instrumental")), json.getLong("checked"))
                .also { memory[key] = it }
        }.getOrNull()
    }

    @Synchronized fun write(key: String, value: CachedLyrics, replace: Boolean = false): CachedLyrics {
        val previous = read(key)
        val stored = if (!replace && previous != null && previous.document.quality > value.document.quality)
            CachedLyrics(previous.document, maxOf(previous.checkedAt, value.checkedAt)) else value
        memory[key] = stored
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val rows = JSONArray()
            stored.document.lines.forEach { line ->
                rows.put(JSONObject().put("text", line.text).put("start", line.startMs ?: -1).put("end", line.endMs ?: -1)
                    .put("words", JSONArray(line.words.map { JSONArray(listOf(it.startMs, it.endMs, it.from, it.until)) })))
            }
            val bytes = JSONObject().put("version", 1).put("checked", stored.checkedAt).put("source", stored.document.source)
                .put("instrumental", stored.document.instrumental).put("lines", rows).toString().toByteArray()
            if (bytes.size > 1_048_576) return@runCatching
            val target = AtomicFile(file(key))
            val output = target.startWrite()
            try { output.write(bytes); target.finishWrite(output) } catch (error: Exception) { target.failWrite(output); throw error }
            val files = directory.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
            var total = files.sumOf { it.length() }
            var count = files.size
            for (old in files) {
                if (count <= 128 && total <= 8 * 1024 * 1024) break
                val size = old.length()
                if (old.delete()) { count--; total -= size }
            }
        }
        return stored
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }
}
