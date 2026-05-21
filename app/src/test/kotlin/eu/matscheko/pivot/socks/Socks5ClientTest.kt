package eu.matscheko.pivot.socks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class Socks5ClientTest {

    private fun readN(ins: InputStream, n: Int): ByteArray {
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
    fun sendsNoAuthGreetingAndIpv4Connect() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val request = AtomicReference<ByteArray>()
        val t = thread {
            val s = server.accept()
            val ins = s.getInputStream()
            val out = s.getOutputStream()
            // Greeting: VER, NMETHODS, methods.
            val greet = readN(ins, 2)
            readN(ins, greet[1].toInt() and 0xff)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            // CONNECT request: VER CMD RSV ATYP + 4-byte IPv4 + 2-byte port.
            request.set(readN(ins, 10))
            // Reply success with a dummy bound address.
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
        }

        val dest = byteArrayOf(198.toByte(), 18, 0, 7)
        val sock = Socks5Client.connect("127.0.0.1", server.localPort, dest, 443)
        t.join(2000)

        val req = request.get()!!
        assertEquals(0x05, req[0].toInt())   // VER
        assertEquals(0x01, req[1].toInt())   // CONNECT
        assertEquals(0x01, req[3].toInt())   // ATYP IPv4
        assertArrayEquals(dest, req.copyOfRange(4, 8))
        assertEquals(443, ((req[8].toInt() and 0xff) shl 8) or (req[9].toInt() and 0xff))

        sock.close()
        server.close()
    }

    @Test
    fun throwsWhenProxyRejectsConnect() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread {
            val s = server.accept()
            val ins = s.getInputStream()
            val out = s.getOutputStream()
            val greet = readN(ins, 2)
            readN(ins, greet[1].toInt() and 0xff)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            readN(ins, 10)
            // REP = 0x05 (connection refused).
            out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
        }

        assertThrows(IOException::class.java) {
            Socks5Client.connect("127.0.0.1", server.localPort, byteArrayOf(1, 1, 1, 1), 80)
        }
        server.close()
    }
}
