package app.swapboard.android.net

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import app.swapboard.android.core.TailnetPolicy
import java.net.InetAddress

/** The Tailscale Android app: whether it's installed and connected, and shortcuts into it. */
object TailscaleApp {
    const val PACKAGE = "com.tailscale.ipn"

    /** Tailscale's own DNS address; only a Tailscale VPN routes it. */
    private val magicDns: InetAddress = InetAddress.getByAddress(byteArrayOf(100, 100, 100, 100))

    fun isInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /** True while a VPN network carries a Tailscale address (100.64.0.0/10). */
    @Suppress("DEPRECATION") // allNetworks is the simplest way to see VPN networks alongside Wi-Fi
    fun isConnected(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            val link = connectivity.getLinkProperties(network) ?: return@any false
            link.linkAddresses.any { TailnetPolicy.isTailscaleAddress(it.address.hostAddress.orEmpty()) } ||
                link.routes.any { it.matches(magicDns) }
        }
    }

    /** Whether Android lets Tailscale run in the background without battery limits. */
    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(PACKAGE) == true

    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            openStore(context)
        }
    }

    fun openStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$PACKAGE".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$PACKAGE".toUri())
            runCatching { context.startActivity(web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Tailscale's App info page, where Battery → Unrestricted lives. */
    fun openAppInfo(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$PACKAGE".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
