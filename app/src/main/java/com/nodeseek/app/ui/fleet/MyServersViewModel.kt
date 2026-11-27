package com.nodeseek.app.ui.fleet

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.fleet.JvmTlsCertificateInspector
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetIdleThresholdConfig
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetPortfolioSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.FleetSalePostBuilder
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetSalePostRequest
import com.nodeseek.app.domain.fleet.FleetTrafficAndIdleAnalyzer
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbeDailyCpuBucket
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.RestockAssetPrefill
import com.nodeseek.app.domain.fleet.ServerIdleAssessment
import com.nodeseek.app.domain.fleet.ServerTrafficForecast
import com.nodeseek.app.domain.fleet.TlsCertificateInspector
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.fleet.formatBytesBinary
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class MyServersTab(val label: String) {
    PROBES("探针状态"),
    ASSETS("资产账本"),
}

enum class ProbeOnlineFilter(val label: String) {
    ALL("全部"),
    ONLINE("在线"),
    OFFLINE("离线"),
}

enum class AssetCategoryFilter(val label: String) {
    ALL("全部"),
    VPS("VPS 小鸡"),
    DOMAIN_CERT("域名·证书"),
}

enum class PanelManagerSection {
    PANELS,
    MONITOR_SETTINGS,
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
    val tags: List<String> = emptyList(),
    val renewalAmount: String = "",
    val currency: ValuationCurrency = ValuationCurrency.USD,
    val cyclePreset: BillingCyclePreset = BillingCyclePreset.ANNUAL,
    val customCycleDays: String = "365",
    val expiryDateIso: String = defaultExpiryDateIso(),
    val purchaseUrl: String = "",
    val panelManageUrl: String = "",
    val linkedProbeKey: String? = null,
    val monthlyTrafficQuotaGb: String = "",
    val trafficResetDay: Int? = null,
    val remindDaysBeforeExpiry: Int = VpsAssetRecord.DEFAULT_REMIND_DAYS_BEFORE_EXPIRY,
    val autoRenew: Boolean = false,
    val autoRenewNote: String = "",
    val notes: String = "",
    val openSalePostAfterSave: Boolean = false,
    val assetType: FleetAssetType = FleetAssetType.VPS,
    val domainName: String = "",
    val registrarName: String = "",
    val domainCreatedIso: String? = null,
    val rdapSyncedAtEpochMillis: Long? = null,
    val rdapStatusTags: List<String> = emptyList(),
    val tlsHost: String = "",
    val tlsPort: String = VpsAssetRecord.DEFAULT_TLS_PORT.toString(),
    val tlsExpiresAtEpochMillis: Long? = null,
    val tlsIssuer: String = "",
    val tlsSubject: String = "",
    val tlsCheckedAtEpochMillis: Long? = null,
    val tlsCheckError: String? = null,
    val rdapSyncRevision: Int = 0,
    val tlsCheckRevision: Int = 0,
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
            routeTag = record.resolvedRouteTagSummary,
            tags = record.resolvedTags,
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
            trafficResetDay = record.trafficResetDay,
            remindDaysBeforeExpiry = record.remindDaysBeforeExpiry,
            autoRenew = record.autoRenew,
            autoRenewNote = record.autoRenewNote,
            notes = record.notes,
            assetType = record.assetType,
            domainName = record.domainName.orEmpty(),
            registrarName = record.registrarName,
            domainCreatedIso = record.domainCreatedIso,
            rdapSyncedAtEpochMillis = record.rdapSyncedAtEpochMillis,
            rdapStatusTags = record.rdapStatusTags,
            tlsHost = record.tlsHost.orEmpty(),
            tlsPort = record.tlsPort.toString(),
            tlsExpiresAtEpochMillis = record.tlsExpiresAtEpochMillis,
            tlsIssuer = record.tlsIssuer,
            tlsSubject = record.tlsSubject,
            tlsCheckedAtEpochMillis = record.tlsCheckedAtEpochMillis,
            tlsCheckError = record.tlsCheckError,
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
    val cpuHistoryByProbeKey: Map<String, List<ProbeDailyCpuBucket>> = emptyMap(),
    val trafficForecastsByProbeKey: Map<String, ServerTrafficForecast> = emptyMap(),
    val highTrafficForecasts: List<ServerTrafficForecast> = emptyList(),
    val idleAssessmentsByProbeKey: Map<String, ServerIdleAssessment> = emptyMap(),
    val idleServers: List<ServerIdleAssessment> = emptyList(),
    val portfolioSummary: FleetPortfolioSummary = FleetPortfolioSummary(),
    val fxRatesState: FxRatesState? = null,
    val monitorSettings: FleetMonitorSettings = FleetMonitorSettings(),
    val isRefreshing: Boolean = false,
    val lastSyncEpochMillis: Long = 0L,
    val panelErrors: Map<String, String> = emptyMap(),
    val showPanelManagerSheet: Boolean = false,
    val panelManagerInitialSection: PanelManagerSection = PanelManagerSection.PANELS,
    val panelTestState: PanelTestUiState = PanelTestUiState.Idle,
    val panelSaveError: String? = null,
    val panelSaveSuccessVersion: Long = 0L,
    val isUsingSoftwareTokenFallback: Boolean = false,
    val assetEditorDraft: AssetEditorDraft? = null,
    val assetSaveError: String? = null,
    val salePostAsset: VpsAssetRecord? = null,
    val bindingProbeAsset: VpsAssetRecord? = null,
    val assetCategoryFilter: AssetCategoryFilter = AssetCategoryFilter.ALL,
    val filteredAssets: List<VpsAssetRecord> = emptyList(),
    val isLookingUpRdap: Boolean = false,
    val rdapLookupError: String? = null,
    val isCheckingTls: Boolean = false,
    val tlsCheckResultMessage: String? = null,
    val refreshingDomainAssetIds: Set<String> = emptySet(),
)

