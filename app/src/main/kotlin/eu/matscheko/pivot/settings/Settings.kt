package eu.matscheko.pivot.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import eu.matscheko.pivot.ADDRESS_ALL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Single settings model for both engines.
 *
 *  - egress*  → the on-device SOCKS5 listener (microsocks).
 *  - upstream* → the interception proxy the VPN sends captured traffic to (e.g. Burp).
 *  - vpn behaviour → fake-IP DNS, direct DNS, per-app, ipv6 (from socksdroid Profile).
 */
data class AppSettings(
    // Egress proxy (microsocks)
    val egressPort: Int = DEFAULT_EGRESS_PORT,
    val egressBindAddress: String = ADDRESS_ALL,
    val egressAuthEnabled: Boolean = false,
    val egressUsername: String = "",
    val egressPassword: String = "",
    // VPN upstream proxy (e.g. Burp)
    val upstreamHost: String = "127.0.0.1",
    val upstreamPort: Int = DEFAULT_UPSTREAM_PORT,
    val upstreamType: String = UPSTREAM_SOCKS5,   // SOCKS5 or HTTP CONNECT (Burp)
    val upstreamAuthEnabled: Boolean = false,
    val upstreamUsername: String = "",
    val upstreamPassword: String = "",
    // Domains that bypass the upstream proxy and go straight to the internet
    // (comma/space/newline separated). Suffix-matched; only effective in fake-IP
    // (DNS-over-proxy) mode, where the hostname is known to LocalShim.
    val bypassDomains: String = "",
    // VPN behaviour
    val dnsOverProxy: Boolean = true,        // fake-IP mode (preserves DNS-over-SOCKS5)
    // Direct-DNS mode (dnsOverProxy = false): resolve via the underlying network's
    // own DNS servers, or, when that is off, the manual resolver below.
    val directUseUnderlyingDns: Boolean = true,
    val directDns: String = "8.8.8.8",
    val directDnsPort: Int = 53,
    val ipv6: Boolean = false,
    // Per-app filtering of what the VPN captures (package names).
    //  - APP_FILTER_OFF: capture every app.
    //  - APP_FILTER_INCLUDE: capture only [appList] (everything else goes direct).
    //  - APP_FILTER_EXCLUDE: capture everything except [appList].
    val appFilterMode: String = APP_FILTER_OFF,
    val appList: Set<String> = emptySet(),
    // Lifecycle
    val startEgressOnBoot: Boolean = false,
    // Opportunistic VPN auto-start: only fires at boot when VpnService consent is
    // already granted (VpnService.prepare() == null); otherwise it is skipped, since
    // the consent dialog cannot be shown from a boot receiver.
    val startVpnOnBoot: Boolean = false,
) {
    companion object {
        const val DEFAULT_EGRESS_PORT = 1080
        const val DEFAULT_UPSTREAM_PORT = 8080
        const val UPSTREAM_SOCKS5 = "socks5"
        const val UPSTREAM_HTTP = "http"
        const val APP_FILTER_OFF = "off"
        const val APP_FILTER_INCLUDE = "include"
        const val APP_FILTER_EXCLUDE = "exclude"
    }
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.EGRESS_PORT] = next.egressPort
            prefs[Keys.EGRESS_BIND] = next.egressBindAddress
            prefs[Keys.EGRESS_AUTH] = next.egressAuthEnabled
            prefs[Keys.EGRESS_USER] = next.egressUsername
            prefs[Keys.EGRESS_PASS] = next.egressPassword
            prefs[Keys.UPSTREAM_HOST] = next.upstreamHost
            prefs[Keys.UPSTREAM_PORT] = next.upstreamPort
            prefs[Keys.UPSTREAM_TYPE] = next.upstreamType
            prefs[Keys.UPSTREAM_AUTH] = next.upstreamAuthEnabled
            prefs[Keys.UPSTREAM_USER] = next.upstreamUsername
            prefs[Keys.UPSTREAM_PASS] = next.upstreamPassword
            prefs[Keys.BYPASS_DOMAINS] = next.bypassDomains
            prefs[Keys.DNS_OVER_PROXY] = next.dnsOverProxy
            prefs[Keys.DIRECT_USE_UNDERLYING_DNS] = next.directUseUnderlyingDns
            prefs[Keys.DIRECT_DNS] = next.directDns
            prefs[Keys.DIRECT_DNS_PORT] = next.directDnsPort
            prefs[Keys.IPV6] = next.ipv6
            prefs[Keys.APP_FILTER_MODE] = next.appFilterMode
            prefs[Keys.APP_LIST] = next.appList
            prefs[Keys.START_EGRESS_ON_BOOT] = next.startEgressOnBoot
            prefs[Keys.START_VPN_ON_BOOT] = next.startVpnOnBoot
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        egressPort = this[Keys.EGRESS_PORT] ?: AppSettings.DEFAULT_EGRESS_PORT,
        egressBindAddress = this[Keys.EGRESS_BIND] ?: ADDRESS_ALL,
        egressAuthEnabled = this[Keys.EGRESS_AUTH] ?: false,
        egressUsername = this[Keys.EGRESS_USER] ?: "",
        egressPassword = this[Keys.EGRESS_PASS] ?: "",
        upstreamHost = this[Keys.UPSTREAM_HOST] ?: "127.0.0.1",
        upstreamPort = this[Keys.UPSTREAM_PORT] ?: AppSettings.DEFAULT_UPSTREAM_PORT,
        upstreamType = this[Keys.UPSTREAM_TYPE] ?: AppSettings.UPSTREAM_SOCKS5,
        upstreamAuthEnabled = this[Keys.UPSTREAM_AUTH] ?: false,
        upstreamUsername = this[Keys.UPSTREAM_USER] ?: "",
        upstreamPassword = this[Keys.UPSTREAM_PASS] ?: "",
        bypassDomains = this[Keys.BYPASS_DOMAINS] ?: "",
        dnsOverProxy = this[Keys.DNS_OVER_PROXY] ?: true,
        directUseUnderlyingDns = this[Keys.DIRECT_USE_UNDERLYING_DNS] ?: true,
        directDns = this[Keys.DIRECT_DNS] ?: "8.8.8.8",
        directDnsPort = this[Keys.DIRECT_DNS_PORT] ?: 53,
        ipv6 = this[Keys.IPV6] ?: false,
        appFilterMode = this[Keys.APP_FILTER_MODE] ?: AppSettings.APP_FILTER_OFF,
        appList = this[Keys.APP_LIST] ?: emptySet(),
        startEgressOnBoot = this[Keys.START_EGRESS_ON_BOOT] ?: false,
        startVpnOnBoot = this[Keys.START_VPN_ON_BOOT] ?: false,
    )

    private object Keys {
        val EGRESS_PORT = intPreferencesKey("egress_port")
        val EGRESS_BIND = stringPreferencesKey("egress_bind")
        val EGRESS_AUTH = booleanPreferencesKey("egress_auth")
        val EGRESS_USER = stringPreferencesKey("egress_user")
        val EGRESS_PASS = stringPreferencesKey("egress_pass")
        val UPSTREAM_HOST = stringPreferencesKey("upstream_host")
        val UPSTREAM_PORT = intPreferencesKey("upstream_port")
        val UPSTREAM_TYPE = stringPreferencesKey("upstream_type")
        val UPSTREAM_AUTH = booleanPreferencesKey("upstream_auth")
        val UPSTREAM_USER = stringPreferencesKey("upstream_user")
        val UPSTREAM_PASS = stringPreferencesKey("upstream_pass")
        val BYPASS_DOMAINS = stringPreferencesKey("bypass_domains")
        val DNS_OVER_PROXY = booleanPreferencesKey("dns_over_proxy")
        val DIRECT_USE_UNDERLYING_DNS = booleanPreferencesKey("direct_use_underlying_dns")
        val DIRECT_DNS = stringPreferencesKey("direct_dns")
        val DIRECT_DNS_PORT = intPreferencesKey("direct_dns_port")
        val IPV6 = booleanPreferencesKey("ipv6")
        val APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
        val APP_LIST = stringSetPreferencesKey("app_list")
        val START_EGRESS_ON_BOOT = booleanPreferencesKey("start_egress_on_boot")
        val START_VPN_ON_BOOT = booleanPreferencesKey("start_vpn_on_boot")
    }
}
