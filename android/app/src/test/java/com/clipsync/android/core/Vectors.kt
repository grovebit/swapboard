package com.clipsync.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Loads protocol/vectors.json (path passed by Gradle as the `clipsync.vectors` system property). */
object Vectors {
    val root: JsonObject by lazy {
        val path = System.getProperty("clipsync.vectors") ?: error("system property clipsync.vectors not set")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    fun obj(name: String): JsonObject = root[name]!!.jsonObject
    fun array(name: String): JsonArray = root[name]!!.jsonArray

    val session get() = obj("session")
    val psk: ByteArray get() = Base64Url.decode(session.str("psk"))
    val nc: ByteArray get() = Base64Url.decode(session.str("nc"))
    val ns: ByteArray get() = Base64Url.decode(session.str("ns"))
    val kC2S: ByteArray get() = session.str("k_c2s_hex").hexToBytes()
    val kS2C: ByteArray get() = session.str("k_s2c_hex").hexToBytes()

    fun key(direction: String): ByteArray = when (direction) {
        "c2s" -> kC2S
        "s2c" -> kS2C
        else -> error("bad direction $direction")
    }

    data class Frame(val direction: String, val counter: Long, val plaintext: String, val bytes: ByteArray)

    val frames: List<Frame>
        get() = array("frames").map {
            val o = it.jsonObject
            Frame(o.str("direction"), o["counter"]!!.jsonPrimitive.long, o.str("plaintext"), o.str("frame_hex").hexToBytes())
        }

    data class Invalid(val reason: String, val direction: String, val expectedCounter: Long, val bytes: ByteArray)

    val invalidFrames: List<Invalid>
        get() = array("invalid_frames").map {
            val o = it.jsonObject
            Invalid(
                o.str("reason"),
                o.str("direction"),
                o["expected_counter"]!!.jsonPrimitive.long,
                o.str("frame_hex").hexToBytes(),
            )
        }

    val pairingPid: String get() = obj("pairing_uri").str("pid")
    val pairingPort: Int get() = obj("pairing_uri")["port"]!!.jsonPrimitive.int

    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
}
