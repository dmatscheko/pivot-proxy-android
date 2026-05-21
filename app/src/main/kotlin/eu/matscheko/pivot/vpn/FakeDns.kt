package eu.matscheko.pivot.vpn

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal "fake-IP" DNS resolver (port of socksdroid's util/FakeDns.java).
 *
 * Every A query is answered with a synthetic address from 198.18.0.0/15 (RFC 2544
 * benchmarking range) and the fakeIP <-> hostname mapping is remembered. The actual
 * name resolution then happens on the proxy side when [LocalShim] issues a hostname
 * CONNECT for that fake IP. AAAA / other types are answered with NODATA so clients
 * fall back to the fake IPv4 address.
 *
 * The userspace stack ([eu.matscheko.pivot.vpn.stack.TunStack]) intercepts UDP/53
 * directly and calls [handle] as a plain function, writing the response straight back
 * into the tun — so there is no DNS gateway socket and no `--dnsgw` redirect.
 */
class FakeDns {

    private val hostToIp = ConcurrentHashMap<String, Int>()
    private val ipToHost = ConcurrentHashMap<Int, String>()
    private var next = 1 // skip the network address (.0)

    /** Reverse lookup used by [LocalShim] to recover the hostname for a fake IP. */
    fun hostnameForIp(ip: String): String? = try {
        ipToHost[ipToInt(InetAddress.getByName(ip).address)]
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun allocate(host: String): Int {
        hostToIp[host]?.let { return it }
        val ip = POOL_BASE + (next % POOL_SIZE)
        next++
        ipToHost.put(ip, host)?.let { evicted -> hostToIp.remove(evicted) }
        hostToIp[host] = ip
        return ip
    }

    internal fun handle(data: ByteArray, len: Int): ByteArray? {
        if (len < 12) return null
        val qdcount = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        if (qdcount < 1) return null

        // Parse the (single) question name + type.
        var i = 12
        val name = StringBuilder()
        while (i < len && data[i].toInt() != 0) {
            val l = data[i].toInt() and 0xff
            if ((l and 0xc0) != 0) return null // compression not expected in a question
            i++
            if (i + l > len) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(data, i, l, Charsets.US_ASCII))
            i += l
        }
        i++ // skip the terminating zero
        if (i + 4 > len) return null
        val qtype = ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
        val questionEnd = i + 4

        val isA = qtype == 1
        val out = ByteArrayOutputStream()
        out.write(data[0].toInt())
        out.write(data[1].toInt())                // transaction id
        out.write(0x80 or (data[2].toInt() and 0x01)) // QR=1, copy RD
        out.write(0x80)                           // RA=1, RCODE=0
        out.write(0)
        out.write(1)                              // QDCOUNT
        out.write(0)
        out.write(if (isA) 1 else 0)              // ANCOUNT
        out.write(0)
        out.write(0)                              // NSCOUNT
        out.write(0)
        out.write(0)                              // ARCOUNT
        out.write(data, 12, questionEnd - 12)     // echo question

        if (isA) {
            val ip = allocate(name.toString())
            out.write(0xc0)
            out.write(0x0c)            // name pointer to question
            out.write(0)
            out.write(1)               // type A
            out.write(0)
            out.write(1)               // class IN
            out.write((TTL ushr 24) and 0xff)
            out.write((TTL ushr 16) and 0xff)
            out.write((TTL ushr 8) and 0xff)
            out.write(TTL and 0xff)
            out.write(0)
            out.write(4)               // RDLENGTH
            out.write((ip ushr 24) and 0xff)
            out.write((ip ushr 16) and 0xff)
            out.write((ip ushr 8) and 0xff)
            out.write(ip and 0xff)
        }
        return out.toByteArray()
    }

    companion object {
        private const val POOL_BASE = (198 shl 24) or (18 shl 16) // 198.18.0.0
        private const val POOL_SIZE = 1 shl 17                    // /15
        private const val TTL = 1                                 // seconds

        private fun ipToInt(b: ByteArray): Int =
            ((b[0].toInt() and 0xff) shl 24) or
                ((b[1].toInt() and 0xff) shl 16) or
                ((b[2].toInt() and 0xff) shl 8) or
                (b[3].toInt() and 0xff)
    }
}
