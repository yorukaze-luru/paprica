package app.webgen.generator

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * Settings page (v1.3): everything that is not APK creation lives here,
 * reached from the hamburger button on the main screen.
 *
 * - Default values for the permission toggles on the creation form.
 *   Saving bumps a revision counter; MainActivity re-applies the
 *   defaults to its form when the revision changes.
 * - Generator update check against the baked-in GitHub repo
 *   (button only; there is no repo input field).
 * - Notes about installing generated APKs and Google Play Protect.
 */
class SettingsActivity : Activity() {

    private lateinit var updateStatus: TextView

    private var appliedThemeMode = -1

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePrefs.wrap(newBase))
        appliedThemeMode = ThemePrefs.getMode(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupThemeSpinner()

        bindDefaultSwitch(R.id.defMic, MainActivity.KEY_DEF_MIC)
        bindDefaultSwitch(R.id.defCamera, MainActivity.KEY_DEF_CAMERA)
        bindDefaultSwitch(R.id.defLocation, MainActivity.KEY_DEF_LOCATION)

        updateStatus = findViewById(R.id.updateStatus)
        findViewById<TextView>(R.id.versionText).text =
            "現在のバージョン: Pアプリカ v${GeneratorEngine.APP_VERSION_NAME}"
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener {
            checkUpdate()
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        setupDistributorCard()
        setupGenLimitCard()
    }

    // ---------------- Phase 5: distributor + generation limit --------

    private lateinit var distributorStatus: TextView

    private fun setupDistributorCard() {
        distributorStatus = findViewById(R.id.distributorStatus)
        val toggle = findViewById<Switch>(R.id.distributorToggle)
        var binding = true
        toggle.isChecked = app.webgen.updist.UpDistributor.isEnabled(this)
        binding = false
        toggle.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            // OFF: stops the relay FGS; DistributorReceiver then replies
            // REGISTRATION_FAILED/INTERNAL_ERROR to new REGISTERs so
            // clients fall back cleanly. Generation, history and the
            // updater are untouched by this toggle.
            app.webgen.updist.UpDistributor.setEnabled(this, checked)
            if (checked) {
                app.webgen.updist.UpDistributor.start(
                    this, MainActivity.DIST_NOTIFICATION_LABEL
                )
            }
            refreshDistributorStatus()
        }
        refreshDistributorStatus()
    }

    private fun refreshDistributorStatus() {
        if (!::distributorStatus.isInitialized) return
        val st = app.webgen.updist.UpDistributor.status(this)
        val stateLabel = when {
            !st.enabled -> "停止中"
            st.connectionState == app.webgen.updist.UpDistributor.STATE_CONNECTED -> "接続中"
            st.connectionState == app.webgen.updist.UpDistributor.STATE_CONNECTING ->
                "接続中（接続を試みています）"
            st.connectionState == app.webgen.updist.UpDistributor.STATE_WAITING ->
                "待機中（登録アプリなし）"
            else -> "停止中"
        }
        distributorStatus.text =
            "接続状態: $stateLabel\n" +
                "登録アプリ数: ${st.registeredPackageCount}（登録件数: ${st.registeredAppCount}）"
    }

    // Live status: poll the distributor status while this page is
    // visible so the connection state line stays current.
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            refreshDistributorStatus()
            statusHandler.postDelayed(this, 2_000)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDistributorStatus()
        statusHandler.removeCallbacks(statusPoll)
        statusHandler.postDelayed(statusPoll, 2_000)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusPoll)
        super.onPause()
    }

    private fun setupGenLimitCard() {
        val input = findViewById<EditText>(R.id.genLimitInput)
        val status = findViewById<TextView>(R.id.genLimitStatus)
        input.setText(GeneratorEngine.generationLimit(this).toString())
        status.text = "現在の上限: ${GeneratorEngine.generationLimit(this)} 件"
        findViewById<Button>(R.id.genLimitSaveButton).setOnClickListener {
            val parsed = input.text.toString().trim().toIntOrNull()
            if (parsed == null) {
                Toast.makeText(this, "上限は数字で入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clamped = parsed.coerceIn(1, 100)
            prefs().edit().putInt(GeneratorEngine.KEY_GEN_LIMIT, clamped).apply()
            input.setText(clamped.toString())
            status.text = "現在の上限: $clamped 件" +
                if (parsed != clamped) "（1〜100の範囲に収めました）" else ""
            Toast.makeText(this, "上限を $clamped 件に設定しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun setupThemeSpinner() {
        val spinner = findViewById<Spinner>(R.id.themeSpinner)
        val labels = listOf("端末の設定に合わせる", "ライト", "ダーク")
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        var binding = true
        spinner.setSelection(ThemePrefs.getMode(this))
        binding = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                styleSpinnerText(spinner)
                if (binding) return
                if (position != ThemePrefs.getMode(this@SettingsActivity)) {
                    ThemePrefs.setMode(this@SettingsActivity, position)
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinner.post { styleSpinnerText(spinner) }
    }

    /** The spinner's closed view uses the theme's default text color,
     *  which stays dark; restyle it for the dark card background. */
    private fun styleSpinnerText(spinner: Spinner) {
        val tv = spinner.getChildAt(0) as? TextView ?: return
        tv.setTextColor(
            if (ThemePrefs.isNight(this)) 0xFFE8F4F1.toInt() else 0xFF173A3A.toInt()
        )
    }

    private fun bindDefaultSwitch(id: Int, key: String) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = prefs().getBoolean(key, false)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs().edit()
                .putBoolean(key, checked)
                .putLong(
                    MainActivity.KEY_DEF_REV,
                    prefs().getLong(MainActivity.KEY_DEF_REV, 0) + 1
                )
                .apply()
        }
    }

    private fun checkUpdate() {
        updateStatus.text = "更新を確認しています…"
        UpdateChecker.fetchLatest(UpdateChecker.DEFAULT_REPO) { tag, pageUrl, assetUrl, error ->
            runOnUiThread {
                when {
                    error != null ->
                        updateStatus.text = "更新の確認に失敗しました: $error"
                    tag == null ->
                        updateStatus.text = "リリースが見つかりませんでした"
                    UpdateChecker.compareVersions(
                        tag, GeneratorEngine.APP_VERSION_NAME
                    ) > 0 -> {
                        updateStatus.text =
                            "新しいバージョン $tag があります"
                        UpdateFlow.prompt(this, tag, pageUrl!!, assetUrl)
                    }
                    else ->
                        updateStatus.text =
                            "最新です（Pアプリカ v${GeneratorEngine.APP_VERSION_NAME}）"
                }
            }
        }
    }

}
