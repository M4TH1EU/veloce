package ch.broillet.veloce.protocol

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.TrustManagerFactory

internal object TestIdentity {
    val password = "test-password".toCharArray()
    val store: KeyStore by lazy {
        val directory = Files.createTempDirectory("veloce-tls-test")
        try {
            val file = directory.resolve("identity.p12")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Veloce test only",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", String(password), "-keypass", String(password), "-noprompt").redirectErrorStream(true).start()
            check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "Test key generation timed out" }
            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
            KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, password) } }
        } finally { directory.toFile().deleteRecursively() }
    }

    fun client(): SSLEngine {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLSv1.2").apply { init(null, trust.trustManagers, null) }.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
            beginHandshake()
        }
    }

    fun handshake(client: SSLEngine, send: (ByteArray) -> Unit, receive: () -> ByteArray) {
        var pending = ByteArray(0)
        var turns = 0
        while (client.handshakeStatus != HandshakeStatus.NOT_HANDSHAKING) {
            check(++turns < 128) { "Test TLS handshake stalled" }
            when (client.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> while (true) (client.delegatedTask ?: break).run()
                HandshakeStatus.NEED_WRAP -> {
                    val output = ByteBuffer.allocate(65536)
                    check(client.wrap(ByteBuffer.allocate(0), output).status == Status.OK)
                    send(output.array().copyOf(output.position()))
                }
                HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    if (pending.isEmpty()) pending = receive()
                    val source = ByteBuffer.wrap(pending)
                    val result = client.unwrap(source, ByteBuffer.allocate(65536))
                    pending = ByteArray(source.remaining()).also(source::get)
                    if (result.status == Status.BUFFER_UNDERFLOW) pending += receive()
                    else check(result.status == Status.OK) { "Client TLS status ${result.status}" }
                }
                else -> error("Unexpected client TLS state")
            }
        }
    }

    fun wrap(client: SSLEngine, bytes: ByteArray): ByteArray {
        val output = ByteBuffer.allocate(65536)
        val source = ByteBuffer.wrap(bytes)
        check(client.wrap(source, output).status == Status.OK && !source.hasRemaining())
        return output.array().copyOf(output.position())
    }

    fun unwrap(client: SSLEngine, bytes: ByteArray): ByteArray {
        val output = ByteBuffer.allocate(65536)
        val source = ByteBuffer.wrap(bytes)
        while (source.hasRemaining()) check(client.unwrap(source, output).status == Status.OK)
        return output.array().copyOf(output.position())
    }
}
