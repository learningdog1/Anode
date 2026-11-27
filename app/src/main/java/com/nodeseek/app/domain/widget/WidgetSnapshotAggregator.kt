package com.nodeseek.app.domain.widget

import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetTrafficAndIdleAnalyzer
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.LatestSeekMailOtp
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import java.time.Clock
import java.time.LocalDate

/**
 * Pure Kotlin aggregator producing deterministic widget snapshots from Fleet, Account/Check-In,
 * Radar, and Seek Mail domain models.
 */
object WidgetSnapshotAggregator {

    private const val PRIMARY_MACHINE_KEYWORD = "主力"

    internal data class PrimaryMachineSelection(
        val snapshot: ProbeServerSnapshot,
        val displayName: String,
        val flagEmoji: String,
        val usedBytes: Long,
        val limitBytes: Long?,
        val usageRatio: Float?,
    )

    fun buildServerStatus(
        assets: List<VpsAssetRecord>,
        snapshots: List<ProbeServerSnapshot>,
        lastSyncEpochMillis: Long = 0L,
        today: LocalDate = VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID)),
    ): ServerStatusWidgetSnapshot {
        val summary = FleetPortfolioCalculator.summarize(
            assets = assets,
            snapshots = snapshots,
            fxRatesState = null,
            today = today,
        )
        val primary = selectPrimaryServer(snapshots = snapshots, assets = assets)
        // Strictly exclude DOMAIN_CERT assets from the VPS renewal countdown.
        val nearestVpsRenewal = summary.upcomingRenewals.firstOrNull { !it.asset.isDomainAsset }
        val nearestName = nearestVpsRenewal?.asset?.let { asset ->
            asset.nickname.trim()
                .ifEmpty { asset.providerName.trim() }
                .ifEmpty {
                    asset.id.trim().takeIf { it.isNotEmpty() }?.let { "VPS #$it" } ?: "VPS"
                }
        }

        return ServerStatusWidgetSnapshot(
            totalProbeCount = summary.totalProbeNodeCount,
            onlineProbeCount = summary.onlineProbeCount,
            offlineProbeCount = summary.offlineProbeCount,
            primaryServerName = primary?.displayName,
            primaryServerFlag = primary?.flagEmoji.orEmpty(),
            primaryTrafficUsedBytes = primary?.usedBytes ?: 0L,
            primaryTrafficLimitBytes = primary?.limitBytes,
            primaryTrafficRatio = primary?.usageRatio,
            nearestVpsName = nearestName,
            nearestVpsDaysRemaining = nearestVpsRenewal?.daysRemaining,
            nearestVpsUrgency = nearestVpsRenewal?.urgency,
            nearestVpsExpiryIso = nearestVpsRenewal?.asset?.expiryDateIso,
            lastSyncEpochMillis = lastSyncEpochMillis,
        )
    }

    internal fun selectPrimaryServer(
        snapshots: List<ProbeServerSnapshot>,
        assets: List<VpsAssetRecord>,
    ): PrimaryMachineSelection? {
        if (snapshots.isEmpty()) return null

        val vpsAssetsByProbeKey = HashMap<String, VpsAssetRecord>()
        for (asset in assets) {
            val key = asset.linkedProbeKey?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val existing = vpsAssetsByProbeKey[key]
            if (existing == null || (existing.isDomainAsset && !asset.isDomainAsset)) {
                vpsAssetsByProbeKey[key] = asset
            }
        }

        data class RankedCandidate(
            val tier: Int,
            val index: Int,
            val selection: PrimaryMachineSelection,
        )

        val candidates = snapshots.mapIndexed { index, snapshot ->
            val linkedAsset = vpsAssetsByProbeKey[snapshot.probeKey]
            val limitBytes = FleetTrafficAndIdleAnalyzer.resolveEffectiveTrafficLimitBytes(
                snapshot = snapshot,
                linkedAsset = linkedAsset,
            )?.takeIf { it > 0L }
            val hasLimit = limitBytes != null
            val usedBytes = snapshot.totalMonthlyTransferBytes.coerceAtLeast(0L)
            val usageRatio = if (limitBytes != null) {
                (usedBytes.toDouble() / limitBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
            val isPrimaryTagged = snapshot.name.contains(PRIMARY_MACHINE_KEYWORD) ||
                (linkedAsset?.nickname?.contains(PRIMARY_MACHINE_KEYWORD) == true) ||
                (linkedAsset?.resolvedTags?.any { it.contains(PRIMARY_MACHINE_KEYWORD) } == true)

            val tier = when {
                snapshot.isOnline && isPrimaryTagged && hasLimit -> 0
                snapshot.isOnline && isPrimaryTagged -> 1
                snapshot.isOnline && hasLimit -> 2
                hasLimit -> 3
                snapshot.isOnline -> 4
                else -> 5
            }

            val displayName = linkedAsset?.nickname?.trim()?.takeIf { it.isNotEmpty() }
                ?: snapshot.name.trim().ifBlank { snapshot.remoteServerId }

            RankedCandidate(
                tier = tier,
                index = index,
                selection = PrimaryMachineSelection(
                    snapshot = snapshot,
                    displayName = displayName,
                    flagEmoji = snapshot.resolvedFlagEmoji,
                    usedBytes = usedBytes,
                    limitBytes = limitBytes,
                    usageRatio = usageRatio,
                ),
            )
        }

        return candidates.minWithOrNull { a, b ->
            if (a.tier != b.tier) {
                return@minWithOrNull a.tier.compareTo(b.tier)
            }
            when (a.tier) {
                0, 1 -> {
                    val ratioCmp = (b.selection.usageRatio ?: -1f).compareTo(a.selection.usageRatio ?: -1f)
                    if (ratioCmp != 0) ratioCmp else a.index.compareTo(b.index)
                }
                2, 3 -> {
                    val ratioCmp = (b.selection.usageRatio ?: -1f).compareTo(a.selection.usageRatio ?: -1f)
                    if (ratioCmp != 0) {
                        ratioCmp
                    } else {
                        val bytesCmp = b.selection.usedBytes.compareTo(a.selection.usedBytes)
                        if (bytesCmp != 0) bytesCmp else a.index.compareTo(b.index)
                    }
                }
                else -> a.index.compareTo(b.index)
            }
        }?.selection
    }

    fun buildDailyPulse(
        session: AccountSession?,
        settings: AppSettings,
        checkInStatus: CheckInStatus?,
        radarPollingSettings: RadarPollingSettings,
        unreadAlertCount: Int,
        latestAlert: LocalAlertRecord?,
        latestOtp: LatestSeekMailOtp?,
    ): DailyPulseWidgetSnapshot {
        val signedIn = session != null
        return DailyPulseWidgetSnapshot(
            isSignedIn = signedIn,
            username = session?.username.orEmpty(),
            drumsticks = session?.drumsticks ?: 0,
            hasCheckedInToday = signedIn && checkInStatus?.hasCheckedInToday == true,
            todayGain = if (signedIn) checkInStatus?.todayGain else null,
            todayOrder = if (signedIn) checkInStatus?.todayOrder else null,
            preferRandomCheckIn = settings.preferRandomCheckIn,
            radarEnabled = radarPollingSettings.isEnabled,
            unreadAlertCount = unreadAlertCount.coerceAtLeast(0),
            latestAlertTitle = latestAlert?.topicTitle?.trim()?.takeIf { it.isNotEmpty() },
            latestAlertKeyword = latestAlert?.matchedKeyword?.trim()?.takeIf { it.isNotEmpty() },
            latestOtpCode = latestOtp?.code?.trim()?.takeIf { it.isNotEmpty() },
            latestOtpSender = latestOtp?.sender?.trim()?.takeIf { it.isNotEmpty() },
            hasSeekMailKey = settings.seekMailApiKey.trim().isNotEmpty(),
        )
    }
}
