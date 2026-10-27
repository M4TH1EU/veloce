package ch.broillet.veloce.protocol

import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class CarSessionTest {
    @Test(timeout = 30_000) fun `simulated head unit authenticates negotiates display receives video and sends input`() = exercise()

    @Test(timeout = 30_000) fun `authenticated session rejects plaintext shutdown`() = exercise(plaintextShutdown = true)

    @Test(timeout = 30_000) fun `rejected key binding fails instead of silently disabling input`() = exercise(rejectBinding = true)

    private fun exercise(plaintextShutdown: Boolean = false, rejectBinding: Boolean = false) {
        val phoneInput = PipedInputStream(256 * 1024)
        val carOutput = PipedOutputStream(phoneInput)
        val carInput = PipedInputStream(256 * 1024)
        val phoneOutput = PipedOutputStream(carInput)
        val link = object : CarLink {
            override val input = phoneInput
            override val output = phoneOutput
            override fun close() { phoneInput.close(); phoneOutput.close() }
        }
        val video = ArrayBlockingQueue<VideoMode>(1)
        val focusChanges = ArrayBlockingQueue<Boolean>(8)
        val touches = ArrayBlockingQueue<Proto>(1)
        val failures = ArrayBlockingQueue<Exception>(2)
        val session = CarSession(link, TlsEngine(TestIdentity.store, TestIdentity.password), object : CarSessionEvents {
            override fun status(detail: String) = Unit
            override fun video(mode: VideoMode) { video.add(mode) }
            override fun focus(projected: Boolean, inputEnabled: Boolean) { focusChanges.add(projected) }
            override fun input(report: Proto) { touches.add(report) }
            override fun failure(error: Exception) { failures.offer(error) }
        })
        val executor = Executors.newSingleThreadExecutor()
        val producer = Executors.newSingleThreadExecutor()
        val run = executor.submit { session.run() }
        val client = TestIdentity.client()
        val input = DataInputStream(carInput)
        var lastReadEncrypted = false

        fun send(channel: Int, id: Int, bytes: ByteArray, encrypted: Boolean = true, control: Boolean = false) {
            val message = byteArrayOf((id ushr 8).toByte(), id.toByte()) + bytes
            carOutput.write(Frame(channel, Fragment.COMPLETE, encrypted, control,
                if (encrypted) TestIdentity.wrap(client, message) else message).encode())
            carOutput.flush()
        }
        fun read(): Triple<Int, Int, ByteArray> {
            val assembler = MessageAssembler()
            while (true) {
                val channel = input.readUnsignedByte()
                val flags = input.readUnsignedByte()
                lastReadEncrypted = flags and 8 != 0
                val size = input.readUnsignedShort()
                assertTrue(size + 8 <= 16 * 1024)
                val fragment = Fragment.entries[flags and 3]
                val total = if (fragment == Fragment.FIRST) input.readInt().toLong() else null
                val bytes = ByteArray(size).also(input::readFully)
                val plain = if (flags and 8 != 0) TestIdentity.unwrap(client, bytes) else bytes
                val payload = assembler.accept(Frame(channel, fragment, flags and 8 != 0, flags and 4 != 0, bytes, total), plain)
                    ?: continue
                val id = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
                assertEquals(id == 7 || id == 8, flags and 4 != 0)
                return Triple(channel, id, payload.copyOfRange(2, payload.size))
            }
        }

        try {
            send(0, 1, byteArrayOf(0, 6, 0, 1), encrypted = false)
            val version = read()
            assertEquals(2, version.second)
            assertArrayEquals(byteArrayOf(0, 1, 0, 7, 0, 0), version.third)
            TestIdentity.handshake(client, { send(0, 3, it, encrypted = false) }, {
                val handshake = read(); assertEquals(3, handshake.second); handshake.third
            })
            send(0, 4, Proto.build { number(1, 0) }, encrypted = false)
            assertEquals(5, read().second)
            if (plaintextShutdown) {
                send(0, 15, Proto.build { number(1, 1) }, encrypted = false)
                val error = assertThrows(ExecutionException::class.java) { run.get(2, TimeUnit.SECONDS) }
                assertEquals("Unencrypted message after authentication", error.cause?.message)
                return
            }

            val discovery = Proto.build {
                bytes(1, Proto.build {
                    number(1, 7)
                    bytes(3, Proto.build {
                        number(1, 3)
                        bytes(4, Proto.build { number(1, 2); number(2, 2); number(5, 240) })
                    })
                })
                bytes(1, Proto.build { number(1, 11); bytes(4, Proto.build { number(1, 4); number(1, 23) }) })
            }
            send(0, 6, discovery)
            val opened = listOf(read(), read())
            assertEquals(setOf(7, 11), opened.map { it.first }.toSet())
            opened.forEach { assertEquals(7, it.second); send(it.first, 8, Proto.build { number(1, 0) }, control = true) }
            val setup = listOf(read(), read())
            assertEquals(setOf(0x8000, 0x8002), setup.map { it.second }.toSet())
            if (rejectBinding) {
                send(11, 0x8003, Proto.build { number(1, -1) })
                val error = assertThrows(ExecutionException::class.java) { run.get(2, TimeUnit.SECONDS) }
                assertEquals("The car refused the input binding", error.cause?.message)
                return
            }
            send(11, 0x8003, Proto.build { number(1, 0) })
            send(7, 0x8003, Proto.build { number(1, 2); number(2, 1); number(3, 0) })
            assertEquals(0x8007, read().second)
            send(7, 0x8008, Proto.build { number(1, 1) })
            val start = read()
            assertEquals(0x8001, start.second)
            assertEquals(0L, Proto(start.third).number(2))
            assertEquals(1280, video.poll(2, TimeUnit.SECONDS)?.width)
            assertEquals(true, focusChanges.poll(2, TimeUnit.SECONDS))

            session.videoConfig(byteArrayOf(0, 0, 0, 1, 0x67))
            val timestamp = 0x12345678abcdefL
            val h264 = ByteArray(40_000) { (it % 251).toByte() }
            val producing = producer.submit { session.videoFrame(h264, timestamp, keyFrame = true) }
            assertEquals(1, read().second)
            assertThrows(TimeoutException::class.java) { producing.get(50, TimeUnit.MILLISECONDS) }
            send(7, 0x8004, Proto.build { number(1, 1); number(2, 1) })
            val frame = read()
            assertEquals(0, frame.second)
            assertEquals(timestamp, ByteBuffer.wrap(frame.third).long)
            assertArrayEquals(h264, frame.third.copyOfRange(8, frame.third.size))
            producing.get(1, TimeUnit.SECONDS)
            send(7, 0x8004, Proto.build { number(1, 1); number(2, 1) })

            val pingTimestamp = 0x1234abcdef98L
            send(0, 11, Proto.build { number(1, pingTimestamp); bytes(3, byteArrayOf(3, 4, 5)) })
            val ping = read()
            assertEquals(12, ping.second)
            assertEquals(pingTimestamp, Proto(ping.third).number(1))
            assertArrayEquals(byteArrayOf(3, 4, 5), Proto(ping.third).bytes(2))
            assertTrue(lastReadEncrypted)
            send(0, 11, Proto.build { number(1, pingTimestamp + 1) }, encrypted = false)
            val plainPing = read()
            assertEquals(12, plainPing.second)
            assertEquals(pingTimestamp + 1, Proto(plainPing.third).number(1))
            assertFalse(lastReadEncrypted)

            send(11, 0x8001, Proto.build {
                number(1, 10)
                bytes(3, Proto.build {
                    bytes(1, Proto.build { number(1, 123); number(2, 234); number(3, 0) })
                    number(2, 0); number(3, 0)
                })
            })
            val report = touches.poll(2, TimeUnit.SECONDS)
            assertNotNull(report)
            assertEquals(123L, Proto(checkNotNull(report).bytes(3)!!).messages(1).single().number(1))
            send(7, 0x8008, Proto.build { number(1, 4) })
            assertEquals(true, focusChanges.poll(2, TimeUnit.SECONDS))
            send(11, 0x8001, Proto.build { number(1, 11) })
            assertNull(touches.poll(50, TimeUnit.MILLISECONDS))
            session.exitToCar()
            val exit = read()
            assertEquals(0x8007, exit.second)
            assertEquals(2L, Proto(exit.third).number(2))

            send(7, 0x8008, Proto.build { number(1, 2) })
            assertEquals(0x8002, read().second)
            assertEquals(false, focusChanges.poll(2, TimeUnit.SECONDS))
            send(7, 0x8008, Proto.build { number(1, 1) })
            val restart = read()
            assertEquals(0x8001, restart.second)
            val stream = Proto(restart.third).number(1)
            assertNotEquals(1L, stream)
            assertEquals(true, focusChanges.poll(2, TimeUnit.SECONDS))
            session.videoFrame(byteArrayOf(0x41), timestamp + 1, keyFrame = false)
            val resumed = producer.submit { session.videoFrame(h264, timestamp + 2, keyFrame = true) }
            assertEquals(1, read().second)
            send(7, 0x8004, Proto.build { number(1, 1); number(2, 8) })
            assertThrows(TimeoutException::class.java) { resumed.get(50, TimeUnit.MILLISECONDS) }
            send(7, 0x8004, Proto.build { number(1, stream); number(2, 1) })
            assertEquals(timestamp + 2, ByteBuffer.wrap(read().third).long)
            resumed.get(1, TimeUnit.SECONDS)

            val cancelled = producer.submit { session.videoFrame(h264, timestamp + 3, keyFrame = false) }
            assertThrows(TimeoutException::class.java) { cancelled.get(50, TimeUnit.MILLISECONDS) }
            send(0, 15, Proto.build { number(1, 1) })
            assertEquals(16, read().second)
            cancelled.get(1, TimeUnit.SECONDS)
            assertTrue(failures.isEmpty())
        } finally {
            session.close()
            producer.shutdownNow()
            carOutput.close()
            carInput.close()
            executor.shutdown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            if (!plaintextShutdown && !rejectBinding) run.get(1, TimeUnit.SECONDS)
        }
    }
}
