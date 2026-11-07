package app.webgen.updist

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Distributor -> connector replies (contract §5). All replies are
 * plain broadcasts addressed by PACKAGE ONLY ([Intent.setPackage] to
 * the client package recorded for the token, never a component —
 * §5.1), because the receiving class inside the client package is
 * the client's own choice.
 *
 * FLAG_INCLUDE_STOPPED_PACKAGES is set so a reply can cold-start a
 * force-stopped client; ntfy's own flag use is UNVERIFIED (U-1), the
 * contract only proves package addressing is what the connector
 * expects.
 */
internal object DistributorReplies {

    private const val ACTION_NEW_ENDPOINT =
        "org.unifiedpush.android.connector.NEW_ENDPOINT"
    private const val ACTION_MESSAGE =
        "org.unifiedpush.android.connector.MESSAGE"
    private const val ACTION_REGISTRATION_FAILED =
        "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    private const val ACTION_UNREGISTERED =
        "org.unifiedpush.android.connector.UNREGISTERED"

    private fun base(ctx: Context, clientPackage: String, action: String): Intent =
        Intent(action).apply {
            setPackage(clientPackage)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

    fun sendNewEndpoint(ctx: Context, reg: DistributorStore.Registration) {
        val intent = base(ctx, reg.clientPackage, ACTION_NEW_ENDPOINT).apply {
            putExtra("token", reg.token)
            putExtra("endpoint", reg.endpoint)
        }
        ctx.sendBroadcast(intent)
        Log.i(UpDistributor.TAG, "NEW_ENDPOINT -> ${reg.clientPackage} (${reg.endpoint})")
    }

    fun sendRegistrationFailed(ctx: Context, clientPackage: String, token: String, reason: String) {
        // `reason` must be a FailedReason enum name verbatim (§5.2):
        // INTERNAL_ERROR / NETWORK / ACTION_REQUIRED / VAPID_REQUIRED.
        val intent = base(ctx, clientPackage, ACTION_REGISTRATION_FAILED).apply {
            putExtra("token", token)
            putExtra("reason", reason)
        }
        ctx.sendBroadcast(intent)
        Log.i(UpDistributor.TAG, "REGISTRATION_FAILED($reason) -> $clientPackage")
    }

    fun sendUnregistered(ctx: Context, reg: DistributorStore.Registration) {
        val intent = base(ctx, reg.clientPackage, ACTION_UNREGISTERED).apply {
            putExtra("token", reg.token)
        }
        ctx.sendBroadcast(intent)
        Log.i(UpDistributor.TAG, "UNREGISTERED -> ${reg.clientPackage}")
    }

    fun sendMessage(
        ctx: Context,
        reg: DistributorStore.Registration,
        bytes: ByteArray,
        messageId: String?
    ) {
        val intent = base(ctx, reg.clientPackage, ACTION_MESSAGE).apply {
            putExtra("token", reg.token)
            // Strictly byte[] (§5.4 / §8.3): a String here would be
            // read as null by the connector and silently dropped.
            putExtra("bytesMessage", bytes)
            // Including `id` makes the connector answer with a
            // MESSAGE_ACK (§5.5), which DistributorReceiver tolerates.
            if (!messageId.isNullOrEmpty()) putExtra("id", messageId)
        }
        ctx.sendBroadcast(intent)
        Log.i(
            UpDistributor.TAG,
            "MESSAGE(${bytes.size}B, id=$messageId) -> ${reg.clientPackage}"
        )
    }
}
