package app.webgen.generator

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Shared GitHub Releases update check for the PUSH edition generator
 * (Pアプリカ). Used silently at startup (MainActivity) and manually
 * from the settings page (SettingsActivity).
 *
 * Since the repository split, push-edition releases live in their own
 * repo ([DEFAULT_REPO] = yorukaze-luru/paprica); the version numbering
 * restarted at 1.0.0 there (the aprica repo carried this edition up to
 * push-v2.0.0 under the old shared-repo scheme). This checker queries
 * the releases LIST of [DEFAULT_REPO] and considers only:
 *  - tags matching ^v\d+\.\d+\.\d+$,
 *  - within a release, only the asset named exactly
 *    paprica-v<that-version>.apk (anchored full-name match), and
 *  - releases without that asset are ignored entirely.
 * The highest such version is reported; callers compare it against
 * [GeneratorEngine.APP_VERSION_NAME] and only offer an update when it
 * is strictly greater.
 *
 * [feedUrl] is the URL written into GENERATED apps as their
 * template-update feed, built from the same configured repo.
 */
object UpdateChecker {

    const val DEFAULT_REPO = "yorukaze-luru/paprica"

    fun feedUrl(repo: String): String =
        "https://api.github.com/repos/$repo/releases/latest"

    private fun releasesListUrl(repo: String): String =
        "https://api.github.com/repos/$repo/releases?per_page=100"

    private val TAG_RE = Regex("""^v\d+\.\d+\.\d+$""")

    /**
     * Fetches the newest push-edition release on a background thread.
     * Callback parameters: (tag, release page URL, asset URL, error
     * message); exactly one of tag / error is non-null. The callback
     * runs on the background thread; callers must hop to the UI thread
     * themselves.
     */
    fun fetchLatest(
        repo: String,
        callback: (tag: String?, pageUrl: String?, assetUrl: String?, error: String?) -> Unit
    ) {
        thread {
            try {
                val conn = URL(releasesListUrl(repo)).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val releases = org.json.JSONArray(text)
                var bestTag: String? = null
                var bestVersion: String? = null
                var bestPageUrl: String? = null
                var bestAssetUrl: String? = null
                for (i in 0 until releases.length()) {
                    val rel = releases.optJSONObject(i) ?: continue
                    val tag = rel.optString("tag_name", "")
                    if (!TAG_RE.matches(tag)) continue
                    val version = tag.removePrefix("v")
                    if (bestVersion != null &&
                        compareVersions(version, bestVersion!!) <= 0
                    ) continue
                    val wantAsset = "paprica-v$version.apk"
                    var assetUrl: String? = null
                    val assets = rel.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val a = assets.optJSONObject(j) ?: continue
                            if (a.optString("name") == wantAsset) {
                                assetUrl = a.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    if (assetUrl == null) continue // no push asset: ignore release
                    bestTag = tag
                    bestVersion = version
                    bestPageUrl = rel.optString(
                        "html_url", "https://github.com/$repo/releases"
                    )
                    bestAssetUrl = assetUrl
                }
                if (bestTag == null) {
                    callback(null, null, null, "リリースが見つかりませんでした")
                } else {
                    callback(bestTag, bestPageUrl, bestAssetUrl, null)
                }
            } catch (e: Exception) {
                callback(null, null, null, e.message ?: e.javaClass.simpleName)
            }
        }
    }
    /**
     * Downloads an APK (e.g. a newer Pアプリカ release) into the app's
     * cache on a background thread, for the in-app update flow.
     *
     * [onProgress] runs on the background thread with
     * (bytes downloaded so far, total bytes); the total is -1 when the
     * server did not report a size. It fires when the whole-percent
     * value changes or at least every ~200 ms, plus once when the
     * stream ends, so the settings page can show live progress (v1.2).
     * [callback]: (downloaded file, error message); also on the
     * background thread.
     */
    fun downloadApk(
        ctx: android.content.Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        callback: (file: java.io.File?, error: String?) -> Unit
    ) {
        thread {
            try {
                val dir = java.io.File(ctx.cacheDir, "update").apply { mkdirs() }
                val out = java.io.File(dir, "aprica-update.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReportAt = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            val percent = if (total > 0) {
                                (downloaded * 100 / total).toInt()
                            } else {
                                -1
                            }
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent || now - lastReportAt >= 200) {
                                lastPercent = percent
                                lastReportAt = now
                                onProgress(downloaded, total)
                            }
                        }
                        onProgress(downloaded, total)
                    }
                }
                if (out.length() == 0L) throw java.io.IOException("empty download")
                callback(out, null)
            } catch (e: Exception) {
                callback(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Numeric dotted-version compare: "v1.10" > "1.2" etc. */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parseVersion(v: String): List<Int> {
        // Tags in the paprica repo look like "v1.2.0"; strip the leading
        // "v" so they compare numerically against "1.0.0" etc.
        val core = v.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-")
        return core.split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
