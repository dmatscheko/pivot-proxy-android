package eu.matscheko.pivot.vpn.stack

import android.os.ParcelFileDescriptor
import android.util.Log
import eu.matscheko.pivot.socks.Socks5Client
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** The pure-Kotlin tun↔SOCKS bridge that replaces `libtun2socks.so`. */
internal interface TunStack {
    fun start()
    fun stop()
}

/**
 * Userspace TCP/IP stack: reads raw IP packets off the tun, terminates TCP locally
 * and bridges each flow to the loopback SOCKS5 shim, intercepts UDP/53 and answers it
 * with [dnsHandler], and drops everything else (matching the bundled tun2socks, which
 * had no udpgw configured). Handles both IPv4 and IPv6.
 *
 * Replaces the entire native child-process + fd-hand-off + `--dnsgw` machinery: we own
 * the packet loop, so DNS is a direct function call and no gateway redirect is needed.
 */
internal class KotlinTunStack(
    private val pfd: ParcelFileDescriptor,
    private val mtu: Int,
    private val socksHost: String,
    private val socksPort: Int,
    /** Answers a raw UDP/53 query payload, or returns null to drop it. */
    private val dnsHandler: (ByteArray) -> ByteArray?,
    /**
     * When true, refuse TCP/853 (DNS-over-TLS) so an opportunistic resolver falls
     * back to plaintext UDP/53 — which [dnsHandler] (fake-IP) intercepts. Used only in
     * fake-IP mode, and only when Private DNS is not in strict ("hostname") mode.
     */
    private val rejectDnsOverTls: Boolean = false,
) : TunStack {

    @Volatile
    private var running = false

    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)
    private val writeLock = Any()

    private val flows = ConcurrentHashMap<String, TcpFlow>()
    private val workers = Executors.newCachedThreadPool(daemon("tun-stack-worker"))
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("tun-stack-tick"))
    private lateinit var reader: Thread

    override fun start() {
        running = true
        ticker.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        reader = Thread({ readLoop() }, "tun-stack-reader").apply { start() }
    }

    override fun stop() {
        running = false
        flows.values.forEach { runCatching { it.close() } }
        flows.clear()
        ticker.shutdownNow()
        workers.shutdownNow()
        runCatching { pfd.close() } // unblocks the blocked read()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun readLoop() {
        val buf = ByteArray(maxOf(mtu, 65535))
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "tun read: ${e.message}")
                break
            }
            if (n <= 0) {
                if (n < 0) break else continue
            }
            try {
                dispatch(buf, n)
            } catch (e: Exception) {
                Log.w(TAG, "dispatch: ${e.message}")
            }
        }
    }

    private fun dispatch(buf: ByteArray, len: Int) {
        val ip = Packets.parseIp(buf, len) ?: return
        if (ip.fragmented) return // we don't reassemble; rare for TCP/DNS with DF set
        when (ip.protocol) {
            IpProto.TCP -> handleTcp(ip, buf)
            IpProto.UDP -> handleUdp(ip, buf)
            else -> {} // ICMP and others: dropped (same as the native bridge)
        }
    }

    private fun handleTcp(ip: IpHeader, buf: ByteArray) {
        val tcp = Packets.parseTcp(buf, ip.l4Offset, ip.l4Length) ?: return
        // Force DNS-over-TLS to fall back to plaintext UDP/53 (handled by FakeDns).
        if (rejectDnsOverTls && tcp.dstPort == 853 && tcp.has(TcpFlag.SYN) && !tcp.has(TcpFlag.ACK)) {
            sendRst(ip, tcp)
            return
        }
        val key = flowKey(ip.src, tcp.srcPort, ip.dst, tcp.dstPort)
        val existing = flows[key]
        if (existing != null) {
            existing.onSegment(tcp, buf)
            return
        }
        if (!tcp.has(TcpFlag.SYN) || tcp.has(TcpFlag.ACK)) {
            // No flow and not a fresh SYN → tell the peer to give up.
            if (!tcp.has(TcpFlag.RST)) sendRst(ip, tcp)
            return
        }
        val mss = mtu - if (ip.version == 6) 60 else 40
        val flow = TcpFlow(
            localAddr = ip.dst,
            localPort = tcp.dstPort,
            remoteAddr = ip.src,
            remotePort = tcp.srcPort,
            mss = mss,
            connectSocks = { destAddr, destPort ->
                Socks5Client.connect(socksHost, socksPort, destAddr, destPort)
            },
            writePacket = ::writePacket,
            executor = workers,
            onClosed = { flows.remove(key) },
        )
        if (flows.putIfAbsent(key, flow) == null) {
            flow.onSyn(tcp.seq, tcp.window)
        } else {
            flows[key]?.onSegment(tcp, buf)
        }
    }

    private fun handleUdp(ip: IpHeader, buf: ByteArray) {
        val udp = Packets.parseUdp(buf, ip.l4Offset, ip.l4Length) ?: return
        if (udp.dstPort != 53) return // only DNS; non-DNS UDP is dropped
        val query = buf.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        workers.execute {
            val answer = try {
                dnsHandler(query)
            } catch (e: Exception) {
                Log.w(TAG, "dns handler: ${e.message}")
                null
            } ?: return@execute
            // Reply: from the queried server back to the app.
            val pkt = Packets.buildUdp(ip.dst, ip.src, udp.dstPort, udp.srcPort, answer)
            writePacket(pkt)
        }
    }

    private fun sendRst(ip: IpHeader, tcp: TcpHeader) {
        // RST in response to a stray segment: ack the peer's data so it stops.
        val seq = if (tcp.has(TcpFlag.ACK)) tcp.ack else 0L
        val ackNum = (tcp.seq + tcp.payloadLength + (if (tcp.has(TcpFlag.SYN) || tcp.has(TcpFlag.FIN)) 1 else 0)) and 0xffff_ffffL
        val pkt = Packets.buildTcp(
            ip.dst, ip.src, tcp.dstPort, tcp.srcPort,
            seq, ackNum, TcpFlag.RST or TcpFlag.ACK, 0, null, 0, 0, null,
        )
        writePacket(pkt)
    }

    private fun writePacket(pkt: ByteArray) {
        synchronized(writeLock) {
            try {
                output.write(pkt)
                output.flush()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "tun write: ${e.message}")
            }
        }
    }

    private fun tick() {
        for (flow in flows.values) {
            try {
                flow.onTick()
            } catch (e: Exception) {
                Log.w(TAG, "tick: ${e.message}")
            }
        }
    }

    private fun flowKey(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): String =
        "${hex(src)}:$srcPort>${hex(dst)}:$dstPort"

    companion object {
        private const val TAG = "TunStack"
        private const val TICK_MS = 500L

        private fun hex(b: ByteArray): String = buildString(b.size * 2) {
            for (x in b) append(((x.toInt() and 0xff) + 0x100).toString(16).substring(1))
        }

        private fun daemon(name: String) = ThreadFactory { r ->
            Thread(r, name).apply { isDaemon = true }
        }
    }
}
