package com.clipsync.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class ProtocolSessionTest {
    private val vectorPairing = Pairing(
        pid = Vectors.pairingPid,
        name = "Test Mac",
        psk = Vectors.psk,
        hosts = listOf("100.101.102.103"),
        port = Vectors.pairingPort,
    )

    /** Drives the client with the vector nonces: every frame it produces must match the vectors byte for byte. */
    @Test
    fun clientHandshakeAndTrafficMatchVectors() {
        val frames = Vectors.frames
        val client = ClientSession(vectorPairing, "Test Phone", ncOverride = Vectors.nc)

        val hello = Json.parseToJsonElement(client.start()).jsonObject
        assertEquals(1, hello["v"]!!.jsonPrimitive.int)
        assertEquals(Vectors.pairingPid, hello["pid"]!!.jsonPrimitive.content)
        assertArrayEquals(Vectors.nc, Base64Url.decode(hello["nc"]!!.jsonPrimitive.content))

        val auth = client.onText("""{"v":1,"ns":"${Base64Url.encode(Vectors.ns)}"}""")
        assertEquals(frames[0].bytes.toHex(), auth.toHex())

        assertEquals(Welcome("Test Mac"), client.onBinary(frames[1].bytes))
        assertTrue(client.isEstablished)
        assertEquals("Test Mac", client.macName)

        val clip = Messages.decode(frames[2].plaintext.toByteArray()) as Clip
        assertEquals(frames[2].bytes.toHex(), client.seal(clip).toHex())
        assertEquals(Ack(clip.id), client.onBinary(frames[3].bytes))
        assertEquals(true, (client.onBinary(frames[4].bytes) as Clip).sensitive)
        assertEquals(frames[5].bytes.toHex(), client.seal(Ping).toHex())
        assertEquals(Pong, client.onBinary(frames[6].bytes))
    }

    @Test
    fun fullHandshakeAgainstSimulatedServer() {
        val server = TestServer(vectorPairing.psk, "Server Mac")
        val client = ClientSession(vectorPairing, "Phone")
        val serverHello = server.onHello(client.start())
        val authFrame = client.onText(serverHello)
        val welcome = server.onAuth(authFrame)
        assertEquals("Phone", server.deviceName)
        assertEquals(Welcome("Server Mac"), client.onBinary(welcome))

        val toPhone = Clip("id-1", "from mac ✓", sensitive = false, ts = 1)
        assertEquals(toPhone, client.onBinary(server.seal(toPhone)))
        val toMac = Clip("id-2", "from phone", sensitive = true, ts = 2)
        assertEquals(toMac, server.open(client.seal(toMac)))
    }

    @Test
    fun serverRejectsClientWithWrongPsk() {
        val server = TestServer(ByteArray(32) { 7 }, "Mac")
        val client = ClientSession(vectorPairing, "Phone")
        val authFrame = client.onText(server.onHello(client.start()))
        expectProtocolError(Protocol.CLOSE_AUTH) { server.onAuth(authFrame) }
    }

    @Test
    fun clientRejectsWelcomeFromServerWithWrongPsk() {
        val impostor = TestServer(ByteArray(32) { 9 }, "Impostor")
        val client = ClientSession(vectorPairing, "Phone")
        client.onText(impostor.onHello(client.start()))
        val forgedWelcome = impostor.seal(Welcome("Impostor"))
        expectProtocolError(Protocol.CLOSE_AUTH) { client.onBinary(forgedWelcome) }
    }

    @Test
    fun rejectsVersionMismatchAndOutOfOrderFrames() {
        val c1 = ClientSession(vectorPairing, "Phone")
        c1.start()
        expectProtocolError(Protocol.CLOSE_VERSION) { c1.onText("""{"v":2,"ns":"${Base64Url.encode(ByteArray(16))}"}""") }

        // First encrypted frame from the server must be welcome.
        val server = TestServer(vectorPairing.psk, "Mac")
        val c2 = ClientSession(vectorPairing, "Phone")
        server.onAuth(c2.onText(server.onHello(c2.start())), sendWelcome = false)
        expectProtocolError(Protocol.CLOSE_AUTH) { c2.onBinary(server.seal(Ping)) }

        // Text frames after the handshake are violations.
        val server3 = TestServer(vectorPairing.psk, "Mac")
        val c3 = ClientSession(vectorPairing, "Phone")
        c3.onBinary(server3.onAuth(c3.onText(server3.onHello(c3.start()))))
        expectProtocolError(Protocol.CLOSE_AUTH) { c3.onText("""{"v":1,"ns":"x"}""") }

        // Binary before the server hello is a violation.
        val c4 = ClientSession(vectorPairing, "Phone")
        c4.start()
        expectProtocolError(Protocol.CLOSE_AUTH) { c4.onBinary(ByteArray(40)) }
    }

    private fun expectProtocolError(code: Int, block: () -> Unit) {
        try {
            block()
            fail("expected ProtocolException($code)")
        } catch (e: ProtocolException) {
            assertEquals(code, e.closeCode)
        }
    }
}

/** Minimal server side of PROTOCOL.md for tests (the real server is the Mac agent). */
class TestServer(private val psk: ByteArray, private val macName: String) {
    private val random = SecureRandom()
    private lateinit var sealer: FrameSealer
    private lateinit var opener: FrameOpener
    var deviceName: String? = null
        private set

    fun onHello(text: String): String {
        val hello = Json.parseToJsonElement(text).jsonObject
        require(hello["v"]!!.jsonPrimitive.int == 1)
        val nc = Base64Url.decode(hello["nc"]!!.jsonPrimitive.content)
        val ns = ByteArray(16).also(random::nextBytes)
        val keys = SessionKeys.derive(psk, nc, ns)
        sealer = FrameSealer(keys.s2c)
        opener = FrameOpener(keys.c2s)
        return """{"v":1,"ns":"${Base64Url.encode(ns)}"}"""
    }

    /** Verifies the auth frame and returns the welcome frame. */
    fun onAuth(frame: ByteArray, sendWelcome: Boolean = true): ByteArray {
        val auth = Messages.decode(opener.open(frame)) as? Auth
            ?: throw ProtocolException(Protocol.CLOSE_AUTH, "expected auth")
        deviceName = auth.device
        return if (sendWelcome) seal(Welcome(macName)) else ByteArray(0)
    }

    fun seal(message: Message): ByteArray = sealer.seal(Messages.encode(message))

    fun open(frame: ByteArray): Message? = Messages.decode(opener.open(frame))
}
