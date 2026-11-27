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
import com.nodeseek.app.domain.fleet.DomainRdapResult
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.TlsCertificateInspectionResult
import com.nodeseek.app.domain.fleet.TlsCertificateInspector
import com.nodeseek.app.domain.fleet.UpcomingRenewalItem
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DomainAssetFilter(val label: String) {
    ALL("全部"),
    DUE_SOON("近期续费"),
    TLS_ALERT("证书预警"),
}

@Immutable
data class DomainAssetsUiState(
    val domainAssets: List<VpsAssetRecord> = emptyList(),
    val filteredDomains: List<VpsAssetRecord> = emptyList(),
    val filter: DomainAssetFilter = DomainAssetFilter.ALL,
    val upcomingRenewals: List<UpcomingRenewalItem> = emptyList(),
    val domainMonthlyCostCny: BigDecimal = BigDecimal.ZERO.setScale(2),
    val domainAnnualizedCostCny: BigDecimal = BigDecimal.ZERO.setScale(2),
    val dueSoonCount: Int = 0,
    val expiredCount: Int = 0,
    val tlsAlertCount: Int = 0,
    val tlsHealthyCount: Int = 0,
    val fxRatesState: FxRatesState? = null,
    val isRefreshingAll: Boolean = false,
    val assetEditorDraft: AssetEditorDraft? = null,
    val assetSaveError: String? = null,
    val isLookingUpRdap: Boolean = false,
    val rdapLookupError: String? = null,
    val rdapSyncSummary: String? = null,
    val isCheckingTls: Boolean = false,
    val tlsCheckResultMessage: String? = null,
    val refreshingDomainAssetIds: Set<String> = emptySet(),
)

/**
 * Dedicated ViewModel for the standalone Domain & TLS Certificate Asset Management screen
 * (`DomainAssetsScreen`), completely decoupled from the VPS Server Asset Ledger UI.
 */
