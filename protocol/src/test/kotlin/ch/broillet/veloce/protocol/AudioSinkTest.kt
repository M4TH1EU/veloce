package ch.broillet.veloce.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AudioSinkTest {
    private data class Packet(val id: Int, val bytes: ByteArray, val epoch: Int?)
    private val packets = ArrayBlockingQueue<Packet>(32)
    private val controls = ArrayBlockingQueue<Packet>(32)
    private var ready = false
    private var error: String? = null
    private val events = object : CarSessionEvents {
        override fun status(detail: String) = Unit
        override fun video(mode: VideoMode) = Unit
        override fun focus(projected: Boolean, inputEnabled: Boolean) = Unit
        override fun input(report: Proto) = Unit
        override fun failure(error: Exception) = Unit
        override fun audio(sink: AudioSink) { ready = true }
        override fun audioError(detail: String) { error = detail }
    }

    @Test fun selectsMediaCodecAndAdvertisedConfigurationWithoutConfusingGuidance() {
        val discovery = Proto(Proto.build {
            bytes(1, service(2, codec = 1, type = 1))
            bytes(1, service(3, codec = 4))
            bytes(1, service(5, codec = 1, rates = listOf(96_000, 44_100)))
        })
        val offer = checkNotNull(AudioOffer.find(discovery))
        assertEquals(5, offer.channel)
        val sink = sink(offer)
        sink.receive(8, Proto.build { number(1, 0) })
        assertEquals(1L, Proto(packets.take().bytes).number(1))
        sink.receive(0x8003, Proto.build { number(1, 1) })
        assertFalse(ready)
        sink.receive(0x8003, Proto.build { number(1, 2); number(3, 1) })
        assertTrue(ready)
        assertEquals(AudioMode(1, 44_100, 2, 1), sink.mode)
        sink.close()
    }

    @Test fun ignoresUnsupportedCodecsFormatsAndWrappedIntegers() {
        val invalid = Proto(Proto.build {
            bytes(1, service(2, codec = 3))
            bytes(1, service(3, codec = 1, bits = 24))
            bytes(1, Proto.build { number(1, 4); bytes(3, Proto.build {
                number(1, 1); number(2, 3)
                bytes(3, Proto.build { number(1, 0x100000000L + 48_000); number(2, 16); number(3, 2) })
            }) })
        })
        assertNull(AudioOffer.find(invalid))
        val aac = checkNotNull(AudioOffer.find(Proto(Proto.build { bytes(1, service(9, codec = 4)) })))
        assertEquals(4, aac.choose(listOf(0)).codec)
    }

    @Test(timeout = 5_000) fun waitsForFocusAndUsesTimestampsAndSessionScopedCredits() {
        val sink = configured()
        val worker = Executors.newSingleThreadExecutor()
        try {
            sink.playing(true)
            assertEquals(1L, Proto(controls.take().bytes).number(1))
            assertEquals(-1, sink.streamId)
            assertFalse(sink.write(byteArrayOf(1, 2), 10, -1))
            sink.focus(1)
            val first = sink.streamId
            assertEquals(first.toLong(), Proto(packets.take().bytes).number(1))
            assertTrue(sink.write(byteArrayOf(1, 2), 123456, first))
            val data = packets.take()
            assertEquals(first, data.epoch)
            assertEquals(123456L, ByteBuffer.wrap(data.bytes).long)
            assertArrayEquals(byteArrayOf(1, 2), data.bytes.copyOfRange(8, data.bytes.size))
            val waiting = worker.submit<Boolean> { sink.write(byteArrayOf(3, 4), 20, first) }
            sink.receive(0x8004, ack(first - 1, 1))
            sink.receive(0x8004, ack(first, 0))
            assertThrows(TimeoutException::class.java) { waiting.get(50, TimeUnit.MILLISECONDS) }
            sink.receive(0x8004, ack(first, 1))
            assertTrue(waiting.get(1, TimeUnit.SECONDS))
            packets.take()
            val interrupted = worker.submit<Boolean> { sink.write(byteArrayOf(5, 6), 30, first) }
            sink.focus(5)
            assertFalse(interrupted.get(1, TimeUnit.SECONDS))
            assertEquals(0x8002, packets.take().id)
            sink.focus(1)
            assertNotEquals(first, sink.streamId)
            assertEquals(0x8001, packets.take().id)
            assertFalse(sink.write(byteArrayOf(7, 8), 40, first))
            assertNull(error)
        } finally { sink.close(); worker.shutdownNow() }
    }

    @Test(timeout = 5_000) fun codecConfigConsumesCreditAndCloseCancelsBlockedProducer() {
        val sink = configured(codec = 2)
        val worker = Executors.newSingleThreadExecutor()
        try {
            sink.playing(true)
            controls.take()
            sink.focus(6)
            packets.take()
            assertTrue(sink.config(byteArrayOf(0x11, 0x90.toByte()), sink.streamId))
            assertEquals(1, packets.take().id)
            val stream = sink.streamId
            val waiting = worker.submit<Boolean> { sink.write(byteArrayOf(1), 10, stream) }
            assertThrows(TimeoutException::class.java) { waiting.get(50, TimeUnit.MILLISECONDS) }
            sink.close()
            assertFalse(waiting.get(1, TimeUnit.SECONDS))
            assertEquals(4L, Proto(controls.take().bytes).number(1))
        } finally { sink.close(); worker.shutdownNow() }
    }

    @Test fun permanentFocusLossDoesNotRepeatedlyTakeBackTheRadio() {
        val sink = configured()
        sink.playing(true)
        controls.take()
        sink.focus(1)
        packets.take()
        sink.focus(3)
        assertEquals(4L, Proto(controls.take().bytes).number(1))
        sink.playing(true)
        sink.focus(1)
        assertTrue(controls.isEmpty())
        assertEquals(-1, sink.streamId)
        sink.playing(false)
        sink.playing(true)
        assertEquals(1L, Proto(controls.take().bytes).number(1))
        sink.close()
    }

    @Test fun negotiationRejectionReportsAudioFailureWithoutProjectionFailure() {
        val offer = checkNotNull(AudioOffer.find(Proto(Proto.build { bytes(1, service(5, codec = 1)) })))
        sink(offer).receive(8, Proto.build { number(1, -1) })
        assertTrue(error!!.contains("refused"))
        assertFalse(ready)
    }

    private fun configured(codec: Int = 1): AudioSink {
        val sink = sink(checkNotNull(AudioOffer.find(Proto(Proto.build { bytes(1, service(5, codec)) }))))
        sink.receive(0x8003, Proto.build { number(1, 2); number(2, 1); number(3, 0) })
        return sink
    }

    private fun sink(offer: AudioOffer) = AudioSink(offer,
        { id, bytes, epoch -> packets.add(Packet(id, bytes, epoch)) },
        { id, bytes -> controls.add(Packet(id, bytes, null)) }, events)

    private fun ack(stream: Int, count: Int) = Proto.build { number(1, stream.toLong()); number(2, count.toLong()) }

    private fun service(channel: Int, codec: Int, type: Int = 3, rates: List<Int> = listOf(48_000), bits: Int = 16) = Proto.build {
        number(1, channel.toLong())
        bytes(3, Proto.build {
            number(1, codec.toLong()); number(2, type.toLong())
            rates.forEach { bytes(3, Proto.build { number(1, it.toLong()); number(2, bits.toLong()); number(3, 2) }) }
        })
    }
}
