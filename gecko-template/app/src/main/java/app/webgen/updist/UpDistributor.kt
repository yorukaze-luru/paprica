package app.webgen.updist

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject

/**
 * UnifiedPush distributor embedded in the Gecko template
 * (template v3 self-embedding, Phase 5). Copied from the shared
 * module that ships inside Pアプリカ (package `app.webgen.updist`,
 * 6 files, self-contained) and adapted ONLY for the template
 * context:
 *
 *  (1) MODE GATING — there is no Settings toggle in the template.
 *      [isEnabled] is NOT a user preference here; it is the
 *      baked-config gate: the distributor is enabled iff
 *      assets/site_config.json (read exactly the way PushBridge /
 *      MainActivity read it — assets.open + JSONObject) carries
 *      "pushMode": "self" AND notifications are ON (top-level
 *      "notifications" true AND, when present,
 *      "permissions.notifications" true — the generator writes
 *      both in sync). Any read failure, hub mode, or
 *      notifications OFF fails CLOSED (disabled). Consequences:
 *       - hub mode: DistributorReceiver replies
 *         REGISTRATION_FAILED / INTERNAL_ERROR (mirroring
 *         Pアプリカ's OFF behaviour) and the FGS never starts;
 *       - notifications OFF: the same — no registration is
 *         accepted and no code path starts the FGS, regardless
 *         of the FOREGROUND_SERVICE permission remaining
 *         declared in the manifest (see PORT-REPORT permission
 *         decision: that permission is structural and is NOT
 *         tied to the notifications toggle in code or in the
 *         generator's renaming; this gate is the enforcement).
 *  (2) FGS LABEL — [notificationLabel] is computed at runtime
 *      from the host app's own application label via
 *      PackageManager + "：プッシュ受信待機中", so a generated
 *      app shows "<生成アプリ名>：プッシュ受信待機中". No string
 *      templating, no persisted label is used for display (the
 *      persisted value is kept only as a fallback if the
 *      PackageManager lookup fails).
 *  (3) No Settings/status UI — [status] / [setEnabled] are kept
 *      only so the copied module compiles unchanged in shape;
 *      setEnabled's preference is ANDed into the gate and
 *      defaults to true, so it never enables anything the
 *      baked config forbids.
 *
 * Facade: [isEnabled], [setEnabled], [start], [stop], [refresh],
 * [status], [notificationLabel].
 */
object UpDistributor {

    const val TAG = "UpDistributor"

    const val STATE_STOPPED = "stopped"
    const val STATE_WAITING = "waiting"
    const val STATE_CONNECTING = "connecting"
    const val STATE_CONNECTED = "connected"

    private const val PREFS_SETTINGS = "updist_settings"
    private const val KEY_ENABLED = "distributor_enabled"
    private const val KEY_NOTIF_LABEL = "notification_label"

    private const val PREFS_STATUS = "updist_status"
    private const val KEY_STATE = "connection_state"

    private const val DEFAULT_LABEL = "プッシュ受信待機中"
    private const val LABEL_SUFFIX = "：プッシュ受信待機中"

    @Volatile
    private var cachedGate: Boolean? = null

    /**
     * Baked-config gate (see class doc). Cached per process: the
     * config is an APK asset and cannot change at runtime.
     */
    fun isEnabled(ctx: Context): Boolean {
        cachedGate?.let { gate ->
            // The preference AND is cheap; keep it live.
            val pref = ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
            return gate && pref
        }
        val gate = computeGate(ctx)
        cachedGate = gate
        val pref = ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
        return gate && pref
    }

    private fun computeGate(ctx: Context): Boolean {
        return try {
            val text = ctx.applicationContext.assets.open("site_config.json")
                .bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val mode = json.optString("pushMode", "hub")
            if (mode != "self") return false
            if (!json.optBoolean("notifications", false)) return false
            val perms = json.optJSONObject("permissions")
            if (perms != null && perms.has("notifications") &&
                !perms.optBoolean("notifications", false)
            ) return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "site_config gate read failed, distributor disabled: $e")
            false
        }
    }

    /** Test/debug hook: forget the cached gate (not used in prod paths). */
    internal fun clearGateCache() {
        cachedGate = null
    }

    /**
     * Persistent-notification label: the host app's own
     * application label (as patched into the generated app) +
     * "：プッシュ受信待機中", read via PackageManager at runtime.
     */
    fun notificationLabel(ctx: Context): String {
        try {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(ctx.packageName, 0)
            val label = pm.getApplicationLabel(ai).toString()
            if (label.isNotEmpty()) return label + LABEL_SUFFIX
        } catch (e: Exception) {
            Log.w(TAG, "application label lookup failed: $e")
        }
        val persisted = ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_NOTIF_LABEL, null)
        return if (!persisted.isNullOrEmpty()) persisted else DEFAULT_LABEL
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            start(ctx, null)
        } else {
            stop(ctx)
        }
    }

    fun start(ctx: Context, notificationLabel: String?) {
        if (notificationLabel != null) {
            ctx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NOTIF_LABEL, notificationLabel).apply()
        }
        if (!isEnabled(ctx)) return
        startService(ctx, NtfyRelayService.ACTION_RUN)
    }

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
        // Defense in depth: never attempt an FGS start when gated off,
        // even if a caller forgot to check isEnabled first.
        if (!isEnabled(ctx)) return
        try {
            val intent = Intent(ctx, NtfyRelayService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            // Background FGS starts can be rejected (Android 12+
            // restrictions, SecurityException / IllegalStateException).
            // Never crash the host app: the service is also started
            // from MainActivity (foreground, unrestricted) and at
            // BOOT_COMPLETED when registrations exist.
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
