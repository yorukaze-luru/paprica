package app.webgen.generator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Receives PackageInstaller session results (v1.5).
 *
 * STATUS_PENDING_USER_ACTION means the platform wants the user to
 * confirm; the confirmation intent is in EXTRA_INTENT and we launch
 * it. STATUS_SUCCESS can arrive with no UI at all for updates that
 * qualified for the no-user-action path on Android 12+.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    try {
                        context.startActivity(
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(
                            context, "インストール確認を開けませんでした",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "インストールが完了しました", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    context,
                    "インストールできませんでした" + (msg?.let { ": $it" } ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        const val ACTION = "app.webgen.generator.INSTALL_RESULT"
    }
}
