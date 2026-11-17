package com.nodeseek.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Abstraction over Android's package installer & unknown-sources permission APIs
 * so update state transitions can be tested cleanly on JVM unit tests.
 */
interface ApkInstallerGateway {
    fun canRequestPackageInstalls(): Boolean
    fun verifyArchivePackageName(apkFile: File): Boolean
    fun getArchiveVersionName(apkFile: File): String?
    fun launchInstall(apkFile: File): Boolean
    fun openUnknownSourcesSettings(): Boolean
}

class AndroidApkUpdateInstaller(
    context: Context,
) : ApkInstallerGateway {

    private val appContext: Context = context.applicationContext ?: context

    override fun canRequestPackageInstalls(): Boolean {
        return runCatching {
            appContext.packageManager.canRequestPackageInstalls()
        }.getOrDefault(false)
    }

    override fun verifyArchivePackageName(apkFile: File): Boolean {
        val info = readArchiveInfo(apkFile) ?: return false
        return info.packageName == appContext.packageName
    }

    override fun getArchiveVersionName(apkFile: File): String? {
        return readArchiveInfo(apkFile)?.versionName?.trim()?.takeIf { it.isNotEmpty() }
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

    private fun readArchiveInfo(apkFile: File): PackageInfo? {
        if (!apkFile.isFile || apkFile.length() <= 0L) return null
        return runCatching {
            val pm = appContext.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        }.getOrNull()
    }
}
