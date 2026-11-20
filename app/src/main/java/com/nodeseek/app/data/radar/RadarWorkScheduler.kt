package com.nodeseek.app.data.radar

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nodeseek.app.domain.radar.RadarPollingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
    private var observerJob: Job? = null

    private data class ScheduleSignature(
        val shouldSchedule: Boolean,
        val intervalMinutes: Long,
        val wifiOnlyInBackground: Boolean,
        val requireBatteryNotLowInBackground: Boolean,
    )

    fun observePollingSettings(
        scope: CoroutineScope,
        pollingSettingsFlow: Flow<RadarPollingSettings>,
    ) {
        synchronized(observerLock) {
            observerJob?.cancel()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                pollingSettingsFlow
                    .map { settings -> settings to toScheduleSignature(settings) }
                    .distinctUntilChanged { old, new -> old.second == new.second }
                    .collect { (settings, _) ->
                        sync(settings)
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
        const val WORK_TAG = "nodepulse_background_radar"
        const val MIN_INTERVAL_MINUTES = 15L
        const val MAX_INTERVAL_MINUTES = 180L
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
    }
}
