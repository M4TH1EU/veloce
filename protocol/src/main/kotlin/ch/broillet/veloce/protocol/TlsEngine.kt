package ch.broillet.veloce.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

/** Projection embeds TLS records in its own frames; it is not a TLS socket. */
class TlsEngine(identity: KeyStore, password: CharArray) {
    private val engine: SSLEngine
    private var handshakeInput = ByteArray(0)
    val ready: Boolean @Synchronized get() = engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING &&
        !engine.isInboundDone && !engine.isOutboundDone

    init {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identity, password) }
        engine = SSLContext.getInstance("TLSv1.2").apply {
            init(keys.keyManagers, null, SecureRandom())
        }.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = false
            enabledProtocols = arrayOf("TLSv1.2")
            beginHandshake()
        }
    }

    @Synchronized
    fun handshake(incoming: ByteArray): List<ByteArray> {
        if (ready) throw SSLException("TLS handshake is already complete")
        if (incoming.size > 256 * 1024 - handshakeInput.size) throw SSLException("Oversized TLS handshake")
        val input = ByteBuffer.wrap(handshakeInput + incoming)
        val records = mutableListOf<ByteArray>()
        var turns = 0
        while (!ready) {
            if (++turns > 128) throw SSLException("TLS handshake made no progress")
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> runTasks()
                HandshakeStatus.NEED_WRAP -> {
                    val output = ByteBuffer.allocate(engine.session.packetBufferSize)
                    val result = engine.wrap(ByteBuffer.allocate(0), output)
                    if (result.status != Status.OK) throw SSLException("TLS handshake wrap: ${result.status}")
                    if (output.position() > 0) records.add(output.takeBytes())
                }
                HandshakeStatus.NEED_UNWRAP -> {
                    val output = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = engine.unwrap(input, output)
                    if (result.status == Status.BUFFER_UNDERFLOW) break
                    if (result.status != Status.OK) throw SSLException("TLS handshake unwrap: ${result.status}")
                    if (output.position() != 0) throw SSLException("Application data inside TLS handshake")
                }
                else -> break
            }
        }
        handshakeInput = ByteArray(input.remaining()).also(input::get)
        if (ready && handshakeInput.isNotEmpty()) throw SSLException("Trailing data after TLS handshake")
        return records
    }

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray = transform(plaintext, encrypt = true)

    @Synchronized
    fun decrypt(ciphertext: ByteArray): ByteArray = transform(ciphertext, encrypt = false)

    private fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        if (!ready) throw SSLException("TLS authentication has not finished")
        val input = ByteBuffer.wrap(bytes)
        val resultBytes = ByteArrayOutputStream()
        val output = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, engine.session.applicationBufferSize) * 2)
        while (input.hasRemaining()) {
            output.clear()
            val result = if (encrypt) engine.wrap(input, output) else engine.unwrap(input, output)
            if (result.status != Status.OK || result.bytesConsumed() == 0) throw SSLException("TLS record: ${result.status}")
            if (result.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
            if (!ready) throw SSLException("Unexpected TLS renegotiation")
            resultBytes.write(output.takeBytes())
        }
        return resultBytes.toByteArray()
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: break).run()
    }

    private fun ByteBuffer.takeBytes(): ByteArray {
        flip()
        return ByteArray(remaining()).also(::get)
    }
}
