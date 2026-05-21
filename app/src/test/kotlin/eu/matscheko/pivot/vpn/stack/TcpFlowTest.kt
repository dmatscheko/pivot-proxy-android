package eu.matscheko.pivot.vpn.stack

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TcpFlowTest {

    private val local = byteArrayOf(10, 0, 0, 2)   // our side (app's destination)
    private val remote = byteArrayOf(10, 0, 0, 1)  // the app
    private val localPort = 80
    private val remotePort = 12345
    private val mss = 1460

    private val captured = LinkedBlockingQueue<ByteArray>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun parse(pkt: ByteArray): TcpHeader {
        val ip = Packets.parseIp(pkt, pkt.size)!!
        return Packets.parseTcp(pkt, ip.l4Offset, ip.l4Length)!!
    }

    private fun nextTcp(): Pair<TcpHeader, ByteArray> {
        val pkt = captured.poll(3, TimeUnit.SECONDS) ?: error("no packet emitted")
        return parse(pkt) to pkt
    }

    private fun feed(
        flow: TcpFlow,
        flags: Int,
        seq: Long,
        ack: Long,
        payload: ByteArray? = null,
        window: Int = 65535,
    ) {
        val buf = Packets.buildTcp(remote, local, remotePort, localPort, seq, ack, flags, window, payload)
        val ip = Packets.parseIp(buf, buf.size)!!
        flow.onSegment(Packets.parseTcp(buf, ip.l4Offset, ip.l4Length)!!, buf)
    }

    private fun newFlow(serverPort: Int, closed: CountDownLatch): TcpFlow = TcpFlow(
        localAddr = local, localPort = localPort,
        remoteAddr = remote, remotePort = remotePort, mss = mss,
        connectSocks = { _, _ -> Socket("127.0.0.1", serverPort) },
        writePacket = { captured.put(it) },
        executor = executor,
        onClosed = { closed.countDown() },
    )

    /** Run the SYN/SYN-ACK/ACK handshake; returns the stack-chosen server ISS. */
    private fun handshake(flow: TcpFlow, clientIss: Long): Long {
        flow.onSyn(clientIss, 65535)
        val (synAck, _) = nextTcp()
        assertTrue(synAck.has(TcpFlag.SYN) && synAck.has(TcpFlag.ACK))
        assertEquals(clientIss + 1, synAck.ack)
        feed(flow, TcpFlag.ACK, clientIss + 1, synAck.seq + 1)
        return synAck.seq
    }

    private fun payloadOf(pkt: ByteArray): ByteArray {
        val ip = Packets.parseIp(pkt, pkt.size)!!
        val tcp = Packets.parseTcp(pkt, ip.l4Offset, ip.l4Length)!!
        return pkt.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
    }

    private fun readFully(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(b, off, n - off)
            if (r < 0) error("eof")
            off += r
        }
        return b
    }

    @Test
    fun fullFlowHandshakeDataAndTeardown() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicReference<Socket>()
        val acceptThread = thread { accepted.set(server.accept()) }
        val closed = CountDownLatch(1)

        val flow = TcpFlow(
            localAddr = local, localPort = localPort,
            remoteAddr = remote, remotePort = remotePort, mss = mss,
            connectSocks = { _, _ -> Socket("127.0.0.1", server.localPort) },
            writePacket = { captured.put(it) },
            executor = executor,
            onClosed = { closed.countDown() },
        )

        val clientIss = 1000L
        flow.onSyn(clientIss, 65535)

        // SYN-ACK.
        val (synAck, _) = nextTcp()
        assertTrue("SYN", synAck.has(TcpFlag.SYN))
        assertTrue("ACK", synAck.has(TcpFlag.ACK))
        assertEquals(clientIss + 1, synAck.ack)
        val serverIss = synAck.seq

        // App ACKs the SYN-ACK (establishes).
        feed(flow, TcpFlag.ACK, clientIss + 1, serverIss + 1)
        acceptThread.join(2000)
        val srv = accepted.get() ?: error("server did not accept")

        // App → socks data.
        val ping = "ping".toByteArray()
        feed(flow, TcpFlag.ACK or TcpFlag.PSH, clientIss + 1, serverIss + 1, ping)
        val (ackForPing, _) = nextTcp()
        assertEquals(clientIss + 1 + ping.size, ackForPing.ack)
        assertEquals("ping", String(readFully(srv.getInputStream(), ping.size)))

        // socks → app data.
        srv.getOutputStream().write("pong".toByteArray())
        srv.getOutputStream().flush()
        val (pongSeg, pongPkt) = nextTcp()
        assertEquals(serverIss + 1, pongSeg.seq)
        assertEquals("pong", String(payloadOf(pongPkt)))

        // App FIN (also acks our "pong").
        feed(flow, TcpFlag.FIN or TcpFlag.ACK, clientIss + 1 + ping.size, serverIss + 1 + 4)
        val (ackForFin, _) = nextTcp()
        assertEquals(clientIss + 2 + ping.size, ackForFin.ack) // FIN consumed a seq

        // Half-close propagates to the upstream socket; then it closes too.
        assertEquals(-1, srv.getInputStream().read())
        srv.close()

        // We emit our FIN; app acks it; flow closes.
        val (ourFin, _) = nextTcp()
        assertTrue("our FIN", ourFin.has(TcpFlag.FIN))
        feed(flow, TcpFlag.ACK, clientIss + 2 + ping.size, ourFin.seq + 1)

        assertTrue("flow closed", closed.await(3, TimeUnit.SECONDS))
        server.close()
    }

    @Test
    fun largeUpstreamToAppTransferRespectsWindow() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicReference<Socket>()
        val acceptThread = thread { accepted.set(server.accept()) }
        val closed = CountDownLatch(1)
        val flow = newFlow(server.localPort, closed)

        val clientIss = 1000L
        val serverIss = handshake(flow, clientIss)
        acceptThread.join(2000)
        val srv = accepted.get() ?: error("no accept")

        // Upstream pushes 200 KB then half-closes.
        val total = 200_000
        val payload = ByteArray(total) { (it % 251).toByte() }
        thread {
            srv.getOutputStream().write(payload)
            srv.getOutputStream().flush()
            srv.shutdownOutput()
        }

        // Act as the app with a small (16 KB) receive window: only ACK what we've
        // received contiguously, which is the only thing that lets the sender advance.
        val window = 16384
        val clientSeq = clientIss + 1
        val received = java.io.ByteArrayOutputStream()
        var expect = (serverIss + 1) and 0xffff_ffffL
        var sawFin = false
        val deadline = System.currentTimeMillis() + 20_000
        while ((received.size() < total || !sawFin) && System.currentTimeMillis() < deadline) {
            val pkt = captured.poll(5, TimeUnit.SECONDS) ?: error("stalled at ${received.size()}/$total")
            val tcp = parse(pkt)
            if (tcp.payloadLength > 0) {
                if (tcp.seq == expect) {
                    received.write(payloadOf(pkt))
                    expect = (expect + tcp.payloadLength) and 0xffff_ffffL
                    feed(flow, TcpFlag.ACK, clientSeq, expect, window = window)
                }
                // else: a retransmit/out-of-order copy — ignore (the sender will catch up)
            }
            if (tcp.has(TcpFlag.FIN) && tcp.seq == expect) {
                expect = (expect + 1) and 0xffff_ffffL
                sawFin = true
                feed(flow, TcpFlag.ACK, clientSeq, expect, window = window)
            }
        }

        assertEquals("received all bytes", total, received.size())
        assertArrayEquals(payload, received.toByteArray())
        assertTrue("saw upstream FIN", sawFin)
        server.close()
    }

    @Test
    fun largeAppToUpstreamTransfer() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicReference<Socket>()
        val acceptThread = thread { accepted.set(server.accept()) }
        val closed = CountDownLatch(1)
        val flow = newFlow(server.localPort, closed)

        val clientIss = 1000L
        val serverIss = handshake(flow, clientIss)
        acceptThread.join(2000)
        val srv = accepted.get() ?: error("no accept")

        // Drain everything the upstream receives in a background thread.
        val total = 200_000
        val sink = java.io.ByteArrayOutputStream()
        val readDone = CountDownLatch(1)
        thread {
            val ins = srv.getInputStream()
            val b = ByteArray(8192)
            while (true) {
                val n = ins.read(b)
                if (n < 0) break
                sink.write(b, 0, n)
            }
            readDone.countDown()
        }

        val payload = ByteArray(total) { ((it * 31) % 251).toByte() }
        var seq = clientIss + 1
        var off = 0
        while (off < total) {
            val len = minOf(mss, total - off)
            feed(flow, TcpFlag.ACK or TcpFlag.PSH, seq, serverIss + 1, payload.copyOfRange(off, off + len))
            // Wait for the stack to ACK this segment before sending the next, so we
            // exercise the receive path's ACKing rather than blasting blindly.
            awaitAck(seq + len)
            seq += len
            off += len
        }
        // App closes its half; upstream should see EOF.
        feed(flow, TcpFlag.FIN or TcpFlag.ACK, seq, serverIss + 1)

        assertTrue("upstream saw EOF", readDone.await(10, TimeUnit.SECONDS))
        assertEquals(total, sink.size())
        assertArrayEquals(payload, sink.toByteArray())
        server.close()
    }

    private fun awaitAck(minAck: Long) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val pkt = captured.poll(5, TimeUnit.SECONDS) ?: error("no ACK")
            val tcp = parse(pkt)
            if (tcp.has(TcpFlag.ACK) && tcp.ack >= minAck) return
        }
        error("ACK for $minAck never arrived")
    }

    @Test
    fun proxyRefusalSendsRst() {
        val closed = CountDownLatch(1)
        val flow = TcpFlow(
            localAddr = local, localPort = localPort,
            remoteAddr = remote, remotePort = remotePort, mss = mss,
            connectSocks = { _, _ -> throw IOException("connection refused") },
            writePacket = { captured.put(it) },
            executor = executor,
            onClosed = { closed.countDown() },
        )

        flow.onSyn(2000L, 65535)
        val (synAck, _) = nextTcp()
        assertTrue(synAck.has(TcpFlag.SYN))

        // The async SOCKS connect fails → the flow resets the app's connection.
        val (rst, _) = nextTcp()
        assertTrue("RST", rst.has(TcpFlag.RST))
        assertTrue("flow closed", closed.await(3, TimeUnit.SECONDS))
    }
}
