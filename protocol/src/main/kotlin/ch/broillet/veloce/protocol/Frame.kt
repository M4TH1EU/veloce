package ch.broillet.veloce.protocol

enum class Fragment(val bits: Int) { CONTINUATION(0), FIRST(1), LAST(2), COMPLETE(3) }

class Frame(
    val channel: Int,
    val fragment: Fragment,
    val encrypted: Boolean,
    val control: Boolean,
    val payload: ByteArray,
    val messageSize: Long? = null,
) {
    init {
        require(channel in 0..255)
        require(payload.size <= 65_535)
        require((fragment == Fragment.FIRST) == (messageSize != null))
        require(messageSize == null || messageSize in 1..0xffff_ffffL)
    }

    fun encode(): ByteArray {
        val headerSize = if (messageSize == null) 4 else 8
        return ByteArray(headerSize + payload.size).also { bytes ->
            bytes[0] = channel.toByte()
            bytes[1] = (fragment.bits or (if (encrypted) 8 else 0) or (if (control) 4 else 0)).toByte()
            bytes[2] = (payload.size ushr 8).toByte()
            bytes[3] = payload.size.toByte()
            messageSize?.let { length ->
                repeat(4) { index -> bytes[index + 4] = (length ushr (24 - index * 8)).toByte() }
            }
            payload.copyInto(bytes, headerSize)
        }
    }
}
