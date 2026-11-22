package com.nodeseek.app.data.radar

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nodeseek.app.domain.radar.RadarPollingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

internal interface RadarWorkManagerGateway {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) = Unit

    fun cancelUniqueWork(uniqueWorkName: String)
}

internal class AndroidWorkManagerGateway(
    appContext: Context,
) : RadarWorkManagerGateway {
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

    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            existingWorkPolicy,
            request,
        )
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        workManager.cancelUniqueWork(uniqueWorkName)
    }
}

/**
 * Synchronizes [RadarPollingSettings] with Android [WorkManager] periodic background scheduling.
 */
class RadarWorkScheduler internal constructor(
    private val workManagerGateway: RadarWorkManagerGateway,
) {
    constructor(appContext: Context) : this(
        AndroidWorkManagerGateway(appContext.applicationContext ?: appContext),
    )

    private val observerLock = Any()
    private val scheduleLock = Any()
    private var observerJob: Job? = null

    @Volatile
    private var explicitSyncEpoch: Long = 0L

    private var lifecycleEpoch: Long = 0L
    private var inForeground: Boolean = false

    @Volatile
    private var backgroundEntryWorkEnqueued: Boolean = false

    private data class ScheduleSignature(
        val shouldSchedule: Boolean,
        val intervalMinutes: Long,
        val wifiOnlyInBackground: Boolean,
        val requireBatteryNotLowInBackground: Boolean,
    )

    private data class ObservedScheduleEmission(
        val settings: RadarPollingSettings,
        val signature: ScheduleSignature,
        val capturedEpoch: Long,
    )

    fun observePollingSettings(
        scope: CoroutineScope,
        pollingSettingsFlow: Flow<RadarPollingSettings>,
    ) {
        synchronized(observerLock) {
            observerJob?.cancel()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                pollingSettingsFlow
                    .map { settings ->
                        ObservedScheduleEmission(
                            settings = settings,
                            signature = toScheduleSignature(settings),
                            capturedEpoch = explicitSyncEpoch,
                        )
                    }
                    .distinctUntilChanged { old, new ->
                        old.signature == new.signature && old.capturedEpoch == new.capturedEpoch
                    }
                    .collectLatest { emission ->
                        ensureActive()
                        synchronized(scheduleLock) {
                            ensureActive()
                            if (emission.capturedEpoch != explicitSyncEpoch) return@synchronized
                            applyPeriodicSyncLocked(emission.settings)
                        }
                    }
            }
            observerJob = job
            job.start()
        }
    }

    fun stopObserving() {
        synchronized(observerLock) {
            observerJob?.cancel()
            observerJob = null
        }
    }

    fun sync(settings: RadarPollingSettings) {
        synchronized(scheduleLock) {
            explicitSyncEpoch++
            applyPeriodicSyncLocked(settings)
        }
    }

    private fun applyPeriodicSyncLocked(settings: RadarPollingSettings) {
        if (shouldSchedule(settings)) {
            val request = buildPeriodicWorkRequest(settings)
            workManagerGateway.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                request = request,
            )
        } else {
            workManagerGateway.cancelUniqueWork(UNIQUE_WORK_NAME)
            if (backgroundEntryWorkEnqueued) {
                backgroundEntryWorkEnqueued = false
                workManagerGateway.cancelUniqueWork(UNIQUE_BACKGROUND_ENTRY_WORK_NAME)
            }
        }
    }

    /**
     * Ensures periodic WorkManager monitoring stays active without resetting its 15-minute window
     * when the app enters the background, and schedules a prompt one-shot check in case the OS
     * freezes or reclaims the cached app process before the next periodic window.
     */
    fun onAppEnteredBackground(settings: RadarPollingSettings) {
        synchronized(scheduleLock) {
            inForeground = false
            lifecycleEpoch++
            applyBackgroundEntryLocked(settings)
        }
    }

    fun onAppEnteredBackgroundAsync(
        scope: CoroutineScope,
        settingsProvider: suspend () -> RadarPollingSettings,
    ) {
        val capturedEpoch = synchronized(scheduleLock) {
            inForeground = false
            ++lifecycleEpoch
        }
        scope.launch {
            val settings = settingsProvider()
            synchronized(scheduleLock) {
                if (inForeground || lifecycleEpoch != capturedEpoch) return@synchronized
                applyBackgroundEntryLocked(settings)
            }
        }
    }

    private fun applyBackgroundEntryLocked(settings: RadarPollingSettings) {
        if (shouldSchedule(settings)) {
            workManagerGateway.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
                request = buildPeriodicWorkRequest(settings),
            )
            backgroundEntryWorkEnqueued = true
            workManagerGateway.enqueueUniqueWork(
                uniqueWorkName = UNIQUE_BACKGROUND_ENTRY_WORK_NAME,
                existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                request = buildBackgroundEntryWorkRequest(settings),
            )
        } else {
            workManagerGateway.cancelUniqueWork(UNIQUE_WORK_NAME)
            if (backgroundEntryWorkEnqueued) {
                backgroundEntryWorkEnqueued = false
                workManagerGateway.cancelUniqueWork(UNIQUE_BACKGROUND_ENTRY_WORK_NAME)
            }
        }
    }

    fun onAppEnteredForeground() {
        synchronized(scheduleLock) {
            inForeground = true
            lifecycleEpoch++
            if (backgroundEntryWorkEnqueued) {
                backgroundEntryWorkEnqueued = false
                workManagerGateway.cancelUniqueWork(UNIQUE_BACKGROUND_ENTRY_WORK_NAME)
            }
        }
    }

    private fun toScheduleSignature(settings: RadarPollingSettings): ScheduleSignature {
        val active = shouldSchedule(settings)
        return ScheduleSignature(
            shouldSchedule = active,
            intervalMinutes = if (active) coerceIntervalMinutes(settings.backgroundIntervalMinutes) else 0L,
            wifiOnlyInBackground = if (active) settings.wifiOnlyInBackground else false,
            requireBatteryNotLowInBackground = if (active) settings.requireBatteryNotLowInBackground else false,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nodepulse_background_radar"
        const val UNIQUE_BACKGROUND_ENTRY_WORK_NAME = "nodepulse_background_radar_entry"
        const val WORK_TAG = "nodepulse_background_radar"
        const val MIN_INTERVAL_MINUTES = 15L
        const val MAX_INTERVAL_MINUTES = 180L
        const val BACKGROUND_ENTRY_DELAY_SECONDS = 60L
        private const val RETRY_BACKOFF_SECONDS = 30L

        fun shouldSchedule(settings: RadarPollingSettings): Boolean =
            settings.isEnabled && (
                settings.keywordAlertsEnabled ||
                    settings.replyTrackingEnabled ||
                    settings.inboxPushEnabled
                )

        fun coerceIntervalMinutes(rawMinutes: Int): Long =
            rawMinutes.toLong().coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

        fun buildConstraints(settings: RadarPollingSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnlyInBackground) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresBatteryNotLow(settings.requireBatteryNotLowInBackground)
                .build()

        fun buildPeriodicWorkRequest(settings: RadarPollingSettings): PeriodicWorkRequest {
            val intervalMinutes = coerceIntervalMinutes(settings.backgroundIntervalMinutes)
            val constraints = buildConstraints(settings)
            return PeriodicWorkRequestBuilder<PulseRadarWorker>(
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

        fun buildBackgroundEntryWorkRequest(settings: RadarPollingSettings): OneTimeWorkRequest {
            val constraints = buildConstraints(settings)
            return OneTimeWorkRequestBuilder<PulseRadarWorker>()
                .setInitialDelay(BACKGROUND_ENTRY_DELAY_SECONDS, TimeUnit.SECONDS)
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
