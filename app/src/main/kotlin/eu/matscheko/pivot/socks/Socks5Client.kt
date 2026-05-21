package eu.matscheko.pivot.socks

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal client half of the SOCKS5 handshake, used by the userspace TUN stack to
 * open each terminated TCP flow against the local [eu.matscheko.pivot.vpn.LocalShim]
 * (loopback, no auth). It mirrors exactly what the old `libtun2socks.so` did: greet
 * with the no-auth method, then `CONNECT` to the flow's destination **IP** (a fake
 * IP in fake-IP mode, or a real one otherwise) — the shim owns the fake-IP→hostname
 * mapping and the real upstream protocol.
 *
 * Pure `java.net`, so it is JVM-unit-testable with in-memory streams.
 */
object Socks5Client {

    /**
     * Connect to the SOCKS5 proxy at [proxyHost]:[proxyPort] and issue a no-auth
     * `CONNECT` to [destAddr] (4- or 16-byte IP) : [destPort]. Returns the live
     * socket on success; throws [IOException] (and closes the socket) on any failure.
     */
    fun connect(
        proxyHost: String,
        proxyPort: Int,
        destAddr: ByteArray,
        destPort: Int,
        connectTimeoutMs: Int = 10_000,
    ): Socket {
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()

            // Greeting: VER=5, 1 method, NO_AUTH.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = readN(ins, 2)
            if (greeting[0].toInt() != 0x05 || (greeting[1].toInt() and 0xff) != 0x00) {
                throw IOException("proxy refused no-auth (${greeting[1].toInt() and 0xff})")
            }

            // CONNECT request with an IPv4 (ATYP=1) or IPv6 (ATYP=4) address.
            val atyp = if (destAddr.size == 16) 0x04 else 0x01
            val req = ByteArray(4 + destAddr.size + 2)
            req[0] = 0x05
            req[1] = 0x01 // CONNECT
            req[2] = 0x00 // RSV
            req[3] = atyp.toByte()
            System.arraycopy(destAddr, 0, req, 4, destAddr.size)
            req[4 + destAddr.size] = ((destPort ushr 8) and 0xff).toByte()
            req[5 + destAddr.size] = (destPort and 0xff).toByte()
            out.write(req)
            out.flush()

            // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT.
            val head = readN(ins, 4)
            val rep = head[1].toInt() and 0xff
            if (rep != 0x00) throw IOException("proxy CONNECT failed (rep=$rep)")
            val addrLen = when (head[3].toInt() and 0xff) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> readN(ins, 1)[0].toInt() and 0xff
                else -> throw IOException("bad reply ATYP")
            }
            readN(ins, addrLen + 2) // consume BND.ADDR + BND.PORT
            return sock
        } catch (e: Throwable) {
            try {
                sock.close()
            } catch (ignored: IOException) {
            }
            throw if (e is IOException) e else IOException(e)
        }
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(b, off, n - off)
            if (r < 0) throw IOException("proxy closed during handshake")
            off += r
        }
        return b
    }
}
