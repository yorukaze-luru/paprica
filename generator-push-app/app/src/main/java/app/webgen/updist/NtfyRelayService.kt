package app.webgen.updist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Relay: ONE persistent connection to ntfy.sh covering ALL
 * registered topics at once, via ntfy's dependency-free JSON stream
 * (`GET https://ntfy.sh/<topic1,topic2,...>/json`, one JSON object
 * per line) over [HttpsURLConnection] — no WebSocket framing and no
 * third-party libraries, matching this project's manual no-Gradle
 * build.
 *
 * On a `message` event the base64 `message` field is decoded back to
 * the raw push bytes and broadcast to the owning app package ONLY
 * (contract §5.2 MESSAGE). Reconnects use capped exponential
 * backoff; an [ACTION_REFRESH] start (new registration set) drops
 * the current stream so it re-opens with the current topic list.
 *
 * Runs as a foreground service (type remoteMessaging) with a
 * persistent notification whose label is the host-supplied string
 * persisted via [UpDistributor.start].
 */
class NtfyRelayService : Service() {

    @Volatile
    private var running = false

    @Volatile
    private var connection: HttpURLConnection? = null

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UpDistributor.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-assert the foreground notification (label may have been
        // persisted after onCreate).
        startForegroundCompat()
        if (intent?.action == ACTION_REFRESH) {
            // Registration set changed: force the stream loop to
            // reconnect with the current topics.
            disconnect()
        }
        ensureWorker()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        disconnect()
        UpDistributor.setConnectionState(this, UpDistributor.STATE_STOPPED)
        super.onDestroy()
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        running = true
        worker = thread(name = "updist-ntfy-relay") { relayLoop() }
    }

    private fun disconnect() {
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // already gone
        }
        connection = null
    }

    private fun relayLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running) {
            val topics = DistributorStore.all(this).map { it.topic }.distinct()
            if (topics.isEmpty()) {
                UpDistributor.setConnectionState(this, UpDistributor.STATE_WAITING)
                sleep(2_000)
                continue
            }
            UpDistributor.setConnectionState(this, UpDistributor.STATE_CONNECTING)
            try {
                streamOnce(topics)
                backoffMs = INITIAL_BACKOFF_MS
            } catch (e: Exception) {
                Log.w(UpDistributor.TAG, "ntfy stream error: ${e.message}")
            }
            disconnect()
            if (!running) break
            UpDistributor.setConnectionState(this, UpDistributor.STATE_CONNECTING)
            sleep(backoffMs)
            backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    /** One stream session; returns when the stream ends or errors. */
    private fun streamOnce(topics: List<String>) {
        val url = URL("https://ntfy.sh/" + topics.joinToString(",") + "/json")
        val conn = url.openConnection() as HttpsURLConnection
        connection = conn
        conn.connectTimeout = 15_000
        // ntfy emits keepalive events on the JSON stream; a read
        // timeout comfortably above that cadence detects dead
        // connections without dropping healthy idle ones.
        conn.readTimeout = 150_000
        conn.setRequestProperty("Accept", "application/x-ndjson")
        conn.connect()
        if (conn.responseCode != 200) {
            throw IllegalStateException("ntfy stream HTTP ${conn.responseCode}")
        }
        UpDistributor.setConnectionState(this, UpDistributor.STATE_CONNECTED)
        Log.i(UpDistributor.TAG, "ntfy stream connected: ${topics.size} topic(s)")
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        while (running) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            handleEvent(line)
        }
    }

    private fun handleEvent(line: String) {
        val event = try {
            JSONObject(line)
        } catch (e: Exception) {
            return
        }
        when (event.optString("event")) {
            "message" -> {
                val topic = event.optString("topic")
                val reg = DistributorStore.findByTopic(this, topic) ?: run {
                    Log.w(UpDistributor.TAG, "message for unknown topic=$topic; dropped")
                    return
                }
                // The push body travels base64-encoded in ntfy's
                // JSON stream; decode to the RAW bytes (§8.3) — the
                // connector expects the untouched aes128gcm blob.
                val b64 = event.optString("message")
                val bytes = try {
                    if (b64.isEmpty()) ByteArray(0) else Base64.decode(b64, Base64.DEFAULT)
                } catch (e: Exception) {
                    Log.w(UpDistributor.TAG, "base64 decode failed: ${e.message}")
                    return
                }
                DistributorReplies.sendMessage(
                    this, reg, bytes, event.optString("id").ifEmpty { null }
                )
            }
            // "open" / "keepalive" / "poll_request": no action needed.
            else -> Unit
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // reconnect sooner; loop re-checks `running`
        }
    }

    // ----------------------------------------------------------------
    // foreground-service plumbing
    // ----------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "プッシュ配信",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = if (launch != null) {
            android.app.PendingIntent.getActivity(
                this, 0,
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(UpDistributor.notificationLabel(this))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            // targetSdk 34 hosts must pass the declared type
            // (remoteMessaging) explicitly.
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_RUN = "app.webgen.updist.RUN"
        const val ACTION_REFRESH = "app.webgen.updist.REFRESH"

        private const val CHANNEL_ID = "updist_relay"
        private const val NOTIFICATION_ID = 4201
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
