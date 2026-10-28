package ch.broillet.veloce.connection

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiNetworkSpecifier
import ch.broillet.veloce.protocol.Proto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarWifiNetworkTest {
    @Test fun adapterMode8RequestsWpa2WithTheSuppliedCredentials() {
        val network = carWifiSpecifier(credentials(8)).wifiConfiguration
        assertEquals("\"Car adapter\"", network.SSID)
        assertEquals("12:34:56:78:90:ab", network.BSSID)
        assertEquals("\"test-password\"", network.preSharedKey)
        assertTrue(network.allowedKeyManagement[WifiConfiguration.KeyMgmt.WPA_PSK])
        assertFalse(network.allowedKeyManagement[WifiConfiguration.KeyMgmt.WPA_EAP])
    }

    @Test fun personalModesAndOpenNetworksKeepTheirAuthentication() {
        for (mode in 4L..6L) {
            assertTrue(carWifiSpecifier(credentials(mode)).wifiConfiguration.allowedKeyManagement[WifiConfiguration.KeyMgmt.WPA_PSK])
        }
        val open = carWifiSpecifier(credentials(1, "")).wifiConfiguration
        assertNull(open.preSharedKey)
        assertTrue(open.allowedKeyManagement[WifiConfiguration.KeyMgmt.NONE])
    }

    @Test fun malformedOrUnsupportedCredentialsNeverBecomeOpenNetworks() {
        for ((mode, password) in listOf(8L to "", 8L to "short", 8L to "é".repeat(12), 1L to "test-password", 2L to "test-password", 7L to "test-password", 99L to "test-password")) {
            assertThrows(IOException::class.java) { carWifiSpecifier(credentials(mode, password)) }
        }
    }

    @Test fun missingAdapterBssidDoesNotPreventSsidMatching() {
        for (bssid in listOf("", "invalid", "00:00:00:00:00:00")) {
            assertNull(carWifiSpecifier(credentials(8, bssid = bssid)).wifiConfiguration.BSSID)
        }
    }

    private fun credentials(mode: Long, password: String = "test-password", bssid: String = "12:34:56:78:90:ab") = Proto(Proto.build {
        text(1, "Car adapter"); text(2, password); text(3, bssid); number(4, mode)
    })

    private val WifiNetworkSpecifier.wifiConfiguration: WifiConfiguration
        get() = ReflectionHelpers.getField(this, "wifiConfiguration")
}
