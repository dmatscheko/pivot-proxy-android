package eu.matscheko.pivot.vpn.stack

import android.util.Log
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom

/**
 * A userspace TCP endpoint for one captured flow. This is the part that replaces
 * `libtun2socks.so` (badvpn / lwIP): the tun delivers raw IP packets, so to expose a
 * byte stream to a SOCKS5 proxy we terminate TCP on the device side.
 *
 * The state machine is intentionally minimal — enough for the **CONNECT-only**, no
 * inbound/BIND use the app needs (matching both source projects' non-goals). The link
 * to the app is the lossless tun, so we ACK promptly, drop out-of-order/duplicate
 * segments (the peer retransmits), do simple windowed segmentation outbound, and keep
 * a low-rate retransmit timer purely as a safety net.
 *
 * Per flow there is one SOCKS5 connection to the loopback [eu.matscheko.pivot.vpn.LocalShim]
 * (opened via [connectSocks], no auth), and two pumps: tun→socks and socks→tun.
 */
internal class TcpFlow(
    /** Our side of the flow (the address/port the app connected to). Becomes the source of replies. */
    private val localAddr: ByteArray,
    private val localPort: Int,
    /** The app's side. */
    private val remoteAddr: ByteArray,
    private val remotePort: Int,
    private val mss: Int,
    private val connectSocks: (destAddr: ByteArray, destPort: Int) -> Socket,
    private val writePacket: (ByteArray) -> Unit,
    private val executor: ExecutorService,
    private val onClosed: () -> Unit,
) {
    private val lock = Object()

    // Our send sequence space.
    private var iss = 0L
    private var sndNxt = 0L
    private var sndUna = 0L
    // Next byte we expect from the app.
    private var rcvNxt = 0L
    // The app's advertised receive window (bytes).
    private var peerWnd = 0

    private var socks: Socket? = null
    private var socksConnected = false
    private var finSent = false
    private var finReceived = false
    private var finAcked = false
    private var closed = false
    private var lastActivity = now()
    private var lastAdvertised = RCV_WINDOW

    // App→socks payload buffered until the writer drains it (bounded by RCV_WINDOW).
    private val inQueue = LinkedBlockingQueue<ByteArray>()
    private var inBuffered = 0

    // Outbound segments awaiting ACK (for the safety-net retransmit timer).
    private val rexmit = ArrayList<Segment>()

    private class Segment(
        val seq: Long,
        val flags: Int,
        val data: ByteArray?,
        var sentAt: Long,
        var retries: Int,
    ) {
        val seqLen: Int = (data?.size ?: 0) + (if ((flags and (TcpFlag.SYN or TcpFlag.FIN)) != 0) 1 else 0)
    }

    /** Called on the initial SYN: choose ISS, reply SYN-ACK, connect to the proxy. */
    fun onSyn(clientSeq: Long, clientWindow: Int) {
        synchronized(lock) {
            iss = ThreadLocalRandom.current().nextLong(0, 0x1_0000_0000L)
            sndNxt = iss
            sndUna = iss
            rcvNxt = add(clientSeq, 1)
            peerWnd = clientWindow.coerceAtLeast(1)
            // SYN-ACK with our MSS advertised.
            sendSegment(TcpFlag.SYN or TcpFlag.ACK, null, 0, 0, mss)
        }
        executor.execute { connectTask() }
    }

    private fun connectTask() {
        val s = try {
            connectSocks(localAddr, localPort)
        } catch (e: Exception) {
            Log.d(TAG, "socks connect failed: ${e.message}")
            closeFlow(sendRst = true)
            return
        }
        var ok = false
        synchronized(lock) {
            if (!closed) {
                socks = s
                socksConnected = true
                ok = true
            }
        }
        if (!ok) {
            runCatching { s.close() }
            return
        }
        executor.execute { pumpFromSocks(s) }
        pumpToSocks(s)
    }

    /** Handle one inbound TCP segment for this flow. Must not block (tun reader thread). */
    fun onSegment(tcp: TcpHeader, buf: ByteArray) {
        synchronized(lock) {
            if (closed) return
            lastActivity = now()

            if (tcp.has(TcpFlag.RST)) {
                closeFlowLocked(sendRst = false)
                return
            }

            // Retransmitted SYN before the handshake completed → resend SYN-ACK.
            if (tcp.has(TcpFlag.SYN) && sub(sndNxt, iss) <= 1) {
                resendSynAck()
                return
            }

            if (tcp.has(TcpFlag.ACK)) processAck(tcp.ack)
            peerWnd = tcp.window.coerceAtLeast(0)
            (lock as Object).notifyAll()

            var consumed = false

            // In-order data only; otherwise drop and let the peer retransmit.
            if (tcp.payloadLength > 0 && tcp.seq == rcvNxt) {
                if (inBuffered + tcp.payloadLength <= RCV_WINDOW) {
                    val chunk = buf.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
                    inQueue.put(chunk)
                    inBuffered += tcp.payloadLength
                    rcvNxt = add(rcvNxt, tcp.payloadLength)
                    consumed = true
                }
            } else if (tcp.payloadLength > 0 && seqLt(tcp.seq, rcvNxt)) {
                consumed = true // duplicate; re-ACK below
            }

            // FIN consumes the sequence number right after this segment's data.
            val finSeq = add(tcp.seq, tcp.payloadLength)
            if (tcp.has(TcpFlag.FIN) && !finReceived && finSeq == rcvNxt) {
                rcvNxt = add(rcvNxt, 1)
                finReceived = true
                inQueue.put(EOF) // tell the writer to half-close the socks socket
                consumed = true
            }

            if (consumed) sendSegment(TcpFlag.ACK, null, 0, 0, null)

            maybeFullCloseLocked()
        }
    }

    /** Safety-net retransmit + idle reaping; called periodically by [TunStack]. */
    fun onTick() {
        synchronized(lock) {
            if (closed) return
            val t = now()
            if (t - lastActivity > IDLE_TIMEOUT_MS) {
                closeFlowLocked(sendRst = true)
                return
            }
            for (seg in rexmit) {
                if (t - seg.sentAt > RTO_MS) {
                    if (seg.retries >= MAX_RETRIES) {
                        closeFlowLocked(sendRst = true)
                        return
                    }
                    seg.retries++
                    seg.sentAt = t
                    writePacket(
                        Packets.buildTcp(
                            localAddr, remoteAddr, localPort, remotePort,
                            seg.seq, rcvNxt, seg.flags, advertisedWindow(),
                            seg.data, 0, seg.data?.size ?: 0,
                            if ((seg.flags and TcpFlag.SYN) != 0) mss else null,
                        ),
                    )
                }
            }
        }
    }

    fun close() = closeFlow(sendRst = true)

    // ---- send paths --------------------------------------------------------

    /** Build and emit a segment, recording it for retransmit. Caller holds [lock]. */
    private fun sendSegment(flags: Int, data: ByteArray?, off: Int, len: Int, mssOpt: Int?) {
        val payload = if (data != null && len > 0) data.copyOfRange(off, off + len) else null
        val pkt = Packets.buildTcp(
            localAddr, remoteAddr, localPort, remotePort,
            sndNxt, rcvNxt, flags, advertisedWindow(),
            payload, 0, payload?.size ?: 0, mssOpt,
        )
        lastAdvertised = advertisedWindow()
        writePacket(pkt)
        val carriesSeq = payload != null || (flags and (TcpFlag.SYN or TcpFlag.FIN)) != 0
        if (carriesSeq) {
            rexmit.add(Segment(sndNxt, flags, payload, now(), 0))
            sndNxt = add(sndNxt, (payload?.size ?: 0) + (if ((flags and (TcpFlag.SYN or TcpFlag.FIN)) != 0) 1 else 0))
        }
    }

    private fun resendSynAck() {
        // The original SYN-ACK is the first rexmit entry; just re-emit it.
        writePacket(
            Packets.buildTcp(
                localAddr, remoteAddr, localPort, remotePort,
                iss, rcvNxt, TcpFlag.SYN or TcpFlag.ACK, advertisedWindow(), null, 0, 0, mss,
            ),
        )
    }

    private fun processAck(ack: Long) {
        // Advance only for an ACK within (sndUna, sndNxt].
        if (seqLt(sndUna, ack) && !seqLt(sndNxt, ack)) {
            sndUna = ack
            rexmit.removeAll { seg -> !seqLt(sndUna, add(seg.seq, seg.seqLen.toLong())) }
        } else if (ack == sndUna) {
            // duplicate ACK; nothing to advance
        }
        if (finSent && ack == sndNxt) finAcked = true
    }

    /** Outbound data from socks → segmented to the app, respecting MSS + peer window. */
    private fun sendData(data: ByteArray, length: Int) {
        var p = 0
        while (p < length) {
            synchronized(lock) {
                while (!closed) {
                    val inFlight = sub(sndNxt, sndUna)
                    val avail = peerWnd - inFlight.toInt()
                    if (avail > 0) {
                        val seg = minOf(mss, length - p, avail)
                        sendSegment(TcpFlag.ACK or TcpFlag.PSH, data, p, seg, null)
                        p += seg
                        return@synchronized
                    }
                    try {
                        (lock as Object).wait(ZERO_WINDOW_PROBE_MS)
                    } catch (e: InterruptedException) {
                        return@synchronized
                    }
                }
            }
            if (closed) return
        }
    }

    private fun sendFin() {
        synchronized(lock) {
            if (closed || finSent) return
            sendSegment(TcpFlag.FIN or TcpFlag.ACK, null, 0, 0, null)
            finSent = true
            maybeFullCloseLocked()
        }
    }

    // ---- pumps -------------------------------------------------------------

    private fun pumpToSocks(s: Socket) {
        try {
            val out = s.getOutputStream()
            while (true) {
                val chunk = inQueue.take()
                if (chunk === EOF) {
                    runCatching { s.shutdownOutput() }
                    break
                }
                if (closed) break
                out.write(chunk)
                out.flush()
                synchronized(lock) {
                    inBuffered -= chunk.size
                    if (advertisedWindow() >= mss && lastAdvertised < mss && !closed) {
                        sendSegment(TcpFlag.ACK, null, 0, 0, null) // window update
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) closeFlow(sendRst = true)
        }
    }

    private fun pumpFromSocks(s: Socket) {
        val buf = ByteArray(mss)
        try {
            val ins = s.getInputStream()
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                sendData(buf, n)
            }
            sendFin()
        } catch (e: Exception) {
            if (!closed) closeFlow(sendRst = true)
        }
    }

    // ---- teardown ----------------------------------------------------------

    private fun maybeFullCloseLocked() {
        if (finSent && finAcked && finReceived) closeFlowLocked(sendRst = false)
    }

    private fun closeFlow(sendRst: Boolean) {
        synchronized(lock) { closeFlowLocked(sendRst) }
    }

    private fun closeFlowLocked(sendRst: Boolean) {
        if (closed) return
        closed = true
        if (sendRst) {
            runCatching {
                writePacket(
                    Packets.buildTcp(
                        localAddr, remoteAddr, localPort, remotePort,
                        sndNxt, rcvNxt, TcpFlag.RST or TcpFlag.ACK, 0, null, 0, 0, null,
                    ),
                )
            }
        }
        runCatching { inQueue.put(EOF) } // unblock the writer
        runCatching { socks?.close() }
        (lock as Object).notifyAll()
        onClosed()
    }

    private fun advertisedWindow(): Int = (RCV_WINDOW - inBuffered).coerceIn(0, 0xffff)

    companion object {
        private const val TAG = "TcpFlow"
        private const val RCV_WINDOW = 65535
        private const val RTO_MS = 1_000L
        private const val ZERO_WINDOW_PROBE_MS = 200L
        private const val IDLE_TIMEOUT_MS = 300_000L
        private const val MAX_RETRIES = 10
        private val EOF = ByteArray(0)

        private fun now() = System.currentTimeMillis()
        private fun add(a: Long, n: Int): Long = (a + n) and 0xffff_ffffL
        private fun add(a: Long, n: Long): Long = (a + n) and 0xffff_ffffL
        private fun sub(a: Long, b: Long): Long = (a - b) and 0xffff_ffffL

        /** True if [a] is strictly before [b] in 32-bit modular sequence space. */
        private fun seqLt(a: Long, b: Long): Boolean = ((a - b) and 0xffff_ffffL) >= 0x8000_0000L
    }
}
