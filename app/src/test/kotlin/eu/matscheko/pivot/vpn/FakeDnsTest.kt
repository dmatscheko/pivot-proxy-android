package eu.matscheko.pivot.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FakeDnsTest {

    private fun query(name: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x12); out.write(0x34)          // id
        out.write(0x01); out.write(0x00)          // flags: RD
        out.write(0x00); out.write(0x01)          // qdcount
        out.write(0x00); out.write(0x00)          // ancount
        out.write(0x00); out.write(0x00)          // nscount
        out.write(0x00); out.write(0x00)          // arcount
        name.split(".").forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00)                            // root label
        out.write((qtype ushr 8) and 0xff); out.write(qtype and 0xff)
        out.write(0x00); out.write(0x01)          // qclass IN
        return out.toByteArray()
    }

    @Test
    fun aQueryReturnsFakeIpAndRoundTrips() {
        val dns = FakeDns()
        val q = query("example.com", 1)
        val resp = dns.handle(q, q.size)!!

        // QR set, ANCOUNT == 1
        assertTrue("QR bit", (resp[2].toInt() and 0x80) != 0)
        assertEquals(1, ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff))

        // Last 4 bytes are the A record RDATA (the fake IP), in 198.18.0.0/15.
        val n = resp.size
        val ip = intArrayOf(
            resp[n - 4].toInt() and 0xff,
            resp[n - 3].toInt() and 0xff,
            resp[n - 2].toInt() and 0xff,
            resp[n - 1].toInt() and 0xff,
        )
        assertEquals(198, ip[0])
        assertTrue("second octet 18 or 19 (/15)", ip[1] == 18 || ip[1] == 19)

        val ipStr = "${ip[0]}.${ip[1]}.${ip[2]}.${ip[3]}"
        assertEquals("example.com", dns.hostnameForIp(ipStr))
    }

    @Test
    fun aaaaQueryHasNoAnswer() {
        val dns = FakeDns()
        val q = query("example.com", 28) // AAAA
        val resp = dns.handle(q, q.size)!!
        assertEquals(0, ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff))
    }

    @Test
    fun malformedQueryReturnsNull() {
        val dns = FakeDns()
        assertNull(dns.handle(ByteArray(5), 5))
    }

    @Test
    fun sameHostReusesSameIp() {
        val dns = FakeDns()
        val q = query("repeat.example", 1)
        val a = dns.handle(q, q.size)!!
        val b = dns.handle(q, q.size)!!
        assertEquals(a[a.size - 1], b[b.size - 1])
    }
}
