package eu.matscheko.pivot.socks

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import kotlin.concurrent.thread

class Socks5HandshakeTest {

    private lateinit var sink: ServerSocket
    // Thread-safe: the accept thread appends while tests read/close concurrently.
    private val opened = java.util.concurrent.CopyOnWriteArrayList<Socket>()

    /** A real TCP endpoint so successful handshakes get a usable upstream socket. */
    private fun upstreamConnect(): (InetAddress, Int) -> Socket = { _, _ ->
        Socket("127.0.0.1", sink.localPort).also { opened += it }
    }

    @Before
    fun setUp() {
        sink = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                while (true) opened += sink.accept()
            } catch (e: Exception) {
                // closed
            }
        }
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching { sink.close() }
    }

    private fun bytes(builder: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(builder).toByteArray()

    private fun ByteArrayOutputStream.u8(vararg values: Int) = values.forEach { write(it and 0xFF) }

    private fun run(
        request: ByteArray,
        auth: AuthConfig,
        resolve: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByName("1.2.3.4")) },
        connect: (InetAddress, Int) -> Socket = upstreamConnect(),
    ): Result {
        val out = ByteArrayOutputStream()
        val socket = Socks5Handshake(
            ByteArrayInputStream(request),
            out,
            auth,
            resolve,
            connect,
        ).negotiate()
        return Result(socket, out.toByteArray())
    }

    private data class Result(val upstream: Socket?, val reply: ByteArray)

    @Test
    fun unsupportedMethodIsRejected() {
        // Server wants password auth, client offers only no-auth (0x00).
        val request = bytes { u8(0x05, 0x01, 0x00) }
        val result = run(request, AuthConfig.Password("u", "p"))

        assertNull(result.upstream)
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte()), result.reply)
    }

    @Test
    fun hostnameResolutionSuccess() {
        val host = "example.com"
        val request = bytes {
            u8(0x05, 0x01, 0x00) // greeting: no-auth
            u8(0x05, 0x01, 0x00, 0x03, host.length)
            write(host.toByteArray(Charsets.US_ASCII))
            u8(0x00, 0x50) // port 80
        }
        val result = run(request, AuthConfig.None)

        assertNotNull(result.upstream)
        // method reply, then request reply with REP=SUCCEEDED
        assertEquals(0x05, result.reply[0].toInt() and 0xFF)
        assertEquals(0x00, result.reply[1].toInt() and 0xFF)
        assertEquals(0x05, result.reply[2].toInt() and 0xFF)
        assertEquals(Reply.SUCCEEDED, result.reply[3].toInt() and 0xFF)
    }

    @Test
    fun hostnameResolutionFailure() {
        val host = "no-such-host.invalid"
        val request = bytes {
            u8(0x05, 0x01, 0x00)
            u8(0x05, 0x01, 0x00, 0x03, host.length)
            write(host.toByteArray(Charsets.US_ASCII))
            u8(0x00, 0x50)
        }
        val result = run(
            request,
            AuthConfig.None,
            resolve = { throw UnknownHostException(it) },
        )

        assertNull(result.upstream)
        assertEquals(Reply.HOST_UNREACHABLE, result.reply[3].toInt() and 0xFF)
    }

    @Test
    fun ipv6TargetIsParsed() {
        val target = InetAddress.getByName("2001:db8::1")
        val request = bytes {
            u8(0x05, 0x01, 0x00)
            u8(0x05, 0x01, 0x00, 0x04)
            write(target.address)
            u8(0x01, 0xBB) // port 443
        }
        var captured: InetAddress? = null
        val result = run(
            request,
            AuthConfig.None,
            connect = { addr, _ ->
                captured = addr
                Socket("127.0.0.1", sink.localPort).also { opened += it }
            },
        )

        assertNotNull(result.upstream)
        assertTrue(captured is Inet6Address)
        assertArrayEquals(target.address, captured!!.address)
        assertEquals(Reply.SUCCEEDED, result.reply[3].toInt() and 0xFF)
    }

    @Test
    fun unsupportedCommandIsRejected() {
        val request = bytes {
            u8(0x05, 0x01, 0x00)
            u8(0x05, 0x02, 0x00, 0x01) // CMD=BIND
            u8(0x01, 0x02, 0x03, 0x04)
            u8(0x00, 0x50)
        }
        val result = run(request, AuthConfig.None)

        assertNull(result.upstream)
        assertEquals(Reply.COMMAND_NOT_SUPPORTED, result.reply[3].toInt() and 0xFF)
    }

    @Test
    fun passwordAuthSuccess() {
        val request = bytes {
            u8(0x05, 0x01, 0x02) // greeting: username/password
            u8(0x01, 4)
            write("user".toByteArray())
            u8(4)
            write("pass".toByteArray())
            u8(0x05, 0x01, 0x00, 0x01) // CONNECT IPv4
            u8(0x01, 0x02, 0x03, 0x04)
            u8(0x00, 0x50)
        }
        val result = run(request, AuthConfig.Password("user", "pass"))

        assertNotNull(result.upstream)
        // method reply (05 02), auth reply (01 00), then request reply (05 00)
        assertArrayEquals(byteArrayOf(0x05, 0x02), result.reply.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0x01, 0x00), result.reply.copyOfRange(2, 4))
        assertEquals(0x05, result.reply[4].toInt() and 0xFF)
        assertEquals(Reply.SUCCEEDED, result.reply[5].toInt() and 0xFF)
    }

    @Test
    fun passwordAuthFailure() {
        val request = bytes {
            u8(0x05, 0x01, 0x02)
            u8(0x01, 4)
            write("user".toByteArray())
            u8(5)
            write("wrong".toByteArray())
        }
        val result = run(request, AuthConfig.Password("user", "pass"))

        assertNull(result.upstream)
        assertArrayEquals(byteArrayOf(0x05, 0x02), result.reply.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0x01, 0x01), result.reply.copyOfRange(2, 4))
    }
}
