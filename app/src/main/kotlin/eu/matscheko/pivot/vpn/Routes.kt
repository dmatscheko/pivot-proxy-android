package eu.matscheko.pivot.vpn

import android.net.VpnService

/**
 * Installs the tun routing table (port of socksdroid's util/Routes.java).
 *
 * Phase 1 supports the "all" route only (capture everything). The 127.0.0.0/8
 * loopback block is never routed into the tun. The fake-IP and DNS-stub routes
 * are added separately in [PivotVpnService.configure].
 */
object Routes {
    fun addRoutes(builder: VpnService.Builder, mode: String) {
        val routes = when (mode) {
            else -> arrayOf("0.0.0.0/0")
        }
        for (r in routes) {
            val cidr = r.split("/")
            if (cidr.size == 2 && !cidr[0].startsWith("127")) {
                builder.addRoute(cidr[0], cidr[1].toInt())
            }
        }
    }
}
