package com.nodeseek.app.domain.baseline

import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Pure-Kotlin comparison engine evaluating Before-vs-After deltas between a saved
 * [BenchmarkBaselineSnapshot] and a later benchmark snapshot.
 */
object BaselineDiffEngine {

    private val HE_TOKEN_REGEX = Regex("""\bHE(?:\.net)?\b""", RegexOption.IGNORE_CASE)
    private val COGENT_TOKEN_REGEX = Regex("""\bCogent\b""", RegexOption.IGNORE_CASE)

    private val PREMIUM_DOWNGRADE_PAIRS: List<Pair<BackboneRouteTag, Set<BackboneRouteTag>>> = listOf(
        BackboneRouteTag.CN2_GIA to setOf(BackboneRouteTag.CT_163, BackboneRouteTag.CN2_GT),
        BackboneRouteTag.CU_9929 to setOf(BackboneRouteTag.CU_4837),
        BackboneRouteTag.CMIN2 to setOf(BackboneRouteTag.CMI, BackboneRouteTag.CMNET),
    )

    fun compare(
        baseline: BenchmarkBaselineSnapshot,
        current: BenchmarkBaselineSnapshot,
    ): BaselineDiffReport {
        val elapsedDays = computeElapsedDays(baseline.capturedAtEpochMillis, current.capturedAtEpochMillis)
        val bandwidthDeltas = buildBandwidthDeltas(baseline.bandwidth, current.bandwidth)
        val latencyDeltas = buildLatencyDeltas(baseline.latency, current.latency)
        val streamingDiffs = buildStreamingDiffs(baseline.streamingUnlocks, current.streamingUnlocks)
        val routingShifts = buildRoutingShifts(baseline.routing, current.routing)

        val beforeOrigin = baseline.ipMetrics?.originType ?: IpOriginType.UNKNOWN
        val afterOrigin = current.ipMetrics?.originType ?: IpOriginType.UNKNOWN
        val ipOriginChanged = beforeOrigin != IpOriginType.UNKNOWN &&
            afterOrigin != IpOriginType.UNKNOWN &&
            beforeOrigin != afterOrigin

        val highlights = buildHighlightBullets(
            baseline = baseline,
            current = current,
            bandwidthDeltas = bandwidthDeltas,
            latencyDeltas = latencyDeltas,
            streamingDiffs = streamingDiffs,
            routingShifts = routingShifts,
            ipOriginChanged = ipOriginChanged,
        )

        val overallVerdict = resolveOverallVerdict(
            bandwidthDeltas = bandwidthDeltas,
            latencyDeltas = latencyDeltas,
            streamingDiffs = streamingDiffs,
            routingShifts = routingShifts,
            ipOriginChanged = ipOriginChanged,
            beforeOrigin = beforeOrigin,
            afterOrigin = afterOrigin,
        )

        return BaselineDiffReport(
            baseline = baseline,
            current = current,
            overallVerdict = overallVerdict,
            elapsedDays = elapsedDays,
            bandwidthDeltas = bandwidthDeltas,
            latencyDeltas = latencyDeltas,
            streamingDiffs = streamingDiffs,
            routingShifts = routingShifts,
            ipOriginChanged = ipOriginChanged,
            highlightBullets = highlights,
        )
    }

    private fun computeElapsedDays(beforeMillis: Long, afterMillis: Long): Long {
        if (afterMillis <= beforeMillis) return 0L
        val d1 = Instant.ofEpochMilli(beforeMillis).atZone(VALUATION_ZONE_ID).toLocalDate()
        val d2 = Instant.ofEpochMilli(afterMillis).atZone(VALUATION_ZONE_ID).toLocalDate()
        return ChronoUnit.DAYS.between(d1, d2).coerceAtLeast(0L)
    }

