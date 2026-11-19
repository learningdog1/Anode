package com.nodeseek.app.data.restock

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockWatchItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

internal interface RestockWorkManagerGateway {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

internal class AndroidRestockWorkManagerGateway(
    appContext: Context,
) : RestockWorkManagerGateway {
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
 * Synchronizes [RestockMonitorSettings] and active watch items with Android [WorkManager]
 * periodic background polling (`15..180` minutes).
 */
class RestockWorkScheduler internal constructor(
    private val workManagerGateway: RestockWorkManagerGateway,
) {
    constructor(appContext: Context) : this(
        AndroidRestockWorkManagerGateway(appContext.applicationContext ?: appContext),
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
        settingsFlow: Flow<RestockMonitorSettings>,
        watchItemsFlow: Flow<List<RestockWatchItem>>,
    ) {
        synchronized(observerLock) {
            observerJob?.cancel()
            observerJob = scope.launch {
                combine(settingsFlow, watchItemsFlow) { settings, items ->
                    val hasEnabled = items.any { it.isEnabled }
                    Triple(settings, hasEnabled, toSignature(settings, hasEnabled))
                }
                    .distinctUntilChanged { old, new -> old.third == new.third }
                    .collect { (settings, hasEnabled, _) ->
                        sync(settings = settings, hasEnabledWatches = hasEnabled)
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

    fun sync(
        settings: RestockMonitorSettings,
        hasEnabledWatches: Boolean,
    ) {
        if (shouldSchedule(settings, hasEnabledWatches)) {
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

    private fun toSignature(
        settings: RestockMonitorSettings,
        hasEnabledWatches: Boolean,
    ): ScheduleSignature {
        val active = shouldSchedule(settings, hasEnabledWatches)
        return ScheduleSignature(
            shouldSchedule = active,
            intervalMinutes = if (active) coerceIntervalMinutes(settings.backgroundIntervalMinutes) else 0L,
            wifiOnlyInBackground = if (active) settings.wifiOnlyInBackground else false,
            requireBatteryNotLowInBackground = if (active) settings.requireBatteryNotLowInBackground else false,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "anode_vps_restock_monitor"
        const val WORK_TAG = "anode_vps_restock_monitor"
        const val MIN_INTERVAL_MINUTES = 15L
        const val MAX_INTERVAL_MINUTES = 180L
        private const val RETRY_BACKOFF_SECONDS = 30L

        fun shouldSchedule(
            settings: RestockMonitorSettings,
            hasEnabledWatches: Boolean,
        ): Boolean = settings.isMonitorEnabled && hasEnabledWatches

        fun coerceIntervalMinutes(rawMinutes: Int): Long =
            rawMinutes.toLong().coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

        fun buildConstraints(settings: RestockMonitorSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnlyInBackground) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresBatteryNotLow(settings.requireBatteryNotLowInBackground)
                .build()

        fun buildPeriodicWorkRequest(settings: RestockMonitorSettings): PeriodicWorkRequest {
            val intervalMinutes = coerceIntervalMinutes(settings.backgroundIntervalMinutes)
            val constraints = buildConstraints(settings)
            return PeriodicWorkRequestBuilder<RestockCheckWorker>(
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
