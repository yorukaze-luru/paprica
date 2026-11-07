package app.webgen.geckotemplate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Template site app (GeckoView edition, templateVersion 3) — the
 * Gecko-engine counterpart of the WebView template (template-app,
 * v6). Single Activity, framework Android APIs + GeckoView only.
 *
 * On startup it reads assets/site_config.json:
 *   {"url": "https://...", "name": "...",
 *    "templateVersion": 3,                    // template version baked in
 *    "notifications": true,                   // ask POST_NOTIFICATIONS once
 *    "permissions": {"microphone": true, "camera": false,
 *                    "location": false, "notifications": true},
 *    "pushMode": "hub",                       // optional: "hub" (default) or
 *                                             // "self" — see PushBridge
 *    "updateFeedUrl": "https://..."}          // optional update feed
 * and shows the URL in a full-screen GeckoView. The generator patches
 * site_config.json, the manifest package/label literals, the launcher
 * icon PNGs and (for disabled capabilities) the permission name
 * strings to produce a stand-alone site app.
 *
 * Feature mapping from the WebView template (see PORT-REPORT.md):
 * - Progress bar: ProgressDelegate.onProgressChange (brand teal).
 * - Splash overlay: hidden at the first ProgressDelegate.onPageStop.
 * - Error overlay: NavigationDelegate.onLoadError for main-frame
 *   network / TLS failures (WebRequestError categories), with the
 *   same friendly copy and a 再読み込み button (session.reload()).
 * - Navigation: NavigationDelegate.onLoadRequest. http/https always
 *   stays in the engine; non-http(s) schemes are dispatched to
 *   external apps; intent:// is resolved with browser_fallback_url
 *   support — identical rules to the WebView template.
 * - Popups (window.open / target=_blank): NavigationDelegate
 *   .onNewSession supplies a second session which takes over the
 *   same GeckoView full-screen (the WebView template uses a dialog);
 *   ContentDelegate.onCloseRequest or the back key returns to the
 *   opener session.
 * - Pull-to-refresh: same anti-misfire rule as WebView v6 (arm only
 *   when nothing under the touch point can scroll up). GeckoView 159
 *   has NO evaluateJavascript API, so the elementFromPoint probe runs
 *   in a bundled built-in WebExtension content script
 *   (assets/webext/ptrprobe) that answers over a native-messaging
 *   port; arming additionally requires the root scroller at top
 *   (ScrollDelegate).
 * - Site permissions: PermissionDelegate. Config toggles are honored
 *   at runtime: OFF -> deny without prompting; ON -> the engine's
 *   Android-permission bridge (onAndroidPermissionsRequest) and the
 *   media flow run the usual runtime permission dialogs.
 * - File upload: PromptDelegate.onFilePrompt -> system picker (the
 *   prompt's mime types honored, multiple selection supported), with
 *   a camera capture option when CAMERA is granted — same shape as
 *   the WebView template's onShowFileChooser.
 * - Dark mode: the runtime is created with
 *   preferredColorScheme = COLOR_SCHEME_SYSTEM, so sites that honor
 *   prefers-color-scheme follow the device; the overlays switch to
 *   the same night colors as WebView template v5.
 * - Back key: session history (tracked via onCanGoBack) first, then
 *   the opener session for popups, else finish.
 * - Web push (v2 / Phase 3): the Phase-2 device-proven UnifiedPush
 *   stack, productized in PushBridge/PushReceiver — connector
 *   MessagingReceiver, Tink-backed decryption, WebNotificationDelegate
 *   posting real Android notifications under the generated app's
 *   label/icon. No distributor installed -> subscribe rejects
 *   gracefully; browsing is unaffected and nothing nags the user.
 * - Update check: identical feed format and dialog as the WebView
 *   template ({"templateVersion": N} or a template-vN release tag).
 */
class MainActivity : Activity() {

    private lateinit var geckoView: GeckoView
    private lateinit var progress: ProgressBar
    private lateinit var splashOverlay: LinearLayout
    private lateinit var errorOverlay: LinearLayout

    private lateinit var siteConfig: JSONObject
    private var startUrl: String = ""
    private var currentUrl: String = ""

    private lateinit var runtime: GeckoRuntime
    private var mainSession: GeckoSession? = null
    private var popupSession: GeckoSession? = null
    private var activeSession: GeckoSession? = null
    private val canGoBackMap = HashMap<GeckoSession, Boolean>()
    private var pageError = false
    private var sessionCrashed = false

    // Pull-to-refresh state.
    private var touchDownY = 0f
    private var touchActive = false
    private var pullReloadArmed = false
    private var pageScrollY = 0
    private var probeSeq = 0
    private var pendingProbeId = -1
    private val probePorts = HashMap<GeckoSession, WebExtension.Port>()

    // Bridges between GeckoView permission callbacks and Android
    // runtime permission requests.
    private var pendingAndroidCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingAndroidPerms: Array<String> = emptyArray()
    private var pendingMedia: PendingMedia? = null

    private class PendingMedia(
        val callback: GeckoSession.PermissionDelegate.MediaCallback,
        val video: GeckoSession.PermissionDelegate.MediaSource?,
        val audio: GeckoSession.PermissionDelegate.MediaSource?
    )

    // File chooser state.
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var cameraImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this
        PushBridge.attachAppContext(this)

        siteConfig = readSiteConfig()
        startUrl = siteConfig.optString("url", "https://example.com")
        title = siteConfig.optString("name", "")

        val root = FrameLayout(this)
        geckoView = GeckoView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(BRAND_TEAL)
        root.addView(geckoView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, 6))
        errorOverlay = buildErrorOverlay()
        root.addView(errorOverlay, FrameLayout.LayoutParams(-1, -1))
        splashOverlay = buildSplashOverlay()
        root.addView(splashOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Keep page content (e.g. a chat input pinned to the bottom of
        // the page) out from behind the system bars (WebView template
        // v6 parity): brand-color the bars and pad the root view by
        // the system-bar insets. The manifest sets
        // windowSoftInputMode=adjustResize so the page shrinks above
        // the keyboard.
        window.statusBarColor = BRAND_TEAL_DARK
        window.navigationBarColor = BRAND_TEAL_DARK
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            insets
        }
        root.requestApplyInsets()

        runtime = obtainRuntime()

        val session = createSession()
        mainSession = session
        activeSession = session
        geckoView.setSession(session)

        installPullToRefresh()

        val initialUrl = savedInstanceState?.getString(STATE_CURRENT_URL)
            ?.takeIf { it.isNotEmpty() } ?: startUrl
        currentUrl = initialUrl
        session.loadUri(initialUrl)

        handleNotifIntent(intent)

        maybeRequestNotificationPermission()
        maybeStartEmbeddedDistributor()
        checkForTemplateUpdate()
    }

    /**
     * Template v3 self-embedding (Phase 5) — FGS start policy, app
     * launch leg: when the baked site_config.json has
     * "pushMode": "self" AND notifications ON, start the embedded
     * distributor's relay foreground service from here, where the
     * app is in the foreground and startForegroundService is
     * unrestricted. [app.webgen.updist.UpDistributor.start]
     * re-checks the same baked-config gate internally, so in hub
     * mode (or notifications OFF) this is a guaranteed no-op and
     * the FGS never starts. The other legs of the policy: the
     * distributor receiver lazily starts/refreshes the service on
     * the first accepted registration, and BOOT_COMPLETED restarts
     * it when registrations persist (both gated the same way, and
     * background-start failures are caught inside UpDistributor).
     */
    private fun maybeStartEmbeddedDistributor() {
        if (siteConfig.optString("pushMode", "hub") != "self") return
        if (!siteConfig.optBoolean("notifications", false)) return
        app.webgen.updist.UpDistributor.start(this, null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotifIntent(intent)
    }

    /** A tap on one of our Web Push Android notifications. */
    private fun handleNotifIntent(intent: Intent?) {
        val tag = intent?.getStringExtra(PushBridge.EXTRA_NOTIF_TAG) ?: return
        PushBridge.handleNotificationClick(this, tag)
    }

    // ------------------------------------------------------------------
    // Runtime (shared, process-wide) + push stub + probe extension
    // ------------------------------------------------------------------

    private var probeExtInstalled = false

    private fun obtainRuntime(): GeckoRuntime {
        // PushBridge owns the (process-wide) runtime: it creates it
        // with the template's color-scheme settings and installs the
        // WebPush + WebNotification delegates before any page load.
        // It is also what PushReceiver uses when a push broadcast
        // cold-starts the process, so both paths share one runtime.
        val rt = PushBridge.ensureRuntime(this)
        if (!probeExtInstalled) {
            probeExtInstalled = true
            // --- Pull-to-refresh probe extension ---
            rt.webExtensionController.ensureBuiltIn(PTR_EXT_URI, PTR_EXT_ID).accept(
                { ext ->
                    if (ext != null) {
                        ext.setMessageDelegate(probeMessageDelegate, PTR_EXT_APP)
                        Log.i(TAG, "ptr probe extension ready: ${ext.id}")
                    }
                },
                { e -> Log.w(TAG, "ptr probe extension install failed", e) }
            )
        }
        return rt
    }

    /** Ports connect from content scripts; route them to the live activity. */
    private fun registerProbePort(port: WebExtension.Port) {
        val session = try {
            port.sender.session
        } catch (e: Exception) {
            null
        } ?: return
        probePorts[session] = port
        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, senderPort: WebExtension.Port) {
                val json = message as? JSONObject ?: return
                if (json.optString("type") == "probeResult") {
                    onProbeResult(json.optInt("id", -1), json.optString("result"))
                }
            }

            override fun onDisconnect(senderPort: WebExtension.Port) {
                probePorts.values.remove(senderPort)
            }
        })
    }

    // ------------------------------------------------------------------
    // Sessions and delegates
    // ------------------------------------------------------------------

    private fun createSession(): GeckoSession {
        val session = GeckoSession()
        session.setNavigationDelegate(navigationDelegate)
        session.setProgressDelegate(progressDelegate)
        session.setContentDelegate(contentDelegate)
        session.setPermissionDelegate(permissionDelegate)
        session.setPromptDelegate(promptDelegate)
        session.setScrollDelegate(scrollDelegate)
        session.open(runtime)
        return session
    }

    private fun attachToView(session: GeckoSession) {
        if (geckoView.session != null && geckoView.session !== session) {
            geckoView.releaseSession()
        }
        if (geckoView.session !== session) {
            geckoView.setSession(session)
        }
        activeSession = session
        // The overlays track the visible session again.
        progress.visibility = View.GONE
        errorOverlay.visibility = View.GONE
        pageError = false
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            // ALLOW = the engine loads it (target=_blank requests are
            // then routed to onNewSession). DENY = handled outside or
            // swallowed by interceptExternal().
            val handled = interceptExternal(Uri.parse(request.uri))
            return GeckoResult.fromValue(if (handled) AllowOrDeny.DENY else AllowOrDeny.ALLOW)
        }

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            val scheme = Uri.parse(request.uri).scheme?.lowercase()
            return GeckoResult.fromValue(
                if (scheme == "http" || scheme == "https" || scheme in INTERNAL_SCHEMES) {
                    AllowOrDeny.ALLOW
                } else {
                    // Never fire external intents from subframes.
                    AllowOrDeny.DENY
                }
            )
        }

        override fun onNewSession(
            session: GeckoSession,
            uri: String
        ): GeckoResult<GeckoSession> {
            // window.open / target=_blank: a new session takes over
            // the same GeckoView full-screen (instead of the WebView
            // template's popup dialog). The engine loads uri itself.
            val popup = createSession()
            popupSession = popup
            attachToView(popup)
            return GeckoResult.fromValue(popup)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            canGoBackMap[session] = canGoBack
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String> {
            // Main-frame load failure: show the friendly overlay
            // instead of the engine's raw error page (WebView
            // template: onReceivedError / onReceivedSslError).
            if (session === activeSession && error.category !=
                WebRequestError.ERROR_CATEGORY_SAFEBROWSING
            ) {
                showError()
            }
            return GeckoResult.fromValue(null)
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (session !== activeSession) return
            currentUrl = url
            pageError = false
            sessionCrashed = false
            errorOverlay.visibility = View.GONE
            progress.progress = 0
            progress.visibility = View.VISIBLE
        }

        override fun onProgressChange(session: GeckoSession, progressValue: Int) {
            if (session !== activeSession) return
            progress.progress = progressValue
            progress.visibility = if (progressValue >= 100) View.GONE else View.VISIBLE
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (session !== activeSession) return
            progress.visibility = View.GONE
            splashOverlay.visibility = View.GONE
            if (!pageError) errorOverlay.visibility = View.GONE
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onCloseRequest(session: GeckoSession) {
            // window.close() in a popup session: return to the opener.
            if (session === popupSession) closePopupSession()
        }

        override fun onCrash(session: GeckoSession) {
            // The content process died; the session is unusable. The
            // error overlay's retry button recreates the session.
            if (session === activeSession) {
                sessionCrashed = true
                showError()
            }
        }
    }

    private val scrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
            if (session === activeSession) pageScrollY = scrollY
        }
    }

    /**
     * Central navigation rule (WebView template parity). Returns true
     * when the navigation has been handled (or swallowed) OUTSIDE the
     * engine and the load must be denied.
     */
    private fun interceptExternal(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return when {
            // All http/https stays in the engine, host does not
            // matter, so cross-host auth redirects stay in-app.
            scheme == "http" || scheme == "https" -> false

            // Schemes the engine itself renders; never dispatch out.
            scheme in INTERNAL_SCHEMES -> false

            scheme == "intent" -> {
                handleIntentScheme(uri)
                true
            }

            // tel:, mailto:, sms:, geo:, market:, and any other
            // custom scheme: hand to an external app; if none handles
            // it, ignore quietly.
            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    // No handler installed: swallow silently.
                }
                true
            }
        }
    }

    private fun handleIntentScheme(uri: Uri) {
        val intent = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // App not installed etc.: fall back to the web URL if given.
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrEmpty()) {
                activeSession?.loadUri(fallback)
            }
        }
    }

    private fun closePopupSession() {
        val popup = popupSession ?: return
        popupSession = null
        canGoBackMap.remove(popup)
        probePorts.remove(popup)
        try {
            popup.close()
        } catch (e: Exception) {
            // already closed
        }
        val main = mainSession ?: return
        attachToView(main)
    }

    // ------------------------------------------------------------------
    // Site permissions (PermissionDelegate)
    // ------------------------------------------------------------------

    private fun permEnabled(key: String): Boolean {
        val perms = siteConfig.optJSONObject("permissions") ?: return false
        return perms.optBoolean(key, false)
    }

    /** Config toggle gating an Android permission, or null if ungated. */
    private fun configGateFor(androidPermission: String): Boolean? {
        return when (androidPermission) {
            Manifest.permission.RECORD_AUDIO -> permEnabled("microphone")
            Manifest.permission.CAMERA -> permEnabled("camera")
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION -> permEnabled("location")
            else -> null
        }
    }

    private fun isGranted(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        /**
         * The engine asks the app to obtain Android runtime
         * permissions (geolocation after a content-level ALLOW,
         * camera/mic around media capture, ...). Config toggles gate
         * this bridge exactly like the WebView template's manifest
         * rewriting does: a capability that is OFF in the config is
         * denied without ever prompting the user.
         */
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) {
            val perms = permissions ?: emptyArray()
            if (perms.any { configGateFor(it) == false }) {
                callback.reject()
                return
            }
            if (perms.all { isGranted(it) }) {
                callback.grant()
                return
            }
            pendingAndroidCallback?.reject()
            pendingAndroidCallback = callback
            pendingAndroidPerms = perms
            requestPermissions(perms, REQ_ANDROID_PERMS)
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int> {
            val value = when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
                    if (permEnabled("location")) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    }
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                    if (permEnabled("notifications")) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    }
                // No XR runtime in the template (WebView parity: none).
                GeckoSession.PermissionDelegate.PERMISSION_XR ->
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                // Persistent storage, autoplay, DRM (media key system),
                // storage access, tracking: not gated by the template
                // (the WebView template enables DOM storage etc. too).
                else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            }
            return GeckoResult.fromValue(value)
        }

        /**
         * The site asks for mic/camera (getUserMedia). Pick the
         * sources the config allows, bridge the missing Android
         * runtime permissions, then grant/deny accordingly.
         */
        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            videoSources: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audioSources: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) {
            val videos = videoSources ?: emptyArray()
            val audios = audioSources ?: emptyArray()
            val videoPick = pickVideoSource(videos)
            val audioPick = if (audios.isNotEmpty() && permEnabled("microphone")) {
                audios[0]
            } else {
                null
            }
            if (videoPick == null && audioPick == null) {
                callback.reject()
                return
            }
            val needed = mutableListOf<String>()
            if (videoPick != null &&
                videoPick.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA &&
                !isGranted(Manifest.permission.CAMERA)
            ) {
                needed.add(Manifest.permission.CAMERA)
            }
            if (audioPick != null && !isGranted(Manifest.permission.RECORD_AUDIO)) {
                needed.add(Manifest.permission.RECORD_AUDIO)
            }
            if (needed.isEmpty()) {
                callback.grant(videoPick, audioPick)
                return
            }
            pendingMedia?.callback?.reject()
            pendingMedia = PendingMedia(callback, videoPick, audioPick)
            requestPermissions(needed.toTypedArray(), REQ_MEDIA)
        }
    }

    private fun pickVideoSource(
        videoSources: Array<GeckoSession.PermissionDelegate.MediaSource>
    ): GeckoSession.PermissionDelegate.MediaSource? {
        if (videoSources.isEmpty()) return null
        if (permEnabled("camera")) {
            return videoSources.firstOrNull {
                it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA
            } ?: videoSources[0]
        }
        // Camera is OFF in the config: a screen-capture source (which
        // needs no Android camera permission) may still be granted.
        return videoSources.firstOrNull {
            it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_SCREEN
        }
    }

    // ------------------------------------------------------------------
    // File upload (PromptDelegate)
    // ------------------------------------------------------------------

    private val promptDelegate = object : GeckoSession.PromptDelegate {
        /**
         * <select> popups (Phase-2 fix round 2, ported verbatim in
         * behaviour): GeckoView delegates them to the embedder; with
         * no onChoicePrompt a tap on any <select> silently does
         * nothing. Choices are flattened recursively (a choice with
         * non-empty items is an <optgroup>), then shown as a native
         * dialog. GV 159 exposes no TYPE_* constants on ChoicePrompt;
         * the prompt service sends 1=single, 2=multiple, 3=menu —
         * only 2 branches, everything else is single-choice.
         * AlertPrompt has no public confirm() in 159, so OK maps to
         * dismiss() (JS alert() completes either way).
         */
        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val rows = ArrayList<ChoiceRow>()
            flattenChoices(prompt.choices, 0, rows)
            Log.i(TAG, "onChoicePrompt: type=${prompt.type} rows=${rows.size}")
            runOnUiThread { showChoiceDialog(prompt, rows, result) }
            return result
        }

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            Log.i(TAG, "onAlertPrompt: ${prompt.message}")
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.complete(prompt.dismiss())
                    return@runOnUiThread
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(prompt.title?.takeIf { it.isNotBlank() })
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        result.complete(prompt.dismiss())
                        dialog.dismiss()
                    }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
            }
            return result
        }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            completeFilePrompt(null) // supersede a still-pending prompt
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            pendingFilePrompt = prompt
            pendingFileResult = result
            cameraImageUri = null

            val cameraIntent = buildCameraIntent()
            if (prompt.capture != GeckoSession.PromptDelegate.FilePrompt.Capture.NONE &&
                cameraIntent != null
            ) {
                // The page explicitly wants a capture (capture="user" etc.).
                @Suppress("DEPRECATION")
                startActivityForResult(cameraIntent, REQ_FILE_CHOOSER)
                return result
            }

            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                val mimes = prompt.mimeTypes?.filter { it.isNotBlank() } ?: emptyList()
                type = if (mimes.size == 1) mimes[0] else "*/*"
                if (mimes.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                }
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            val chooser = Intent.createChooser(contentIntent, "ファイルを選択")
            if (cameraIntent != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(chooser, REQ_FILE_CHOOSER)
            } catch (e: Exception) {
                completeFilePrompt(null)
            }
            return result
        }

        override fun onFolderUploadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FolderUploadPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            // Folder upload is not offered (the WebView template's
            // chooser has no directory mode either).
            return GeckoResult.fromValue(prompt.dismiss())
        }
    }

    /** One flattened row of a <select>: leaf, group header or separator. */
    private class ChoiceRow(
        val choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice?,
        val label: String,
        val selectable: Boolean,
        val depth: Int,
        val kind: Int // 0 = leaf, 1 = group header, 2 = separator
    )

    private fun flattenChoices(
        choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>?,
        depth: Int,
        out: MutableList<ChoiceRow>
    ) {
        if (choices == null) return
        for (choice in choices) {
            val items = choice.items
            when {
                choice.separator ->
                    out += ChoiceRow(null, "─".repeat(16), false, depth, 2)
                items != null && items.isNotEmpty() -> {
                    out += ChoiceRow(null, choice.label ?: "", false, depth, 1)
                    flattenChoices(items, depth + 1, out)
                }
                else ->
                    out += ChoiceRow(choice, choice.label ?: "", !choice.disabled, depth, 0)
            }
        }
    }

    private fun choiceRowLabel(row: ChoiceRow): String {
        val indent = "　".repeat(row.depth) // full-width space per nesting level
        val mark = if (row.choice?.selected == true) "✓ " else ""
        return indent + mark + row.label
    }

    private fun showChoiceDialog(
        prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        rows: List<ChoiceRow>,
        result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
    ) {
        if (isFinishing || isDestroyed) {
            result.complete(prompt.dismiss())
            return
        }
        var completed = false
        fun finish(response: GeckoSession.PromptDelegate.PromptResponse) {
            if (!completed) {
                completed = true
                result.complete(response)
            }
        }
        val dialogTitle = when {
            !prompt.message.isNullOrBlank() -> prompt.message
            !prompt.title.isNullOrBlank() -> prompt.title
            else -> "選択してください"
        }

        if (prompt.type == CHOICE_TYPE_MULTIPLE) {
            // Multi-choice: leaves only (headers/separators cannot be
            // represented), nesting shown as label indentation.
            val leaves = rows.filter { it.choice != null }
            val labels = leaves.map { choiceRowLabel(it) }.toTypedArray()
            val checked = BooleanArray(leaves.size) { leaves[it].choice!!.selected }
            AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val selected = leaves
                        .filterIndexed { index, _ -> checked[index] }
                        .map { it.choice!! }
                        .toTypedArray()
                    Log.i(TAG, "onChoicePrompt: multiple confirmed ids=${selected.joinToString { it.id ?: "" }}")
                    finish(prompt.confirm(selected))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish(prompt.dismiss())
                }
                .setOnCancelListener { finish(prompt.dismiss()) }
                .show()
            return
        }

        // Single choice / menu: a ListView so group headers and
        // separators render but cannot be picked, disabled leaves are
        // inert, and a tap on an enabled leaf confirms at once.
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_single_choice,
            rows.map { choiceRowLabel(it) }
        ) {
            override fun areAllItemsEnabled(): Boolean = false
            override fun isEnabled(position: Int): Boolean = rows[position].selectable
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view as TextView
                tv.typeface = if (rows[position].kind == 1) {
                    android.graphics.Typeface.DEFAULT_BOLD
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                return view
            }
        }
        val listView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            this.adapter = adapter
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish(prompt.dismiss())
            }
            .setOnCancelListener { finish(prompt.dismiss()) }
            .create()
        // Safety net: any dismissal path still completes exactly once.
        dialog.setOnDismissListener { finish(prompt.dismiss()) }
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position]
            val choice = row.choice
            if (row.selectable && choice != null) {
                Log.i(TAG, "onChoicePrompt: selected id=${choice.id} label=${choice.label}")
                finish(prompt.confirm(choice))
                dialog.dismiss()
            }
        }
        dialog.show()
        val preselect = rows.indexOfFirst { it.choice?.selected == true }
        if (preselect >= 0) {
            listView.setItemChecked(preselect, true)
            listView.setSelection(preselect)
        }
    }

    private fun completeFilePrompt(uris: Array<Uri>?) {
        val prompt = pendingFilePrompt ?: return
        val result = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (result == null) return
        if (uris == null) {
            result.complete(prompt.dismiss())
        } else {
            result.complete(prompt.confirm(this, uris))
        }
    }

    /** IMAGE_CAPTURE intent with a MediaStore output Uri; null if unavailable. */
    private fun buildCameraIntent(): Intent? {
        if (!isGranted(Manifest.permission.CAMERA)) return null
        val uri = try {
            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "webgen_" + System.currentTimeMillis() + ".jpg"
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return null
        cameraImageUri = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------
    // Pull-to-refresh (v6 anti-misfire rule, extension-probe edition)
    // ------------------------------------------------------------------

    private fun installPullToRefresh() {
        geckoView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = ev.y
                    touchActive = true
                    pullReloadArmed = false
                    pendingProbeId = -1
                    if (pageScrollY == 0) {
                        val density = resources.displayMetrics.density
                        val cx = (ev.x / density).toInt()
                        val cy = (ev.y / density).toInt()
                        sendProbe(cx, cy)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val threshold = 160f * resources.displayMetrics.density
                    if (pullReloadArmed && pageScrollY == 0 &&
                        ev.y - touchDownY > threshold
                    ) {
                        pullReloadArmed = false
                        activeSession?.reload()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchActive = false
                    pullReloadArmed = false
                }
            }
            false
        }
    }

    private fun sendProbe(cssX: Int, cssY: Int) {
        val session = activeSession ?: return
        val port = probePorts[session] ?: return
        val id = ++probeSeq
        pendingProbeId = id
        try {
            port.postMessage(
                JSONObject()
                    .put("type", "probe")
                    .put("id", id)
                    .put("x", cssX)
                    .put("y", cssY)
            )
        } catch (e: Exception) {
            // Port died between connect and use: PTR stays disarmed
            // for this gesture (same as a "block" answer).
        }
    }

    private fun onProbeResult(id: Int, result: String) {
        if (id == pendingProbeId && touchActive && pageScrollY == 0 && result == "ok") {
            pullReloadArmed = true
        }
    }

    // ------------------------------------------------------------------
    // Overlays (splash / error) — copy and colors mirror the WebView
    // template, including the v5 night palette.
    // ------------------------------------------------------------------

    private fun showError() {
        pageError = true
        splashOverlay.visibility = View.GONE
        errorOverlay.visibility = View.VISIBLE
    }

    private fun appIconDrawable(): android.graphics.drawable.Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun brandBg(): Int =
        if (isNightMode()) 0xFF0C1B1A.toInt() else BRAND_BG

    private fun inkColor(): Int =
        if (isNightMode()) 0xFFE8F4F1.toInt() else INK

    private fun inkSubColor(): Int =
        if (isNightMode()) 0xFF9DB8B3.toInt() else INK_SUB

    /** Full-screen splash shown until the first page finishes loading. */
    private fun buildSplashOverlay(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(brandBg())
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(appIconDrawable())
                val size = (96 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            addView(icon)
            addView(TextView(this@MainActivity).apply {
                text = siteConfig.optString("name", "")
                textSize = 20f
                setTextColor(inkColor())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, (14 * density).toInt(), 0, 0)
            })
            addView(ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(BRAND_TEAL)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (18 * density).toInt() }
            })
        }
    }

    /** Friendly error screen with retry, for main-frame load failures. */
    private fun buildErrorOverlay(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(brandBg())
            visibility = View.GONE
            setPadding(
                (32 * density).toInt(), 0, (32 * density).toInt(), 0
            )
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(appIconDrawable())
                val size = (72 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            addView(icon)
            addView(TextView(this@MainActivity).apply {
                text = "ページを読み込めませんでした"
                textSize = 17f
                setTextColor(inkColor())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "通信環境を確認して、もう一度お試しください。"
                textSize = 13f
                setTextColor(inkSubColor())
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
            addView(Button(this@MainActivity).apply {
                text = "再読み込み"
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(BRAND_TEAL)
                setOnClickListener {
                    errorOverlay.visibility = View.GONE
                    pageError = false
                    retryLoad()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (20 * density).toInt() }
            })
        }
    }

    private fun retryLoad() {
        val session = activeSession ?: return
        if (sessionCrashed) {
            // A crashed session cannot reload: replace it and load
            // the last known URL (or the start URL) afresh.
            sessionCrashed = false
            val wasPopup = session === popupSession
            try {
                session.close()
            } catch (e: Exception) {
                // already gone
            }
            canGoBackMap.remove(session)
            probePorts.remove(session)
            val fresh = createSession()
            if (wasPopup) {
                popupSession = fresh
            } else {
                mainSession = fresh
            }
            attachToView(fresh)
            fresh.loadUri(currentUrl.ifEmpty { startUrl })
            return
        }
        if (currentUrl.isEmpty()) {
            session.loadUri(startUrl)
        } else {
            session.reload()
        }
    }

    // ------------------------------------------------------------------
    // Runtime permission / activity results
    // ------------------------------------------------------------------

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_ANDROID_PERMS -> {
                val callback = pendingAndroidCallback ?: return
                val requested = pendingAndroidPerms
                pendingAndroidCallback = null
                pendingAndroidPerms = emptyArray()
                if (requested.all { isGranted(it) }) {
                    callback.grant()
                } else {
                    callback.reject()
                }
            }
            REQ_MEDIA -> {
                val pending = pendingMedia ?: return
                pendingMedia = null
                val video = pending.video?.takeIf {
                    it.source != GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA ||
                        isGranted(Manifest.permission.CAMERA)
                }
                val audio = pending.audio?.takeIf { isGranted(Manifest.permission.RECORD_AUDIO) }
                if (video == null && audio == null) {
                    pending.callback.reject()
                } else {
                    pending.callback.grant(video, audio)
                }
            }
            REQ_NOTIFICATIONS -> {
                // Nothing to do: the grant state is what matters, and
                // the system records it. This request was app-side only.
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE_CHOOSER) return
        if (pendingFilePrompt == null) return
        var results: Array<Uri>? = null
        if (resultCode == Activity.RESULT_OK) {
            val clip = data?.clipData
            val single = data?.data
            results = when {
                clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                single != null -> arrayOf(single)
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
        }
        cameraImageUri = null
        completeFilePrompt(results)
    }

    // ------------------------------------------------------------------
    // Notifications (app-side only) & template update check — the same
    // logic as the WebView template.
    // ------------------------------------------------------------------

    /**
     * If the generator enabled notifications for this app, ask for
     * POST_NOTIFICATIONS once at startup (Android 13+). This is for
     * app-side notices (like the update prompt below); site push
     * notifications go through the WebPushDelegate (Phase 2 transport).
     */
    private fun maybeRequestNotificationPermission() {
        if (!siteConfig.optBoolean("notifications", false)) return
        if (Build.VERSION.SDK_INT < 33) return // install-time granted before 13
        if (!isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
            )
        }
    }

    /**
     * Fetches updateFeedUrl once (background, silent on failure). If
     * the feed reports a templateVersion newer than the one baked
     * into this app's config, prompt the user to regenerate with
     * アプリカ.
     */
    private fun checkForTemplateUpdate() {
        val feedUrl = siteConfig.optString("updateFeedUrl", "")
        if (feedUrl.isEmpty()) return
        val current = siteConfig.optInt("templateVersion", 0)
        if (current <= 0) return
        thread {
            val latest = try {
                val conn = URL(feedUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "WebGenTemplateUpdateCheck")
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                parseLatestTemplateVersion(JSONObject(text))
            } catch (e: Exception) {
                null
            }
            if (latest != null && latest > current && !isFinishing) {
                runOnUiThread { showTemplateUpdateDialog(feedUrl) }
            }
        }
    }

    /**
     * Feed JSON format: {"templateVersion": N} (preferred), or a
     * GitHub release object whose tag_name looks like "template-vN" /
     * "template-N".
     */
    private fun parseLatestTemplateVersion(json: JSONObject): Int? {
        val direct = json.optInt("templateVersion", -1)
        if (direct > 0) return direct
        val tag = json.optString("tag_name", "")
        val m = Regex("template[-_]?v?(\\d+)", RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun showTemplateUpdateDialog(feedUrl: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("新しいバージョンがあります")
            .setMessage("このアプリの新しいバージョンが用意できます。アプリカで再生成してください。")
            .setPositiveButton("アプリカを開く") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(GENERATOR_PACKAGE)
                if (launch != null) {
                    startActivity(launch)
                } else {
                    // Generator not installed: open the releases page instead.
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesPageUrl(feedUrl))))
                    } catch (e: Exception) {
                        // nowhere to go; dialog dismissal is enough
                    }
                }
            }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun releasesPageUrl(feedUrl: String): String {
        val m = Regex("api\\.github\\.com/repos/([^/]+)/([^/]+)").find(feedUrl)
        return if (m != null) {
            "https://github.com/${m.groupValues[1]}/${m.groupValues[2]}/releases"
        } else {
            feedUrl
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val active = activeSession
        val canGoBack = active != null && (canGoBackMap[active] == true)
        when {
            popupSession != null && active === popupSession && !canGoBack -> {
                closePopupSession()
            }
            canGoBack -> active?.goBack()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // GeckoSession has no saveState equivalent; the current URL is
        // kept so a recreated activity reloads the page the user was
        // on (engine storage/cookies persist in the profile).
        outState.putString(STATE_CURRENT_URL, currentUrl)
    }

    override fun onDestroy() {
        if (currentActivity === this) currentActivity = null
        completeFilePrompt(null)
        popupSession?.let {
            try {
                it.close()
            } catch (e: Exception) {
                // already closed
            }
        }
        popupSession = null
        mainSession?.let {
            try {
                it.close()
            } catch (e: Exception) {
                // already closed
            }
        }
        mainSession = null
        activeSession = null
        super.onDestroy()
    }

    private fun readSiteConfig(): JSONObject {
        return try {
            val text = assets.open("site_config.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    companion object {
        private const val TAG = "WebGenGecko"

        // UI accents shared with the generator's brand palette.
        private const val BRAND_TEAL = 0xFF0E9594.toInt()
        private const val BRAND_TEAL_DARK = 0xFF0A6E6D.toInt()
        private const val BRAND_BG = 0xFFF2F8F7.toInt()
        private const val INK = 0xFF173A3A.toInt()
        private const val INK_SUB = 0xFF57726F.toInt()

        /** Schemes rendered/handled by the engine itself, never sent out. */
        private val INTERNAL_SCHEMES = setOf(
            "about", "data", "blob", "file", "content", "javascript", "view-source"
        )

        /** Pull-to-refresh probe extension (assets/webext/ptrprobe). */
        private const val PTR_EXT_URI = "resource://android/assets/webext/ptrprobe/"
        private const val PTR_EXT_ID = "ptrprobe@webgen.template"
        private const val PTR_EXT_APP = "webgenPtrProbe"

        /** The generator app that can regenerate this site app. */
        private const val GENERATOR_PACKAGE = "app.webgen.generator"

        private const val STATE_CURRENT_URL = "webgen.currentUrl"

        /** ChoicePrompt.type for multiple selection (GV 159 has no TYPE_* constants). */
        private const val CHOICE_TYPE_MULTIPLE = 2

        private const val REQ_ANDROID_PERMS = 1001
        private const val REQ_MEDIA = 1002
        private const val REQ_FILE_CHOOSER = 1003
        private const val REQ_NOTIFICATIONS = 1004

        /** The live activity, target of probe-extension port events. */
        @Volatile
        private var currentActivity: MainActivity? = null

        private val probeMessageDelegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                currentActivity?.registerProbePort(port)
            }
        }
    }
}
