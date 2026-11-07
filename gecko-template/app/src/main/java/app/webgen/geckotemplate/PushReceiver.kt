package app.webgen.geckotemplate

import android.content.Context
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush connector 3.3.5 callback surface (Phase-2 proven).
 *
 * The abstract base class (MessagingReceiver.onReceive) resolves the
 * registration instance from the broadcast token, decrypts Web Push
 * payloads with the connector-managed keys (DefaultKeyManager — we
 * deliberately do NOT override getKeyManager), acknowledges messages
 * to the distributor, and then dispatches to these overrides.
 * All real work lives in PushBridge.
 */
class PushReceiver : MessagingReceiver() {

    override fun onNewEndpoint(
        context: Context, endpoint: PushEndpoint, instance: String
    ) {
        PushBridge.attachAppContext(context)
        PushBridge.onNewEndpoint(context, endpoint, instance)
    }

    override fun onMessage(
        context: Context, message: PushMessage, instance: String
    ) {
        PushBridge.attachAppContext(context)
        PushBridge.onMessage(context, message, instance)
    }

    override fun onRegistrationFailed(
        context: Context, reason: FailedReason, instance: String
    ) {
        PushBridge.attachAppContext(context)
        PushBridge.onRegistrationFailed(context, reason, instance)
    }

    override fun onUnregistered(context: Context, instance: String) {
        PushBridge.attachAppContext(context)
        PushBridge.onUnregistered(context, instance)
    }

    override fun onTempUnavailable(context: Context, instance: String) {
        PushBridge.attachAppContext(context)
        PushBridge.onTempUnavailable(context, instance)
    }
}
