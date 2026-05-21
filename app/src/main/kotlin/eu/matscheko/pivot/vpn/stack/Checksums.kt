package eu.matscheko.pivot.vpn.stack

/**
 * Internet checksum (RFC 1071) and the TCP/UDP pseudo-header checksums for IPv4 and
 * IPv6. Pure arithmetic on `ByteArray`s so it is unit-testable on the JVM with no
 * Android or socket dependencies.
 */
internal object Checksums {

    /** One's-complement sum over [len] bytes of [data] starting at [off]. */
    private fun sum(data: ByteArray, off: Int, len: Int, initial: Int): Int {
        var acc = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            acc += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < end) {
            acc += (data[i].toInt() and 0xff) shl 8
        }
        return acc
    }

    private fun fold(accumulated: Int): Int {
        var acc = accumulated
        while (acc shr 16 != 0) {
            acc = (acc and 0xffff) + (acc shr 16)
        }
        return acc.inv() and 0xffff
    }

    /** Standard IPv4 header checksum over [len] bytes at [off]. */
    fun ip(data: ByteArray, off: Int, len: Int): Int = fold(sum(data, off, len, 0))

    /**
     * TCP/UDP checksum for an IPv4 payload. [src]/[dst] are 4-byte addresses,
     * [protocol] is 6 (TCP) or 17 (UDP), and the L4 segment lives in [data] at
     * [l4Off]..[l4Off]+[l4Len].
     */
    fun l4Ipv4(
        src: ByteArray,
        dst: ByteArray,
        protocol: Int,
        data: ByteArray,
        l4Off: Int,
        l4Len: Int,
    ): Int {
        var acc = 0
        acc = sum(src, 0, 4, acc)
        acc = sum(dst, 0, 4, acc)
        acc += protocol and 0xff
        acc += l4Len and 0xffff
        acc = sum(data, l4Off, l4Len, acc)
        return fold(acc)
    }

    /**
     * TCP/UDP checksum for an IPv6 payload. [src]/[dst] are 16-byte addresses,
     * [nextHeader] is 6 (TCP) or 17 (UDP).
     */
    fun l4Ipv6(
        src: ByteArray,
        dst: ByteArray,
        nextHeader: Int,
        data: ByteArray,
        l4Off: Int,
        l4Len: Int,
    ): Int {
        var acc = 0
        acc = sum(src, 0, 16, acc)
        acc = sum(dst, 0, 16, acc)
        // 32-bit upper-layer packet length.
        acc += (l4Len ushr 16) and 0xffff
        acc += l4Len and 0xffff
        acc += nextHeader and 0xff
        acc = sum(data, l4Off, l4Len, acc)
        return fold(acc)
    }
}
