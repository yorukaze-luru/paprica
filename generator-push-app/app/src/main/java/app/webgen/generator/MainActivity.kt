package app.webgen.generator

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var pkgInput: EditText
    private lateinit var permMic: Switch
    private lateinit var permCamera: Switch
    private lateinit var permLocation: Switch
    private lateinit var pushModeHub: RadioButton
    private lateinit var pushModeSelf: RadioButton
    private lateinit var iconPreview: ImageView
    private lateinit var iconStatus: TextView
    private lateinit var statusText: TextView
    private lateinit var generateButton: Button
    private lateinit var historyList: LinearLayout
    private lateinit var formScroll: android.widget.ScrollView

    private var selectedIcon: Bitmap? = null
    private var pkgUserEdited = false
    private var settingPkg = false

    private val history = mutableListOf<JSONObject>()

    private var appliedThemeMode = -1

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePrefs.wrap(newBase))
        appliedThemeMode = ThemePrefs.getMode(newBase)
    }

    private fun cInk() =
        if (ThemePrefs.isNight(this)) 0xFFE8F4F1.toInt() else 0xFF173A3A.toInt()

    private fun cSub() =
        if (ThemePrefs.isNight(this)) 0xFF9DB8B3.toInt() else 0xFF57726F.toInt()

    private fun cAccentText() =
        if (ThemePrefs.isNight(this)) 0xFF35D0BA.toInt() else 0xFF0A6E6D.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        nameInput = findViewById(R.id.nameInput)
        pkgInput = findViewById(R.id.pkgInput)
        permMic = findViewById(R.id.permMic)
        permCamera = findViewById(R.id.permCamera)
        permLocation = findViewById(R.id.permLocation)
        pushModeHub = findViewById(R.id.pushModeHub)
        pushModeSelf = findViewById(R.id.pushModeSelf)
        pushModeHub.isChecked = true
        iconPreview = findViewById(R.id.iconPreview)
        iconStatus = findViewById(R.id.iconStatus)
        statusText = findViewById(R.id.statusText)
        generateButton = findViewById(R.id.generateButton)
        historyList = findViewById(R.id.historyList)
        formScroll = findViewById(R.id.formScroll)

        pkgInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!settingPkg) pkgUserEdited = true
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!pkgUserEdited) {
                    val suggestion = suggestPackage(urlInput.text.toString())
                    if (suggestion.isNotEmpty()) {
                        settingPkg = true
                        pkgInput.setText(suggestion)
                        settingPkg = false
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<Button>(R.id.faviconButton).setOnClickListener { fetchFavicon() }
        findViewById<Button>(R.id.pickButton).setOnClickListener { pickImage() }
        generateButton.setOnClickListener { generate() }

        // Settings live on their own page (hamburger button).
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Permission toggles start from the defaults chosen in Settings.
        applyPermissionDefaults()

        // Built-in UnifiedPush distributor (Phase 5): keep the ntfy
        // relay foreground service running while the distributor is
        // enabled, and make sure its persistent notification can be
        // shown (POST_NOTIFICATIONS, API 33+).
        app.webgen.updist.UpDistributor.start(this, DIST_NOTIFICATION_LABEL)
        if (app.webgen.updist.UpDistributor.isEnabled(this) &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            @Suppress("DEPRECATION")
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS
            )
        }

        loadHistory()
        refreshHistory()

        // Silent update check at startup (repo is baked in since v1.3).
        checkGeneratorUpdate(manual = false)
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    /**
     * "owner/name" used for update checks and as the update feed written
     * into generated apps. Since v1.3 the official repo is baked in
     * ([UpdateChecker.DEFAULT_REPO]); a repo saved by an older version
     * is still honored when present.
     */
    private fun configuredRepo(): String {
        val saved = prefs().getString(KEY_REPO, null)?.trim()
        return if (saved != null && REPO_RE.matches(saved)) saved
        else UpdateChecker.DEFAULT_REPO
    }

    private fun updateFeedUrlFor(repo: String): String {
        return UpdateChecker.feedUrl(repo)
    }

    // ------------------------------------------------------------------
    // permission defaults (edited on the settings page)
    // ------------------------------------------------------------------

    private var appliedDefaultsRev = -1L

    private fun applyPermissionDefaults() {
        val p = prefs()
        permMic.isChecked = p.getBoolean(KEY_DEF_MIC, false)
        permCamera.isChecked = p.getBoolean(KEY_DEF_CAMERA, false)
        permLocation.isChecked = p.getBoolean(KEY_DEF_LOCATION, false)
        appliedDefaultsRev = p.getLong(KEY_DEF_REV, 0)
    }

    override fun onResume() {
        super.onResume()
        if (ThemePrefs.getMode(this) != appliedThemeMode) {
            recreate()
            return
        }
        // Re-apply only when the defaults were edited in Settings,
        // so per-app tweaks on this form survive other round trips.
        if (::permMic.isInitialized &&
            prefs().getLong(KEY_DEF_REV, 0) != appliedDefaultsRev
        ) {
            applyPermissionDefaults()
        }
        // Install state may have changed while we were away
        // (install finished, app uninstalled, ...).
        if (::historyList.isInitialized) refreshHistory()
    }

    // ------------------------------------------------------------------
    // package suggestion
    // ------------------------------------------------------------------

    private fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    private fun hostOf(rawUrl: String): String? {
        return try {
            URI(normalizeUrl(rawUrl)).host
        } catch (e: Exception) {
            null
        }
    }

    private fun suggestPackage(rawUrl: String): String {
        val host = hostOf(rawUrl) ?: return ""
        val labels = host.removePrefix("www.").split(".")
            .map { part ->
                var p = part.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
                if (p.isNotEmpty() && p[0].isDigit()) p = "a$p"
                p
            }
            .filter { it.isNotEmpty() }
        if (labels.isEmpty()) return ""
        // Push edition: the regular proposal gets a ".push" segment
        // appended, so a site app generated here can coexist with the
        // same site generated by the regular edition.
        return "app.webgen." + labels.reversed().joinToString(".") + ".push"
    }

    // ------------------------------------------------------------------
    // icon acquisition
    // ------------------------------------------------------------------

    private fun setIcon(bmp: Bitmap, status: String) {
        selectedIcon = bmp
        iconPreview.setImageBitmap(bmp)
        iconStatus.text = status
    }

    private fun fetchFavicon() {
        val rawUrl = urlInput.text.toString()
        if (rawUrl.isBlank()) {
            toast("先にサイトURLを入力してください")
            return
        }
        iconStatus.text = "アイコンを取得しています…"
        thread {
            val bmp = try {
                fetchSiteIcon(normalizeUrl(rawUrl))
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bmp != null) {
                    setIcon(bmp, "サイトのアイコンを取得しました（${bmp.width}×${bmp.height}）")
                } else {
                    iconStatus.text = "サイトのアイコンを取得できませんでした（頭文字アイコンが自動生成されます）"
                }
            }
        }
    }

    private fun fetchSiteIcon(pageUrl: String): Bitmap? {
        val candidates = mutableListOf<String>()
        // 1) candidates declared in the page HTML (apple-touch-icon preferred)
        try {
            val html = httpGetText(pageUrl, 200_000)
            if (html != null) {
                val tagRe = Regex("<link[^>]+>", RegexOption.IGNORE_CASE)
                val hrefRe = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                var apple: String? = null
                var icon: String? = null
                for (m in tagRe.findAll(html)) {
                    val tag = m.value
                    val href = hrefRe.find(tag)?.groupValues?.get(1) ?: continue
                    when {
                        tag.contains("apple-touch-icon", ignoreCase = true) ->
                            if (apple == null) apple = href
                        tag.contains("icon", ignoreCase = true) ->
                            if (icon == null) icon = href
                    }
                }
                apple?.let { candidates.add(URL(URL(pageUrl), it).toString()) }
                icon?.let { candidates.add(URL(URL(pageUrl), it).toString()) }
            }
        } catch (e: Exception) {
            // fall through to well-known paths
        }
        // 2) well-known paths
        val base = URL(pageUrl)
        val root = "${base.protocol}://${base.host}" + if (base.port > 0) ":${base.port}" else ""
        candidates.add("$root/apple-touch-icon.png")
        candidates.add("$root/favicon.png")
        candidates.add("$root/favicon.ico")

        for (candidate in candidates.distinct()) {
            try {
                val bmp = httpGetBitmap(candidate)
                if (bmp != null && bmp.width >= 16 && bmp.height >= 16) return bmp
            } catch (e: Exception) {
                // try next candidate
            }
        }
        return null
    }

    private fun openConn(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) WebGen")
        return conn
    }

    private fun httpGetText(url: String, maxChars: Int): String? {
        val conn = openConn(url)
        conn.inputStream.use { input ->
            val bytes = input.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            return if (text.length > maxChars) text.substring(0, maxChars) else text
        }
    }

    private fun httpGetBitmap(url: String): Bitmap? {
        val conn = openConn(url)
        conn.inputStream.use { input ->
            return BitmapFactory.decodeStream(input)
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "アイコン画像を選ぶ"), REQ_PICK_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    if (bmp != null) {
                        setIcon(bmp, "端末の画像をアイコンに設定しました（${bmp.width}×${bmp.height}）")
                    } else {
                        toast("画像を読み込めませんでした")
                    }
                }
            } catch (e: Exception) {
                toast("画像の読み込みに失敗しました: ${e.message}")
            }
        }
    }

    private fun makeInitialIcon(name: String): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colors = intArrayOf(
            0xFF3F51B5.toInt(), 0xFF00897B.toInt(), 0xFF6A1B9A.toInt(),
            0xFFAD1457.toInt(), 0xFFEF6C00.toInt(), 0xFF2E7D32.toInt(),
            0xFF1565C0.toInt(), 0xFF4E342E.toInt()
        )
        val bg = colors[Math.floorMod(name.hashCode(), colors.size)]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 112f, 112f, paint)
        val letter = name.trim().firstOrNull()?.toString() ?: "W"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 250f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val metrics = textPaint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(letter, size / 2f, baseline, textPaint)
        return bmp
    }

    // ------------------------------------------------------------------
    // generation
    // ------------------------------------------------------------------

    private fun generate() {
        // Generation limit (Phase 5): a NEW generation is blocked when
        // the history already holds >= the configured limit entries.
        // Lowering the limit never deletes anything; it only blocks
        // until entries are deleted or the limit is raised again.
        // 複製 goes through this same generate() path (it only fills
        // the form), so a duplicated generation counts too; 再生成
        // updates an existing entry in place and does NOT count.
        val limit = GeneratorEngine.generationLimit(this)
        if (history.size >= limit) {
            AlertDialog.Builder(this)
                .setTitle("生成できるアプリの上限に達しています")
                .setMessage(
                    "履歴が上限の $limit 件に達しているため、新しいアプリを生成できません。" +
                        "履歴の項目を削除するか、設定ページで「生成できるアプリの上限」を" +
                        "引き上げると、また生成できるようになります。"
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val rawUrl = urlInput.text.toString().trim()
        if (rawUrl.isEmpty()) {
            toast("サイトURLを入力してください")
            return
        }
        val url = normalizeUrl(rawUrl)
        var appName = nameInput.text.toString().trim()
        if (appName.isEmpty()) {
            val host = hostOf(url)
            appName = host?.removePrefix("www.")?.split(".")?.firstOrNull()
                ?.replaceFirstChar { it.uppercase() } ?: "Site App"
            nameInput.setText(appName)
        }
        val pkg = pkgInput.text.toString().trim()
            .ifEmpty { suggestPackage(url) }
        if (!PACKAGE_RE.matches(pkg)) {
            statusText.text = "パッケージ名が不正です: $pkg（例: app.webgen.com.example）"
            return
        }
        if (pkg == "app.webgen.geckotemplate" || pkg == "app.webgen.generator.push" ||
            pkg == "app.webgen.template" || pkg == "app.webgen.generator"
        ) {
            statusText.text = "そのパッケージ名は予約済みです。別の名前にしてください"
            return
        }
        val icon = selectedIcon ?: makeInitialIcon(appName).also { iconPreview.setImageBitmap(it) }
        val perms = currentPermissionToggles()
        val pushMode = if (pushModeSelf.isChecked) "self" else "hub"
        val repo = configuredRepo()
        val feedUrl = updateFeedUrlFor(repo)

        generateButton.isEnabled = false
        statusText.text = "生成しています…（初回は署名キーの生成に少し時間がかかります）"
        thread {
            try {
                val file = GeneratorEngine.generate(this, url, appName, pkg, icon, perms, feedUrl, pushMode)
                val entry = JSONObject().apply {
                    put("name", appName)
                    put("url", url)
                    put("pkg", pkg)
                    put("path", file.absolutePath)
                    put("created", System.currentTimeMillis())
                    put("templateVersion", GeneratorEngine.TEMPLATE_VERSION)
                    put("perms", perms)
                    put("repo", repo)
                    put("pushMode", pushMode)
                }
                runOnUiThread {
                    history.add(0, entry)
                    saveHistory()
                    refreshHistory()
                    generateButton.isEnabled = true
                    statusText.text = "生成しました: ${file.absolutePath}"
                    toast("APKを生成しました")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    generateButton.isEnabled = true
                    statusText.text = "生成に失敗しました: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    private fun currentPermissionToggles(): JSONObject {
        return JSONObject().apply {
            put("microphone", permMic.isChecked)
            put("camera", permCamera.isChecked)
            put("location", permLocation.isChecked)
            // Notifications are always allowed in Pアプリカ
            // (v1.1.0): there is no toggle; generated apps always
            // keep POST_NOTIFICATIONS so Web Push can work.
            put("notifications", true)
        }
    }

    /**
     * Rebuilds an existing history entry with the CURRENT bundled template
     * (same URL / name / package / permissions). The icon is recovered
     * from the previously generated APK when possible. The entry is
     * updated in place; installing the result uses the normal flow.
     */
    private fun regenerate(entry: JSONObject, thenInstall: Boolean = false) {
        val url = entry.optString("url")
        val appName = entry.optString("name")
        val pkg = entry.optString("pkg")
        if (url.isEmpty() || pkg.isEmpty()) {
            toast("再生成に必要な情報が履歴にありません")
            return
        }
        val perms = entry.optJSONObject("perms") ?: JSONObject()
        val pushMode = entry.optString("pushMode").ifEmpty { "hub" }
        val repo = entry.optString("repo").ifEmpty { configuredRepo() }
        val feedUrl = if (repo.isNotEmpty()) updateFeedUrlFor(repo) else null
        val oldFile = File(entry.optString("path"))
        val icon = GeneratorEngine.extractIcon(oldFile) ?: makeInitialIcon(appName)

        statusText.text = "再生成しています…: $appName"
        thread {
            try {
                val file = GeneratorEngine.generate(this, url, appName, pkg, icon, perms, feedUrl, pushMode)
                runOnUiThread {
                    entry.put("path", file.absolutePath)
                    entry.put("created", System.currentTimeMillis())
                    entry.put("templateVersion", GeneratorEngine.TEMPLATE_VERSION)
                    if (repo.isNotEmpty()) entry.put("repo", repo)
                    saveHistory()
                    refreshHistory()
                    if (thenInstall) {
                        statusText.text = "再生成しました: ${file.absolutePath}"
                        installApk(file, pkg)
                    } else {
                        statusText.text = "再生成しました: ${file.absolutePath}（「インストール」から上書きできます）"
                        toast("再生成しました")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "再生成に失敗しました: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // generator self update check (GitHub Releases)
    // ------------------------------------------------------------------

    private fun checkGeneratorUpdate(manual: Boolean) {
        if (manual) statusText.text = "更新を確認しています…"
        UpdateChecker.fetchLatest(configuredRepo()) { tag, pageUrl, assetUrl, error ->
            runOnUiThread {
                when {
                    error != null -> {
                        if (manual) {
                            statusText.text = "更新の確認に失敗しました: $error"
                        }
                    }
                    tag.isNullOrEmpty() -> {
                        if (manual) statusText.text = "リリースが見つかりませんでした"
                    }
                    UpdateChecker.compareVersions(
                        tag!!, GeneratorEngine.APP_VERSION_NAME
                    ) > 0 -> {
                        UpdateFlow.prompt(this, tag!!, pageUrl!!, assetUrl)
                    }
                    else -> {
                        if (manual) {
                            statusText.text =
                                "最新です（Pアプリカ v${GeneratorEngine.APP_VERSION_NAME}）"
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // history
    // ------------------------------------------------------------------

    private fun historyFile() = File(filesDir, "history.json")

    private fun loadHistory() {
        try {
            val f = historyFile()
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) history.add(arr.getJSONObject(i))
            }
        } catch (e: Exception) {
            // corrupted history is non-fatal; start fresh
        }
    }

    private fun saveHistory() {
        try {
            val arr = JSONArray()
            for (e in history) arr.put(e)
            historyFile().writeText(arr.toString())
        } catch (e: Exception) {
            // ignore: generation result itself is already on disk
        }
    }

    private fun refreshHistory() {
        historyList.removeAllViews()
        if (history.isEmpty()) {
            val tv = TextView(this).apply {
                text = "まだ生成したAPKはありません"
                setTextColor(cSub())
            }
            historyList.addView(tv)
            return
        }
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
        for (entry in history) {
            val name = entry.optString("name")
            val file = File(entry.optString("path"))
            val density = resources.displayMetrics.density
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val pad = (14 * density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() }
            }
            container.addView(TextView(this).apply {
                text = name
                setTextColor(cInk())
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val entryTemplate = entry.optInt("templateVersion", 0)
            val updateAvailable = entryTemplate < GeneratorEngine.TEMPLATE_VERSION
            container.addView(TextView(this).apply {
                setTextColor(cSub())
                text = "${entry.optString("url")}\n" +
                    "${entry.optString("pkg")} · ${fmt.format(Date(entry.optLong("created")))}" +
                    "\nテンプレート v$entryTemplate" +
                    (if (updateAvailable) " → v${GeneratorEngine.TEMPLATE_VERSION} に更新できます" else "") +
                    "\n配信方式: " +
                    (if (entry.optString("pushMode") == "self") "アプリ内蔵" else "Pアプリカ経由") +
                    if (file.exists()) "" else "\n（ファイルが見つかりません）"
                textSize = 12f
            })
            if (updateAvailable) {
                container.addView(TextView(this).apply {
                    text = "★ 更新あり（新しいテンプレートで作り直せます）"
                    setTextColor(cAccentText())
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
            val entryPkg = entry.optString("pkg")
            when {
                updateAvailable ->
                    row.addView(smallButton("アップデート") {
                        regenerate(entry, thenInstall = true)
                    })
                isInstalled(entryPkg) ->
                    row.addView(smallButton("起動") { launchApp(entryPkg) })
                else ->
                    row.addView(smallButton("インストール") { installApk(file, entryPkg) })
            }
            row.addView(smallButton("複製") { duplicateToForm(entry) })
            row.addView(smallButton("削除") { confirmDelete(entry, file) })
            container.addView(row)
            historyList.addView(container)
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setBackgroundResource(R.drawable.bg_button_secondary)
            setTextColor(cAccentText())
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                val m = (3 * resources.displayMetrics.density).toInt()
                marginStart = m; marginEnd = m
            }
            setOnClickListener { onClick() }
        }
    }

    /**
     * Installing a generated APK from アプリカ first shows a short
     * Play Protect heads-up (v1.3): sideloaded apps may trigger a
     * Google Play Protect confirmation, which no app can suppress or
     * scope. The guidance is shown only here, i.e. only when installing
     * a generated app from within アプリカ.
     */
    private fun installApk(file: File, pkg: String) {
        if (!file.exists()) {
            toast("ファイルが見つかりません")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("インストールの前に")
            .setMessage(
                "このあと Android のインストール確認が開きます。" +
                    "Google Play プロテクトの確認画面が出た場合は、内容を確認して" +
                    "「そのままインストール」を選んでください。" +
                    "（ストア外のアプリへの標準の確認で、アプリ側では消せません）"
            )
            .setPositiveButton("インストールに進む") { _, _ -> Installer.install(this, file, pkg) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** Installed-state of a generated app. Visibility of launcher
     *  apps is declared via <queries> in the manifest. */
    private fun isInstalled(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("起動できませんでした")
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("起動できませんでした")
        }
    }

    /**
     * 「削除」: always confirm first (the APK file cannot be restored).
     * When the generated app is still installed, offer to uninstall
     * it too — uninstalling is the only way its storage (WebView
     * data, logins) is removed, since another app's private storage
     * cannot be touched from アプリカ. The system shows its own
     * uninstall confirmation afterwards.
     */
    private fun confirmDelete(entry: JSONObject, file: File) {
        val pkg = entry.optString("pkg")
        val name = entry.optString("name")
        val builder = AlertDialog.Builder(this)
            .setTitle("削除しますか？")
            .setNegativeButton("キャンセル", null)
        if (isInstalled(pkg)) {
            builder.setMessage(
                "「$name」の生成APKと履歴を削除します。\n" +
                    "インストール済みのアプリ本体と、そのストレージ（ログイン情報など）も" +
                    "消す場合は「アプリもアンインストール」を選んでください。"
            )
            builder.setNeutralButton("APKと履歴だけ削除") { _, _ ->
                deleteEntry(entry, file)
            }
            builder.setPositiveButton("アプリもアンインストール") { _, _ ->
                deleteEntry(entry, file)
                uninstallApp(pkg)
            }
        } else {
            builder.setMessage("「$name」の生成APKと履歴を削除します。")
            builder.setPositiveButton("削除") { _, _ -> deleteEntry(entry, file) }
        }
        builder.show()
    }

    private fun deleteEntry(entry: JSONObject, file: File) {
        file.delete()
        history.remove(entry)
        saveHistory()
        refreshHistory()
        toast("削除しました")
    }

    private fun uninstallApp(pkg: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("アンインストール画面を開けませんでした")
        }
    }

    /**
     * 「複製」: copy a history entry back into the creation form so the
     * user can generate a variant. The package must differ from the
     * original (same-package apps cannot coexist), so a unique
     * ".copy" suffix is proposed; the icon is carried over from the
     * generated APK when it can be extracted.
     */
    private fun duplicateToForm(entry: JSONObject) {
        urlInput.setText(entry.optString("url"))
        nameInput.setText(entry.optString("name"))
        val basePkg = entry.optString("pkg")
        var candidate = if (basePkg.isNotEmpty()) "$basePkg.copy" else ""
        val taken = history.map { it.optString("pkg") }.toSet()
        var n = 2
        while (candidate in taken) {
            candidate = "$basePkg.copy${n++}"
        }
        pkgUserEdited = true
        settingPkg = true
        pkgInput.setText(candidate)
        settingPkg = false
        if (entry.optString("pushMode") == "self") pushModeSelf.isChecked = true
        else pushModeHub.isChecked = true
        val perms = entry.optJSONObject("perms")
        if (perms != null) {
            permMic.isChecked = perms.optBoolean("microphone")
            permCamera.isChecked = perms.optBoolean("camera")
            permLocation.isChecked = perms.optBoolean("location")
        }
        val icon = GeneratorEngine.extractIcon(File(entry.optString("path")))
        if (icon != null) {
            selectedIcon = icon
            iconPreview.setImageBitmap(icon)
            iconStatus.text = "複製元のアイコンを引き継ぎました"
        } else {
            selectedIcon = null
            iconPreview.setImageBitmap(null)
            iconStatus.text = "アイコン未選択（生成時にアプリ名の頭文字アイコンを自動生成します）"
        }
        formScroll.smoothScrollTo(0, 0)
        toast("フォームに複製しました。内容を確認して「APKを生成」してください")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PICK_IMAGE = 42
        private const val REQ_POST_NOTIFICATIONS = 43

        /** Persistent-notification label for the built-in distributor
         *  (the only app-specific string the updist package takes). */
        const val DIST_NOTIFICATION_LABEL = "Pアプリカ：プッシュ受信待機中"
        private const val KEY_REPO = "gh_repo"
        const val KEY_DEF_MIC = "def_perm_microphone"
        const val KEY_DEF_CAMERA = "def_perm_camera"
        const val KEY_DEF_LOCATION = "def_perm_location"
        const val KEY_DEF_REV = "def_perm_rev"
        private val PACKAGE_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        private val REPO_RE = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    }
}
