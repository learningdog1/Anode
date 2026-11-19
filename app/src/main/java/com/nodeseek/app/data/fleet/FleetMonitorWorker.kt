package com.nodeseek.app.data.fleet

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

/**
 * Reusable background check runner executing one serialized pass of:
 * 1. Refreshing enabled probe panels
 * 2. Evaluating `ONLINE -> OFFLINE` transitions & upcoming VPS renewals
 * 3. Dispatching notifications through [FleetAlertSink]
 */
class FleetMonitorRunner(
    private val fleetRepository: FleetRepository,
    private val alertSink: FleetAlertSink,
    private val fxRatesProvider: suspend () -> com.nodeseek.app.data.valuation.FxRatesState? = { null },
    private val todayProvider: () -> LocalDate = { VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)) },
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun runOnce(): FleetMonitorCheckReport {
        fleetRepository.loadPersistedState()
        val refreshSummary = fleetRepository.refreshAllPanels()
        val fxRates = runCatching { fxRatesProvider() }.getOrNull()
        val report = fleetRepository.evaluateAlerts(
            today = todayProvider(),
            nowEpochMillis = nowMillisProvider(),
            fxRatesState = fxRates,
            checkedPanels = refreshSummary.refreshedPanelCount,
            failedPanels = refreshSummary.failedPanelErrors.size,
        )
        alertSink.dispatchReport(report)
        return report
    }
}

/**
 * WorkManager periodic [CoroutineWorker] monitoring My Servers probe availability and VPS expiry reminders.
 */
class FleetMonitorWorker internal constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val customRunner: (suspend () -> FleetMonitorCheckReport)?,
) : CoroutineWorker(appContext, workerParams) {

    constructor(
        appContext: Context,
        workerParams: WorkerParameters,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        customRunner = null,
    )

    override suspend fun doWork(): Result {
        return try {
            val report = if (customRunner != null) {
                customRunner.invoke()
            } else {
                val repo = externalRepositoryFactory?.invoke(applicationContext)
                    ?: FleetRepositoryImpl.forContext(applicationContext)
                val fxRepo = ExchangeRateRepository.forContext(applicationContext)
                val notifier = FleetAlertNotifier(applicationContext)
                FleetMonitorRunner(
                    fleetRepository = repo,
                    alertSink = notifier,
                    fxRatesProvider = { fxRepo.loadPersistedState() },
                ).runOnce()
            }

            if (report.checkedPanels > 0 &&
                report.failedPanels == report.checkedPanels &&
                runAttemptCount < MAX_TRANSIENT_RETRIES
            ) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            if (runAttemptCount < MAX_TRANSIENT_RETRIES) Result.retry() else Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    companion object {
        private const val MAX_TRANSIENT_RETRIES = 3

        /**
         * Optional DI hook allowing `NodePulseApp` / `@integrator` to override the repository
         * resolved by [FleetMonitorWorker]. Defaults to [FleetRepositoryImpl.forContext].
         */
        @Volatile
        var externalRepositoryFactory: ((Context) -> FleetRepository)? = null
    }
}
