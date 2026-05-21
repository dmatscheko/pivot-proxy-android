package eu.matscheko.pivot.vpn.stack

/**
 * IPv4 / IPv6 + TCP / UDP header parsing and emitting for the userspace stack.
 *
 * Pure byte arithmetic, no Android or socket dependencies, so the packet handling is
 * unit-testable on the JVM. Sequence/acknowledgement numbers are carried as `Long`
 * in `[0, 2^32)` to make the unsigned 32-bit wrap-around arithmetic in [TcpFlow]
 * explicit.
 */
internal object IpProto {
    const val TCP = 6
    const val UDP = 17
    const val ICMP = 1
    const val ICMPV6 = 58
}

/** TCP control-bit flags. */
internal object TcpFlag {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

/** A parsed IP header pointing into the original packet buffer. */
internal class IpHeader(
    val version: Int,
    val protocol: Int,
    /** Raw address bytes (4 for IPv4, 16 for IPv6). */
    val src: ByteArray,
    val dst: ByteArray,
    /** Offset of the layer-4 payload within the packet buffer. */
    val l4Offset: Int,
    /** Length of the layer-4 payload (TCP/UDP header + data). */
    val l4Length: Int,
    /** IPv4 fragment (offset != 0 or MF set); such packets are dropped. */
    val fragmented: Boolean,
)

internal class TcpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val window: Int,
    /** Offset of the TCP payload within the packet buffer. */
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun has(flag: Int) = (flags and flag) != 0
}

internal class UdpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

