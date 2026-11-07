package app.webgen.geckotemplate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import org.mozilla.geckoview.WebPushDelegate
import org.mozilla.geckoview.WebPushSubscription
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Template v2 (Phase 3): Web Push transport for the Gecko template,
 * productized from the device-proven Phase-2 spike
 * (gecko-push/PUSH-TEST-REPORT.md, incl. fix rounds 1-3 and the
 * 2026-11-07 on-device full-chain success).
 *
 * Design (identical to the proven spike):
 * - GeckoView's WebPushController.onPushEvent(scope, data) expects
 *   the UNENCRYPTED payload. The UnifiedPush connector 3.3.5
 *   managed-key flow matches exactly: register() uses a
 *   DefaultKeyManager that generates the Web Push key pair + auth
 *   secret per instance; onNewEndpoint reports them as
 *   PushEndpoint.pubKeySet; incoming messages are decrypted by the
 *   connector (Tink fixed_webpush) before MessagingReceiver.onMessage
 *   hands us PushMessage{content = plaintext, decrypted = true}.
 * - One UnifiedPush "instance" per service-worker scope:
 *   instance = "g" + hex(scope.hashCode()). The connector holds the
 *   private keys; we persist only scope <-> instance <-> endpoint /
 *   public keys / appServerKey so subscriptions survive restarts.
 * - No distributor installed: onSubscribe completes exceptionally at
 *   once (no hang, no dialog, no user-facing nag). Browsing is
 *   completely unaffected — push is only touched when a site calls
 *   pushManager.subscribe().
 *
 * Test-only spike artifacts are NOT carried over: no status overlay,
 * no self-send, no RawTapReceiver, no on-screen / file log. Logging
 * here is Logcat-only (tag "WebGenGeckoPush").
 *
 * Template v3 (Phase 5) adds ONE behaviour on top of v2: the
 * generation-time delivery model, site_config.json "pushMode"
 * ("hub" | "self", absent -> "hub"), driving distributor selection
 * in [ensureDistributor] on every subscribe attempt:
 *  - "hub": if the Aprica Push generator app itself (package
 *    [PREFERRED_DISTRIBUTOR]) is installed as a UnifiedPush
 *    distributor, it is saved as the current distributor and used,
 *    ahead of any other distributor (e.g. ntfy). Absent -> the v2
 *    current/default flow runs unchanged.
 *  - "self": the generated app embeds its own distributor (added by
 *    a follow-up pass — no distributor code exists in this build);
 *    the app's OWN package is preferred the same way. While no
 *    own-package distributor is declared, selection finds nothing
 *    and degrades exactly like the no-distributor case (graceful
 *    reject), never a crash and never a silent fallback to another
 *    distributor.
 */
object PushBridge {

    private const val TAG = "WebGenGeckoPush"
    private const val PREFS = "pushbridge"
    private const val CHANNEL_ID = "webpush"
    private const val MSG_FOR_DISTRIBUTOR = "WebGen Gecko (Aprica)"

    /**
     * Template v3: the Aprica Push generator app's package. In
     * "hub" mode, when it appears in UnifiedPush.getDistributors(),
     * it is the preferred distributor (see [ensureDistributor]).
     */
    private const val PREFERRED_DISTRIBUTOR = "app.webgen.generator.push"

    /** site_config.json "pushMode" values (template v3). */
    private const val PUSH_MODE_HUB = "hub"
    private const val PUSH_MODE_SELF = "self"

    @Volatile
    private var cachedPushMode: String? = null

    /**
     * The generation-time delivery model baked into
     * assets/site_config.json ("pushMode": "hub" | "self").
     * Absent or unrecognised -> "hub". Read once per process; the
     * config is an APK asset, so it cannot change at runtime.
     */
    private fun pushMode(context: Context): String {
        cachedPushMode?.let { return it }
        val mode = try {
            val text = context.applicationContext.assets.open("site_config.json")
                .bufferedReader().use { it.readText() }
            val raw = JSONObject(text).optString("pushMode", PUSH_MODE_HUB)
            if (raw == PUSH_MODE_SELF) PUSH_MODE_SELF else PUSH_MODE_HUB
        } catch (e: Exception) {
            Log.w(TAG, "pushMode read failed, defaulting to $PUSH_MODE_HUB: $e")
            PUSH_MODE_HUB
        }
        cachedPushMode = mode
        return mode
    }

    const val EXTRA_NOTIF_TAG = "notif_tag"

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var appContext: Context? = null

    /** instance -> GeckoResult waiting for its endpoint. */
    private val pending = ConcurrentHashMap<String, GeckoResult<WebPushSubscription>>()

    /** tag -> WebNotification, for click forwarding. */
    private val liveNotifications = ConcurrentHashMap<String, WebNotification>()

