package ch.broillet.veloce.protocol

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

class AudioSink internal constructor(
    private val offer: AudioOffer,
    private val send: (Int, ByteArray, Int?) -> Unit,
    private val control: (Int, ByteArray) -> Unit,
    private val events: CarSessionEvents,
) : Closeable {
    private val window = AckWindow(2_000, "audio")
    private var configured = false
    private var closed = false
    private var playing = false
    private var requested = false
    private var epoch = 100
    private var underflows = 0
    private var selected: AudioMode? = null
    val mode: AudioMode get() = checkNotNull(selected)
    @Volatile var streamId: Int = -1
        private set

    @Synchronized internal fun receive(id: Int, bytes: ByteArray) {
        if (closed) return
        try {
            val message = Proto(bytes)
            when (id) {
                8 -> {
                    if (message.number(1, -1) != 0L) throw IOException("The car refused the media audio channel")
                    send(0x8000, Proto.build { number(1, offer.codec.toLong()) }, null)
                }
                0x8003 -> {
                    if (message.number(1) == 1L) return
                    if (message.number(1) != 2L) throw IOException("The car's audio decoder is not ready")
                    if (configured) throw IOException("The car configured audio more than once")
                    selected = offer.choose(message.integers(3).ifEmpty { listOf(0) })
                    window.limit = message.number(2, 1).coerceIn(1, 8).toInt()
                    configured = true
                    events.diagnostic("Media audio channel ${offer.channel}: ${mode.description}, window=${window.limit}")
                    events.audio(this)
                }
                0x8004 -> window.acknowledge(message.number(1, -1).toInt(), message.number(2, 1).coerceIn(0, 8).toInt())
                0x800B -> {
                    underflows++
                    if (underflows == 1 || underflows % 50 == 0) events.diagnostic("Car audio underflows: $underflows")
                }
                255, 65535 -> throw IOException("The car rejected media audio setup ($id)")
            }
        } catch (error: IOException) {
            close()
            events.audioError(error.message ?: "Media audio negotiation failed")
        }
    }

    @Synchronized fun playing(value: Boolean) {
        if (closed || !configured || playing == value) return
        playing = value
        if (value) {
            requested = true
            control(18, Proto.build { number(1, 1) })
            events.diagnostic("Requesting car media audio focus")
        } else stop()
    }

    @Synchronized internal fun focus(state: Int) {
        if (closed || !requested) return
        events.diagnostic("Car audio focus: $state")
        when (state) {
            1, 2, 6 -> if (playing && streamId < 0) {
                val id = ++epoch
                window.reset(id)
                send(0x8001, Proto.build { number(1, id.toLong()); number(2, mode.index.toLong()) }, null)
                streamId = id
            }
            3 -> { playing = false; stop() }
            5 -> suspendStream()
            4 -> Unit // The head unit ducks its media sink.
        }
        events.audioFocus(state)
    }

    fun config(bytes: ByteArray, stream: Int): Boolean = packet(1, bytes, stream)

    fun write(bytes: ByteArray, timestampUs: Long, stream: Int): Boolean =
        packet(0, ByteBuffer.allocate(8 + bytes.size).putLong(timestampUs).put(bytes).array(), stream)

    private fun packet(id: Int, body: ByteArray, stream: Int): Boolean {
        if (!accepts(stream) || !window.acquire(stream)) return false
        return synchronized(this) {
            if (!accepts(stream)) false else {
                send(id, body, stream)
                true
            }
        }
    }

    internal fun accepts(stream: Int): Boolean = stream >= 0 && streamId == stream

    private fun suspendStream() {
        if (streamId >= 0) send(0x8002, ByteArray(0), null)
        streamId = -1
        window.reset(++epoch)
    }

    private fun stop() {
        suspendStream()
        if (requested) control(18, Proto.build { number(1, 4) })
        requested = false
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { stop() } finally { window.close() }
    }
}
