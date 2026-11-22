package com.clipsync.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Finds the VPN network that routes to a Tailscale address. Sockets from [socketFactoryFor] are
 * bound to that network, so traffic can only travel through Tailscale. If Tailscale is off, there
 * is no such network and ClipSync doesn't connect at all instead of falling back to Wi-Fi.
 */
class TailscaleNetwork(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun socketFactoryFor(host: String): SocketFactory? = networkFor(host)?.socketFactory

    @Suppress("DEPRECATION") // allNetworks is the simplest way to see VPN networks alongside Wi-Fi
    private fun networkFor(host: String): Network? {
        val target = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null // IP literal: no DNS
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                connectivity.getLinkProperties(network)?.routes?.any { it.matches(target) } == true
        }
    }
}
