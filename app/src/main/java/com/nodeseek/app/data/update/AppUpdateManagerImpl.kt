package com.nodeseek.app.data.update

import com.nodeseek.app.BuildConfig
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.update.AppReleaseInfo
import com.nodeseek.app.domain.update.AppUpdateManager
import com.nodeseek.app.domain.update.AppUpdateState
import com.nodeseek.app.domain.update.AppVersionComparator
import com.nodeseek.app.domain.update.UpdateCheckResult
import com.nodeseek.app.domain.update.UpdateDownloadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
private data class AppUpdateDiskCachePayload(
    val lastCheckedAtMillis: Long = 0L,
    val etag: String? = null,
    val includePrerelease: Boolean = false,
    val cachedRelease: AppReleaseInfo? = null,
)

/**
 * Production [AppUpdateManager] backed by [GitHubReleaseClient], [SettingsRepository],
 * [ApkInstallerGateway], and `filesDir/app_update_cache_v1.json`.
 */
class AppUpdateManagerImpl(
    private val releaseClient: GitHubReleaseClient,
    private val settingsRepository: SettingsRepository,
    private val installer: ApkInstallerGateway,
    private val cacheFile: File,
    private val apkDirectory: File,
    private val externalScope: CoroutineScope,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) : AppUpdateManager {

    companion object {
        const val CACHE_FILE_NAME = "app_update_cache_v1.json"
        const val APK_CACHE_DIR_NAME = "app_updates"
        const val AUTO_CHECK_COOLDOWN_MILLIS = 15L * 60L * 1000L
        private val FILENAME_VERSION_REGEX = Regex("""(\d+(?:\.\d+)+(?:-[0-9A-Za-z.-]+)?)""")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val checkMutex = Mutex()
    private val diskMutex = Mutex()
    private var diskLoaded = false
    private var cachedPayload = AppUpdateDiskCachePayload()

    private val launchCheckTriggered = AtomicBoolean(false)

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var pendingAutoInstallAfterPermission: Boolean = false

    private val _state = MutableStateFlow(
        AppUpdateState(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
        ),
    )
    override val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    init {
        externalScope.launch(ioDispatcher) {
            cleanupInstalledOrStaleApks()
            hydrateStateFromDiskCache()
        }
        externalScope.launch {
            settingsRepository.settings
                .map { it.includePrereleaseUpdates }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    clearCachedEtag()
                }
        }
    }

    override fun checkForUpdatesOnLaunch() {
        if (!launchCheckTriggered.compareAndSet(false, true)) return
        externalScope.launch {
            cleanupInstalledOrStaleApks()
            if (!settingsRepository.settings.value.autoCheckUpdates) {
                hydrateStateFromDiskCache()
                return@launch
            }
            checkForUpdates(manual = false)
        }
    }

    override suspend fun checkForUpdates(manual: Boolean): UpdateCheckResult = checkMutex.withLock {
        val settings = settingsRepository.settings.value
        val now = nowMillisProvider()

        val diskPayload = ensureDiskLoaded()
        val cacheMatchesChannel = diskPayload.includePrerelease == settings.includePrereleaseUpdates
        val withinCooldown = (now - diskPayload.lastCheckedAtMillis) in 0L until AUTO_CHECK_COOLDOWN_MILLIS

        if (!manual && cacheMatchesChannel && withinCooldown && diskPayload.lastCheckedAtMillis > 0L) {
            val cachedRelease = diskPayload.cachedRelease
            val availableUpdate = cachedRelease?.takeIf(::isReleaseNewer)
            val hasUpdate = availableUpdate != null
            val shouldPrompt = availableUpdate != null &&
                availableUpdate.tagName != settings.ignoredUpdateTag
            _state.update { current ->
                current.copy(
                    isChecking = false,
                    lastCheckedAtMillis = diskPayload.lastCheckedAtMillis,
                    latestRelease = cachedRelease,
                    hasUpdate = hasUpdate,
                    checkError = null,
                    showUpdatePrompt = shouldPrompt || current.showUpdatePrompt,
                )
            }
            return@withLock if (availableUpdate != null) {
                UpdateCheckResult.UpdateAvailable(availableUpdate)
            } else {
                UpdateCheckResult.UpToDate(currentVersionName)
            }
        }

        _state.update { it.copy(isChecking = true, checkError = null) }

        val effectiveEtag = if (cacheMatchesChannel && diskPayload.cachedRelease != null) {
            diskPayload.etag
        } else {
            null
        }

        return@withLock try {
            val fetched = releaseClient.fetchLatestRelease(
                includePrerelease = settings.includePrereleaseUpdates,
                cachedEtag = effectiveEtag,
            )

            val resolvedRelease: AppReleaseInfo?
            val updatedPayload: AppUpdateDiskCachePayload
            when (fetched) {
                FetchReleaseResponse.NotModified -> {
                    resolvedRelease = diskPayload.cachedRelease
                    updatedPayload = diskPayload.copy(
                        lastCheckedAtMillis = now,
                        includePrerelease = settings.includePrereleaseUpdates,
                    )
                }

                is FetchReleaseResponse.Success -> {
                    resolvedRelease = fetched.release
                    updatedPayload = AppUpdateDiskCachePayload(
                        lastCheckedAtMillis = now,
                        etag = fetched.etag,
                        includePrerelease = settings.includePrereleaseUpdates,
                        cachedRelease = fetched.release,
                    )
                }
            }

            persistDiskPayload(updatedPayload)

            val availableUpdate = resolvedRelease?.takeIf(::isReleaseNewer)
            val hasUpdate = availableUpdate != null
            val shouldPrompt = availableUpdate != null &&
                (manual || availableUpdate.tagName != settings.ignoredUpdateTag)

            _state.update { current ->
                val releaseChanged = current.latestRelease?.tagName != resolvedRelease?.tagName
                val nextDownloadPhase = if (releaseChanged && current.downloadPhase !is UpdateDownloadPhase.Downloading) {
                    UpdateDownloadPhase.Idle
                } else {
                    current.downloadPhase
                }
                current.copy(
                    isChecking = false,
                    lastCheckedAtMillis = now,
                    latestRelease = resolvedRelease,
                    hasUpdate = hasUpdate,
                    checkError = null,
                    showUpdatePrompt = if (hasUpdate) {
                        shouldPrompt || current.showUpdatePrompt
                    } else {
                        false
                    },
                    downloadPhase = nextDownloadPhase,
                )
            }

            if (availableUpdate != null) {
                UpdateCheckResult.UpdateAvailable(availableUpdate)
            } else {
                UpdateCheckResult.UpToDate(currentVersionName)
            }
        } catch (c: CancellationException) {
            _state.update { it.copy(isChecking = false) }
            throw c
        } catch (t: Throwable) {
            val message = t.message?.takeIf { it.isNotBlank() }
                ?: "检查更新失败，请稍后重试"
            _state.update { it.copy(isChecking = false, checkError = message) }
            UpdateCheckResult.Error(message)
        }
    }

    override fun openUpdateDialog() {
        val current = _state.value
        if (current.latestRelease != null && current.hasUpdate) {
            _state.update { it.copy(showUpdatePrompt = true) }
        }
    }

    override fun dismissUpdatePrompt() {
        _state.update { it.copy(showUpdatePrompt = false) }
    }

    override fun ignoreRelease(tagName: String) {
        val cleanTag = tagName.trim()
        _state.update { it.copy(showUpdatePrompt = false) }
        if (cleanTag.isNotEmpty()) {
            externalScope.launch {
                settingsRepository.setIgnoredUpdateTag(cleanTag)
            }
        }
    }

    override fun startDownloadAndInstall() {
        val release = _state.value.latestRelease ?: return
        val asset = release.apkAsset ?: run {
            _state.update {
                it.copy(
                    downloadPhase = UpdateDownloadPhase.Failed("当前 Release 未附带可安装的 APK 文件，请前往 GitHub 页面下载"),
                )
            }
            return
        }
        if (downloadJob?.isActive == true) return

        pendingAutoInstallAfterPermission = false
        _state.update {
            it.copy(
                downloadPhase = UpdateDownloadPhase.Downloading(
                    downloadedBytes = 0L,
                    totalBytes = asset.sizeBytes.coerceAtLeast(0L),
                ),
            )
        }

        downloadJob = externalScope.launch {
            try {
                val apkFile = releaseClient.downloadApkAsset(
                    asset = asset,
                    targetDir = apkDirectory,
                ) { downloaded, total ->
                    _state.update { current ->
                        if (current.downloadPhase is UpdateDownloadPhase.Downloading) {
                            current.copy(
                                downloadPhase = UpdateDownloadPhase.Downloading(
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                ),
                            )
                        } else {
                            current
                        }
                    }
                }

                val isValidPackage = withContext(ioDispatcher) {
                    installer.verifyArchivePackageName(apkFile)
                }
                if (!isValidPackage) {
                    apkFile.delete()
                    _state.update {
                        it.copy(
                            downloadPhase = UpdateDownloadPhase.Failed("安装包应用包名校验失败，已自动移除以保障安全"),
                        )
                    }
                    return@launch
                }

                if (installer.canRequestPackageInstalls()) {
                    _state.update {
                        it.copy(
                            downloadPhase = UpdateDownloadPhase.ReadyToInstall(
                                apkPath = apkFile.absolutePath,
                                requiresInstallPermission = false,
                            ),
                        )
                    }
                    val launched = installer.launchInstall(apkFile)
                    if (!launched) {
                        _state.update {
                            it.copy(
                                downloadPhase = UpdateDownloadPhase.Failed("无法拉起系统安装界面，请稍后重试或通过浏览器下载"),
                            )
                        }
                    }
                } else {
                    _state.update {
                        it.copy(
                            showUpdatePrompt = true,
                            downloadPhase = UpdateDownloadPhase.ReadyToInstall(
                                apkPath = apkFile.absolutePath,
                                requiresInstallPermission = true,
                            ),
                        )
                    }
                }
            } catch (c: CancellationException) {
                _state.update { current ->
                    if (current.downloadPhase is UpdateDownloadPhase.Downloading) {
                        current.copy(downloadPhase = UpdateDownloadPhase.Idle)
                    } else {
                        current
                    }
                }
                throw c
            } catch (t: Throwable) {
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "下载更新包失败，请检查网络连接"
                _state.update {
                    it.copy(
                        showUpdatePrompt = true,
                        downloadPhase = UpdateDownloadPhase.Failed(msg),
                    )
                }
            } finally {
                downloadJob = null
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        pendingAutoInstallAfterPermission = false
        _state.update { it.copy(downloadPhase = UpdateDownloadPhase.Idle) }
    }

    override fun installReadyApk(): Boolean {
        val ready = _state.value.downloadPhase as? UpdateDownloadPhase.ReadyToInstall ?: return false
        val apkFile = File(ready.apkPath)
        if (!apkFile.isFile || !installer.verifyArchivePackageName(apkFile)) {
            apkFile.delete()
            _state.update {
                it.copy(
                    downloadPhase = UpdateDownloadPhase.Failed("安装包文件不存在或已失效，请重新下载"),
                )
            }
            return false
        }

        if (!installer.canRequestPackageInstalls()) {
            _state.update {
                it.copy(
                    downloadPhase = ready.copy(requiresInstallPermission = true),
                )
            }
            return false
        }

        pendingAutoInstallAfterPermission = false
        _state.update {
            it.copy(
                downloadPhase = ready.copy(requiresInstallPermission = false),
            )
        }
        val launched = installer.launchInstall(apkFile)
        if (!launched) {
            _state.update {
                it.copy(
                    downloadPhase = UpdateDownloadPhase.Failed("无法拉起系统安装界面，请稍后重试或通过浏览器下载"),
                )
            }
        }
        return launched
    }

    override fun openUnknownSourcesSettings(): Boolean {
        val opened = installer.openUnknownSourcesSettings()
        if (opened) {
            pendingAutoInstallAfterPermission = true
        }
        return opened
    }

    override fun onAppResumed() {
        val ready = _state.value.downloadPhase as? UpdateDownloadPhase.ReadyToInstall ?: return
        val hasPermission = installer.canRequestPackageInstalls()
        if (!hasPermission) {
            if (!ready.requiresInstallPermission) {
                _state.update { it.copy(downloadPhase = ready.copy(requiresInstallPermission = true)) }
            }
            return
        }

        _state.update { it.copy(downloadPhase = ready.copy(requiresInstallPermission = false)) }
        if (pendingAutoInstallAfterPermission) {
            pendingAutoInstallAfterPermission = false
            val apkFile = File(ready.apkPath)
            if (apkFile.isFile && installer.verifyArchivePackageName(apkFile)) {
                installer.launchInstall(apkFile)
            }
        }
    }

    private fun isReleaseNewer(release: AppReleaseInfo): Boolean {
        return AppVersionComparator.isNewer(release.versionName, currentVersionName) ||
            AppVersionComparator.isNewer(release.tagName, currentVersionName)
    }

    private suspend fun hydrateStateFromDiskCache() {
        val payload = ensureDiskLoaded()
        val cachedRelease = payload.cachedRelease
        val hasUpdate = cachedRelease != null && isReleaseNewer(cachedRelease)
        _state.update { current ->
            if (current.latestRelease != null || current.lastCheckedAtMillis > 0L) {
                current
            } else {
                current.copy(
                    lastCheckedAtMillis = payload.lastCheckedAtMillis,
                    latestRelease = cachedRelease,
                    hasUpdate = hasUpdate,
                )
            }
        }
    }

    private suspend fun clearCachedEtag() {
        diskMutex.withLock {
            ensureDiskLoadedLocked()
            if (cachedPayload.etag != null) {
                cachedPayload = cachedPayload.copy(etag = null)
                writeDiskPayloadLocked(cachedPayload)
            }
        }
    }

    private suspend fun ensureDiskLoaded(): AppUpdateDiskCachePayload =
        diskMutex.withLock {
            ensureDiskLoadedLocked()
            cachedPayload
        }

    private suspend fun persistDiskPayload(payload: AppUpdateDiskCachePayload) {
        diskMutex.withLock {
            diskLoaded = true
            cachedPayload = payload
            writeDiskPayloadLocked(payload)
        }
    }

    private suspend fun ensureDiskLoadedLocked() {
        if (diskLoaded) return
        diskLoaded = true
        withContext(ioDispatcher) {
            if (!cacheFile.isFile) return@withContext
            val decoded = runCatching {
                val raw = cacheFile.readText(Charsets.UTF_8)
                if (raw.isBlank()) null else json.decodeFromString<AppUpdateDiskCachePayload>(raw)
            }.getOrNull()
            if (decoded != null) {
                cachedPayload = decoded
            }
        }
    }

    private suspend fun writeDiskPayloadLocked(payload: AppUpdateDiskCachePayload) {
        withContext(ioDispatcher) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val encoded = json.encodeToString(payload)
                val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                tmpFile.writeText(encoded, Charsets.UTF_8)
                if (!tmpFile.renameTo(cacheFile)) {
                    cacheFile.writeText(encoded, Charsets.UTF_8)
                    tmpFile.delete()
                }
            }
        }
    }

    private suspend fun cleanupInstalledOrStaleApks() {
        withContext(ioDispatcher) {
            if (_state.value.downloadPhase is UpdateDownloadPhase.Downloading) return@withContext
            if (!apkDirectory.isDirectory) return@withContext
            apkDirectory.listFiles()?.forEach { child ->
                if (_state.value.downloadPhase is UpdateDownloadPhase.Downloading) return@withContext
                if (!child.isFile) return@forEach
                val name = child.name
                if (name.endsWith(".part", ignoreCase = true) || name.endsWith(".tmp", ignoreCase = true)) {
                    child.delete()
                    return@forEach
                }
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val archiveVersion = installer.getArchiveVersionName(child)
                        ?: extractVersionFromFileName(name)
                    if (archiveVersion != null &&
                        !AppVersionComparator.isNewer(archiveVersion, currentVersionName)
                    ) {
                        child.delete()
                    }
                }
            }
        }
    }

    private fun extractVersionFromFileName(fileName: String): String? {
        val withoutSuffix = fileName
            .removeSuffix(".apk")
            .removeSuffix("-release")
            .removeSuffix("-debug")
        return FILENAME_VERSION_REGEX.find(withoutSuffix)?.groupValues?.getOrNull(1)
    }
}
