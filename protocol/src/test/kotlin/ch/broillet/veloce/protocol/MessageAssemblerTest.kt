package ch.broillet.veloce.protocol

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MessageAssemblerTest {
    @Test fun `independent channels can interleave fragmented messages`() {
        val assembler = MessageAssembler()
        val a = frame(1, Fragment.FIRST, 4)
        val b = frame(2, Fragment.FIRST, 3)
        assertNull(assembler.accept(a, byteArrayOf(1, 2)))
        assertNull(assembler.accept(b, byteArrayOf(8)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), assembler.accept(frame(1, Fragment.LAST), byteArrayOf(3, 4)))
        assertArrayEquals(byteArrayOf(8, 9, 10), assembler.accept(frame(2, Fragment.LAST), byteArrayOf(9, 10)))
        assembler.endOfInput()
    }

    @Test fun `aggregate reservation is bounded before allocation`() {
        val assembler = MessageAssembler(10)
        assembler.accept(frame(1, Fragment.FIRST, 8), byteArrayOf(1))
        assertThrows(IOException::class.java) { assembler.accept(frame(2, Fragment.FIRST, 8), byteArrayOf(2)) }
    }

    @Test fun `flags length and fragment ordering are checked`() {
        assertThrows(IOException::class.java) { MessageAssembler().accept(frame(1, Fragment.LAST), byteArrayOf(1)) }
        val assembler = MessageAssembler()
        assembler.accept(frame(1, Fragment.FIRST, 3), byteArrayOf(1))
        assertThrows(IOException::class.java) {
            assembler.accept(Frame(1, Fragment.LAST, false, false, ByteArray(0)), byteArrayOf(2, 3))
        }
        val truncated = MessageAssembler()
        truncated.accept(frame(1, Fragment.FIRST, 3), byteArrayOf(1))
        assertThrows(IOException::class.java) { truncated.accept(frame(1, Fragment.LAST), byteArrayOf(2)) }
        assertThrows(IOException::class.java) { truncated.endOfInput() }
    }

    private fun frame(channel: Int, fragment: Fragment, size: Long? = null) = Frame(channel, fragment, true, false, ByteArray(0), size)
}
