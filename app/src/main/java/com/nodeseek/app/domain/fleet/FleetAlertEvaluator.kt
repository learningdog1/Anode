package com.nodeseek.app.domain.fleet

import com.nodeseek.app.data.valuation.FxRatesState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure domain evaluator for:
 * 1. Probe node `ONLINE -> OFFLINE` transitions with per-outage deduplication & optional recovery alerts.
 * 2. VPS asset expiry renewal reminders within `remindDaysBeforeExpiry`, deduped per `(assetId, expiryDateIso)`.
 */
object FleetAlertEvaluator {

    data class EvaluationOutput(
        val updatedTracker: FleetAlertTrackerState,
        val report: FleetMonitorCheckReport,
    )

    fun evaluate(
        previousTracker: FleetAlertTrackerState,
        panels: List<ProbePanelConfig>,
        snapshots: List<ProbeServerSnapshot>,
        assets: List<VpsAssetRecord>,
        monitorSettings: FleetMonitorSettings,
        today: LocalDate,
        nowEpochMillis: Long,
        fxRatesState: FxRatesState? = null,
        checkedPanels: Int = panels.count { it.enabled },
        failedPanels: Int = 0,
    ): EvaluationOutput {
        val panelsById = panels.associateBy { it.id }
        val nextOnlineMap = previousTracker.lastKnownOnlineByProbeKey.toMutableMap()
        val nextOutageKeys = previousTracker.activeOutageNotifiedKeys.toMutableSet()
        val nextRenewalKeys = previousTracker.notifiedRenewalKeys.toMutableSet()

        val offlineAlerts = mutableListOf<FleetAlertTransition.OfflineDetected>()
        val recoveryAlerts = mutableListOf<FleetAlertTransition.RecoveredOnline>()
        val renewalReminders = mutableListOf<FleetAlertTransition.RenewalReminderDue>()

        // Prune probe tracking entries whose panels were deleted
        val validPanelIds = panelsById.keys
        nextOnlineMap.keys.removeAll { key ->
            val panelId = key.substringBefore(':', "")
            panelId.isNotEmpty() && panelId !in validPanelIds
        }
        nextOutageKeys.removeAll { key ->
            val panelId = key.substringBefore(':', "")
            panelId.isNotEmpty() && panelId !in validPanelIds
        }

        // 1. Evaluate probe ONLINE -> OFFLINE / OFFLINE -> ONLINE transitions
        for (snapshot in snapshots) {
            val panel = panelsById[snapshot.panelId] ?: continue
            val probeKey = snapshot.probeKey
            val previousOnline = nextOnlineMap[probeKey]

            if (panel.enabled && panel.notifyOnOffline) {
                when {
                    previousOnline == true && !snapshot.isOnline -> {
                        if (nextOutageKeys.add(probeKey)) {
                            offlineAlerts += FleetAlertTransition.OfflineDetected(
                                panelId = panel.id,
                                panelName = panel.name.ifBlank { panel.baseUrl },
                                server = snapshot,
                            )
                        }
                    }

                    previousOnline == false && snapshot.isOnline -> {
                        val wasNotifiedOutage = nextOutageKeys.remove(probeKey)
                        val shouldNotifyRecovery = panel.notifyOnRecovery || monitorSettings.notifyOnRecovery
                        if (wasNotifiedOutage && shouldNotifyRecovery) {
                            recoveryAlerts += FleetAlertTransition.RecoveredOnline(
                                panelId = panel.id,
                                panelName = panel.name.ifBlank { panel.baseUrl },
                                server = snapshot,
                            )
                        }
                    }

                    snapshot.isOnline -> {
                        nextOutageKeys.remove(probeKey)
                    }
                }
            } else if (snapshot.isOnline) {
                nextOutageKeys.remove(probeKey)
            }

            nextOnlineMap[probeKey] = snapshot.isOnline
        }

        // 2. Evaluate upcoming renewal reminders within `remindDaysBeforeExpiry`
        val validRenewalKeyPrefixes = assets.map { "${it.id}:${it.expiryDateIso.trim()}" }.toSet()
        nextRenewalKeys.retainAll(validRenewalKeyPrefixes)

        for (asset in assets) {
            val remindDays = asset.remindDaysBeforeExpiry
            if (remindDays <= 0) continue
            val expiryDate = FleetPortfolioCalculator.parseExpiryDate(asset.expiryDateIso) ?: continue
            val daysRemaining = ChronoUnit.DAYS.between(today, expiryDate)
            if (daysRemaining in 0L..remindDays.toLong()) {
                val dedupKey = "${asset.id}:${asset.expiryDateIso.trim()}"
                if (nextRenewalKeys.add(dedupKey)) {
                    val rate = fxRatesState?.rateFor(asset.currency) ?: asset.currency.defaultCnyRate
                    val (renewalCny, _, _) = FleetPortfolioCalculator.computeAssetCnyCosts(asset, rate)
                    renewalReminders += FleetAlertTransition.RenewalReminderDue(
                        asset = asset,
                        daysRemaining = daysRemaining,
                        renewalCny = renewalCny.setScale(2, RoundingMode.HALF_UP),
                    )
                }
            }
        }

        val sortedRenewalReminders = renewalReminders.sortedBy { it.daysRemaining }

        return EvaluationOutput(
            updatedTracker = FleetAlertTrackerState(
                lastKnownOnlineByProbeKey = nextOnlineMap,
                activeOutageNotifiedKeys = nextOutageKeys,
                notifiedRenewalKeys = nextRenewalKeys,
            ),
            report = FleetMonitorCheckReport(
                checkedPanels = checkedPanels,
                failedPanels = failedPanels,
                totalNodes = snapshots.size,
                onlineNodes = snapshots.count { it.isOnline },
                offlineAlerts = offlineAlerts,
                recoveryAlerts = recoveryAlerts,
                renewalReminders = sortedRenewalReminders,
                checkedAtEpochMillis = nowEpochMillis,
            ),
        )
    }
}
