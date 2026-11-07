package app.webgen.generator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File

/**
 * Pアプリカ self-update flow (settings-page progress UI since
 * v1.2): the update is announced with a dialog, but the download
 * itself runs on the settings page, where the update section shows
 * the live state and progress until the install step begins
 * ([Installer], PackageInstaller session). On Android 12+ the
 * install of an update may complete without a confirmation screen
 * when the platform's conditions are met; otherwise the usual
 * confirmation appears. If the release has no APK asset, falls back
 * to opening the release page.
 */
object UpdateFlow {

    /**
     * Progress callbacks for an in-progress update download.
     * All methods are invoked on the UI thread.
     */
    interface DownloadListener {
        /** [total] is -1 when the download size is unknown. */
        fun onProgress(downloaded: Long, total: Long)
        fun onDownloaded(file: File)
        fun onError(message: String)
    }

    fun prompt(activity: Activity, tag: String, pageUrl: String, assetUrl: String?) {
        if (activity.isFinishing) return
        val builder = AlertDialog.Builder(activity)
            .setTitle("新しいバージョンがあります")
            .setMessage(
                "Pアプリカ $tag が GitHub Releases で公開されています。" +
                    "新しいAPKも同じ署名キーで署名されているため、そのまま上書きインストールできます。" +
                    "ダウンロードは設定ページで進み具合を見ながら行われます。"
            )
            .setNegativeButton("あとで", null)
        if (assetUrl != null) {
            builder.setPositiveButton("ダウンロードして更新") { _, _ ->
                SettingsActivity.openForUpdate(activity, tag, pageUrl, assetUrl)
            }
            builder.setNeutralButton("リリースページ") { _, _ -> openPage(activity, pageUrl) }
        } else {
            builder.setPositiveButton("リリースページを開く") { _, _ ->
                openPage(activity, pageUrl)
            }
        }
        builder.show()
    }

    /**
     * Downloads the update APK, reporting progress to [listener].
     * The caller (the settings page) displays the progress and, from
     * [DownloadListener.onDownloaded], hands the file to [Installer]
     * so the state text can switch to the install step first.
     */
    fun download(activity: Activity, assetUrl: String, listener: DownloadListener) {
        UpdateChecker.downloadApk(
            activity, assetUrl,
            onProgress = { downloaded, total ->
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        listener.onProgress(downloaded, total)
                    }
                }
            },
            callback = { file, error ->
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    when {
                        error != null -> listener.onError(error)
                        file == null -> listener.onError("ダウンロードに失敗しました")
                        else -> listener.onDownloaded(file)
                    }
                }
            }
        )
    }

    fun openPage(activity: Activity, pageUrl: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)))
        } catch (e: Exception) {
            Toast.makeText(activity, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }
}
