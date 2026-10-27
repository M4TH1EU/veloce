package ch.broillet.veloce.protocol

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class Proto(bytes: ByteArray) {
    private val numbers = mutableMapOf<Int, MutableList<Long>>()
    private val blobs = mutableMapOf<Int, MutableList<ByteArray>>()

    init {
        val input = CodedInputStream.newInstance(bytes)
        input.setSizeLimit(8 * 1024 * 1024)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (tag and 7) {
                0 -> numbers.getOrPut(tag ushr 3, ::mutableListOf).add(input.readUInt64())
                2 -> blobs.getOrPut(tag ushr 3, ::mutableListOf).add(input.readByteArray())
                1, 5 -> input.skipField(tag)
                else -> throw IOException("Unsupported protobuf wire type")
            }
        }
    }

    fun number(field: Int, default: Long = 0): Long = numbers[field]?.firstOrNull() ?: default
    fun bytes(field: Int): ByteArray? = blobs[field]?.firstOrNull()
    fun messages(field: Int): List<Proto> = blobs[field].orEmpty().map(::Proto)
    fun text(field: Int): String = bytes(field)?.toString(Charsets.UTF_8).orEmpty()
    fun has(field: Int): Boolean = numbers.containsKey(field) || blobs.containsKey(field)
    fun integers(field: Int): List<Int> = buildList {
        numbers[field]?.forEach { add(it.toInt()) }
        blobs[field]?.forEach { packed ->
            val input = CodedInputStream.newInstance(packed)
            while (!input.isAtEnd) add(input.readInt32())
        }
    }

    class Builder {
        private val bytes = ByteArrayOutputStream()
        private val out = CodedOutputStream.newInstance(bytes)
        fun number(field: Int, value: Long) { out.writeUInt64(field, value) }
        fun bytes(field: Int, value: ByteArray) { out.writeByteArray(field, value) }
        fun text(field: Int, value: String) { out.writeString(field, value) }
        fun build(): ByteArray { out.flush(); return bytes.toByteArray() }
    }

    companion object {
        fun build(write: Builder.() -> Unit): ByteArray = Builder().apply(write).build()
    }
}
