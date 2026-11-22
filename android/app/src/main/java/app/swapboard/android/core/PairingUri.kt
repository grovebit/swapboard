package app.swapboard.android.core

import java.io.ByteArrayOutputStream

/** A stored pairing with one Mac (PROTOCOL.md §1). */
data class Pairing(
    val pid: String,
    val name: String,
    val psk: ByteArray,
    val hosts: List<String>,
    val port: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is Pairing && pid == other.pid && name == other.name && psk.contentEquals(other.psk) &&
            hosts == other.hosts && port == other.port

    override fun hashCode(): Int = pid.hashCode() * 31 + psk.contentHashCode()

    override fun toString(): String = "Pairing(pid=$pid, name=$name, hosts=$hosts, port=$port, psk=<redacted>)"
}

/**
 * Parses and builds `swapboard://pair?v=1&pid=…&name=…&k=…&h=…&p=…`.
 * Percent-decoding follows RFC 3986: `+` is a literal plus (the Mac encodes it as %2B).
 */
object PairingUri {
    private const val PREFIX = "swapboard://pair"
    private val pidPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun parse(uri: String): Pairing {
        val trimmed = uri.trim()
        require(trimmed.startsWith("$PREFIX?") || trimmed.startsWith("$PREFIX/?")) { "not a Swapboard pairing link" }
        val query = trimmed.substringAfter('?').substringBefore('#')
        val raw = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = percentDecode(part.substringBefore('='))
            val value = if ('=' in part) part.substringAfter('=') else ""
            raw.putIfAbsent(key, value) // first occurrence wins
        }

        require(raw["v"] == "1") { "unsupported pairing version ${raw["v"]}" }
        val pid = percentDecode(raw["pid"] ?: "")
        require(pidPattern.matches(pid)) { "invalid pairing id" }
        val name = percentDecode(raw["name"] ?: "").ifBlank { "Mac" }
        val psk = try {
            Base64Url.decode(percentDecode(raw["k"] ?: ""))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid key")
        }
        require(psk.size == Protocol.PSK_BYTES) { "key must be ${Protocol.PSK_BYTES} bytes" }
        val hosts = (raw["h"] ?: "").split(',').map { percentDecode(it).trim() }.filter { it.isNotEmpty() }
        require(hosts.isNotEmpty()) { "no hosts in pairing link" }
        val port = raw["p"]?.let { percentDecode(it).toIntOrNull() } ?: Protocol.DEFAULT_PORT
        require(port in 1..65535) { "invalid port" }
        return Pairing(pid = pid, name = name, psk = psk, hosts = hosts, port = port)
    }

    fun build(pairing: Pairing): String =
        "$PREFIX?v=1&pid=${encode(pairing.pid)}&name=${encode(pairing.name)}" +
            "&k=${Base64Url.encode(pairing.psk)}&h=${pairing.hosts.joinToString(",") { encode(it) }}" +
            "&p=${pairing.port}"

    /** Percent-decodes UTF-8; `+` stays a literal plus. Throws IllegalArgumentException on bad escapes. */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                require(i + 3 <= s.length) { "truncated percent escape" }
                val hex = s.substring(i + 1, i + 3)
                val b = hex.toIntOrNull(16) ?: throw IllegalArgumentException("bad percent escape %$hex")
                out.write(b)
                i += 3
            } else {
                val end = s.indexOf('%', i).let { if (it < 0) s.length else it }
                val bytes = s.substring(i, end).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i = end
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt() and 0xff
            val c = ch.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(ch))
            }
        }
        return sb.toString()
    }
}