    fun attachAppContext(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Runtime + delegates (installed before any page load)
    // ------------------------------------------------------------------

    @Synchronized
    fun ensureRuntime(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val appCtx = context.applicationContext
        attachAppContext(appCtx)
        // Same runtime settings as the Phase-1 template: the engine
        // follows the device color scheme (prefers-color-scheme).
        val settings = GeckoRuntimeSettings.Builder()
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .build()
        val rt = GeckoRuntime.create(appCtx, settings)
        rt.webPushController.setDelegate(pushDelegate)
        rt.setWebNotificationDelegate(notificationDelegate)
        runtime = rt
        Log.i(TAG, "GeckoRuntime created, push delegates installed")
        return rt
    }

    fun runtimeOrNull(): GeckoRuntime? = runtime

    // ------------------------------------------------------------------
    // Distributor handling
    // ------------------------------------------------------------------

    /** Make sure a distributor is saved; callback reports success. */
    fun ensureDistributor(context: Context, onReady: (Boolean) -> Unit) {
        val appCtx = context.applicationContext
        val distributors = try {
            UnifiedPush.getDistributors(appCtx)
        } catch (e: Exception) {
            emptyList()
        }
        // Template v3 (Phase 5): distributor selection is driven by
        // the generation-time "pushMode" (see pushMode()). This runs
        // on every subscribe attempt, so a later-installed preferred
        // distributor is picked up without template changes.
        //  - hub: prefer the Aprica Push generator app; absent (or
        //    save failing) -> the v2 flow below, unchanged.
        //  - self: prefer this app's OWN package (its embedded
        //    distributor, added by a follow-up pass). Absent or
        //    save failing -> reject at once, exactly like the
        //    no-distributor case; no fallback to another distributor.
        val mode = pushMode(appCtx)
        val preferred =
            if (mode == PUSH_MODE_SELF) appCtx.packageName else PREFERRED_DISTRIBUTOR
        if (distributors.contains(preferred)) {
            try {
                UnifiedPush.saveDistributor(appCtx, preferred)
                Log.i(TAG, "preferred distributor selected (pushMode=$mode): $preferred")
                onReady(true)
                return
            } catch (e: Exception) {
                Log.w(TAG, "saveDistributor($preferred) failed: $e")
                if (mode == PUSH_MODE_SELF) {
                    onReady(false)
                    return
                }
                // hub: fall through to the v2 flow below.
            }
        } else if (mode == PUSH_MODE_SELF) {
            Log.w(
                TAG,
                "pushMode=self but no own-package distributor declared; " +
                    "subscribe will fail gracefully"
            )
            onReady(false)
            return
        }
        if (distributors.isEmpty()) {
            Log.w(TAG, "no UnifiedPush distributor installed; subscribe will fail gracefully")
            onReady(false)
            return
        }
        val saved = try {
            UnifiedPush.getSavedDistributor(appCtx) ?: ""
        } catch (e: Exception) {
            ""
        }
        if (saved.isNotEmpty()) {
            onReady(true)
            return
        }
        try {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(appCtx) { success ->
                Log.i(TAG, "tryUseCurrentOrDefaultDistributor -> $success")
                onReady(success)
            }
        } catch (e: Exception) {
            Log.w(TAG, "distributor selection failed: $e")
            onReady(false)
        }
    }

    // ------------------------------------------------------------------
    // scope <-> instance mapping + subscription persistence
    // ------------------------------------------------------------------

    private fun instanceForScope(scope: String): String =
        "g" + Integer.toHexString(scope.hashCode())

    private fun b64Encode(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun b64Decode(s: String): ByteArray {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return java.util.Base64.getUrlDecoder().decode(padded)
    }

    private fun saveScopeMapping(context: Context, scope: String, instance: String) {
        prefs(context).edit()
            .putString("scope.$scope.instance", instance)
            .putString("instance.$instance.scope", scope)
            .apply()
    }

    private fun scopeForInstance(context: Context, instance: String): String? =
        prefs(context).getString("instance.$instance.scope", null)

    private fun buildSubscription(context: Context, scope: String): WebPushSubscription? {
        val p = prefs(context)
        val endpoint = p.getString("scope.$scope.endpoint", null) ?: return null
        val pubKey = p.getString("scope.$scope.pubkey", null) ?: return null
        val auth = p.getString("scope.$scope.auth", null) ?: return null
        val serverKey = p.getString("scope.$scope.serverkey", null)?.let { b64Decode(it) }
        return try {
            WebPushSubscription(scope, endpoint, serverKey, b64Decode(pubKey), b64Decode(auth))
        } catch (e: Exception) {
            Log.w(TAG, "buildSubscription failed for $scope: $e")
            null
        }
    }

    private fun clearScope(context: Context, scope: String, instance: String) {
        prefs(context).edit()
            .remove("scope.$scope.instance")
            .remove("scope.$scope.endpoint")
            .remove("scope.$scope.pubkey")
            .remove("scope.$scope.auth")
            .remove("scope.$scope.serverkey")
            .remove("instance.$instance.scope")
            .apply()
    }

    // ------------------------------------------------------------------
    // WebPushDelegate
    // ------------------------------------------------------------------

    private val pushDelegate = object : WebPushDelegate {

        override fun onSubscribe(
            scope: String, serverKey: ByteArray?
        ): GeckoResult<WebPushSubscription> {
            val context = appContext
            if (context == null) {
                return GeckoResult.fromException(
                    RuntimeException("runtime not initialised")
                )
            }
            val instance = instanceForScope(scope)
            Log.i(TAG, "onSubscribe scope=$scope instance=$instance")
            saveScopeMapping(context, scope, instance)
            prefs(context).edit().apply {
                if (serverKey != null) putString("scope.$scope.serverkey", b64Encode(serverKey))
                else remove("scope.$scope.serverkey")
            }.apply()

            buildSubscription(context, scope)?.let {
                Log.i(TAG, "onSubscribe: returning existing subscription")
                return GeckoResult.fromValue(it)
            }

            val result = GeckoResult<WebPushSubscription>()
            pending[instance] = result
            ensureDistributor(context) { ok ->
                if (!ok) {
                    // No distributor: reject gracefully, at once.
                    // No dialog / nag; browsing is unaffected.
                    pending.remove(instance)
                    Log.w(TAG, "onSubscribe: no distributor, rejecting")
                    result.completeExceptionally(
                        RuntimeException("no UnifiedPush distributor")
                    )
                    return@ensureDistributor
                }
                try {
                    val vapid = serverKey?.let { b64Encode(it) }
                    Log.i(TAG, "UnifiedPush.register instance=$instance vapid=${vapid != null}")
                    UnifiedPush.register(context, instance, MSG_FOR_DISTRIBUTOR, vapid)
                } catch (e: Exception) {
                    pending.remove(instance)
                    Log.w(TAG, "UnifiedPush.register failed: $e")
                    result.completeExceptionally(e)
                }
            }
            return result
        }

        override fun onGetSubscription(
            scope: String
        ): GeckoResult<WebPushSubscription> {
            val context = appContext
                ?: return GeckoResult.fromException(RuntimeException("runtime not initialised"))
            val sub = buildSubscription(context, scope)
            Log.i(TAG, "onGetSubscription scope=$scope -> ${if (sub != null) "found" else "none"}")
            val result = GeckoResult<WebPushSubscription>()
            result.complete(sub)
            return result
        }

        override fun onUnsubscribe(scope: String): GeckoResult<Void> {
            val context = appContext
                ?: return GeckoResult.fromException(RuntimeException("runtime not initialised"))
            val instance = instanceForScope(scope)
            Log.i(TAG, "onUnsubscribe scope=$scope instance=$instance")
            try {
                UnifiedPush.unregister(context, instance)
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed: $e")
            }
            clearScope(context, scope, instance)
            return GeckoResult.fromValue(null)
        }
    }

    // ------------------------------------------------------------------
    // UnifiedPush receiver callbacks (invoked by PushReceiver)
    // ------------------------------------------------------------------

    fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val appCtx = context.applicationContext
        val scope = scopeForInstance(appCtx, instance)
        Log.i(TAG, "onNewEndpoint instance=$instance scope=$scope url=${endpoint.url}")

        var pubKeySet = endpoint.pubKeySet
        if (pubKeySet == null) {
            pubKeySet = try {
                DefaultKeyManager(appCtx).getPublicKeySet(instance)
            } catch (e: Exception) {
                Log.w(TAG, "getPublicKeySet fallback failed: $e")
                null
            }
        }

        var changedForSubscribedScope = false
        if (scope != null) {
            val p = prefs(appCtx)
            val oldEndpoint = p.getString("scope.$scope.endpoint", null)
            val hadSubscription = oldEndpoint != null &&
                p.getString("scope.$scope.pubkey", null) != null
            p.edit().apply {
                putString("scope.$scope.endpoint", endpoint.url)
                putString("lastEndpoint", endpoint.url)
                if (pubKeySet != null) {
                    putString("scope.$scope.pubkey", pubKeySet.pubKey)
                    putString("scope.$scope.auth", pubKeySet.auth)
                }
            }.apply()
            changedForSubscribedScope = hadSubscription && oldEndpoint != endpoint.url
            if (pubKeySet == null) {
                Log.w(TAG, "onNewEndpoint: no public key set (instance=$instance)")
            }
        } else {
            prefs(appCtx).edit().putString("lastEndpoint", endpoint.url).apply()
            Log.i(TAG, "onNewEndpoint: instance without a pending scope mapping")
        }

        pending.remove(instance)?.let { result ->
            val sub = if (scope != null) buildSubscription(appCtx, scope) else null
            if (sub != null) {
                Log.i(TAG, "subscribe completed: scope=$scope")
                result.complete(sub)
            } else {
                Log.w(TAG, "endpoint arrived but subscription could not be built (scope=$scope)")
                result.completeExceptionally(
                    RuntimeException("endpoint without key material")
                )
            }
        }

        if (changedForSubscribedScope && scope != null) {
            val rt = runtimeOrNull()
            if (rt != null) {
                Log.i(TAG, "endpoint changed -> onSubscriptionChanged($scope)")
                try {
                    rt.webPushController.onSubscriptionChanged(scope)
                } catch (e: Exception) {
                    Log.w(TAG, "onSubscriptionChanged failed: $e")
                }
            }
        }
    }

    fun onMessage(context: Context, message: PushMessage, instance: String) {
        val appCtx = context.applicationContext
        val scope = scopeForInstance(appCtx, instance)
        Log.i(
            TAG,
            "onMessage instance=$instance scope=$scope bytes=${message.content.size} decrypted=${message.decrypted}"
        )
        if (scope == null) {
            Log.w(TAG, "message for unknown instance: $instance")
            return
        }
        try {
            // If the process was cold-started by the broadcast, the
            // runtime is created on the spot so delivery still happens.
            val rt = ensureRuntime(appCtx)
            if (message.content.isEmpty()) {
                rt.webPushController.onPushEvent(scope)
            } else {
                // GeckoView expects the UNENCRYPTED payload; the
                // connector has already decrypted it (decrypted=true).
                rt.webPushController.onPushEvent(scope, message.content)
            }
            Log.i(TAG, "onPushEvent delivered scope=$scope")
        } catch (e: Exception) {
            Log.w(TAG, "onPushEvent failed: $e")
        }
    }

    fun onUnregistered(context: Context, instance: String) {
        val appCtx = context.applicationContext
        val scope = scopeForInstance(appCtx, instance)
        Log.i(TAG, "onUnregistered instance=$instance scope=$scope")
        pending.remove(instance)?.completeExceptionally(
            RuntimeException("unregistered")
        )
        if (scope != null) clearScope(appCtx, scope, instance)
    }

    fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Log.w(TAG, "registration failed instance=$instance reason=$reason")
        pending.remove(instance)?.completeExceptionally(
            RuntimeException("UnifiedPush registration failed: $reason")
        )
    }

