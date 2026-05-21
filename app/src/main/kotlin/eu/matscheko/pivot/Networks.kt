package eu.matscheko.pivot

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress

/**
 * Helpers for finding the device's underlying (non-VPN) network and its DNS
 * servers. Used so that, while our own capturing VPN is up, DNS resolution and
 * upstream egress can deliberately bypass the tun (otherwise they'd loop back
 * into it).
 */
object Networks {

    /** A non-VPN network with internet, preferring a validated one. */
    @Suppress("DEPRECATION") // allNetworks: deprecated in API 31 but still the simplest
                             // way to enumerate non-VPN networks; the replacement needs
                             // a stateful NetworkCallback. Revisit if minSdk rises.
    fun underlying(cm: ConnectivityManager?): Network? {
        cm ?: return null
        val candidates = cm.allNetworks.filter { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.firstOrNull { n ->
            cm.getNetworkCapabilities(n)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    /** The DNS servers configured for [network] (e.g. the Wi-Fi/cellular resolver). */
    fun dnsServers(cm: ConnectivityManager?, network: Network): List<InetAddress> =
        cm?.getLinkProperties(network)?.dnsServers ?: emptyList()
}
