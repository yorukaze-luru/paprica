package app.webgen.generator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * アプリカ self-update flow (v1.5): confirm, download the new APK
 * inside the app, then hand it to the PackageInstaller flow
 * ([Installer]). On Android 12+ the install of an update may complete
 * without a confirmation screen when the platform's conditions are
 * met; otherwise the usual confirmation appears. If the release has
 * no APK asset, falls back to opening the release page.
 */
object UpdateFlow {

    fun prompt(activity: Activity, tag: String, pageUrl: String, assetUrl: String?) {
        if (activity.isFinishing) return
        val builder = AlertDialog.Builder(activity)
            .setTitle("新しいバージョンがあります")
            .setMessage(
                "Pアプリカ $tag が GitHub Releases で公開されています。" +
                    "新しいAPKも同じ署名キーで署名されているため、そのまま上書きインストールできます。"
            )
            .setNegativeButton("あとで", null)
        if (assetUrl != null) {
            builder.setPositiveButton("ダウンロードして更新") { _, _ ->
                downloadAndInstall(activity, assetUrl)
            }
            builder.setNeutralButton("リリースページ") { _, _ -> openPage(activity, pageUrl) }
        } else {
            builder.setPositiveButton("リリースページを開く") { _, _ ->
                openPage(activity, pageUrl)
            }
        }
        builder.show()
    }

    private fun downloadAndInstall(activity: Activity, assetUrl: String) {
        Toast.makeText(activity, "新しいバージョンをダウンロードしています…", Toast.LENGTH_SHORT).show()
        UpdateChecker.downloadApk(activity, assetUrl) { file, error ->
            activity.runOnUiThread {
                when {
                    error != null || file == null ->
                        Toast.makeText(
                            activity, "ダウンロードに失敗しました: ${error ?: ""}",
                            Toast.LENGTH_LONG
                        ).show()
                    else -> Installer.install(activity, file, activity.packageName)
                }
            }
        }
    }

    private fun openPage(activity: Activity, pageUrl: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)))
        } catch (e: Exception) {
            Toast.makeText(activity, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }
}
