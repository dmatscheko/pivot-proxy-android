package eu.matscheko.pivot.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import eu.matscheko.pivot.MainActivity
import eu.matscheko.pivot.Networks
import eu.matscheko.pivot.R
import eu.matscheko.pivot.VpnState
import eu.matscheko.pivot.control.EngineStatus
import eu.matscheko.pivot.settings.AppSettings
import eu.matscheko.pivot.settings.SettingsRepository
import eu.matscheko.pivot.vpn.stack.KotlinTunStack
import eu.matscheko.pivot.vpn.stack.TunStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Capturing VPN (Kotlin rewrite of socksdroid's SocksVpnService).
 *
 * Builds a tun and runs a pure-Kotlin userspace TCP/IP stack ([TunStack]) that
 * terminates captured TCP and bridges each flow to the loopback [LocalShim] over
 * SOCKS5 — the in-process replacement for the old `libtun2socks.so`. In fake-IP mode
 * [FakeDns] + [LocalShim] make DNS resolve on the proxy side (DNS-over-SOCKS5).
 * Captured traffic is sent to the configured upstream proxy (e.g. Burp), which can
 * chain back to the device's own [eu.matscheko.pivot.egress.EgressService] to egress
 * through the phone itself.
 *
 * The stack owns the packet loop, so UDP/53 is answered by a direct function call
 * (no `--dnsgw` redirect) and there is no native child process or fd hand-off.
 *
 * Loop avoidance: the app stays ON the VPN, and every socket that must reach the real
 * network is `protect()`-ed — [LocalShim]'s upstream, the egress proxy's sockets (via
 * [VpnProtector]), and the direct-DNS forwarder. The stack→LocalShim hop is loopback
 * and needs no protection; the stack itself touches no socket for the tun.
 */
class PivotVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Drives notification re-posts on the main thread (see [updateNotification]).
     *
     * A service started while the app is in the background — a boot auto-start — has
     * its foreground notification held by the platform for ~10s, and an update posted
     * during that window is captured stale unless something re-posts after it. The VPN
     * transitions to "Running" exactly once, well inside that window, so without later
     * re-posts the notification stays frozen on "Starting…". (The egress service only
     * escapes this naturally when its connection-count updates re-post past the window.)
     */
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

    private val binder = LocalBinder()
    private val _state = MutableStateFlow<VpnState>(VpnState.Off)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private var tun: ParcelFileDescriptor? = null
    private var fakeDns: FakeDns? = null
    private var localShim: LocalShim? = null
    private var tunStack: TunStack? = null

    @Volatile
    private var running = false

    inner class LocalBinder : Binder() {
        val service: PivotVpnService get() = this@PivotVpnService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Publish every state change process-wide so ControlReceiver's STATUS read-back
        // can report it without binding.
        state.onEach { EngineStatus.vpn.set(it) }.launchIn(scope)
    }

    override fun onBind(intent: Intent): IBinder? {
        // The system binds with the VpnService framework action for permission;
        // let the base class handle that. Our UI binds with no action → local binder.
        if (SERVICE_INTERFACE == intent.action) {
            return super.onBind(intent)
        }
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithState(_state.value)

        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                if (!running) startVpn()
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        _state.value = VpnState.Starting
        updateNotification()
        scope.launch {
            try {
                val settings = SettingsRepository(this@PivotVpnService).settings.first()
                val pfd = configure(settings)
                tun = pfd
                start(settings, pfd)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                _state.value = VpnState.Error(e.message ?: "Failed to start VPN")
                updateNotification()
                stopVpn()
            }
        }
    }

    private fun configure(s: AppSettings): ParcelFileDescriptor {
        val b = Builder()
        b.setMtu(MTU)
            .setSession("Pivot Proxy")
            .addAddress(TUN_ADDR, 24)
            .addDnsServer("8.8.8.8")

        if (s.ipv6) {
            b.addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
        }

        Routes.addRoutes(b, "all")

        if (s.dnsOverProxy) {
            // Fake-IP addresses must be routed into the tunnel.
            b.addRoute(FAKE_IP_ROUTE, FAKE_IP_PREFIX)
        }

        // DNS stub: advertised resolver, routed into the tun so the stack intercepts
        // its UDP/53 (the captured app's queries) regardless of the default route.
        b.addRoute("8.8.8.8", 32)

        applyAppFilter(b, s)

        // Keep our own app ON the VPN so FakeDns replies route back into the tun;
        // bypass sockets are protect()-ed individually. (We never disallow self.)
        return b.establish() ?: throw IllegalStateException("VpnService.establish() returned null")
    }

    /**
     * Restrict which apps the VPN captures. INCLUDE = only the listed apps enter the
     * tun (everything else, plus traffic we don't list, goes direct); EXCLUDE = the
     * listed apps bypass the tun entirely. We never put ourselves on the disallow list
     * and always keep ourselves on the allowlist, so FakeDns reply routing keeps
     * working (see the loop-avoidance notes).
     */
    private fun applyAppFilter(b: Builder, s: AppSettings) {
        when (s.appFilterMode) {
            AppSettings.APP_FILTER_INCLUDE -> {
                if (s.appList.isEmpty()) return // empty include = capture all (avoid a dead VPN)
                (s.appList + packageName).forEach { pkg ->
                    runCatching { b.addAllowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "allow app $pkg: ${it.message}") }
                }
            }
            AppSettings.APP_FILTER_EXCLUDE -> {
                s.appList.filter { it != packageName }.forEach { pkg ->
                    runCatching { b.addDisallowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "disallow app $pkg: ${it.message}") }
                }
            }
            else -> {} // APP_FILTER_OFF: capture everything
        }
    }

    private fun start(s: AppSettings, pfd: ParcelFileDescriptor) {
        // The stack only ever talks to our local no-auth shim (loopback). The shim
        // handles the real upstream's protocol (SOCKS5 or HTTP CONNECT), its auth, and
        // per-domain bypass. Routing every flow through it means bypass works in both
        // DNS modes (the stack→shim hop is plain SOCKS5).
        val httpUpstream = s.upstreamType == AppSettings.UPSTREAM_HTTP

        val bypass = s.bypassDomains
            .split(',', '\n', ' ', '\t')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        fun startShim(fakeDnsInstance: FakeDns?, bypassLookup: (String) -> String?) {
            val shim = LocalShim(
                listenPort = LOCAL_PROXY_PORT,
                upstreamHost = s.upstreamHost,
                upstreamPort = s.upstreamPort,
                username = if (s.upstreamAuthEnabled) s.upstreamUsername else null,
                password = if (s.upstreamAuthEnabled) s.upstreamPassword else null,
                fakeDns = fakeDnsInstance,
                httpUpstream = httpUpstream,
                protect = { sock -> protect(sock) },
                bypassDomains = bypass,
                resolve = ::underlyingResolve,
                bypassLookup = bypassLookup,
            )
            shim.start()
            localShim = shim
        }

        // DNS is answered by the stack via a direct function call (no --dnsgw redirect):
        // fake-IP mode hands synthetic A records out of [FakeDns]; direct mode forwards
        // the raw query off the tun and records answers for the bypass reverse lookup.
        val dnsHandler: (ByteArray) -> ByteArray?
        if (s.dnsOverProxy) {
            val fd = FakeDns()
            fakeDns = fd
            startShim(fd, fd::hostnameForIp)
            dnsHandler = { query -> fd.handle(query, query.size) }
        } else {
            val useUnderlying = s.directUseUnderlyingDns
            val manualHost = s.directDns
            val manualPort = s.directDnsPort
            // Reverse cache so bypass-by-domain can match real IPs in direct mode.
            val cache = DnsCache()
            startShim(null, cache::hostnameForIp)
            dnsHandler = { query ->
                forwardDns(query, useUnderlying, manualHost, manualPort)?.also { cache.record(it) }
            }
        }

        // In fake-IP mode, force DNS-over-TLS to fall back to plaintext UDP/53 (which
        // FakeDns answers locally) — unless the user runs strict Private DNS, where
        // blocking :853 would break resolution entirely.
        val rejectDot = s.dnsOverProxy && !privateDnsStrict()
        if (s.dnsOverProxy && privateDnsStrict()) {
            Log.w(TAG, "strict Private DNS is on; fake-IP DNS won't apply (DoT can't be intercepted)")
        }

        val stack = KotlinTunStack(
            pfd = pfd,
            mtu = MTU,
            socksHost = "127.0.0.1",
            socksPort = LOCAL_PROXY_PORT,
            dnsHandler = dnsHandler,
            rejectDnsOverTls = rejectDot,
        )
        stack.start()
        tunStack = stack

        running = true
        VpnProtector.service = this
        _state.value = VpnState.Running(
            upstream = "${s.upstreamHost}:${s.upstreamPort}",
            dnsOverProxy = s.dnsOverProxy,
            upstreamType = s.upstreamType,
        )
        updateNotification()
    }

    /**
     * Resolve a host on the underlying (non-VPN) network, for bypassed connections
     * that must leave the phone directly rather than loop back into the tun. Mirrors
     * [eu.matscheko.pivot.egress.EgressService]'s resolver.
     */
    private fun underlyingResolve(host: String): InetAddress {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = Networks.underlying(cm)
        val addrs = try {
            if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host)
        } catch (e: Exception) {
            InetAddress.getAllByName(host)
        }
        return addrs.first()
    }

    /**
     * Forward one raw DNS query off the tun and return the raw answer. In
     * underlying mode the query is sent on the device's real network to that
     * network's own DNS server; otherwise to the manually configured resolver over
     * a `protect()`-ed socket. Either way it bypasses our capturing tun.
     */
    private fun forwardDns(
        query: ByteArray,
        useUnderlying: Boolean,
        manualHost: String,
        manualPort: Int,
    ): ByteArray? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val underlying = if (useUnderlying) Networks.underlying(cm) else null
        return try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(null)
                val target: InetSocketAddress
                val dns = underlying?.let { Networks.dnsServers(cm, it).firstOrNull() }
                if (underlying != null && dns != null) {
                    underlying.bindSocket(sock)
                    target = InetSocketAddress(dns, 53)
                } else {
                    // Manual resolver, or fallback when no underlying DNS is known.
                    protect(sock)
                    target = InetSocketAddress(InetAddress.getByName(manualHost), manualPort)
                }
                sock.soTimeout = DNS_QUERY_TIMEOUT_MS
                sock.send(DatagramPacket(query, query.size, target))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                buf.copyOf(resp.length)
            }
        } catch (e: Exception) {
            Log.w(TAG, "forwardDns: ${e.message}")
            null
        }
    }

    /**
     * True when the device is in strict Private DNS ("hostname") mode, where every
     * query must go over DNS-over-TLS to a specific resolver. In that mode we must not
     * block :853 (it would break DNS), so fake-IP DNS interception can't apply.
     */
    private fun privateDnsStrict(): Boolean = try {
        android.provider.Settings.Global.getString(contentResolver, "private_dns_mode") == "hostname"
    } catch (e: Exception) {
        false
    }

    private fun stopVpn() {
        running = false
        VpnProtector.service = null
        // Drop any queued notification posts so a late startForeground can't re-promote
        // the service to the foreground after we've torn it down below.
        mainHandler.removeCallbacksAndMessages(null)

        // Stopping the stack closes the tun fd it owns. If the stack never started
        // (e.g. an error between establish() and start()), close the fd ourselves.
        val stack = tunStack
        tunStack = null
        if (stack != null) {
            stack.stop()
        } else {
            try {
                tun?.close()
            } catch (e: Exception) {
                Log.w(TAG, "close tun: ${e.message}")
            }
        }
        tun = null

        localShim?.stop()
        localShim = null
        fakeDns = null

        _state.value = VpnState.Off
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithState(state: VpnState) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(state), type)
    }

    private fun updateNotification() {
        // Post now, synchronously on the calling thread: this keeps the "Starting…" set
        // during onStartCommand inside the platform's background-start notification
        // snapshot (a handler.post would defer it past the snapshot, leaving the stale
        // "Stopped"). The immediate post is dropped while the app is backgrounded, so
        // also re-post once a second for ~20s, which refreshes the notification the
        // moment the ~10s deferral window lifts.
        mainHandler.removeCallbacks(notifyRunnable)
        notifyRepostsLeft = NOTIFY_REPOST_COUNT
        notifyRunnable.run()
    }

    private fun buildNotification(state: VpnState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PivotVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = when (state) {
            VpnState.Off -> "Stopped"
            VpnState.Starting -> "Starting…"
            is VpnState.Running -> {
                val dns = if (state.dnsOverProxy) "DNS via proxy" else "direct DNS"
                val proto = if (state.upstreamType == AppSettings.UPSTREAM_HTTP) "HTTP" else "SOCKS5"
                "Capturing → ${state.upstream} ($proto) · $dns"
            }
            is VpnState.Error -> "Error: ${state.message}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pivot Proxy — VPN capture")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn)
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
                "VPN capture",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Status of the traffic-capturing VPN"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private val TAG = PivotVpnService::class.java.simpleName

        const val ACTION_START = "eu.matscheko.pivot.vpn.action.START"
        const val ACTION_STOP = "eu.matscheko.pivot.vpn.action.STOP"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 2

        private const val MTU = 1500
        // Re-post the notification once a second for ~20s after a state change, so it
        // refreshes as soon as the platform's ~10s background-start (boot) FGS
        // notification deferral window lifts.
        private const val NOTIFY_REPOST_INTERVAL_MS = 1_000L
        private const val NOTIFY_REPOST_COUNT = 20
        private const val DNS_QUERY_TIMEOUT_MS = 5_000
        private const val LOCAL_PROXY_PORT = 1081
        private const val TUN_ADDR = "26.26.26.1"
        private const val FAKE_IP_ROUTE = "198.18.0.0"
        private const val FAKE_IP_PREFIX = 15
    }
}
