package com.nodeseek.app.data.fleet

import android.content.Context
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.AssetRenewalResult
import com.nodeseek.app.domain.fleet.FleetAlertEvaluator
import com.nodeseek.app.domain.fleet.FleetAlertTrackerState
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetRefreshSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.FleetTrafficAndIdleAnalyzer
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbeDailyCpuBucket
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class FleetStorePayload(
    val version: Int = 1,
    val panels: List<StoredProbePanelConfig> = emptyList(),
    val snapshots: List<StoredProbeServerSnapshot> = emptyList(),
    val assets: List<StoredVpsAssetRecord> = emptyList(),
    val cpuHistoryByProbeKey: Map<String, List<StoredProbeDailyCpuBucket>> = emptyMap(),
    val monitorSettings: StoredFleetMonitorSettings = StoredFleetMonitorSettings(),
    val alertTracker: StoredFleetAlertTracker = StoredFleetAlertTracker(),
    val lastSyncEpochMillis: Long = 0L,
)

@Serializable
private data class StoredProbeDailyCpuBucket(
    val dateIso: String,
    val avgCpuPercent: Double,
    val sampleCount: Int,
    val maxCpuPercent: Double = avgCpuPercent,
    val lastSampleEpochMillis: Long = 0L,
)

@Serializable
private data class StoredProbePanelConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val panelType: String = ProbePanelType.AUTO.name,
    val resolvedPanelType: String? = null,
    val encryptedApiToken: String? = null,
    val notifyOnOffline: Boolean = true,
    val notifyOnRecovery: Boolean = false,
    val enabled: Boolean = true,
    val createdAtEpochMillis: Long = 0L,
    val lastSyncedEpochMillis: Long = 0L,
    val lastError: String? = null,
)

@Serializable
private data class StoredProbeServerSnapshot(
    val panelId: String,
    val remoteServerId: String,
    val name: String,
    val countryCode: String = "",
    val flagEmoji: String = "",
    val os: String = "",
    val cpuModel: String = "",
    val cpuCores: Int = 0,
    val cpuPercent: Double = 0.0,
    val memUsedBytes: Long = 0L,
    val memTotalBytes: Long = 0L,
    val diskUsedBytes: Long = 0L,
    val diskTotalBytes: Long = 0L,
    val netInSpeedBps: Long = 0L,
    val netOutSpeedBps: Long = 0L,
    val monthlyTransferInBytes: Long = 0L,
    val monthlyTransferOutBytes: Long = 0L,
    val trafficLimitBytes: Long? = null,
    val uptimeSeconds: Long = 0L,
    val load1: Double? = null,
    val isOnline: Boolean = false,
    val lastActiveEpochMillis: Long = 0L,
)

@Serializable
private data class StoredVpsAssetRecord(
    val id: String,
    val nickname: String,
    val providerName: String,
    val specsSummary: String,
    val region: String,
    val routeTag: String = "",
    val tags: List<String> = emptyList(),
    val renewalAmount: String,
    val currency: String = ValuationCurrency.USD.name,
    val cyclePreset: String = BillingCyclePreset.ANNUAL.name,
    val customCycleDays: Int? = null,
    val expiryDateIso: String,
    val purchaseUrl: String = "",
    val panelManageUrl: String = "",
    val linkedProbeKey: String? = null,
    val monthlyTrafficQuotaGb: Double? = null,
    val trafficResetDay: Int? = null,
    val remindDaysBeforeExpiry: Int = VpsAssetRecord.DEFAULT_REMIND_DAYS_BEFORE_EXPIRY,
    val autoRenew: Boolean = false,
    val autoRenewNote: String = "",
    val notes: String = "",
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
)

@Serializable
private data class StoredFleetMonitorSettings(
    val backgroundMonitorEnabled: Boolean = true,
    val pollIntervalMinutes: Int = 15,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = false,
    val notifyOnRecovery: Boolean = false,
)

@Serializable
private data class StoredFleetAlertTracker(
    val lastKnownOnlineByProbeKey: Map<String, Boolean> = emptyMap(),
    val activeOutageNotifiedKeys: Set<String> = emptySet(),
    val notifiedRenewalKeys: Set<String> = emptySet(),
    val notifiedTrafficCycleKeys: Set<String> = emptySet(),
)

/**
 * Thread-safe persistent [FleetRepository] backed by `filesDir/fleet_store.json`.
 *
 * All probe panel API tokens are encrypted via [FleetTokenCipher] (Android Keystore AES-GCM
 * on device, with JCA AES-GCM fallback on JVM unit tests) before touching disk.
 */
