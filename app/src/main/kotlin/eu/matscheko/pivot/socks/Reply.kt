package eu.matscheko.pivot.socks

/** Protocol-level SOCKS5 constants (RFC 1928 / RFC 1929). */
object Socks {
    const val VERSION = 0x05

    // Method-negotiation auth methods.
    const val METHOD_NO_AUTH = 0x00
    const val METHOD_USERNAME_PASSWORD = 0x02
    const val METHOD_NONE_ACCEPTABLE = 0xFF

    // Username/password subnegotiation (RFC 1929). Note: this VER is 0x01, not 0x05.
    const val AUTH_VERSION = 0x01
    const val AUTH_SUCCESS = 0x00
    const val AUTH_FAILURE = 0x01

    // Commands.
    const val CMD_CONNECT = 0x01
    const val CMD_BIND = 0x02
    const val CMD_UDP_ASSOCIATE = 0x03

    // Address types.
    const val ATYP_IPV4 = 0x01
    const val ATYP_DOMAIN = 0x03
    const val ATYP_IPV6 = 0x04
}

/** REP values for the request reply. */
object Reply {
    const val SUCCEEDED = 0x00
    const val GENERAL_FAILURE = 0x01
    const val CONNECTION_NOT_ALLOWED = 0x02
    const val NETWORK_UNREACHABLE = 0x03
    const val HOST_UNREACHABLE = 0x04
    const val CONNECTION_REFUSED = 0x05
    const val TTL_EXPIRED = 0x06
    const val COMMAND_NOT_SUPPORTED = 0x07
    const val ADDRESS_TYPE_NOT_SUPPORTED = 0x08
}

/** Server-side auth configuration chosen in the UI. */
sealed interface AuthConfig {
    data object None : AuthConfig
    data class Password(val username: String, val password: String) : AuthConfig
}
