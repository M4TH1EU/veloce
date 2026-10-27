package ch.broillet.veloce.protocol

import java.util.ArrayDeque
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class TlsEngineTest {
    @Test fun `TLS handshake accepts fragmented encapsulated records`() {
        val server = TlsEngine(TestIdentity.store, TestIdentity.password)
        val client = TestIdentity.client()
        val records = ArrayDeque<ByteArray>()
        TestIdentity.handshake(client, { bytes ->
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + 7)
                server.handshake(bytes.copyOfRange(offset, end)).forEach(records::addLast)
                offset = end
            }
        }, { records.removeFirst() })
        assertTrue(server.ready)
        assertThrows(SSLException::class.java) { server.handshake(byteArrayOf(1)) }
    }

    @Test fun `real TLS handshake and application records work in both directions`() {
        val server = TlsEngine(TestIdentity.store, TestIdentity.password)
        val client = TestIdentity.client()
        val records = ArrayDeque<ByteArray>()
        TestIdentity.handshake(client, { server.handshake(it).forEach(records::addLast) }, { records.removeFirst() })
        assertTrue(server.ready)
        val payload = ByteArray(12 * 1024) { (it % 251).toByte() }
        assertArrayEquals(payload, TestIdentity.unwrap(client, server.encrypt(payload)))
        assertArrayEquals(payload, server.decrypt(TestIdentity.wrap(client, payload)))
    }

    @Test fun `unauthenticated data and partial encrypted records fail`() {
        val server = TlsEngine(TestIdentity.store, TestIdentity.password)
        assertThrows(SSLException::class.java) { server.encrypt(byteArrayOf(1)) }
        val client = TestIdentity.client()
        val records = ArrayDeque<ByteArray>()
        TestIdentity.handshake(client, { server.handshake(it).forEach(records::addLast) }, { records.removeFirst() })
        val encrypted = TestIdentity.wrap(client, byteArrayOf(1, 2, 3))
        assertThrows(SSLException::class.java) { server.decrypt(encrypted.copyOf(encrypted.size - 1)) }
    }
}
