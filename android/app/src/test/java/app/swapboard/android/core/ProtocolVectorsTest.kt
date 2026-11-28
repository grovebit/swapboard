package app.swapboard.android.core

import app.swapboard.android.core.Vectors.str
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolVectorsTest {

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val v = Vectors.obj("hkdf_rfc5869_case1")
        val okm = Hkdf.derive(
            ikm = v.str("ikm").hexToBytes(),
            salt = v.str("salt").hexToBytes(),
            info = v.str("info").hexToBytes(),
            length = v["length"]!!.jsonPrimitive.int,
        )
        assertEquals(v.str("okm"), okm.toHex())
        // The vector file must agree with the RFC itself, not just with Node.
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun sessionKeysMatchVectors() {
        val keys = SessionKeys.derive(Vectors.psk, Vectors.nc, Vectors.ns)
        assertEquals(Vectors.session.str("salt_hex"), (Vectors.nc + Vectors.ns).toHex())
        assertEquals(Vectors.session.str("k_c2s_hex"), keys.c2s.toHex())
        assertEquals(Vectors.session.str("k_s2c_hex"), keys.s2c.toHex())
        assertEquals(Protocol.AAD, Vectors.session.str("aad"))
    }

    @Test
    fun sealProducesExactFrameBytesInBothDirections() {
        val frames = Vectors.frames
        assertTrue(frames.any { it.direction == "c2s" } && frames.any { it.direction == "s2c" })
        for (f in frames) {
            val sealed = FrameCipher.seal(Vectors.key(f.direction), f.counter, f.plaintext.toByteArray(Charsets.UTF_8))
            assertEquals("frame ${f.direction}#${f.counter}", f.bytes.toHex(), sealed.toHex())
        }
    }

    @Test
    fun openDecryptsEveryFrame() {
        for (f in Vectors.frames) {
            val plain = FrameCipher.open(Vectors.key(f.direction), f.counter, f.bytes)
            assertArrayEquals(f.plaintext.toByteArray(Charsets.UTF_8), plain)
        }
    }

    @Test
    fun sequentialOpenersAcceptFramesInOrder() {
        for (direction in listOf("c2s", "s2c")) {
            val opener = FrameOpener(Vectors.key(direction))
            val sealer = FrameSealer(Vectors.key(direction))
            for (f in Vectors.frames.filter { it.direction == direction }.sortedBy { it.counter }) {
                assertArrayEquals(f.plaintext.toByteArray(Charsets.UTF_8), opener.open(f.bytes))
                assertArrayEquals(f.bytes, sealer.seal(f.plaintext.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    @Test
    fun invalidFramesAreRejected() {
        val invalid = Vectors.invalidFrames
        assertTrue(invalid.isNotEmpty())
        for (v in invalid) {
            try {
                FrameCipher.open(Vectors.key(v.direction), v.expectedCounter, v.bytes)
                fail("accepted invalid frame: ${v.reason}")
            } catch (e: ProtocolException) {
                assertEquals("close code for ${v.reason}", Protocol.CLOSE_AUTH, e.closeCode)
            }
        }
    }

    @Test
    fun oversizedFrameIsRejectedWith4003() {
        try {
            FrameCipher.open(Vectors.kC2S, 0, ByteArray(Protocol.MAX_FRAME + 1))
            fail("accepted oversized frame")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.CLOSE_TOO_LARGE, e.closeCode)
        }
    }

    @Test
    fun vectorPlaintextsDecodeToMessages() {
        val decoded = Vectors.frames.map { Messages.decode(it.plaintext.toByteArray(Charsets.UTF_8)) }
        assertEquals(Auth("Test Phone"), decoded[0])
        assertEquals(Welcome("Test Mac"), decoded[1])
        val clip = decoded[2] as Clip
        assertEquals("6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b", clip.id)
        assertEquals("Hello from Android 👋\nLine 2 — ünïcödé", clip.text)
        assertEquals(false, clip.sensitive)
        assertEquals(1763800000000L, clip.ts)
        assertEquals(Ack("6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b"), decoded[3])
        val secret = decoded[4] as Clip
        assertEquals("secret from Mac", secret.text)
        assertEquals(true, secret.sensitive)
        assertEquals(Ping, decoded[5])
        assertEquals(Pong, decoded[6])
        assertEquals(Quiet, decoded[7])
    }

    @Test
    fun encoderReproducesVectorPlaintextsExactly() {
        for (f in Vectors.frames) {
            val message = Messages.decode(f.plaintext.toByteArray(Charsets.UTF_8))!!
            assertEquals(f.plaintext, Messages.encode(message).toString(Charsets.UTF_8))
        }
    }

    @Test
    fun unknownTypesAndFieldsAreIgnoredButGarbageIsRejected() {
        assertNull(Messages.decode("""{"type":"future","x":1}""".toByteArray()))
        assertEquals(Ack("a"), Messages.decode("""{"type":"ack","id":"a","extra":true}""".toByteArray()))
        for (bad in listOf("not json", """{"id":"x"}""", """{"type":"clip","id":"x"}""", "[]")) {
            try {
                Messages.decode(bad.toByteArray())
                fail("accepted $bad")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.CLOSE_AUTH, e.closeCode)
            }
        }
    }
}
