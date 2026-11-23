package com.nodeseek.app.data.activity

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
import com.nodeseek.app.domain.activity.ActivityLotteryClassifier
import com.nodeseek.app.domain.activity.ActivityMonitorController
import com.nodeseek.app.domain.activity.ActivityMonitorRuntimeStatus
import com.nodeseek.app.domain.activity.ActivityMonitorSettings
import com.nodeseek.app.domain.activity.ActivityPollOutcome
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.ActivityTimeStatus
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
 * Host interface implemented by [com.nodeseek.app.NodePulseApp] so [ActivityMonitorWorker]
 * shares the process-wide [ActivityMonitorController] instance.
 */
interface ActivityMonitorHost {
    val activityMonitorController: ActivityMonitorController
}

/**
 * Production [ActivityMonitorController] coordinating opt-in community activity monitoring:
 * - Strictly idle by default when `notificationsEnabled == false`,
 * - Lightweight conditional (`ETag` -> `304`) foreground coroutine polling (default every 3 minutes),
 * - Serialized WorkManager background poll passes (`15` minutes) sharing [pollMutex],
 * - Baseline seeding on first enable so historical activities never spam notifications,
 * - Normalizes incoming records via [ActivityLotteryClassifier] so only explicit-link lotteries
 *   carry the `抽奖` kind/status, skips already-`ENDED` lotteries, and notifies newly discovered
 *   active lotteries (`ONGOING`/`UPCOMING`) and non-lottery community activities.
 */
