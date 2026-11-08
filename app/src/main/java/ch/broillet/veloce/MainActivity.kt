package ch.broillet.veloce

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.broillet.veloce.connection.ConnectionSetupActivity
import ch.broillet.veloce.connection.ConnectionService
import ch.broillet.veloce.audio.AudioRoutes
import ch.broillet.veloce.ui.VeloceApp
import ch.broillet.veloce.ui.VeloceTheme
import ch.broillet.veloce.ui.PairedCar
import ch.broillet.veloce.ui.PairedCarSheet

class MainActivity : ComponentActivity() {
    private val model by viewModels<HomeModel>()
    private val usb get() = getSystemService(UsbManager::class.java)
    private val usbPermissionAction get() = "$packageName.USB_PERMISSION"
    private var audioRoutes: AudioRoutes? = null
    private var wirelessCars by mutableStateOf<List<PairedCar>?>(null)
    private val companionApproval = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.let(model.wirelessAuto::associated)
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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        registerReceiver(usbEvents, IntentFilter(usbPermissionAction), RECEIVER_NOT_EXPORTED)
        if (savedInstanceState == null) handleAccessory(intent)
        setContent {
            val settings by model.settings.state.collectAsStateWithLifecycle()
            VeloceTheme(settings) {
                val light = MaterialTheme.colorScheme.background.luminance() > .5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                }
                VeloceApp(model, ::connectUsb, ::connectWireless, ::openMediaAccess, ::shareDiagnostics,
                    ::requestAudio, { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, {
                        if (model.wirelessAuto.enabled.value) model.wirelessAuto.disable()
                        else model.wirelessAuto.enable { companionApproval.launch(IntentSenderRequest.Builder(it).build()) }
                    })
                wirelessCars?.let { cars ->
                    PairedCarSheet(cars, { wirelessCars = null }) { car ->
                        wirelessCars = null
                        model.connections.wireless(car.address)
                    }
                }
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

    private fun requestAudio() {
        val state = model.connections.state.value
        val session = state.audioRequest ?: return
        if (!state.active || state.audioCapturing) return
        startActivity(ConnectionSetupActivity.intent(this, session))
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
        wirelessCars = cars.map { PairedCar(it.name ?: it.address, it.address) }
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
        model.connections.state.value.sessionId?.let { session ->
            startService(Intent(this, ConnectionService::class.java).setAction("prepare").putExtra("session", session))
        }
        audioRoutes = AudioRoutes(this, model.connections::audioRoute)
    }
    override fun onPause() { audioRoutes?.close(); audioRoutes = null; super.onPause() }
    override fun onDestroy() { unregisterReceiver(usbEvents); super.onDestroy() }
}
