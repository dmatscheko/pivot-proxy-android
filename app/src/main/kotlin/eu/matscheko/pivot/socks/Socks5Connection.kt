package eu.matscheko.pivot.socks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Handles one accepted client connection: runs the handshake, then pumps bytes
 * bidirectionally between client and upstream until either side closes.
 *
 * [resolve] and [connect] are forwarded to the handshake so the egress service can
 * decorate them — e.g. resolve names on the underlying (non-VPN) network and
 * `VpnService.protect()` the upstream socket. Both default to plain behaviour.
 *
 * Never throws to the caller — any failure results in a clean close.
 */
class Socks5Connection(
    private val client: Socket,
    private val auth: AuthConfig,
    private val resolve: ((String) -> Array<InetAddress>)? = null,
    private val connect: ((InetAddress, Int) -> Socket)? = null,
    private val onClosed: () -> Unit,
) {
    suspend fun handle() {
        var upstream: Socket? = null
        try {
            runCatching { client.tcpNoDelay = true }
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val handshake = Socks5Handshake(
                input,
                output,
                auth,
                resolve = resolve ?: { InetAddress.getAllByName(it) },
                connect = connect ?: { addr, port ->
                    Socket().apply {
                        connect(InetSocketAddress(addr, port), Socks5Handshake.CONNECT_TIMEOUT_MS)
                    }
                },
            )
            upstream = handshake.negotiate()
            if (upstream == null) return

            relay(input, output, upstream)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // IOException, EOFException, OOM, etc. — close quietly, never crash.
        } finally {
            closeQuietly(client)
            upstream?.let { closeQuietly(it) }
            onClosed()
        }
    }

    private suspend fun relay(
        clientIn: InputStream,
        clientOut: OutputStream,
        upstream: Socket,
    ) = coroutineScope {
        val upstreamIn = upstream.getInputStream()
        val upstreamOut = upstream.getOutputStream()

        val clientToUpstream = launch(Dispatchers.IO) {
            if (copyStream(clientIn, upstreamOut)) {
                // Client finished sending → half-close the upstream so it sees EOF
                // but can still send the rest of the response back.
                runCatching { upstream.shutdownOutput() }
            } else {
                // Real error → tear down both so the other pump unblocks.
                closeQuietly(client)
                closeQuietly(upstream)
            }
        }
        val upstreamToClient = launch(Dispatchers.IO) {
            if (copyStream(upstreamIn, clientOut)) {
                runCatching { client.shutdownOutput() }
            } else {
                closeQuietly(client)
                closeQuietly(upstream)
            }
        }
        joinAll(clientToUpstream, upstreamToClient)
        closeQuietly(client)
        closeQuietly(upstream)
    }

    /** Pumps until EOF (returns true) or an error/exhaustion (returns false). */
    private fun copyStream(input: InputStream, output: OutputStream): Boolean {
        val buffer = try {
            ByteArray(BUFFER_SIZE)
        } catch (e: OutOfMemoryError) {
            return false // resource exhaustion: drop this pump, do not crash
        }
        return try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}

internal fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (e: IOException) {
        // ignore
    }
}