class ActivityMonitorControllerImpl(
    private val repository: ActivityRepository,
    private val preferencesStore: ActivityPreferencesStore,
    private val notificationDispatcher: PulseNotificationDispatcher,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val isUnmeteredNetwork: () -> Boolean = { true },
    private val startupDelayMillis: Long = DEFAULT_STARTUP_DELAY_MS,
) : ActivityMonitorController {

    constructor(
        appContext: Context,
        repository: ActivityRepository,
        preferencesStore: ActivityPreferencesStore,
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

    override val settingsFlow: StateFlow<ActivityMonitorSettings>
        get() = preferencesStore.settingsFlow

    private val _runtimeStatus = MutableStateFlow(
        ActivityMonitorRuntimeStatus(
            isRunningInForeground = true,
            baselineInitialized = preferencesStore.currentBaselineInitialized,
            lastCheckEpochMillis = preferencesStore.currentLastCheckedEpochMillis,
            lastNotifiedEpochMillis = preferencesStore.currentLastNotifiedEpochMillis,
        ),
    )
    override val runtimeStatus: StateFlow<ActivityMonitorRuntimeStatus> = _runtimeStatus.asStateFlow()

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
            val currentLoadedIds = repository.snapshot.value.activities
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
        transform: (ActivityMonitorSettings) -> ActivityMonitorSettings,
    ): ActivityMonitorSettings {
        val updated = preferencesStore.updateSettings(transform)
        wakeSignal.trySend(Unit)
        return updated
    }

    override suspend fun checkNow(manualTrigger: Boolean): ActivityPollOutcome =
        pollMutex.withLock {
            executePollPassLocked(
                manualTrigger = manualTrigger,
                isBackgroundPass = false,
            )
        }

    override suspend fun runBackgroundPollPass(): ActivityPollOutcome =
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
                if (outcome is ActivityPollOutcome.Skipped) {
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
                    if (outcome is ActivityPollOutcome.Skipped) {
                        lastSkippedAttemptMillis = clockMillis()
                    }
                    continue
                }
                if (!_runtimeStatus.value.isRunningInForeground || !isStarted) continue
                if (!preferencesStore.getSettings().notificationsEnabled) continue

                hasPerformedStartupPoll = true
                val initialOutcome = checkNow(manualTrigger = false)
                if (initialOutcome is ActivityPollOutcome.Skipped) {
                    lastSkippedAttemptMillis = clockMillis()
                }
                continue
            }

            val intervalMs = settings.foregroundIntervalMinutes.coerceIn(
                ActivityMonitorSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
                ActivityMonitorSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
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
                if (outcome is ActivityPollOutcome.Skipped) {
                    lastSkippedAttemptMillis = clockMillis()
                }
                continue
            }
            if (!_runtimeStatus.value.isRunningInForeground || !isStarted) continue

            val latestSettings = preferencesStore.getSettings()
            if (!latestSettings.notificationsEnabled) continue

            if (wokeEarly != null) {
                val latestIntervalMs = latestSettings.foregroundIntervalMinutes.coerceIn(
                    ActivityMonitorSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
                    ActivityMonitorSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
                ) * 60_000L
                val elapsed = clockMillis() - maxOf(
                    _runtimeStatus.value.lastCheckEpochMillis,
                    lastSkippedAttemptMillis,
                )
                if (elapsed >= latestIntervalMs && clockMillis() >= backoffUntilEpochMillis) {
                    val outcome = checkNow(manualTrigger = false)
                    if (outcome is ActivityPollOutcome.Skipped) {
                        lastSkippedAttemptMillis = clockMillis()
                    }
                }
            } else {
                val outcome = checkNow(manualTrigger = false)
                if (outcome is ActivityPollOutcome.Skipped) {
                    lastSkippedAttemptMillis = clockMillis()
                }
            }
        }
    }

    private suspend fun executePollPassLocked(
        manualTrigger: Boolean,
        isBackgroundPass: Boolean,
    ): ActivityPollOutcome {
        val now = clockMillis()
        val settings = preferencesStore.getSettings()

        if (!settings.notificationsEnabled) {
            return ActivityPollOutcome.Skipped(
                ActivityPollOutcome.Skipped.SkipReason.DISABLED,
            )
        }
        if (!manualTrigger && now < backoffUntilEpochMillis) {
            return ActivityPollOutcome.Skipped(
                ActivityPollOutcome.Skipped.SkipReason.BACKOFF_ACTIVE,
            )
        }
        if (isBackgroundPass && settings.wifiOnlyInBackground && !isUnmeteredNetwork()) {
            return ActivityPollOutcome.Skipped(
                ActivityPollOutcome.Skipped.SkipReason.UNMETERED_NETWORK_REQUIRED,
            )
        }
        if (!manualTrigger && !isBackgroundPass) {
            val lastCheck = _runtimeStatus.value.lastCheckEpochMillis
            if (lastCheck > 0L && (now - lastCheck) < MIN_FOREGROUND_DEBOUNCE_MS) {
                return ActivityPollOutcome.Completed(
                    totalActivities = repository.snapshot.value.activities.size,
                    newActivitiesNotified = 0,
                    baselineInitializedNow = false,
                )
            }
        }

        _runtimeStatus.update {
            it.copy(isPollingNow = true, lastErrorSummary = null)
        }

        return try {
            val refreshResult = repository.refreshActivities(force = false)
            val finishedAt = clockMillis()

            if (refreshResult.isFailure) {
                val cause = refreshResult.exceptionOrNull()
                if (cause is CancellationException) throw cause
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
                val backoffMs = (BASE_ERROR_BACKOFF_MS shl (consecutiveFailures - 1))
                    .coerceAtMost(MAX_ERROR_BACKOFF_MS)
                backoffUntilEpochMillis = finishedAt + backoffMs
                val reason = cause?.message?.takeIf { it.isNotBlank() } ?: "社区活动同步失败"
                _runtimeStatus.update {
                    it.copy(
                        isPollingNow = false,
                        lastCheckEpochMillis = finishedAt,
                        lastErrorSummary = reason,
                    )
                }
                return ActivityPollOutcome.Retry(reason = reason)
            }

            consecutiveFailures = 0
            backoffUntilEpochMillis = 0L

            val validActivities = refreshResult.getOrNull()
                ?.activities
                .orEmpty()
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .distinctBy { it.id.trim() }
                .map(ActivityLotteryClassifier::normalizeItem)

            val currentIds = validActivities.map { it.id.trim() }
            val wasBaselineInitialized = preferencesStore.isBaselineInitialized()

            if (!wasBaselineInitialized) {
                preferencesStore.initializeBaseline(
                    activityIds = currentIds,
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
                return ActivityPollOutcome.Completed(
                    totalActivities = validActivities.size,
                    newActivitiesNotified = 0,
                    baselineInitializedNow = true,
                )
            }

            val seenIds = preferencesStore.getSeenActivityIds()
            // Lottery status applies only to explicit-link lotteries: skip already-ended lotteries,
            // while never discarding non-lottery community activities (whose lottery status is null).
            val unseenActiveItems = validActivities.filter { item ->
                item.id.trim() !in seenIds &&
                    item.resolveLotteryStatusOrNull(finishedAt) != ActivityTimeStatus.ENDED
            }
            val canNotify = notificationDispatcher.hasPostNotificationsPermission()
            val notifiedItems = if (unseenActiveItems.isNotEmpty() && canNotify) {
                unseenActiveItems
            } else {
                emptyList()
            }

            preferencesStore.recordPollOutcome(
                currentFeedIds = currentIds,
                newlyNotifiedIds = notifiedItems.map { it.id.trim() },
                checkedAtEpochMillis = finishedAt,
            )

            if (notifiedItems.isNotEmpty()) {
                notificationDispatcher.postCommunityActivityNotifications(notifiedItems)
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

            ActivityPollOutcome.Completed(
                totalActivities = validActivities.size,
                newActivitiesNotified = notifiedItems.size,
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
 * WorkManager periodic worker executing a single serialized community activity check pass
 * and dispatching system notifications whenever new active lotteries or community activities appear.
 */
class ActivityMonitorWorker internal constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val backgroundPollRunner: (suspend () -> ActivityPollOutcome)?,
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
            is ActivityPollOutcome.Retry -> Result.retry()
            is ActivityPollOutcome.Completed,
            is ActivityPollOutcome.Skipped,
            -> Result.success()
        }
    }

    private fun resolveDefaultRunner(): suspend () -> ActivityPollOutcome {
        val app = applicationContext
        if (app is ActivityMonitorHost) {
            return app.activityMonitorController::runBackgroundPollPass
        }
        registeredControllerProvider?.invoke()?.let { registered ->
            return registered::runBackgroundPollPass
        }

        val standaloneRepository = ForumWatchActivityRepositoryImpl(context = app)
        val standalonePrefs = ActivityPreferencesStore(context = app)
        val standaloneDispatcher = PulseNotificationDispatcher(context = app)
        val standaloneController = ActivityMonitorControllerImpl(
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
        private var registeredControllerProvider: (() -> ActivityMonitorController)? = null

        fun registerControllerProvider(provider: (() -> ActivityMonitorController)?) {
            registeredControllerProvider = provider
        }
    }
}

internal interface ActivityWorkManagerGateway {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

internal class AndroidActivityWorkManagerGateway(
    appContext: Context,
) : ActivityWorkManagerGateway {
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
 * Synchronizes [ActivityMonitorSettings] with Android [WorkManager] periodic background checks
 * (`15..180` minutes with network connectivity constraints).
 *
 * Immediately cancels scheduled background work whenever [ActivityMonitorSettings.notificationsEnabled]
 * is `false` (including default startup).
 */
class ActivityWorkScheduler internal constructor(
    private val workManagerGateway: ActivityWorkManagerGateway,
) {
    constructor(appContext: Context) : this(
        AndroidActivityWorkManagerGateway(appContext.applicationContext ?: appContext),
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
        settingsFlow: Flow<ActivityMonitorSettings>,
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

    fun sync(settings: ActivityMonitorSettings) {
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

    fun onAppEnteredBackground(settings: ActivityMonitorSettings) {
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

    private fun toSignature(settings: ActivityMonitorSettings): ScheduleSignature {
        val active = shouldSchedule(settings)
        return ScheduleSignature(
            shouldSchedule = active,
            intervalMinutes = if (active) coerceIntervalMinutes(settings.backgroundIntervalMinutes) else 0L,
            wifiOnlyInBackground = if (active) settings.wifiOnlyInBackground else false,
            requireBatteryNotLowInBackground = if (active) settings.requireBatteryNotLowInBackground else false,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nodepulse_community_activity_monitor"
        const val WORK_TAG = "nodepulse_community_activity_monitor"
        const val MIN_INTERVAL_MINUTES = 15L
        const val MAX_INTERVAL_MINUTES = 180L
        private const val RETRY_BACKOFF_SECONDS = 30L

        fun shouldSchedule(settings: ActivityMonitorSettings): Boolean =
            settings.notificationsEnabled

        fun coerceIntervalMinutes(rawMinutes: Int): Long =
            rawMinutes.toLong().coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

        fun buildConstraints(settings: ActivityMonitorSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnlyInBackground) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresBatteryNotLow(settings.requireBatteryNotLowInBackground)
                .build()

        fun buildPeriodicWorkRequest(settings: ActivityMonitorSettings): PeriodicWorkRequest {
            val intervalMinutes = coerceIntervalMinutes(settings.backgroundIntervalMinutes)
            val constraints = buildConstraints(settings)
            return PeriodicWorkRequestBuilder<ActivityMonitorWorker>(
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
