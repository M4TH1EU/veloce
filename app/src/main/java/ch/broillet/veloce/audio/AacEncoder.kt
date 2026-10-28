package ch.broillet.veloce.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import ch.broillet.veloce.protocol.AudioMode
import java.io.Closeable
import java.io.IOException

internal class AacEncoder(private val mode: AudioMode) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var codecConfig: ByteArray? = null

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, mode.sampleRate, mode.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, minOf(mode.channels * 96_000, mode.sampleRate * mode.channels * 4))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (error: Exception) {
            codec.release()
            throw error
        }
    }

    fun encode(pcm: ByteArray, size: Int, timestampUs: Long, output: (ByteArray, Long, Boolean) -> Unit) {
        var offset = 0
        while (offset < size) {
            drain(output)
            val input = codec.dequeueInputBuffer(20_000)
            if (input < 0) throw IOException("AAC encoder input stalled")
            val buffer = checkNotNull(codec.getInputBuffer(input))
            buffer.clear()
            val count = minOf(size - offset, buffer.remaining()) / mode.frameBytes * mode.frameBytes
            if (count == 0) throw IOException("AAC encoder input is too small")
            buffer.put(pcm, offset, count)
            codec.queueInputBuffer(input, 0, count, timestampUs + offset / mode.frameBytes * 1_000_000L / mode.sampleRate, 0)
            offset += count
        }
        drain(output)
    }

    private fun drain(output: (ByteArray, Long, Boolean) -> Unit) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.getByteBuffer("csd-0")?.duplicate()?.let { buffer ->
                    sendConfig(ByteArray(buffer.remaining()).also { buffer.get(it) }, output)
                }
                continue
            }
            if (index < 0) return
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val timestamp = info.presentationTimeUs
            val bytes = try {
                val buffer = checkNotNull(codec.getOutputBuffer(index))
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                ByteArray(info.size).also { buffer.get(it) }
            } finally { codec.releaseOutputBuffer(index, false) }
            if (bytes.isEmpty()) continue
            if (config) {
                sendConfig(bytes, output)
            } else output(if (mode.codec == 4) adts(bytes) else bytes, timestamp, false)
        }
    }

    private fun sendConfig(bytes: ByteArray, output: (ByteArray, Long, Boolean) -> Unit) {
        if (mode.codec != 2 || bytes.isEmpty() || codecConfig?.contentEquals(bytes) == true) return
        codecConfig = bytes
        output(bytes, 0, true)
    }

    private fun adts(frame: ByteArray): ByteArray {
        val rateIndex = RATES.indexOf(mode.sampleRate)
        val length = frame.size + 7
        require(rateIndex >= 0 && length <= 8191)
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (rateIndex shl 2) or (mode.channels shr 2)).toByte(),
            (((mode.channels and 3) shl 6) or (length shr 11)).toByte(), (length shr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
        return header + frame
    }

    override fun close() {
        try { codec.stop() } finally { codec.release() }
    }

    companion object {
        private val RATES = listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
    }
}