class MyServersViewModel(
    private val fleetRepository: FleetRepository,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val workScheduler: FleetWorkScheduler? = null,
    private val todayProvider: () -> LocalDate = {
        VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID))
    },
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val idleThresholdConfig: FleetIdleThresholdConfig = FleetIdleThresholdConfig(),
    initialTab: MyServersTab = MyServersTab.PROBES,
    private val computationDispatcher: CoroutineDispatcher? = null,
    private val domainRdapClient: DomainRdapClient? = null,
    private val tlsCertificateInspector: TlsCertificateInspector? = null,
) : ViewModel() {

    private val effectiveRdapClient: DomainRdapClient
        get() = domainRdapClient ?: DomainRdapClient.sharedDefault

    private val effectiveTlsInspector: TlsCertificateInspector by lazy {
        tlsCertificateInspector ?: JvmTlsCertificateInspector(nowMillisProvider = nowMillisProvider)
    }

    private val _selectedTab = MutableStateFlow(initialTab)
    private val _probeFilter = MutableStateFlow(ProbeOnlineFilter.ALL)
    private val _assetCategoryFilter = MutableStateFlow(AssetCategoryFilter.ALL)
    private val _selectedPanelIdFilter = MutableStateFlow<String?>(null)
    private val _showPanelManagerSheet = MutableStateFlow(false)
    private val _panelManagerInitialSection = MutableStateFlow(PanelManagerSection.PANELS)
    private val _panelTestState = MutableStateFlow<PanelTestUiState>(PanelTestUiState.Idle)
    private val _panelSaveError = MutableStateFlow<String?>(null)
    private val _panelSaveSuccessVersion = MutableStateFlow(0L)
    private val _assetEditorDraft = MutableStateFlow<AssetEditorDraft?>(null)
    private val _assetSaveError = MutableStateFlow<String?>(null)
    private val _salePostAsset = MutableStateFlow<VpsAssetRecord?>(null)
    private val _bindingProbeAsset = MutableStateFlow<VpsAssetRecord?>(null)
    private val _isLookingUpRdap = MutableStateFlow(false)
    private val _rdapLookupError = MutableStateFlow<String?>(null)
    private val _isCheckingTls = MutableStateFlow(false)
    private val _tlsCheckResultMessage = MutableStateFlow<String?>(null)
    private val _refreshingDomainAssetIds = MutableStateFlow<Set<String>>(emptySet())

    private val probeRefreshMutex = Mutex()
    private val manualRefreshRequested = AtomicBoolean(false)
    private val rdapLookupSeq = AtomicLong(0L)
    private var rdapLookupJob: Job? = null
    private val tlsCheckSeq = AtomicLong(0L)
    private var tlsCheckJob: Job? = null
    private val assetRefreshSeqs = ConcurrentHashMap<String, AtomicLong>()
    private val assetRefreshJobs = ConcurrentHashMap<String, Job>()

    private val _snackbarChannel = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarChannel.receiveAsFlow()

    private data class NavigationFilterState(
        val tab: MyServersTab,
        val probeFilter: ProbeOnlineFilter,
        val assetCategoryFilter: AssetCategoryFilter,
        val panelIdFilter: String?,
        val showPanelManager: Boolean,
        val panelManagerInitialSection: PanelManagerSection,
        val panelTestState: PanelTestUiState,
    )

    private data class SheetModalState(
        val editorDraft: AssetEditorDraft?,
        val assetSaveError: String?,
        val panelSaveError: String?,
        val panelSaveSuccessVersion: Long,
        val saleAsset: VpsAssetRecord?,
        val bindingAsset: VpsAssetRecord?,
        val isLookingUpRdap: Boolean,
        val rdapLookupError: String?,
        val isCheckingTls: Boolean,
        val tlsCheckResultMessage: String?,
        val refreshingDomainAssetIds: Set<String>,
    )

    private data class RepoCollections(
        val panels: List<ProbePanelConfig>,
        val snapshots: List<ProbeServerSnapshot>,
        val assets: List<VpsAssetRecord>,
        val cpuHistoryByProbeKey: Map<String, List<ProbeDailyCpuBucket>>,
        val settings: FleetMonitorSettings,
        val isRefreshing: Boolean,
        val lastSyncEpochMillis: Long,
        val panelErrors: Map<String, String>,
        val isUsingSoftwareTokenFallback: Boolean,
    )

    private val navFilterFlow = combine(
        combine(
            _selectedTab,
            _probeFilter,
            _assetCategoryFilter,
            _selectedPanelIdFilter,
        ) { tab, probeFilter, assetFilter, panelIdFilter ->
            Quad(tab, probeFilter, assetFilter, panelIdFilter)
        },
        combine(
            _showPanelManagerSheet,
            _panelManagerInitialSection,
            _panelTestState,
        ) { showPanelManager, initialSection, testState ->
            Triple(showPanelManager, initialSection, testState)
        },
    ) { (tab, probeFilter, assetFilter, panelIdFilter), (showPanelManager, initialSection, testState) ->
        NavigationFilterState(
            tab = tab,
            probeFilter = probeFilter,
            assetCategoryFilter = assetFilter,
            panelIdFilter = panelIdFilter,
            showPanelManager = showPanelManager,
            panelManagerInitialSection = initialSection,
            panelTestState = testState,
        )
    }

    private val domainLookupUiFlow = combine(
        _isLookingUpRdap,
        _rdapLookupError,
        _isCheckingTls,
        _tlsCheckResultMessage,
        _refreshingDomainAssetIds,
    ) { lookingUpRdap, rdapErr, checkingTls, tlsMsg, refreshingIds ->
        Quint(lookingUpRdap, rdapErr, checkingTls, tlsMsg, refreshingIds)
    }

    private val modalFlow = combine(
        combine(
            _assetEditorDraft,
            _assetSaveError,
            _panelSaveError,
        ) { draft, assetErr, panelErr ->
            Triple(draft, assetErr, panelErr)
        },
        combine(
            _panelSaveSuccessVersion,
            _salePostAsset,
            _bindingProbeAsset,
        ) { saveVer, saleAsset, bindingAsset ->
            Triple(saveVer, saleAsset, bindingAsset)
        },
        domainLookupUiFlow,
    ) { (draft, assetErr, panelErr), (saveVer, saleAsset, bindingAsset), (lookingUpRdap, rdapErr, checkingTls, tlsMsg, refreshingIds) ->
        SheetModalState(
            editorDraft = draft,
            assetSaveError = assetErr,
            panelSaveError = panelErr,
            panelSaveSuccessVersion = saveVer,
            saleAsset = saleAsset,
            bindingAsset = bindingAsset,
            isLookingUpRdap = lookingUpRdap,
            rdapLookupError = rdapErr,
            isCheckingTls = checkingTls,
            tlsCheckResultMessage = tlsMsg,
            refreshingDomainAssetIds = refreshingIds,
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
            fleetRepository.cpuHistoryByProbeKeyFlow,
            fleetRepository.isRefreshingFlow,
            fleetRepository.lastSyncEpochMillisFlow,
            fleetRepository.panelErrorsFlow,
            fleetRepository.isUsingSoftwareTokenFallbackFlow,
        ) { cpuHistory, refreshing, lastSync, errors, swFallback ->
            Quint(cpuHistory, refreshing, lastSync, errors, swFallback)
        },
    ) { (panels, snapshots, assets, settings), (cpuHistory, refreshing, lastSync, errors, swFallback) ->
        RepoCollections(
            panels = panels,
            snapshots = snapshots,
            assets = assets,
            cpuHistoryByProbeKey = cpuHistory,
            settings = settings,
            isRefreshing = refreshing,
            lastSyncEpochMillis = lastSync,
            panelErrors = errors,
            isUsingSoftwareTokenFallback = swFallback,
        )
    }

    // Decoupled from 2s probe CPU/net ticks: only recomputes BigDecimal CNY costs & renewals when
    // assets, FX rates, or probe node total/online counts actually change.
    private val probeOnlineCountsFlow = fleetRepository.snapshotsFlow
        .map { snaps -> snaps.size to snaps.count { it.isOnline } }
        .distinctUntilChanged()

    private val portfolioSummaryFlow: Flow<Pair<FxRatesState?, FleetPortfolioSummary>> = combine(
        fleetRepository.assetsFlow,
        exchangeRateRepository.state,
        probeOnlineCountsFlow,
    ) { assets, fxState, (totalNodes, onlineNodes) ->
        val base = FleetPortfolioCalculator.summarize(
            assets = assets,
            snapshots = emptyList(),
            fxRatesState = fxState,
            today = todayProvider(),
        )
        val offlineNodes = (totalNodes - onlineNodes).coerceAtLeast(0)
        fxState to base.copy(
            totalProbeNodeCount = totalNodes,
            onlineProbeCount = onlineNodes,
            offlineProbeCount = offlineNodes,
        )
    }

    private val rawUiStateFlow: Flow<MyServersUiState> = combine(
        repoFlow,
        portfolioSummaryFlow,
        navFilterFlow,
        modalFlow,
    ) { repo, (fxState, summary), nav, modals ->
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
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.probeKey },
            )
            .toList()

        val today = todayProvider()
        val evalEpochMillis = FleetTrafficAndIdleAnalyzer.alignEpochMillisWithDate(
            today = today,
            nowEpochMillis = nowMillisProvider(),
        )
        val assetsByProbeKey = LinkedHashMap<String, VpsAssetRecord>()
        for (asset in repo.assets) {
            val key = asset.linkedProbeKey?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            assetsByProbeKey.putIfAbsent(key, asset)
        }

        val forecastsByKey = LinkedHashMap<String, ServerTrafficForecast>(repo.snapshots.size)
        val idleByKey = LinkedHashMap<String, ServerIdleAssessment>(repo.snapshots.size)
        for (snapshot in repo.snapshots) {
            val linkedAsset = assetsByProbeKey[snapshot.probeKey]
            val forecast = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
                snapshot = snapshot,
                linkedAsset = linkedAsset,
                nowEpochMillis = evalEpochMillis,
            )
            forecastsByKey[snapshot.probeKey] = forecast

            val cpuBuckets = repo.cpuHistoryByProbeKey[snapshot.probeKey].orEmpty()
            idleByKey[snapshot.probeKey] = FleetTrafficAndIdleAnalyzer.assessIdle(
                snapshot = snapshot,
                linkedAsset = linkedAsset,
                cpuBuckets = cpuBuckets,
                trafficForecast = forecast,
                nowEpochMillis = evalEpochMillis,
                config = idleThresholdConfig,
            )
        }

        val highTrafficForecasts = forecastsByKey.values
            .filter { it.isAtOrAboveAlertThreshold }
            .sortedWith(
                compareByDescending<ServerTrafficForecast> { it.usageRatio ?: 0.0 }
                    .thenBy { it.probeKey },
            )

        val idleServers = idleByKey.values
            .filter { it.isIdle }
            .sortedWith(
                compareBy<ServerIdleAssessment> { it.linkedAsset == null }
                    .thenBy { it.avgCpu30dPercent ?: 100.0 }
                    .thenBy { it.serverName.lowercase() }
                    .thenBy { it.probeKey },
            )

        val filteredAssets = when (nav.assetCategoryFilter) {
            AssetCategoryFilter.ALL -> repo.assets
            AssetCategoryFilter.VPS -> repo.assets.filter { !it.isDomainAsset }
            AssetCategoryFilter.DOMAIN_CERT -> repo.assets.filter { it.isDomainAsset }
        }

        MyServersUiState(
            selectedTab = nav.tab,
            probeFilter = nav.probeFilter,
            selectedPanelIdFilter = effectivePanelFilter,
            panels = repo.panels,
            snapshots = repo.snapshots,
            filteredSnapshots = filtered,
            assets = repo.assets,
            cpuHistoryByProbeKey = repo.cpuHistoryByProbeKey,
            trafficForecastsByProbeKey = forecastsByKey,
            highTrafficForecasts = highTrafficForecasts,
            idleAssessmentsByProbeKey = idleByKey,
            idleServers = idleServers,
            portfolioSummary = summary,
            fxRatesState = fxState,
            monitorSettings = repo.settings,
            isRefreshing = repo.isRefreshing,
            lastSyncEpochMillis = repo.lastSyncEpochMillis,
            panelErrors = repo.panelErrors,
            showPanelManagerSheet = nav.showPanelManager,
            panelManagerInitialSection = nav.panelManagerInitialSection,
            panelTestState = nav.panelTestState,
            panelSaveError = modals.panelSaveError,
            panelSaveSuccessVersion = modals.panelSaveSuccessVersion,
            isUsingSoftwareTokenFallback = repo.isUsingSoftwareTokenFallback,
            assetEditorDraft = modals.editorDraft,
            assetSaveError = modals.assetSaveError,
            salePostAsset = modals.saleAsset,
            bindingProbeAsset = modals.bindingAsset,
            assetCategoryFilter = nav.assetCategoryFilter,
            filteredAssets = filteredAssets,
            isLookingUpRdap = modals.isLookingUpRdap,
            rdapLookupError = modals.rdapLookupError,
            isCheckingTls = modals.isCheckingTls,
            tlsCheckResultMessage = modals.tlsCheckResultMessage,
            refreshingDomainAssetIds = modals.refreshingDomainAssetIds,
        )
    }

    val uiState: StateFlow<MyServersUiState> = (
        if (computationDispatcher != null) {
            rawUiStateFlow.flowOn(computationDispatcher)
        } else {
            rawUiStateFlow
        }
        ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MyServersUiState(selectedTab = initialTab),
    )

    init {
        viewModelScope.launch {
            try {
                fleetRepository.loadPersistedState()
                try {
                    val autoRolled = fleetRepository.autoRollExpiredAutoRenewAssets(todayProvider())
                    if (autoRolled.isNotEmpty()) {
                        _snackbarChannel.trySend("已自动顺延 ${autoRolled.size} 台自动续费资产的到期日")
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // Best-effort auto-roll must not block ledger loading or probe sync.
                }
                syncBackgroundSchedule()
                if (fleetRepository.panelsFlow.value.any { it.enabled }) {
                    probeRefreshMutex.withLock {
                        fleetRepository.refreshAllPanels()
                    }
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
        _panelManagerInitialSection.value = PanelManagerSection.PANELS
        _panelTestState.value = PanelTestUiState.Idle
        _panelSaveError.value = null
        _showPanelManagerSheet.value = true
    }

    fun openMonitorSettings() {
        _panelManagerInitialSection.value = PanelManagerSection.MONITOR_SETTINGS
        _panelTestState.value = PanelTestUiState.Idle
        _panelSaveError.value = null
        _showPanelManagerSheet.value = true
    }

    fun closePanelManager() {
        _showPanelManagerSheet.value = false
        _panelManagerInitialSection.value = PanelManagerSection.PANELS
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
                _panelSaveSuccessVersion.value += 1L
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

    fun refreshCurrentTab() {
        when (_selectedTab.value) {
            MyServersTab.PROBES -> refreshAllProbes()
            MyServersTab.ASSETS -> {
                refreshFxRates()
                if (fleetRepository.panelsFlow.value.any { it.enabled }) {
                    viewModelScope.launch {
                        refreshProbesSilently()
                    }
                }
            }
        }
    }

    fun refreshAllProbes() {
        if (!manualRefreshRequested.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val summary = probeRefreshMutex.withLock {
                    fleetRepository.refreshAllPanels(silent = false)
                }
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
            } finally {
                manualRefreshRequested.set(false)
            }
        }
    }

    suspend fun refreshProbesSilently() {
        if (!fleetRepository.isPersistedStateLoadedFlow.value) return
        if (!fleetRepository.panelsFlow.value.any { it.enabled }) return
        if (manualRefreshRequested.get()) return
        if (!probeRefreshMutex.tryLock()) return
        try {
            fleetRepository.refreshAllPanels(silent = true)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Silent background/foreground tick keeps previous snapshots and panelErrorsFlow intact.
        } finally {
            probeRefreshMutex.unlock()
        }
    }

    /**
     * Runs a silent live telemetry refresh loop (default every 2 s) while `MyServersScreen` is
     * visible in the foreground, guarded by [probeRefreshMutex] so slow requests never overlap or
     * pile up, and pacing subsequent delays against request duration.
     */
    suspend fun runForegroundProbeAutoRefresh(
        intervalMillis: Long = DEFAULT_FOREGROUND_POLL_INTERVAL_MS,
    ) {
        val safeInterval = intervalMillis.coerceAtLeast(MIN_FOREGROUND_POLL_INTERVAL_MS)
        val lastSync = fleetRepository.lastSyncEpochMillisFlow.value
        if (lastSync > 0L && (nowMillisProvider() - lastSync) >= safeInterval) {
            refreshProbesSilently()
        }
        var nextDelayMs = safeInterval
        while (currentCoroutineContext().isActive) {
            delay(nextDelayMs)
            val startedAt = nowMillisProvider()
            refreshProbesSilently()
            val elapsed = (nowMillisProvider() - startedAt).coerceAtLeast(0L)
            nextDelayMs = if (elapsed >= safeInterval) {
                safeInterval
            } else {
                (safeInterval - elapsed).coerceAtLeast(MIN_POST_POLL_GAP_MS)
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

    private fun resetDraftInspectionJobs() {
        rdapLookupSeq.incrementAndGet()
        rdapLookupJob?.cancel()
        rdapLookupJob = null
        tlsCheckSeq.incrementAndGet()
        tlsCheckJob?.cancel()
        tlsCheckJob = null
        _isLookingUpRdap.value = false
        _isCheckingTls.value = false
    }

    fun openAddAssetSheet() {
        resetDraftInspectionJobs()
        _assetSaveError.value = null
        _rdapLookupError.value = null
        _tlsCheckResultMessage.value = null
        _assetEditorDraft.value = AssetEditorDraft(
            expiryDateIso = AssetEditorDraft.defaultExpiryDateIso(todayProvider()),
        )
    }

    fun openEditAssetSheet(asset: VpsAssetRecord) {
        resetDraftInspectionJobs()
        _assetSaveError.value = null
        _rdapLookupError.value = null
        _tlsCheckResultMessage.value = null
        _assetEditorDraft.value = AssetEditorDraft.fromRecord(asset)
    }

    fun openAddAssetFromProbe(
        snapshot: ProbeServerSnapshot,
        openSalePostAfterSave: Boolean = false,
    ) {
        val specsParts = buildList {
            if (snapshot.cpuCores > 0) add("${snapshot.cpuCores}C")
            if (snapshot.memTotalBytes > 0L) add(formatBytesBinary(snapshot.memTotalBytes))
            if (snapshot.diskTotalBytes > 0L) add(formatBytesBinary(snapshot.diskTotalBytes))
        }
        val quotaGb = snapshot.trafficLimitBytes?.takeIf { it > 0L }?.let { bytes ->
            val gb = bytes / (1024.0 * 1024.0 * 1024.0)
            val formatted = String.format(Locale.US, "%.1f", gb)
            if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
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
            openSalePostAfterSave = openSalePostAfterSave,
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
        resetDraftInspectionJobs()
        _assetSaveError.value = null
        _rdapLookupError.value = null
        _tlsCheckResultMessage.value = null
        _assetEditorDraft.value = null
    }

    fun selectAssetCategoryFilter(filter: AssetCategoryFilter) {
        _assetCategoryFilter.value = filter
    }

    fun openAddDomainAssetSheet() {
        resetDraftInspectionJobs()
        _assetSaveError.value = null
        _rdapLookupError.value = null
        _tlsCheckResultMessage.value = null
        _selectedTab.value = MyServersTab.ASSETS
        _assetEditorDraft.value = AssetEditorDraft(
            assetType = FleetAssetType.DOMAIN_CERT,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = AssetEditorDraft.defaultExpiryDateIso(todayProvider()),
        )
    }

    fun lookupDomainRdapForDraft(rawDomain: String) {
        val cleanDomain = VpsAssetRecord.normalizeDomainName(rawDomain)
        if (cleanDomain == null) {
            _rdapLookupError.value = "请输入有效的域名，例如 example.com"
            return
        }
        val initialDraft = _assetEditorDraft.value ?: return
        val editingIdSnapshot = initialDraft.editingId
        val seq = rdapLookupSeq.incrementAndGet()
        rdapLookupJob?.cancel()
        _rdapLookupError.value = null
        _isLookingUpRdap.value = true
        rdapLookupJob = viewModelScope.launch {
            try {
                val res = effectiveRdapClient.lookupDomain(cleanDomain)
                val current = _assetEditorDraft.value
                if (rdapLookupSeq.get() != seq || current == null || current.editingId != editingIdSnapshot) {
                    return@launch
                }
                res.onSuccess { rdap ->
                    val registrar = rdap.registrarName?.trim().orEmpty()
                    val isDifferentDomain = current.domainName.isNotBlank() &&
                        !current.domainName.trim().equals(rdap.domainName, ignoreCase = true)
                    val shouldUpdateDraftTlsHost = current.tlsHost.isBlank() ||
                        current.tlsHost.trim().equals(current.domainName.trim(), ignoreCase = true)
                    val nextRegistrar = when {
                        registrar.isNotEmpty() -> registrar
                        isDifferentDomain -> ""
                        else -> current.registrarName
                    }
                    val nextProvider = when {
                        registrar.isNotEmpty() -> registrar
                        isDifferentDomain -> ""
                        else -> current.providerName
                    }
                    _assetEditorDraft.value = current.copy(
                        assetType = FleetAssetType.DOMAIN_CERT,
                        domainName = rdap.domainName,
                        nickname = current.nickname.ifBlank { rdap.domainName },
                        registrarName = nextRegistrar,
                        providerName = nextProvider,
                        expiryDateIso = rdap.expiryDateIso ?: current.expiryDateIso,
                        domainCreatedIso = rdap.registrationDateIso ?: if (isDifferentDomain) null else current.domainCreatedIso,
                        rdapSyncedAtEpochMillis = rdap.fetchedAtEpochMillis,
                        rdapStatusTags = rdap.statuses,
                        tlsHost = if (shouldUpdateDraftTlsHost) rdap.domainName else current.tlsHost,
                        rdapSyncRevision = current.rdapSyncRevision + 1,
                    )
                    _rdapLookupError.value = null
                    _snackbarChannel.trySend(rdap.toSyncSummary())
                }.onFailure { err ->
                    _rdapLookupError.value = err.message ?: "RDAP 查询失败"
                }
            } finally {
                if (rdapLookupSeq.get() == seq) {
                    _isLookingUpRdap.value = false
                }
            }
        }
    }

    fun checkTlsForDraft(
        rawHost: String,
        port: Int = VpsAssetRecord.DEFAULT_TLS_PORT,
    ) {
        val cleanHost = VpsAssetRecord.normalizeDomainName(rawHost)
        if (cleanHost == null) {
            _tlsCheckResultMessage.value = "请输入有效的 HTTPS 主机名或域名"
            return
        }
        val initialDraft = _assetEditorDraft.value ?: return
        val editingIdSnapshot = initialDraft.editingId
        val cleanPort = port.coerceIn(1, 65535)
        val seq = tlsCheckSeq.incrementAndGet()
        tlsCheckJob?.cancel()
        _tlsCheckResultMessage.value = null
        _isCheckingTls.value = true
        tlsCheckJob = viewModelScope.launch {
            try {
                val res = effectiveTlsInspector.inspect(cleanHost, cleanPort)
                val current = _assetEditorDraft.value
                if (tlsCheckSeq.get() != seq || current == null || current.editingId != editingIdSnapshot) {
                    return@launch
                }
                res.onSuccess { tls ->
                    _assetEditorDraft.value = current.copy(
                        tlsHost = tls.host,
                        tlsPort = tls.port.toString(),
                        tlsExpiresAtEpochMillis = tls.notAfterEpochMillis,
                        tlsIssuer = tls.issuer,
                        tlsSubject = tls.subject,
                        tlsCheckedAtEpochMillis = tls.checkedAtEpochMillis,
                        tlsCheckError = if (tls.isExpired) {
                            "证书已过期 ${-tls.daysRemaining} 天"
                        } else if (!tls.isTrusted) {
                            tls.warningMessage ?: "证书未受系统信任"
                        } else {
                            null
                        },
                        tlsCheckRevision = current.tlsCheckRevision + 1,
                    )
                    val summaryMsg = buildString {
                        if (tls.isExpired) {
                            append("证书已过期 ${-tls.daysRemaining} 天")
                        } else {
                            append("TLS 剩余 ${tls.daysRemaining} 天")
                        }
                        if (tls.issuer.isNotBlank()) append(" · ${tls.issuer}")
                        append(" · 到期 ${tls.expiresDateIso}")
                        if (!tls.isTrusted && !tls.isExpired) append(" (未受信任)")
                    }
                    _tlsCheckResultMessage.value = summaryMsg
                }.onFailure { err ->
                    val errMsg = err.message ?: "HTTPS 证书检测失败"
                    _assetEditorDraft.value = current.copy(
                        tlsHost = cleanHost,
                        tlsPort = cleanPort.toString(),
                        tlsCheckedAtEpochMillis = nowMillisProvider(),
                        tlsCheckError = errMsg,
                        tlsCheckRevision = current.tlsCheckRevision + 1,
                    )
                    _tlsCheckResultMessage.value = errMsg
                }
            } finally {
                if (tlsCheckSeq.get() == seq) {
                    _isCheckingTls.value = false
                }
            }
        }
    }

    fun refreshDomainAndTlsForAsset(assetId: String) {
        val asset = fleetRepository.assetsFlow.value.firstOrNull { it.id == assetId }
            ?: uiState.value.assets.firstOrNull { it.id == assetId }
            ?: return
        val domain = asset.domainName?.let { VpsAssetRecord.normalizeDomainName(it) }
        val tlsTarget = asset.effectiveTlsHost?.let { VpsAssetRecord.normalizeDomainName(it) }
        if (domain == null && tlsTarget == null) {
            _snackbarChannel.trySend("该资产未设置域名或 HTTPS 主机")
            return
        }
        val seqCounter = assetRefreshSeqs.computeIfAbsent(assetId) { AtomicLong(0L) }
        val seq = seqCounter.incrementAndGet()
        assetRefreshJobs.remove(assetId)?.cancel()
        _refreshingDomainAssetIds.value = _refreshingDomainAssetIds.value + assetId
        assetRefreshJobs[assetId] = viewModelScope.launch {
            try {
                var rdapResult: com.nodeseek.app.domain.fleet.DomainRdapResult? = null
                var tlsResult: com.nodeseek.app.domain.fleet.TlsCertificateInspectionResult? = null
                var tlsFailureMessage: String? = null
                var rdapOk = false
                var tlsOk = false
                if (domain != null) {
                    effectiveRdapClient.lookupDomain(domain).onSuccess { rdap ->
                        rdapOk = true
                        rdapResult = rdap
                    }
                }
                if (tlsTarget != null) {
                    effectiveTlsInspector.inspect(tlsTarget, asset.tlsPort).onSuccess { tls ->
                        tlsOk = true
                        tlsResult = tls
                    }.onFailure { err ->
                        tlsFailureMessage = err.message ?: "HTTPS 证书检测失败"
                    }
                }
                if (seqCounter.get() != seq) {
                    return@launch
                }
                val latestAsset = fleetRepository.assetsFlow.value.firstOrNull { it.id == assetId }
                    ?: return@launch
                var updated = latestAsset
                rdapResult?.let { rdap ->
                    val registrar = rdap.registrarName?.trim().orEmpty()
                    updated = updated.copy(
                        domainName = rdap.domainName,
                        registrarName = registrar.ifBlank { updated.registrarName },
                        providerName = registrar.ifBlank { updated.providerName },
                        expiryDateIso = rdap.expiryDateIso ?: updated.expiryDateIso,
                        domainCreatedIso = rdap.registrationDateIso ?: updated.domainCreatedIso,
                        rdapSyncedAtEpochMillis = rdap.fetchedAtEpochMillis,
                        rdapStatusTags = rdap.statuses,
                    )
                }
                val tls = tlsResult
                if (tls != null) {
                    updated = updated.copy(
                        tlsHost = tls.host,
                        tlsPort = tls.port,
                        tlsExpiresAtEpochMillis = tls.notAfterEpochMillis,
                        tlsIssuer = tls.issuer,
                        tlsSubject = tls.subject,
                        tlsCheckedAtEpochMillis = tls.checkedAtEpochMillis,
                        tlsCheckError = if (tls.isExpired) {
                            "证书已过期 ${-tls.daysRemaining} 天"
                        } else if (!tls.isTrusted) {
                            tls.warningMessage ?: "证书未受系统信任"
                        } else {
                            null
                        },
                    )
                } else if (tlsFailureMessage != null) {
                    updated = updated.copy(
                        tlsCheckedAtEpochMillis = nowMillisProvider(),
                        tlsCheckError = tlsFailureMessage,
                    )
                }
                fleetRepository.upsertAsset(updated)
                val msg = when {
                    rdapOk && tlsOk -> "已刷新「${updated.nickname}」RDAP 与 TLS 证书状态"
                    rdapOk -> "已刷新「${updated.nickname}」RDAP 信息"
                    tlsOk -> "已更新「${updated.nickname}」TLS 证书剩余天数"
                    else -> "未能拉取「${updated.nickname}」的 RDAP/TLS 更新"
                }
                _snackbarChannel.trySend(msg)
            } finally {
                if (seqCounter.get() == seq) {
                    _refreshingDomainAssetIds.value = _refreshingDomainAssetIds.value - assetId
                }
            }
        }
    }

    fun saveAsset(asset: VpsAssetRecord) {
        val previousDraft = _assetEditorDraft.value
        val preservedEditingId = when {
            previousDraft != null -> previousDraft.editingId
            fleetRepository.assetsFlow.value.any { it.id == asset.id } -> asset.id
            else -> null
        }
        val shouldOpenSalePost = previousDraft?.openSalePostAfterSave == true
        val draftFromSubmission = AssetEditorDraft.fromRecord(asset).copy(
            editingId = preservedEditingId,
            customCycleDays = (
                asset.customCycleDays
                    ?: previousDraft?.customCycleDays?.toIntOrNull()
                    ?: 365
                ).toString(),
            openSalePostAfterSave = shouldOpenSalePost,
        )
        _assetEditorDraft.value = draftFromSubmission
        _assetSaveError.value = null

        viewModelScope.launch {
            try {
                val saved = fleetRepository.upsertAsset(asset)
                _assetSaveError.value = null
                _assetEditorDraft.value = null
                if (shouldOpenSalePost) {
                    _salePostAsset.value = saved
                }
                syncBackgroundSchedule()
                _snackbarChannel.trySend(
                    if (shouldOpenSalePost) {
                        "已保存资产「${saved.nickname}」，已自动打开出售帖生成器"
                    } else {
                        "已保存资产「${saved.nickname}」"
                    },
                )
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
        assetRefreshSeqs.computeIfAbsent(assetId) { AtomicLong(0L) }.incrementAndGet()
        assetRefreshJobs.remove(assetId)?.cancel()
        _refreshingDomainAssetIds.value = _refreshingDomainAssetIds.value - assetId
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

    fun markAssetRenewed(assetId: String) {
        viewModelScope.launch {
            try {
                val result = fleetRepository.markAssetRenewed(
                    assetId = assetId,
                    today = todayProvider(),
                )
                if (result == null) {
                    _snackbarChannel.trySend("未找到对应服务器资产")
                    return@launch
                }
                syncBackgroundSchedule()
                val cycleSuffix = if (result.cyclesAdvanced > 1) {
                    "（顺延 ${result.cyclesAdvanced} 个周期）"
                } else {
                    ""
                }
                _snackbarChannel.trySend(
                    "已确认「${result.updatedAsset.nickname}」续费$cycleSuffix，新到期日：${result.newExpiryDateIso}",
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("标记已续费失败：${formatPersistenceError(t)}")
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

    // ── One-Click Valuation & Sale Post ("发现浪费 → 卖掉回血" 闭环) ─────────────

    fun openSalePostSheet(asset: VpsAssetRecord) {
        _salePostAsset.value = asset
    }

    /**
     * One-tap closed-loop entry from an idle ("闲置") probe card or idle fleet banner:
     * - Opens [FleetSalePostSheet] directly when the node is already linked to a [VpsAssetRecord].
     * - Otherwise opens [AssetEditorBottomSheet] prefilled from the probe snapshot and automatically
     *   transitions into [FleetSalePostSheet] once the user fills in renewal price & expiry.
     */
    fun openIdleSalePost(probeKey: String) {
        val linkedAsset = fleetRepository.assetsFlow.value.firstOrNull { it.linkedProbeKey == probeKey }
            ?: uiState.value.assets.firstOrNull { it.linkedProbeKey == probeKey }
        if (linkedAsset != null) {
            _salePostAsset.value = linkedAsset
            return
        }
        val snapshot = fleetRepository.snapshotsFlow.value.firstOrNull { it.probeKey == probeKey }
            ?: uiState.value.snapshots.firstOrNull { it.probeKey == probeKey }
            ?: return
        openAddAssetFromProbe(snapshot = snapshot, openSalePostAfterSave = true)
        _snackbarChannel.trySend("请先补齐续费价格与到期日，保存后将自动生成出售帖")
    }

    fun closeSalePostSheet() {
        _salePostAsset.value = null
    }

    fun buildSalePost(request: FleetSalePostRequest): FleetSalePostOutcome =
        FleetSalePostBuilder.build(request)

    /**
     * Builds a ready-to-publish NodeSeek `trade` sale post for [asset] using its live linked probe
     * telemetry and current exchange rates via [FleetSalePostBuilder].
     */
    fun generateSalePostForAsset(
        asset: VpsAssetRecord,
        askingPriceCny: BigDecimal? = null,
        extraNote: String? = null,
    ): FleetSalePostOutcome {
        val currentState = uiState.value
        val linkedProbe = asset.linkedProbeKey?.let { key ->
            currentState.snapshots.firstOrNull { it.probeKey == key }
                ?: fleetRepository.snapshotsFlow.value.firstOrNull { it.probeKey == key }
        }
        val fxRates = currentState.fxRatesState
        val fxQuote = fxRates?.quoteFor(asset.currency)
        val fxRate = fxQuote?.rateToCny ?: asset.currency.defaultCnyRate
        val fxSourceNote = when (fxQuote?.source) {
            FxRateSource.ONLINE_FRESH -> fxRates.providerDate?.let { "在线汇率 $it" } ?: "在线实时汇率"
            FxRateSource.ONLINE_CACHED -> "本地缓存汇率"
            FxRateSource.MANUAL_OVERRIDE -> "手动校准汇率"
            else -> "参考汇率"
        }
        return FleetSalePostBuilder.build(
            FleetSalePostRequest(
                asset = asset,
                probeSnapshot = linkedProbe,
                fxRateToCny = fxRate,
                fxSourceNote = fxSourceNote,
                askingPriceCny = askingPriceCny,
                includeProbeTelemetry = linkedProbe != null,
                extraNote = extraNote ?: asset.notes,
                valuationDateTime = todayProvider().atStartOfDay(),
            ),
        )
    }

    /**
     * Convenience closed-loop builder for an idle server identified by [probeKey].
     */
    fun generateIdleSalePost(
        probeKey: String,
        askingPriceCny: BigDecimal? = null,
    ): FleetSalePostOutcome {
        val linkedAsset = uiState.value.assets.firstOrNull { it.linkedProbeKey == probeKey }
            ?: fleetRepository.assetsFlow.value.firstOrNull { it.linkedProbeKey == probeKey }
            ?: return FleetSalePostOutcome.Invalid(listOf("该闲置节点尚未绑定资产账本，请先补齐续费价格与到期日"))
        return generateSalePostForAsset(
            asset = linkedAsset,
            askingPriceCny = askingPriceCny,
        )
    }

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
    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    companion object {
        const val DEFAULT_FOREGROUND_POLL_INTERVAL_MS = 2_000L
        private const val MIN_FOREGROUND_POLL_INTERVAL_MS = 2_000L
        private const val MIN_POST_POLL_GAP_MS = 500L

        fun factory(
            fleetRepository: FleetRepository,
            exchangeRateRepository: ExchangeRateRepository,
            workScheduler: FleetWorkScheduler? = null,
            initialTab: MyServersTab = MyServersTab.PROBES,
            domainRdapClient: DomainRdapClient? = null,
            tlsCertificateInspector: TlsCertificateInspector? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MyServersViewModel(
                    fleetRepository = fleetRepository,
                    exchangeRateRepository = exchangeRateRepository,
                    workScheduler = workScheduler,
                    initialTab = initialTab,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = domainRdapClient,
                    tlsCertificateInspector = tlsCertificateInspector,
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
