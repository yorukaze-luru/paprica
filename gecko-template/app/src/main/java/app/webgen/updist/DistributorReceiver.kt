package app.webgen.updist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The distributor's discovery + registration surface (contract §1.4):
 * an EXPORTED broadcast receiver whose intent-filter carries
 * REGISTER (the action `queryBroadcastReceivers` discovery looks
 * for), UNREGISTER and MESSAGE_ACK (both addressed to this package
 * only, so some exported receiver here must declare them).
 *
 * Wire handling follows §3 (REGISTER), §4 (UNREGISTER) and §5.5
 * (MESSAGE_ACK: no obligations on the distributor — accept and log).
 *
 * Template gating: [UpDistributor.isEnabled] is the baked-config
 * gate (pushMode=self + notifications ON). When it is closed —
 * hub mode, or notifications OFF — REGISTER is NOT accepted: the
 * receiver replies REGISTRATION_FAILED / INTERNAL_ERROR (mirroring
 * Pアプリカ's OFF behaviour) and never starts the relay FGS
 * (the post-register refresh is gated in UpDistributor as well).
 * Lazily starting the FGS on the first accepted registration is
 * part of the template FGS start policy: UpDistributor.refresh
 * attempts startForegroundService, and any platform rejection is
 * caught inside UpDistributor.startService (never a crash).
 */
class DistributorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REGISTER -> onRegister(context, intent)
            ACTION_UNREGISTER -> onUnregister(context, intent)
            ACTION_MESSAGE_ACK -> {
                // §5.5 / U-8: the ack asserts only that the connector
                // dispatched (token, id). Nothing is owed back.
                Log.i(
                    UpDistributor.TAG,
                    "MESSAGE_ACK token=${intent.getStringExtra("token")} " +
                        "id=${intent.getStringExtra("id")}"
                )
            }
            else -> Log.w(UpDistributor.TAG, "unknown action: ${intent.action}")
        }
    }

    private fun onRegister(context: Context, intent: Intent) {
        // §3.2 extras: token (String), features (String[] — sent,
        // contains BYTES_MESSAGE, which this distributor honours by
        // always delivering byte[] payloads), application (String),
        // pi (PendingIntent — accepted and ignored, §3.3 / U-5),
        // optional message, optional vapid.
        val token = intent.getStringExtra("token")
        val application = intent.getStringExtra("application")
        if (token.isNullOrEmpty() || application.isNullOrEmpty()) {
            Log.w(UpDistributor.TAG, "REGISTER without token/application; dropped")
            return
        }
        val features = try {
            intent.getStringArrayExtra("features")?.toList()
        } catch (e: Exception) {
            null
        }
        Log.i(
            UpDistributor.TAG,
            "REGISTER token=$token app=$application features=$features"
        )

        if (!UpDistributor.isEnabled(context)) {
            // Gate closed (hub mode / notifications OFF / config
            // unreadable): fail cleanly so the client sees a
            // defined outcome instead of silence (§5.2).
            DistributorReplies.sendRegistrationFailed(
                context, application, token, "INTERNAL_ERROR"
            )
            return
        }

        // VAPID stance (§7): the extra is optional and is NEVER a
        // hard requirement here — registrations with a VAPID key and
        // without one are both accepted; the value is only stored
        // with the registration.
        val reg = DistributorStore.register(
            ctx = context,
            token = token,
            clientPackage = application,
            message = intent.getStringExtra("message"),
            vapid = intent.getStringExtra("vapid")
        )
        DistributorReplies.sendNewEndpoint(context, reg)
        // Make sure the relay stream covers the (possibly new) topic.
        UpDistributor.refresh(context)
    }

    private fun onUnregister(context: Context, intent: Intent) {
        // §4.2 extras: token + pi (pi unused). Unknown tokens are
        // tolerated silently (§4.3 / U-6).
        val token = intent.getStringExtra("token")
        if (token.isNullOrEmpty()) return
        val removed = DistributorStore.remove(context, token)
        if (removed != null) {
            DistributorReplies.sendUnregistered(context, removed)
            UpDistributor.refresh(context)
        } else {
            Log.i(UpDistributor.TAG, "UNREGISTER for unknown token=$token; ignored")
        }
    }

    companion object {
        const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
        const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
        const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    }
}
