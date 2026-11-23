package com.nodeseek.app.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.nodeseek.app.R
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Abstraction over Android's package installer & unknown-sources permission APIs
 * so update state transitions can be tested cleanly on JVM unit tests.
 */
interface ApkInstallerGateway {
    fun canRequestPackageInstalls(): Boolean
    fun verifyArchivePackageName(apkFile: File): Boolean
    fun verifyArchiveSignature(apkFile: File): Boolean = true
    fun getArchiveVersionName(apkFile: File): String?
    fun getArchiveVersionCode(apkFile: File): Long? = null
    fun launchInstall(apkFile: File): Boolean
    fun openUnknownSourcesSettings(): Boolean
    fun notifyUpdateReady(versionName: String) = Unit
}

class AndroidApkUpdateInstaller(
    context: Context,
) : ApkInstallerGateway {

    companion object {
        private const val UPDATE_NOTIFICATION_CHANNEL_ID = "anode_app_update"
        private const val UPDATE_READY_NOTIFICATION_ID = 42_001
    }

    private val appContext: Context = context.applicationContext ?: context

    override fun canRequestPackageInstalls(): Boolean {
        return runCatching {
            appContext.packageManager.canRequestPackageInstalls()
        }.getOrDefault(false)
    }

    override fun verifyArchivePackageName(apkFile: File): Boolean {
        val info = readArchiveInfo(apkFile, includeSignatures = false) ?: return false
        return info.packageName == appContext.packageName
    }

    override fun verifyArchiveSignature(apkFile: File): Boolean {
        val archiveInfo = readArchiveInfo(apkFile, includeSignatures = true) ?: return false
        val installedInfo = readInstalledPackageInfo(includeSignatures = true) ?: return false
        val archiveDigests = extractSigningCertificateDigests(archiveInfo)
        val installedDigests = extractSigningCertificateDigests(installedInfo)
        if (archiveDigests.isEmpty() || installedDigests.isEmpty()) return false
        return archiveDigests == installedDigests
    }

    override fun getArchiveVersionName(apkFile: File): String? {
        return readArchiveInfo(apkFile, includeSignatures = false)
            ?.versionName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun getArchiveVersionCode(apkFile: File): Long? {
        val info = readArchiveInfo(apkFile, includeSignatures = false) ?: return null
        return runCatching { PackageInfoCompat.getLongVersionCode(info) }.getOrNull()
    }

    override fun launchInstall(apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() <= 0L) return false
        return runCatching {
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override fun openUnknownSourcesSettings(): Boolean {
        val packageSpecificIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedSpecific = runCatching {
            appContext.startActivity(packageSpecificIntent)
            true
        }.getOrDefault(false)
        if (launchedSpecific) return true

        val fallbackIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appContext.startActivity(fallbackIntent)
            true
        }.getOrDefault(false)
    }

    override fun notifyUpdateReady(versionName: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val manager = NotificationManagerCompat.from(appContext)
            if (!manager.areNotificationsEnabled()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val sysManager = appContext.getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    UPDATE_NOTIFICATION_CHANNEL_ID,
                    "应用更新提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "后台下载新版本安装包完成后提醒返回应用安装"
                }
                sysManager?.createNotificationChannel(channel)
            }

            val launchIntent = appContext.packageManager
                .getLaunchIntentForPackage(appContext.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    appContext,
                    UPDATE_READY_NOTIFICATION_ID,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val label = versionName.trim().ifBlank { "新版本" }
            val notification = NotificationCompat.Builder(appContext, UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Anode $label 更新包已下载完成")
                .setContentText("点击返回应用以启动系统安装界面")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .apply {
                    if (pendingIntent != null) setContentIntent(pendingIntent)
                }
                .build()
            manager.notify(UPDATE_READY_NOTIFICATION_ID, notification)
        }
    }

    private fun readArchiveInfo(apkFile: File, includeSignatures: Boolean): PackageInfo? {
        if (!apkFile.isFile || apkFile.length() <= 0L) return null
        return runCatching {
            val pm = appContext.packageManager
            val flags = signatureQueryFlags(includeSignatures)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }
        }.getOrNull()
    }

    private fun readInstalledPackageInfo(includeSignatures: Boolean): PackageInfo? {
        return runCatching {
            val pm = appContext.packageManager
            val flags = signatureQueryFlags(includeSignatures)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(appContext.packageName, flags)
            }
        }.getOrNull()
    }

    private fun signatureQueryFlags(includeSignatures: Boolean): Int {
        if (!includeSignatures) return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun extractSigningCertificateDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            val contents = signingInfo?.apkContentsSigners
            if (!contents.isNullOrEmpty()) {
                contents
            } else {
                signingInfo?.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        if (signatures.isNullOrEmpty()) return emptySet()
        return signatures.mapNotNull { sig ->
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                digest.joinToString(separator = "") { b -> "%02x".format(Locale.ROOT, b) }
            }.getOrNull()
        }.toSet()
    }
}
