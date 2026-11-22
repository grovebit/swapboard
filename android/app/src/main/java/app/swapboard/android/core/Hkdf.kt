package app.swapboard.android.core

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with HMAC-SHA256 (RFC 5869). */
object Hkdf {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "invalid HKDF length $length" }
        val out = ByteArrayOutputStream(length + HASH_LEN)
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/** Per-direction session keys derived from the PSK and both handshake nonces. */
class SessionKeys(val c2s: ByteArray, val s2c: ByteArray) {
    companion object {
        fun derive(psk: ByteArray, nc: ByteArray, ns: ByteArray): SessionKeys {
            require(psk.size == Protocol.PSK_BYTES) { "PSK must be ${Protocol.PSK_BYTES} bytes" }
            require(nc.size == Protocol.NONCE_BYTES && ns.size == Protocol.NONCE_BYTES) {
                "nonces must be ${Protocol.NONCE_BYTES} bytes"
            }
            val salt = nc + ns
            return SessionKeys(
                c2s = Hkdf.derive(psk, salt, Protocol.INFO_C2S.toByteArray(Charsets.US_ASCII), 32),
                s2c = Hkdf.derive(psk, salt, Protocol.INFO_S2C.toByteArray(Charsets.US_ASCII), 32),
            )
        }
    }
}
