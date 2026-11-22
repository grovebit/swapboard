package app.swapboard.android.core

import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted frame format (PROTOCOL.md §4):
 * `ctr (8 bytes BE) ‖ AES-256-GCM ciphertext ‖ tag (16 bytes)`,
 * nonce = `00000000 ‖ ctr`, AAD = "swapboard-v1".
 */
object FrameCipher {
    private val aad = Protocol.AAD.toByteArray(Charsets.US_ASCII)

    fun nonce(counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    fun seal(key: ByteArray, counter: Long, plaintext: ByteArray): ByteArray {
        if (plaintext.size > Protocol.MAX_PLAINTEXT) {
            throw ProtocolException(Protocol.CLOSE_TOO_LARGE, "message too large")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(counter)))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext) // ciphertext ‖ tag
        return ByteBuffer.allocate(Protocol.COUNTER_BYTES + sealed.size).putLong(counter).put(sealed).array()
    }

    /** Opens [frame], which must carry exactly [expectedCounter]. Throws [ProtocolException] otherwise. */
    fun open(key: ByteArray, expectedCounter: Long, frame: ByteArray): ByteArray {
        if (frame.size > Protocol.MAX_FRAME) {
            throw ProtocolException(Protocol.CLOSE_TOO_LARGE, "frame too large")
        }
        if (frame.size < Protocol.COUNTER_BYTES + Protocol.TAG_BYTES) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "frame too short")
        }
        val counter = ByteBuffer.wrap(frame, 0, Protocol.COUNTER_BYTES).long
        if (counter != expectedCounter) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "bad counter $counter, expected $expectedCounter")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(counter)))
            cipher.updateAAD(aad)
            cipher.doFinal(frame, Protocol.COUNTER_BYTES, frame.size - Protocol.COUNTER_BYTES)
        } catch (e: AEADBadTagException) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "authentication failed")
        } catch (e: java.security.GeneralSecurityException) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "decrypt failed: ${e.javaClass.simpleName}")
        }
    }
}

/** Seals frames for one direction, numbering them 0, 1, 2, … */
class FrameSealer(private val key: ByteArray) {
    var nextCounter: Long = 0
        private set

    fun seal(plaintext: ByteArray): ByteArray = FrameCipher.seal(key, nextCounter, plaintext).also { nextCounter++ }
}

/** Opens frames for one direction, requiring counters 0, 1, 2, … exactly. */
class FrameOpener(private val key: ByteArray) {
    var expectedCounter: Long = 0
        private set

    fun open(frame: ByteArray): ByteArray = FrameCipher.open(key, expectedCounter, frame).also { expectedCounter++ }
}
