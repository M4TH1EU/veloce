package ch.broillet.veloce

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ch.broillet.veloce.connection.ConnectionService
import ch.broillet.veloce.audio.AudioRoutes
import ch.broillet.veloce.ui.VeloceApp
import ch.broillet.veloce.ui.VeloceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<HomeModel>()
    private val usb get() = getSystemService(UsbManager::class.java)
    private val usbPermissionAction get() = "$packageName.USB_PERMISSION"
    private var requestedAudio: String? = null
    private var consentSession: String? = null
    private var audioRoutes: AudioRoutes? = null
    private val companionApproval = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.let(model.wirelessAuto::associated)
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askAudioConsent() else audioDenied("Allow audio recording permission to capture media playback")
    }
    private val audioConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val session = consentSession
        consentSession = null
        if (session != null && session == model.connections.state.value.audioRequest) {
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startService(Intent(this, ConnectionService::class.java).setAction("audio")
                    .putExtra("session", session).putExtra("result", result.resultCode).putExtra("consent", data))
            } else model.connections.audio("Audio sharing declined · tap Enable audio to retry")
        }
        requestAudio()
    }
    private val wirelessPermissions get() = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (wirelessPermissions.all { permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }) chooseCar()
        else model.connections.update("Wireless needs Bluetooth, nearby Wi-Fi, and local network access", false, true)
    }
    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == usbPermissionAction) {
                val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                if (accessory != null && usb.hasPermission(accessory)) model.connections.usb(accessory)
                else model.connections.update("USB accessory permission was denied", false, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedAudio = savedInstanceState?.getString("requested_audio")
        consentSession = savedInstanceState?.getString("consent_session")
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        registerReceiver(usbEvents, IntentFilter(usbPermissionAction), RECEIVER_NOT_EXPORTED)
        if (savedInstanceState == null) handleAccessory(intent)
        setContent {
            VeloceTheme { VeloceApp(model, ::connectUsb, ::connectWireless, ::openMediaAccess, ::shareDiagnostics,
                { requestAudio(manual = true) }, { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, {
                    if (model.wirelessAuto.enabled.value) model.wirelessAuto.disable()
                    else model.wirelessAuto.enable { companionApproval.launch(IntentSenderRequest.Builder(it).build()) }
                }) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.connections.state.collect { requestAudio() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAccessory(intent)
    }

    private fun handleAccessory(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)?.let(::requestUsb)
        }
    }

    private fun connectUsb() {
        val accessory = usb.accessoryList?.firstOrNull { it.manufacturer == "Android" &&
            it.model in listOf("Android Auto", "Android Open Automotive Protocol") }
        if (accessory == null) model.connections.update("Plug into the car's Android Auto USB port and select Veloce in Android's accessory chooser", false)
        else requestUsb(accessory)
    }

    private fun requestUsb(accessory: UsbAccessory) {
        if (!model.connections.state.value.identityReady) {
            model.connections.update("Set up the certificate, then reconnect the USB cable", false)
        } else if (usb.hasPermission(accessory)) model.connections.usb(accessory)
        else usb.requestPermission(accessory, PendingIntent.getBroadcast(this, 0,
            Intent(usbPermissionAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun connectWireless() {
        permissions.launch((wirelessPermissions + Manifest.permission.POST_NOTIFICATIONS).toTypedArray())
    }

    private fun requestAudio(manual: Boolean = false) {
        val state = model.connections.state.value
        val session = state.audioRequest ?: return
        if (!state.active || state.audioCapturing || consentSession != null || (!manual && requestedAudio == session)) return
        requestedAudio = session
        consentSession = session
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askAudioConsent()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askAudioConsent() {
        val session = consentSession ?: return
        if (session != model.connections.state.value.audioRequest || !model.connections.state.value.active) {
            consentSession = null
            requestAudio()
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            audioConsent.launch(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
        } catch (error: Exception) { audioDenied("Could not request audio sharing: ${error.message}") }
    }

    private fun audioDenied(detail: String) {
        if (consentSession == model.connections.state.value.audioRequest) model.connections.audio(detail)
        consentSession = null
        requestAudio()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("requested_audio", requestedAudio)
        outState.putString("consent_session", consentSession)
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("MissingPermission")
    private fun chooseCar() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter?.isEnabled != true) {
            model.connections.update("Turn on Bluetooth and Wi-Fi before connecting", false, true)
            return
        }
        val cars = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        if (cars.isEmpty()) {
            model.connections.update("Pair the phone with your car in Android's Bluetooth settings first", false)
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.choose_car)
            .setItems(cars.map { it.name ?: it.address }.toTypedArray()) { _, index -> model.connections.wireless(cars[index].address) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun openMediaAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, model.media.listener.flattenToString())
        })
    }

    private fun shareDiagnostics() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, model.connections.diagnostics()), getString(R.string.share_diagnostics)))
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
        audioRoutes = AudioRoutes(this, model.connections::audioRoute)
    }
    override fun onPause() { audioRoutes?.close(); audioRoutes = null; super.onPause() }
    override fun onDestroy() { unregisterReceiver(usbEvents); super.onDestroy() }
}
