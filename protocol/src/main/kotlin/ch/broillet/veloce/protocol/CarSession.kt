package ch.broillet.veloce.protocol

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

interface CarLink : Closeable {
    val input: InputStream
    val output: OutputStream
}

interface CarSessionEvents {
    fun status(detail: String)
    fun video(mode: VideoMode)
    fun focus(projected: Boolean, inputEnabled: Boolean)
    fun input(report: Proto)
    fun failure(error: Exception)
}

class CarSession(private val link: CarLink, private val tls: TlsEngine, private val events: CarSessionEvents) : Closeable {
    private enum class Stage { VERSION, TLS, AUTH, DISCOVERY, CHANNELS }
    private data class Outgoing(val channel: Int, val id: Int, val body: ByteArray, val encrypted: Boolean,
        val control: Boolean, val sent: CountDownLatch? = null, val videoEpoch: Int? = null)
    private val stopped = AtomicBoolean(false)
    private val writes = ArrayBlockingQueue<Outgoing>(24)
    private val assembler = MessageAssembler()
    private val window = AckWindow()
    private var stage = Stage.VERSION
    private var offer: VideoOffer? = null
    private var mode: VideoMode? = null
    private var videoStarted = false
    private var inputEnabled = false
    private val videoLock = Any()
    private var needsKeyFrame = true
    @Volatile private var projected = false
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var videoEpoch = 0

