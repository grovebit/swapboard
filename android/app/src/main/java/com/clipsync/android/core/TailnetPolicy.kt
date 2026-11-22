package com.clipsync.android.core

/**
 * ClipSync only talks to the Mac over Tailscale. Pairing links may list other addresses (older
 * Macs added LAN IPs and `.local` names); those are ignored, never tried.
 */
object TailnetPolicy {
    /** True for an IPv4 literal inside Tailscale's address range 100.64.0.0/10. */
    fun isTailscaleAddress(host: String): Boolean = canonical(host) != null

    /** The Tailscale addresses from [hosts], in their original order, in canonical dotted form. */
    fun allowedHosts(hosts: List<String>): List<String> = hosts.mapNotNull(::canonical).distinct()

    private fun canonical(host: String): String? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
        if (octets[0] != 100 || octets[1] !in 64..127) return null
        return octets.joinToString(".")
    }
}
