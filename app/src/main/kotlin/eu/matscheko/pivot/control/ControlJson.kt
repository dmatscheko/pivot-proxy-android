package eu.matscheko.pivot.control

import eu.matscheko.pivot.ServerState
import eu.matscheko.pivot.VpnState
import eu.matscheko.pivot.settings.AppSettings

/**
 * Pure serializers for the adb QUERY_CONFIG (config dump) and STATUS (live engine state)
 * read-back actions. Deliberately free of Android dependencies so they unit-test on
 * the plain JVM; [ControlReceiver] supplies the live state and the consent flag.
 *
 * The config keys mirror the CONFIGURE broadcast extras one-for-one, so a dump reads
 * straight back into `--es/--ei/--ez` flags. The two passwords are never emitted.
 */
internal object ControlJson {

    /** Current persisted settings as JSON. Secrets (egress_pass, upstream_pass) are omitted. */
    fun configJson(s: AppSettings): String = obj(
        "egress_port" to num(s.egressPort),
        "egress_bind" to str(s.egressBindAddress),
        "egress_auth" to bool(s.egressAuthEnabled),
        "egress_user" to str(s.egressUsername),
        // egress_pass intentionally omitted
        "upstream_host" to str(s.upstreamHost),
        "upstream_port" to num(s.upstreamPort),
        "upstream_type" to str(s.upstreamType),
        "upstream_auth" to bool(s.upstreamAuthEnabled),
        "upstream_user" to str(s.upstreamUsername),
        // upstream_pass intentionally omitted
        "dns_over_proxy" to bool(s.dnsOverProxy),
        "direct_use_underlying_dns" to bool(s.directUseUnderlyingDns),
        "direct_dns" to str(s.directDns),
        "direct_dns_port" to num(s.directDnsPort),
        "ipv6" to bool(s.ipv6),
        "bypass_domains" to str(s.bypassDomains),
        "app_filter_mode" to str(s.appFilterMode),
        "app_list" to arr(s.appList),
        "start_egress_on_boot" to bool(s.startEgressOnBoot),
        "start_vpn_on_boot" to bool(s.startVpnOnBoot),
    )

    /** The two engine states as JSON, e.g. `{"vpn":"running","egress":"stopped"}`. */
    fun statusJson(vpn: VpnState, egress: ServerState, vpnConsentGranted: Boolean): String = obj(
        "vpn" to str(vpnStatus(vpn, vpnConsentGranted)),
        "egress" to str(egressStatus(egress)),
    )

    /**
     * VPN status word:
     *  - `running` / `starting` — the capture VPN is up (or coming up).
     *  - `stopped` — not running, but the user has already granted VpnService consent.
     *  - `permission_required` — not running, and consent is not granted; Android's VPN
     *    connection request dialog (from VpnService.prepare()) must be accepted in the app
     *    first (it can't be shown from a broadcast), so VPN_START would be refused.
     *  - `error` — the last start attempt failed.
     */
    fun vpnStatus(state: VpnState, consentGranted: Boolean): String = when (state) {
        VpnState.Off -> if (consentGranted) "stopped" else "permission_required"
        VpnState.Starting -> "starting"
        is VpnState.Running -> "running"
        is VpnState.Error -> "error"
    }

    /** Egress status word: `running`, `starting`, `stopped` or `error`. */
    fun egressStatus(state: ServerState): String = when (state) {
        ServerState.Off -> "stopped"
        ServerState.Starting -> "starting"
        is ServerState.Running -> "running"
        is ServerState.Error -> "error"
    }

    private fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) -> "${str(k)}:$v" }

    private fun num(v: Int): String = v.toString()
    private fun bool(v: Boolean): String = v.toString()
    private fun arr(v: Set<String>): String = v.joinToString(prefix = "[", postfix = "]", separator = ",") { str(it) }

    private fun str(v: String): String = buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
