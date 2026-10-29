package ch.broillet.veloce.connection

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WirelessHandoffTest {
    @Test fun adapterClosingBluetoothDoesNotAbortWifiHandoff() {
        val messages = mutableListOf<String>()
        reportWifiReady({ _, _ -> throw IOException("Bluetooth closed") }, "192.168.50.2", messages::add)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("continuing over TCP"))
    }
}
