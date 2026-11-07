package app.webgen.generator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.webgen.core.ApkBuilder
import app.webgen.core.SelfSignedCert
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** On-device generation pipeline: template copy -> patch -> sign -> output. */
object GeneratorEngine {

    /**
     * versionCode of the bundled template.apk. MUST be kept in sync with
     * build_template.sh (--version-code). Recorded into every generated
     * app's site_config.json and into the history, where it drives the
     * "update available" marker and the regenerate flow.
     */
    const val TEMPLATE_VERSION = 3

    /**
     * Generator app versionName (kept in sync with build_generator_push.sh).
     * Numbering restarted at 1.0.0 with the move to the paprica repo;
     * the Android versionCode keeps increasing monotonically (this
     * build is versionCode 4, following 1.0.0's versionCode 3).
     */
    const val APP_VERSION_NAME = "1.1.0"

    /** Settings key: max number of history entries (Phase 5). */
    const val KEY_GEN_LIMIT = "gen_limit"
    const val DEFAULT_GEN_LIMIT = 10

    fun generationLimit(ctx: Context): Int {
        val v = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(KEY_GEN_LIMIT, DEFAULT_GEN_LIMIT)
        return v.coerceIn(1, 100)
    }

    private val DENSITIES = listOf(
        "mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192
    )

    fun generatedDir(ctx: Context): File {
        val dir = GenFileProvider.generatedDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Generates (and signs) a site-app APK inside the device.
     * Must be called from a background thread (file IO + RSA key generation
     * on first launch).
     */
    fun generate(
        ctx: Context,
        url: String,
        appName: String,
        packageName: String,
        icon: Bitmap?
    ): File = generate(ctx, url, appName, packageName, icon, null, null)

    /**
     * @param permissions per-site capability toggles as a JSONObject with
     *                    boolean keys microphone / camera / location
     *                    (missing keys count as OFF); null means all OFF.
     *                    Notifications are NOT taken from this object:
     *                    since v1.1.0 generated apps always get
     *                    notifications enabled (see below).
     * @param updateFeedUrl optional update feed URL baked into the
     *                    generated app's config; null disables the
     *                    generated app's own update check.
     */
    fun generate(
        ctx: Context,
        url: String,
        appName: String,
        packageName: String,
        icon: Bitmap?,
        permissions: JSONObject?,
        updateFeedUrl: String?
    ): File = generate(ctx, url, appName, packageName, icon, permissions, updateFeedUrl, "hub")

    /**
     * @param pushMode push delivery model: "hub" (Pアプリカ経由) or
     *                 "self" (アプリ内蔵); written into the generated
     *                 app's site_config.json as "pushMode" (always
     *                 explicitly, Phase 5).
     */
    fun generate(
        ctx: Context,
        url: String,
        appName: String,
        packageName: String,
        icon: Bitmap?,
        permissions: JSONObject?,
        updateFeedUrl: String?,
        pushMode: String
    ): File {
        // 1. extract bundled template.apk from assets (small; copy every time
        //    so an app update can never leave a stale template behind)
        val templateFile = File(ctx.cacheDir, "template.apk")
        ctx.assets.open("template.apk").use { input ->
            templateFile.outputStream().use { out -> input.copyTo(out) }
        }

        // 2. signing key material (created on first launch, then reused)
        val keys = SelfSignedCert.loadOrCreate(File(ctx.filesDir, "signing"))

        // 3. icon PNGs per density
        val iconMap: Map<String, ByteArray>? = icon?.let { bmp ->
            val map = HashMap<String, ByteArray>()
            for ((density, size) in DENSITIES) {
                val scaled = Bitmap.createScaledBitmap(bmp, size, size, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
                map[density] = baos.toByteArray()
                if (scaled !== bmp) scaled.recycle()
            }
            map
        }

        // 4. patch + sign
        val options = ApkBuilder.BuildOptions().apply {
            microphone = permissions?.optBoolean("microphone", false) ?: false
            camera = permissions?.optBoolean("camera", false) ?: false
            location = permissions?.optBoolean("location", false) ?: false
            // Always ON (user decision, v1.1.0): Pアプリカ has no
            // notifications toggle. Forcing it here — rather than
            // trusting the caller's JSON — also makes re-generation
            // of pre-1.1.0 history entries (which may carry
            // notifications=false) produce notifications-enabled
            // apps, and keeps POST_NOTIFICATIONS in the manifest.
            notifications = true
            templateVersion = TEMPLATE_VERSION
            this.updateFeedUrl = updateFeedUrl
            this.pushMode = if (pushMode == "self") "self" else "hub"
        }
        val outFile = File(generatedDir(ctx), sanitizeFileName(appName) + ".apk")
        ApkBuilder.buildAndSign(
            templateFile, outFile, url, appName, packageName, iconMap, keys, options
        )
        return outFile
    }

    /** Largest launcher icon of a previously generated APK (for 再生成). */
    fun extractIcon(apkFile: File): Bitmap? {
        if (!apkFile.exists()) return null
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("res/mipmap-xxxhdpi-v4/ic_launcher.png")
                    ?: return null
                zip.getInputStream(entry).use { input -> BitmapFactory.decodeStream(input) }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_', '.', ' ')
        return if (cleaned.isEmpty()) "site-app" else cleaned.take(60)
    }
}
