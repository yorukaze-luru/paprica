package app.webgen.updist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * UnifiedPush distributor embedded in this app (contract:
 * DISTRIBUTOR-CONTRACT.md, connector 3.3.5).
 *
 * This package (`app.webgen.updist`) is SELF-CONTAINED: it has no
 * references to any of the host app's UI / history / generator
 * classes, so it can be lifted verbatim into another app (e.g. the
 * Gecko template for "self" distributor mode). The only host-specific
 * inputs are:
 *  - the host [Context] / its own package (used for broadcasts,
 *    preferences and the foreground-service intent), and
 *  - the persistent-notification label string, handed to [start]
 *    once by the host and then persisted inside this package's own
 *    preferences so boot / service restarts can reuse it.
 *
 * Facade used by the host app: [isEnabled], [setEnabled], [start],
 * [stop], [refresh], [status].
 */
object UpDistributor {

    const val TAG = "UpDistributor"

    // Connection states reported by [status] (host UI maps these to
    // display strings).
    const val STATE_STOPPED = "stopped"
    const val STATE_WAITING = "waiting"       // enabled, no registrations yet
    const val STATE_CONNECTING = "connecting" // stream connecting / backing off
    const val STATE_CONNECTED = "connected"

    private const val PREFS_SETTINGS = "updist_settings"
    private const val KEY_ENABLED = "distributor_enabled"
    private const val KEY_NOTIF_LABEL = "notification_label"

    private const val PREFS_STATUS = "updist_status"
    private const val KEY_STATE = "connection_state"

    private const val DEFAULT_LABEL = "プッシュ受信待機中"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    /** The persistent-notification label persisted by the host's last
     *  [start] call (or the package default before the first one). */
    fun notificationLabel(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_NOTIF_LABEL, null) ?: DEFAULT_LABEL

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            start(ctx, null)
        } else {
            stop(ctx)
        }
    }

    /**
     * Starts (or no-ops into) the relay foreground service when the
     * distributor is enabled. [notificationLabel], when non-null, is
     * persisted and used for the persistent notification.
     */
    fun start(ctx: Context, notificationLabel: String?) {
        if (notificationLabel != null) {
            ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NOTIF_LABEL, notificationLabel).apply()
        }
        if (!isEnabled(ctx)) return
        startService(ctx, NtfyRelayService.ACTION_RUN)
    }

    /**
     * Tells a running relay service that the registration set changed
     * so it re-opens its stream with the current topic list. Starts
     * the service when it is not running yet.
     */
    fun refresh(ctx: Context) {
        if (!isEnabled(ctx)) return
        startService(ctx, NtfyRelayService.ACTION_REFRESH)
    }

    fun stop(ctx: Context) {
        try {
            ctx.stopService(Intent(ctx, NtfyRelayService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stopService failed: ${e.message}")
        }
        setConnectionState(ctx, STATE_STOPPED)
    }

    private fun startService(ctx: Context, action: String) {
        try {
            val intent = Intent(ctx, NtfyRelayService::class.java).setAction(action)
            // minSdk is 26 for the hosts of this package, so the
            // foreground-service start API is always available.
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            // Background foreground-service starts can be rejected by
            // the platform (e.g. Android 12+ restrictions when the
            // broadcast that triggered us confers no exemption). The
            // service is also started from the host UI and at boot,
            // so a rejected opportunistic start is not fatal.
            Log.w(TAG, "startForegroundService($action) failed: ${e.message}")
        }
    }

    internal fun setConnectionState(ctx: Context, state: String) {
        ctx.getSharedPreferences(PREFS_STATUS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, state).apply()
    }

    data class Status(
        val enabled: Boolean,
        val connectionState: String,
        val registeredAppCount: Int,
        /** Distinct client packages; one app may hold several tokens. */
        val registeredPackageCount: Int
    )

    fun status(ctx: Context): Status {
        val enabled = isEnabled(ctx)
        val state = if (!enabled) {
            STATE_STOPPED
        } else {
            ctx.getSharedPreferences(PREFS_STATUS, Context.MODE_PRIVATE)
                .getString(KEY_STATE, STATE_WAITING) ?: STATE_WAITING
        }
        val regs = DistributorStore.all(ctx)
        return Status(
            enabled = enabled,
            connectionState = state,
            registeredAppCount = regs.size,
            registeredPackageCount = regs.map { it.clientPackage }.distinct().size
        )
    }
}
