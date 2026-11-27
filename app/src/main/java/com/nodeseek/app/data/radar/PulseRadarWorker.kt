package com.nodeseek.app.data.radar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nodeseek.app.NodePulseApp
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.radar.RadarBackgroundPollOutcome
import com.nodeseek.app.widget.GlanceWidgetUpdater
import kotlinx.coroutines.CancellationException

internal interface AppContainerHost {
    val container: AppContainer
}

/**
 * WorkManager periodic worker executing a single serialized background radar poll pass
 * (keyword watch rules, tracked topic replies, and inbox/DM system notifications).
 */
class PulseRadarWorker internal constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val backgroundPollRunner: (suspend () -> RadarBackgroundPollOutcome)?,
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
            val runner = backgroundPollRunner ?: run {
                val appContainer: AppContainer = when (val app = applicationContext) {
                    is NodePulseApp -> app.container
                    is AppContainerHost -> app.container
                    else -> return Result.failure()
                }
                appContainer.radarMonitorController::runBackgroundPollPass
            }
            runner()
        } catch (c: CancellationException) {
            throw c
        } catch (_: UninitializedPropertyAccessException) {
            // SystemJobService / WorkManager can invoke doWork() during ContentProvider startup
            // before Application.onCreate() finishes initializing AppContainer; retry cleanly later.
            return Result.retry()
        } catch (t: Throwable) {
            return if (PulseRadarMonitor.isTransientNetworkFailure(t)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        if (outcome is RadarBackgroundPollOutcome.Completed &&
            (outcome.keywordAlertsTriggered > 0 || outcome.trackedReplyAlertsTriggered > 0)
        ) {
            GlanceWidgetUpdater.updateAll(applicationContext)
        }

        return when (outcome) {
            is RadarBackgroundPollOutcome.Retry -> Result.retry()
            is RadarBackgroundPollOutcome.Completed,
            is RadarBackgroundPollOutcome.Skipped,
            -> Result.success()
        }
    }
}
