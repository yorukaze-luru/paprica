package app.webgen.generator

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * APK installation via PackageInstaller sessions (v1.5), replacing
 * bare ACTION_VIEW intents so that アプリカ is the installer of
 * record for the apps it installs.
 *
 * On Android 12+, updates of already-installed packages are requested
 * with USER_ACTION_NOT_REQUIRED; whether the platform actually skips
 * the confirmation depends on its own conditions (installer of
 * record, the UPDATE_PACKAGES_WITHOUT_USER_ACTION declaration, target
 * API levels). When it cannot, the session falls back to
 * STATUS_PENDING_USER_ACTION and InstallResultReceiver shows the
 * normal confirmation — so behavior degrades gracefully.
 *
 * Any failure falls back to the legacy ACTION_VIEW installer flow.
 */
object Installer {

    fun install(activity: Activity, file: File, pkg: String) {
        try {
            sessionInstall(activity, file, pkg)
        } catch (e: Exception) {
            legacyInstall(activity, file)
        }
    }

    private fun sessionInstall(activity: Activity, file: File, pkg: String) {
        val pm = activity.packageManager
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(pkg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alreadyInstalled = try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
            params.setRequireUserAction(
                if (alreadyInstalled) {
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                } else {
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                }
            )
        }
        val installer = pm.packageInstaller
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val resultIntent = Intent(
                activity, InstallResultReceiver::class.java
            ).setAction(InstallResultReceiver.ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(activity, sessionId, resultIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    fun legacyInstall(activity: Activity, file: File) {
        val uri = GenFileProvider.uriFor(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ignored: Exception) {
            }
        }
    }
}
