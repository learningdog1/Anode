package com.nodeseek.app.data.fleet

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nodeseek.app.data.radar.AppContainerHost
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.alerts.AlertForwarder
import com.nodeseek.app.domain.alerts.ForwardableAlertEvent
import com.nodeseek.app.domain.alerts.NoOpAlertForwarder
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.widget.GlanceWidgetUpdater
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

/**
 * Reusable background check runner executing one serialized pass of:
 * 1. Refreshing enabled probe panels (recording rolling 30-day CPU utilization samples)
 * 2. Evaluating `ONLINE -> OFFLINE` transitions, upcoming VPS renewals, and >=80% cycle traffic alerts
 * 3. Dispatching notifications through [FleetAlertSink]
 */
class FleetMonitorRunner(
    private val fleetRepository: FleetRepository,
    private val alertSink: FleetAlertSink,
    private val fxRatesProvider: suspend () -> com.nodeseek.app.data.valuation.FxRatesState? = { null },
    private val todayProvider: () -> LocalDate = { VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)) },
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val alertForwarder: AlertForwarder = NoOpAlertForwarder,
) {
    suspend fun runOnce(): FleetMonitorCheckReport {
        fleetRepository.loadPersistedState()
        val refreshSummary = fleetRepository.refreshAllPanels()
        val fxRates = runCatching { fxRatesProvider() }.getOrNull()
        val nowMillis = nowMillisProvider()
        val report = fleetRepository.evaluateAlerts(
            today = todayProvider(),
            nowEpochMillis = nowMillis,
            fxRatesState = fxRates,
            checkedPanels = refreshSummary.refreshedPanelCount,
            failedPanels = refreshSummary.failedPanelErrors.size,
        )
        alertSink.dispatchReport(report)
        forwardReportAlerts(report, nowMillis)
        return report
    }

    private suspend fun forwardReportAlerts(
        report: FleetMonitorCheckReport,
        occurredAtMillis: Long,
    ) {
        for (offline in report.offlineAlerts) {
            safeForward(
                ForwardableAlertEvent.ProbeOffline(
                    panelName = offline.panelName,
                    serverName = offline.server.name,
                    probeKey = offline.server.probeKey,
                    flagEmoji = offline.server.resolvedFlagEmoji,
                    occurredAtEpochMillis = occurredAtMillis,
                ),
            )
        }
        for (recovered in report.recoveryAlerts) {
            safeForward(
                ForwardableAlertEvent.ProbeRecovered(
                    panelName = recovered.panelName,
                    serverName = recovered.server.name,
                    probeKey = recovered.server.probeKey,
                    flagEmoji = recovered.server.resolvedFlagEmoji,
                    occurredAtEpochMillis = occurredAtMillis,
                ),
            )
        }
        for (traffic in report.trafficAlerts) {
            safeForward(
                ForwardableAlertEvent.TrafficThreshold(
                    panelName = traffic.panelName,
                    serverName = traffic.server.name,
                    probeKey = traffic.server.probeKey,
                    flagEmoji = traffic.server.resolvedFlagEmoji,
                    usedBytes = traffic.usedBytes,
                    limitBytes = traffic.limitBytes,
                    usageRatio = traffic.usageRatio,
                    nextResetDateIso = traffic.nextResetDateIso,
                    exhaustionForecastText = traffic.exhaustionForecastText,
                    occurredAtEpochMillis = occurredAtMillis,
                ),
            )
        }
    }

    private suspend fun safeForward(event: ForwardableAlertEvent) {
        try {
            alertForwarder.forward(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // External forwarding failures must never abort background monitoring.
        }
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
                val forwarder = externalAlertForwarderFactory?.invoke(applicationContext)
                    ?: (applicationContext as? AppContainerHost)?.container?.alertForwarder
                    ?: NoOpAlertForwarder
                val fxRepo = ExchangeRateRepository.forContext(applicationContext)
                val notifier = FleetAlertNotifier(applicationContext)
                FleetMonitorRunner(
                    fleetRepository = repo,
                    alertSink = notifier,
                    fxRatesProvider = { fxRepo.loadPersistedState() },
                    alertForwarder = forwarder,
                ).runOnce()
            }

            GlanceWidgetUpdater.updateAll(applicationContext)

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
         * Optional DI hook allowing [com.nodeseek.app.NodePulseApp] to override the repository
         * resolved by [FleetMonitorWorker]. Defaults to [FleetRepositoryImpl.forContext].
         */
        @Volatile
        var externalRepositoryFactory: ((Context) -> FleetRepository)? = null

        /**
         * Optional DI hook allowing [com.nodeseek.app.NodePulseApp] to provide the singleton
         * [AlertForwarder] to [FleetMonitorWorker]. Defaults to [NoOpAlertForwarder] unless hosted.
         */
        @Volatile
        var externalAlertForwarderFactory: ((Context) -> AlertForwarder)? = null
    }
}
