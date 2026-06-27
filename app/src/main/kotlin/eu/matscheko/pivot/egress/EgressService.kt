package eu.matscheko.pivot.egress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import eu.matscheko.pivot.ADDRESS_ALL
import eu.matscheko.pivot.MainActivity
import eu.matscheko.pivot.NetUtils
import eu.matscheko.pivot.Networks
import eu.matscheko.pivot.R
import eu.matscheko.pivot.ServerState
import eu.matscheko.pivot.control.EngineStatus
import eu.matscheko.pivot.settings.SettingsRepository
import eu.matscheko.pivot.socks.AuthConfig
import eu.matscheko.pivot.socks.Socks5Handshake
import eu.matscheko.pivot.socks.Socks5Server
import eu.matscheko.pivot.vpn.VpnProtector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Foreground service hosting the on-device SOCKS5 egress proxy (microsocks).
 *
 * Its outbound and accepted sockets are `protect()`-ed via [VpnProtector] so that,
 * when the capturing VPN is also running, the egress traffic bypasses our own tun
 * and actually leaves through the phone's real interface (no routing loop).
 */
class EgressService : LifecycleService() {

    private val binder = LocalBinder()
    private val _state = MutableStateFlow<ServerState>(ServerState.Off)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var server: Socks5Server? = null
    private var connectionsJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Remaining 1s re-posts; refilled by [updateNotification], drained by [notifyRunnable]. */
    private var notifyRepostsLeft = 0

    /** Posts the current state, then re-arms itself each second until the budget runs out. */
    private val notifyRunnable = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(_state.value))
            if (notifyRepostsLeft > 0) {
                notifyRepostsLeft--
                mainHandler.postDelayed(this, NOTIFY_REPOST_INTERVAL_MS)
            }
        }
    }

    inner class LocalBinder : Binder() {
        val service: EgressService get() = this@EgressService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Publish every state change process-wide so ControlReceiver's STATUS read-back
        // can report it without binding.
        state.onEach { EngineStatus.egress.set(it) }.launchIn(lifecycleScope)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundWithState(_state.value)

        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server != null) return
        _state.value = ServerState.Starting
        updateNotification()

        lifecycleScope.launch {
            val settings = SettingsRepository(this@EgressService).settings.first()
            val auth = if (settings.egressAuthEnabled) {
                AuthConfig.Password(settings.egressUsername, settings.egressPassword)
            } else {
                AuthConfig.None
            }
            val bind = InetAddress.getByName(settings.egressBindAddress)
            val newServer = Socks5Server(
                bindAddress = bind,
                port = settings.egressPort,
                auth = auth,
                resolve = ::underlyingResolve,
                connect = ::protectedConnect,
                onAccept = { sock -> runCatching { VpnProtector.protect(sock) } },
            )
            try {
                newServer.start()
            } catch (e: Exception) {
                _state.value = ServerState.Error(e.message ?: "Failed to start server")
                updateNotification()
                return@launch
            }
            server = newServer
            val addresses = NetUtils.displayAddresses(settings.egressBindAddress)
            _state.value = ServerState.Running(addresses, settings.egressPort, 0)
            updateNotification()

            connectionsJob = lifecycleScope.launch {
                newServer.activeConnections.collect { count ->
                    val current = _state.value
                    if (current is ServerState.Running) {
                        _state.value = current.copy(connections = count)
                        updateNotification()
                    }
                }
            }
        }
    }

    /** Dial upstream on a socket kept off our own tun, then connect with a timeout. */
    private fun protectedConnect(addr: InetAddress, port: Int): Socket =
        Socket().apply {
            bind(null) // allocate the fd so protect() can mark it
            VpnProtector.protect(this)
            connect(InetSocketAddress(addr, port), Socks5Handshake.CONNECT_TIMEOUT_MS)
        }

    /**
     * Resolve names on the underlying (non-VPN) network. Critical for the
     * same-device pivot: if we used the default resolver while our own VPN is up,
     * the query would be captured by the tun (→ fake IP / blocked resolver). Falls
     * back to the default resolver when no non-VPN network is found (VPN off).
     */
    private fun underlyingResolve(host: String): Array<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = Networks.underlying(cm)
        return try {
            if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host)
        } catch (e: Exception) {
            InetAddress.getAllByName(host)
        }
    }

    private fun stopServer() {
        connectionsJob?.cancel()
        connectionsJob = null
        mainHandler.removeCallbacks(notifyRunnable)
        server?.stop()
        server = null
        _state.value = ServerState.Off
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connectionsJob?.cancel()
        mainHandler.removeCallbacks(notifyRunnable)
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun startForegroundWithState(state: ServerState) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(state), type)
    }

    private fun updateNotification() {
        // Post now, then re-post once a second for ~20s. Connection-count updates
        // normally re-post often enough to refresh past the platform's ~10s
        // background-start notification deferral window on their own, but the timed
        // re-posts guarantee the running state surfaces even when no app ever connects
        // (e.g. a boot start).
        mainHandler.removeCallbacks(notifyRunnable)
        notifyRepostsLeft = NOTIFY_REPOST_COUNT
        notifyRunnable.run()
    }

    private fun buildNotification(state: ServerState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EgressService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = when (state) {
            ServerState.Off -> "Stopped"
            ServerState.Starting -> "Starting…"
            is ServerState.Running -> {
                val where = state.boundAddresses.firstOrNull() ?: ADDRESS_ALL
                val conns = if (state.connections == 1) "1 connection" else "${state.connections} connections"
                "Egress on $where:${state.port} · $conns"
            }
            is ServerState.Error -> "Error: ${state.message}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pivot Proxy — egress proxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_socks)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Egress proxy",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Status of the on-device SOCKS5 egress proxy"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "eu.matscheko.pivot.egress.action.START"
        const val ACTION_STOP = "eu.matscheko.pivot.egress.action.STOP"
        private const val CHANNEL_ID = "egress_status"
        private const val NOTIFICATION_ID = 1
        // Re-post the notification once a second for ~20s after a state change, so it
        // refreshes as soon as the platform's ~10s background-start (boot) FGS
        // notification deferral window lifts.
        private const val NOTIFY_REPOST_INTERVAL_MS = 1_000L
        private const val NOTIFY_REPOST_COUNT = 20
    }
}
