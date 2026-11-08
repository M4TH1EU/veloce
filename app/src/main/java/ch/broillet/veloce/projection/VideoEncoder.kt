package ch.broillet.veloce.projection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import ch.broillet.veloce.protocol.CarSession
import ch.broillet.veloce.protocol.VideoMode
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VideoEncoder(mode: VideoMode, wireless: Boolean, private val session: CarSession,
    private val failure: (Exception) -> Unit, private val redraw: () -> Unit = {}) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var drain: Thread? = null
    @Volatile private var running = true
    val surface: Surface
    val description: String get() = "${codec.name}, hardware=${codec.codecInfo.isHardwareAccelerated}"

    init {
        var createdSurface: Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mode.width, mode.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_BIT_RATE, (mode.width * mode.height * if (wireless) 3 else 5).coerceIn(1_500_000, 8_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, mode.fps.toFloat())
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            createdSurface = surface
            codec.start()
        } catch (error: Exception) {
            createdSurface?.release()
            codec.release()
            throw error
        }
    }

    @Synchronized fun start() {
        check(running && drain == null)
        drain = thread(name = "veloce-video") {
            try {
                val info = MediaCodec.BufferInfo()
                var configuration: ByteArray? = null
                val started = System.nanoTime()
                var lastFrame = started
                var lastRedraw = started
                var receivedFrame = false
                while (running) {
                    val index = codec.dequeueOutputBuffer(info, 50_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = codec.outputFormat
                        val buffers = (0..1).mapNotNull { format.getByteBuffer("csd-$it")?.duplicate() }
                        val bytes = buffers.fold(ByteArray(0)) { all, buffer -> all + ByteArray(buffer.remaining()).also(buffer::get) }
                        if (bytes.isNotEmpty()) { configuration = bytes; session.videoConfig(bytes) }
                    } else if (index >= 0) {
                        val bytes = try {
                            if (info.size > 0) {
                                val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset)
                                ByteArray(info.size).also(buffer::get)
                            } else null
                        } finally { codec.releaseOutputBuffer(index, false) }
                        if (bytes != null) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                if (!bytes.contentEquals(configuration)) { configuration = bytes; session.videoConfig(bytes) }
                            } else {
                                receivedFrame = true
                                lastFrame = System.nanoTime()
                                session.videoFrame(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                    }
                    val now = System.nanoTime()
                    if (now - lastFrame >= TimeUnit.SECONDS.toNanos(1) && now - lastRedraw >= TimeUnit.SECONDS.toNanos(1)) {
                        lastRedraw = now
                        redraw()
                    }
                    if (!receivedFrame && System.nanoTime() - started > TimeUnit.SECONDS.toNanos(10)) {
                        throw IOException("The car display encoder produced no video")
                    }
                }
            } catch (error: Exception) { if (running) failure(error) }
            finally { release() }
        }
    }

    @Synchronized fun keyFrame() {
        if (running) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    @Synchronized override fun close() {
        if (!running) return
        running = false
        val worker = drain
        if (worker == null) release() else worker.interrupt()
    }

    @Synchronized private fun release() {
        running = false
        runCatching { codec.stop() }
        try { codec.release() } finally { surface.release() }
    }
}
