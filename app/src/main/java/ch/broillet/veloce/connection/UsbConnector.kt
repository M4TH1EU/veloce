package ch.broillet.veloce.connection

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import ch.broillet.veloce.protocol.CarLink
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class UsbConnector(context: Context, private val accessory: UsbAccessory) : Closeable {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val resources = LinkResources()

    fun open(): CarLink {
        if (manager.accessoryList?.contains(accessory) != true || !manager.hasPermission(accessory)) {
            throw IOException("The USB accessory is unavailable or permission was denied")
        }
        val descriptor = resources.own(manager.openAccessory(accessory) ?: throw IOException("Cannot open the USB accessory"))
        val inputDescriptor = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        val stream = resources.own(ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor))
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("USB accessory reads must consume whole transfers")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val poll = StructPollfd().apply { fd = inputDescriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                while (!resources.isClosed) {
                    if (Os.poll(arrayOf(poll), 500) > 0) return stream.read(bytes, offset, length)
                }
                throw IOException("USB connection closed")
            }
        }
        val output = resources.own(ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)))
        return object : CarLink {
            override val input: InputStream = input
            override val output: OutputStream = output
            override fun close() = resources.close()
        }
    }

    override fun close() = resources.close()
}
