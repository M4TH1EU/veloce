package ch.broillet.veloce.connection

import ch.broillet.veloce.protocol.Proto
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WirelessHandoffTest {
    @Test fun reportsIpv4BeforeOpeningProjection() {
        val messages = mutableListOf<Pair<Int, Proto>>()
        reportWifiReady({ id, bytes -> messages.add(id to Proto(bytes)) }, "192.168.50.2") { fail(it) }
        assertEquals(listOf(7, 6), messages.map { it.first })
        assertEquals("192.168.50.2", messages[0].second.text(1))
        assertEquals(0L, messages[0].second.number(3, -1))
        assertEquals(0L, messages[1].second.number(1, -1))
    }

    @Test fun adapterClosingBluetoothDoesNotAbortWifiHandoff() {
        val messages = mutableListOf<String>()
        reportWifiReady({ _, _ -> throw IOException("Bluetooth closed") }, "192.168.50.2", messages::add)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("continuing over TCP"))
    }
}
