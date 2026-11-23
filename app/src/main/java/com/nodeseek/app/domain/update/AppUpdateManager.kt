package com.nodeseek.app.domain.update

import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates automatic & manual GitHub Release update checks, APK download/verification,
 * unknown-sources permission handoff, and in-app installer launch.
 */
interface AppUpdateManager {
    val state: StateFlow<AppUpdateState>

    fun checkForUpdatesOnLaunch()

    suspend fun checkForUpdates(manual: Boolean = true): UpdateCheckResult

    fun openUpdateDialog()

    fun dismissUpdatePrompt()

    fun ignoreRelease(tagName: String)

    fun startDownloadAndInstall()

    fun cancelDownload()

    fun installReadyApk(): Boolean

    fun openUnknownSourcesSettings(): Boolean

    fun onAppResumed()

    fun onAppBackgrounded() = Unit
}
