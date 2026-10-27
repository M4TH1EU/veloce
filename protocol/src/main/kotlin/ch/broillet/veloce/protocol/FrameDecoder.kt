package ch.broillet.veloce.protocol

import java.io.EOFException
import java.io.IOException

/** One decoder per connection. Feed complete transport reads, including USB bulk transfers. */
class FrameDecoder(private val maxMessageSize: Long = 8L * 1024 * 1024) {
    private val header = ByteArray(8)
    private var headerSize = 4
    private var headerUsed = 0
    private var payload: ByteArray? = null
    private var payloadUsed = 0
    private var failed = false

    init { require(maxMessageSize in 1..0xffff_ffffL) }

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, receive: (Frame) -> Unit) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        check(!failed) { "Discard the decoder after a protocol failure" }
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            if (headerUsed < headerSize) {
                val count = minOf(end - cursor, headerSize - headerUsed)
                bytes.copyInto(header, headerUsed, cursor, cursor + count)
                cursor += count
                headerUsed += count
                if (headerUsed < headerSize) continue
                if (headerSize == 4) {
                    if (unsigned(header[1]) and 0xf0 != 0) reject("Unsupported frame flags")
                    if (unsigned(header[1]) and 3 == Fragment.FIRST.bits) {
                        headerSize = 8
                        continue
                    }
                }
            }

            if (payload == null) {
                if (headerSize == 8 && (messageSize() == 0L || messageSize() > maxMessageSize)) {
                    reject("Message exceeds the decoder resource limit")
                }
                payload = ByteArray((unsigned(header[2]) shl 8) or unsigned(header[3]))
            }
            val target = checkNotNull(payload)
            val count = minOf(end - cursor, target.size - payloadUsed)
            bytes.copyInto(target, payloadUsed, cursor, cursor + count)
            cursor += count
            payloadUsed += count
            if (payloadUsed == target.size) {
                val flags = unsigned(header[1])
                val frame = Frame(
                    unsigned(header[0]), Fragment.entries[flags and 3], flags and 8 != 0,
                    flags and 4 != 0, target, if (headerSize == 8) messageSize() else null,
                )
                headerUsed = 0
                headerSize = 4
                payload = null
                payloadUsed = 0
                receive(frame)
            }
        }
    }

    fun endOfInput() {
        check(!failed) { "Discard the decoder after a protocol failure" }
        if (headerUsed != 0 || payload != null) {
            failed = true
            throw EOFException("Connection ended inside a frame")
        }
    }

    private fun messageSize(): Long = (4..7).fold(0L) { size, index -> (size shl 8) or unsigned(header[index]).toLong() }

    private fun reject(message: String): Nothing {
        failed = true
        throw IOException(message)
    }

    private fun unsigned(byte: Byte) = byte.toInt() and 0xff
}
