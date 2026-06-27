package eu.matscheko.pivot.control

import eu.matscheko.pivot.ServerState
import eu.matscheko.pivot.VpnState
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide snapshot of each engine's live state, published by the services so
 * other components in the same process — notably [ControlReceiver]'s STATUS read-back —
 * can read it without binding.
 *
 * A cold process (no service running) correctly reports [ServerState.Off] /
 * [VpnState.Off]: a running foreground service is exactly what keeps the process — and
 * these values — alive, so if either engine were up the process (and its last published
 * state) would still be here. The services mirror every state change here from onCreate.
 */
object EngineStatus {
    val egress = AtomicReference<ServerState>(ServerState.Off)
    val vpn = AtomicReference<VpnState>(VpnState.Off)
}
