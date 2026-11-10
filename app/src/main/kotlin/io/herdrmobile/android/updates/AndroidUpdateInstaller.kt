package io.herdrmobile.android.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.edit
import io.herdrmobile.android.HerdrApplication
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {
    private val preferences = context.getSharedPreferences("update-installer", Context.MODE_PRIVATE)
    private val mutableResults = MutableStateFlow(InstallResult.entries.find { it.name == preferences.getString("result", null) })
    override val results = mutableResults.filterNotNull()
    // Settings can cause the app process to be reclaimed. Retain only our own cache file.
    override val pendingPermissionApk: File?
        get() = File(context.cacheDir, "updates/update.apk").takeIf {
            preferences.getBoolean("waiting_permission", false) && it.isFile
        }
    override fun awaitPermission(apk: File) {
        check(apk.canonicalFile == File(context.cacheDir, "updates/update.apk").canonicalFile)
        preferences.edit { putBoolean("waiting_permission", true) }
    }
    override fun clearPendingPermission() { preferences.edit { remove("waiting_permission") } }
    override fun consumeResult() {
        preferences.edit { remove("result") }
        mutableResults.value = null
    }
    override fun canInstall() = context.packageManager.canRequestPackageInstalls()
    internal fun report(result: InstallResult) {
        preferences.edit { putString("result", result.name) }
        mutableResults.value = result
    }

    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (archive?.packageName != context.packageName) throw IOException("Unexpected APK package")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).setAction("${context.packageName}.UPDATE_INSTALL_RESULT")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, id, callback, flags)
                session.commit(pending.intentSender)
            }
            clearPendingPermission()
            apk.delete()
            Unit
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val installer = (context.applicationContext as HerdrApplication).updateInstaller
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                try {
                    if (confirmation == null) installer.report(InstallResult.FAILED)
                    else context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { installer.report(InstallResult.FAILED) }
            }
            PackageInstaller.STATUS_SUCCESS -> installer.report(InstallResult.SUCCESS)
            PackageInstaller.STATUS_FAILURE_ABORTED -> installer.report(InstallResult.CANCELLED)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> installer.report(InstallResult.CONFLICT)
            PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> installer.report(InstallResult.INVALID)
            PackageInstaller.STATUS_FAILURE_STORAGE -> installer.report(InstallResult.STORAGE)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> installer.report(InstallResult.BLOCKED)
            else -> installer.report(InstallResult.FAILED)
        }
    }
}
