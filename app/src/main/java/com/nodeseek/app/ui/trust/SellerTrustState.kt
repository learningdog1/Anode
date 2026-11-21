package com.nodeseek.app.ui.trust

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.NoOpOfficialTradeRepository
import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.trust.OfficialTradeRepository
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustFetchStatus
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * High-level inspection state surfaced by [SellerTrustCardSheet]:
 * - [LOADING]: Initial remote verification in flight
 * - [FRESH]: Freshly fetched from NodeSeek
 * - [FROM_CACHE]: Served from memory LRU or persistent disk cache (displays update timestamp)
 * - [RATE_LIMITED]: Upstream 429/503 rate-limit cooldown with no usable cached snapshot
 * - [FAILED_RETRYABLE]: Remote failure or empty/unresolvable seller with manual retry available
 */
enum class SellerTrustLoadStatus {
    LOADING,
    FRESH,
    FROM_CACHE,
    RATE_LIMITED,
    FAILED_RETRYABLE,
}

/**
 * UI status of the NodeSeek Official Trade Platform (`agent.nodeseek.org`) section inside
 * [SellerTrustCardSheet].
 */
enum class OfficialTradeLoadStatus {
    IDLE,
    LOADING,
    FOUND,
    NOT_CONFIGURED,
    NOT_FOUND,
    UNAUTHORIZED,
    RATE_LIMITED,
    FAILED,
}

/**
 * Lightweight in-process invalidation counter notifying inline [SellerTrustQuickBadge] instances
 * when cached official trade stats or API key state change.
 */
internal object OfficialTradeUiSignalBus {
    private val _epoch = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = _epoch.asStateFlow()

    fun notifyMutated() {
        _epoch.update { it + 1L }
    }
}

private val TRUST_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val TRUST_MONTH_DAY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)
private val TRUST_FULL_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

/**
 * Formats [epochMillis] into a concise Chinese timestamp string showing both recency and wall-clock time
 * (e.g. `"刚刚 (12:30)"`, `"15 分钟前 (12:15)"`, `"今日 09:20"`, `"昨日 21:40"`, `"11-18 14:05"`).
 */
internal fun formatTrustUpdatedAt(
    epochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
): String {
    if (epochMillis <= 0L) return "时间未知"
    val targetZdt = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    val nowZdt = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
    val targetDate = targetZdt.toLocalDate()
    val nowDate = nowZdt.toLocalDate()
    val diffSeconds = ((nowEpochMillis - epochMillis) / 1_000L).coerceAtLeast(0L)
    val timePart = TRUST_TIME_FORMATTER.format(targetZdt)

    return when {
        diffSeconds < 60L && targetDate == nowDate -> "刚刚 ($timePart)"
        diffSeconds < 3_600L && targetDate == nowDate -> "${diffSeconds / 60L} 分钟前 ($timePart)"
        targetDate == nowDate -> {
            val hours = (diffSeconds / 3_600L).coerceAtLeast(1L)
            "今日 $timePart（${hours}小时前）"
        }
        targetDate == nowDate.minusDays(1) -> "昨日 $timePart"
        targetZdt.year == nowZdt.year -> TRUST_MONTH_DAY_TIME_FORMATTER.format(targetZdt)
        else -> TRUST_FULL_DATE_TIME_FORMATTER.format(targetZdt)
    }
}

/**
 * Optional CompositionLocal for providing a shared [SellerTrustRepository] (e.g. from `AppContainer`).
 * When not explicitly provided, [rememberSellerTrustRepository] falls back to `LocalAppContainer.current.sellerTrustRepository`
 * or process-scoped [SellerTrustRepositoryImpl.forContext].
 */
val LocalSellerTrustRepository = staticCompositionLocalOf<SellerTrustRepository?> { null }
val LocalOfficialTradeRepository = staticCompositionLocalOf<OfficialTradeRepository?> { null }

/**
 * Resolves the active [SellerTrustRepository] from an explicit override, [LocalSellerTrustRepository],
 * or [LocalAppContainer].
 */
@Composable
fun rememberSellerTrustRepository(
    override: SellerTrustRepository? = null,
): SellerTrustRepository {
    if (override != null) return override
    LocalSellerTrustRepository.current?.let { return it }
    val context = LocalContext.current
    val container = LocalAppContainer.current
    return remember(context, container) {
        container.sellerTrustRepository
    }
}

/**
 * Resolves the active [OfficialTradeRepository] from an explicit override,
 * [LocalOfficialTradeRepository], or [LocalAppContainer].
 */