    private fun buildBandwidthDeltas(
        before: BaselineBandwidthMetrics,
        after: BaselineBandwidthMetrics,
    ): List<BandwidthDelta> {
        val pairs = listOf(
            Triple("下行带宽", before.downloadMbps, after.downloadMbps),
            Triple("上行带宽", before.uploadMbps, after.uploadMbps),
            Triple("峰值下行", before.peakDownloadMbps, after.peakDownloadMbps),
            Triple("峰值上行", before.peakUploadMbps, after.peakUploadMbps),
        )
        return pairs.mapNotNull { (label, bVal, aVal) ->
            if (bVal == null || aVal == null || !bVal.isFinite() || !aVal.isFinite()) return@mapNotNull null
            val delta = aVal - bVal
            val pct = if (bVal > 0.0) (delta / bVal) * 100.0 else null
            val trend = when {
                pct != null && pct <= -35.0 -> MetricTrend.DEGRADED_SEVERE
                pct != null && pct <= -15.0 -> MetricTrend.DEGRADED_WARN
                pct != null && pct >= 20.0 -> MetricTrend.IMPROVED
                pct == null && aVal > bVal && aVal > 0.0 -> MetricTrend.IMPROVED
                else -> MetricTrend.UNCHANGED
            }
            BandwidthDelta(
                metricLabel = label,
                beforeMbps = bVal,
                afterMbps = aVal,
                deltaMbps = delta,
                deltaPercent = pct,
                trend = trend,
            )
        }
    }

    private fun buildLatencyDeltas(
        before: BaselineLatencyMetrics,
        after: BaselineLatencyMetrics,
    ): List<LatencyDelta> {
        val result = mutableListOf<LatencyDelta>()

        fun addMsDelta(label: String, bVal: Double?, aVal: Double?) {
            if (bVal == null || aVal == null || !bVal.isFinite() || !aVal.isFinite()) return
            val delta = aVal - bVal
            val pct = if (bVal > 0.0) (delta / bVal) * 100.0 else 0.0
            val trend = when {
                delta >= 70.0 || (pct >= 60.0 && delta >= 30.0) -> MetricTrend.DEGRADED_SEVERE
                delta >= 30.0 || (pct >= 25.0 && delta >= 15.0) -> MetricTrend.DEGRADED_WARN
                delta <= -15.0 && pct <= -20.0 -> MetricTrend.IMPROVED
                else -> MetricTrend.UNCHANGED
            }
            result += LatencyDelta(label, bVal, aVal, delta, "ms", trend)
        }

        fun addLossDelta(label: String, bVal: Double?, aVal: Double?) {
            if (bVal == null || aVal == null || !bVal.isFinite() || !aVal.isFinite()) return
            val delta = aVal - bVal
            val trend = when {
                delta >= 5.0 -> MetricTrend.DEGRADED_SEVERE
                delta >= 1.0 -> MetricTrend.DEGRADED_WARN
                delta <= -2.0 -> MetricTrend.IMPROVED
                else -> MetricTrend.UNCHANGED
            }
            result += LatencyDelta(label, bVal, aVal, delta, "%", trend)
        }

        addMsDelta("ICMP 平均延迟", before.icmpAvgMs, after.icmpAvgMs)
        addLossDelta("ICMP 丢包率", before.icmpLossPercent, after.icmpLossPercent)
        addMsDelta("TCP 平均延迟", before.tcpAvgMs, after.tcpAvgMs)
        addLossDelta("TCP 丢包率", before.tcpLossPercent, after.tcpLossPercent)
        addMsDelta("HTTP TTFB", before.httpTtfbMs, after.httpTtfbMs)
        return result
    }

