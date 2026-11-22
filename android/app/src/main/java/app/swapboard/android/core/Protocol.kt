package app.swapboard.android.core

import java.util.Base64

/** Constants from protocol/PROTOCOL.md (v1). Plain Kotlin: no Android imports in core/. */
object Protocol {
    const val VERSION = 1
    const val AAD = "swapboard-v1"
    const val INFO_C2S = "swapboard v1 c2s"
    const val INFO_S2C = "swapboard v1 s2c"
    const val DEFAULT_PORT = 48613

    const val PSK_BYTES = 32
    const val NONCE_BYTES = 16
    const val COUNTER_BYTES = 8
    const val TAG_BYTES = 16

    const val MAX_PLAINTEXT = 8 * 1024 * 1024
    const val MAX_FRAME = MAX_PLAINTEXT + COUNTER_BYTES + TAG_BYTES
    const val MAX_TEXT_BYTES = 1024 * 1024

    const val CLOSE_NORMAL = 1000
    const val CLOSE_AUTH = 4001
    const val CLOSE_VERSION = 4002
    const val CLOSE_TOO_LARGE = 4003
    const val CLOSE_REPLACED = 4004

    const val PENDING_TTL_MS = 5 * 60 * 1000L
    const val DEDUPE_IDS = 50
    const val PING_INTERVAL_MS = 30_000L
    const val DEAD_AFTER_MS = 75_000L
}

/** A protocol violation; [closeCode] is the WebSocket close code to send. */
class ProtocolException(val closeCode: Int, message: String) : Exception(message)

/** base64url without padding, as used for every binary field of the protocol. */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Decodes base64url (padding optional). Throws IllegalArgumentException on bad input. */
    fun decode(text: String): ByteArray = decoder.decode(text.trimEnd('='))
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
