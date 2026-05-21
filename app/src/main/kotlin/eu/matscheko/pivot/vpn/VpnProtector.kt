package eu.matscheko.pivot.vpn

import android.net.VpnService
import java.net.DatagramSocket
import java.net.Socket

/**
 * Process-global bridge so the egress proxy (a separate service) can ask the
 * running [VpnService] to `protect()` its sockets — i.e. route them around our
 * own capturing tun. When no VPN is active the calls are no-ops that report
 * success, so the egress proxy behaves like a plain SOCKS5 server.
 *
 * [PivotVpnService] registers itself here while running and clears it on stop.
 */
object VpnProtector {
    @Volatile
    var service: VpnService? = null

    /** Bypass the tun for [socket]. Returns true if there is nothing to do. */
    fun protect(socket: Socket): Boolean {
        val s = service ?: return true
        return s.protect(socket)
    }

    fun protect(socket: DatagramSocket): Boolean {
        val s = service ?: return true
        return s.protect(socket)
    }
}