class FleetRepositoryImpl(
    private val storeFile: File,
    private val probeClient: ProbePanelClient = ProbePanelClient(),
    private val tokenCipher: FleetTokenCipher = FleetTokenCipherFactory.createDefault(
        fallbackKeyFile = File(storeFile.absoluteFile.parentFile, FALLBACK_KEY_FILE_NAME),
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) : FleetRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var diskLoaded = false
    private var cachedPayload = FleetStorePayload()

    private val _isPersistedStateLoadedFlow = MutableStateFlow(false)
    override val isPersistedStateLoadedFlow: StateFlow<Boolean> = _isPersistedStateLoadedFlow.asStateFlow()

    private val _panelsFlow = MutableStateFlow<List<ProbePanelConfig>>(emptyList())
    override val panelsFlow: StateFlow<List<ProbePanelConfig>> = _panelsFlow.asStateFlow()

    private val _snapshotsFlow = MutableStateFlow<List<ProbeServerSnapshot>>(emptyList())
    override val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>> = _snapshotsFlow.asStateFlow()

    private val _assetsFlow = MutableStateFlow<List<VpsAssetRecord>>(emptyList())
    override val assetsFlow: StateFlow<List<VpsAssetRecord>> = _assetsFlow.asStateFlow()

    private val _cpuHistoryByProbeKeyFlow =
        MutableStateFlow<Map<String, List<ProbeDailyCpuBucket>>>(emptyMap())
    override val cpuHistoryByProbeKeyFlow: StateFlow<Map<String, List<ProbeDailyCpuBucket>>> =
        _cpuHistoryByProbeKeyFlow.asStateFlow()

    private val _monitorSettingsFlow = MutableStateFlow(FleetMonitorSettings())
    override val monitorSettingsFlow: StateFlow<FleetMonitorSettings> = _monitorSettingsFlow.asStateFlow()

    private val _lastSyncEpochMillisFlow = MutableStateFlow(0L)
    override val lastSyncEpochMillisFlow: StateFlow<Long> = _lastSyncEpochMillisFlow.asStateFlow()

    private val _isRefreshingFlow = MutableStateFlow(false)
    override val isRefreshingFlow: StateFlow<Boolean> = _isRefreshingFlow.asStateFlow()

    private val _panelErrorsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    override val panelErrorsFlow: StateFlow<Map<String, String>> = _panelErrorsFlow.asStateFlow()

    override suspend fun loadPersistedState() {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
            }
        }
    }

    override suspend fun upsertPanel(config: ProbePanelConfig): ProbePanelConfig =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
                val now = nowMillisProvider()
                val cleanId = config.id.trim().ifBlank { UUID.randomUUID().toString() }
                val existing = cachedPayload.panels.firstOrNull { it.id == cleanId }
                val normalized = config.copy(
                    id = cleanId,
                    name = config.name.trim().ifBlank { ProbePanelClient.normalizeBaseUrl(config.baseUrl) },
                    baseUrl = ProbePanelClient.normalizeBaseUrl(config.baseUrl),
                    apiToken = config.apiToken?.trim()?.takeIf { it.isNotEmpty() },
                    createdAtEpochMillis = existing?.createdAtEpochMillis?.takeIf { it > 0L }
                        ?: config.createdAtEpochMillis.takeIf { it > 0L }
                        ?: now,
                )
                val stored = normalized.toStored(tokenCipher)
                val updatedPanels = cachedPayload.panels
                    .filterNot { it.id == cleanId }
                    .plus(stored)
                    .sortedBy { it.createdAtEpochMillis }

                val nextPayload = cachedPayload.copy(panels = updatedPanels)
                writeStoreToDiskLocked(nextPayload)
                cachedPayload = nextPayload
                publishStateLocked()
                normalized
            }
        }

    override suspend fun deletePanel(panelId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
                val updatedPanels = cachedPayload.panels.filterNot { it.id == panelId }
                val updatedSnapshots = cachedPayload.snapshots.filterNot { it.panelId == panelId }
                val prefix = "$panelId:"
                val updatedAssets = cachedPayload.assets.map { asset ->
                    if (asset.linkedProbeKey?.startsWith(prefix) == true) {
                        asset.copy(linkedProbeKey = null)
                    } else {
                        asset
                    }
                }
                val updatedCpuHistory = cachedPayload.cpuHistoryByProbeKey
                    .filterKeys { !it.startsWith(prefix) }
                val updatedTracker = cachedPayload.alertTracker.copy(
                    lastKnownOnlineByProbeKey = cachedPayload.alertTracker.lastKnownOnlineByProbeKey
                        .filterKeys { !it.startsWith(prefix) },
                    activeOutageNotifiedKeys = cachedPayload.alertTracker.activeOutageNotifiedKeys
                        .filterNot { it.startsWith(prefix) }
                        .toSet(),
                    notifiedTrafficCycleKeys = cachedPayload.alertTracker.notifiedTrafficCycleKeys
                        .filterNot { it.startsWith(prefix) }
                        .toSet(),
                )

                val nextPayload = cachedPayload.copy(
                    panels = updatedPanels,
                    snapshots = updatedSnapshots,
                    assets = updatedAssets,
                    cpuHistoryByProbeKey = updatedCpuHistory,
                    alertTracker = updatedTracker,
                )
                writeStoreToDiskLocked(nextPayload)
                cachedPayload = nextPayload
                _panelErrorsFlow.value = _panelErrorsFlow.value - panelId
                publishStateLocked()
            }
        }
    }

    override suspend fun testPanelConnection(config: ProbePanelConfig): ProbeConnectivityResult =
        probeClient.testConnection(
            config.copy(baseUrl = ProbePanelClient.normalizeBaseUrl(config.baseUrl)),
        )

    override suspend fun refreshPanel(panelId: String): Result<List<ProbeServerSnapshot>> =
        withContext(ioDispatcher) {
            val panel = mutex.withLock {
                ensureDiskLoadedLocked()
                cachedPayload.panels.firstOrNull { it.id == panelId }?.toDomain(tokenCipher)
            } ?: return@withContext Result.failure(IllegalArgumentException("未找到探针面板: $panelId"))

            val now = nowMillisProvider()
            try {
                val fetched = probeClient.fetchPanelServers(panel)
                mutex.withLock {
                    ensureDiskLoadedLocked()
                    val updatedPanels = cachedPayload.panels.map { stored ->
                        if (stored.id == panelId) {
                            stored.copy(
                                resolvedPanelType = fetched.detectedPanelType.name,
                                lastSyncedEpochMillis = now,
                                lastError = null,
                            )
                        } else {
                            stored
                        }
                    }
                    val updatedSnapshots = cachedPayload.snapshots
                        .filterNot { it.panelId == panelId }
                        .plus(fetched.snapshots.map { it.toStored() })
                    val seededOnlineMap = cachedPayload.alertTracker.lastKnownOnlineByProbeKey.toMutableMap()
                    fetched.snapshots.forEach { snap ->
                        if (snap.isOnline) {
                            seededOnlineMap.putIfAbsent(snap.probeKey, true)
                        }
                    }
                    val updatedCpuHistory = FleetTrafficAndIdleAnalyzer.recordCpuSamplesAndPrune(
                        existingByProbeKey = cachedPayload.cpuHistoryByProbeKey.toDomainCpuHistory(),
                        freshSnapshots = fetched.snapshots,
                        nowEpochMillis = now,
                    ).toStoredCpuHistory()
                    val nextPayload = cachedPayload.copy(
                        panels = updatedPanels,
                        snapshots = updatedSnapshots,
                        cpuHistoryByProbeKey = updatedCpuHistory,
                        alertTracker = cachedPayload.alertTracker.copy(
                            lastKnownOnlineByProbeKey = seededOnlineMap,
                        ),
                        lastSyncEpochMillis = now,
                    )
                    writeStoreToDiskLocked(nextPayload)
                    cachedPayload = nextPayload
                    _panelErrorsFlow.value = _panelErrorsFlow.value - panelId
                    publishStateLocked()
                }
                Result.success(fetched.snapshots)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val errorMsg = t.message?.takeIf { it.isNotBlank() } ?: "同步失败"
                mutex.withLock {
                    runCatching {
                        ensureDiskLoadedLocked()
                        val updatedPanels = cachedPayload.panels.map { stored ->
                            if (stored.id == panelId) stored.copy(lastError = errorMsg) else stored
                        }
                        val errorPayload = cachedPayload.copy(panels = updatedPanels)
                        writeStoreToDiskLocked(errorPayload)
                        cachedPayload = errorPayload
                        publishStateLocked()
                    }
                    _panelErrorsFlow.value = _panelErrorsFlow.value + (panelId to errorMsg)
                }
                Result.failure(t)
            }
        }

    override suspend fun refreshAllPanels(): FleetRefreshSummary =
        refreshAllPanels(silent = false)

    override suspend fun refreshAllPanels(silent: Boolean): FleetRefreshSummary = withContext(ioDispatcher) {
        if (silent) {
            if (!refreshMutex.tryLock()) {
                return@withContext FleetRefreshSummary(
                    refreshedPanelCount = 0,
                    succeededPanelCount = 0,
                    failedPanelErrors = _panelErrorsFlow.value,
                    snapshots = _snapshotsFlow.value,
                    syncedAtEpochMillis = _lastSyncEpochMillisFlow.value,
                )
            }
            try {
                return@withContext performRefreshAllPanelsLocked(showRefreshingIndicator = _snapshotsFlow.value.isEmpty())
            } finally {
                refreshMutex.unlock()
            }
        }

        refreshMutex.withLock {
            performRefreshAllPanelsLocked(showRefreshingIndicator = true)
        }
    }

    private suspend fun performRefreshAllPanelsLocked(
        showRefreshingIndicator: Boolean,
    ): FleetRefreshSummary {
        if (showRefreshingIndicator) {
            _isRefreshingFlow.value = true
        }
        try {
            val activePanels = mutex.withLock {
                ensureDiskLoadedLocked()
                cachedPayload.panels.map { it.toDomain(tokenCipher) }.filter { it.enabled }
            }
            val now = nowMillisProvider()
            if (activePanels.isEmpty()) {
                return FleetRefreshSummary(
                    refreshedPanelCount = 0,
                    succeededPanelCount = 0,
                    failedPanelErrors = emptyMap(),
                    snapshots = _snapshotsFlow.value,
                    syncedAtEpochMillis = now,
                )
            }

            val results = coroutineScope {
                activePanels.map { panel ->
                    async {
                        val outcome = try {
                            Result.success(probeClient.fetchPanelServers(panel))
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            Result.failure(t)
                        }
                        panel to outcome
                    }
                }.awaitAll()
            }

            val errors = LinkedHashMap<String, String>()
            val freshByPanelId = LinkedHashMap<String, ProbePanelFetchResult>()
            for ((panel, outcome) in results) {
                outcome.fold(
                    onSuccess = { fetched -> freshByPanelId[panel.id] = fetched },
                    onFailure = { err ->
                        errors[panel.id] = err.message?.takeIf { it.isNotBlank() } ?: "同步失败"
                    },
                )
            }

            val finalSnapshots = mutex.withLock {
                ensureDiskLoadedLocked()
                val updatedPanels = cachedPayload.panels.map { stored ->
                    val fetched = freshByPanelId[stored.id]
                    val err = errors[stored.id]
                    when {
                        fetched != null -> stored.copy(
                            resolvedPanelType = fetched.detectedPanelType.name,
                            lastSyncedEpochMillis = now,
                            lastError = null,
                        )
                        err != null -> stored.copy(lastError = err)
                        else -> stored
                    }
                }
                val retainedSnapshots = cachedPayload.snapshots.filterNot { it.panelId in freshByPanelId.keys }
                val freshDomainSnapshots = freshByPanelId.values.flatMap { it.snapshots }
                val newSnapshots = freshDomainSnapshots.map { it.toStored() }
                val seededOnlineMap = cachedPayload.alertTracker.lastKnownOnlineByProbeKey.toMutableMap()
                freshDomainSnapshots.forEach { snap ->
                    if (snap.isOnline) {
                        seededOnlineMap.putIfAbsent(snap.probeKey, true)
                    }
                }
                val updatedCpuHistory = FleetTrafficAndIdleAnalyzer.recordCpuSamplesAndPrune(
                    existingByProbeKey = cachedPayload.cpuHistoryByProbeKey.toDomainCpuHistory(),
                    freshSnapshots = freshDomainSnapshots,
                    nowEpochMillis = now,
                ).toStoredCpuHistory()
                val nextPayload = cachedPayload.copy(
                    panels = updatedPanels,
                    snapshots = retainedSnapshots + newSnapshots,
                    cpuHistoryByProbeKey = updatedCpuHistory,
                    alertTracker = cachedPayload.alertTracker.copy(
                        lastKnownOnlineByProbeKey = seededOnlineMap,
                    ),
                    lastSyncEpochMillis = if (freshByPanelId.isNotEmpty()) now else cachedPayload.lastSyncEpochMillis,
                )
                writeStoreToDiskLocked(nextPayload)
                cachedPayload = nextPayload
                _panelErrorsFlow.value = errors.toMap()
                publishStateLocked()
                _snapshotsFlow.value
            }

            return FleetRefreshSummary(
                refreshedPanelCount = activePanels.size,
                succeededPanelCount = freshByPanelId.size,
                failedPanelErrors = errors,
                snapshots = finalSnapshots,
                syncedAtEpochMillis = now,
            )
        } finally {
            if (showRefreshingIndicator) {
                _isRefreshingFlow.value = false
            }
        }
    }

    override suspend fun upsertAsset(asset: VpsAssetRecord): VpsAssetRecord =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
                val now = nowMillisProvider()
                val cleanId = asset.id.trim().ifBlank { UUID.randomUUID().toString() }
                val existing = cachedPayload.assets.firstOrNull { it.id == cleanId }
                val preservedExistingTags = existing?.takeIf { stored ->
                    asset.tags.isEmpty() &&
                        stored.tags.isNotEmpty() &&
                        asset.routeTag.trim() == stored.routeTag.trim()
                }?.let { VpsAssetRecord.normalizeTags(it.tags) }
                val normalizedTags = preservedExistingTags ?: asset.resolvedTags
                val normalizedRouteTag = VpsAssetRecord.formatRouteTag(normalizedTags)
                val normalizedExpiryIso = asset.expiryDateIso.trim()
                val normalized = asset.copy(
                    id = cleanId,
                    nickname = asset.nickname.trim().ifBlank { asset.providerName.trim().ifBlank { "未命名 VPS" } },
                    providerName = asset.providerName.trim(),
                    specsSummary = asset.specsSummary.trim(),
                    region = asset.region.trim(),
                    routeTag = normalizedRouteTag,
                    tags = normalizedTags,
                    renewalAmount = asset.renewalAmount.trim(),
                    expiryDateIso = normalizedExpiryIso,
                    purchaseUrl = asset.purchaseUrl.trim(),
                    panelManageUrl = asset.panelManageUrl.trim(),
                    linkedProbeKey = asset.linkedProbeKey?.trim()?.takeIf { it.isNotEmpty() },
                    trafficResetDay = asset.trafficResetDay?.coerceIn(1, 31),
                    remindDaysBeforeExpiry = asset.remindDaysBeforeExpiry.coerceIn(0, 365),
                    autoRenew = asset.autoRenew,
                    autoRenewNote = asset.autoRenewNote.trim(),
                    notes = asset.notes.trim(),
                    createdAtEpochMillis = existing?.createdAtEpochMillis?.takeIf { it > 0L }
                        ?: asset.createdAtEpochMillis.takeIf { it > 0L }
                        ?: now,
                    updatedAtEpochMillis = now,
                )

                val updatedAssets = cachedPayload.assets
                    .filterNot { it.id == cleanId }
                    .plus(normalized.toStored())
                    .sortedBy { it.createdAtEpochMillis }

                val expiryChanged = existing != null && existing.expiryDateIso.trim() != normalizedExpiryIso
                val updatedTracker = if (expiryChanged) {
                    val prefix = "$cleanId:"
                    cachedPayload.alertTracker.copy(
                        notifiedRenewalKeys = cachedPayload.alertTracker.notifiedRenewalKeys
                            .filterNot { it.startsWith(prefix) }
                            .toSet(),
                    )
                } else {
                    cachedPayload.alertTracker
                }

                val nextPayload = cachedPayload.copy(
                    assets = updatedAssets,
                    alertTracker = updatedTracker,
                )
                writeStoreToDiskLocked(nextPayload)
                cachedPayload = nextPayload
                publishStateLocked()
                normalized
            }
        }

    override suspend fun deleteAsset(assetId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
                val updatedAssets = cachedPayload.assets.filterNot { it.id == assetId }
                val prefix = "$assetId:"
                val updatedTracker = cachedPayload.alertTracker.copy(
                    notifiedRenewalKeys = cachedPayload.alertTracker.notifiedRenewalKeys
                        .filterNot { it.startsWith(prefix) }
                        .toSet(),
                )
                val nextPayload = cachedPayload.copy(
                    assets = updatedAssets,
                    alertTracker = updatedTracker,
                )
                writeStoreToDiskLocked(nextPayload)
                cachedPayload = nextPayload
                publishStateLocked()
            }
        }
    }

    override suspend fun linkAssetToProbe(assetId: String, probeKey: String?) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
                val cleanKey = probeKey?.trim()?.takeIf { it.isNotEmpty() }
                val now = nowMillisProvider()
                val updatedAssets = cachedPayload.assets.map { stored ->
                    if (stored.id == assetId) {
                        stored.copy(linkedProbeKey = cleanKey, updatedAtEpochMillis = now)
                    } else {
                        stored
                    }
                }
                val nextPayload = cachedPayload.copy(assets = updatedAssets)
                writeStoreToDiskLocked(nextPayload)
                cachedPayload = nextPayload
                publishStateLocked()
            }
        }
    }

    override suspend fun markAssetRenewed(
        assetId: String,
        today: LocalDate,
    ): AssetRenewalResult? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val cleanId = assetId.trim()
            if (cleanId.isEmpty()) return@withLock null
            val existingStored = cachedPayload.assets.firstOrNull { it.id == cleanId }
                ?: return@withLock null
            val now = nowMillisProvider()
            val renewalResult = FleetPortfolioCalculator.applyAssetRenewal(
                asset = existingStored.toDomain(),
                today = today,
                nowEpochMillis = now,
                isAutoRolled = false,
            )
            val updatedStored = renewalResult.updatedAsset.toStored()
            val updatedAssets = cachedPayload.assets.map { stored ->
                if (stored.id == cleanId) updatedStored else stored
            }
            val prefix = "$cleanId:"
            val updatedTracker = cachedPayload.alertTracker.copy(
                notifiedRenewalKeys = cachedPayload.alertTracker.notifiedRenewalKeys
                    .filterNot { it.startsWith(prefix) }
                    .toSet(),
            )
            val nextPayload = cachedPayload.copy(
                assets = updatedAssets,
                alertTracker = updatedTracker,
            )
            writeStoreToDiskLocked(nextPayload)
            cachedPayload = nextPayload
            publishStateLocked()
            renewalResult
        }
    }

    override suspend fun autoRollExpiredAutoRenewAssets(
        today: LocalDate,
    ): List<AssetRenewalResult> = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val now = nowMillisProvider()
            val rolledResults = mutableListOf<AssetRenewalResult>()
            val rolledAssetIds = HashSet<String>()
            val updatedAssets = cachedPayload.assets.map { stored ->
                val rolled = FleetPortfolioCalculator.autoRollIfExpired(
                    asset = stored.toDomain(),
                    today = today,
                    nowEpochMillis = now,
                )
                if (rolled != null) {
                    rolledResults += rolled
                    rolledAssetIds += stored.id
                    rolled.updatedAsset.toStored()
                } else {
                    stored
                }
            }
            if (rolledResults.isEmpty()) {
                return@withLock emptyList()
            }
            val updatedTracker = cachedPayload.alertTracker.copy(
                notifiedRenewalKeys = cachedPayload.alertTracker.notifiedRenewalKeys
                    .filterNot { key -> rolledAssetIds.any { id -> key.startsWith("$id:") } }
                    .toSet(),
            )
            val nextPayload = cachedPayload.copy(
                assets = updatedAssets,
                alertTracker = updatedTracker,
            )
            writeStoreToDiskLocked(nextPayload)
            cachedPayload = nextPayload
            publishStateLocked()
            rolledResults
        }
    }

    override suspend fun updateMonitorSettings(settings: FleetMonitorSettings) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureDiskLoadedLocked()
                val clamped = settings.copy(
                    pollIntervalMinutes = settings.pollIntervalMinutes.coerceIn(15, 360),
                )
                val nextPayload = cachedPayload.copy(monitorSettings = clamped.toStored())
                writeStoreToDiskLocked(nextPayload)
                cachedPayload = nextPayload
                publishStateLocked()
            }
        }
    }

    override suspend fun evaluateAlerts(
        today: LocalDate,
        nowEpochMillis: Long,
        fxRatesState: FxRatesState?,
        checkedPanels: Int?,
        failedPanels: Int,
    ): FleetMonitorCheckReport = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val panels = cachedPayload.panels.map { it.toDomain(tokenCipher) }
            val snapshots = cachedPayload.snapshots.map { it.toDomain() }
            val assets = cachedPayload.assets.map { it.toDomain() }
            val settings = cachedPayload.monitorSettings.toDomain()
            val prevTracker = cachedPayload.alertTracker.toDomain()

            val evaluation = FleetAlertEvaluator.evaluate(
                previousTracker = prevTracker,
                panels = panels,
                snapshots = snapshots,
                assets = assets,
                monitorSettings = settings,
                today = today,
                nowEpochMillis = nowEpochMillis,
                fxRatesState = fxRatesState,
                checkedPanels = checkedPanels ?: panels.count { it.enabled },
                failedPanels = failedPanels,
            )

            val updatedStoredAssets = if (evaluation.report.autoRolledRenewals.isNotEmpty()) {
                evaluation.updatedAssets.map { it.toStored() }
            } else {
                cachedPayload.assets
            }
            val nextPayload = cachedPayload.copy(
                assets = updatedStoredAssets,
                alertTracker = evaluation.updatedTracker.toStored(),
            )
            writeStoreToDiskLocked(nextPayload)
            cachedPayload = nextPayload
            publishStateLocked()
            evaluation.report
        }
    }

    // ── Disk I/O & Mapping ────────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun ensureDiskLoadedLocked() {
        if (diskLoaded) return
        if (storeFile.exists() && storeFile.isFile) {
            val content = storeFile.readText(Charsets.UTF_8)
            if (content.isNotBlank()) {
                val loaded = runCatching {
                    json.decodeFromString<FleetStorePayload>(content)
                }.getOrNull()
                if (loaded != null) {
                    cachedPayload = loaded
                }
            }
        }
        diskLoaded = true
        publishStateLocked()
        _isPersistedStateLoadedFlow.value = true
    }

    @Throws(IOException::class)
    private fun writeStoreToDiskLocked(payload: FleetStorePayload) {
        val targetFile = storeFile.absoluteFile
        val parentDir = targetFile.parentFile
            ?: throw IOException("无法解析本地账本存储目录: ${targetFile.path}")

        if (parentDir.exists() && !parentDir.isDirectory) {
            throw IOException("本地账本父路径不是有效目录: ${parentDir.path}")
        }
        if (!parentDir.exists() && !parentDir.mkdirs() && !parentDir.isDirectory) {
            throw IOException("无法创建本地账本存储目录: ${parentDir.path}")
        }
        if (targetFile.exists() && (!targetFile.isFile || !targetFile.canWrite())) {
            throw IOException("本地账本文件不可写: ${targetFile.path}")
        }

        val encodedBytes = try {
            json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        } catch (t: Throwable) {
            throw IOException("序列化服务器账本数据失败", t)
        }

        val tmpFile = File(parentDir, "${targetFile.name}.tmp")
        if (tmpFile.exists() && tmpFile.isDirectory) {
            throw IOException("本地账本临时路径异常: ${tmpFile.path}")
        }

        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(encodedBytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            replaceStoreFileAtomically(tmpFile, targetFile, encodedBytes)
        } catch (t: Throwable) {
            runCatching {
                if (tmpFile.exists() && tmpFile.isFile) {
                    tmpFile.delete()
                }
            }
            if (t is IOException) throw t
            throw IOException("写入服务器账本文件失败: ${targetFile.path}", t)
        }
    }

    @Throws(IOException::class)
    private fun replaceStoreFileAtomically(
        tmpFile: File,
        targetFile: File,
        encodedBytes: ByteArray,
    ) {
        val tmpPath = tmpFile.toPath()
        val targetPath = targetFile.toPath()
        try {
            Files.move(
                tmpPath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // Filesystem does not support ATOMIC_MOVE; try standard REPLACE_EXISTING move next.
        }

        try {
            Files.move(
                tmpPath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (moveErr: IOException) {
            if (tmpFile.renameTo(targetFile)) {
                return
            }
            try {
                FileOutputStream(targetFile).use { fos ->
                    fos.write(encodedBytes)
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
                runCatching { tmpFile.delete() }
            } catch (fallbackErr: IOException) {
                fallbackErr.addSuppressed(moveErr)
                throw fallbackErr
            }
        }
    }

    private fun publishStateLocked() {
        val panels = cachedPayload.panels.map { it.toDomain(tokenCipher) }
        val snapshots = cachedPayload.snapshots.map { it.toDomain() }
        val assets = cachedPayload.assets.map { it.toDomain() }
        _panelsFlow.value = panels
        _snapshotsFlow.value = snapshots
        _assetsFlow.value = assets
        _cpuHistoryByProbeKeyFlow.value = cachedPayload.cpuHistoryByProbeKey.toDomainCpuHistory()
        _monitorSettingsFlow.value = cachedPayload.monitorSettings.toDomain()
        _lastSyncEpochMillisFlow.value = cachedPayload.lastSyncEpochMillis
    }

    private fun ProbePanelConfig.toStored(cipher: FleetTokenCipher): StoredProbePanelConfig {
        val encryptedToken = apiToken?.takeIf { it.isNotBlank() }?.let { plain ->
            runCatching { cipher.encrypt(plain) }.getOrNull()
        }
        return StoredProbePanelConfig(
            id = id,
            name = name,
            baseUrl = baseUrl,
            panelType = panelType.name,
            resolvedPanelType = resolvedPanelType?.name,
            encryptedApiToken = encryptedToken,
            notifyOnOffline = notifyOnOffline,
            notifyOnRecovery = notifyOnRecovery,
            enabled = enabled,
            createdAtEpochMillis = createdAtEpochMillis,
            lastSyncedEpochMillis = lastSyncedEpochMillis,
            lastError = lastError,
        )
    }

    private fun StoredProbePanelConfig.toDomain(cipher: FleetTokenCipher): ProbePanelConfig {
        val decryptedToken = encryptedApiToken?.takeIf { it.isNotBlank() }?.let { enc ->
            runCatching { cipher.decrypt(enc) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return ProbePanelConfig(
            id = id,
            name = name,
            baseUrl = baseUrl,
            panelType = runCatching { ProbePanelType.valueOf(panelType) }.getOrDefault(ProbePanelType.AUTO),
            resolvedPanelType = resolvedPanelType?.let {
                runCatching { ProbePanelType.valueOf(it) }.getOrNull()
            },
            apiToken = decryptedToken,
            notifyOnOffline = notifyOnOffline,
            notifyOnRecovery = notifyOnRecovery,
            enabled = enabled,
            createdAtEpochMillis = createdAtEpochMillis,
            lastSyncedEpochMillis = lastSyncedEpochMillis,
            lastError = lastError,
        )
    }

    private fun ProbeServerSnapshot.toStored() = StoredProbeServerSnapshot(
        panelId = panelId,
        remoteServerId = remoteServerId,
        name = name,
        countryCode = countryCode,
        flagEmoji = flagEmoji,
        os = os,
        cpuModel = cpuModel,
        cpuCores = cpuCores,
        cpuPercent = cpuPercent,
        memUsedBytes = memUsedBytes,
        memTotalBytes = memTotalBytes,
        diskUsedBytes = diskUsedBytes,
        diskTotalBytes = diskTotalBytes,
        netInSpeedBps = netInSpeedBps,
        netOutSpeedBps = netOutSpeedBps,
        monthlyTransferInBytes = monthlyTransferInBytes,
        monthlyTransferOutBytes = monthlyTransferOutBytes,
        trafficLimitBytes = trafficLimitBytes,
        uptimeSeconds = uptimeSeconds,
        load1 = load1,
        isOnline = isOnline,
        lastActiveEpochMillis = lastActiveEpochMillis,
    )

    private fun StoredProbeServerSnapshot.toDomain() = ProbeServerSnapshot(
        panelId = panelId,
        remoteServerId = remoteServerId,
        name = name,
        countryCode = countryCode,
        flagEmoji = flagEmoji,
        os = os,
        cpuModel = cpuModel,
        cpuCores = cpuCores,
        cpuPercent = cpuPercent,
        memUsedBytes = memUsedBytes,
        memTotalBytes = memTotalBytes,
        diskUsedBytes = diskUsedBytes,
        diskTotalBytes = diskTotalBytes,
        netInSpeedBps = netInSpeedBps,
        netOutSpeedBps = netOutSpeedBps,
        monthlyTransferInBytes = monthlyTransferInBytes,
        monthlyTransferOutBytes = monthlyTransferOutBytes,
        trafficLimitBytes = trafficLimitBytes,
        uptimeSeconds = uptimeSeconds,
        load1 = load1,
        isOnline = isOnline,
        lastActiveEpochMillis = lastActiveEpochMillis,
    )

    private fun VpsAssetRecord.toStored(): StoredVpsAssetRecord {
        val normalizedTags = resolvedTags
        return StoredVpsAssetRecord(
            id = id,
            nickname = nickname,
            providerName = providerName,
            specsSummary = specsSummary,
            region = region,
            routeTag = VpsAssetRecord.formatRouteTag(normalizedTags),
            tags = normalizedTags,
            renewalAmount = renewalAmount,
            currency = currency.name,
            cyclePreset = cyclePreset.name,
            customCycleDays = customCycleDays,
            expiryDateIso = expiryDateIso,
            purchaseUrl = purchaseUrl,
            panelManageUrl = panelManageUrl,
            linkedProbeKey = linkedProbeKey,
            monthlyTrafficQuotaGb = monthlyTrafficQuotaGb,
            trafficResetDay = trafficResetDay?.coerceIn(1, 31),
            remindDaysBeforeExpiry = remindDaysBeforeExpiry,
            autoRenew = autoRenew,
            autoRenewNote = autoRenewNote,
            notes = notes,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun StoredVpsAssetRecord.toDomain(): VpsAssetRecord {
        val migratedTags = if (tags.isNotEmpty()) {
            VpsAssetRecord.normalizeTags(tags)
        } else {
            VpsAssetRecord.parseLegacyTags(routeTag)
        }
        return VpsAssetRecord(
            id = id,
            nickname = nickname,
            providerName = providerName,
            specsSummary = specsSummary,
            region = region,
            routeTag = VpsAssetRecord.formatRouteTag(migratedTags),
            renewalAmount = renewalAmount,
            currency = runCatching { ValuationCurrency.valueOf(currency) }.getOrDefault(ValuationCurrency.USD),
            cyclePreset = runCatching { BillingCyclePreset.valueOf(cyclePreset) }.getOrDefault(BillingCyclePreset.ANNUAL),
            customCycleDays = customCycleDays,
            expiryDateIso = expiryDateIso,
            purchaseUrl = purchaseUrl,
            panelManageUrl = panelManageUrl,
            linkedProbeKey = linkedProbeKey,
            monthlyTrafficQuotaGb = monthlyTrafficQuotaGb,
            trafficResetDay = trafficResetDay?.takeIf { it in 1..31 },
            remindDaysBeforeExpiry = remindDaysBeforeExpiry,
            autoRenewNote = autoRenewNote,
            notes = notes,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
            tags = migratedTags,
            autoRenew = autoRenew,
        )
    }

    private fun Map<String, List<StoredProbeDailyCpuBucket>>.toDomainCpuHistory():
        Map<String, List<ProbeDailyCpuBucket>> =
        mapValues { (_, list) ->
            list.map { stored ->
                ProbeDailyCpuBucket(
                    dateIso = stored.dateIso,
                    avgCpuPercent = stored.avgCpuPercent,
                    sampleCount = stored.sampleCount,
                    maxCpuPercent = stored.maxCpuPercent,
                    lastSampleEpochMillis = stored.lastSampleEpochMillis,
                )
            }
        }

    private fun Map<String, List<ProbeDailyCpuBucket>>.toStoredCpuHistory():
        Map<String, List<StoredProbeDailyCpuBucket>> =
        mapValues { (_, list) ->
            list.map { bucket ->
                StoredProbeDailyCpuBucket(
                    dateIso = bucket.dateIso,
                    avgCpuPercent = bucket.avgCpuPercent,
                    sampleCount = bucket.sampleCount,
                    maxCpuPercent = bucket.maxCpuPercent,
                    lastSampleEpochMillis = bucket.lastSampleEpochMillis,
                )
            }
        }

    private fun FleetMonitorSettings.toStored() = StoredFleetMonitorSettings(
        backgroundMonitorEnabled = backgroundMonitorEnabled,
        pollIntervalMinutes = pollIntervalMinutes,
        wifiOnlyInBackground = wifiOnlyInBackground,
        requireBatteryNotLowInBackground = requireBatteryNotLowInBackground,
        notifyOnRecovery = notifyOnRecovery,
    )

    private fun StoredFleetMonitorSettings.toDomain() = FleetMonitorSettings(
        backgroundMonitorEnabled = backgroundMonitorEnabled,
        pollIntervalMinutes = pollIntervalMinutes.coerceIn(15, 360),
        wifiOnlyInBackground = wifiOnlyInBackground,
        requireBatteryNotLowInBackground = requireBatteryNotLowInBackground,
        notifyOnRecovery = notifyOnRecovery,
    )

    private fun FleetAlertTrackerState.toStored() = StoredFleetAlertTracker(
        lastKnownOnlineByProbeKey = lastKnownOnlineByProbeKey,
        activeOutageNotifiedKeys = activeOutageNotifiedKeys,
        notifiedRenewalKeys = notifiedRenewalKeys,
        notifiedTrafficCycleKeys = notifiedTrafficCycleKeys,
    )

    private fun StoredFleetAlertTracker.toDomain() = FleetAlertTrackerState(
        lastKnownOnlineByProbeKey = lastKnownOnlineByProbeKey,
        activeOutageNotifiedKeys = activeOutageNotifiedKeys,
        notifiedRenewalKeys = notifiedRenewalKeys,
        notifiedTrafficCycleKeys = notifiedTrafficCycleKeys,
    )

    companion object {
        const val STORE_FILE_NAME = "fleet_store.json"
        private const val FALLBACK_KEY_FILE_NAME = ".fleet_token_sw.key"

        private val sharedInstances = ConcurrentHashMap<String, FleetRepositoryImpl>()

        /**
         * Returns a process-scoped [FleetRepositoryImpl] backed by `filesDir/fleet_store.json`.
         * Shared seamlessly across UI screens and [FleetMonitorWorker].
         */
        fun forContext(context: Context): FleetRepositoryImpl {
            val appCtx = context.applicationContext ?: context
            val file = File(appCtx.filesDir, STORE_FILE_NAME)
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            return sharedInstances.getOrPut(key) {
                FleetRepositoryImpl(storeFile = file)
            }
        }
    }
}
