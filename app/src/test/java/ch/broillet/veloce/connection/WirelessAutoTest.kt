package ch.broillet.veloce.connection

import android.app.Application
import android.companion.CompanionDeviceManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], application = Application::class)
class WirelessAutoTest {
    private val context = RuntimeEnvironment.getApplication()
    private val address = "12:34:56:78:90:AB"

    @Test fun savedCarObservationIsRestoredAcrossProcessRestarts() {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        val shadow = shadowOf(manager)
        shadow.addAssociation(address)
        context.getSharedPreferences("wireless_auto", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("address", address).commit()
        val auto = WirelessAuto(context, Connections(context))
        auto.restore()
        assertTrue(auto.enabled.value)
        if (android.os.Build.VERSION.SDK_INT >= 36) assertEquals(manager.myAssociations.single().id, shadow.lastObservingDevicePresenceRequestAssociationId)
        else assertEquals(address, shadow.lastObservingDevicePresenceDeviceAddress.uppercase())
    }

    @Test fun missingAssociationDoesNotLeaveAnEnabledButBrokenSetting() {
        context.getSharedPreferences("wireless_auto", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("address", address).commit()
        val auto = WirelessAuto(context, Connections(context))
        auto.restore()
        assertFalse(auto.enabled.value)
    }

    @Test fun onlyTheConfiguredCarStartsAndDuplicatePresenceEventsAreCoalesced() {
        context.getSharedPreferences("wireless_auto", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("address", address).commit()
        val other = "AA:BB:CC:DD:EE:FF"
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().putString("wireless", other).commit()
        File(context.noBackupFilesDir, "projection.p12").writeBytes(byteArrayOf(1))
        val auto = WirelessAuto(context, Connections(context))
        auto.appeared(other)
        assertNull(shadowOf(context).nextStartedService)
        auto.appeared(address.lowercase())
        assertEquals(address, shadowOf(context).nextStartedService.getStringExtra("wireless"))
        auto.appeared(address)
        assertNull(shadowOf(context).nextStartedService)
    }
}
