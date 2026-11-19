package com.nodeseek.app.data.restock

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nodeseek.app.domain.restock.RestockBackgroundPollOutcome
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.restock.RestockRuntimeStatus
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.restock.StockAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default [RestockMonitorController] coordinating:
 * - Per-item fast foreground polling (1..60 min intervals) while the app is in the foreground,
 * - Serialized WorkManager background poll passes (via [pollMutex]),
 * - On-demand single/all checks and non-persisted live URL probing,
 * - Deduplicated urgent heads-up restock notifications via [RestockNotificationHelper].
 */
class RestockMonitorControllerImpl(
    private val repository: RestockRepository,
    private val analyzer: StockPageAnalyzer,
    private val notificationHelper: RestockNotificationHelper,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val isUnmeteredNetwork: () -> Boolean = { true },
) : RestockMonitorController {

    constructor(
        appContext: Context,
        repository: RestockRepository,
        analyzer: StockPageAnalyzer,
        notificationHelper: RestockNotificationHelper,
        scope: CoroutineScope,
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        repository = repository,
        analyzer = analyzer,
        notificationHelper = notificationHelper,
        scope = scope,
        clockMillis = clockMillis,
        isUnmeteredNetwork = { defaultIsUnmeteredNetwork(appContext) },
    )

    private val _runtimeStatus = MutableStateFlow(RestockRuntimeStatus())
    override val runtimeStatus: StateFlow<RestockRuntimeStatus> = _runtimeStatus.asStateFlow()

    private val lifecycleLock = Any()
    private val pollMutex = Mutex()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private var isStarted: Boolean = false
    private var monitorJob: Job? = null
    private var watchObserverJob: Job? = null
    private var settingsObserverJob: Job? = null

    override fun start() {
        synchronized(lifecycleLock) {
            isStarted = true
            if (watchObserverJob?.isActive != true) {
                watchObserverJob = scope.launch {
                    repository.watchItemsFlow
                        .drop(1)
                        .collect { wakeSignal.trySend(Unit) }
                }
            }
            if (settingsObserverJob?.isActive != true) {
                settingsObserverJob = scope.launch {
                    repository.settingsFlow
                        .drop(1)
                        .collect { wakeSignal.trySend(Unit) }
                }
            }
            if (_runtimeStatus.value.isRunningInForeground) {
                launchForegroundLoopLocked()
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            isStarted = false
            watchObserverJob?.cancel()
            watchObserverJob = null
            settingsObserverJob?.cancel()
            settingsObserverJob = null
            monitorJob?.cancel()
            monitorJob = null
            _runtimeStatus.update {
                it.copy(isCheckingNow = false, checkingItemIds = emptySet())
            }
        }
    }

    override fun onForegroundChanged(inForeground: Boolean) {
        synchronized(lifecycleLock) {
            val previous = _runtimeStatus.value.isRunningInForeground
            _runtimeStatus.update { it.copy(isRunningInForeground = inForeground) }
            if (inForeground) {
                if (isStarted && monitorJob?.isActive != true) {
                    launchForegroundLoopLocked()
                } else if (previous != inForeground) {
                    wakeSignal.trySend(Unit)
                }
            } else if (previous != inForeground) {
                // Wake foreground loop so it exits promptly and hands off background checks to WorkManager.
                wakeSignal.trySend(Unit)
            }
        }
    }

    override fun triggerImmediateCheckAll() {
        scope.launch {
            checkAllNow(onlyEnabled = true)
        }
    }

    override fun triggerCheckSingle(watchId: String) {
        scope.launch {
            checkWatchNow(watchId)
        }
    }

    override suspend fun probeUrl(
        rawUrl: String,
        customInStockKeyword: String,
        customOutOfStockKeyword: String,
    ): StockAnalysisResult = analyzer.analyzeUrl(
        rawUrl = rawUrl,
        customInStockKeyword = customInStockKeyword,
        customOutOfStockKeyword = customOutOfStockKeyword,
    )

    override suspend fun checkWatchNow(watchId: String): StockAnalysisResult? =
        pollMutex.withLock {
            val target = repository.getWatchItemsSnapshot().firstOrNull { it.id == watchId }
                ?: return@withLock null
            val settings = repository.getSettingsSnapshot()
            val results = inspectBatchLocked(
                items = listOf(target),
                urgentNotificationsEnabled = settings.urgentNotificationsEnabled,
            )
            results.firstOrNull()?.analysis
        }

    override suspend fun checkAllNow(onlyEnabled: Boolean): List<StockAnalysisResult> =
        pollMutex.withLock {
            val allItems = repository.getWatchItemsSnapshot()
            val targets = if (onlyEnabled) allItems.filter { it.isEnabled } else allItems
            if (targets.isEmpty()) {
                _runtimeStatus.update {
                    it.copy(lastCheckEpochMillis = clockMillis(), lastErrorSummary = null)
                }
                return@withLock emptyList()
            }
            val settings = repository.getSettingsSnapshot()
            val outcomes = inspectBatchLocked(
                items = targets,
                urgentNotificationsEnabled = settings.urgentNotificationsEnabled,
            )
            outcomes.map { it.analysis }
        }

    override suspend fun runBackgroundPollPass(): RestockBackgroundPollOutcome =
        pollMutex.withLock {
            val settings = repository.getSettingsSnapshot()
            if (!settings.isMonitorEnabled) {
                return@withLock RestockBackgroundPollOutcome.Skipped(
                    RestockBackgroundPollOutcome.Skipped.SkipReason.DISABLED,
                )
            }
            if (settings.wifiOnlyInBackground && !isUnmeteredNetwork()) {
                return@withLock RestockBackgroundPollOutcome.Skipped(
                    RestockBackgroundPollOutcome.Skipped.SkipReason.UNMETERED_NETWORK_REQUIRED,
                )
            }
            val enabledItems = repository.getWatchItemsSnapshot().filter { it.isEnabled }
            if (enabledItems.isEmpty()) {
                return@withLock RestockBackgroundPollOutcome.Skipped(
                    RestockBackgroundPollOutcome.Skipped.SkipReason.NO_ENABLED_WATCHES,
                )
            }

            val outcomes = inspectBatchLocked(
                items = enabledItems,
                urgentNotificationsEnabled = settings.urgentNotificationsEnabled,
            )

            val restockedCount = outcomes.count { it.triggeredRestockWave }
            val errorOutcomes = outcomes.filter { it.analysis.stockStatus == RestockStockStatus.CHECK_ERROR }
            val allTransientFailures = outcomes.isNotEmpty() &&
                errorOutcomes.size == outcomes.size &&
                errorOutcomes.all { it.analysis.isTransientNetworkError }

            if (allTransientFailures) {
                RestockBackgroundPollOutcome.Retry(
                    reason = errorOutcomes.first().analysis.errorMessage ?: "Transient network failure",
                )
            } else {
                RestockBackgroundPollOutcome.Completed(
                    checkedCount = outcomes.size,
                    restockedCount = restockedCount,
                    errorCount = errorOutcomes.size,
                    errorSummary = errorOutcomes.firstOrNull()?.analysis?.errorMessage,
                )
            }
        }

    internal fun isForegroundLoopActive(): Boolean =
        synchronized(lifecycleLock) { monitorJob?.isActive == true }

    private fun launchForegroundLoopLocked() {
        if (monitorJob?.isActive == true) return
        wakeSignal.tryReceive()
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            runForegroundPollingLoop()
        }
        monitorJob = launched
        launched.start()
    }

    private suspend fun runForegroundPollingLoop() {
        val selfJob = currentCoroutineContext()[Job]
        while (currentCoroutineContext().isActive) {
            val shouldExit = synchronized(lifecycleLock) {
                if (!_runtimeStatus.value.isRunningInForeground || !isStarted) {
                    if (monitorJob === selfJob) {
                        monitorJob = null
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldExit) break

            val settings = repository.getSettingsSnapshot()
            val enabledItems = repository.getWatchItemsSnapshot().filter { it.isEnabled }

            if (!settings.isMonitorEnabled || enabledItems.isEmpty()) {
                wakeSignal.receive()
                continue
            }

            val now = clockMillis()
            val dueItems = enabledItems.filter { item ->
                val intervalMs = RestockWatchItem.clampPollInterval(item.pollIntervalMinutes) * 60_000L
                item.lastCheckedEpochMillis <= 0L || (now - item.lastCheckedEpochMillis) >= intervalMs
            }

            if (dueItems.isNotEmpty()) {
                pollMutex.withLock {
                    val latestSettings = repository.getSettingsSnapshot()
                    if (latestSettings.isMonitorEnabled && _runtimeStatus.value.isRunningInForeground) {
                        inspectBatchLocked(
                            items = dueItems,
                            urgentNotificationsEnabled = latestSettings.urgentNotificationsEnabled,
                        )
                    }
                }
                continue
            }

            val nextDueAt = enabledItems.minOf { item ->
                val intervalMs = RestockWatchItem.clampPollInterval(item.pollIntervalMinutes) * 60_000L
                item.lastCheckedEpochMillis + intervalMs
            }
            val waitMs = (nextDueAt - clockMillis()).coerceAtLeast(MIN_WAIT_SLICE_MS)

            withTimeoutOrNull(waitMs) {
                wakeSignal.receive()
            }
        }
    }

    private data class ItemCheckOutcome(
        val watchId: String,
        val analysis: StockAnalysisResult,
        val triggeredRestockWave: Boolean,
    )

    private suspend fun inspectBatchLocked(
        items: List<RestockWatchItem>,
        urgentNotificationsEnabled: Boolean,
    ): List<ItemCheckOutcome> {
        val ids = items.map { it.id }.toSet()
        _runtimeStatus.update {
            it.copy(
                isCheckingNow = true,
                checkingItemIds = it.checkingItemIds + ids,
                lastErrorSummary = null,
            )
        }

        val semaphore = Semaphore(MAX_CONCURRENT_CHECKS)
        return try {
            val outcomes = coroutineScope {
                items.map { item ->
                    async {
                        semaphore.withPermit {
                            val analysis = try {
                                analyzer.analyzeUrl(
                                    rawUrl = item.rawUrl,
                                    customInStockKeyword = item.customInStockKeyword,
                                    customOutOfStockKeyword = item.customOutOfStockKeyword,
                                )
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                StockAnalysisResult(
                                    rawUrl = item.rawUrl,
                                    resolvedOrderUrl = item.resolvedOrderUrl,
                                    finalUrl = item.resolvedOrderUrl,
                                    panelType = item.panelType,
                                    stockStatus = RestockStockStatus.CHECK_ERROR,
                                    matchedSignal = "检测异常",
                                    errorMessage = t.message ?: "检测失败",
                                    isTransientNetworkError = true,
                                    checkedAtEpochMillis = clockMillis(),
                                )
                            }

                            val event = repository.recordCheckResult(
                                watchId = item.id,
                                analysis = analysis,
                            )
                            if (event != null && urgentNotificationsEnabled) {
                                notificationHelper.postRestockUrgentNotification(event)
                            }
                            _runtimeStatus.update { state ->
                                state.copy(checkingItemIds = state.checkingItemIds - item.id)
                            }
                            ItemCheckOutcome(
                                watchId = item.id,
                                analysis = analysis,
                                triggeredRestockWave = event != null,
                            )
                        }
                    }
                }.awaitAll()
            }

            val firstError = outcomes.firstOrNull {
                it.analysis.stockStatus == RestockStockStatus.CHECK_ERROR
            }?.analysis?.errorMessage

            _runtimeStatus.update {
                it.copy(
                    isCheckingNow = false,
                    checkingItemIds = it.checkingItemIds - ids,
                    lastCheckEpochMillis = clockMillis(),
                    lastErrorSummary = firstError,
                )
            }
            outcomes
        } finally {
            _runtimeStatus.update {
                it.copy(
                    isCheckingNow = false,
                    checkingItemIds = it.checkingItemIds - ids,
                )
            }
        }
    }

    companion object {
        private const val MAX_CONCURRENT_CHECKS = 3
        private const val MIN_WAIT_SLICE_MS = 2_000L

        internal fun defaultIsUnmeteredNetwork(context: Context): Boolean {
            return runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
                val active = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(active) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }.getOrDefault(true)
        }
    }
}
