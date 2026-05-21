package eu.matscheko.pivot.socks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class Socks5ConnectionTest {

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

    /**
     * A client that sends its request and then half-closes (shutdownOutput) must still
     * receive the full response — the relay must not tear the upstream down on the
     * client's write-side EOF.
     */
    @Test
    fun halfCloseStillDeliversResponse() = runBlocking {
        // Fake origin: read the whole request (until the client half-closes), then
        // reply and close.
        val origin = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val originThread = thread {
            val s = origin.accept()
            val req = s.getInputStream().readBytes() // reads until EOF (client half-close)
            assertEquals("GET /", String(req))
            s.getOutputStream().write("RESPONSE-BODY".toByteArray())
            s.getOutputStream().flush()
            s.close()
        }

        // A real socket pair for the "client" side the connection reads from.
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val clientSide = Socket("127.0.0.1", listener.localPort)
        clientSide.soTimeout = 5000
        val serverSide = listener.accept()

        val conn = Socks5Connection(
            client = serverSide,
            auth = AuthConfig.None,
            connect = { _, _ -> Socket("127.0.0.1", origin.localPort) },
            onClosed = {},
        )

        withTimeout(8000) {
            val job = launch(Dispatchers.IO) { conn.handle() }

            val out = clientSide.getOutputStream()
            val ins = clientSide.getInputStream()

            // SOCKS5 greeting (no-auth) + method reply.
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val method = readN(ins, 2)
            assertEquals(0x05, method[0].toInt())
            assertEquals(0x00, method[1].toInt())

            // CONNECT 1.2.3.4:80, then a 10-byte success reply.
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0, 80)); out.flush()
            val reply = readN(ins, 10)
            assertEquals(0x00, reply[1].toInt())

            // Send the request, then half-close — the response must still come back.
            out.write("GET /".toByteArray()); out.flush()
            clientSide.shutdownOutput()

            val resp = ins.readBytes()
            assertEquals("RESPONSE-BODY", String(resp))

            job.join()
        }

        originThread.join(2000)
        clientSide.close()
        listener.close()
        origin.close()
    }
}