class DomainAssetsViewModel(
    private val fleetRepository: FleetRepository,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val workScheduler: FleetWorkScheduler? = null,
    private val todayProvider: () -> LocalDate = {
        VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID))
    },
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val computationDispatcher: CoroutineDispatcher? = null,
    private val domainRdapClient: DomainRdapClient? = null,
    private val tlsCertificateInspector: TlsCertificateInspector? = null,
) : ViewModel() {

    private val effectiveRdapClient: DomainRdapClient
        get() = domainRdapClient ?: DomainRdapClient.sharedDefault

    private val effectiveTlsInspector: TlsCertificateInspector by lazy {
        tlsCertificateInspector ?: JvmTlsCertificateInspector(nowMillisProvider = nowMillisProvider)
    }

    private val _filter = MutableStateFlow(DomainAssetFilter.ALL)
    private val _isRefreshingAll = MutableStateFlow(false)
    private val _assetEditorDraft = MutableStateFlow<AssetEditorDraft?>(null)
    private val _assetSaveError = MutableStateFlow<String?>(null)
    private val _isLookingUpRdap = MutableStateFlow(false)
    private val _rdapLookupError = MutableStateFlow<String?>(null)
    private val _rdapSyncSummary = MutableStateFlow<String?>(null)
    private val _isCheckingTls = MutableStateFlow(false)
    private val _tlsCheckResultMessage = MutableStateFlow<String?>(null)
    private val _refreshingDomainAssetIds = MutableStateFlow<Set<String>>(emptySet())

    private val batchRefreshInFlight = AtomicBoolean(false)
    private val rdapLookupSeq = AtomicLong(0L)
    private var rdapLookupJob: Job? = null
    private val tlsCheckSeq = AtomicLong(0L)
    private var tlsCheckJob: Job? = null
    private val assetRefreshSeqs = ConcurrentHashMap<String, AtomicLong>()
    private val assetRefreshJobs = ConcurrentHashMap<String, Job>()

    private val _snackbarChannel = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarChannel.receiveAsFlow()

    private data class EditorModalState(
        val draft: AssetEditorDraft?,
        val saveError: String?,
        val isLookingUpRdap: Boolean,
        val rdapLookupError: String?,
        val rdapSyncSummary: String?,
        val isCheckingTls: Boolean,
        val tlsCheckResultMessage: String?,
        val refreshingAssetIds: Set<String>,
    )

    private val editorModalFlow = combine(
        combine(_assetEditorDraft, _assetSaveError, _isLookingUpRdap, _rdapLookupError) { d, sErr, looking, rErr ->
            Quad(d, sErr, looking, rErr)
        },
        combine(_rdapSyncSummary, _isCheckingTls, _tlsCheckResultMessage, _refreshingDomainAssetIds) { rSum, checking, tMsg, refIds ->
            Quad(rSum, checking, tMsg, refIds)
        },
    ) { (draft, saveErr, lookingUpRdap, rdapErr), (rdapSum, checkingTls, tlsMsg, refreshingIds) ->
        EditorModalState(
            draft = draft,
            saveError = saveErr,
            isLookingUpRdap = lookingUpRdap,
            rdapLookupError = rdapErr,
            rdapSyncSummary = rdapSum,
            isCheckingTls = checkingTls,
            tlsCheckResultMessage = tlsMsg,
            refreshingAssetIds = refreshingIds,
        )
    }

    private val rawUiStateFlow: Flow<DomainAssetsUiState> = combine(
        fleetRepository.assetsFlow,
        exchangeRateRepository.state,
        _filter,
        _isRefreshingAll,
        editorModalFlow,
    ) { allAssets, fxState, filter, refreshingAll, modal ->
        val domains = allAssets.filter { it.isDomainAsset }
        val today = todayProvider()
        val nowMillis = nowMillisProvider()
        val summary = FleetPortfolioCalculator.summarize(
            assets = domains,
            snapshots = emptyList(),
            fxRatesState = fxState,
            today = today,
        )
        val renewalsById = summary.upcomingRenewals.associateBy { it.asset.id }

        var tlsAlerts = 0
        var tlsHealthy = 0
        for (asset in domains) {
            val tlsDays = asset.tlsDaysRemaining(nowEpochMillis = nowMillis)
            val hasTlsAlert = !asset.tlsCheckError.isNullOrBlank() ||
                (tlsDays != null && tlsDays <= TLS_WARNING_DAYS_THRESHOLD)
            if (hasTlsAlert) {
                tlsAlerts++
            } else if (tlsDays != null) {
                tlsHealthy++
            }
        }

        val filtered = when (filter) {
            DomainAssetFilter.ALL -> domains
            DomainAssetFilter.DUE_SOON -> domains.filter { asset ->
                val renewal = renewalsById[asset.id]
                renewal != null && (renewal.isRenewalDue || renewal.daysRemaining <= 30L)
            }
            DomainAssetFilter.TLS_ALERT -> domains.filter { asset ->
                val tlsDays = asset.tlsDaysRemaining(nowEpochMillis = nowMillis)
                !asset.tlsCheckError.isNullOrBlank() || (tlsDays != null && tlsDays <= TLS_WARNING_DAYS_THRESHOLD)
            }
        }

        DomainAssetsUiState(
            domainAssets = domains,
            filteredDomains = filtered,
            filter = filter,
            upcomingRenewals = summary.upcomingRenewals,
            domainMonthlyCostCny = summary.domainMonthlyCostCny,
            domainAnnualizedCostCny = summary.domainAnnualizedCostCny,
            dueSoonCount = summary.dueSoonRenewalCount,
            expiredCount = summary.expiredRenewalCount,
            tlsAlertCount = tlsAlerts,
            tlsHealthyCount = tlsHealthy,
            fxRatesState = fxState,
            isRefreshingAll = refreshingAll,
            assetEditorDraft = modal.draft,
            assetSaveError = modal.saveError,
            isLookingUpRdap = modal.isLookingUpRdap,
            rdapLookupError = modal.rdapLookupError,
            rdapSyncSummary = modal.rdapSyncSummary,
            isCheckingTls = modal.isCheckingTls,
            tlsCheckResultMessage = modal.tlsCheckResultMessage,
            refreshingDomainAssetIds = modal.refreshingAssetIds,
        )
    }

    val uiState: StateFlow<DomainAssetsUiState> = (
        if (computationDispatcher != null) {
            rawUiStateFlow.flowOn(computationDispatcher)
        } else {
            rawUiStateFlow
        }
        ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DomainAssetsUiState(),
    )

    init {
        viewModelScope.launch {
            try {
                fleetRepository.loadPersistedState()
                try {
                    val autoRolled = fleetRepository.autoRollExpiredAutoRenewAssets(todayProvider())
                        .filter { it.updatedAsset.isDomainAsset }
                    if (autoRolled.isNotEmpty()) {
                        _snackbarChannel.trySend("已自动顺延 ${autoRolled.size} 个自动续费域名的到期日")
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // Best-effort auto-roll must not block loading.
                }
                syncBackgroundSchedule()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("加载域名资产失败：${formatPersistenceError(t)}")
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

    fun selectFilter(filter: DomainAssetFilter) {
        _filter.value = filter
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

    fun refreshAllDomainsAndTls() {
        if (!batchRefreshInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            _isRefreshingAll.value = true
            try {
                runCatching { exchangeRateRepository.refreshOnline() }
                val domains = fleetRepository.assetsFlow.value.filter { it.isDomainAsset }
                if (domains.isEmpty()) {
                    _snackbarChannel.trySend("已刷新汇率，暂未录入域名资产")
                    return@launch
                }
                var updatedCount = 0
                for (asset in domains) {
                    val didUpdate = refreshSingleAssetSuspend(asset.id, emitSnackbar = false)
                    if (didUpdate) updatedCount++
                }
                _snackbarChannel.trySend(
                    if (updatedCount > 0) {
                        "已刷新 $updatedCount/${domains.size} 个域名的 RDAP 与 TLS 状态"
                    } else {
                        "未能拉取域名 RDAP/TLS 更新，请检查网络"
                    },
                )
            } finally {
                _isRefreshingAll.value = false
                batchRefreshInFlight.set(false)
            }
        }
    }

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

    fun openAddDomainSheet() {
        resetDraftInspectionJobs()
        _assetSaveError.value = null
        _rdapLookupError.value = null
        _rdapSyncSummary.value = null
        _tlsCheckResultMessage.value = null
        _assetEditorDraft.value = AssetEditorDraft(
            assetType = FleetAssetType.DOMAIN_CERT,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = AssetEditorDraft.defaultExpiryDateIso(todayProvider()),
        )
    }

    fun openEditDomainSheet(asset: VpsAssetRecord) {
        resetDraftInspectionJobs()
        _assetSaveError.value = null
        _rdapLookupError.value = null
        _rdapSyncSummary.value = null
        _tlsCheckResultMessage.value = null
        _assetEditorDraft.value = AssetEditorDraft.fromRecord(asset).copy(
            assetType = FleetAssetType.DOMAIN_CERT,
        )
    }

    fun clearAssetSaveError() {
        _assetSaveError.value = null
    }

    fun closeAssetEditor() {
        resetDraftInspectionJobs()
        _assetSaveError.value = null
        _rdapLookupError.value = null
        _rdapSyncSummary.value = null
        _tlsCheckResultMessage.value = null
        _assetEditorDraft.value = null
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
        _rdapSyncSummary.value = null
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
                    val shouldUpdateNickname = current.nickname.isBlank() ||
                        (isDifferentDomain && current.nickname.trim().equals(current.domainName.trim(), ignoreCase = true))
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
                        nickname = if (shouldUpdateNickname) rdap.domainName else current.nickname,
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
                    val summary = rdap.toSyncSummary()
                    _rdapSyncSummary.value = summary
                    _snackbarChannel.trySend(summary)
                }.onFailure { err ->
                    _rdapSyncSummary.value = null
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
        assetRefreshJobs.remove(assetId)?.cancel()
        assetRefreshJobs[assetId] = viewModelScope.launch {
            refreshSingleAssetSuspend(assetId, emitSnackbar = true)
        }
    }

    private suspend fun refreshSingleAssetSuspend(
        assetId: String,
        emitSnackbar: Boolean,
    ): Boolean {
        val asset = fleetRepository.assetsFlow.value.firstOrNull { it.id == assetId }
            ?: uiState.value.domainAssets.firstOrNull { it.id == assetId }
            ?: return false
        val domain = asset.domainName?.let { VpsAssetRecord.normalizeDomainName(it) }
        val tlsTarget = asset.effectiveTlsHost?.let { VpsAssetRecord.normalizeDomainName(it) }
        if (domain == null && tlsTarget == null) {
            if (emitSnackbar) _snackbarChannel.trySend("该域名资产未设置域名或 HTTPS 主机")
            return false
        }
        val seqCounter = assetRefreshSeqs.computeIfAbsent(assetId) { AtomicLong(0L) }
        val seq = seqCounter.incrementAndGet()
        _refreshingDomainAssetIds.value = _refreshingDomainAssetIds.value + assetId
        return try {
            var rdapResult: DomainRdapResult? = null
            var tlsResult: TlsCertificateInspectionResult? = null
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
                return false
            }
            val latestAsset = fleetRepository.assetsFlow.value.firstOrNull { it.id == assetId }
                ?: return false
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
            if (emitSnackbar) {
                val msg = when {
                    rdapOk && tlsOk -> "已刷新「${updated.nickname}」RDAP 与 TLS 证书状态"
                    rdapOk -> "已刷新「${updated.nickname}」RDAP 信息"
                    tlsOk -> "已更新「${updated.nickname}」TLS 证书剩余天数"
                    else -> "未能拉取「${updated.nickname}」的 RDAP/TLS 更新"
                }
                _snackbarChannel.trySend(msg)
            }
            rdapOk || tlsOk
        } finally {
            if (seqCounter.get() == seq) {
                _refreshingDomainAssetIds.value = _refreshingDomainAssetIds.value - assetId
            }
        }
    }

    fun saveDomainAsset(asset: VpsAssetRecord) {
        val domainOnlyAsset = asset.copy(
            assetType = FleetAssetType.DOMAIN_CERT,
            linkedProbeKey = null,
        )
        val previousDraft = _assetEditorDraft.value
        val preservedEditingId = when {
            previousDraft != null -> previousDraft.editingId
            fleetRepository.assetsFlow.value.any { it.id == domainOnlyAsset.id } -> domainOnlyAsset.id
            else -> null
        }
        val draftFromSubmission = AssetEditorDraft.fromRecord(domainOnlyAsset).copy(
            editingId = preservedEditingId,
            customCycleDays = (
                domainOnlyAsset.customCycleDays
                    ?: previousDraft?.customCycleDays?.toIntOrNull()
                    ?: 365
                ).toString(),
        )
        _assetEditorDraft.value = draftFromSubmission
        _assetSaveError.value = null

        viewModelScope.launch {
            try {
                val saved = fleetRepository.upsertAsset(domainOnlyAsset)
                _assetSaveError.value = null
                _rdapSyncSummary.value = null
                _assetEditorDraft.value = null
                syncBackgroundSchedule()
                _snackbarChannel.trySend("已保存域名资产「${saved.nickname}」")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val errorMessage = "保存域名资产失败：${formatPersistenceError(t)}"
                _assetSaveError.value = errorMessage
                _assetEditorDraft.value = draftFromSubmission
                _snackbarChannel.trySend(errorMessage)
            }
        }
    }

    fun markDomainRenewed(assetId: String) {
        viewModelScope.launch {
            try {
                val result = fleetRepository.markAssetRenewed(
                    assetId = assetId,
                    today = todayProvider(),
                )
                if (result == null) {
                    _snackbarChannel.trySend("未找到对应域名资产")
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

    fun deleteDomainAsset(assetId: String) {
        assetRefreshSeqs.computeIfAbsent(assetId) { AtomicLong(0L) }.incrementAndGet()
        assetRefreshJobs.remove(assetId)?.cancel()
        _refreshingDomainAssetIds.value = _refreshingDomainAssetIds.value - assetId
        viewModelScope.launch {
            try {
                fleetRepository.deleteAsset(assetId)
                syncBackgroundSchedule()
                _snackbarChannel.trySend("已删除域名资产")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snackbarChannel.trySend("删除域名资产失败：${formatPersistenceError(t)}")
            }
        }
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
            "本地数据写入失败，请检查存储空间或目录权限"
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        const val TLS_WARNING_DAYS_THRESHOLD = 14L

        fun factory(
            fleetRepository: FleetRepository,
            exchangeRateRepository: ExchangeRateRepository,
            workScheduler: FleetWorkScheduler? = null,
            domainRdapClient: DomainRdapClient? = null,
            tlsCertificateInspector: TlsCertificateInspector? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DomainAssetsViewModel(
                    fleetRepository = fleetRepository,
                    exchangeRateRepository = exchangeRateRepository,
                    workScheduler = workScheduler,
                    computationDispatcher = Dispatchers.Default,
                    domainRdapClient = domainRdapClient,
                    tlsCertificateInspector = tlsCertificateInspector,
                ) as T
            }
        }

        fun factory(context: Context): ViewModelProvider.Factory = factory(
            fleetRepository = FleetRepositoryImpl.forContext(context),
            exchangeRateRepository = ExchangeRateRepository.forContext(context),
            workScheduler = FleetWorkScheduler(context),
        )
    }
}