@Composable
fun rememberOfficialTradeRepository(
    override: OfficialTradeRepository? = null,
): OfficialTradeRepository {
    if (override != null) return override
    LocalOfficialTradeRepository.current?.let { return it }
    val container = LocalAppContainer.current
    return remember(container) {
        container.officialTradeRepository
    }
}

/**
 * Immutable UI state for [SellerTrustCardSheet].
 */
@Immutable
data class SellerTrustUiState(
    val uid: Long,
    val usernameHint: String,
    val avatarUrlHint: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val report: SellerTrustReport? = null,
    val localNote: LocalSellerNote? = null,
    val draftTag: TrustTag = TrustTag.NONE,
    val draftNoteText: String = "",
    val isSavingNote: Boolean = false,
    val noteSavedFeedback: Boolean = false,
    val errorMessage: String? = null,
    val cooldownUntilEpochMillis: Long = 0L,
    val cooldownRemainingSeconds: Int = 0,
    val statusNoticeMessage: String? = null,
    val isManualRefreshThrottled: Boolean = false,
    val isDeepSearching: Boolean = false,
    val deepSearchCooldownSeconds: Int = 0,
    val deepSearchNotice: String? = null,
    val isScanningMoreTopics: Boolean = false,
    val topicScanCooldownSeconds: Int = 0,
    val topicScanNotice: String? = null,
    val isOfficialTradeLoading: Boolean = false,
    val isOfficialTradeRefreshing: Boolean = false,
    val officialTradeLookup: OfficialTradeLookup? = null,
    val officialTradeQueriedUsername: String = "",
    val officialTradeCooldownUntilMillis: Long = 0L,
    val officialTradeCooldownSeconds: Int = 0,
) {
    val resolvedUid: Long
        get() = report?.uid?.takeIf { it > 0L } ?: uid

    val resolvedUsername: String
        get() = report?.username?.takeIf { it.isNotBlank() }
            ?: usernameHint.trim().ifEmpty { if (uid > 0L) "用户 #$uid" else "未知卖家" }

    val effectiveOfficialUsername: String
        get() = report?.username?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
            ?: usernameHint.trim().removePrefix("@")

    val officialTradeFound: OfficialTradeLookup.Found?
        get() = (officialTradeLookup as? OfficialTradeLookup.Found)?.takeIf { it.matches.isNotEmpty() }

    val officialTradeStatus: OfficialTradeLoadStatus
        get() {
            if (isOfficialTradeLoading && officialTradeFound == null) {
                return OfficialTradeLoadStatus.LOADING
            }
            return when (val lookup = officialTradeLookup) {
                null -> {
                    if (effectiveOfficialUsername.isNotBlank()) {
                        OfficialTradeLoadStatus.LOADING
                    } else {
                        OfficialTradeLoadStatus.IDLE
                    }
                }
                OfficialTradeLookup.NotConfigured -> OfficialTradeLoadStatus.NOT_CONFIGURED
                OfficialTradeLookup.Unauthorized -> OfficialTradeLoadStatus.UNAUTHORIZED
                is OfficialTradeLookup.RateLimited -> OfficialTradeLoadStatus.RATE_LIMITED
                is OfficialTradeLookup.Failed -> OfficialTradeLoadStatus.FAILED
                OfficialTradeLookup.NotFound -> OfficialTradeLoadStatus.NOT_FOUND
                is OfficialTradeLookup.Found -> {
                    if (lookup.matches.isEmpty()) {
                        OfficialTradeLoadStatus.NOT_FOUND
                    } else {
                        OfficialTradeLoadStatus.FOUND
                    }
                }
            }
        }

    val resolvedAvatarUrl: String
        get() = report?.avatarUrl?.takeIf { it.isNotBlank() }
            ?: avatarUrlHint.trim().ifEmpty {
                val id = resolvedUid
                if (id > 0L) "https://www.nodeseek.com/avatar/$id.png" else ""
            }

    val hasUsableReport: Boolean
        get() = report != null && report.anyRemoteSucceeded

    val loadStatus: SellerTrustLoadStatus
        get() = when {
            isLoading && !hasUsableReport -> SellerTrustLoadStatus.LOADING
            hasUsableReport -> {
                if (report!!.isFromCache) {
                    SellerTrustLoadStatus.FROM_CACHE
                } else {
                    SellerTrustLoadStatus.FRESH
                }
            }
            report?.fetchStatus == TrustFetchStatus.RATE_LIMITED ||
                (!isManualRefreshThrottled && cooldownRemainingSeconds > 0 && report == null) -> {
                SellerTrustLoadStatus.RATE_LIMITED
            }
            else -> SellerTrustLoadStatus.FAILED_RETRYABLE
        }

    val canManualRefresh: Boolean
        get() = !isLoading && !isRefreshing && cooldownRemainingSeconds <= 0

    val hasUnsavedNoteChanges: Boolean
        get() {
            val savedTag = localNote?.tag ?: TrustTag.NONE
            val savedText = localNote?.noteText.orEmpty().trim()
            return draftTag != savedTag || draftNoteText.trim() != savedText
        }
}