    fun onTempUnavailable(context: Context, instance: String) {
        Log.i(TAG, "onTempUnavailable instance=$instance")
    }

    // ------------------------------------------------------------------
    // WebNotificationDelegate -> real Android notifications
    // ------------------------------------------------------------------

    private val notificationDelegate = object : WebNotificationDelegate {

        override fun onShowNotification(notification: WebNotification) {
            val context = appContext ?: return
            Log.i(TAG, "onShowNotification title=${notification.title} tag=${notification.tag}")
            try {
                ensureChannel(context)
                liveNotifications[notification.tag] = notification
                // The intent targets this app's MainActivity class as
                // it exists in the dex; the notification shows the
                // generated app's label and icon because both come
                // from the (patched) application info / resources.
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_NOTIF_TAG, notification.tag)
                }
                val pi = PendingIntent.getActivity(
                    context, notification.tag.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(notification.title)
                    .setContentText(notification.text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                if (notification.silent) builder.setSilent(true)
                if (notification.requireInteraction) builder.setOngoing(true)
                NotificationManagerCompat.from(context)
                    .notify(notification.tag.hashCode(), builder.build())
            } catch (e: Exception) {
                Log.w(TAG, "showing Android notification failed: $e")
            }
        }

        override fun onCloseNotification(notification: WebNotification) {
            val context = appContext ?: return
            Log.i(TAG, "onCloseNotification tag=${notification.tag}")
            liveNotifications.remove(notification.tag)
            try {
                NotificationManagerCompat.from(context)
                    .cancel(notification.tag.hashCode())
            } catch (e: Exception) {
                Log.w(TAG, "cancel failed: $e")
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Web Push", NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    /** Called by MainActivity when our Android notification is tapped. */
    fun handleNotificationClick(context: Context, tag: String) {
        Log.i(TAG, "notification tapped tag=$tag")
        try {
            NotificationManagerCompat.from(context).cancel(tag.hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "cancel on click failed: $e")
        }
        liveNotifications.remove(tag)?.let { notification ->
            try {
                notification.click()
            } catch (e: Exception) {
                Log.w(TAG, "webNotification.click failed: $e")
            }
        }
    }
}
