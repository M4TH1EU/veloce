package ch.broillet.veloce.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.broillet.veloce.R

private data class PhoneState(val battery: Int? = null, val charging: Boolean = false, val wifi: Boolean = false,
    val connected: Boolean = false, val networkLevel: Int? = null)

private val wifiIcons = listOf(R.drawable.ic_rounded_wifi_0, R.drawable.ic_rounded_wifi_1,
    R.drawable.ic_rounded_wifi_2, R.drawable.ic_rounded_wifi_3, R.drawable.ic_rounded_wifi_4)
private val cellularIcons = listOf(R.drawable.ic_rounded_cellular_0, R.drawable.ic_rounded_cellular_1,
    R.drawable.ic_rounded_cellular_2, R.drawable.ic_rounded_cellular_3, R.drawable.ic_rounded_cellular_4)

@Composable
internal fun PhoneStatus(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val status by produceState(PhoneState(), context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        var capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        var cellular: Int? = runCatching { telephony?.signalStrength?.level }.getOrNull()
        fun publishNetwork() {
            val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val rssi = capabilities?.signalStrength?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
            val level = if (wifi) rssi?.let { ((it + 95) / 10).coerceIn(0, 4) } else cellular
            value = value.copy(wifi = wifi, connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                networkLevel = level)
        }
        val battery = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                value = value.copy(battery = level.takeIf { it >= 0 && scale > 0 }?.let { it * 100 / scale },
                    charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
        }
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)?.let { battery.onReceive(context, it) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                context.mainExecutor.execute { capabilities = caps; publishNetwork() }
            }
            override fun onLost(network: Network) {
                context.mainExecutor.execute { capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork); publishNetwork() }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        val signal = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(strength: SignalStrength) { cellular = strength.level; publishNetwork() }
        }
        val registered = runCatching { telephony?.registerTelephonyCallback(context.mainExecutor, signal); telephony != null }.getOrDefault(false)
        publishNetwork()
        awaitDispose {
            context.unregisterReceiver(battery)
            connectivity.unregisterNetworkCallback(callback)
            if (registered) telephony?.unregisterTelephonyCallback(signal)
        }
    }
    val color = MaterialTheme.colorScheme.onSurface
    val label = buildString {
        append(status.battery?.let { "Battery $it percent" } ?: "Battery")
        if (status.charging) append(", charging")
        append(if (status.connected) if (status.wifi) ", Wi-Fi" else ", cellular" else ", offline")
        status.networkLevel?.let { append(", signal $it of 4") }
    }
    val level = if (status.connected) (status.networkLevel ?: 0).coerceIn(0, 4) else 0
    val networkIcon = (if (status.wifi) wifiIcons else cellularIcons)[level]
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(networkIcon), contentDescription = null, modifier = Modifier.size(24.dp),
            tint = if (status.connected) color else color.copy(alpha = .38f))
        BatteryIndicator(status.battery, status.charging)
    }
}

@Composable
private fun BatteryIndicator(level: Int?, charging: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    val battery = painterResource(R.drawable.ic_rounded_battery)
    val fraction = (level ?: 0).coerceIn(0, 100) / 100f
    Box(Modifier.size(30.dp, 20.dp), contentAlignment = Alignment.Center) {
        Icon(battery, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = color.copy(alpha = .28f))
        Icon(battery, contentDescription = null, modifier = Modifier.fillMaxSize().drawWithContent {
            clipRect(left = size.width * (880f - 800f * fraction) / 960f) { this@drawWithContent.drawContent() }
        }, tint = color)
        if (charging) {
            Icon(painterResource(R.drawable.ic_rounded_bolt), contentDescription = null, modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.surface)
        }
    }
}
