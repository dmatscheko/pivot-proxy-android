package eu.matscheko.pivot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import eu.matscheko.pivot.egress.EgressService
import eu.matscheko.pivot.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the egress proxy on boot when enabled. The VPN is intentionally not
 * auto-started: VpnService requires foreground user consent that cannot be granted
 * from a boot receiver.
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
            } finally {
                pendingResult.finish()
            }
        }
    }
}
