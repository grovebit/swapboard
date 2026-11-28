package app.swapboard.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encrypted messages (PROTOCOL.md §5). The JSON discriminator field is `type`. */
@Serializable
sealed interface Message

@Serializable
@SerialName("auth")
data class Auth(val device: String) : Message

@Serializable
@SerialName("welcome")
data class Welcome(val mac: String) : Message

@Serializable
@SerialName("clip")
data class Clip(
    val id: String,
    val text: String,
    val sensitive: Boolean = false,
    val ts: Long,
) : Message

@Serializable
@SerialName("ack")
data class Ack(val id: String) : Message

@Serializable
@SerialName("ping")
data object Ping : Message

@Serializable
@SerialName("pong")
data object Pong : Message

/** Client → server: the client stops sending `ping` until its next `ping` (PROTOCOL.md §6, quiet mode). */
@Serializable
@SerialName("quiet")
data object Quiet : Message

object Messages {
    private val knownTypes = setOf("auth", "welcome", "clip", "ack", "ping", "pong", "quiet")

    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(message: Message): ByteArray =
        json.encodeToString(Message.serializer(), message).toByteArray(Charsets.UTF_8)

    /**
     * Decodes one message. Returns null for an unknown `type` (which the protocol says to ignore).
     * Malformed JSON or a known type with missing fields is a protocol violation (4001).
     */
    fun decode(bytes: ByteArray): Message? {
        val text = bytes.toString(Charsets.UTF_8)
        val obj: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "malformed message JSON")
        }
        val type = try {
            obj["type"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        } ?: throw ProtocolException(Protocol.CLOSE_AUTH, "message without type")
        if (type !in knownTypes) return null
        return try {
            json.decodeFromJsonElement(Message.serializer(), obj)
        } catch (e: SerializationException) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "invalid $type message")
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(Protocol.CLOSE_AUTH, "invalid $type message")
        }
    }
}
