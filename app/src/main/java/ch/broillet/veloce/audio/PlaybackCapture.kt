package ch.broillet.veloce.audio

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import ch.broillet.veloce.protocol.AudioSink
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class PlaybackCapture(
    private val context: Context,
    private val sink: AudioSink,
    private val status: (String) -> Unit,
    private val log: (String) -> Unit,
    private val ended: (PlaybackCapture, String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val local = LocalAudioState(context, log)
    private var projection: MediaProjection? = null
    @Volatile private var record: AudioRecord? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { finish("Audio sharing stopped") }
    }

    fun start(resultCode: Int, consent: Intent) {
        try {
            local.acquire()
            val token = checkNotNull(context.getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, consent)) { "Audio sharing was not authorized" }
            projection = token
            token.registerCallback(callback, Handler(Looper.getMainLooper()))
            thread(name = "veloce-playback-capture") { capture(token) }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun capture(projection: MediaProjection) {
        val mode = sink.mode
        var recorder: AudioRecord? = null
        var encoder: AacEncoder? = null
        var detail = "Audio sharing stopped"
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val mask = if (mode.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val chunk = mode.sampleRate / 50 * mode.frameBytes
            val minimum = AudioRecord.getMinBufferSize(mode.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IOException("Playback capture does not support ${mode.description}")
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Audio recording permission was revoked")
            }
            recorder = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(mode.sampleRate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, chunk * 12)).build()
            record = recorder
            if (closed.get()) return
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Playback capture did not start")
            status("Audio capture ready · ${mode.description}")
            val buffer = ByteArray(chunk)
            val began = System.nanoTime() / 1_000
            var frames = 0L
            var lastSignal = 0L
            var heard = false
            var warned = false
            var previousStream = -1
            var playing = false
            while (!closed.get()) {
                val size = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (size <= 0) throw IOException("Playback capture read failed ($size)")
                if (size % mode.frameBytes != 0) throw IOException("Playback capture returned a partial audio frame")
                val timestamp = began + frames * 1_000_000L / mode.sampleRate
                frames += size / mode.frameBytes
                val now = System.nanoTime()
                val signal = (0 until size).any { buffer[it] != 0.toByte() }
                if (signal) {
                    lastSignal = now
                    if (!heard) { heard = true; log("Playback capture received non-silent media") }
                }
                val active = lastSignal != 0L && now - lastSignal < 1_000_000_000L
                if (active != playing) {
                    playing = active
                    status(if (active) "Waiting for car audio focus" else "Audio capture ready · media paused")
                    sink.playing(active)
                }
                if (!heard && !warned && now / 1_000 - began > 10_000_000) {
                    warned = true
                    status("Capture is silent · start media playback; some apps prohibit capture")
                }
                val stream = sink.streamId
                if (stream != previousStream) {
                    encoder?.close()
                    encoder = if (stream >= 0 && mode.codec != 1) AacEncoder(mode) else null
                    previousStream = stream
                }
                if (stream < 0) continue
                val aac = encoder
                if (aac == null) sink.write(buffer.copyOf(size), timestamp, stream)
                else aac.encode(buffer, size, timestamp) { bytes, time, configBuffer ->
                    if (configBuffer) sink.config(bytes, stream) else sink.write(bytes, time, stream)
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                detail = "Audio stopped: ${error.message ?: error.javaClass.simpleName}"
                log(detail)
            }
        } finally {
            record = null
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { encoder?.close() }
            finish(detail)
        }
    }

    private fun finish(detail: String) {
        if (closed.get()) return
        close()
        ended(this, detail)
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { sink.playing(false) }
        runCatching { record?.stop() }
        projection?.unregisterCallback(callback)
        runCatching { projection?.stop() }
        local.close()
    }
}
