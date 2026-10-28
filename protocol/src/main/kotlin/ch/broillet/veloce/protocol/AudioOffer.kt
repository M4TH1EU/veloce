package ch.broillet.veloce.protocol

import java.io.IOException

data class AudioMode(val index: Int, val sampleRate: Int, val channels: Int, val codec: Int) {
    val frameBytes get() = channels * 2
    val description get() = "${sampleRate} Hz · $channels channels · ${when (codec) { 1 -> "PCM"; 2 -> "AAC-LC"; else -> "AAC-ADTS" }}"
}

class AudioOffer private constructor(val channel: Int, val codec: Int, private val modes: List<AudioMode>) {
    fun choose(indices: List<Int>): AudioMode = modes.firstOrNull { it.index in indices }
        ?: throw IOException("The car offered no supported media audio configuration")

    companion object {
        fun find(discovery: Proto): AudioOffer? = discovery.messages(1).mapNotNull { service ->
            val channel = service.number(1)
            val sink = service.bytes(3)?.let(::Proto) ?: return@mapNotNull null
            val codec = sink.number(1, 1)
            if (channel !in 1..255 || sink.number(2) != 3L || codec !in listOf(1L, 2L, 4L)) return@mapNotNull null
            val modes = sink.messages(3).mapIndexedNotNull { index, config ->
                val rate = config.number(1)
                val channels = config.number(3)
                if (config.number(2) != 16L || channels !in 1..2 || rate !in SAMPLE_RATES) null
                else AudioMode(index, rate.toInt(), channels.toInt(), codec.toInt())
            }
            if (modes.isEmpty()) null else AudioOffer(channel.toInt(), codec.toInt(), modes)
        }.minByOrNull { if (it.codec == 1) 0 else 1 }

        private val SAMPLE_RATES = setOf(8_000L, 11_025L, 12_000L, 16_000L, 22_050L, 24_000L, 32_000L, 44_100L, 48_000L)
    }
}
