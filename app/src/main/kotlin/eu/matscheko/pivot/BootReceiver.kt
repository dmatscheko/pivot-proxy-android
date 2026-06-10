package eu.matscheko.pivot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import eu.matscheko.pivot.egress.EgressService
import eu.matscheko.pivot.settings.SettingsRepository
import eu.matscheko.pivot.vpn.PivotVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the engines on boot when their respective "start on boot" toggles are on.
 *
 * The egress proxy always starts. The VPN starts *opportunistically*: VpnService
 * consent cannot be granted from a boot receiver (there is no Activity to show the
 * system dialog), so we only start it when consent is already in place —
 * [VpnService.prepare] returns null. Consent persists across reboots once granted,
 * so after the user confirms it in-app once, every later boot auto-starts silently.
 * When consent is missing we skip rather than fail; the user grants it by opening the
 * app once, after which boot-start works on its own.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).settings.first()
                if (settings.startEgressOnBoot) {
                    val serviceIntent = Intent(appContext, EgressService::class.java)
                        .setAction(EgressService.ACTION_START)
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                }
                if (settings.startVpnOnBoot) {
                    if (VpnService.prepare(appContext) == null) {
                        val vpnIntent = Intent(appContext, PivotVpnService::class.java)
                            .setAction(PivotVpnService.ACTION_START)
                        ContextCompat.startForegroundService(appContext, vpnIntent)
                    } else {
                        Log.i(TAG, "VPN boot-start skipped: consent not granted (open the app once)")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
