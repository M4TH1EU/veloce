package ch.broillet.veloce.connection

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ConnectionState(
    val active: Boolean = false,
    val projecting: Boolean = false,
    val detail: String = "Plug in or go wireless. Your launcher is ready for the road.",
    val identityReady: Boolean = false,
    val preparing: Boolean = false,
    val error: Boolean = false,
    val audioDetail: String = "Audio through the car connection",
    val audioRequest: String? = null,
    val audioCapturing: Boolean = false,
    val audioRoute: String = "",
)

class Connections(private val context: Context) {
    val identity = IdentityStore(context)
    private val mutable = MutableStateFlow(ConnectionState(identityReady = identity.exists))
    val state = mutable.asStateFlow()
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val audioMode = MutableStateFlow(preferences.getBoolean("forward_audio", true))
    val forwardAudio = audioMode.asStateFlow()
    private val lines = ArrayDeque<String>()
    private val logTime = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun update(detail: String, active: Boolean, error: Boolean = false) {
        log(detail)
        mutable.update { it.copy(detail = detail, active = active, projecting = active && it.projecting, error = error) }
    }

    fun projecting() { mutable.update { it.copy(projecting = true) } }

    fun prepare() {
        mutable.update { it.copy(preparing = true, error = false) }
        try {
            identity.installDefault()
            mutable.update { it.copy(identityReady = true, preparing = false, detail = "Default car certificate installed") }
            log("Default aasdk identity installed and verified")
        } catch (error: Exception) {
            mutable.update { it.copy(preparing = false, error = true, detail = "Certificate setup failed: ${error.message}") }
            log("Certificate setup failed: ${error.javaClass.simpleName}")
        }
    }

    fun usb(accessory: UsbAccessory) = start(Intent(context, ConnectionService::class.java).putExtra("usb", accessory))
    val lastWireless: String? get() = preferences.getString("wireless", null)
    fun wireless(address: String) {
        val canonical = address.uppercase(Locale.ROOT)
        preferences.edit { putString("wireless", canonical) }
        start(Intent(context, ConnectionService::class.java).putExtra("wireless", canonical))
    }
    fun disconnect() { context.startService(Intent(context, ConnectionService::class.java).setAction("disconnect")) }
    fun stopAudio() { context.startService(Intent(context, ConnectionService::class.java).setAction("audio-stop")) }

    fun setForwardAudio(value: Boolean) {
        if (state.value.active) return
        audioMode.value = value
        preferences.edit { putBoolean("forward_audio", value) }
    }

    fun audio(detail: String, request: String? = state.value.audioRequest, capturing: Boolean = state.value.audioCapturing) {
        log(detail)
        mutable.update { it.copy(audioDetail = detail, audioRequest = request, audioCapturing = capturing) }
    }

    fun audioRoute(detail: String) {
        if (state.value.audioRoute == detail) return
        log(detail)
        mutable.update { it.copy(audioRoute = detail) }
    }

    private fun start(intent: Intent) {
        if (!identity.exists) { update("Set up the car certificate before connecting", false, true); return }
        context.startForegroundService(intent)
    }

    @Synchronized fun log(message: String) {
        Log.i("Veloce", message)
        if (lines.size == 200) lines.removeFirst()
        lines.addLast("${LocalTime.now().format(logTime)} $message")
    }

    @Synchronized fun diagnostics(): String = lines.joinToString("\n").ifBlank { "No connection attempts yet." }
}
