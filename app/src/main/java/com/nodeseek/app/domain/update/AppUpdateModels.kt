package com.nodeseek.app.domain.update

import kotlinx.serialization.Serializable

/**
 * Downloadable APK asset attached to a GitHub Release.
 */
@Serializable
data class AppReleaseAsset(
    val id: Long,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String = "",
    val sha256: String? = null,
)

/**
 * Domain representation of an Anode GitHub Release entry.
 */
@Serializable
data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelogMarkdown: String,
    val publishedAtIso: String,
    val isPrerelease: Boolean,
    val htmlUrl: String,
    val apkAsset: AppReleaseAsset? = null,
)

/**
 * Current download / verification / installation stage for the pending update APK.
 */
sealed interface UpdateDownloadPhase {
    data object Idle : UpdateDownloadPhase

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateDownloadPhase {
        val progressFraction: Float?
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data class ReadyToInstall(
        val apkPath: String,
        val requiresInstallPermission: Boolean = false,
        val sha256Verified: Boolean = false,
    ) : UpdateDownloadPhase

    data class Failed(
        val message: String,
    ) : UpdateDownloadPhase
}

/**
 * UI-observable state for GitHub Release update checking and in-app installation.
 */
data class AppUpdateState(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val isChecking: Boolean = false,
    val lastCheckedAtMillis: Long = 0L,
    val latestRelease: AppReleaseInfo? = null,
    val hasUpdate: Boolean = false,
    val checkError: String? = null,
    val showUpdatePrompt: Boolean = false,
    val downloadPhase: UpdateDownloadPhase = UpdateDownloadPhase.Idle,
)

/**
 * Explicit outcome returned by [AppUpdateManager.checkForUpdates].
 */
sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: AppReleaseInfo) : UpdateCheckResult
    data class UpToDate(val currentVersionName: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}
