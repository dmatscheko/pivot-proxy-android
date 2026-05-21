package eu.matscheko.pivot.socks

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Performs the full SOCKS5 handshake on a pair of streams: method negotiation,
 * optional RFC 1929 username/password subnegotiation, and the CONNECT request
 * including upstream resolution + connection.
 *
 * Pure Kotlin / java.net only so it can be unit-tested with in-memory streams.
 * [resolve] and [connect] are injectable for the same reason — and [connect] is
 * exactly where the VPN's `protect()` is wired in for the egress proxy, so that
 * the proxy's outbound sockets bypass our own capturing tun.
 *
 * [negotiate] returns the connected upstream [Socket] on success (caller relays),
 * or `null` if the handshake failed — in which case an appropriate reply has
 * already been written (when the protocol called for one) and the caller should
 * just close the client socket.
 */
class Socks5Handshake(
    input: InputStream,
    private val output: OutputStream,
    private val auth: AuthConfig,
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val connect: (InetAddress, Int) -> Socket = { addr, port ->
        Socket().apply { connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS) }
    },
) {
    private val input = DataInputStream(input)

    fun negotiate(): Socket? {
        if (!negotiateMethod()) return null
        if (auth is AuthConfig.Password && !authenticate()) return null
        return handleRequest()
    }

    private fun negotiateMethod(): Boolean {
        if (readByte() != Socks.VERSION) return false
        val nMethods = readByte()
        if (nMethods <= 0) return false
        val methods = readFully(nMethods)

        val required = if (auth is AuthConfig.Password) {
            Socks.METHOD_USERNAME_PASSWORD
        } else {
            Socks.METHOD_NO_AUTH
        }
        if (methods.none { (it.toInt() and 0xFF) == required }) {
            write(Socks.VERSION, Socks.METHOD_NONE_ACCEPTABLE)
            return false
        }
        write(Socks.VERSION, required)
        return true
    }

    private fun authenticate(): Boolean {
        val cfg = auth as AuthConfig.Password
        if (readByte() != Socks.AUTH_VERSION) return false
        val username = String(readFully(readByte()), Charsets.UTF_8)
        val password = String(readFully(readByte()), Charsets.UTF_8)
        val ok = username == cfg.username && password == cfg.password
        write(Socks.AUTH_VERSION, if (ok) Socks.AUTH_SUCCESS else Socks.AUTH_FAILURE)
        return ok
    }

    private fun handleRequest(): Socket? {
        if (readByte() != Socks.VERSION) return null
        val cmd = readByte()
        readByte() // RSV, ignored
        val atyp = readByte()

        if (cmd != Socks.CMD_CONNECT) {
            sendReply(Reply.COMMAND_NOT_SUPPORTED)
            return null
        }

        val target: InetAddress = try {
            when (atyp) {
                Socks.ATYP_IPV4 -> InetAddress.getByAddress(readFully(4))
                Socks.ATYP_IPV6 -> InetAddress.getByAddress(readFully(16))
                Socks.ATYP_DOMAIN -> {
                    val host = String(readFully(readByte()), Charsets.US_ASCII)
                    resolve(host).firstOrNull() ?: throw UnknownHostException(host)
                }
                else -> {
                    sendReply(Reply.ADDRESS_TYPE_NOT_SUPPORTED)
                    return null
                }
            }
        } catch (e: UnknownHostException) {
            sendReply(Reply.HOST_UNREACHABLE)
            return null
        }
        val port = (readByte() shl 8) or readByte()

        val upstream = try {
            connect(target, port)
        } catch (e: Exception) {
            sendReply(mapConnectError(e))
            return null
        }
        sendReply(Reply.SUCCEEDED, upstream.localAddress, upstream.localPort)
        return upstream
    }

    private fun mapConnectError(e: Exception): Int = when (e) {
        is ConnectException ->
            if (e.message?.contains("refused", ignoreCase = true) == true) {
                Reply.CONNECTION_REFUSED
            } else {
                Reply.NETWORK_UNREACHABLE
            }
        is NoRouteToHostException -> Reply.HOST_UNREACHABLE
        is UnknownHostException -> Reply.HOST_UNREACHABLE
        is SocketTimeoutException -> Reply.HOST_UNREACHABLE
        else -> Reply.GENERAL_FAILURE
    }

    private fun sendReply(rep: Int, bndAddr: InetAddress? = null, bndPort: Int = 0) {
        val buf = ByteArrayOutputStream(22)
        buf.write(Socks.VERSION)
        buf.write(rep)
        buf.write(0x00) // RSV
        val raw = bndAddr?.address
        when (raw?.size) {
            16 -> {
                buf.write(Socks.ATYP_IPV6)
                buf.write(raw)
            }
            4 -> {
                buf.write(Socks.ATYP_IPV4)
                buf.write(raw)
            }
            else -> {
                buf.write(Socks.ATYP_IPV4)
                buf.write(byteArrayOf(0, 0, 0, 0))
            }
        }
        buf.write((bndPort ushr 8) and 0xFF)
        buf.write(bndPort and 0xFF)
        output.write(buf.toByteArray())
        output.flush()
    }

    /** Reads a single unsigned byte; throws [EOFException] at end of stream. */
    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("unexpected end of stream")
        return b
    }

    private fun readFully(n: Int): ByteArray {
        if (n < 0) throw IOException("negative length")
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return bytes
    }

    private fun write(vararg bytes: Int) {
        output.write(ByteArray(bytes.size) { bytes[it].toByte() })
        output.flush()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