    fun run() {
        val writer = thread(name = "veloce-car-writer") {
            try {
                while (!stopped.get()) {
                    val outgoing = writes.take()
                    write(outgoing)
                    outgoing.sent?.countDown()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!stopped.get()) events.failure(error)
                close()
            }
        }
        try {
            events.status("Waiting for the car's protocol version")
            val decoder = FrameDecoder()
            val bytes = ByteArray(16 * 1024)
            while (!stopped.get()) {
                val count = link.input.read(bytes)
                if (count < 0) {
                    decoder.endOfInput()
                    assembler.endOfInput()
                    throw IOException("The car closed the connection during ${stage.name.lowercase()}")
                }
                decoder.feed(bytes, 0, count) { frame ->
                    if (stopped.get()) return@feed
                    val plain = if (frame.encrypted) tls.decrypt(frame.payload) else frame.payload
                    val message = assembler.accept(frame, plain) ?: return@feed
                    if (message.size < 2) throw IOException("Missing protocol message ID")
                    val id = ((message[0].toInt() and 255) shl 8) or (message[1].toInt() and 255)
                    val ping = frame.channel == 0 && id in 11..12
                    if (stage >= Stage.DISCOVERY && !frame.encrypted && !ping) {
                        throw IOException("Unencrypted message after authentication")
                    }
                    if (stage < Stage.DISCOVERY && !ping && (frame.channel != 0 || id !in listOf(1, 3, 4, 15, 255, 65535))) {
                        throw IOException("Unexpected message before authentication")
                    }
                    receive(frame.channel, id, message.copyOfRange(2, message.size), frame.encrypted)
                }
            }
        } finally {
            close()
            writer.interrupt()
            try { writer.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun receive(channel: Int, id: Int, body: ByteArray, encrypted: Boolean) {
        if (channel == 0) {
            when (id) {
                1 -> {
                    if (stage != Stage.VERSION || body.size < 4 || body[0] == 0.toByte() && body[1] == 0.toByte()) {
                        throw IOException("Unexpected or unsupported protocol version")
                    }
                    send(0, 2, byteArrayOf(0, 1, 0, 7, 0, 0), encrypted = false)
                    stage = Stage.TLS
                    events.status("Securing the car connection")
                }
                3 -> {
                    if (stage != Stage.TLS) throw IOException("Unexpected TLS handshake")
                    tls.handshake(body).forEach { send(0, 3, it, encrypted = false) }
                    if (tls.ready) stage = Stage.AUTH
                }
                4 -> {
                    if (stage != Stage.AUTH || Proto(body).number(1, -1) != 0L) throw IOException("The car rejected authentication")
                    stage = Stage.DISCOVERY
                    events.status("Authenticated · discovering the car display")
                    send(0, 5, Proto.build { text(4, "Veloce"); text(5, "Veloce") })
                }
                6 -> {
                    if (stage != Stage.DISCOVERY) throw IOException("Unexpected service discovery")
                    val discovery = VideoOffer(Proto(body))
                    offer = discovery
                    stage = Stage.CHANNELS
                    events.status("Opening video and input channels")
                    open(discovery.channel)
                    discovery.inputChannel?.let(::open)
                }
                11 -> {
                    val ping = Proto(body)
                    send(0, 12, Proto.build {
                        number(1, ping.number(1))
                        ping.bytes(3)?.let { bytes(2, it) }
                    }, encrypted = encrypted)
                }
                13 -> send(0, 14, Proto.build { number(1, 1) })
                15 -> {
                    val sent = CountDownLatch(1)
                    send(0, 16, ByteArray(0), encrypted = tls.ready, sent = sent)
                    sent.await(750, TimeUnit.MILLISECONDS)
                    val reason = Proto(body).number(1).toInt()
                    if (reason in listOf(1, 2, 7, 8)) close()
                    else throw IOException("The car ended projection (reason $reason)")
                }
                18 -> send(0, 19, Proto.build { number(1, 3) })
                255, 65535 -> throw IOException("The car reported protocol error $id")
            }
            return
        }
        val discovery = offer ?: throw IOException("Channel message before service discovery")
        when {
            id == 7 -> send(channel, 8, Proto.build {
                number(1, if (channel == discovery.channel || channel == discovery.inputChannel) 0 else -1)
            }, control = true)
            channel == discovery.channel -> when (id) {
                8 -> {
                    requireSuccess(body, "video channel")
                    send(channel, 0x8000, Proto.build { number(1, 3) })
                }
                0x8003 -> {
                    if (mode != null) throw IOException("Video was configured more than once")
                    val config = Proto(body)
                    if (config.number(1) == 1L) return
                    if (config.number(1) != 2L) throw IOException("Video decoder is not ready")
                    mode = discovery.choose(config.integers(3).ifEmpty { discovery.modes.map { it.index } })
                    window.limit = config.number(2, 1).coerceIn(1, 8).toInt()
                    send(channel, 0x8007, Proto.build { number(2, 1); number(3, 1) })
                    events.status("Waiting for display focus")
                }
                0x8008 -> {
                    val focus = Proto(body).number(1).toInt()
                    val visible = focus == 1 || focus == 4
                    if (focus !in 1..4) throw IOException("Invalid video focus")
                    inputEnabled = focus == 1
                    synchronized(videoLock) {
                        if (visible != projected) {
                            projected = visible
                            videoEpoch++
                            window.reset(videoEpoch)
                            needsKeyFrame = true
                            if (visible) {
                                val selected = mode ?: throw IOException("Video focus before configuration")
                                send(channel, 0x8001, Proto.build { number(1, videoEpoch.toLong()); number(2, selected.index.toLong()) })
                            } else send(channel, 0x8002, ByteArray(0))
                        }
                    }
                    if (projected && !videoStarted) {
                        videoStarted = true
                        events.video(checkNotNull(mode))
                    }
                    events.focus(projected, inputEnabled)
                }
                0x8004 -> {
                    val ack = Proto(body)
                    window.acknowledge(ack.number(1, -1).toInt(), ack.number(2, 1).coerceIn(0, 8).toInt())
                }
            }
            channel == discovery.inputChannel -> when (id) {
                8 -> {
                    requireSuccess(body, "input channel")
                    send(channel, 0x8002, Proto.build {
                        discovery.keys.forEach { number(1, it.toLong()) }
                    })
                }
                0x8001 -> if (inputEnabled) events.input(Proto(body))
                0x8003 -> requireSuccess(body, "input binding")
            }
        }
    }

    fun videoConfig(bytes: ByteArray) = synchronized(videoLock) {
        if (!bytes.contentEquals(codecConfig)) needsKeyFrame = true
        codecConfig = bytes
    }

    fun videoFrame(bytes: ByteArray, timestampUs: Long, keyFrame: Boolean) {
        if (!projected || stopped.get()) return
        val epoch: Int
        val config: ByteArray?
        synchronized(videoLock) {
            epoch = videoEpoch
            if (needsKeyFrame && !keyFrame) return
            config = if (needsKeyFrame) codecConfig ?: return else null
            needsKeyFrame = false
        }
        if (config != null && !sendVideo(1, config, epoch)) return
        val data = ByteBuffer.allocate(8 + bytes.size).putLong(timestampUs).put(bytes).array()
        sendVideo(0, data, epoch)
    }

    private fun sendVideo(id: Int, bytes: ByteArray, epoch: Int): Boolean {
        if (!window.acquire(epoch)) return false
        synchronized(videoLock) {
            if (!projected || epoch != videoEpoch || stopped.get()) return false
            send(checkNotNull(offer).channel, id, bytes, videoEpoch = epoch)
        }
        return true
    }

    fun exitToCar() {
        offer?.let { send(it.channel, 0x8007, Proto.build { number(2, 2); number(3, 2) }) }
    }

    private fun open(channel: Int) = send(channel, 7, Proto.build { number(1, 0); number(2, channel.toLong()) }, control = true)

    private fun requireSuccess(body: ByteArray, operation: String) {
        if (Proto(body).number(1, -1) != 0L) throw IOException("The car refused the $operation")
    }

    private fun send(channel: Int, id: Int, body: ByteArray, encrypted: Boolean = true, control: Boolean = false,
        sent: CountDownLatch? = null, videoEpoch: Int? = null) {
        if (stopped.get()) return
        if (!writes.offer(Outgoing(channel, id, body, encrypted, control, sent, videoEpoch))) throw IOException("Car write queue stalled")
    }

    private fun write(message: Outgoing) {
        if (message.videoEpoch != null && (message.videoEpoch != videoEpoch || !projected)) return
        val plain = byteArrayOf((message.id ushr 8).toByte(), message.id.toByte()) + message.body
        var offset = 0
        while (offset < plain.size) {
            val length = minOf(12 * 1024, plain.size - offset)
            val last = offset + length == plain.size
            val fragment = if (offset == 0) {
                if (last) Fragment.COMPLETE else Fragment.FIRST
            } else if (last) Fragment.LAST else Fragment.CONTINUATION
            val part = plain.copyOfRange(offset, offset + length)
            val payload = if (message.encrypted) tls.encrypt(part) else part
            link.output.write(Frame(message.channel, fragment, message.encrypted, message.control, payload,
                if (fragment == Fragment.FIRST) plain.size.toLong() else null).encode())
            offset += length
        }
        link.output.flush()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        window.close()
        runCatching { link.close() }
    }
}

internal class AckWindow : Closeable {
    @Volatile var limit = 1
    private var inFlight = 0
    private var closed = false
    private var epoch = 0
    private val lock = Object()

    fun acquire(expectedEpoch: Int): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (inFlight >= limit && !closed && epoch == expectedEpoch) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("The car stopped acknowledging video")
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        if (closed || epoch != expectedEpoch) return false
        inFlight++
        true
    }

    fun acknowledge(sessionId: Int, count: Int): Unit = synchronized(lock) {
        if (sessionId != epoch || count <= 0) return
        inFlight = (inFlight - count).coerceAtLeast(0)
        lock.notifyAll()
    }

    fun reset(sessionId: Int) = synchronized(lock) { epoch = sessionId; inFlight = 0; lock.notifyAll() }

    override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
}
