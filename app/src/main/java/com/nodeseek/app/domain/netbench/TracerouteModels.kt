package com.nodeseek.app.domain.netbench

import kotlinx.serialization.Serializable

/**
 * Direction of a visual traceroute / MTR measurement.
 */
@Serializable
enum class TracerouteDirection(val label: String) {
    FORWARD("去程 (手机 → VPS)"),
    REVERSE("回程 (VPS → 手机)"),
}

/**
 * Recognized Chinese & international transit backbone tags along a traceroute path.
 */
@Serializable
enum class BackboneRouteTag(
    val displayLabel: String,
    val isPremium: Boolean = false,
) {
    CN2_GIA("CN2 GIA", isPremium = true),
    CN2_GT("CN2 GT", isPremium = false),
    CU_9929("9929", isPremium = true),
    CMIN2("CMIN2", isPremium = true),
    CU_4837("4837", isPremium = false),
    IIJ("IIJ", isPremium = false),
    SOFTBANK("SoftBank", isPremium = false),
    CT_163("163 骨干", isPremium = false),
    CMI("CMI", isPremium = false),
    CMNET("CMNET", isPremium = false),
    NTT("NTT", isPremium = false),
    PCCW("PCCW", isPremium = false),
    LUMEN("Lumen", isPremium = false),
    TELIA("Arelion", isPremium = false),
    COGENT("Cogent", isPremium = false),
    HE("HE", isPremium = false),
    CLOUDFLARE("Cloudflare", isPremium = false),
}

/**
 * Distinguishes benign intermediate ICMP control-plane rate-limiting from genuine packet loss.
 */
@Serializable
enum class HopLossClassification(val label: String) {
    ZERO_LOSS("零丢包"),
    INTERMEDIATE_RATE_LIMIT("中间跳限速"),
    REAL_PACKET_LOSS("真实链路丢包"),
    TIMEOUT_NO_REPLY("超时无响应"),
}

/**
 * Single hop telemetry aggregated across MTR probe rounds.
 */
@Serializable
data class TracerouteHop(
    val hopNumber: Int,
    val ip: String? = null,
    val hostname: String? = null,
    val asn: String? = null,
    val orgName: String? = null,
    val ispName: String? = null,
    val countryCode: String? = null,
    val countryName: String? = null,
    val regionName: String? = null,
    val cityName: String? = null,
    val sent: Int = 0,
    val received: Int = 0,
    val lossPercent: Double = 0.0,
    val minMs: Double? = null,
    val avgMs: Double? = null,
    val maxMs: Double? = null,
    val p95Ms: Double? = null,
    val jitterMdevMs: Double? = null,
    val backboneTags: List<BackboneRouteTag> = emptyList(),
    val lossClassification: HopLossClassification = HopLossClassification.TIMEOUT_NO_REPLY,
    val isPrivateOrCgnat: Boolean = false,
    val isTargetReached: Boolean = false,
) {
    val isTimeoutOnly: Boolean
        get() = ip == null && received == 0

    val locationSummary: String?
        get() {
            if (isPrivateOrCgnat) return "局域网 / CGNAT"
            if (countryCode.isNullOrBlank() && countryName.isNullOrBlank() && regionName.isNullOrBlank() && cityName.isNullOrBlank()) {
                return null
            }
            return IpClassificationEngine.formatLocationSummary(
                countryCode = countryCode,
                countryName = countryName,
                regionName = regionName,
                cityName = cityName,
            )
        }
}

/**
 * Complete forward or reverse traceroute/MTR summary consumed by UI, Markdown formatter, and M2b baselines.
 */
@Serializable
data class TracerouteSummary(
    val direction: TracerouteDirection,
    val targetInput: String,
    val targetIp: String,
    val ipFamily: IpFamily = IpFamily.UNKNOWN,
    val sourceLabel: String = "",
    val toolName: String = "MTR",
    val bindingMode: IcmpNetworkBindingMode = IcmpNetworkBindingMode.SYSTEM_DEFAULT,
    val boundInterfaceName: String? = null,
    val hops: List<TracerouteHop> = emptyList(),
    val reachedTarget: Boolean = false,
    val totalHops: Int = hops.size,
    val terminalAvgMs: Double? = null,
    val peakHopLossPercent: Double = 0.0,
    val peakLossHopNumber: Int? = null,
    val hasRealPacketLoss: Boolean = false,
    val hasIntermediateRateLimitOnly: Boolean = false,
    val dominantBackboneTags: List<BackboneRouteTag> = emptyList(),
    val routeSignature: String = "",
    val isPeakHourWindow: Boolean = false,
    val timeWindowLabel: String = "",
    val testedAtEpochMillis: Long = 0L,
    val rawOutputSnippet: String? = null,
    val errorMessage: String? = null,
) {
    val hasValidHops: Boolean
        get() = hops.any { !it.isTimeoutOnly }
}

/**
 * Configuration for forward [com.nodeseek.app.data.netbench.TracerouteRunner].
 */
data class TracerouteConfig(
    val target: String,
    val probesPerHop: Int = DEFAULT_PROBES_PER_HOP,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val preferredFamily: PreferredIpFamily = PreferredIpFamily.AUTO,
    val useProxy: Boolean = false,
    val tcpProbePort: Int = TcpPingConfig.DEFAULT_PORT,
) {
    val normalizedProbesPerHop: Int
        get() = probesPerHop.coerceIn(MIN_PROBES_PER_HOP, MAX_PROBES_PER_HOP)

    val normalizedMaxHops: Int
        get() = maxHops.coerceIn(MIN_MAX_HOPS, MAX_MAX_HOPS)

    val normalizedTimeoutMs: Int
        get() = timeoutMs.coerceIn(300, 5_000)

    val normalizedTcpProbePort: Int
        get() = tcpProbePort.coerceIn(1, 65535)

    companion object {
        const val DEFAULT_PROBES_PER_HOP = 5
        const val MIN_PROBES_PER_HOP = 1
        const val MAX_PROBES_PER_HOP = 15
        val PROBE_COUNT_PRESETS = listOf(3, 5, 10)
        const val DEFAULT_MAX_HOPS = 25
        const val MIN_MAX_HOPS = 5
        const val MAX_MAX_HOPS = 32
        const val DEFAULT_TIMEOUT_MS = 1_200
    }
}

/**
 * Live progress emitted by [com.nodeseek.app.data.netbench.TracerouteRunner].
 */
data class TracerouteProgress(
    val resolvedTarget: ResolvedTarget?,
    val currentHop: Int,
    val maxHops: Int,
    val completedRounds: Int,
    val totalRounds: Int,
    val hops: List<TracerouteHop>,
    val summary: TracerouteSummary,
    val isFinished: Boolean,
    val errorMessage: String? = null,
)
