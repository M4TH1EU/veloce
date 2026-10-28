package ch.broillet.veloce.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.edit
import ch.broillet.veloce.protocol.CarLink
import ch.broillet.veloce.protocol.Proto
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
internal class WirelessConnector(context: Context, private val address: String, private val status: (String) -> Unit) : Closeable {
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val resources = LinkResources()
    private val ready = CompletableFuture<CarLink>()
    private val service = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
    private val deviceId = context.getSharedPreferences("wireless", Context.MODE_PRIVATE).let { preferences ->
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit { putString("device_id", it) }
        }
    }

    fun open(): CarLink {
        resources.own(Closeable { ready.completeExceptionally(IOException("Wireless connection closed")) })
        val device = bluetooth.adapter?.bondedDevices?.firstOrNull { it.address == address }
            ?: throw IOException("Pair the phone with the car in Android's Bluetooth settings first")
        status("Reaching the car over Bluetooth")
        val socket = resources.own(device.createRfcommSocketToServiceRecord(service))
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ runCatching { socket.close() } }, 8, TimeUnit.SECONDS)
        val connected = try {
            socket.connect()
            socket
        } catch (_: IOException) {
            socket.close()
            if (resources.isClosed) throw IOException("Wireless connection cancelled")
            status("Waiting for the car · reconnect the phone from the car's Bluetooth menu if needed")
            val server = resources.own(bluetooth.adapter.listenUsingRfcommWithServiceRecord("Android Auto", service))
            try {
                val incoming = resources.own(server.accept(120_000))
                if (incoming.remoteDevice.address != address) throw IOException("A different Bluetooth device attempted to connect")
                incoming
            } finally { server.close() }
        } finally {
            timeout.cancel(false)
            timer.shutdownNow()
        }
        thread(name = "veloce-wireless-bootstrap") {
            try { bootstrap(connected) } catch (error: Exception) {
                if (ready.completeExceptionally(error)) resources.close()
            }
        }
        return try { ready.get(180, TimeUnit.SECONDS) } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun bootstrap(socket: BluetoothSocket) {
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        fun send(id: Int, body: ByteArray) = synchronized(output) {
            output.writeShort(body.size)
            output.writeShort(id)
            output.write(body)
            output.flush()
        }
        var target: Proto? = null
        var credentials: Proto? = null
        var joining = false
        while (true) {
            val id: Int
            val body: ByteArray
            try {
                val size = input.readUnsignedShort()
                id = input.readUnsignedShort()
                body = ByteArray(size).also(input::readFully)
            } catch (error: IOException) {
                if (joining) return
                throw error
            }
            when (id) {
                4 -> {
                    val version = Proto(body)
                    send(5, Proto.build {
                        number(1, version.number(1, 1)); number(2, version.number(2))
                        text(3, deviceId); number(4, 0)
                    })
                }
                1 -> { target = Proto(body); send(2, ByteArray(0)) }
                3 -> credentials = Proto(body)
                8 -> send(9, body)
            }
            val endpoint = target
            val wifi = credentials
            if (!joining && endpoint != null && wifi != null) {
                joining = true
                thread(name = "veloce-wireless-network") {
                    try {
                        val (network, phoneIp) = join(wifi)
                        send(7, Proto.build { text(1, phoneIp); number(3, 0) })
                        send(6, Proto.build { number(1, 0) })
                        val link = tcp(network, endpoint)
                        if (!ready.complete(link)) link.close()
                    } catch (error: Exception) {
                        ready.completeExceptionally(error)
                        resources.close()
                    }
                }
            }
        }
    }

    private fun join(wifi: Proto): Pair<Network, String> {
        status("Joining the car's Wi-Fi · accept Android's connection prompt")
        val specifier = carWifiSpecifier(wifi)
        if (wifi.number(4) == 8L) status("Wi-Fi mode 8 · trying WPA2-Personal adapter compatibility")
        val available = CompletableFuture<Pair<Network, String>>()
        resources.own(Closeable { available.completeExceptionally(IOException("Wi-Fi request cancelled")) })
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                properties.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress?.let {
                    available.complete(network to it)
                }
            }
            override fun onUnavailable() { available.completeExceptionally(IOException("Android could not join the car's Wi-Fi")) }
            override fun onLost(network: Network) { resources.close() }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        connectivity.requestNetwork(request, callback, 60_000)
        resources.own(Closeable { connectivity.unregisterNetworkCallback(callback) })
        return available.get(65, TimeUnit.SECONDS)
    }

    private fun tcp(network: Network, endpoint: Proto): CarLink {
        val ip = endpoint.text(1)
        val port = endpoint.number(2).toInt()
        if (ip.split('.').size != 4 || ip.split('.').any { it.toIntOrNull() !in 0..255 } || port !in 1..65535) {
            throw IOException("The car supplied an invalid projection address")
        }
        status("Opening wireless projection")
        var failure: IOException? = null
        repeat(8) {
            if (resources.isClosed) throw IOException("Wireless connection cancelled")
            val socket = resources.own(network.socketFactory.createSocket())
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000
                val target = InetAddress.getByAddress(ip.split('.').map { it.toInt().toByte() }.toByteArray())
                socket.connect(InetSocketAddress(target, port), 4000)
                return object : CarLink {
                    override val input: InputStream = socket.getInputStream()
                    override val output: OutputStream = socket.getOutputStream()
                    override fun close() = resources.close()
                }
            } catch (error: IOException) {
                socket.close()
                if (resources.isClosed) throw IOException("Wireless connection cancelled", error)
                failure = error
                Thread.sleep(1000)
            }
        }
        throw IOException("The car's projection port did not become available", failure)
    }

    override fun close() = resources.close()
}
