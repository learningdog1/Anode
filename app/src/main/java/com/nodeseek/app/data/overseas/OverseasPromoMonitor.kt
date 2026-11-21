package com.nodeseek.app.data.overseas

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasOfferQualityFilter
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorController
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorSettings
import com.nodeseek.app.domain.overseas.OverseasPromoPollOutcome
import com.nodeseek.app.domain.overseas.OverseasPromoRuntimeStatus
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host interface implemented by [com.nodeseek.app.NodePulseApp] (and test [Context]s) so
 * [OverseasPromoWorker] shares the process-wide [OverseasPromoMonitorController] instance.
 */
interface OverseasPromoMonitorHost {
    val overseasPromoMonitorController: OverseasPromoMonitorController
}

/**
 * Production [OverseasPromoMonitorController] coordinating:
 * - Proactive foreground coroutine polling (default every 2 minutes with debounce & exponential error backoff),
 * - Serialized WorkManager background poll passes (sharing [pollMutex] so foreground and background never double-notify),
 * - Safe first-run / first-enable baseline initialization (`seenOfferIds`) so historical offers never spam notifications,
 * - Persistent deduplication via [OverseasPromoPreferencesStore] and system alerts via [PulseNotificationDispatcher].
 */
class OverseasPromoMonitorControllerImpl(
    private val repository: OverseasIntelRepository,
    private val preferencesStore: OverseasPromoPreferencesStore,
    private val notificationDispatcher: PulseNotificationDispatcher,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val isUnmeteredNetwork: () -> Boolean = { true },
    private val startupDelayMillis: Long = DEFAULT_STARTUP_DELAY_MS,
) : OverseasPromoMonitorController {

    constructor(
        appContext: Context,
        repository: OverseasIntelRepository,
        preferencesStore: OverseasPromoPreferencesStore,
        notificationDispatcher: PulseNotificationDispatcher,
        scope: CoroutineScope,
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        repository = repository,
        preferencesStore = preferencesStore,
        notificationDispatcher = notificationDispatcher,
        scope = scope,
        clockMillis = clockMillis,
        isUnmeteredNetwork = { defaultIsUnmeteredNetwork(appContext) },
        startupDelayMillis = DEFAULT_STARTUP_DELAY_MS,
    )

    override val settingsFlow: StateFlow<OverseasPromoMonitorSettings>
        get() = preferencesStore.settingsFlow

    private val _runtimeStatus = MutableStateFlow(
        OverseasPromoRuntimeStatus(
            isRunningInForeground = true,
            baselineInitialized = preferencesStore.currentBaselineInitialized,
            lastCheckEpochMillis = preferencesStore.currentLastCheckedEpochMillis,
            lastNotifiedEpochMillis = preferencesStore.currentLastNotifiedEpochMillis,
        ),
    )
    override val runtimeStatus: StateFlow<OverseasPromoRuntimeStatus> = _runtimeStatus.asStateFlow()

    private val lifecycleLock = Any()
    private val pollMutex = Mutex()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val pendingManualTrigger = AtomicBoolean(false)

    private var isStarted: Boolean = false
    private var monitorJob: Job? = null
    private var settingsObserverJob: Job? = null

    @Volatile
    private var backoffUntilEpochMillis: Long = 0L

    @Volatile
    private var consecutiveFailures: Int = 0

    override fun start() {
        synchronized(lifecycleLock) {
            isStarted = true
            if (settingsObserverJob?.isActive != true) {
                val observer = scope.launch(start = CoroutineStart.LAZY) {
                    preferencesStore.settingsFlow
                        .drop(1)
                        .collect {
                            wakeSignal.trySend(Unit)
                        }
                }
                settingsObserverJob = observer
                observer.start()
            }
            if (_runtimeStatus.value.isRunningInForeground || pendingManualTrigger.get()) {
                launchForegroundLoopLocked()
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            isStarted = false
            pendingManualTrigger.set(false)
            settingsObserverJob?.cancel()
            settingsObserverJob = null
            monitorJob?.cancel()
            monitorJob = null
            _runtimeStatus.update { it.copy(isPollingNow = false) }
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
                // Wake sleeping foreground coroutine so it exits promptly and hands off to WorkManager.
                wakeSignal.trySend(Unit)
            }
        }
    }

    override fun triggerImmediateCheck() {
        synchronized(lifecycleLock) {
            if (!preferencesStore.settingsFlow.value.notificationsEnabled) return
            pendingManualTrigger.set(true)
            if (isStarted && monitorJob?.isActive != true) {
                launchForegroundLoopLocked()
            } else if (!isStarted) {
                scope.launch {
                    if (pendingManualTrigger.getAndSet(false)) {
                        checkNow(manualTrigger = true)
                    }
                }
            }
            wakeSignal.trySend(Unit)
        }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        if (enabled) {
            backoffUntilEpochMillis = 0L
            consecutiveFailures = 0
            val currentLoadedIds = repository.snapshot.value.offers
                .map { it.id }
                .filter { it.isNotBlank() }
            preferencesStore.setNotificationsEnabled(
                enabled = true,
                seedBaselineIds = currentLoadedIds,
            )
            _runtimeStatus.update {
                it.copy(
                    baselineInitialized = preferencesStore.currentBaselineInitialized,
                    lastErrorSummary = null,
                )
            }
            triggerImmediateCheck()
        } else {
            pendingManualTrigger.set(false)
            preferencesStore.setNotificationsEnabled(enabled = false)
            _runtimeStatus.update {
                it.copy(isPollingNow = false)
            }
            wakeSignal.trySend(Unit)
        }
    }

    override suspend fun updateSettings(
        transform: (OverseasPromoMonitorSettings) -> OverseasPromoMonitorSettings,
    ): OverseasPromoMonitorSettings {
        val updated = preferencesStore.updateSettings(transform)
        wakeSignal.trySend(Unit)
        return updated
    }

    override suspend fun checkNow(manualTrigger: Boolean): OverseasPromoPollOutcome =
        pollMutex.withLock {
            executePollPassLocked(
                manualTrigger = manualTrigger,
                isBackgroundPass = false,
            )
        }

    override suspend fun runBackgroundPollPass(): OverseasPromoPollOutcome =
        pollMutex.withLock {
            executePollPassLocked(
                manualTrigger = false,
                isBackgroundPass = true,
            )
        }

    internal fun isForegroundLoopActive(): Boolean =
        synchronized(lifecycleLock) { monitorJob?.isActive == true }

    private fun launchForegroundLoopLocked() {
        if (monitorJob?.isActive == true) return
        wakeSignal.tryReceive()
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val selfJob = currentCoroutineContext()[Job]
            try {
                runForegroundPollingLoop(selfJob = selfJob)
            } finally {
                if (selfJob != null) {
                    synchronized(lifecycleLock) {
                        if (monitorJob === selfJob) {
                            monitorJob = null
                        }
                    }
                }
            }
        }
        monitorJob = launched
        launched.start()
    }

    private suspend fun runForegroundPollingLoop(selfJob: Job?) {
        var lastSkippedAttemptMillis = 0L
        var hasPerformedStartupPoll = _runtimeStatus.value.lastCheckEpochMillis > 0L

        while (currentCoroutineContext().isActive) {
            if (pendingManualTrigger.getAndSet(false)) {
                val outcome = checkNow(manualTrigger = true)
                if (outcome is OverseasPromoPollOutcome.Skipped) {
                    lastSkippedAttemptMillis = clockMillis()
                }
                hasPerformedStartupPoll = true
            }

            val shouldExit = synchronized(lifecycleLock) {
                if ((!_runtimeStatus.value.isRunningInForeground || !isStarted) && !pendingManualTrigger.get()) {
                    if (selfJob != null && monitorJob === selfJob) {
                        monitorJob = null
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldExit) break

            if (pendingManualTrigger.get()) continue

            val settings = preferencesStore.getSettings()
            if (!settings.notificationsEnabled) {
                wakeSignal.receive()
                continue
            }

            if (!hasPerformedStartupPoll) {
                if (startupDelayMillis > 0L) {
                    withTimeoutOrNull(startupDelayMillis) {
                        wakeSignal.receive()
                    }
                }
                if (pendingManualTrigger.getAndSet(false)) {
                    hasPerformedStartupPoll = true
                    val outcome = checkNow(manualTrigger = true)
                    if (outcome is OverseasPromoPollOutcome.Skipped) {
                        lastSkippedAttemptMillis = clockMillis()
                    }
                    continue
                }
                if (!_runtimeStatus.value.isRunningInForeground || !isStarted) continue
                if (!preferencesStore.getSettings().notificationsEnabled) continue

                hasPerformedStartupPoll = true
                val initialOutcome = checkNow(manualTrigger = false)
                if (initialOutcome is OverseasPromoPollOutcome.Skipped) {
                    lastSkippedAttemptMillis = clockMillis()
                }
                continue
            }

            val intervalMs = settings.foregroundIntervalMinutes.coerceIn(
                OverseasPromoMonitorSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
                OverseasPromoMonitorSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
            ) * 60_000L
            val now = clockMillis()
            val lastAttemptMs = maxOf(_runtimeStatus.value.lastCheckEpochMillis, lastSkippedAttemptMillis)
            val nextScheduledMs = maxOf(lastAttemptMs + intervalMs, backoffUntilEpochMillis)
            val waitMs = (nextScheduledMs - now).coerceAtLeast(MIN_WAIT_SLICE_MS)

            val wokeEarly = withTimeoutOrNull(waitMs) {
                wakeSignal.receive()
            }
            if (pendingManualTrigger.getAndSet(false)) {
                val outcome = checkNow(manualTrigger = true)
                if (outcome is OverseasPromoPollOutcome.Skipped) {
                    lastSkippedAttemptMillis = clockMillis()
                }
                continue
            }
            if (!_runtimeStatus.value.isRunningInForeground || !isStarted) continue

            val latestSettings = preferencesStore.getSettings()
            if (!latestSettings.notificationsEnabled) continue

            if (wokeEarly != null) {
                val latestIntervalMs = latestSettings.foregroundIntervalMinutes.coerceIn(
                    OverseasPromoMonitorSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
                    OverseasPromoMonitorSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
                ) * 60_000L
                val elapsed = clockMillis() - maxOf(
                    _runtimeStatus.value.lastCheckEpochMillis,
                    lastSkippedAttemptMillis,
                )
                if (elapsed >= latestIntervalMs && clockMillis() >= backoffUntilEpochMillis) {
                    val outcome = checkNow(manualTrigger = false)
                    if (outcome is OverseasPromoPollOutcome.Skipped) {
                        lastSkippedAttemptMillis = clockMillis()
                    }
                }
            } else {
                val outcome = checkNow(manualTrigger = false)
                if (outcome is OverseasPromoPollOutcome.Skipped) {
                    lastSkippedAttemptMillis = clockMillis()
                }
            }
        }
    }

    private suspend fun executePollPassLocked(
        manualTrigger: Boolean,
        isBackgroundPass: Boolean,
    ): OverseasPromoPollOutcome {
        val now = clockMillis()
        val settings = preferencesStore.getSettings()

        if (!settings.notificationsEnabled) {
            return OverseasPromoPollOutcome.Skipped(
                OverseasPromoPollOutcome.Skipped.SkipReason.DISABLED,
            )
        }
        if (!manualTrigger && now < backoffUntilEpochMillis) {
            return OverseasPromoPollOutcome.Skipped(
                OverseasPromoPollOutcome.Skipped.SkipReason.BACKOFF_ACTIVE,
            )
        }
        if (isBackgroundPass && settings.wifiOnlyInBackground && !isUnmeteredNetwork()) {
            return OverseasPromoPollOutcome.Skipped(
                OverseasPromoPollOutcome.Skipped.SkipReason.UNMETERED_NETWORK_REQUIRED,
            )
        }
        if (!manualTrigger && !isBackgroundPass) {
            val lastCheck = _runtimeStatus.value.lastCheckEpochMillis
            if (lastCheck > 0L && (now - lastCheck) < MIN_FOREGROUND_DEBOUNCE_MS) {
                return OverseasPromoPollOutcome.Completed(
                    totalOffers = repository.snapshot.value.offers.size,
                    newOffersNotified = 0,
                    baselineInitializedNow = false,
                )
            }
        }

        _runtimeStatus.update {
            it.copy(isPollingNow = true, lastErrorSummary = null)
        }

        return try {
            val refreshResult = repository.refresh(query = "")
            val finishedAt = clockMillis()

            if (refreshResult.isFailure) {
                val cause = refreshResult.exceptionOrNull()
                if (cause is CancellationException) throw cause
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
                val backoffMs = (BASE_ERROR_BACKOFF_MS shl (consecutiveFailures - 1))
                    .coerceAtMost(MAX_ERROR_BACKOFF_MS)
                backoffUntilEpochMillis = finishedAt + backoffMs
                val reason = cause?.message?.takeIf { it.isNotBlank() } ?: "海外情报同步失败"
                _runtimeStatus.update {
                    it.copy(
                        isPollingNow = false,
                        lastCheckEpochMillis = finishedAt,
                        lastErrorSummary = reason,
                    )
                }
                return OverseasPromoPollOutcome.Retry(reason = reason)
            }

            consecutiveFailures = 0
            backoffUntilEpochMillis = 0L

            val activeFilter = repository.snapshot.value.filterConfig
            val validOffers = OverseasOfferQualityFilter.filter(
                offers = refreshResult.getOrDefault(emptyList()),
                config = activeFilter,
            )
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id.trim() }

            val currentIds = validOffers.map { it.id.trim() }
            val wasBaselineInitialized = preferencesStore.isBaselineInitialized()

            if (!wasBaselineInitialized) {
                preferencesStore.initializeBaseline(
                    offerIds = currentIds,
                    checkedAtEpochMillis = finishedAt,
                )
                _runtimeStatus.update {
                    it.copy(
                        isPollingNow = false,
                        baselineInitialized = true,
                        lastCheckEpochMillis = finishedAt,
                        lastErrorSummary = null,
                    )
                }
                return OverseasPromoPollOutcome.Completed(
                    totalOffers = validOffers.size,
                    newOffersNotified = 0,
                    baselineInitializedNow = true,
                )
            }

            val seenIds = preferencesStore.getSeenOfferIds()
            val unseenOffers = validOffers.filter { it.id.trim() !in seenIds }
            val canNotify = notificationDispatcher.hasPostNotificationsPermission()
            val notifiedOffers = if (unseenOffers.isNotEmpty() && canNotify) {
                unseenOffers
            } else {
                emptyList()
            }

            preferencesStore.recordPollOutcome(
                currentFeedIds = currentIds,
                newlyNotifiedIds = notifiedOffers.map { it.id.trim() },
                checkedAtEpochMillis = finishedAt,
            )

            if (notifiedOffers.isNotEmpty()) {
                notificationDispatcher.postOverseasPromoNotifications(notifiedOffers)
            }

            _runtimeStatus.update {
                it.copy(
                    isPollingNow = false,
                    baselineInitialized = true,
                    lastCheckEpochMillis = finishedAt,
                    lastNotifiedEpochMillis = preferencesStore.currentLastNotifiedEpochMillis,
                    lastErrorSummary = null,
                )
            }

            OverseasPromoPollOutcome.Completed(
                totalOffers = validOffers.size,
                newOffersNotified = notifiedOffers.size,
                baselineInitializedNow = false,
            )
        } finally {
            _runtimeStatus.update { state ->
                if (state.isPollingNow) state.copy(isPollingNow = false) else state
            }
        }
    }

    companion object {
        const val DEFAULT_STARTUP_DELAY_MS = 2_500L
        private const val MIN_WAIT_SLICE_MS = 2_000L
        private const val MIN_FOREGROUND_DEBOUNCE_MS = 8_000L
        private const val BASE_ERROR_BACKOFF_MS = 30_000L
        private const val MAX_ERROR_BACKOFF_MS = 300_000L
        private const val MAX_BACKOFF_EXPONENT = 4

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

/**
 * WorkManager periodic worker executing a single serialized overseas promotion check pass
 * and dispatching system notifications whenever new promotions appear.
 */
class OverseasPromoWorker internal constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val backgroundPollRunner: (suspend () -> OverseasPromoPollOutcome)?,
) : CoroutineWorker(appContext, workerParams) {

    constructor(
        appContext: Context,
        workerParams: WorkerParameters,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        backgroundPollRunner = null,
    )

    override suspend fun doWork(): Result {
        val outcome = try {
            val runner = backgroundPollRunner ?: resolveDefaultRunner()
            runner()
        } catch (c: CancellationException) {
            throw c
        } catch (_: IOException) {
            return Result.retry()
        } catch (_: Throwable) {
            return Result.failure()
        }

        return when (outcome) {
            is OverseasPromoPollOutcome.Retry -> Result.retry()
            is OverseasPromoPollOutcome.Completed,
            is OverseasPromoPollOutcome.Skipped,
            -> Result.success()
        }
    }

    private fun resolveDefaultRunner(): suspend () -> OverseasPromoPollOutcome {
        val app = applicationContext
        if (app is OverseasPromoMonitorHost) {
            return app.overseasPromoMonitorController::runBackgroundPollPass
        }
        registeredControllerProvider?.invoke()?.let { registered ->
            return registered::runBackgroundPollPass
        }

        // Cold-start resilient standalone fallback sharing the same JSON preference & cache files.
        val standaloneRepository = OverseasIntelRepositoryImpl(context = app)
        val standalonePrefs = OverseasPromoPreferencesStore(context = app)
        val standaloneDispatcher = PulseNotificationDispatcher(context = app)
        val standaloneController = OverseasPromoMonitorControllerImpl(
            appContext = app,
            repository = standaloneRepository,
            preferencesStore = standalonePrefs,
            notificationDispatcher = standaloneDispatcher,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        return standaloneController::runBackgroundPollPass
    }

    companion object {
        @Volatile
        private var registeredControllerProvider: (() -> OverseasPromoMonitorController)? = null

        fun registerControllerProvider(provider: (() -> OverseasPromoMonitorController)?) {
            registeredControllerProvider = provider
        }
    }
}

internal interface OverseasPromoWorkManagerGateway {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

internal class AndroidOverseasPromoWorkManagerGateway(
    appContext: Context,
) : OverseasPromoWorkManagerGateway {
    private val context = appContext.applicationContext ?: appContext

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName,
            existingPeriodicWorkPolicy,
            request,
        )
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        workManager.cancelUniqueWork(uniqueWorkName)
    }
}

/**
 * Synchronizes [OverseasPromoMonitorSettings] with Android [WorkManager] periodic background
 * checks (`15..180` minutes with network connectivity constraints).
 *
 * Immediately cancels scheduled background work when [OverseasPromoMonitorSettings.notificationsEnabled]
 * is turned off.
 */
class OverseasPromoWorkScheduler internal constructor(
    private val workManagerGateway: OverseasPromoWorkManagerGateway,
) {
    constructor(appContext: Context) : this(
        AndroidOverseasPromoWorkManagerGateway(appContext.applicationContext ?: appContext),
    )

    private val observerLock = Any()
    private var observerJob: Job? = null

    private data class ScheduleSignature(
        val shouldSchedule: Boolean,
        val intervalMinutes: Long,
        val wifiOnlyInBackground: Boolean,
        val requireBatteryNotLowInBackground: Boolean,
    )

    fun observe(
        scope: CoroutineScope,
        settingsFlow: Flow<OverseasPromoMonitorSettings>,
    ) {
        synchronized(observerLock) {
            observerJob?.cancel()
            observerJob = scope.launch {
                settingsFlow
                    .map { settings -> settings to toSignature(settings) }
                    .distinctUntilChanged { old, new -> old.second == new.second }
                    .collect { (settings, _) ->
                        sync(settings)
                    }
            }
        }
    }

    fun stopObserving() {
        synchronized(observerLock) {
            observerJob?.cancel()
            observerJob = null
        }
    }

    fun sync(settings: OverseasPromoMonitorSettings) {
        if (shouldSchedule(settings)) {
            val request = buildPeriodicWorkRequest(settings)
            workManagerGateway.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                request = request,
            )
        } else {
            workManagerGateway.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    /**
     * Ensures background periodic monitoring is active when transitioning to background, or cancels
     * if notifications were disabled.
     */
    fun onAppEnteredBackground(settings: OverseasPromoMonitorSettings) {
        if (shouldSchedule(settings)) {
            val request = buildPeriodicWorkRequest(settings)
            workManagerGateway.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
                request = request,
            )
        } else {
            workManagerGateway.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private fun toSignature(settings: OverseasPromoMonitorSettings): ScheduleSignature {
        val active = shouldSchedule(settings)
        return ScheduleSignature(
            shouldSchedule = active,
            intervalMinutes = if (active) coerceIntervalMinutes(settings.backgroundIntervalMinutes) else 0L,
            wifiOnlyInBackground = if (active) settings.wifiOnlyInBackground else false,
            requireBatteryNotLowInBackground = if (active) settings.requireBatteryNotLowInBackground else false,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nodepulse_overseas_promo_monitor"
        const val WORK_TAG = "nodepulse_overseas_promo_monitor"
        const val MIN_INTERVAL_MINUTES = 15L
        const val MAX_INTERVAL_MINUTES = 180L
        private const val RETRY_BACKOFF_SECONDS = 30L

        fun shouldSchedule(settings: OverseasPromoMonitorSettings): Boolean =
            settings.notificationsEnabled

        fun coerceIntervalMinutes(rawMinutes: Int): Long =
            rawMinutes.toLong().coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

        fun buildConstraints(settings: OverseasPromoMonitorSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnlyInBackground) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresBatteryNotLow(settings.requireBatteryNotLowInBackground)
                .build()

        fun buildPeriodicWorkRequest(settings: OverseasPromoMonitorSettings): PeriodicWorkRequest {
            val intervalMinutes = coerceIntervalMinutes(settings.backgroundIntervalMinutes)
            val constraints = buildConstraints(settings)
            return PeriodicWorkRequestBuilder<OverseasPromoWorker>(
                intervalMinutes,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .addTag(WORK_TAG)
                .build()
        }
    }
}