internal object Packets {

    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xff) shl 24) or
            ((b[off + 1].toLong() and 0xff) shl 16) or
            ((b[off + 2].toLong() and 0xff) shl 8) or
            (b[off + 3].toLong() and 0xff)

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xff).toByte()
        b[off + 1] = (v and 0xff).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xff).toByte()
        b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte()
        b[off + 3] = (v and 0xff).toByte()
    }

    /** Parse the IP header, or null if [len] is too short / version unknown. */
    fun parseIp(b: ByteArray, len: Int): IpHeader? {
        if (len < 1) return null
        return when ((b[0].toInt() and 0xf0) ushr 4) {
            4 -> parseIpv4(b, len)
            6 -> parseIpv6(b, len)
            else -> null
        }
    }

    private fun parseIpv4(b: ByteArray, len: Int): IpHeader? {
        if (len < 20) return null
        val ihl = (b[0].toInt() and 0x0f) * 4
        if (ihl < 20 || ihl > len) return null
        val totalLength = u16(b, 2)
        if (totalLength < ihl || totalLength > len) return null
        val flagsFrag = u16(b, 6)
        val fragmented = (flagsFrag and 0x2000) != 0 || (flagsFrag and 0x1fff) != 0
        return IpHeader(
            version = 4,
            protocol = b[9].toInt() and 0xff,
            src = b.copyOfRange(12, 16),
            dst = b.copyOfRange(16, 20),
            l4Offset = ihl,
            l4Length = totalLength - ihl,
            fragmented = fragmented,
        )
    }

    private fun parseIpv6(b: ByteArray, len: Int): IpHeader? {
        if (len < 40) return null
        val payloadLength = u16(b, 4)
        if (40 + payloadLength > len) return null
        return IpHeader(
            version = 6,
            protocol = b[6].toInt() and 0xff, // next header; extension headers unsupported (dropped)
            src = b.copyOfRange(8, 24),
            dst = b.copyOfRange(24, 40),
            l4Offset = 40,
            l4Length = payloadLength,
            fragmented = false,
        )
    }

    fun parseTcp(b: ByteArray, off: Int, l4Len: Int): TcpHeader? {
        if (l4Len < 20) return null
        val dataOffset = ((b[off + 12].toInt() and 0xf0) ushr 4) * 4
        if (dataOffset < 20 || dataOffset > l4Len) return null
        return TcpHeader(
            srcPort = u16(b, off),
            dstPort = u16(b, off + 2),
            seq = u32(b, off + 4),
            ack = u32(b, off + 8),
            flags = b[off + 13].toInt() and 0x3f,
            window = u16(b, off + 14),
            payloadOffset = off + dataOffset,
            payloadLength = l4Len - dataOffset,
        )
    }

    fun parseUdp(b: ByteArray, off: Int, l4Len: Int): UdpHeader? {
        if (l4Len < 8) return null
        val length = u16(b, off + 4)
        val payloadLen = (length - 8).coerceIn(0, l4Len - 8)
        return UdpHeader(
            srcPort = u16(b, off),
            dstPort = u16(b, off + 2),
            payloadOffset = off + 8,
            payloadLength = payloadLen,
        )
    }

    /**
     * Build a complete IP + TCP packet. [src]/[dst] are the addresses as they appear
     * in the emitted packet (our side = [src], the app = [dst]); their length (4 or
     * 16) selects IPv4 vs IPv6. When [mss] is non-null an MSS option is appended (used
     * on SYN/SYN-ACK).
     */
    fun buildTcp(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOff: Int = 0,
        payloadLen: Int = payload?.size ?: 0,
        mss: Int? = null,
    ): ByteArray {
        val ipv6 = src.size == 16
        val ipHdrLen = if (ipv6) 40 else 20
        val tcpHdrLen = if (mss != null) 24 else 20
        val total = ipHdrLen + tcpHdrLen + payloadLen
        val pkt = ByteArray(total)

        if (ipv6) buildIpv6Header(pkt, src, dst, IpProto.TCP, tcpHdrLen + payloadLen)
        else buildIpv4Header(pkt, src, dst, IpProto.TCP, tcpHdrLen + payloadLen)

        val t = ipHdrLen
        putU16(pkt, t, srcPort)
        putU16(pkt, t + 2, dstPort)
        putU32(pkt, t + 4, seq)
        putU32(pkt, t + 8, ack)
        pkt[t + 12] = ((tcpHdrLen / 4) shl 4).toByte() // data offset; reserved = 0
        pkt[t + 13] = (flags and 0x3f).toByte()
        putU16(pkt, t + 14, window)
        // checksum (t+16) left 0 for now; urgent pointer (t+18) = 0
        if (mss != null) {
            pkt[t + 20] = 2 // kind: MSS
            pkt[t + 21] = 4 // length
            putU16(pkt, t + 22, mss)
        }
        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOff, pkt, t + tcpHdrLen, payloadLen)
        }

        val csum = if (ipv6) {
            Checksums.l4Ipv6(src, dst, IpProto.TCP, pkt, t, tcpHdrLen + payloadLen)
        } else {
            Checksums.l4Ipv4(src, dst, IpProto.TCP, pkt, t, tcpHdrLen + payloadLen)
        }
        putU16(pkt, t + 16, csum)
        return pkt
    }

    /** Build a complete IP + UDP datagram. Address length (4/16) selects IPv4/IPv6. */
    fun buildUdp(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val ipv6 = src.size == 16
        val ipHdrLen = if (ipv6) 40 else 20
        val udpLen = 8 + payload.size
        val total = ipHdrLen + udpLen
        val pkt = ByteArray(total)

        if (ipv6) buildIpv6Header(pkt, src, dst, IpProto.UDP, udpLen)
        else buildIpv4Header(pkt, src, dst, IpProto.UDP, udpLen)

        val u = ipHdrLen
        putU16(pkt, u, srcPort)
        putU16(pkt, u + 2, dstPort)
        putU16(pkt, u + 4, udpLen)
        // checksum (u+6) computed below
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)

        var csum = if (ipv6) {
            Checksums.l4Ipv6(src, dst, IpProto.UDP, pkt, u, udpLen)
        } else {
            Checksums.l4Ipv4(src, dst, IpProto.UDP, pkt, u, udpLen)
        }
        // A computed UDP checksum of zero is transmitted as 0xffff.
        if (csum == 0) csum = 0xffff
        putU16(pkt, u + 6, csum)
        return pkt
    }

    private fun buildIpv4Header(pkt: ByteArray, src: ByteArray, dst: ByteArray, protocol: Int, l4Len: Int) {
        pkt[0] = 0x45 // version 4, IHL 5
        // tos = 0
        putU16(pkt, 2, 20 + l4Len) // total length
        // id = 0, flags: Don't Fragment
        pkt[6] = 0x40
        pkt[8] = 64 // TTL
        pkt[9] = protocol.toByte()
        System.arraycopy(src, 0, pkt, 12, 4)
        System.arraycopy(dst, 0, pkt, 16, 4)
        putU16(pkt, 10, Checksums.ip(pkt, 0, 20))
    }

    private fun buildIpv6Header(pkt: ByteArray, src: ByteArray, dst: ByteArray, nextHeader: Int, l4Len: Int) {
        pkt[0] = 0x60 // version 6
        putU16(pkt, 4, l4Len) // payload length
        pkt[6] = nextHeader.toByte()
        pkt[7] = 64 // hop limit
        System.arraycopy(src, 0, pkt, 8, 16)
        System.arraycopy(dst, 0, pkt, 24, 16)
    }
}
