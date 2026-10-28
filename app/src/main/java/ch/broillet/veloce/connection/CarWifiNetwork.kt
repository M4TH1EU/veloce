package ch.broillet.veloce.connection

import android.net.MacAddress
import android.net.wifi.WifiNetworkSpecifier
import ch.broillet.veloce.protocol.Proto
import java.io.IOException

internal fun carWifiSpecifier(wifi: Proto): WifiNetworkSpecifier {
    val password = wifi.text(2)
    val security = wifi.number(4)
    when (security) {
        1L -> if (password.isNotEmpty()) throw IOException("The car supplied a password for open Wi-Fi")
        // Some wired-to-wireless adapters report 8 while supplying a personal-network password.
        4L, 5L, 6L, 8L -> if (password.length !in 8..63 || password.any { it.code > 127 }) {
            throw IOException("The car supplied an invalid WPA passphrase (mode $security)")
        }
        else -> throw IOException("The car uses an unsupported Wi-Fi security mode (mode $security)")
    }
    return try {
        WifiNetworkSpecifier.Builder().setSsid(wifi.text(1)).apply {
            runCatching { MacAddress.fromString(wifi.text(3)) }.getOrNull()
                ?.takeIf { it.toString() != "00:00:00:00:00:00" }?.let(::setBssid)
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
    } catch (_: IllegalArgumentException) {
        throw IOException("The car supplied invalid Wi-Fi network details (mode $security)")
    }
}
