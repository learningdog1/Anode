package com.nodeseek.app.ui.fleet

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetPortfolioSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.FleetSalePostBuilder
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetSalePostRequest
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.RestockAssetPrefill
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.fleet.formatBytesBinary
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class MyServersTab(val label: String) {
    PROBES("探针状态"),
    ASSETS("资产账本"),
}

enum class ProbeOnlineFilter(val label: String) {
    ALL("全部"),
    ONLINE("在线"),
    OFFLINE("离线"),
}

@Immutable
sealed interface PanelTestUiState {
    data object Idle : PanelTestUiState
    data object Testing : PanelTestUiState
    data class Completed(val result: ProbeConnectivityResult) : PanelTestUiState
}

@Immutable
data class AssetEditorDraft(
    val editingId: String? = null,
    val nickname: String = "",
    val providerName: String = "",
    val specsSummary: String = "",
    val region: String = "",
    val routeTag: String = "",
    val renewalAmount: String = "",
    val currency: ValuationCurrency = ValuationCurrency.USD,
    val cyclePreset: BillingCyclePreset = BillingCyclePreset.ANNUAL,
    val customCycleDays: String = "365",
    val expiryDateIso: String = defaultExpiryDateIso(),
    val purchaseUrl: String = "",
    val panelManageUrl: String = "",
    val linkedProbeKey: String? = null,
    val monthlyTrafficQuotaGb: String = "",
    val remindDaysBeforeExpiry: Int = VpsAssetRecord.DEFAULT_REMIND_DAYS_BEFORE_EXPIRY,
    val autoRenewNote: String = "",
    val notes: String = "",
) {
    companion object {
        private val ISO_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun defaultExpiryDateIso(
            today: LocalDate = VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)),
        ): String = ISO_FORMAT.format(today.plusYears(1))

        fun fromRecord(record: VpsAssetRecord): AssetEditorDraft = AssetEditorDraft(
            editingId = record.id,
            nickname = record.nickname,
            providerName = record.providerName,
            specsSummary = record.specsSummary,
            region = record.region,
            routeTag = record.routeTag,
            renewalAmount = record.renewalAmount,
            currency = record.currency,
            cyclePreset = record.cyclePreset,
            customCycleDays = (record.customCycleDays ?: 365).toString(),
            expiryDateIso = record.expiryDateIso,
            purchaseUrl = record.purchaseUrl,
            panelManageUrl = record.panelManageUrl,
            linkedProbeKey = record.linkedProbeKey,
            monthlyTrafficQuotaGb = record.monthlyTrafficQuotaGb?.let { gb ->
                if (gb % 1.0 == 0.0) gb.toInt().toString() else gb.toString()
            }.orEmpty(),
            remindDaysBeforeExpiry = record.remindDaysBeforeExpiry,
            autoRenewNote = record.autoRenewNote,
            notes = record.notes,
        )
    }
}

@Immutable
data class MyServersUiState(
    val selectedTab: MyServersTab = MyServersTab.PROBES,
    val probeFilter: ProbeOnlineFilter = ProbeOnlineFilter.ALL,
    val selectedPanelIdFilter: String? = null,
    val panels: List<ProbePanelConfig> = emptyList(),
    val snapshots: List<ProbeServerSnapshot> = emptyList(),
    val filteredSnapshots: List<ProbeServerSnapshot> = emptyList(),
    val assets: List<VpsAssetRecord> = emptyList(),
    val portfolioSummary: FleetPortfolioSummary = FleetPortfolioSummary(),
    val fxRatesState: FxRatesState? = null,
    val monitorSettings: FleetMonitorSettings = FleetMonitorSettings(),
    val isRefreshing: Boolean = false,
    val lastSyncEpochMillis: Long = 0L,
    val panelErrors: Map<String, String> = emptyMap(),
    val showPanelManagerSheet: Boolean = false,
    val panelTestState: PanelTestUiState = PanelTestUiState.Idle,
    val panelSaveError: String? = null,
    val assetEditorDraft: AssetEditorDraft? = null,
    val assetSaveError: String? = null,
    val salePostAsset: VpsAssetRecord? = null,
    val bindingProbeAsset: VpsAssetRecord? = null,
)

