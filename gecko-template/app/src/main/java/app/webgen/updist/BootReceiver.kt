package app.webgen.updist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the relay foreground service after device boot — template
 * adaptation of the shared module's boot receiver.
 *
 * FGS start policy (template, documented in PORT-REPORT): BOOT
 * restarts the relay ONLY when (a) the baked-config gate is open
 * (pushMode=self + notifications ON — see [UpDistributor.isEnabled])
 * AND (b) at least one registration persists. With no registrations
 * there is nothing to relay, so no FGS is started at boot. The
 * notification label is computed at runtime from the app's own
 * application label (see [UpDistributor.notificationLabel]), so no
 * persisted label is needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!UpDistributor.isEnabled(context)) return
        if (DistributorStore.all(context).isEmpty()) return
        UpDistributor.start(context, null)
    }
}