/**
 * State holder managing inspection loading, throttled manual refresh, cooldown countdown,
 * reactive local note observation, 1-tap tag persistence, and debounced/manual local note saving.
 */
@Stable
class SellerTrustStateHolder(
    private val repository: SellerTrustRepository,
    private val scope: CoroutineScope,
    uid: Long,
    usernameHint: String,
    avatarUrlHint: String = "",
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uiRefreshMinIntervalMillis: Long = DEFAULT_UI_REFRESH_MIN_INTERVAL_MILLIS,
    private val officialTradeRepository: OfficialTradeRepository = NoOpOfficialTradeRepository,
) {
    private val _uiState = MutableStateFlow(
        SellerTrustUiState(
            uid = uid,
            usernameHint = usernameHint,
            avatarUrlHint = avatarUrlHint,
        ),
    )
    val uiState: StateFlow<SellerTrustUiState> = _uiState.asStateFlow()

    private var inspectJob: Job? = null
    private var officialTradeJob: Job? = null
    private var deepSearchJob: Job? = null
    private var scanMoreTopicsJob: Job? = null
    private var observeNoteJob: Job? = null
    private var observeOfficialConfigJob: Job? = null
    private var autoSaveJob: Job? = null
    private var feedbackJob: Job? = null
    private var cooldownTickerJob: Job? = null
    private var officialTradeCooldownTickerJob: Job? = null
    private var deepSearchTickerJob: Job? = null
    private var topicScanTickerJob: Job? = null
    private var lastManualRefreshAcceptedAtMillis: Long = 0L
    private var noteEditSequence: Long = 0L
    private var tagEditSequence: Long = 0L
    private var saveSequence: Long = 0L
    private var userEditedNoteText: Boolean = false
    private var userEditedTag: Boolean = false
    private var hasLoadedOnce: Boolean = false

    init {
        if (uid > 0L) {
            startObservingNote(uid)
        }
        startObservingOfficialTradeConfig()
    }

    fun load(forceRefresh: Boolean = false) {
        hasLoadedOnce = true
        val snapshot = _uiState.value
        val now = clockMillis()

        val initialOfficialUsername = snapshot.effectiveOfficialUsername
        if (initialOfficialUsername.isNotBlank()) {
            loadOfficialTrade(
                usernameOverride = initialOfficialUsername,
                forceRefresh = forceRefresh,
            )
        }

        if (forceRefresh) {
            if (snapshot.isLoading || snapshot.isRefreshing) return
            if (snapshot.cooldownUntilEpochMillis > now) {
                val remSec = computeRemainingSeconds(snapshot.cooldownUntilEpochMillis, now)
                _uiState.update {
                    it.copy(
                        cooldownRemainingSeconds = remSec,
                        statusNoticeMessage = if (it.isManualRefreshThrottled) {
                            "刷新过于频繁，请 ${remSec} 秒后再试"
                        } else {
                            "当前处于限流冷却期，请 ${remSec} 秒后再试"
                        },
                    )
                }
                startCooldownTicker(snapshot.cooldownUntilEpochMillis)
                return
            }
            if (lastManualRefreshAcceptedAtMillis > 0L &&
                now >= lastManualRefreshAcceptedAtMillis &&
                (now - lastManualRefreshAcceptedAtMillis) < uiRefreshMinIntervalMillis
            ) {
                val throttleUntil = lastManualRefreshAcceptedAtMillis + uiRefreshMinIntervalMillis
                val remSec = computeRemainingSeconds(throttleUntil, now)
                _uiState.update {
                    it.copy(
                        cooldownUntilEpochMillis = throttleUntil,
                        cooldownRemainingSeconds = remSec,
                        isManualRefreshThrottled = true,
                        statusNoticeMessage = "手动刷新受节流保护，请 ${remSec} 秒后再试",
                    )
                }
                startCooldownTicker(throttleUntil)
                return
            }
            lastManualRefreshAcceptedAtMillis = now
        }

        inspectJob?.cancel()
        inspectJob = scope.launch {
            _uiState.update { current ->
                current.copy(
                    isLoading = !current.hasUsableReport,
                    isRefreshing = forceRefresh || current.hasUsableReport,
                    errorMessage = null,
                    statusNoticeMessage = null,
                    isManualRefreshThrottled = false,
                )
            }
            try {
                val report = repository.inspectSeller(
                    uid = snapshot.uid,
                    usernameHint = snapshot.usernameHint,
                    forceRefresh = forceRefresh,
                )
                if (report.uid > 0L && report.uid != snapshot.uid) {
                    startObservingNote(report.uid)
                }

                val finishedNow = clockMillis()
                val untilMillis = report.cooldownUntilEpochMillis.takeIf { it > finishedNow } ?: 0L
                val remainingSec = computeRemainingSeconds(untilMillis, finishedNow)

                _uiState.update { current ->
                    val note = current.localNote ?: report.localNote
                    val syncedReport = if (note != report.localNote) {
                        report.copy(
                            localNote = note,
                            riskAssessment = TrustRiskEvaluator.evaluate(
                                rankLevel = report.rankLevel,
                                registrationDays = report.registrationDays,
                                historicalTradeTopicCount = report.historicalTradeTopicCount,
                                exposureSearchHits = report.exposureSearchHits,
                                searchSucceeded = report.searchSucceeded,
                                localNote = note,
                                profileSucceeded = report.profileSucceeded,
                                registrationDurationLabel = report.registrationDurationLabel,
                                exposureCheckScope = report.exposureCheckScope,
                                scannedTopicsCount = report.scannedTopicsCount,
                                hasMoreTopicPages = report.hasMoreTopicPages,
                            ),
                        )
                    } else {
                        report
                    }

                    val errorText = if (!syncedReport.anyRemoteSucceeded) {
                        syncedReport.failureMessage
                            ?: syncedReport.searchFailureReason
                            ?: "信誉核验加载失败，请手动重试"
                    } else {
                        null
                    }

                    val noticeText = when {
                        syncedReport.isRefreshThrottled -> syncedReport.failureMessage
                        syncedReport.fetchStatus == TrustFetchStatus.STALE_CACHE_FALLBACK ->
                            syncedReport.failureMessage
                        else -> null
                    }

                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        report = syncedReport,
                        localNote = note,
                        draftTag = if (userEditedTag) current.draftTag else (note?.tag ?: TrustTag.NONE),
                        draftNoteText = if (userEditedNoteText) current.draftNoteText else note?.noteText.orEmpty(),
                        errorMessage = errorText,
                        cooldownUntilEpochMillis = untilMillis,
                        cooldownRemainingSeconds = remainingSec,
                        statusNoticeMessage = noticeText,
                        isManualRefreshThrottled = syncedReport.isRefreshThrottled,
                    )
                }

                if (untilMillis > finishedNow) {
                    startCooldownTicker(untilMillis)
                } else {
                    cooldownTickerJob?.cancel()
                }

                val resolvedReportUsername = report.username.trim().removePrefix("@")
                if (resolvedReportUsername.isNotBlank() &&
                    !resolvedReportUsername.equals(_uiState.value.officialTradeQueriedUsername, ignoreCase = true)
                ) {
                    loadOfficialTrade(
                        usernameOverride = resolvedReportUsername,
                        forceRefresh = forceRefresh,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "信誉核验加载失败，请手动重试",
                    )
                }
            }
        }
    }

    fun refresh() {
        load(forceRefresh = true)
    }

    /**
     * Explicitly refreshes the NodeSeek Official Trade Platform stats (`forceRefresh = true`).
     */
    fun refreshOfficialTrade() {
        loadOfficialTrade(forceRefresh = true)
    }

    internal fun loadOfficialTrade(
        usernameOverride: String? = null,
        forceRefresh: Boolean = false,
    ) {
        val snapshot = _uiState.value
        val cleanUsername = (usernameOverride ?: snapshot.effectiveOfficialUsername)
            .trim()
            .removePrefix("@")
        if (cleanUsername.isEmpty()) return

        val now = clockMillis()
        if (forceRefresh && snapshot.officialTradeCooldownUntilMillis > now) {
            val remSec = computeRemainingSeconds(snapshot.officialTradeCooldownUntilMillis, now)
            _uiState.update { it.copy(officialTradeCooldownSeconds = remSec) }
            startOfficialTradeCooldownTicker(snapshot.officialTradeCooldownUntilMillis)
            return
        }
        if (forceRefresh && (snapshot.isOfficialTradeLoading || snapshot.isOfficialTradeRefreshing)) {
            return
        }

        officialTradeJob?.cancel()
        officialTradeJob = scope.launch {
            _uiState.update { current ->
                current.copy(
                    isOfficialTradeLoading = current.officialTradeFound == null,
                    isOfficialTradeRefreshing = forceRefresh || current.officialTradeFound != null,
                    officialTradeQueriedUsername = cleanUsername,
                )
            }
            try {
                val result = officialTradeRepository.lookup(
                    username = cleanUsername,
                    forceRefresh = forceRefresh,
                )
                val finishedNow = clockMillis()
                val rateLimitedUntil = if (result is OfficialTradeLookup.RateLimited) {
                    val retrySec = (result.retryAfterSeconds ?: DEFAULT_OFFICIAL_RETRY_SECONDS)
                        .coerceAtLeast(1L)
                    finishedNow + retrySec * 1_000L
                } else {
                    0L
                }
                val remSec = computeRemainingSeconds(rateLimitedUntil, finishedNow)

                _uiState.update { current ->
                    current.copy(
                        isOfficialTradeLoading = false,
                        isOfficialTradeRefreshing = false,
                        officialTradeLookup = result,
                        officialTradeQueriedUsername = cleanUsername,
                        officialTradeCooldownUntilMillis = rateLimitedUntil,
                        officialTradeCooldownSeconds = remSec,
                    )
                }

                if (result is OfficialTradeLookup.Found) {
                    OfficialTradeUiSignalBus.notifyMutated()
                }

                if (rateLimitedUntil > finishedNow) {
                    startOfficialTradeCooldownTicker(rateLimitedUntil)
                } else {
                    officialTradeCooldownTickerJob?.cancel()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isOfficialTradeLoading = false,
                        isOfficialTradeRefreshing = false,
                        officialTradeLookup = OfficialTradeLookup.Failed(
                            message = t.message?.takeIf { it.isNotBlank() }
                                ?: "官方交易平台查询失败，请手动重试",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Triggers an explicit on-demand single-query `/search` deep scan for historical exposure posts,
     * adhering to the isolated deep-search cooldown & interval guard.
     */
    fun runDeepExposureSearch() {
        val snapshot = _uiState.value
        if (snapshot.isDeepSearching || snapshot.isLoading || snapshot.deepSearchCooldownSeconds > 0) return

        deepSearchJob?.cancel()
        deepSearchJob = scope.launch {
            _uiState.update { it.copy(isDeepSearching = true, deepSearchNotice = null) }
            try {
                val updated = repository.searchSellerExposureDeep(
                    uid = snapshot.resolvedUid,
                    usernameHint = snapshot.resolvedUsername.ifBlank { snapshot.usernameHint },
                )
                val finishedNow = clockMillis()
                val deepUntil = updated.deepSearchCooldownUntilEpochMillis.takeIf { it > finishedNow } ?: 0L
                val deepRemSec = computeRemainingSeconds(deepUntil, finishedNow)

                _uiState.update { current ->
                    val note = current.localNote ?: updated.localNote
                    val synced = if (note != updated.localNote) {
                        updated.copy(
                            localNote = note,
                            riskAssessment = TrustRiskEvaluator.evaluate(
                                rankLevel = updated.rankLevel,
                                registrationDays = updated.registrationDays,
                                historicalTradeTopicCount = updated.historicalTradeTopicCount,
                                exposureSearchHits = updated.exposureSearchHits,
                                searchSucceeded = updated.searchSucceeded,
                                localNote = note,
                                profileSucceeded = updated.profileSucceeded,
                                registrationDurationLabel = updated.registrationDurationLabel,
                                exposureCheckScope = updated.exposureCheckScope,
                                scannedTopicsCount = updated.scannedTopicsCount,
                                hasMoreTopicPages = updated.hasMoreTopicPages,
                            ),
                        )
                    } else {
                        updated
                    }
                    current.copy(
                        isDeepSearching = false,
                        report = synced,
                        deepSearchCooldownSeconds = deepRemSec,
                        deepSearchNotice = synced.deepSearchNoticeMessage,
                    )
                }

                if (deepUntil > finishedNow) {
                    startDeepSearchCooldownTicker(deepUntil)
                } else {
                    deepSearchTickerJob?.cancel()
                }
            } catch (c: CancellationException) {
                throw c
                } catch (t: Throwable) {
                    _uiState.update { current ->
                        current.copy(
                            isDeepSearching = false,
                            deepSearchNotice = t.message?.takeIf { it.isNotBlank() } ?: "历史曝光深搜失败，请稍后重试",
                        )
                    }
                }
            }
        }

    /**
     * Scans the next paced batch of older user discussion pages (`list-discussions`) to uncover
     * historical trade posts beyond the initial automatic scan window.
     */
    fun scanMoreTradeTopics() {
        val snapshot = _uiState.value
        val curReport = snapshot.report ?: return
        if (!curReport.hasMoreTopicPages || snapshot.isScanningMoreTopics || snapshot.isLoading || snapshot.topicScanCooldownSeconds > 0) {
            return
        }

        scanMoreTopicsJob?.cancel()
        scanMoreTopicsJob = scope.launch {
            _uiState.update { it.copy(isScanningMoreTopics = true, topicScanNotice = null) }
            try {
                val updated = repository.scanMoreSellerTradeTopics(
                    uid = snapshot.resolvedUid,
                    usernameHint = snapshot.resolvedUsername.ifBlank { snapshot.usernameHint },
                )
                val finishedNow = clockMillis()
                val scanUntil = updated.topicScanCooldownUntilEpochMillis.takeIf { it > finishedNow } ?: 0L
                val scanRemSec = computeRemainingSeconds(scanUntil, finishedNow)

                _uiState.update { current ->
                    val note = current.localNote ?: updated.localNote
                    val synced = if (note != updated.localNote) {
                        updated.copy(
                            localNote = note,
                            riskAssessment = TrustRiskEvaluator.evaluate(
                                rankLevel = updated.rankLevel,
                                registrationDays = updated.registrationDays,
                                historicalTradeTopicCount = updated.historicalTradeTopicCount,
                                exposureSearchHits = updated.exposureSearchHits,
                                searchSucceeded = updated.searchSucceeded,
                                localNote = note,
                                profileSucceeded = updated.profileSucceeded,
                                registrationDurationLabel = updated.registrationDurationLabel,
                                exposureCheckScope = updated.exposureCheckScope,
                                scannedTopicsCount = updated.scannedTopicsCount,
                                hasMoreTopicPages = updated.hasMoreTopicPages,
                            ),
                        )
                    } else {
                        updated
                    }
                    current.copy(
                        isScanningMoreTopics = false,
                        report = synced,
                        topicScanCooldownSeconds = scanRemSec,
                        topicScanNotice = synced.topicScanNoticeMessage,
                    )
                }

                if (scanUntil > finishedNow) {
                    startTopicScanCooldownTicker(scanUntil)
                } else {
                    topicScanTickerJob?.cancel()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isScanningMoreTopics = false,
                        topicScanNotice = t.message?.takeIf { it.isNotBlank() } ?: "加载更早主题失败，请稍后重试",
                    )
                }
            }
        }
    }

    /**
     * Toggles [tag]: tapping an unselected tag activates it; tapping the already-selected tag resets to [TrustTag.NONE].
     * Immediately persists to local storage and re-evaluates the displayed risk banner.
     */
    fun toggleTag(tag: TrustTag) {
        val current = _uiState.value
        val nextTag = if (current.draftTag == tag) TrustTag.NONE else tag
        userEditedTag = true
        tagEditSequence++
        _uiState.update { it.copy(draftTag = nextTag) }
        autoSaveJob?.cancel()
        persistNote(tag = nextTag, noteText = _uiState.value.draftNoteText)
    }

    /**
     * Updates the note text field and triggers debounced auto-save.
     */
    fun updateDraftNoteText(text: String) {
        userEditedNoteText = true
        noteEditSequence++
        val clamped = if (text.length > MAX_NOTE_LENGTH) text.take(MAX_NOTE_LENGTH) else text
        _uiState.update {
            it.copy(
                draftNoteText = clamped,
                noteSavedFeedback = false,
            )
        }
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            delay(AUTO_SAVE_DEBOUNCE_MILLIS)
            val latest = _uiState.value
            persistNote(tag = latest.draftTag, noteText = latest.draftNoteText)
        }
    }

    /**
     * Persists the current draft tag & note text immediately.
     */
    fun saveNoteNow() {
        autoSaveJob?.cancel()
        val latest = _uiState.value
        persistNote(tag = latest.draftTag, noteText = latest.draftNoteText)
    }

    private fun startCooldownTicker(untilMillis: Long) {
        cooldownTickerJob?.cancel()
        cooldownTickerJob = scope.launch {
            while (true) {
                val now = clockMillis()
                val remaining = computeRemainingSeconds(untilMillis, now)
                if (remaining <= 0) {
                    _uiState.update { current ->
                        current.copy(
                            cooldownUntilEpochMillis = 0L,
                            cooldownRemainingSeconds = 0,
                            isManualRefreshThrottled = false,
                            statusNoticeMessage = if (current.isManualRefreshThrottled) null else current.statusNoticeMessage,
                        )
                    }
                    break
                }
                _uiState.update { current ->
                    current.copy(cooldownRemainingSeconds = remaining)
                }
                delay(1_000L)
            }
        }
    }

    private fun startDeepSearchCooldownTicker(untilMillis: Long) {
        deepSearchTickerJob?.cancel()
        deepSearchTickerJob = scope.launch {
            while (true) {
                val now = clockMillis()
                val remaining = computeRemainingSeconds(untilMillis, now)
                if (remaining <= 0) {
                    _uiState.update { current ->
                        current.copy(
                            deepSearchCooldownSeconds = 0,
                            deepSearchNotice = null,
                        )
                    }
                    break
                }
                _uiState.update { current ->
                    current.copy(deepSearchCooldownSeconds = remaining)
                }
                delay(1_000L)
            }
        }
    }

    private fun startTopicScanCooldownTicker(untilMillis: Long) {
        topicScanTickerJob?.cancel()
        topicScanTickerJob = scope.launch {
            while (true) {
                val now = clockMillis()
                val remaining = computeRemainingSeconds(untilMillis, now)
                if (remaining <= 0) {
                    _uiState.update { current ->
                        current.copy(
                            topicScanCooldownSeconds = 0,
                            topicScanNotice = null,
                        )
                    }
                    break
                }
                _uiState.update { current ->
                    current.copy(topicScanCooldownSeconds = remaining)
                }
                delay(1_000L)
            }
        }
    }

    private fun computeRemainingSeconds(untilMillis: Long, nowMillis: Long): Int {
        if (untilMillis <= nowMillis) return 0
        return ((untilMillis - nowMillis + 999L) / 1_000L).coerceAtLeast(1L).toInt()
    }

    private fun startObservingNote(targetUid: Long) {
        if (targetUid <= 0L) return
        observeNoteJob?.cancel()
        observeNoteJob = scope.launch {
            repository.observeLocalNote(targetUid).collect { observedNote ->
                _uiState.update { current ->
                    val updatedReport = current.report?.let { existing ->
                        if (existing.localNote == observedNote) {
                            existing
                        } else {
                            existing.copy(
                                localNote = observedNote,
                                riskAssessment = TrustRiskEvaluator.evaluate(
                                    rankLevel = existing.rankLevel,
                                    registrationDays = existing.registrationDays,
                                    historicalTradeTopicCount = existing.historicalTradeTopicCount,
                                    exposureSearchHits = existing.exposureSearchHits,
                                    searchSucceeded = existing.searchSucceeded,
                                    localNote = observedNote,
                                    profileSucceeded = existing.profileSucceeded,
                                    registrationDurationLabel = existing.registrationDurationLabel,
                                    exposureCheckScope = existing.exposureCheckScope,
                                    scannedTopicsCount = existing.scannedTopicsCount,
                                    hasMoreTopicPages = existing.hasMoreTopicPages,
                                ),
                            )
                        }
                    }
                    current.copy(
                        localNote = observedNote,
                        report = updatedReport,
                        draftTag = if (userEditedTag) {
                            current.draftTag
                        } else {
                            observedNote?.tag ?: TrustTag.NONE
                        },
                        draftNoteText = if (userEditedNoteText) {
                            current.draftNoteText
                        } else {
                            observedNote?.noteText.orEmpty()
                        },
                    )
                }
            }
        }
    }

    private fun persistNote(tag: TrustTag, noteText: String) {
        val current = _uiState.value
        val targetUid = current.resolvedUid
        if (targetUid <= 0L) return
        val targetUsername = current.resolvedUsername
        val capturedNoteSeq = noteEditSequence
        val capturedTagSeq = tagEditSequence
        val currentSaveSeq = ++saveSequence

        scope.launch {
            _uiState.update { it.copy(isSavingNote = true) }
            try {
                repository.saveLocalNote(
                    uid = targetUid,
                    username = targetUsername,
                    tag = tag,
                    noteText = noteText,
                )
                if (noteEditSequence == capturedNoteSeq) {
                    userEditedNoteText = false
                }
                if (tagEditSequence == capturedTagSeq) {
                    userEditedTag = false
                }
                if (saveSequence == currentSaveSeq) {
                    _uiState.update { it.copy(isSavingNote = false, noteSavedFeedback = true) }
                    feedbackJob?.cancel()
                    feedbackJob = scope.launch {
                        delay(SAVED_FEEDBACK_DURATION_MILLIS)
                        _uiState.update { it.copy(noteSavedFeedback = false) }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                if (saveSequence == currentSaveSeq) {
                    _uiState.update { it.copy(isSavingNote = false) }
                }
            }
        }
    }

    private fun startObservingOfficialTradeConfig() {
        observeOfficialConfigJob?.cancel()
        observeOfficialConfigJob = scope.launch {
            var previousConfigured: Boolean? = null
            officialTradeRepository.isConfigured.collect { configured ->
                val wasConfigured = previousConfigured
                previousConfigured = configured
                if (wasConfigured != null && wasConfigured != configured && hasLoadedOnce) {
                    if (!configured) {
                        officialTradeJob?.cancel()
                        officialTradeCooldownTickerJob?.cancel()
                        _uiState.update { current ->
                            current.copy(
                                isOfficialTradeLoading = false,
                                isOfficialTradeRefreshing = false,
                                officialTradeLookup = OfficialTradeLookup.NotConfigured,
                                officialTradeCooldownUntilMillis = 0L,
                                officialTradeCooldownSeconds = 0,
                            )
                        }
                    } else {
                        loadOfficialTrade(forceRefresh = false)
                    }
                }
            }
        }
    }

    private fun startOfficialTradeCooldownTicker(untilMillis: Long) {
        officialTradeCooldownTickerJob?.cancel()
        officialTradeCooldownTickerJob = scope.launch {
            while (true) {
                val now = clockMillis()
                val remaining = computeRemainingSeconds(untilMillis, now)
                if (remaining <= 0) {
                    _uiState.update { current ->
                        current.copy(
                            officialTradeCooldownUntilMillis = 0L,
                            officialTradeCooldownSeconds = 0,
                        )
                    }
                    break
                }
                _uiState.update { current ->
                    current.copy(officialTradeCooldownSeconds = remaining)
                }
                delay(1_000L)
            }
        }
    }

    companion object {
        const val MAX_NOTE_LENGTH: Int = 240
        const val DEFAULT_UI_REFRESH_MIN_INTERVAL_MILLIS: Long = 3_000L
        private const val DEFAULT_OFFICIAL_RETRY_SECONDS: Long = 60L
        private const val AUTO_SAVE_DEBOUNCE_MILLIS: Long = 700L
        private const val SAVED_FEEDBACK_DURATION_MILLIS: Long = 2200L
    }
}

/**
 * Remembers a [SellerTrustStateHolder] scoped to `(uid, usernameHint, repository, officialTradeRepository)` and
 * triggers the initial inspection automatically.
 */
@Composable
fun rememberSellerTrustState(
    uid: Long,
    usernameHint: String,
    avatarUrlHint: String = "",
    repository: SellerTrustRepository? = null,
    officialTradeRepository: OfficialTradeRepository? = null,
): SellerTrustStateHolder {
    val resolvedRepo = rememberSellerTrustRepository(override = repository)
    val resolvedOfficialRepo = rememberOfficialTradeRepository(override = officialTradeRepository)
    val scope = rememberCoroutineScope()
    val holder = remember(resolvedRepo, resolvedOfficialRepo, uid, usernameHint, avatarUrlHint) {
        SellerTrustStateHolder(
            repository = resolvedRepo,
            scope = scope,
            uid = uid,
            usernameHint = usernameHint,
            avatarUrlHint = avatarUrlHint,
            officialTradeRepository = resolvedOfficialRepo,
        )
    }
    LaunchedEffect(holder) {
        holder.load(forceRefresh = false)
    }
    return holder
}

/**
 * Optional lifecycle-scoped [ViewModel] wrapper around [SellerTrustStateHolder] for callers
 * that prefer `viewModel(...)` retention across configuration changes.
 */
class SellerTrustViewModel(
    repository: SellerTrustRepository,
    uid: Long,
    usernameHint: String,
    avatarUrlHint: String = "",
    officialTradeRepository: OfficialTradeRepository = NoOpOfficialTradeRepository,
) : ViewModel() {

    val stateHolder: SellerTrustStateHolder = SellerTrustStateHolder(
        repository = repository,
        scope = viewModelScope,
        uid = uid,
        usernameHint = usernameHint,
        avatarUrlHint = avatarUrlHint,
        officialTradeRepository = officialTradeRepository,
    )

    val uiState: StateFlow<SellerTrustUiState> = stateHolder.uiState

    init {
        stateHolder.load(forceRefresh = false)
    }

    fun refresh() = stateHolder.refresh()
    fun refreshOfficialTrade() = stateHolder.refreshOfficialTrade()
    fun runDeepExposureSearch() = stateHolder.runDeepExposureSearch()
    fun scanMoreTradeTopics() = stateHolder.scanMoreTradeTopics()
    fun toggleTag(tag: TrustTag) = stateHolder.toggleTag(tag)
    fun updateDraftNoteText(text: String) = stateHolder.updateDraftNoteText(text)
    fun saveNoteNow() = stateHolder.saveNoteNow()

    companion object {
        fun factory(
            repository: SellerTrustRepository,
            uid: Long,
            usernameHint: String,
            avatarUrlHint: String = "",
            officialTradeRepository: OfficialTradeRepository = NoOpOfficialTradeRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SellerTrustViewModel(
                    repository = repository,
                    uid = uid,
                    usernameHint = usernameHint,
                    avatarUrlHint = avatarUrlHint,
                    officialTradeRepository = officialTradeRepository,
                ) as T
            }
        }
    }
}