class MyServersViewModel(
    private val fleetRepository: FleetRepository,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val workScheduler: FleetWorkScheduler? = null,
    private val todayProvider: () -> LocalDate = {
        VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID))
    },
    initialTab: MyServersTab = MyServersTab.PROBES,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(initialTab)
    private val _probeFilter = MutableStateFlow(ProbeOnlineFilter.ALL)
    private val _selectedPanelIdFilter = MutableStateFlow<String?>(null)
    private val _showPanelManagerSheet = MutableStateFlow(false)
    private val _panelTestState = MutableStateFlow<PanelTestUiState>(PanelTestUiState.Idle)
    private val _panelSaveError = MutableStateFlow<String?>(null)
    private val _assetEditorDraft = MutableStateFlow<AssetEditorDraft?>(null)
    private val _assetSaveError = MutableStateFlow<String?>(null)
    private val _salePostAsset = MutableStateFlow<VpsAssetRecord?>(null)
    private val _bindingProbeAsset = MutableStateFlow<VpsAssetRecord?>(null)

    private val _snackbarChannel = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarChannel.receiveAsFlow()

    private data class NavigationFilterState(
        val tab: MyServersTab,
        val probeFilter: ProbeOnlineFilter,
        val panelIdFilter: String?,
        val showPanelManager: Boolean,
        val panelTestState: PanelTestUiState,
    )

    private data class SheetModalState(
        val editorDraft: AssetEditorDraft?,
        val assetSaveError: String?,
        val panelSaveError: String?,
        val saleAsset: VpsAssetRecord?,
        val bindingAsset: VpsAssetRecord?,
    )

    private data class RepoCollections(
        val panels: List<ProbePanelConfig>,
        val snapshots: List<ProbeServerSnapshot>,
        val assets: List<VpsAssetRecord>,
        val settings: FleetMonitorSettings,
        val isRefreshing: Boolean,
        val lastSyncEpochMillis: Long,
        val panelErrors: Map<String, String>,
    )

    private val navFilterFlow = combine(
        _selectedTab,
        _probeFilter,
        _selectedPanelIdFilter,
        _showPanelManagerSheet,
        _panelTestState,
    ) { tab, probeFilter, panelIdFilter, showPanelManager, testState ->
        NavigationFilterState(
            tab = tab,
            probeFilter = probeFilter,
            panelIdFilter = panelIdFilter,
            showPanelManager = showPanelManager,
            panelTestState = testState,
        )
    }

    private val modalFlow = combine(
        _assetEditorDraft,
        _assetSaveError,
        _panelSaveError,
        _salePostAsset,
        _bindingProbeAsset,
    ) { draft, assetErr, panelErr, saleAsset, bindingAsset ->
        SheetModalState(
            editorDraft = draft,
            assetSaveError = assetErr,
            panelSaveError = panelErr,
            saleAsset = saleAsset,
            bindingAsset = bindingAsset,
        )
    }

    private val repoFlow = combine(
        combine(
            fleetRepository.panelsFlow,
            fleetRepository.snapshotsFlow,
            fleetRepository.assetsFlow,
            fleetRepository.monitorSettingsFlow,
        ) { panels, snapshots, assets, settings ->
            Quad(panels, snapshots, assets, settings)
        },
        combine(
            fleetRepository.isRefreshingFlow,
            fleetRepository.lastSyncEpochMillisFlow,
            fleetRepository.panelErrorsFlow,
        ) { refreshing, lastSync, errors ->
            Triple(refreshing, lastSync, errors)
        },
    ) { (panels, snapshots, assets, settings), (refreshing, lastSync, errors) ->
        RepoCollections(
            panels = panels,
            snapshots = snapshots,
            assets = assets,
            settings = settings,
            isRefreshing = refreshing,
            lastSyncEpochMillis = lastSync,
            panelErrors = errors,
        )
    }

    val uiState: StateFlow<MyServersUiState> = combine(
        repoFlow,
        exchangeRateRepository.state,
        navFilterFlow,
        modalFlow,
    ) { repo, fxState, nav, modals ->
        val effectivePanelFilter = nav.panelIdFilter?.takeIf { id ->
            repo.panels.any { it.id == id }
        }
        val filtered = repo.snapshots
            .asSequence()
            .filter { effectivePanelFilter == null || it.panelId == effectivePanelFilter }
            .filter { snap ->
                when (nav.probeFilter) {
                    ProbeOnlineFilter.ALL -> true
                    ProbeOnlineFilter.ONLINE -> snap.isOnline
                    ProbeOnlineFilter.OFFLINE -> !snap.isOnline
                }
            }
            .sortedWith(
                compareByDescending<ProbeServerSnapshot> { it.isOnline }
                    .thenBy { it.name.lowercase() },
            )
            .toList()

        val summary = FleetPortfolioCalculator.summarize(
            assets = repo.assets,
            snapshots = repo.snapshots,
            fxRatesState = fxState,
            today = todayProvider(),
        )

        MyServersUiState(
            selectedTab = nav.tab,
            probeFilter = nav.probeFilter,
            selectedPanelIdFilter = effectivePanelFilter,
            panels = repo.panels,
            snapshots = repo.snapshots,
            filteredSnapshots = filtered,
            assets = repo.assets,
            portfolioSummary = summary,
            fxRatesState = fxState,
            monitorSettings = repo.settings,
            isRefreshing = repo.isRefreshing,
            lastSyncEpochMillis = repo.lastSyncEpochMillis,
            panelErrors = repo.panelErrors,
            showPanelManagerSheet = nav.showPanelManager,
            panelTestState = nav.panelTestState,
            panelSaveError = modals.panelSaveError,
            assetEditorDraft = modals.editorDraft,
            assetSaveError = modals.assetSaveError,
            salePostAsset = modals.saleAsset,
            bindingProbeAsset = modals.bindingAsset,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MyServersUiState(selectedTab = initialTab),
    )

    init {
        viewModelScope.launch {
            try {
                fleetRepository.loadPersistedState()
                syncBackgroundSchedule()
                if (fleetRepository.panelsFlow.value.any { it.enabled }) {
                    fleetRepository.refreshAllPanels()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("加载服务器账本失败：${formatPersistenceError(t)}")
            }
        }
        viewModelScope.launch {
            try {
                exchangeRateRepository.ensureFreshRates(force = false)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Offline fallback rates remain active.
            }
        }
    }

    fun selectTab(tab: MyServersTab) {
        _selectedTab.value = tab
    }

    fun selectProbeFilter(filter: ProbeOnlineFilter) {
        _probeFilter.value = filter
    }

    fun selectPanelFilter(panelId: String?) {
        _selectedPanelIdFilter.value = panelId
    }

    fun openPanelManager() {
        _panelTestState.value = PanelTestUiState.Idle
        _panelSaveError.value = null
        _showPanelManagerSheet.value = true
    }

    fun closePanelManager() {
        _showPanelManagerSheet.value = false
        _panelTestState.value = PanelTestUiState.Idle
        _panelSaveError.value = null
    }

    fun clearPanelTestState() {
        _panelTestState.value = PanelTestUiState.Idle
    }

    fun clearPanelSaveError() {
        _panelSaveError.value = null
    }

    fun testPanelConnection(config: ProbePanelConfig) {
        viewModelScope.launch {
            _panelTestState.value = PanelTestUiState.Testing
            val result = try {
                fleetRepository.testPanelConnection(config)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ProbeConnectivityResult(
                    success = false,
                    errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "连接测试失败",
                )
            }
            _panelTestState.value = PanelTestUiState.Completed(result)
        }
    }

    fun savePanel(config: ProbePanelConfig) {
        _panelSaveError.value = null
        viewModelScope.launch {
            try {
                val saved = fleetRepository.upsertPanel(config)
                _panelSaveError.value = null
                syncBackgroundSchedule()
                _snackbarChannel.trySend("已保存探针面板「${saved.name}」")
                fleetRepository.refreshPanel(saved.id).onFailure { err ->
                    _snackbarChannel.trySend("同步失败：${err.message ?: "请检查网络或令牌"}")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val message = "保存探针面板失败：${formatPersistenceError(t)}"
                _panelSaveError.value = message
                _snackbarChannel.trySend(message)
            }
        }
    }

    fun deletePanel(panelId: String) {
        viewModelScope.launch {
            try {
                fleetRepository.deletePanel(panelId)
                syncBackgroundSchedule()
                _snackbarChannel.trySend("已移除探针面板")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("移除探针面板失败：${formatPersistenceError(t)}")
            }
        }
    }

    fun togglePanelOfflineAlert(panel: ProbePanelConfig, notifyOnOffline: Boolean) {
        viewModelScope.launch {
            try {
                fleetRepository.upsertPanel(panel.copy(notifyOnOffline = notifyOnOffline))
                syncBackgroundSchedule()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("更新离线告警设置失败：${formatPersistenceError(t)}")
            }
        }
    }

    fun refreshAllProbes() {
        viewModelScope.launch {
            try {
                val summary = fleetRepository.refreshAllPanels()
                if (summary.refreshedPanelCount == 0) {
                    _snackbarChannel.trySend("尚未添加启用的探针面板")
                } else if (summary.failedPanelErrors.isEmpty()) {
                    _snackbarChannel.trySend("已同步 ${summary.snapshots.size} 台探针节点")
                } else {
                    _snackbarChannel.trySend(
                        "已同步 ${summary.succeededPanelCount}/${summary.refreshedPanelCount} 个面板，部分面板异常",
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("同步探针数据失败：${formatPersistenceError(t)}")
            }
        }
    }

    fun refreshFxRates() {
        viewModelScope.launch {
            try {
                exchangeRateRepository.refreshOnline()
                _snackbarChannel.trySend("已刷新实时汇率")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("刷新汇率失败：${formatPersistenceError(t)}")
            }
        }
    }

    fun updateMonitorSettings(settings: FleetMonitorSettings) {
        viewModelScope.launch {
            try {
                fleetRepository.updateMonitorSettings(settings)
                syncBackgroundSchedule()
                _snackbarChannel.trySend(
                    if (settings.backgroundMonitorEnabled) {
                        "后台巡检已开启（每 ${settings.pollIntervalMinutes} 分钟）"
                    } else {
                        "后台巡检已关闭"
                    },
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("保存后台巡检设置失败：${formatPersistenceError(t)}")
            }
        }
    }

    // ── Asset Ledger Actions ──────────────────────────────────────────────────

    fun openAddAssetSheet() {
        _assetSaveError.value = null
        _assetEditorDraft.value = AssetEditorDraft(
            expiryDateIso = AssetEditorDraft.defaultExpiryDateIso(todayProvider()),
        )
    }

    fun openEditAssetSheet(asset: VpsAssetRecord) {
        _assetSaveError.value = null
        _assetEditorDraft.value = AssetEditorDraft.fromRecord(asset)
    }

    fun openAddAssetFromProbe(snapshot: ProbeServerSnapshot) {
        val specsParts = buildList {
            if (snapshot.cpuCores > 0) add("${snapshot.cpuCores}C")
            if (snapshot.memTotalBytes > 0L) add(formatBytesBinary(snapshot.memTotalBytes))
            if (snapshot.diskTotalBytes > 0L) add(formatBytesBinary(snapshot.diskTotalBytes))
        }
        val quotaGb = snapshot.trafficLimitBytes?.takeIf { it > 0L }?.let { bytes ->
            (bytes / (1024.0 * 1024.0 * 1024.0)).toInt().toString()
        }.orEmpty()

        _assetSaveError.value = null
        _selectedTab.value = MyServersTab.ASSETS
        _assetEditorDraft.value = AssetEditorDraft(
            nickname = snapshot.name,
            region = snapshot.countryCode.ifBlank { snapshot.flagEmoji },
            specsSummary = specsParts.joinToString(" / "),
            expiryDateIso = AssetEditorDraft.defaultExpiryDateIso(todayProvider()),
            linkedProbeKey = snapshot.probeKey,
            monthlyTrafficQuotaGb = quotaGb,
        )
    }

    /**
     * Cross-feature hook for Feature 1 (Restock Monitor) -> Feature 4 (Asset Ledger):
     * Switches to the Asset Ledger tab and opens the Add Server bottom sheet pre-populated
     * with parsed currency, renewal amount, billing cycle, region/specs hints, and purchase URL.
     */
    fun prefillAssetFromRestock(
        name: String,
        priceHint: String,
        purchaseUrl: String,
    ) {
        val parsed = FleetPortfolioCalculator.parseRestockPrefill(
            RestockAssetPrefill(
                name = name,
                priceHint = priceHint,
                purchaseUrl = purchaseUrl,
            ),
        )
        val today = todayProvider()
        val defaultExpiry = when (parsed.cyclePreset) {
            BillingCyclePreset.MONTHLY -> today.plusMonths(1)
            BillingCyclePreset.QUARTERLY -> today.plusMonths(3)
            BillingCyclePreset.SEMI_ANNUAL -> today.plusMonths(6)
            BillingCyclePreset.BIENNIAL -> today.plusYears(2)
            BillingCyclePreset.TRIENNIAL -> today.plusYears(3)
            else -> today.plusYears(1)
        }
        _assetSaveError.value = null
        _selectedTab.value = MyServersTab.ASSETS
        _assetEditorDraft.value = AssetEditorDraft(
            nickname = parsed.nickname,
            providerName = parsed.providerName,
            specsSummary = parsed.specsSummary,
            region = parsed.region,
            renewalAmount = parsed.renewalAmount,
            currency = parsed.currency,
            cyclePreset = parsed.cyclePreset,
            expiryDateIso = DateTimeFormatter.ISO_LOCAL_DATE.format(defaultExpiry),
            purchaseUrl = parsed.purchaseUrl,
            notes = parsed.notes,
        )
    }

    fun clearAssetSaveError() {
        _assetSaveError.value = null
    }

    fun closeAssetEditor() {
        _assetSaveError.value = null
        _assetEditorDraft.value = null
    }

    fun saveAsset(asset: VpsAssetRecord) {
        val previousDraft = _assetEditorDraft.value
        val preservedEditingId = when {
            previousDraft != null -> previousDraft.editingId
            fleetRepository.assetsFlow.value.any { it.id == asset.id } -> asset.id
            else -> null
        }
        val draftFromSubmission = AssetEditorDraft.fromRecord(asset).copy(
            editingId = preservedEditingId,
            customCycleDays = (
                asset.customCycleDays
                    ?: previousDraft?.customCycleDays?.toIntOrNull()
                    ?: 365
                ).toString(),
        )
        _assetEditorDraft.value = draftFromSubmission
        _assetSaveError.value = null

        viewModelScope.launch {
            try {
                val saved = fleetRepository.upsertAsset(asset)
                _assetSaveError.value = null
                _assetEditorDraft.value = null
                syncBackgroundSchedule()
                _snackbarChannel.trySend("已保存资产「${saved.nickname}」")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val errorMessage = "保存资产失败：${formatPersistenceError(t)}"
                _assetSaveError.value = errorMessage
                _assetEditorDraft.value = draftFromSubmission
                _snackbarChannel.trySend(errorMessage)
            }
        }
    }

    fun deleteAsset(assetId: String) {
        viewModelScope.launch {
            try {
                fleetRepository.deleteAsset(assetId)
                syncBackgroundSchedule()
                _snackbarChannel.trySend("已删除服务器资产")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("删除服务器资产失败：${formatPersistenceError(t)}")
            }
        }
    }

    fun openProbeBinder(asset: VpsAssetRecord) {
        _bindingProbeAsset.value = asset
    }

    fun closeProbeBinder() {
        _bindingProbeAsset.value = null
    }

    fun bindAssetToProbe(assetId: String, probeKey: String?) {
        viewModelScope.launch {
            try {
                fleetRepository.linkAssetToProbe(assetId, probeKey)
                _bindingProbeAsset.value = null
                _snackbarChannel.trySend(if (probeKey != null) "已绑定探针节点" else "已解除探针绑定")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("更新探针绑定失败：${formatPersistenceError(t)}")
            }
        }
    }

    // ── One-Click Valuation & Sale Post ───────────────────────────────────────

    fun openSalePostSheet(asset: VpsAssetRecord) {
        _salePostAsset.value = asset
    }

    fun closeSalePostSheet() {
        _salePostAsset.value = null
    }

    fun buildSalePost(request: FleetSalePostRequest): FleetSalePostOutcome =
        FleetSalePostBuilder.build(request)

    private fun syncBackgroundSchedule() {
        val scheduler = workScheduler ?: return
        if (!fleetRepository.isPersistedStateLoadedFlow.value) return
        val settings = fleetRepository.monitorSettingsFlow.value
        val hasTargets = FleetWorkScheduler.hasActiveTargets(
            panels = fleetRepository.panelsFlow.value,
            assets = fleetRepository.assetsFlow.value,
        )
        scheduler.sync(
            settings = settings,
            hasActiveTargets = hasTargets,
            isPersistedStateLoaded = true,
        )
    }

    private fun formatPersistenceError(t: Throwable): String {
        val raw = t.message?.trim().orEmpty()
        return if (raw.isNotEmpty() && raw.any { it.code in 0x4E00..0x9FFF }) {
            raw
        } else {
            "本地账本写入失败，请检查存储空间或目录权限"
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        fun factory(
            fleetRepository: FleetRepository,
            exchangeRateRepository: ExchangeRateRepository,
            workScheduler: FleetWorkScheduler? = null,
            initialTab: MyServersTab = MyServersTab.PROBES,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MyServersViewModel(
                    fleetRepository = fleetRepository,
                    exchangeRateRepository = exchangeRateRepository,
                    workScheduler = workScheduler,
                    initialTab = initialTab,
                ) as T
            }
        }

        fun factory(
            context: Context,
            initialTab: MyServersTab = MyServersTab.PROBES,
        ): ViewModelProvider.Factory = factory(
            fleetRepository = FleetRepositoryImpl.forContext(context),
            exchangeRateRepository = ExchangeRateRepository.forContext(context),
            workScheduler = FleetWorkScheduler(context),
            initialTab = initialTab,
        )
    }
}
