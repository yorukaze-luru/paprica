package app.webgen.updist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the relay foreground service after device boot when the
 * distributor is enabled (contract §8.5 lifecycle: registrations
 * persist across restarts, and the service re-subscribes all stored
 * topics on start). The notification label persisted by the host's
 * earlier [UpDistributor.start] call is reused automatically.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (UpDistributor.isEnabled(context)) {
            UpDistributor.start(context, null)
        }
    }
}
