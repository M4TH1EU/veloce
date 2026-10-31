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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private data class PhoneState(val battery: Int? = null, val charging: Boolean = false, val wifi: Boolean = false,
    val connected: Boolean = false, val networkLevel: Int? = null)

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
    val surface = MaterialTheme.colorScheme.surface
    val label = buildString {
        append(status.battery?.let { "Battery $it percent" } ?: "Battery")
        if (status.charging) append(", charging")
        append(if (status.connected) if (status.wifi) ", Wi-Fi" else ", cellular" else ", offline")
        status.networkLevel?.let { append(", signal $it of 4") }
    }
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(22.dp, 16.dp)) {
            if (status.wifi) {
                for (ring in 1..3) {
                    val radius = size.width * .65f * ring / 3f
                    drawArc(color.copy(alpha = if (status.connected && (status.networkLevel ?: 0) >= ring) .9f else .25f),
                        225f, 90f, false, Offset(size.width / 2 - radius, size.height - radius), Size(radius * 2, radius * 2),
                        style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
                }
                drawCircle(color.copy(alpha = if (status.connected) .9f else .25f), 1.4.dp.toPx(), Offset(size.width / 2, size.height - 1.dp.toPx()))
            } else for (bar in 0..3) {
                val width = size.width / 6f
                val height = size.height * (bar + 1) / 4f
                drawRoundRect(color.copy(alpha = if (status.connected && (status.networkLevel ?: 0) > bar) .9f else .25f),
                    Offset(bar * size.width / 4, size.height - height), Size(width, height), CornerRadius(1.dp.toPx()))
            }
        }
        Canvas(Modifier.size(25.dp, 15.dp)) {
            val stroke = 1.2.dp.toPx()
            val end = size.width - 3.dp.toPx()
            drawRoundRect(color.copy(alpha = .8f), Offset(stroke, stroke), Size(end - stroke * 2, size.height - stroke * 2),
                CornerRadius(3.dp.toPx()), style = Stroke(stroke))
            drawLine(color.copy(alpha = .8f), Offset(end + 1.dp.toPx(), size.height * .35f),
                Offset(end + 1.dp.toPx(), size.height * .65f), stroke, cap = StrokeCap.Round)
            val fraction = (status.battery ?: 0).coerceIn(0, 100) / 100f
            drawRoundRect(color.copy(alpha = .9f), Offset(3.dp.toPx(), 3.dp.toPx()),
                Size(((end - 6.dp.toPx()) * fraction).coerceAtLeast(0f), size.height - 6.dp.toPx()), CornerRadius(1.dp.toPx()))
            if (status.charging) {
                val bolt = Path().apply {
                    moveTo(end * .55f, size.height * .12f); lineTo(end * .35f, size.height * .55f)
                    lineTo(end * .55f, size.height * .55f); lineTo(end * .4f, size.height * .9f)
                }
                drawPath(bolt, surface, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