    private fun buildStreamingDiffs(
        beforeList: List<StreamingPlatformResult>,
        afterList: List<StreamingPlatformResult>,
    ): List<StreamingUnlockDiff> {
        if (beforeList.isEmpty() && afterList.isEmpty()) return emptyList()
        val beforeMap = beforeList.associateBy { it.platformKey.trim().lowercase(Locale.ROOT) }
        val afterMap = afterList.associateBy { it.platformKey.trim().lowercase(Locale.ROOT) }
        val orderedKeys = (beforeMap.keys + afterMap.keys).toSet()

        return orderedKeys.mapNotNull { key ->
            val b = beforeMap[key] ?: return@mapNotNull null
            val a = afterMap[key] ?: return@mapNotNull null
            val changeType = when {
                b.status == StreamingUnlockStatus.UNLOCKED && a.status == StreamingUnlockStatus.LOCKED ->
                    StreamingChangeType.LOST_UNLOCK
                b.status == StreamingUnlockStatus.UNLOCKED && a.status == StreamingUnlockStatus.ORIGINALS_ONLY ->
                    StreamingChangeType.DOWNGRADED_TO_ORIGINALS
                b.status == StreamingUnlockStatus.ORIGINALS_ONLY && a.status == StreamingUnlockStatus.LOCKED ->
                    StreamingChangeType.LOST_UNLOCK
                (b.status == StreamingUnlockStatus.LOCKED || b.status == StreamingUnlockStatus.ORIGINALS_ONLY) &&
                    a.status == StreamingUnlockStatus.UNLOCKED ->
                    StreamingChangeType.GAINED_UNLOCK
                b.status == StreamingUnlockStatus.LOCKED && a.status == StreamingUnlockStatus.ORIGINALS_ONLY ->
                    StreamingChangeType.GAINED_UNLOCK
                b.status == StreamingUnlockStatus.UNLOCKED && a.status == StreamingUnlockStatus.UNLOCKED &&
                    !b.regionCode.isNullOrBlank() && !a.regionCode.isNullOrBlank() &&
                    !b.regionCode.equals(a.regionCode, ignoreCase = true) ->
                    StreamingChangeType.REGION_CHANGED
                else -> StreamingChangeType.UNCHANGED
            }
            StreamingUnlockDiff(
                platformKey = key,
                displayName = a.displayName.ifBlank { b.displayName },
                beforeStatus = b.status,
                beforeRegion = b.regionCode,
                afterStatus = a.status,
                afterRegion = a.regionCode,
                changeType = changeType,
            )
        }
    }

