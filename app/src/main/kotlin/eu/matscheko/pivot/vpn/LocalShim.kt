package eu.matscheko.pivot.vpn

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local SOCKS5 server that the userspace [eu.matscheko.pivot.vpn.stack.TunStack]
 * connects to (port of socksdroid's util/LocalSocksProxy.java). For each CONNECT it
 * receives (always an IP, because the stack works at the IP layer) it checks whether
 * the destination is a fake IP handed out by [FakeDns]. If so, it recovers the original
 * hostname and forwards the connection to the real upstream proxy (e.g. Burp). Real IPs
 * pass through unchanged.
 *
 * The upstream connection is made with a hostname CONNECT — either a SOCKS5
 * `CONNECT` (ATYP=3) or an HTTP `CONNECT` request, depending on [httpUpstream] — so
 * the proxy performs DNS resolution. The stack→shim hop is plain SOCKS5, so this
 * shim is the bridge that lets the upstream proxy be an HTTP/S proxy like Burp.
 *
 * This is the "fake-IP" trick that gives a TUN VPN DNS-over-SOCKS5. When [fakeDns]
 * is null (direct-DNS mode with an HTTP upstream) the shim simply forwards the real
 * destination IP to the upstream over HTTP CONNECT.
 */
class LocalShim(
    private val listenPort: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val username: String?,
    private val password: String?,
    private val fakeDns: FakeDns?,
    /** When true, talk HTTP CONNECT to the upstream; otherwise SOCKS5. */
    private val httpUpstream: Boolean,
    /** Lets the upstream socket bypass the VPN. Returns true when nothing to do. */
    private val protect: (Socket) -> Boolean,
    /** Hosts (lowercase) that skip the proxy and connect straight to the internet. */
    private val bypassDomains: List<String> = emptyList(),
    /** Resolves a host on the underlying (non-VPN) network for bypassed connections. */
    private val resolve: (String) -> InetAddress = { InetAddress.getByName(it) },
    /** Recovers a hostname for a real destination IP (direct-DNS mode bypass match). */
    private val bypassLookup: (String) -> String? = { null },
) {
    private var server: ServerSocket? = null
    @Volatile
    private var running = false

    @Throws(IOException::class)
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
        server = s
        running = true
        Thread({ acceptLoop() }, "local-socks").start()
    }

    fun stop() {
        running = false
        closeQuietly(server)
    }

    private fun acceptLoop() {
        val s = server ?: return
        while (running) {
            val client = try {
                s.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            Thread({ handle(client) }, "local-socks-conn").start()
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // SOCKS5 server side: greet the stack's client (it offers no-auth).
            val greeting = readN(cin, 2)
            if (greeting[0].toInt() != 0x05) {
                throw IOException("unexpected version: ${greeting[0].toInt() and 0xff}")
            }
            readN(cin, greeting[1].toInt() and 0xff) // method list, ignored
            cout.write(byteArrayOf(0x05, 0x00))
            cout.flush()

            // Read the CONNECT request.
            val head = readN(cin, 4)
            val cmd = head[1].toInt() and 0xff
            val atyp = head[3].toInt() and 0xff
            var destHost: String
            var destIsName = false
            when (atyp) {
                0x01 -> destHost = ipString(readN(cin, 4))
                0x04 -> destHost = ipString(readN(cin, 16))
                0x03 -> {
                    destHost = String(readN(cin, readN(cin, 1)[0].toInt() and 0xff), Charsets.UTF_8)
                    destIsName = true
                }
                else -> throw IOException("bad ATYP: $atyp")
            }
            val portBytes = readN(cin, 2)
            val destPort = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

            if (cmd != 0x01) {
                reply(cout, 0x07) // command not supported
                closeQuietly(client)
                return
            }

            // Promote a fake IP to its hostname for the UPSTREAM connect (fake-IP mode
            // only; in direct mode we keep the real IP so resolution stays on-device).
            var targetHost = destHost
            var targetIsName = destIsName
            if (!destIsName) {
                fakeDns?.hostnameForIp(destHost)?.let {
                    targetHost = it
                    targetIsName = true
                }
            }

            // Hostname to test against the bypass list. In direct mode the flow arrives
            // as a real IP, so recover the name from the DNS reverse cache.
            val matchHost = if (targetIsName) targetHost else bypassLookup(destHost)
            val bypass = matchHost != null && isBypassed(matchHost)

            val up = Socket()
            upstream = up
            // Allocate the underlying fd (a fresh Socket has none yet) so that
            // protect() can mark it, then keep the connection off the VPN to avoid a
            // routing loop back into the tun.
            up.bind(null)
            if (!protect(up)) Log.w(TAG, "failed to protect upstream socket")

            val rep = if (bypass) {
                // Excluded host: go straight to the internet, skipping the proxy.
                // Fake-IP mode resolves the recovered name; direct mode already has
                // the real IP — resolve() handles both (IP literals pass through).
                connectDirect(up, if (targetIsName) targetHost else destHost, destPort)
            } else {
                up.connect(InetSocketAddress(upstreamHost, upstreamPort), CONNECT_TIMEOUT_MS)
                up.tcpNoDelay = true
                if (httpUpstream) {
                    httpConnect(up, targetHost, destPort)
                } else {
                    upstreamConnect(up, targetHost, targetIsName, destPort)
                }
            }
            reply(cout, rep)
            if (rep != 0x00) {
                closeQuietly(client)
                closeQuietly(up)
                return
            }

            val pump = Thread({ copyOneWay(client, up) }, "local-socks-up")
            pump.start()
            copyOneWay(up, client)
            try {
                pump.join()
            } catch (ignored: InterruptedException) {
            }
            closeQuietly(client)
            closeQuietly(up)
        } catch (e: IOException) {
            Log.w(TAG, "connection failed: ${e.message}")
            closeQuietly(client)
            closeQuietly(upstream)
        }
    }

    @Throws(IOException::class)
    private fun upstreamConnect(upstream: Socket, host: String, isName: Boolean, port: Int): Int {
        val ins = upstream.getInputStream()
        val out = upstream.getOutputStream()
        val auth = !username.isNullOrEmpty()

        out.write(if (auth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greeting = readN(ins, 2)
        if (greeting[0].toInt() != 0x05) {
            throw IOException("upstream version: ${greeting[0].toInt() and 0xff}")
        }
        val method = greeting[1].toInt() and 0xff
        if (method == 0x02) {
            if (!auth) throw IOException("upstream requires authentication")
            doUserPassAuth(ins, out)
        } else if (method != 0x00) {
            throw IOException("upstream: no acceptable auth method ($method)")
        }

        val req = ByteArrayOutputStream()
        req.write(0x05)
        req.write(0x01)
        req.write(0x00)
        if (isName) {
            val h = host.toByteArray(Charsets.UTF_8)
            req.write(0x03)
            req.write(h.size)
            req.write(h)
        } else {
            val ip = InetAddress.getByName(host).address
            req.write(if (ip.size == 4) 0x01 else 0x04)
            req.write(ip)
        }
        req.write((port ushr 8) and 0xff)
        req.write(port and 0xff)
        out.write(req.toByteArray())
        out.flush()

        val head = readN(ins, 4)
        val rep = head[1].toInt() and 0xff
        val atyp = head[3].toInt() and 0xff
        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> readN(ins, 1)[0].toInt() and 0xff
            else -> throw IOException("upstream reply ATYP: $atyp")
        }
        readN(ins, addrLen + 2)
        return rep
    }

    /** True if [host] is on the bypass list (exact match or a subdomain of an entry). */
    private fun isBypassed(host: String): Boolean {
        if (bypassDomains.isEmpty()) return false
        val h = host.lowercase()
        return bypassDomains.any { d -> h == d || h.endsWith(".$d") }
    }

    /**
     * Connect straight to the destination, skipping the upstream proxy, for hosts on
     * the bypass list. The name is resolved on the underlying (non-VPN) network and
     * the socket is already `protect()`-ed, so the traffic leaves the phone directly
     * (the app talks to the real server, with no Burp interception).
     */
    private fun connectDirect(upstream: Socket, host: String, port: Int): Int {
        return try {
            val addr = resolve(host)
            upstream.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
            upstream.tcpNoDelay = true
            Log.i(TAG, "bypass: $host:$port direct via ${addr.hostAddress}")
            0x00
        } catch (e: Exception) {
            Log.w(TAG, "bypass connect failed for $host:$port: ${e.message}")
            0x04 // host unreachable
        }
    }

    /**
     * Issue an HTTP `CONNECT host:port` to the upstream proxy (e.g. Burp, which only
     * accepts HTTP/S proxy connections) and translate the result into a SOCKS5 reply
     * code for the stack. The hostname is carried verbatim so the proxy resolves it,
     * preserving DNS-over-proxy.
     */
    @Throws(IOException::class)
    private fun httpConnect(upstream: Socket, host: String, port: Int): Int {
        val out = upstream.getOutputStream()
        val ins = upstream.getInputStream()
        // IPv6 literals must be bracketed in the request-target.
        val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"

        val req = StringBuilder()
        req.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
        req.append("Host: ").append(authority).append("\r\n")
        if (!username.isNullOrEmpty()) {
            val creds = "$username:${password ?: ""}"
            val token = Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            req.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
        }
        req.append("Proxy-Connection: keep-alive\r\n")
        req.append("\r\n")
        out.write(req.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val statusLine = readHttpHead(ins)
        // "HTTP/1.1 200 Connection established" → 2xx means the tunnel is open.
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        if (code in 200..299) return 0x00
        Log.w(TAG, "HTTP CONNECT refused: $statusLine")
        return 0x05 // general SOCKS server failure
    }

    @Throws(IOException::class)
    private fun doUserPassAuth(ins: InputStream, out: OutputStream) {
        val user = (username ?: "").toByteArray(Charsets.UTF_8)
        val pass = (password ?: "").toByteArray(Charsets.UTF_8)
        val req = ByteArrayOutputStream()
        req.write(0x01)
        req.write(user.size)
        req.write(user)
        req.write(pass.size)
        req.write(pass)
        out.write(req.toByteArray())
        out.flush()
        if (readN(ins, 2)[1].toInt() != 0x00) throw IOException("upstream authentication failed")
    }

    /**
     * Pump one direction. On a clean EOF, half-close the destination's write side
     * (`shutdownOutput`) so the peer sees end-of-stream but the other direction can
     * keep flowing — e.g. a client that finishes its request and waits for the
     * response. On a real error, fully close both so the other pump can't hang. The
     * caller closes both sockets once both pumps have returned.
     */
    private fun copyOneWay(from: Socket, to: Socket) {
        try {
            val ins = from.getInputStream()
            val out = to.getOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val len = ins.read(buf)
                if (len < 0) break
                if (len > 0) {
                    out.write(buf, 0, len)
                    out.flush()
                }
            }
            runCatching { to.shutdownOutput() }
        } catch (e: IOException) {
            closeQuietly(from)
            closeQuietly(to)
        }
    }

    companion object {
        private val TAG = LocalShim::class.java.simpleName
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_HTTP_HEAD = 16_384

        @Throws(IOException::class)
        private fun reply(out: OutputStream, rep: Int) {
            // VER, REP, RSV, ATYP=IPv4, BND.ADDR 0.0.0.0, BND.PORT 0
            out.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
        }

        @Throws(IOException::class)
        private fun ipString(addr: ByteArray): String = InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"

        /**
         * Read an HTTP response head byte-by-byte up to and including the blank line
         * (CRLFCRLF) so we never consume tunnel bytes, and return the status line.
         */
        @Throws(IOException::class)
        private fun readHttpHead(ins: InputStream): String {
            val buf = ByteArrayOutputStream()
            var match = 0 // progress through \r\n\r\n
            while (true) {
                val b = ins.read()
                if (b < 0) throw IOException("upstream closed during HTTP CONNECT")
                buf.write(b)
                match = when {
                    b == 0x0d && (match == 0 || match == 2) -> match + 1 // \r
                    b == 0x0a && match == 1 -> 2                          // \n
                    b == 0x0a && match == 3 -> 4                          // \n -> done
                    else -> 0
                }
                if (match == 4) break
                if (buf.size() > MAX_HTTP_HEAD) throw IOException("HTTP response head too large")
            }
            return buf.toString("ISO-8859-1").substringBefore("\r\n")
        }

        @Throws(IOException::class)
        private fun readN(ins: InputStream, n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = ins.read(b, off, n - off)
                if (r < 0) throw IOException("unexpected end of stream")
                off += r
            }
            return b
        }

        private fun closeQuietly(c: java.io.Closeable?) {
            try {
                c?.close()
            } catch (ignored: IOException) {
            }
        }
    }
}
