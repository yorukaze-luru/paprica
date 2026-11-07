package app.webgen.updist

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Durable registration store for the distributor (contract §3.4 / §8.4):
 * one entry per connector `token` (the only correlation key on the
 * wire), each holding the client package it belongs to and the ntfy
 * topic allocated for it. Persisted in this package's own
 * SharedPreferences file so registrations survive process death and
 * device restarts; the relay service re-subscribes all stored topics
 * on every start.
 */
object DistributorStore {

    data class Registration(
        val token: String,
        val clientPackage: String,
        val topic: String,
        val message: String?,
        val vapid: String?
    ) {
        /** Endpoint handed to the connector in NEW_ENDPOINT (§5.2 /
         *  §8.1): ntfy's observed form, stable while this
         *  registration lives. */
        val endpoint: String get() = "https://ntfy.sh/$topic?up=1"
    }

    private const val PREFS = "updist_registrations"
    private const val KEY_PREFIX = "reg."

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, token: String): Registration? {
        val raw = prefs(ctx).getString(KEY_PREFIX + token, null) ?: return null
        return try {
            val o = JSONObject(raw)
            Registration(
                token = token,
                clientPackage = o.getString("package"),
                topic = o.getString("topic"),
                message = o.optString("message").ifEmpty { null },
                vapid = o.optString("vapid").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun all(ctx: Context): List<Registration> =
        prefs(ctx).all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { get(ctx, it.removePrefix(KEY_PREFIX)) }

    fun findByTopic(ctx: Context, topic: String): Registration? =
        all(ctx).firstOrNull { it.topic == topic }

    /**
     * Idempotent insert/update keyed by token (§3.4): a re-REGISTER
     * with an existing token keeps its topic (and therefore its
     * endpoint) and only refreshes the stored metadata.
     */
    fun register(
        ctx: Context,
        token: String,
        clientPackage: String,
        message: String?,
        vapid: String?
    ): Registration {
        val existing = get(ctx, token)
        val reg = Registration(
            token = token,
            clientPackage = clientPackage,
            topic = existing?.topic ?: newTopic(),
            message = message,
            vapid = vapid
        )
        val o = JSONObject().apply {
            put("package", reg.clientPackage)
            put("topic", reg.topic)
            put("message", reg.message ?: "")
            put("vapid", reg.vapid ?: "")
        }
        prefs(ctx).edit().putString(KEY_PREFIX + token, o.toString()).apply()
        return reg
    }

    fun remove(ctx: Context, token: String): Registration? {
        val reg = get(ctx, token)
        prefs(ctx).edit().remove(KEY_PREFIX + token).apply()
        return reg
    }

    /**
     * ntfy topic shape, mirroring what ntfy itself issues (observed
     * in the wild: "up" + 12 random alphanumeric characters, e.g.
     * `upr5mYIJURiu4q`). The topic is the shared secret of the
     * endpoint, so it is generated with [SecureRandom].
     */
    private fun newTopic(): String {
        val alphabet =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val sb = StringBuilder("up")
        repeat(12) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }
}