    private fun buildRoutingShifts(
        before: BaselineRoutingMetrics,
        after: BaselineRoutingMetrics,
    ): List<RoutingShiftDiff> {
        val shifts = mutableListOf<RoutingShiftDiff>()

        val hasForward = before.forwardRouteSignature != null ||
            after.forwardRouteSignature != null ||
            before.forwardBackboneTags.isNotEmpty() ||
            after.forwardBackboneTags.isNotEmpty()
        if (hasForward && (before.forwardRouteSignature != null || before.forwardBackboneTags.isNotEmpty()) &&
            (after.forwardRouteSignature != null || after.forwardBackboneTags.isNotEmpty())
        ) {
            shifts += evaluateDirectionShift(
                directionLabel = "去程路由",
                beforeRoute = before.forwardRouteSignature ?: formatTagList(before.forwardBackboneTags),
                afterRoute = after.forwardRouteSignature ?: formatTagList(after.forwardBackboneTags),
                beforeTags = before.forwardBackboneTags,
                afterTags = after.forwardBackboneTags,
                beforeDetour = before.isDetourSuspected,
                afterDetour = after.isDetourSuspected,
                beforeHops = before.forwardHopCount,
                afterHops = after.forwardHopCount,
                beforeMs = before.forwardTerminalMs,
                afterMs = after.forwardTerminalMs,
                beforeLoss = before.forwardPeakHopLossPercent,
                afterLoss = after.forwardPeakHopLossPercent,
                afterHasRealLoss = after.forwardHasRealPacketLoss == true,
                beforeHasRealLoss = before.forwardHasRealPacketLoss == true,
                detourReason = after.detourReason,
            )
        }

        val hasReverse = (before.reverseRouteSignature != null || before.reverseBackboneTags.isNotEmpty()) &&
            (after.reverseRouteSignature != null || after.reverseBackboneTags.isNotEmpty())
        if (hasReverse) {
            shifts += evaluateDirectionShift(
                directionLabel = "回程路由",
                beforeRoute = before.reverseRouteSignature ?: formatTagList(before.reverseBackboneTags),
                afterRoute = after.reverseRouteSignature ?: formatTagList(after.reverseBackboneTags),
                beforeTags = before.reverseBackboneTags,
                afterTags = after.reverseBackboneTags,
                beforeDetour = before.isDetourSuspected,
                afterDetour = after.isDetourSuspected,
                beforeHops = before.reverseHopCount,
                afterHops = after.reverseHopCount,
                beforeMs = before.reverseTerminalMs,
                afterMs = after.reverseTerminalMs,
                beforeLoss = before.reversePeakHopLossPercent,
                afterLoss = after.reversePeakHopLossPercent,
                afterHasRealLoss = after.reverseHasRealPacketLoss == true,
                beforeHasRealLoss = before.reverseHasRealPacketLoss == true,
                detourReason = after.detourReason,
            )
        }

        // Compare per-carrier three-net backroutes if both have overlapping carriers
        val commonCarriers = before.threeNetBackrouteSummary.keys.intersect(after.threeNetBackrouteSummary.keys)
        for (carrier in commonCarriers) {
            val bStr = before.threeNetBackrouteSummary[carrier].orEmpty()
            val aStr = after.threeNetBackrouteSummary[carrier].orEmpty()
            if (bStr.isBlank() || aStr.isBlank()) continue
            val bTags = BaselineSnapshotExtractor.detectBackboneTagsInText(bStr)
            val aTags = BaselineSnapshotExtractor.detectBackboneTagsInText(aStr)
            val downgraded = isBackboneDowngraded(bTags, aTags)
            val upgraded = isBackboneDowngraded(aTags, bTags)
            val bHasDetour = bStr.contains("绕美") ||
                BackboneRouteTag.COGENT in bTags ||
                BackboneRouteTag.HE in bTags ||
                COGENT_TOKEN_REGEX.containsMatchIn(bStr) ||
                HE_TOKEN_REGEX.containsMatchIn(bStr)
            val aHasDetour = aStr.contains("绕美") ||
                BackboneRouteTag.COGENT in aTags ||
                BackboneRouteTag.HE in aTags ||
                COGENT_TOKEN_REGEX.containsMatchIn(aStr) ||
                HE_TOKEN_REGEX.containsMatchIn(aStr)
            val detourWord = !bHasDetour && aHasDetour
            if (bStr != aStr || downgraded || detourWord) {
                val summary = when {
                    detourWord -> "$carrier 回程出现绕行 ($bStr → $aStr)"
                    downgraded -> "$carrier 优质线路降级 ($bStr → $aStr)"
                    upgraded -> "$carrier 线路升级 ($bStr → $aStr)"
                    else -> "$carrier 路由变化 ($bStr → $aStr)"
                }
                val trend = when {
                    downgraded || detourWord -> MetricTrend.DEGRADED_SEVERE
                    upgraded -> MetricTrend.IMPROVED
                    else -> MetricTrend.INFO
                }
                shifts += RoutingShiftDiff(
                    directionLabel = "$carrier 回程",
                    beforeRoute = bStr,
                    afterRoute = aStr,
                    beforeBackbones = bTags,
                    afterBackbones = aTags,
                    beforeDetour = false,
                    afterDetour = detourWord,
                    detourIntroduced = detourWord,
                    backboneDowngraded = downgraded,
                    hopDelta = null,
                    latencyDeltaMs = null,
                    lossDeltaPercent = null,
                    shiftSummary = summary,
                    trend = trend,
                )
            }
        }

        // Cross-pipeline detour detection (e.g., SSH reverse-only baseline vs NetBench forward-only current)
        if (!before.isDetourSuspected && after.isDetourSuspected && shifts.none { it.detourIntroduced }) {
            val beforeBackbones = before.forwardBackboneTags.ifEmpty { before.reverseBackboneTags }
            val afterBackbones = after.forwardBackboneTags.ifEmpty { after.reverseBackboneTags }
            val beforeRouteSummary = before.forwardRouteSignature
                ?: before.reverseRouteSignature
                ?: before.threeNetBackrouteSummary.entries
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" · ") { (k, v) -> "$k:$v" }
                ?: formatTagList(beforeBackbones).takeIf { beforeBackbones.isNotEmpty() }
            val afterRouteSummary = after.forwardRouteSignature
                ?: after.reverseRouteSignature
                ?: after.threeNetBackrouteSummary.entries
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" · ") { (k, v) -> "$k:$v" }
                ?: formatTagList(afterBackbones).takeIf { afterBackbones.isNotEmpty() }
            shifts += RoutingShiftDiff(
                directionLabel = "路由绕行检测",
                beforeRoute = beforeRouteSummary,
                afterRoute = afterRouteSummary,
                beforeBackbones = beforeBackbones,
                afterBackbones = afterBackbones,
                beforeDetour = false,
                afterDetour = true,
                detourIntroduced = true,
                backboneDowngraded = false,
                hopDelta = null,
                latencyDeltaMs = null,
                lossDeltaPercent = null,
                shiftSummary = after.detourReason?.takeIf { it.isNotBlank() } ?: "检测到新增路由绕行",
                trend = MetricTrend.DEGRADED_SEVERE,
            )
        }

        return shifts
    }

    private fun evaluateDirectionShift(
        directionLabel: String,
        beforeRoute: String?,
        afterRoute: String?,
        beforeTags: List<BackboneRouteTag>,
        afterTags: List<BackboneRouteTag>,
        beforeDetour: Boolean,
        afterDetour: Boolean,
        beforeHops: Int?,
        afterHops: Int?,
        beforeMs: Double?,
        afterMs: Double?,
        beforeLoss: Double?,
        afterLoss: Double?,
        afterHasRealLoss: Boolean,
        beforeHasRealLoss: Boolean,
        detourReason: String?,
    ): RoutingShiftDiff {
        val detourIntroduced = !beforeDetour && afterDetour
        val backboneDowngraded = isBackboneDowngraded(beforeTags, afterTags)
        val backboneUpgraded = !backboneDowngraded && isBackboneDowngraded(afterTags, beforeTags)
        val hopDelta = if (beforeHops != null && afterHops != null) afterHops - beforeHops else null
        val latencyDeltaMs = if (beforeMs != null && afterMs != null && beforeMs.isFinite() && afterMs.isFinite()) {
            afterMs - beforeMs
        } else {
            null
        }
        val lossDeltaPercent = if (beforeLoss != null && afterLoss != null && beforeLoss.isFinite() && afterLoss.isFinite()) {
            afterLoss - beforeLoss
        } else {
            null
        }
        val realLossIntroduced = !beforeHasRealLoss && afterHasRealLoss

        val summaryParts = mutableListOf<String>()
        if (detourIntroduced) {
            summaryParts += detourReason ?: "新增路由绕美/绕行"
        }
        if (backboneDowngraded) {
            summaryParts += "骨干网由 ${formatTagList(beforeTags)} 降级为 ${formatTagList(afterTags)}"
        } else if (backboneUpgraded) {
            summaryParts += "骨干网升级为 ${formatTagList(afterTags)}"
        } else if (!beforeRoute.isNullOrBlank() && !afterRoute.isNullOrBlank() && beforeRoute != afterRoute) {
            summaryParts += "$beforeRoute → $afterRoute"
        }
        if (realLossIntroduced || (lossDeltaPercent != null && lossDeltaPercent >= 5.0)) {
            val afterLossVal = afterLoss?.let { String.format(Locale.US, "%.1f%%", it) } ?: "存在丢包"
            summaryParts += "出现真实链路丢包 ($afterLossVal)"
        }
        if (hopDelta != null && abs(hopDelta) >= 3) {
            summaryParts += if (hopDelta > 0) "跳数增加 +$hopDelta 跳" else "跳数减少 $hopDelta 跳"
        }
        if (summaryParts.isEmpty()) {
            summaryParts += "路由基本一致"
        }

        val trend = when {
            detourIntroduced || backboneDowngraded || realLossIntroduced || (lossDeltaPercent != null && lossDeltaPercent >= 5.0) ->
                MetricTrend.DEGRADED_SEVERE
            (latencyDeltaMs != null && latencyDeltaMs >= 30.0) || (lossDeltaPercent != null && lossDeltaPercent >= 1.5) ->
                MetricTrend.DEGRADED_WARN
            backboneUpgraded || (latencyDeltaMs != null && latencyDeltaMs <= -20.0) ->
                MetricTrend.IMPROVED
            else -> MetricTrend.UNCHANGED
        }

        return RoutingShiftDiff(
            directionLabel = directionLabel,
            beforeRoute = beforeRoute,
            afterRoute = afterRoute,
            beforeBackbones = beforeTags,
            afterBackbones = afterTags,
            beforeDetour = beforeDetour,
            afterDetour = afterDetour,
            detourIntroduced = detourIntroduced,
            backboneDowngraded = backboneDowngraded,
            hopDelta = hopDelta,
            latencyDeltaMs = latencyDeltaMs,
            lossDeltaPercent = lossDeltaPercent,
            shiftSummary = summaryParts.joinToString("；"),
            trend = trend,
        )
    }

    internal fun isBackboneDowngraded(
        beforeTags: List<BackboneRouteTag>,
        afterTags: List<BackboneRouteTag>,
    ): Boolean {
        if (beforeTags.isEmpty()) return false
        val beforeSet = beforeTags.toSet()
        val afterSet = afterTags.toSet()
        for ((premium, degradedSet) in PREMIUM_DOWNGRADE_PAIRS) {
            if (premium in beforeSet && premium !in afterSet && afterSet.any { it in degradedSet }) {
                return true
            }
        }
        val beforeHasPremium = beforeSet.any { it.isPremium }
        val afterHasPremium = afterSet.any { it.isPremium }
        return beforeHasPremium && !afterHasPremium
    }

    private fun formatTagList(tags: List<BackboneRouteTag>): String =
        if (tags.isEmpty()) "常规路由" else tags.joinToString(" + ") { it.displayLabel }

    private fun buildHighlightBullets(
        baseline: BenchmarkBaselineSnapshot,
        current: BenchmarkBaselineSnapshot,
        bandwidthDeltas: List<BandwidthDelta>,
        latencyDeltas: List<LatencyDelta>,
        streamingDiffs: List<StreamingUnlockDiff>,
        routingShifts: List<RoutingShiftDiff>,
        ipOriginChanged: Boolean,
    ): List<String> {
        val bullets = mutableListOf<String>()

        for (bw in bandwidthDeltas) {
            if (bw.trend == MetricTrend.DEGRADED_SEVERE || bw.trend == MetricTrend.DEGRADED_WARN) {
                val pctStr = bw.deltaPercent?.let { String.format(Locale.US, "%.0f%%", it) }.orEmpty()
                val prefix = if (bw.trend == MetricTrend.DEGRADED_SEVERE) "带宽大幅缩水" else "带宽回落"
                bullets += "$prefix：${bw.metricLabel} ${formatMbps(bw.beforeMbps)} → ${formatMbps(bw.afterMbps)} ($pctStr)"
            }
        }

        for (shift in routingShifts) {
            if (shift.detourIntroduced || shift.backboneDowngraded || shift.trend == MetricTrend.DEGRADED_SEVERE) {
                bullets += "路由变化（${shift.directionLabel}）：${shift.shiftSummary}"
            }
        }

        for (unlock in streamingDiffs) {
            when (unlock.changeType) {
                StreamingChangeType.LOST_UNLOCK ->
                    bullets += "流媒体掉解锁：${unlock.displayName} (${formatUnlock(unlock.beforeStatus, unlock.beforeRegion)} → ${unlock.afterStatus.label})"
                StreamingChangeType.DOWNGRADED_TO_ORIGINALS ->
                    bullets += "流媒体降级：${unlock.displayName} (${formatUnlock(unlock.beforeStatus, unlock.beforeRegion)} → 仅自制剧)"
                StreamingChangeType.REGION_CHANGED ->
                    bullets += "解锁区变更：${unlock.displayName} (${unlock.beforeRegion ?: "?"} → ${unlock.afterRegion ?: "?"})"
                StreamingChangeType.GAINED_UNLOCK ->
                    bullets += "新增流媒体解锁：${unlock.displayName} (${formatUnlock(unlock.afterStatus, unlock.afterRegion)})"
                StreamingChangeType.UNCHANGED -> Unit
            }
        }

        for (lat in latencyDeltas) {
            if (lat.trend == MetricTrend.DEGRADED_SEVERE || lat.trend == MetricTrend.DEGRADED_WARN) {
                val bText = formatMetric(lat.beforeValue, lat.unit)
                val aText = formatMetric(lat.afterValue, lat.unit)
                val dVal = lat.deltaValue?.let { String.format(Locale.US, "+%.1f%s", it, lat.unit) }.orEmpty()
                bullets += "${lat.metricLabel}劣化：$bText → $aText ($dVal)"
            }
        }

        if (ipOriginChanged) {
            val bLabel = baseline.ipMetrics?.originType?.label ?: "未知"
            val aLabel = current.ipMetrics?.originType?.label ?: "未知"
            bullets += "IP 属性变化：$bLabel → $aLabel"
        }

        if (bullets.isEmpty()) {
            val improvedBw = bandwidthDeltas.firstOrNull { it.trend == MetricTrend.IMPROVED }
            if (improvedBw != null) {
                val pctStr = improvedBw.deltaPercent?.let { String.format(Locale.US, "+%.0f%%", it) }.orEmpty()
                bullets += "性能提升：${improvedBw.metricLabel} ${formatMbps(improvedBw.beforeMbps)} → ${formatMbps(improvedBw.afterMbps)} ($pctStr)"
            } else {
                bullets += "核心带宽、延迟与线路表现与购入基线基本一致"
            }
        }

        return bullets.distinct()
    }

    private fun resolveOverallVerdict(
        bandwidthDeltas: List<BandwidthDelta>,
        latencyDeltas: List<LatencyDelta>,
        streamingDiffs: List<StreamingUnlockDiff>,
        routingShifts: List<RoutingShiftDiff>,
        ipOriginChanged: Boolean,
        beforeOrigin: IpOriginType,
        afterOrigin: IpOriginType,
    ): BaselineOverallVerdict {
        val hasSevere = bandwidthDeltas.any { it.trend == MetricTrend.DEGRADED_SEVERE } ||
            latencyDeltas.any { it.trend == MetricTrend.DEGRADED_SEVERE } ||
            routingShifts.any { it.trend == MetricTrend.DEGRADED_SEVERE || it.detourIntroduced || it.backboneDowngraded } ||
            streamingDiffs.any {
                it.changeType == StreamingChangeType.LOST_UNLOCK ||
                    it.changeType == StreamingChangeType.DOWNGRADED_TO_ORIGINALS
            } ||
            (ipOriginChanged && beforeOrigin == IpOriginType.NATIVE && afterOrigin == IpOriginType.BROADCAST)

        if (hasSevere) return BaselineOverallVerdict.SIGNIFICANT_DEGRADATION

        val hasWarn = bandwidthDeltas.any { it.trend == MetricTrend.DEGRADED_WARN } ||
            latencyDeltas.any { it.trend == MetricTrend.DEGRADED_WARN } ||
            routingShifts.any { it.trend == MetricTrend.DEGRADED_WARN } ||
            streamingDiffs.any { it.changeType == StreamingChangeType.REGION_CHANGED } ||
            ipOriginChanged

        if (hasWarn) return BaselineOverallVerdict.MINOR_REGRESSION

        val hasImprovement = bandwidthDeltas.any { it.trend == MetricTrend.IMPROVED } ||
            latencyDeltas.any { it.trend == MetricTrend.IMPROVED } ||
            routingShifts.any { it.trend == MetricTrend.IMPROVED } ||
            streamingDiffs.any { it.changeType == StreamingChangeType.GAINED_UNLOCK }

        if (hasImprovement) return BaselineOverallVerdict.IMPROVED

        return BaselineOverallVerdict.STABLE
    }

    private fun formatMbps(v: Double?): String =
        if (v == null) "-" else String.format(Locale.US, "%.1f Mbps", v)

    private fun formatMetric(v: Double?, unit: String): String =
        if (v == null) "-" else String.format(Locale.US, "%.1f %s", v, unit)

    private fun formatUnlock(status: StreamingUnlockStatus, region: String?): String =
        if (region.isNullOrBlank()) status.label else "${status.label} ($region)"
}
