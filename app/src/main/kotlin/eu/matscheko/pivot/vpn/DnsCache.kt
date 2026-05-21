package eu.matscheko.pivot.vpn

import android.util.Log
import java.net.InetAddress
import java.util.Collections

/**
 * Reverse cache of `resolved-IP -> queried-hostname`, built by inspecting the DNS
 * answers forwarded in direct-DNS mode (the stack hands each raw answer to [record]).
 *
 * In fake-IP mode [LocalShim] already recovers the hostname from the synthetic IP
 * (via [FakeDns]); in direct mode the app resolves names itself and connects by real
 * IP, so the name is otherwise lost by the time a flow reaches the proxy. This cache
 * lets the bypass list (and per-flow decisions) match on the hostname in *both*
 * modes.
 *
 * Caveat: several names can resolve to the same IP (CDNs, shared hosting); the last
 * writer wins, so on a shared address the recovered name is best-effort.
 */
class DnsCache(maxEntries: Int = 8192) {

    private val ipToHost: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
                size > maxEntries
        },
    )

    /** Look up the hostname last seen for [ip], or null. Accepts any IP literal form. */
    fun hostnameForIp(ip: String): String? = try {
        val key = InetAddress.getByName(ip).hostAddress ?: return null
        ipToHost[key]
    } catch (e: Exception) {
        null
    }

    /** Parse a raw DNS answer and remember each A/AAAA address -> question name. */
    fun record(answer: ByteArray) {
        try {
            parse(answer)
        } catch (e: Exception) {
            // Malformed/uninteresting answer — ignore.
        }
    }

    private fun parse(d: ByteArray) {
        val len = d.size
        if (len < 12) return
        val qd = u16(d, 4)
        val an = u16(d, 6)
        if (qd < 1 || an < 1) return

        // Question: read the name (no compression in the question section).
        var i = 12
        val name = StringBuilder()
        while (i < len && d[i].toInt() != 0) {
            val l = d[i].toInt() and 0xff
            if ((l and 0xc0) != 0) return
            i++
            if (i + l > len) return
            if (name.isNotEmpty()) name.append('.')
            name.append(String(d, i, l, Charsets.US_ASCII))
            i += l
        }
        i++ // terminating zero
        i += 4 // QTYPE + QCLASS
        if (name.isEmpty()) return
        val host = name.toString().lowercase()

        // Answer records.
        var rr = 0
        while (rr < an && i < len) {
            i = skipName(d, i, len)
            if (i + 10 > len) return
            val type = u16(d, i)
            val rdlen = u16(d, i + 8)
            i += 10
            if (i + rdlen > len) return
            if (type == 1 && rdlen == 4) {
                put(InetAddress.getByAddress(d.copyOfRange(i, i + 4)).hostAddress, host)
            } else if (type == 28 && rdlen == 16) {
                put(InetAddress.getByAddress(d.copyOfRange(i, i + 16)).hostAddress, host)
            }
            i += rdlen
            rr++
        }
    }

    private fun put(ip: String?, host: String) {
        ip ?: return
        ipToHost[ip] = host
    }

    /** Advance past an RR NAME, which may be labels, a 2-byte pointer, or both. */
    private fun skipName(d: ByteArray, start: Int, len: Int): Int {
        var i = start
        while (i < len) {
            val b = d[i].toInt() and 0xff
            when {
                b == 0 -> return i + 1
                (b and 0xc0) == 0xc0 -> return i + 2 // compression pointer ends the name
                else -> i += 1 + b
            }
        }
        return i
    }

    private fun u16(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0xff) shl 8) or (d[off + 1].toInt() and 0xff)

    companion object {
        private val TAG = DnsCache::class.java.simpleName
    }
}
