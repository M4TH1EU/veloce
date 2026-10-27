package ch.broillet.veloce.connection

import android.content.Context
import android.util.AtomicFile
import ch.broillet.veloce.protocol.PemIdentity
import ch.broillet.veloce.protocol.TlsEngine
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class IdentityStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "projection.p12"))
    val exists: Boolean get() = file.baseFile.exists()

    fun tls(): TlsEngine {
        val store = KeyStore.getInstance("PKCS12").apply { file.openRead().use { load(it, charArrayOf()) } }
        return TlsEngine(store, charArrayOf())
    }

    fun installDefault() {
        val base = "https://raw.githubusercontent.com/opencardev/aasdk/9bf6adf933665dee26532201719fac14a047ccf1/cert/"
        val certificate = download(base + "headunit.crt", "85b5043a09b1ba9464f745e6917bdbaa2bc582fe48cb727a7787c1a832a773e4")
        val key = download(base + "headunit.key", "9e837a172a1eef5b05cda9df9d086753639648a17f76c2c939bcbd094aa972f2")
        val store = PemIdentity.read(certificate, key.toString(Charsets.US_ASCII))
        val output = file.startWrite()
        try {
            store.store(output, charArrayOf())
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun download(url: String, digest: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode != 200) throw IOException("Certificate download failed (HTTP ${connection.responseCode})")
            val bytes = connection.inputStream.use { it.readNBytes(16_385) }
            if (bytes.size > 16_384) throw IOException("Certificate download is too large")
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (actual != digest) throw IOException("Certificate download checksum mismatch")
            return bytes
        } finally {
            connection.disconnect()
        }
    }
}
