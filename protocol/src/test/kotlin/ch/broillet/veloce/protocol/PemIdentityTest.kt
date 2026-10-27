package ch.broillet.veloce.protocol

import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PemIdentityTest {
    @Test fun `PKCS8 and PKCS1 RSA identities both match the certificate`() {
        val certificate = TestIdentity.store.getCertificate("test").encoded
        val key = TestIdentity.store.getKey("test", TestIdentity.password) as RSAPrivateCrtKey
        val pkcs8 = "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(key.encoded)}\n-----END PRIVATE KEY-----"
        val fromPkcs8 = PemIdentity.read(certificate, pkcs8)
        assertArrayEquals(certificate, fromPkcs8.getCertificate("projection").encoded)

        val integers = listOf(java.math.BigInteger.ZERO, key.modulus, key.publicExponent, key.privateExponent,
            key.primeP, key.primeQ, key.primeExponentP, key.primeExponentQ, key.crtCoefficient)
        fun tagged(tag: Int, data: ByteArray): ByteArray {
            val size = data.size
            val length = when {
                size < 128 -> byteArrayOf(size.toByte())
                size < 256 -> byteArrayOf(0x81.toByte(), size.toByte())
                else -> byteArrayOf(0x82.toByte(), (size ushr 8).toByte(), size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + data
        }
        val sequence = tagged(0x30, integers.fold(ByteArray(0)) { bytes, value -> bytes + tagged(2, value.toByteArray()) })
        val pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(sequence)}\n-----END RSA PRIVATE KEY-----"
        val fromPkcs1 = PemIdentity.read(certificate, pkcs1)
        assertArrayEquals(key.encoded, fromPkcs1.getKey("projection", charArrayOf()).encoded)
    }
}
