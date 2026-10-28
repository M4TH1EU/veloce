package ch.broillet.veloce.connection

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import ch.broillet.veloce.VeloceApplication
import androidx.annotation.RequiresApi

class CarPresenceService : CompanionDeviceService() {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(info: AssociationInfo) {
        info.deviceMacAddress?.toString()?.let { (application as VeloceApplication).wirelessAuto.appeared(it) }
    }

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event != DevicePresenceEvent.EVENT_BT_CONNECTED) return
        val info = getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull { it.id == event.associationId } ?: return
        info.deviceMacAddress?.toString()?.let { (application as VeloceApplication).wirelessAuto.appeared(it) }
    }
}
