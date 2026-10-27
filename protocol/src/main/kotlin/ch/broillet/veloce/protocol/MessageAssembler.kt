package ch.broillet.veloce.protocol

import java.io.IOException

class MessageAssembler(private val memoryLimit: Int = 8 * 1024 * 1024) {
    private class Pending(val first: Frame, val data: ByteArray, var used: Int = 0)
    private val pending = mutableMapOf<Int, Pending>()
    private var reserved = 0

    fun accept(frame: Frame, plaintext: ByteArray): ByteArray? {
        if (frame.fragment == Fragment.COMPLETE) {
            if (pending.containsKey(frame.channel)) throw IOException("Message interrupted unfinished fragments")
            return plaintext
        }
        if (frame.fragment == Fragment.FIRST) {
            val size = checkNotNull(frame.messageSize)
            if (pending.containsKey(frame.channel) || size < 2 || size > memoryLimit - reserved) {
                throw IOException("Invalid fragmented message or fragment memory limit exceeded")
            }
            pending[frame.channel] = Pending(frame, ByteArray(size.toInt()))
            reserved += size.toInt()
        }
        val message = pending[frame.channel] ?: throw IOException("Orphan message fragment")
        if (message.first.control != frame.control || message.first.encrypted != frame.encrypted ||
            plaintext.size > message.data.size - message.used) throw IOException("Inconsistent message fragments")
        plaintext.copyInto(message.data, message.used)
        message.used += plaintext.size
        if (frame.fragment != Fragment.LAST) return null
        if (message.used != message.data.size) throw IOException("Truncated fragmented message")
        pending.remove(frame.channel)
        reserved -= message.data.size
        return message.data
    }

    fun endOfInput() {
        if (pending.isNotEmpty()) throw IOException("Connection closed inside a fragmented message")
    }
}
