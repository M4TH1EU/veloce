package ch.broillet.veloce.protocol

import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object PemIdentity {
    fun read(certificate: ByteArray, key: String): KeyStore {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream())
        val decoded = Base64.getMimeDecoder().decode(key.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""))
        val pkcs8 = if (key.contains("BEGIN RSA PRIVATE KEY")) {
            val rsaAlgorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(),
                0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
            der(0x30, byteArrayOf(2, 1, 0) + rsaAlgorithm + der(4, decoded))
        } else decoded
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        val challenge = "Veloce identity verification".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(challenge) }.sign()
        check(Signature.getInstance("SHA256withRSA").apply { initVerify(cert); update(challenge) }.verify(signature)) {
            "The certificate and private key do not match"
        }
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("projection", privateKey, charArrayOf(), arrayOf(cert))
        }
    }

    private fun der(tag: Int, body: ByteArray): ByteArray {
        val length = if (body.size < 128) byteArrayOf(body.size.toByte()) else {
            val size = if (body.size <= 255) 1 else 2
            byteArrayOf((0x80 or size).toByte()) + ByteArray(size) { (body.size ushr ((size - it - 1) * 8)).toByte() }
        }
        return byteArrayOf(tag.toByte()) + length + body
    }
}
