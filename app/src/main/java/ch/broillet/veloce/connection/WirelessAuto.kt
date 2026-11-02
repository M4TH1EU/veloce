package ch.broillet.veloce.connection

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WirelessAuto(private val context: Context, private val connections: Connections) {
    private val preferences = context.getSharedPreferences("wireless_auto", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = mutable.asStateFlow()
    private val manager get() = context.getSystemService(CompanionDeviceManager::class.java)
    private var lastAttempt: Long? = null
    private val address get() = preferences.getString("address", null) ?: connections.lastWireless

    fun restore() {
        if (!enabled.value) return
        try {
            val association = manager.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(address, true) }
            if (association == null) {
                preferences.edit { putBoolean("enabled", false) }
                mutable.value = false
                connections.log("Wireless auto-connect needs to be paired again")
            } else {
                preferences.edit { putString("address", checkNotNull(association.deviceMacAddress).toString()) }
                observe(association)
            }
        } catch (error: Exception) { connections.log("Could not restore wireless auto-connect: ${error.message}") }
    }

    fun enable(approve: (IntentSender) -> Unit) {
        val address = connections.lastWireless ?: return
        try {
            check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) { "Companion device setup is unavailable" }
            val existing = manager.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(address, true) }
            if (existing != null) { associated(existing); return }
            val request = AssociationRequest.Builder().setSingleDevice(true)
                .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build()).build()
            manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(sender: IntentSender) = approve(sender)
                override fun onAssociationCreated(info: AssociationInfo) = associated(info)
                override fun onFailure(error: CharSequence?) { connections.log("Wireless auto-connect setup: $error") }
            })
        } catch (error: Exception) { connections.log("Wireless auto-connect setup failed: ${error.message}") }
    }

    @Suppress("DEPRECATION")
    fun associated(info: AssociationInfo) {
        if (!info.deviceMacAddress?.toString().equals(connections.lastWireless, true)) return
        try {
            preferences.edit { putString("address", checkNotNull(info.deviceMacAddress).toString()) }
            observe(info)
            preferences.edit { putBoolean("enabled", true) }
            mutable.value = true
        } catch (error: Exception) { connections.log("Could not observe the wireless device: ${error.message}") }
    }

    @Suppress("DEPRECATION")
    private fun observe(info: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 36) manager.startObservingDevicePresence(
            ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
        else manager.startObservingDevicePresence(checkNotNull(info.deviceMacAddress).toString())
    }

    fun disable() {
        preferences.edit { putBoolean("enabled", false) }
        mutable.value = false
        lastAttempt = null
        manager.myAssociations.forEach { info -> runCatching { manager.disassociate(info.id) } }
    }

    fun appeared(address: String) {
        if (!enabled.value || !address.equals(this.address, true) || connections.state.value.active) return
        val now = SystemClock.elapsedRealtime()
        if (lastAttempt?.let { now - it < 10_000 } == true) return
        lastAttempt = now
        connections.log("Saved car detected · starting wireless automatically")
        runCatching { connections.wireless(address) }
            .onFailure { connections.log("Could not start wireless automatically: ${it.message}") }
    }
}
