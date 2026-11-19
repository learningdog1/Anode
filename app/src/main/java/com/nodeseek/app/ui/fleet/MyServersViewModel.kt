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
    val assetEditorDraft: AssetEditorDraft? = null,
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
    private val _assetEditorDraft = MutableStateFlow<AssetEditorDraft?>(null)
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
        _salePostAsset,
        _bindingProbeAsset,
    ) { draft, saleAsset, bindingAsset ->
        SheetModalState(
            editorDraft = draft,
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
            assetEditorDraft = modals.editorDraft,
            salePostAsset = modals.saleAsset,
            bindingProbeAsset = modals.bindingAsset,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MyServersUiState(selectedTab = initialTab),
    )

    init {
        viewModelScope.launch {
            fleetRepository.loadPersistedState()
            syncBackgroundSchedule()
            if (fleetRepository.panelsFlow.value.any { it.enabled }) {
                fleetRepository.refreshAllPanels()
            }
        }
        viewModelScope.launch {
            exchangeRateRepository.ensureFreshRates(force = false)
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
        _showPanelManagerSheet.value = true
    }

    fun closePanelManager() {
        _showPanelManagerSheet.value = false
        _panelTestState.value = PanelTestUiState.Idle
    }

    fun clearPanelTestState() {
        _panelTestState.value = PanelTestUiState.Idle
    }

    fun testPanelConnection(config: ProbePanelConfig) {
        viewModelScope.launch {
            _panelTestState.value = PanelTestUiState.Testing
            val result = fleetRepository.testPanelConnection(config)
            _panelTestState.value = PanelTestUiState.Completed(result)
        }
    }

    fun savePanel(config: ProbePanelConfig) {
        viewModelScope.launch {
            val saved = fleetRepository.upsertPanel(config)
            syncBackgroundSchedule()
            _snackbarChannel.trySend("已保存探针面板「${saved.name}」")
            fleetRepository.refreshPanel(saved.id).onFailure { err ->
                _snackbarChannel.trySend("同步失败：${err.message ?: "请检查网络或令牌"}")
            }
        }
    }

    fun deletePanel(panelId: String) {
        viewModelScope.launch {
            fleetRepository.deletePanel(panelId)
            syncBackgroundSchedule()
            _snackbarChannel.trySend("已移除探针面板")
        }
    }

    fun togglePanelOfflineAlert(panel: ProbePanelConfig, notifyOnOffline: Boolean) {
        viewModelScope.launch {
            fleetRepository.upsertPanel(panel.copy(notifyOnOffline = notifyOnOffline))
            syncBackgroundSchedule()
        }
    }

    fun refreshAllProbes() {
        viewModelScope.launch {
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
        }
    }

    fun refreshFxRates() {
        viewModelScope.launch {
            exchangeRateRepository.refreshOnline()
            _snackbarChannel.trySend("已刷新实时汇率")
        }
    }

    fun updateMonitorSettings(settings: FleetMonitorSettings) {
        viewModelScope.launch {
            fleetRepository.updateMonitorSettings(settings)
            syncBackgroundSchedule()
            _snackbarChannel.trySend(
                if (settings.backgroundMonitorEnabled) {
                    "后台巡检已开启（每 ${settings.pollIntervalMinutes} 分钟）"
                } else {
                    "后台巡检已关闭"
                },
            )
        }
    }

    // ── Asset Ledger Actions ──────────────────────────────────────────────────

    fun openAddAssetSheet() {
        _assetEditorDraft.value = AssetEditorDraft(
            expiryDateIso = AssetEditorDraft.defaultExpiryDateIso(todayProvider()),
        )
    }

    fun openEditAssetSheet(asset: VpsAssetRecord) {
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

    fun closeAssetEditor() {
        _assetEditorDraft.value = null
    }

    fun saveAsset(asset: VpsAssetRecord) {
        viewModelScope.launch {
            val saved = fleetRepository.upsertAsset(asset)
            _assetEditorDraft.value = null
            syncBackgroundSchedule()
            _snackbarChannel.trySend("已保存资产「${saved.nickname}」")
        }
    }

    fun deleteAsset(assetId: String) {
        viewModelScope.launch {
            fleetRepository.deleteAsset(assetId)
            syncBackgroundSchedule()
            _snackbarChannel.trySend("已删除服务器资产")
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
            fleetRepository.linkAssetToProbe(assetId, probeKey)
            _bindingProbeAsset.value = null
            _snackbarChannel.trySend(if (probeKey != null) "已绑定探针节点" else "已解除探针绑定")
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
        val settings = fleetRepository.monitorSettingsFlow.value
        val hasTargets = fleetRepository.panelsFlow.value.any { it.enabled && it.notifyOnOffline } ||
            fleetRepository.assetsFlow.value.any { it.remindDaysBeforeExpiry > 0 }
        scheduler.sync(settings, hasTargets)
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
