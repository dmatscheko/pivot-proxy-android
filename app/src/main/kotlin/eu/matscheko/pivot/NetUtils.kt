package eu.matscheko.pivot

import java.net.Inet4Address
import java.net.NetworkInterface

/** A selectable bind target for the UI dropdown. */
data class InterfaceOption(val label: String, val address: String)

const val ADDRESS_ALL = "0.0.0.0"
const val ADDRESS_LOOPBACK = "127.0.0.1"

object NetUtils {

    /** Bind choices: all interfaces, loopback, plus each up non-loopback IPv4. */
    fun bindOptions(): List<InterfaceOption> {
        val options = mutableListOf(
            InterfaceOption("All interfaces ($ADDRESS_ALL)", ADDRESS_ALL),
            InterfaceOption("Loopback only ($ADDRESS_LOOPBACK)", ADDRESS_LOOPBACK),
        )
        forEachIpv4 { name, address ->
            options += InterfaceOption("$name ($address)", address)
        }
        return options
    }

    /** Concrete address(es) to show as "bound to" for a given bind selection. */
    fun displayAddresses(bindAddress: String): List<String> = when (bindAddress) {
        ADDRESS_ALL -> localIpv4Addresses().ifEmpty { listOf(ADDRESS_ALL) }
        else -> listOf(bindAddress)
    }

    fun localIpv4Addresses(): List<String> {
        val addresses = mutableListOf<String>()
        forEachIpv4 { _, address -> addresses += address }
        return addresses
    }

    private inline fun forEachIpv4(action: (name: String, address: String) -> Unit) {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces() ?: return
        } catch (e: Exception) {
            return
        }
        for (nif in interfaces) {
            val up = try {
                nif.isUp && !nif.isLoopback
            } catch (e: Exception) {
                false
            }
            if (!up) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { action(nif.displayName ?: nif.name, it) }
                }
            }
        }
    }
}
