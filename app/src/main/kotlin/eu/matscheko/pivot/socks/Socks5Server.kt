package eu.matscheko.pivot.socks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Opens a [ServerSocket], accepts connections on a dedicated single-threaded
 * dispatcher, and hands each one to a [Socks5Connection] on [Dispatchers.IO].
 *
 * [start] binds synchronously, so a bind failure (e.g. port in use) propagates
 * to the caller as an exception. Everything after a successful bind runs on the
 * server's own coroutine scope, torn down by [stop].
 *
 * [resolve] decorates hostname resolution (used to resolve on the underlying,
 * non-VPN network so the egress doesn't resolve through our own tun). [connect]
 * decorates the per-connection upstream dial (used to `protect()` the egress socket
 * against our own VPN). [onAccept] is invoked on every accepted client socket before
 * it is handled (used to `protect()` the accepted socket so its reply packets bypass
 * the tun when the VPN is also running).
 */
class Socks5Server(
    val bindAddress: InetAddress,
    val port: Int,
    private val auth: AuthConfig,
    private val resolve: ((String) -> Array<InetAddress>)? = null,
    private val connect: ((InetAddress, Int) -> Socket)? = null,
    private val onAccept: (Socket) -> Unit = {},
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val acceptDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val _activeConnections = MutableStateFlow(0)
    val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

    /** Throws [IOException] if the socket cannot be bound. */
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        serverSocket = socket
        running = true
        scope.launch(acceptDispatcher) { acceptLoop(socket) }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!running) break
                delay(ACCEPT_BACKOFF_MS)
                continue
            }
            // Keep the accepted socket's traffic off our own tun (no-op if VPN off).
            runCatching { onAccept(client) }
            _activeConnections.update { it + 1 }
            scope.launch(Dispatchers.IO) {
                Socks5Connection(client, auth, resolve, connect) {
                    _activeConnections.update { it - 1 }
                }.handle()
            }
        }
    }

    fun stop() {
        running = false
        closeQuietly(serverSocket)
        serverSocket = null
        scope.cancel()
    }

    companion object {
        const val ACCEPT_BACKOFF_MS = 200L
    }
}
