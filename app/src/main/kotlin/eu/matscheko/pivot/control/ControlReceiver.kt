package eu.matscheko.pivot.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import eu.matscheko.pivot.egress.EgressService
import eu.matscheko.pivot.settings.AppSettings
import eu.matscheko.pivot.settings.SettingsRepository
import eu.matscheko.pivot.vpn.PivotVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * adb-controllable entry point for automation from an attached PC.
 *
 * Exported, but guarded in the manifest by [android.permission.DUMP] — a permission the
 * `shell` user (adb) and the system hold, but which ordinary apps cannot be granted. So
 * in practice only `adb shell am broadcast …` (and the platform) can drive it. (Same
 * gating pattern AndroidX's ProfileInstallReceiver uses.)
 *
 * Actions (under the `eu.matscheko.pivot.action.` namespace): CONFIGURE, EGRESS_START,
 * EGRESS_STOP, VPN_START, VPN_STOP. Any action may also carry configuration extras (see
 * the EXTRA_* keys); when present they are persisted *before* the start/stop runs, so a
 * single broadcast can configure-and-start. Each engine reads its settings when it
 * starts, so configure while it's stopped (or restart it) for changes to take effect.
 *
 * The VPN only starts when consent is already granted ([VpnService.prepare] == null) —
 * the system consent dialog cannot be shown from a broadcast; grant it once in the app.
 *
 * For ordered broadcasts (which `am broadcast` issues) the outcome is returned as the
 * result data, so it shows up in adb's "Broadcast completed: result=..., data=..." line;
 * it is also logged under the "PivotControl" tag.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == null || !action.startsWith(ACTION_PREFIX)) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var status = "ok"
            try {
                if (CONFIG_KEYS.any { intent.hasExtra(it) }) {
                    SettingsRepository(appContext).update { applyConfig(it, intent) }
                }
                when (action) {
                    ACTION_CONFIGURE -> Unit // config already applied above
                    ACTION_EGRESS_START -> start(appContext, EgressService::class.java, EgressService.ACTION_START)
                    ACTION_EGRESS_STOP -> start(appContext, EgressService::class.java, EgressService.ACTION_STOP)
                    ACTION_VPN_START -> status = startVpn(appContext)
                    ACTION_VPN_STOP -> start(appContext, PivotVpnService::class.java, PivotVpnService.ACTION_STOP)
                    else -> status = "error: unknown action $action"
                }
            } catch (e: Exception) {
                Log.e(TAG, "control action failed", e)
                status = "error: ${e.message}"
            } finally {
                Log.i(TAG, "$action -> $status")
                runCatching { pending.setResultData(status) }
                pending.finish()
            }
        }
    }

    private fun startVpn(ctx: Context): String {
        // Consent can't be granted from a broadcast; only start when it already is.
        if (VpnService.prepare(ctx) != null) {
            return "error: VPN consent not granted; start it once from the app first"
        }
        start(ctx, PivotVpnService::class.java, PivotVpnService.ACTION_START)
        return "ok"
    }

    /** Start (or signal stop to) a foreground service; satisfies the FGS start contract either way. */
    private fun start(ctx: Context, cls: Class<*>, action: String) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, cls).setAction(action))
    }

    /** Overlay any provided config extras onto the current settings; absent extras are left untouched. */
    private fun applyConfig(s: AppSettings, i: Intent): AppSettings {
        var n = s
        // Egress
        port(i, EXTRA_EGRESS_PORT)?.let { n = n.copy(egressPort = it) }
        i.getStringExtra(EXTRA_EGRESS_BIND)?.let { n = n.copy(egressBindAddress = it) }
        bool(i, EXTRA_EGRESS_AUTH)?.let { n = n.copy(egressAuthEnabled = it) }
        i.getStringExtra(EXTRA_EGRESS_USER)?.let { n = n.copy(egressUsername = it) }
        i.getStringExtra(EXTRA_EGRESS_PASS)?.let { n = n.copy(egressPassword = it) }
        // VPN upstream proxy
        i.getStringExtra(EXTRA_UPSTREAM_HOST)?.let { n = n.copy(upstreamHost = it) }
        port(i, EXTRA_UPSTREAM_PORT)?.let { n = n.copy(upstreamPort = it) }
        i.getStringExtra(EXTRA_UPSTREAM_TYPE)?.lowercase()?.let {
            if (it == AppSettings.UPSTREAM_SOCKS5 || it == AppSettings.UPSTREAM_HTTP) n = n.copy(upstreamType = it)
            else Log.w(TAG, "ignoring upstream_type=$it (expected socks5 or http)")
        }
        bool(i, EXTRA_UPSTREAM_AUTH)?.let { n = n.copy(upstreamAuthEnabled = it) }
        i.getStringExtra(EXTRA_UPSTREAM_USER)?.let { n = n.copy(upstreamUsername = it) }
        i.getStringExtra(EXTRA_UPSTREAM_PASS)?.let { n = n.copy(upstreamPassword = it) }
        // VPN behaviour
        bool(i, EXTRA_DNS_OVER_PROXY)?.let { n = n.copy(dnsOverProxy = it) }
        bool(i, EXTRA_DIRECT_USE_UNDERLYING_DNS)?.let { n = n.copy(directUseUnderlyingDns = it) }
        i.getStringExtra(EXTRA_DIRECT_DNS)?.let { n = n.copy(directDns = it) }
        port(i, EXTRA_DIRECT_DNS_PORT)?.let { n = n.copy(directDnsPort = it) }
        bool(i, EXTRA_IPV6)?.let { n = n.copy(ipv6 = it) }
        i.getStringExtra(EXTRA_BYPASS_DOMAINS)?.let { n = n.copy(bypassDomains = it) }
        i.getStringExtra(EXTRA_APP_FILTER_MODE)?.lowercase()?.let {
            if (it in APP_FILTER_MODES) n = n.copy(appFilterMode = it)
            else Log.w(TAG, "ignoring app_filter_mode=$it (expected off, include or exclude)")
        }
        i.getStringArrayExtra(EXTRA_APP_LIST)?.let { n = n.copy(appList = it.toSet()) }
        // Lifecycle
        bool(i, EXTRA_START_EGRESS_ON_BOOT)?.let { n = n.copy(startEgressOnBoot = it) }
        bool(i, EXTRA_START_VPN_ON_BOOT)?.let { n = n.copy(startVpnOnBoot = it) }
        return n
    }

    private fun bool(i: Intent, key: String): Boolean? =
        if (i.hasExtra(key)) i.getBooleanExtra(key, false) else null

    private fun port(i: Intent, key: String): Int? {
        if (!i.hasExtra(key)) return null
        val v = i.getIntExtra(key, -1)
        if (v in 1..65535) return v
        Log.w(TAG, "ignoring $key=$v (expected 1..65535; pass it with --ei)")
        return null
    }

    companion object {
        private const val TAG = "PivotControl"

        private const val ACTION_PREFIX = "eu.matscheko.pivot.action."
        const val ACTION_CONFIGURE = ACTION_PREFIX + "CONFIGURE"
        const val ACTION_EGRESS_START = ACTION_PREFIX + "EGRESS_START"
        const val ACTION_EGRESS_STOP = ACTION_PREFIX + "EGRESS_STOP"
        const val ACTION_VPN_START = ACTION_PREFIX + "VPN_START"
        const val ACTION_VPN_STOP = ACTION_PREFIX + "VPN_STOP"

        // Config extras. Strings → --es, ints → --ei, booleans → --ez, string arrays → --esa.
        private const val EXTRA_EGRESS_PORT = "egress_port"
        private const val EXTRA_EGRESS_BIND = "egress_bind"
        private const val EXTRA_EGRESS_AUTH = "egress_auth"
        private const val EXTRA_EGRESS_USER = "egress_user"
        private const val EXTRA_EGRESS_PASS = "egress_pass"
        private const val EXTRA_UPSTREAM_HOST = "upstream_host"
        private const val EXTRA_UPSTREAM_PORT = "upstream_port"
        private const val EXTRA_UPSTREAM_TYPE = "upstream_type"
        private const val EXTRA_UPSTREAM_AUTH = "upstream_auth"
        private const val EXTRA_UPSTREAM_USER = "upstream_user"
        private const val EXTRA_UPSTREAM_PASS = "upstream_pass"
        private const val EXTRA_DNS_OVER_PROXY = "dns_over_proxy"
        private const val EXTRA_DIRECT_USE_UNDERLYING_DNS = "direct_use_underlying_dns"
        private const val EXTRA_DIRECT_DNS = "direct_dns"
        private const val EXTRA_DIRECT_DNS_PORT = "direct_dns_port"
        private const val EXTRA_IPV6 = "ipv6"
        private const val EXTRA_BYPASS_DOMAINS = "bypass_domains"
        private const val EXTRA_APP_FILTER_MODE = "app_filter_mode"
        private const val EXTRA_APP_LIST = "app_list"
        private const val EXTRA_START_EGRESS_ON_BOOT = "start_egress_on_boot"
        private const val EXTRA_START_VPN_ON_BOOT = "start_vpn_on_boot"

        private val APP_FILTER_MODES = setOf(
            AppSettings.APP_FILTER_OFF,
            AppSettings.APP_FILTER_INCLUDE,
            AppSettings.APP_FILTER_EXCLUDE,
        )

        private val CONFIG_KEYS = listOf(
            EXTRA_EGRESS_PORT, EXTRA_EGRESS_BIND, EXTRA_EGRESS_AUTH, EXTRA_EGRESS_USER, EXTRA_EGRESS_PASS,
            EXTRA_UPSTREAM_HOST, EXTRA_UPSTREAM_PORT, EXTRA_UPSTREAM_TYPE, EXTRA_UPSTREAM_AUTH,
            EXTRA_UPSTREAM_USER, EXTRA_UPSTREAM_PASS, EXTRA_DNS_OVER_PROXY, EXTRA_DIRECT_USE_UNDERLYING_DNS,
            EXTRA_DIRECT_DNS, EXTRA_DIRECT_DNS_PORT, EXTRA_IPV6, EXTRA_BYPASS_DOMAINS, EXTRA_APP_FILTER_MODE,
            EXTRA_APP_LIST, EXTRA_START_EGRESS_ON_BOOT, EXTRA_START_VPN_ON_BOOT,
        )
    }
}
