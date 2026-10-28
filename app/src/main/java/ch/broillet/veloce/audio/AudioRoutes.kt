package ch.broillet.veloce.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable

internal class AudioRoutes(context: Context, private val publish: (String) -> Unit) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        val devices = manager.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        publish("Android media route: " + devices.joinToString { "${it.productName} (${when (it.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth hands-free"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
            else -> "type ${it.type}"
        }})" }.ifEmpty { "unknown" })
    }

    override fun close() = manager.unregisterAudioDeviceCallback(callback)
}
