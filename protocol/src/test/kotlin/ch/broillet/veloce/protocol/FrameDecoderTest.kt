package ch.broillet.veloce.protocol

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FrameDecoderTest {
    @Test fun `wire layout is independent of transport read boundaries`() {
        val wire = hex("00 03 00 04 00 01 00 01 07 0b 00 03 aa bb cc ff 0d 00 02 00 01 00 00 9a bc 00 03 00 00")
        for (split in 0..wire.size) {
            val decoder = FrameDecoder()
            val frames = mutableListOf<Frame>()
            decoder.feed(wire, 0, split, frames::add)
            decoder.feed(wire, split, wire.size - split, frames::add)
            decoder.endOfInput()
            assertEquals(4, frames.size)
            assertArrayEquals(hex("00 01 00 01"), frames[0].payload)
            assertFalse(frames[0].encrypted)
            assertEquals(7, frames[1].channel)
            assertTrue(frames[1].encrypted)
            assertArrayEquals(hex("aa bb cc"), frames[1].payload)
            assertEquals(255, frames[2].channel)
            assertEquals(Fragment.FIRST, frames[2].fragment)
            assertEquals(65_536L, frames[2].messageSize)
            assertTrue(frames[2].control)
            assertEquals(0, frames[3].payload.size)
        }
        assertArrayEquals(hex("03 09 00 02 00 00 01 00 aa bb"),
            Frame(3, Fragment.FIRST, true, false, hex("aa bb"), 256).encode())
    }

    @Test fun `truncated oversized and malformed headers fail closed`() {
        val wire = hex("02 01 00 02 00 00 01 00 aa bb")
        for (length in 1 until wire.size) {
            val decoder = FrameDecoder()
            decoder.feed(wire, 0, length) { fail("Unexpected frame") }
            assertThrows(EOFException::class.java) { decoder.endOfInput() }
        }
        for (header in listOf("00 83 00 00", "01 01 00 01 00 00 00 00",
            "01 01 00 01 00 80 00 01", "01 01 00 01 ff ff ff ff")) {
            val decoder = FrameDecoder()
            assertThrows(IOException::class.java) { decoder.feed(hex(header)) { fail("Invalid frame") } }
            assertThrows(IllegalStateException::class.java) { decoder.feed(wire) {} }
        }
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
