package eu.matscheko.pivot.vpn.stack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {

    private val v4a = byteArrayOf(10, 0, 0, 1)
    private val v4b = byteArrayOf(10, 0, 0, 2)
    private val v6a = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val v6b = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }

    @Test
    fun ipv4HeaderChecksumVerifies() {
        val pkt = Packets.buildTcp(v4a, v4b, 1234, 80, 1000, 2000, TcpFlag.ACK, 65535)
        // Internet checksum over a correct header sums to zero.
        assertEquals(0, Checksums.ip(pkt, 0, 20))
    }

    @Test
    fun tcpIpv4ChecksumVerifies() {
        val payload = "hello world".toByteArray()
        val pkt = Packets.buildTcp(
            v4a, v4b, 1234, 80, 1000, 2000, TcpFlag.ACK or TcpFlag.PSH, 65535, payload,
        )
        val ip = Packets.parseIp(pkt, pkt.size)!!
        assertEquals(0, Checksums.l4Ipv4(v4a, v4b, IpProto.TCP, pkt, ip.l4Offset, ip.l4Length))
    }

    @Test
    fun tcpIpv6ChecksumVerifies() {
        val payload = "ipv6 payload".toByteArray()
        val pkt = Packets.buildTcp(
            v6a, v6b, 1234, 443, 5, 9, TcpFlag.ACK or TcpFlag.PSH, 65535, payload,
        )
        val ip = Packets.parseIp(pkt, pkt.size)!!
        assertEquals(6, ip.version)
        assertEquals(0, Checksums.l4Ipv6(v6a, v6b, IpProto.TCP, pkt, ip.l4Offset, ip.l4Length))
    }

    @Test
    fun tcpRoundTrips() {
        val payload = "abcdef".toByteArray()
        val pkt = Packets.buildTcp(
            v4a, v4b, 4321, 8080, 0x11223344L, 0x55667788L,
            TcpFlag.SYN or TcpFlag.ACK, 4096, payload, mss = 1460,
        )
        val ip = Packets.parseIp(pkt, pkt.size)!!
        assertEquals(IpProto.TCP, ip.protocol)
        assertArrayEquals(v4a, ip.src)
        assertArrayEquals(v4b, ip.dst)
        val tcp = Packets.parseTcp(pkt, ip.l4Offset, ip.l4Length)!!
        assertEquals(4321, tcp.srcPort)
        assertEquals(8080, tcp.dstPort)
        assertEquals(0x11223344L, tcp.seq)
        assertEquals(0x55667788L, tcp.ack)
        assertEquals(4096, tcp.window)
        assertTrue(tcp.has(TcpFlag.SYN))
        assertTrue(tcp.has(TcpFlag.ACK))
        assertEquals(payload.size, tcp.payloadLength)
        assertArrayEquals(
            payload,
            pkt.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength),
        )
    }

    @Test
    fun udpRoundTripsAndChecksumVerifies() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val pkt = Packets.buildUdp(v4a, v4b, 53, 40000, payload)
        val ip = Packets.parseIp(pkt, pkt.size)!!
        assertEquals(IpProto.UDP, ip.protocol)
        assertEquals(0, Checksums.l4Ipv4(v4a, v4b, IpProto.UDP, pkt, ip.l4Offset, ip.l4Length))
        val udp = Packets.parseUdp(pkt, ip.l4Offset, ip.l4Length)!!
        assertEquals(53, udp.srcPort)
        assertEquals(40000, udp.dstPort)
        assertArrayEquals(
            payload,
            pkt.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength),
        )
    }

    @Test
    fun rejectsTruncatedAndUnknown() {
        assertNull(Packets.parseIp(ByteArray(3), 3))
        assertNull(Packets.parseIp(byteArrayOf(0x70), 1)) // version 7
    }
}
