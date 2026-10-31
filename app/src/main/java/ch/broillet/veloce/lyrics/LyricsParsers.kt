package ch.broillet.veloce.lyrics

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object LyricsParsers {
    private val lineStamp = Regex("\\[(\\d+):(\\d{1,2}(?:\\.\\d+)?)\\]")
    private val wordStamp = Regex("<(\\d+):(\\d{1,2}(?:\\.\\d+)?)>")
    private val whitespace = Regex("[\\t\\r\\n ]+")
    private val ignoredRoles = setOf("x-bg", "x-translation", "x-roman")

    fun time(value: String?): Long? {
        if (value == null) return null
        val raw = value.trim()
        val seconds = if (':' in raw) raw.split(':').fold(0.0) { total, part -> total * 60 + (part.toDoubleOrNull() ?: return null) }
            else {
                val unit = listOf("ms" to .001, "s" to 1.0, "m" to 60.0, "h" to 3_600.0).firstOrNull { raw.endsWith(it.first) }
                (raw.removeSuffix(unit?.first.orEmpty()).toDoubleOrNull() ?: return null) * (unit?.second ?: 1.0)
            }
        return (seconds * 1_000).takeIf { it.isFinite() && it >= 0 }?.toLong()
    }

    fun lrc(raw: String, source: String, durationMs: Long = 0): LyricsDocument? {
        val offset = Regex("(?i)\\[offset:([+-]?\\d+)]").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val lines = raw.lineSequence().flatMap { row ->
            val stamps = lineStamp.findAll(row).toList()
            val content = row.substring(stamps.lastOrNull()?.range?.last?.plus(1) ?: row.length)
            val markers = wordStamp.findAll(content).toList()
            val text = content.replace(wordStamp, "").trim()
            val words = mutableListOf<LyricWord>()
            var cursor = 0
            markers.forEachIndexed { index, marker ->
                val chunk = content.substring(marker.range.last + 1, markers.getOrNull(index + 1)?.range?.first ?: content.length)
                val from = text.indexOf(chunk.trim(), cursor)
                val start = time("${marker.groupValues[1]}:${marker.groupValues[2]}")
                val end = markers.getOrNull(index + 1)?.let { time("${it.groupValues[1]}:${it.groupValues[2]}") }
                if (chunk.isNotBlank() && from >= 0 && start != null) {
                    cursor = from + chunk.trim().length
                    words.add(LyricWord((start + offset).coerceAtLeast(0), ((end ?: durationMs.takeIf { it > start } ?: start) + offset).coerceAtLeast(0), from, cursor))
                }
            }
            stamps.asSequence().mapNotNull { stamp -> time("${stamp.groupValues[1]}:${stamp.groupValues[2]}")?.let {
                LyricLine(text, (it + offset).coerceAtLeast(0), words = words.toList())
            } }
        }.take(4_000).toList()
        return finishLyrics(lines, source, durationMs)
    }

    fun plain(raw: String, source: String): LyricsDocument? = finishLyrics(raw.lines().take(4_000).map { LyricLine(it.trim()) }, source)

    fun ttml(raw: String, source: String, durationMs: Long = 0): LyricsDocument? {
        require(!raw.contains("<!DOCTYPE", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
        val document = builder.parse(InputSource(StringReader(raw)))
        val paragraphs = document.getElementsByTagNameNS("*", "p")
        val lines = (0 until minOf(paragraphs.length, 4_000)).mapNotNull { index ->
            val paragraph = paragraphs.item(index) as Element
            val text = StringBuilder()
            val words = mutableListOf<LyricWord>()
            fun visit(node: Node, begin: Long?, end: Long?, word: Boolean) {
                if (node is Element) {
                    if (attribute(node, "role") in ignoredRoles) return
                    if (node.localName == "br") { text.append('\n'); return }
                    val start = time(attribute(node, "begin")) ?: begin
                    val stop = time(attribute(node, "end")) ?: time(attribute(node, "dur"))?.let { start?.plus(it) } ?: end
                    val timed = word || (node.localName == "span" && attribute(node, "begin") != null)
                    for (child in 0 until node.childNodes.length) visit(node.childNodes.item(child), start, stop, timed)
                } else if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                    val chunk = node.nodeValue.replace(whitespace, " ")
                    val from = text.length
                    text.append(chunk)
                    if (word && chunk.isNotBlank() && begin != null && end != null && end >= begin) words.add(LyricWord(begin, end, from, text.length))
                }
            }
            val start = time(attribute(paragraph, "begin"))
            val end = time(attribute(paragraph, "end")) ?: time(attribute(paragraph, "dur"))?.let { start?.plus(it) }
            visit(paragraph, start, end, false)
            val content = text.toString()
            val leading = content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val trimmed = content.trim()
            if (trimmed.isBlank()) null else LyricLine(trimmed, start ?: words.firstOrNull()?.startMs, end,
                words.mapNotNull { piece ->
                    val from = (piece.from - leading).coerceIn(0, trimmed.length)
                    val until = (piece.until - leading).coerceIn(from, trimmed.length)
                    piece.copy(from = from, until = until).takeIf { from < until }
                })
        }
        return finishLyrics(lines, source, durationMs)
    }

    private fun attribute(element: Element, name: String): String? = (0 until element.attributes.length)
        .map { element.attributes.item(it) }.firstOrNull { (it.localName ?: it.nodeName) == name }?.nodeValue

    fun lyricsfile(raw: String, source: String, durationMs: Long = 0): LyricsDocument? {
        val settings = LoadSettings.builder().setCodePointLimit(1_000_000).setMaxAliasesForCollections(0).setAllowDuplicateKeys(false).build()
        val root = Load(settings).loadFromString(raw) as? Map<*, *> ?: return null
        val rows = root["lines"] as? List<*> ?: return null
        val lines = rows.take(4_000).mapNotNull { value ->
            val row = value as? Map<*, *> ?: return@mapNotNull null
            val text = row["text"] as? String ?: return@mapNotNull null
            var cursor = 0
            val words = (row["words"] as? List<*>).orEmpty().mapNotNull word@{ value ->
                val word = value as? Map<*, *> ?: return@word null
                val part = word["text"] as? String ?: return@word null
                val start = (word["start_ms"] as? Number)?.toLong() ?: return@word null
                val end = (word["end_ms"] as? Number)?.toLong() ?: return@word null
                val from = text.indexOf(part, cursor)
                if (from < 0 || start < 0 || end < start || part.isEmpty()) return@word null
                cursor = from + part.length
                LyricWord(start, end, from, cursor)
            }
            LyricLine(text, (row["start_ms"] as? Number)?.toLong()?.takeIf { it >= 0 },
                (row["end_ms"] as? Number)?.toLong()?.takeIf { it >= 0 }, words)
        }
        return finishLyrics(lines, source, durationMs)
    }
}
