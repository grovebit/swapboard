package com.clipsync.android.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/**
 * Client side of the handshake (PROTOCOL.md §3) as a socket-free state machine.
 *
 * Usage: send [start] as a text frame; feed the server's text frame to [onText] and send the
 * returned binary frame (encrypted `auth`); feed binary frames to [onBinary]. The first binary
 * frame must be `welcome`, after which [isEstablished] is true and [seal] may be used.
 */
class ClientSession(
    private val pairing: Pairing,
    private val deviceName: String,
    random: SecureRandom = SecureRandom(),
    ncOverride: ByteArray? = null,
) {
    enum class State { NEW, HELLO_SENT, AUTH_SENT, ESTABLISHED }

    var state: State = State.NEW
        private set

    /** Name the Mac reported in `welcome`. */
    var macName: String? = null
        private set

    val isEstablished: Boolean get() = state == State.ESTABLISHED

    private val nc: ByteArray = ncOverride?.copyOf() ?: ByteArray(Protocol.NONCE_BYTES).also(random::nextBytes)
    private var sealer: FrameSealer? = null
    private var opener: FrameOpener? = null

    init {
        require(nc.size == Protocol.NONCE_BYTES) { "nc must be ${Protocol.NONCE_BYTES} bytes" }
    }

    /** Client hello: `{"v":1,"pid":"…","nc":"…"}` (a WebSocket text frame). */
    fun start(): String {
        check(state == State.NEW) { "session already started" }
        state = State.HELLO_SENT
        val hello = JsonObject(
            mapOf(
                "v" to JsonPrimitive(Protocol.VERSION),
                "pid" to JsonPrimitive(pairing.pid),
                "nc" to JsonPrimitive(Base64Url.encode(nc)),
            ),
        )
        return hello.toString()
    }

    /** Handles the server hello text frame and returns the encrypted `auth` frame to send. */
    fun onText(text: String): ByteArray {
        if (state != State.HELLO_SENT) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "unexpected text frame in state $state")
        }
        val obj = try {
            Messages.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "malformed server hello")
        }
        val version = obj["v"]?.jsonPrimitive?.intOrNull
        if (version != Protocol.VERSION) {
            throw ProtocolException(Protocol.CLOSE_VERSION, "unsupported protocol version $version")
        }
        val ns = try {
            Base64Url.decode(obj["ns"]?.jsonPrimitive?.content ?: "")
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "bad server nonce")
        }
        if (ns.size != Protocol.NONCE_BYTES) throw ProtocolException(Protocol.CLOSE_AUTH, "bad server nonce length")

        val keys = SessionKeys.derive(pairing.psk, nc, ns)
        val s = FrameSealer(keys.c2s)
        sealer = s
        opener = FrameOpener(keys.s2c)
        state = State.AUTH_SENT
        return s.seal(Messages.encode(Auth(deviceName)))
    }

    /**
     * Handles a binary frame. Returns the decoded message, or null for an unknown message type.
     * Throws [ProtocolException] on any violation; the caller should close with its code.
     */
    fun onBinary(frame: ByteArray): Message? {
        val o = opener
        return when (state) {
            State.AUTH_SENT -> {
                val msg = Messages.decode(o!!.open(frame))
                if (msg !is Welcome) throw ProtocolException(Protocol.CLOSE_AUTH, "expected welcome")
                macName = msg.mac
                state = State.ESTABLISHED
                msg
            }
            State.ESTABLISHED -> Messages.decode(o!!.open(frame))
            else -> throw ProtocolException(Protocol.CLOSE_AUTH, "unexpected binary frame in state $state")
        }
    }

    /** Encrypts a message for the server. Only valid once established. */
    fun seal(message: Message): ByteArray {
        check(state == State.ESTABLISHED) { "session not established" }
        return sealer!!.seal(Messages.encode(message))
    }
}
