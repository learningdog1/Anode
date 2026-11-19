package com.nodeseek.app.data.fleet

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
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

internal interface FleetWorkManagerGateway {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun enqueueUniqueOneTimeWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

internal class AndroidFleetWorkManagerGateway(
    appContext: Context,
) : FleetWorkManagerGateway {
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

    override fun enqueueUniqueOneTimeWork(
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
 * Synchronizes [FleetMonitorSettings] & active server targets with Android [WorkManager]
 * periodic scheduling for [FleetMonitorWorker].
 */
class FleetWorkScheduler internal constructor(
    private val workManagerGateway: FleetWorkManagerGateway,
) {
    constructor(appContext: Context) : this(
        AndroidFleetWorkManagerGateway(appContext.applicationContext ?: appContext),
    )

    private val observerLock = Any()
    private var observerJob: Job? = null

    private data class ScheduleSignature(
        val shouldSchedule: Boolean,
        val intervalMinutes: Long,
        val wifiOnly: Boolean,
        val batteryNotLow: Boolean,
    )

    /**
     * Convenience entrypoint for `NodePulseApp` startup: observes repository settings, panels,
     * and assets, keeping WorkManager periodic work automatically synchronized.
     */
    fun observeRepository(
        scope: CoroutineScope,
        repository: FleetRepository,
    ) {
        val hasActiveTargetsFlow = combine(
            repository.panelsFlow,
            repository.assetsFlow,
        ) { panels, assets ->
            panels.any { it.enabled && it.notifyOnOffline } ||
                assets.any { it.remindDaysBeforeExpiry > 0 }
        }
        observeMonitorSettings(
            scope = scope,
            settingsFlow = repository.monitorSettingsFlow,
            hasActiveTargetsFlow = hasActiveTargetsFlow,
        )
    }

    fun observeMonitorSettings(
        scope: CoroutineScope,
        settingsFlow: Flow<FleetMonitorSettings>,
        hasActiveTargetsFlow: Flow<Boolean>,
    ) {
        synchronized(observerLock) {
            observerJob?.cancel()
            observerJob = scope.launch {
                combine(settingsFlow, hasActiveTargetsFlow) { settings, hasTargets ->
                    Triple(settings, hasTargets, toSignature(settings, hasTargets))
                }
                    .distinctUntilChanged { old, new -> old.third == new.third }
                    .collect { (settings, hasTargets, _) ->
                        sync(settings, hasTargets)
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
        settings: FleetMonitorSettings,
        hasActiveTargets: Boolean = true,
    ) {
        if (shouldSchedule(settings, hasActiveTargets)) {
            val request = buildPeriodicWorkRequest(settings)
            workManagerGateway.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_PERIODIC_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                request = request,
            )
        } else {
            workManagerGateway.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
        }
    }

    fun enqueueImmediateCheck() {
        val request = OneTimeWorkRequestBuilder<FleetMonitorWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(WORK_TAG)
            .build()
        workManagerGateway.enqueueUniqueOneTimeWork(
            uniqueWorkName = UNIQUE_IMMEDIATE_WORK_NAME,
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = request,
        )
    }

    private fun toSignature(
        settings: FleetMonitorSettings,
        hasActiveTargets: Boolean,
    ): ScheduleSignature {
        val active = shouldSchedule(settings, hasActiveTargets)
        return ScheduleSignature(
            shouldSchedule = active,
            intervalMinutes = if (active) coerceIntervalMinutes(settings.pollIntervalMinutes) else 0L,
            wifiOnly = if (active) settings.wifiOnlyInBackground else false,
            batteryNotLow = if (active) settings.requireBatteryNotLowInBackground else false,
        )
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK_NAME = "nodepulse_fleet_background_monitor"
        const val UNIQUE_IMMEDIATE_WORK_NAME = "nodepulse_fleet_immediate_check"
        const val WORK_TAG = "nodepulse_fleet_monitor"
        const val MIN_INTERVAL_MINUTES = 15L
        const val MAX_INTERVAL_MINUTES = 360L
        private const val RETRY_BACKOFF_SECONDS = 30L

        fun shouldSchedule(
            settings: FleetMonitorSettings,
            hasActiveTargets: Boolean,
        ): Boolean = settings.backgroundMonitorEnabled && hasActiveTargets

        fun coerceIntervalMinutes(rawMinutes: Int): Long =
            rawMinutes.toLong().coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

        fun buildConstraints(settings: FleetMonitorSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnlyInBackground) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresBatteryNotLow(settings.requireBatteryNotLowInBackground)
                .build()

        fun buildPeriodicWorkRequest(settings: FleetMonitorSettings): PeriodicWorkRequest {
            val intervalMinutes = coerceIntervalMinutes(settings.pollIntervalMinutes)
            val constraints = buildConstraints(settings)
            return PeriodicWorkRequestBuilder<FleetMonitorWorker>(
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
