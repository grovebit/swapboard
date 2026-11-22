package com.clipsync.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Calls [onChange] when the default network appears, disappears or changes transport
 * (e.g. Wi-Fi ↔ mobile, or the Tailscale VPN coming up), so the connection can retry at once.
 */
class NetworkMonitor(context: Context, private val onChange: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var lastTransports: Int = -1
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()

        override fun onLost(network: Network) = onChange()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val transports = listOf(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_VPN,
            ).fold(0) { acc, t -> if (caps.hasTransport(t)) acc or (1 shl t) else acc }
            if (transports != lastTransports) {
                lastTransports = transports
                onChange()
            }
        }
    }

    fun start() {
        if (registered) return
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }.onSuccess { registered = true }
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
    }
}
