package com.nodeseek.app.domain.baseline

import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import kotlinx.serialization.Serializable

/**
 * Origin pipeline that produced a [BenchmarkBaselineSnapshot].
 */
@Serializable
enum class BaselineSourceType(val label: String) {
    MOBILE_NETBENCH("手机端测机"),
    SSH_BENCHMARK("SSH 测评"),
    COMBINED("综合基线"),
}

/**
 * Streaming / AI platform unlock status captured from NodeQuality, GoECS, RegionRestrictionCheck, etc.
 */
@Serializable
enum class StreamingUnlockStatus(val label: String) {
    UNLOCKED("原生解锁"),
    ORIGINALS_ONLY("仅自制剧"),
    LOCKED("未解锁"),
    UNKNOWN("未知"),
}

@Serializable
data class StreamingPlatformResult(
    val platformKey: String,
    val displayName: String,
    val status: StreamingUnlockStatus,
    val regionCode: String? = null,
    val rawNote: String? = null,
)

@Serializable
data class SpeedNodeTelemetry(
    val nodeName: String,
    val direction: String = "BOTH",
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val latencyMs: Double? = null,
)

@Serializable
data class BaselineBandwidthMetrics(
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val peakDownloadMbps: Double? = null,
    val peakUploadMbps: Double? = null,
    val iperf3Retransmits: Long? = null,
    val iperf3StabilityPercent: Double? = null,
    val diskReadMbPerSec: Double? = null,
    val diskWriteMbPerSec: Double? = null,
    val geekbenchSingle: Int? = null,
    val geekbenchMulti: Int? = null,
    val speedNodes: List<SpeedNodeTelemetry> = emptyList(),
)

@Serializable
data class BaselineLatencyMetrics(
    val icmpAvgMs: Double? = null,
    val icmpMinMs: Double? = null,
    val icmpMaxMs: Double? = null,
    val icmpJitterMs: Double? = null,
    val icmpLossPercent: Double? = null,
    val tcpAvgMs: Double? = null,
    val tcpLossPercent: Double? = null,
    val httpTtfbMs: Double? = null,
)

@Serializable
data class BaselineRoutingMetrics(
    val forwardRouteSignature: String? = null,
    val forwardBackboneTags: List<BackboneRouteTag> = emptyList(),
    val forwardHopCount: Int? = null,
    val forwardTerminalMs: Double? = null,
    val forwardPeakHopLossPercent: Double? = null,
    val forwardHasRealPacketLoss: Boolean? = null,
    val reverseRouteSignature: String? = null,
    val reverseBackboneTags: List<BackboneRouteTag> = emptyList(),
    val reverseHopCount: Int? = null,
    val reverseTerminalMs: Double? = null,
    val reversePeakHopLossPercent: Double? = null,
    val reverseHasRealPacketLoss: Boolean? = null,
    val threeNetBackrouteSummary: Map<String, String> = emptyMap(),
    val isDetourSuspected: Boolean = false,
    val detourReason: String? = null,
)

@Serializable
data class BaselineIpMetrics(
    val ipFamily: IpFamily = IpFamily.UNKNOWN,
    val originType: IpOriginType = IpOriginType.UNKNOWN,
    val usageType: IpUsageType = IpUsageType.UNKNOWN,
    val countryCode: String? = null,
    val asn: String? = null,
    val ispOrOrg: String? = null,
)

/**
 * Structured purchase baseline snapshot persisted in `filesDir/benchmark_baselines.json`
 * and backed up in `NodePulseFullBackupBundle`.
 */
@Serializable
data class BenchmarkBaselineSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val label: String,
    val targetKey: String,
    val serverId: String? = null,
    val hostDisplay: String,
    val resolvedIp: String? = null,
    val sourceType: BaselineSourceType,
    val presetIds: List<String> = emptyList(),
    val capturedAtEpochMillis: Long,
    val notes: String = "",
    val isPeakHourWindow: Boolean? = null,
    val bandwidth: BaselineBandwidthMetrics = BaselineBandwidthMetrics(),
    val latency: BaselineLatencyMetrics = BaselineLatencyMetrics(),
    val routing: BaselineRoutingMetrics = BaselineRoutingMetrics(),
    val ipMetrics: BaselineIpMetrics? = null,
    val streamingUnlocks: List<StreamingPlatformResult> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

enum class MetricTrend {
    IMPROVED,
    UNCHANGED,
    DEGRADED_WARN,
    DEGRADED_SEVERE,
    INFO,
}

enum class BaselineOverallVerdict(val label: String) {
    IMPROVED("线路/性能提升"),
    STABLE("指标基本稳定"),
    MINOR_REGRESSION("轻度指标波动"),
    SIGNIFICANT_DEGRADATION("明显劣化 / 建议核查"),
}

data class BandwidthDelta(
    val metricLabel: String,
    val beforeMbps: Double?,
    val afterMbps: Double?,
    val deltaMbps: Double?,
    val deltaPercent: Double?,
    val trend: MetricTrend,
)

data class LatencyDelta(
    val metricLabel: String,
    val beforeValue: Double?,
    val afterValue: Double?,
    val deltaValue: Double?,
    val unit: String,
    val trend: MetricTrend,
)

enum class StreamingChangeType(val label: String) {
    LOST_UNLOCK("掉解锁"),
    DOWNGRADED_TO_ORIGINALS("降级自制剧"),
    REGION_CHANGED("解锁区域变化"),
    GAINED_UNLOCK("新增解锁"),
    UNCHANGED("保持不变"),
}

data class StreamingUnlockDiff(
    val platformKey: String,
    val displayName: String,
    val beforeStatus: StreamingUnlockStatus,
    val beforeRegion: String?,
    val afterStatus: StreamingUnlockStatus,
    val afterRegion: String?,
    val changeType: StreamingChangeType,
)

data class RoutingShiftDiff(
    val directionLabel: String,
    val beforeRoute: String?,
    val afterRoute: String?,
    val beforeBackbones: List<BackboneRouteTag>,
    val afterBackbones: List<BackboneRouteTag>,
    val beforeDetour: Boolean,
    val afterDetour: Boolean,
    val detourIntroduced: Boolean,
    val backboneDowngraded: Boolean,
    val hopDelta: Int?,
    val latencyDeltaMs: Double?,
    val lossDeltaPercent: Double?,
    val shiftSummary: String,
    val trend: MetricTrend,
)

data class BaselineDiffReport(
    val baseline: BenchmarkBaselineSnapshot,
    val current: BenchmarkBaselineSnapshot,
    val overallVerdict: BaselineOverallVerdict,
    val elapsedDays: Long,
    val bandwidthDeltas: List<BandwidthDelta>,
    val latencyDeltas: List<LatencyDelta>,
    val streamingDiffs: List<StreamingUnlockDiff>,
    val routingShifts: List<RoutingShiftDiff>,
    val ipOriginChanged: Boolean,
    val highlightBullets: List<String>,
)
