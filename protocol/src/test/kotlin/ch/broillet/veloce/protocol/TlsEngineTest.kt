package ch.broillet.veloce.protocol

import java.util.ArrayDeque
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class TlsEngineTest {
    @Test fun `fragmented TLS handshake secures records and rejects incomplete ciphertext`() {
        val server = TlsEngine(TestIdentity.store, TestIdentity.password)
        assertThrows(SSLException::class.java) { server.encrypt(byteArrayOf(1)) }
        val client = TestIdentity.client()
        val records = ArrayDeque<ByteArray>()
        TestIdentity.handshake(client, { bytes ->
            bytes.asList().chunked(7).forEach { server.handshake(it.toByteArray()).forEach(records::addLast) }
        }, { records.removeFirst() })
        val payload = ByteArray(12 * 1024) { (it % 251).toByte() }
        assertArrayEquals(payload, TestIdentity.unwrap(client, server.encrypt(payload)))
        assertArrayEquals(payload, server.decrypt(TestIdentity.wrap(client, payload)))
        assertThrows(SSLException::class.java) { server.handshake(byteArrayOf(1)) }
        val encrypted = TestIdentity.wrap(client, payload)
        assertThrows(SSLException::class.java) { server.decrypt(encrypted.copyOf(encrypted.size - 1)) }
    }
}
