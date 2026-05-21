package eu.matscheko.pivot

/** Observable state of the egress SOCKS5 proxy, surfaced to the UI and notification. */
sealed interface ServerState {
    data object Off : ServerState
    data object Starting : ServerState
    data class Running(
        val boundAddresses: List<String>,
        val port: Int,
        val connections: Int,
    ) : ServerState
    data class Error(val message: String) : ServerState
}

/** Observable state of the capturing VPN. */
sealed interface VpnState {
    data object Off : VpnState
    data object Starting : VpnState
    data class Running(
        val upstream: String,
        val dnsOverProxy: Boolean,
        /** "socks5" or "http" — the upstream proxy protocol. */
        val upstreamType: String,
    ) : VpnState
    data class Error(val message: String) : VpnState
}
