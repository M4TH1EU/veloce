package ch.broillet.veloce.protocol

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDecoderTest {
    @Test fun `transport boundaries do not affect frame boundaries`() {
        val wire = hex("00 03 00 04 00 01 00 01 07 0b 00 03 aa bb cc")
        for (split in 0..wire.size) {
            val decoder = FrameDecoder()
            val frames = mutableListOf<Frame>()
            decoder.feed(wire, 0, split, frames::add)
            decoder.feed(wire, split, wire.size - split, frames::add)
            decoder.endOfInput()
            assertEquals(2, frames.size)
            assertEquals(0, frames[0].channel)
            assertArrayEquals(hex("00 01 00 01"), frames[0].payload)
            assertFalse(frames[0].encrypted)
            assertEquals(7, frames[1].channel)
            assertTrue(frames[1].encrypted)
            assertArrayEquals(hex("aa bb cc"), frames[1].payload)
        }
    }

    @Test fun `extended header survives byte by byte delivery`() {
        val frames = mutableListOf<Frame>()
        val decoder = FrameDecoder()
        hex("ff 0d 00 02 00 01 00 00 9a bc").forEach { byte ->
            decoder.feed(byteArrayOf(byte), receive = frames::add)
        }
        decoder.endOfInput()
        val frame = frames.single()
        assertEquals(255, frame.channel)
        assertEquals(Fragment.FIRST, frame.fragment)
        assertEquals(65_536L, frame.messageSize)
        assertTrue(frame.control)
        assertTrue(frame.encrypted)
        assertArrayEquals(hex("9a bc"), frame.payload)
    }

    @Test fun `encoder writes the documented wire layout`() {
        assertArrayEquals(
            hex("03 09 00 02 00 00 01 00 aa bb"),
            Frame(3, Fragment.FIRST, encrypted = true, control = false, hex("aa bb"), 256).encode(),
        )
        assertArrayEquals(
            hex("00 07 00 02 00 01"),
            Frame(0, Fragment.COMPLETE, encrypted = false, control = true, hex("00 01")).encode(),
        )
    }

    @Test fun `partial reads never discard the remainder of a USB transfer`() {
        val payload = ByteArray(65_535) { it.toByte() }
        val bytes = Frame(9, Fragment.COMPLETE, true, false, payload).encode() + hex("00 03 00 01 ab")
        val frames = mutableListOf<Frame>()
        val decoder = FrameDecoder()
        var cursor = 0
        while (cursor < bytes.size) {
            val count = minOf(16_384, bytes.size - cursor)
            decoder.feed(bytes, cursor, count, frames::add)
            cursor += count
        }
        decoder.endOfInput()
        assertEquals(2, frames.size)
        assertArrayEquals(payload, frames[0].payload)
        assertArrayEquals(hex("ab"), frames[1].payload)
    }

    @Test fun `truncation at every interior boundary fails on EOF`() {
        val bytes = hex("02 01 00 02 00 00 01 00 aa bb")
        for (length in 1 until bytes.size) {
            val decoder = FrameDecoder()
            decoder.feed(bytes, 0, length) { error("Unexpected complete frame") }
            assertThrows(EOFException::class.java) { decoder.endOfInput() }
        }
    }

    @Test fun `oversized unsigned lengths fail before allocation`() {
        for (size in listOf("00 00 00 00", "00 80 00 01", "ff ff ff ff")) {
            val decoder = FrameDecoder()
            assertThrows(IOException::class.java) {
                decoder.feed(hex("01 01 00 01 $size aa")) { error("Invalid frame emitted") }
            }
            assertThrows(IllegalStateException::class.java) {
                decoder.feed(hex("00 03 00 00")) {}
            }
        }
    }

    @Test fun `reserved flags are rejected`() {
        assertThrows(IOException::class.java) {
            FrameDecoder().feed(hex("00 83 00 00")) {}
        }
    }

    @Test fun `zero length payload does not stall or consume the next frame`() {
        val decoder = FrameDecoder()
        val frames = mutableListOf<Frame>()
        decoder.feed(hex("00 03 00 00 01 03 00 01 ff"), receive = frames::add)
        decoder.endOfInput()
        assertEquals(2, frames.size)
        assertEquals(0, frames[0].payload.size)
        assertArrayEquals(hex("ff"), frames[1].payload)
    }

    @Test fun `invalid slice is rejected without changing decoder state`() {
        val decoder = FrameDecoder()
        assertThrows(IllegalArgumentException::class.java) {
            decoder.feed(ByteArray(4), Int.MAX_VALUE, Int.MAX_VALUE) {}
        }
        decoder.endOfInput()
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
