package com.sehirnotlari.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.sehirnotlari.AppUpdates
import java.io.File
import java.security.MessageDigest

/**
 * Hands a verified APK to Android's [PackageInstaller]. The bytes are hashed while they are
 * written into the session, so a file changed after verification is never committed.
 * The session always asks the user to confirm; this app cannot install silently.
 */
internal object SessionInstaller {
    private const val TAG = "SehirNotlari"
    const val ACTION_STATUS = "com.sehirnotlari.update.INSTALL_STATUS"

    fun commit(context: Context, file: File, sha256: String): InstallLaunch {
        val installer = context.packageManager.packageInstaller
        abandonOwnSessions(context)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            Log.w(TAG, "Install session not created error=${e.javaClass.simpleName}")
            return InstallLaunch.FAILED
        }
        return try {
            installer.openSession(sessionId).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                    session.fsync(out)
                }
                val written = digest.digest().joinToString("") { "%02x".format(it) }
                if (written != sha256) {
                    session.abandon()
                    Log.w(TAG, "Install session abandoned: file changed after verification")
                    return InstallLaunch.CHANGED
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
                // The installer adds the status extras, so the PendingIntent must be mutable; it is explicit.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val status = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(status.intentSender)
            }
            InstallLaunch.STARTED
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed error=${e.javaClass.simpleName}")
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            InstallLaunch.FAILED
        }
    }

    /** Sessions left by an earlier attempt or an earlier process; only this app's own. */
    fun abandonOwnSessions(context: Context) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach {
            try {
                installer.abandonSession(it.sessionId)
            } catch (_: Exception) {
            }
        }
    }

    fun outcome(status: Int): InstallOutcome = when (status) {
        PackageInstaller.STATUS_SUCCESS -> InstallOutcome.SUCCESS
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.CANCELLED
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome.REJECTED
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome.STORAGE
        else -> InstallOutcome.FAILED
    }
}

/** Receives the installer's answers for sessions this app committed. Not exported. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionInstaller.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("SehirNotlari", "Install session status=$status")
        val controller = AppUpdates.controller(context)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm == null) controller.onInstallOutcome(InstallOutcome.FAILED) else AppUpdates.offerConfirm(confirm)
            return
        }
        controller.onInstallOutcome(SessionInstaller.outcome(status))
    }
}
