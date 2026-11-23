package com.nodeseek.app.domain.netbench

import kotlinx.serialization.Serializable

/**
 * IP address family resolved for a test target or detected for the local egress network.
 */
@Serializable
enum class IpFamily(val label: String) {
    IPV4("IPv4"),
    IPV6("IPv6"),
    UNKNOWN("未知"),
}

/**
 * Preferred IP family when resolving dual-stack hostnames.
 */
enum class PreferredIpFamily(val label: String) {
    AUTO("自动"),
    IPV4_ONLY("仅 IPv4"),
    IPV6_ONLY("仅 IPv6"),
}

/**
 * Parsed raw user input supporting IPv4, raw/bracketed IPv6, domain names, and pasted URLs.
 */
data class ParsedTarget(
    val rawInput: String,
    val host: String,
    val explicitPort: Int? = null,
    val isLiteralIp: Boolean = false,
    val literalFamily: IpFamily = IpFamily.UNKNOWN,
    val suggestedHttpScheme: String? = null,
) {
    fun effectivePort(defaultPort: Int): Int = explicitPort ?: defaultPort

    fun formattedEndpoint(port: Int): String = when (literalFamily) {
        IpFamily.IPV6 -> "[$host]:$port"
        else -> "$host:$port"
    }
}

/**
 * Target after DNS resolution on `Dispatchers.IO`.
 */
data class ResolvedTarget(
    val displayHost: String,
    val resolvedIp: String,
    val ipFamily: IpFamily,
    val allResolvedIps: List<String> = listOf(resolvedIp),
    val dnsDurationMs: Double = 0.0,
) {
    val isDomain: Boolean
        get() = !displayHost.equals(resolvedIp, ignoreCase = true) &&
            NetBenchTargetParser.detectIpFamily(displayHost) == IpFamily.UNKNOWN

    fun formatAddressWithPort(port: Int, maskedIp: String? = null): String {
        val ip = maskedIp ?: resolvedIp
        return if (ipFamily == IpFamily.IPV6) "[$ip]:$port" else "$ip:$port"
    }
}

/**
 * Preset TCP ports commonly probed when benchmarking VPS nodes.
 */
enum class TcpPortPreset(val port: Int, val label: String) {
    SSH(22, "22 SSH"),
    HTTP(80, "80 HTTP"),
    HTTPS(443, "443 HTTPS"),
    IPERF3(5201, "5201 iperf3"),
    ALT_HTTPS(8443, "8443"),
    ;

    companion object {
        val ALL_PORTS: List<Int> = entries.map { it.port }
    }
}

/**
 * How the ICMP ping engine handled OS network routing/binding.
 */
enum class IcmpNetworkBindingMode(val label: String) {
    /** System default route (used when no VPN/proxy is active, or when proxy test is chosen). */
    SYSTEM_DEFAULT("系统默认路由"),

    /** Kernel unprivileged ICMP Echo socket bound directly to the underlying physical [android.net.Network] via `Network.bindSocket(fd)`. */
    PHYSICAL_NETWORK_BOUND("已绑定底层物理网络（绕过代理）"),

    /** Bound to the underlying physical interface via `-I <iface>`. */
    INTERFACE_BOUND("已指定底层网卡"),

    /** Direct non-VPN mode was requested, but the ping subprocess could not bind to the physical network. */
    UNBOUND_SUBPROCESS_FALLBACK("进程未绑定底层网卡（跟随系统路由）"),
}

/**
 * Configuration for [com.nodeseek.app.data.netbench.IcmpPingRunner].
 */
data class IcmpPingConfig(
    val target: String,
    val probeCount: Int = DEFAULT_PROBE_COUNT,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val preferredFamily: PreferredIpFamily = PreferredIpFamily.AUTO,
    val useProxy: Boolean = false,
) {
    val normalizedProbeCount: Int
        get() = probeCount.coerceIn(MIN_PROBE_COUNT, MAX_PROBE_COUNT)

    val normalizedTimeoutMs: Int
        get() = timeoutMs.coerceIn(200, 15_000)

    val normalizedTimeoutSec: Int
        get() = ((timeoutMs + 999) / 1000).coerceIn(1, 15)

    val normalizedIntervalMs: Long
        get() = intervalMs.coerceIn(200L, 5_000L)

    companion object {
        const val DEFAULT_PROBE_COUNT = 10
        const val MIN_PROBE_COUNT = 4
        const val MAX_PROBE_COUNT = 50
        const val DEFAULT_TIMEOUT_MS = 2_000
        const val DEFAULT_INTERVAL_MS = 250L
    }
}

/**
 * Result of a single ICMP Ping probe packet.
 */
data class IcmpPingSample(
    val seq: Int,
    val success: Boolean,
    val rttMs: Double? = null,
    val ttl: Int? = null,
    val errorMessage: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Aggregated ICMP Ping statistics (`min / avg / max / mdev`, packet loss, and binding status).
 */
data class IcmpPingSummary(
    val resolvedTarget: ResolvedTarget?,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val jitterMdevMs: Double?,
    val samples: List<IcmpPingSample> = emptyList(),
    val bindingMode: IcmpNetworkBindingMode = IcmpNetworkBindingMode.SYSTEM_DEFAULT,
    val boundInterfaceName: String? = null,
    val networkBindingNote: String? = null,
    val errorMessage: String? = null,
) {
    val lost: Int get() = (sent - received).coerceAtLeast(0)
    val hasValidLatency: Boolean get() = received > 0 && avgMs != null
}

/**
 * Real-time progress event emitted by [com.nodeseek.app.data.netbench.IcmpPingRunner].
 */
data class IcmpPingProgress(
    val resolvedTarget: ResolvedTarget?,
    val totalPlanned: Int,
    val latestSample: IcmpPingSample?,
    val samples: List<IcmpPingSample>,
    val summary: IcmpPingSummary,
    val isFinished: Boolean,
    val errorMessage: String? = null,
) {
    val completedFraction: Float
        get() = if (totalPlanned <= 0) 0f else (samples.size.toFloat() / totalPlanned.toFloat()).coerceIn(0f, 1f)
}

/**
 * Configuration for [com.nodeseek.app.data.netbench.TcpPingRunner].
 */
data class TcpPingConfig(
    val target: String,
    val port: Int = DEFAULT_PORT,
    val probeCount: Int = DEFAULT_PROBE_COUNT,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val preferredFamily: PreferredIpFamily = PreferredIpFamily.AUTO,
    val useProxy: Boolean = false,
) {
    val normalizedProbeCount: Int
        get() = probeCount.coerceIn(MIN_PROBE_COUNT, MAX_PROBE_COUNT)

    val normalizedPort: Int
        get() = port.coerceIn(1, 65535)

    val normalizedTimeoutMs: Int
        get() = timeoutMs.coerceIn(200, 15_000)

    val normalizedIntervalMs: Long
        get() = intervalMs.coerceIn(0L, 5_000L)

    companion object {
        const val DEFAULT_PORT = 443
        const val DEFAULT_PROBE_COUNT = 10
        const val MIN_PROBE_COUNT = 4
        const val MAX_PROBE_COUNT = 50
        const val DEFAULT_TIMEOUT_MS = 2500
        const val DEFAULT_INTERVAL_MS = 250L
    }
}

/**
 * Standardized public-safe Chinese error categories for NetBench probes.
 *
 * SECURITY / PRIVACY: Public reports (Markdown and uploaded NodeImage PNG cards) MUST display
 * [publicLabel] rather than raw Java/OkHttp socket exception messages, which frequently embed
 * target `host/ip:port` or local socket endpoints.
 */
@Serializable
enum class NetBenchErrorCategory(val publicLabel: String) {
    INVALID_TARGET("目标格式无效"),
    DNS_FAILED("DNS 解析失败"),
    CONNECTION_REFUSED("连接被拒绝"),
    CONNECTION_TIMEOUT("连接超时"),
    TLS_HANDSHAKE_FAILED("TLS 握手失败"),
    NETWORK_UNREACHABLE("网络不可达"),
    CONNECTION_RESET("连接已重置"),
    VPN_OR_PERMISSION_RESTRICTED("网络权限受限"),
    SERVER_BUSY("服务端正忙"),
    PROTOCOL_OR_SERVER_ERROR("协议或服务端异常"),
    SERVICE_UNAVAILABLE("查询服务暂不可用"),
    REQUEST_FAILED("请求失败"),
}

/**
 * Result of a single TCPing probe sequence.
 *
 * [errorMessage] / [errorCategory] hold public-safe standardized error labels, while
 * [rawDiagnostic] is strictly for local-only inspection and MUST NEVER be exported.
 */
data class TcpPingSample(
    val seq: Int,
    val success: Boolean,
    val rttMs: Double? = null,
    val errorMessage: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val errorCategory: NetBenchErrorCategory? = null,
    val rawDiagnostic: String? = null,
)

/**
 * Aggregated TCPing statistics matching Linux ping `min / avg / max / mdev`.
 */
data class TcpPingSummary(
    val resolvedTarget: ResolvedTarget?,
    val port: Int,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val jitterMdevMs: Double?,
    val samples: List<TcpPingSample> = emptyList(),
    val errorMessage: String? = null,
    val errorCategory: NetBenchErrorCategory? = null,
    val rawDiagnostic: String? = null,
) {
    val lost: Int get() = (sent - received).coerceAtLeast(0)
    val hasValidLatency: Boolean get() = received > 0 && avgMs != null
}

/**
 * Real-time progress event emitted by [com.nodeseek.app.data.netbench.TcpPingRunner].
 */
data class TcpPingProgress(
    val resolvedTarget: ResolvedTarget?,
    val port: Int,
    val totalPlanned: Int,
    val latestSample: TcpPingSample?,
    val samples: List<TcpPingSample>,
    val summary: TcpPingSummary,
    val isFinished: Boolean,
    val errorMessage: String? = null,
    val errorCategory: NetBenchErrorCategory? = null,
    val rawDiagnostic: String? = null,
) {
    val completedFraction: Float
        get() = if (totalPlanned <= 0) 0f else (samples.size.toFloat() / totalPlanned.toFloat()).coerceIn(0f, 1f)
}

/**
 * Result of an optional HTTP/HTTPS latency probe (DNS + TCP + TLS + TTFB).
 *
 * SECURITY / PRIVACY:
 * - [errorCategory] and [errorMessage] carry clean standardized Chinese categories safe for public display.
 * - [rawDiagnostic] preserves the underlying OkHttp/socket exception detail strictly for local-only UI inspection
 *   and MUST NEVER be written into Markdown reports or uploaded card images.
 */
data class HttpLatencyResult(
    val url: String,
    val statusCode: Int? = null,
    val totalMs: Double? = null,
    val ttfbMs: Double? = null,
    val tlsHandshakeMs: Double? = null,
    val protocol: String? = null,
    val success: Boolean = false,
    val errorMessage: String? = null,
    val errorCategory: NetBenchErrorCategory? = null,
    val rawDiagnostic: String? = null,
) {
    val publicErrorLabel: String?
        get() = if (success) null else (errorCategory?.publicLabel ?: errorMessage)

    val localDiagnosticMessage: String?
        get() {
            if (success) return null
            val primary = errorCategory?.publicLabel ?: errorMessage
            val raw = rawDiagnostic?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                primary.isNullOrBlank() -> raw
                raw == null || raw.equals(primary, ignoreCase = true) -> primary
                else -> "$primary（$raw）"
            }
        }
}

/**
 * Single transfer direction for iperf3 TCP protocol.
 */
enum class Iperf3Direction(val label: String, val flagLabel: String) {
    DOWNLOAD("下载", "-R 下载"),
    UPLOAD("上传", "上传"),
}

/**
 * User-selectable iperf3 test mode in UI.
 */
enum class Iperf3TestMode(val label: String) {
    DOWNLOAD("下载 (-R)"),
    UPLOAD("上传"),
    BOTH("双向 (下+上)"),
}

/**
 * Configuration for [com.nodeseek.app.data.netbench.Iperf3ClientRunner].
 */
data class Iperf3Config(
    val target: String,
    val port: Int = DEFAULT_PORT,
    val durationSec: Int = DEFAULT_DURATION_SEC,
    val direction: Iperf3Direction = Iperf3Direction.DOWNLOAD,
    val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val preferredFamily: PreferredIpFamily = PreferredIpFamily.AUTO,
    val useProxy: Boolean = false,
    val parallelStreams: Int = DEFAULT_PARALLEL_STREAMS,
    /**
     * Warm-up seconds excluded from statistics (official `iperf3 -O`). The test runs for
     * `omitSec + durationSec` seconds in total; the first [omitSec] seconds (TCP slow start)
     * are reported as `omitted` intervals and do not count toward averages.
     */
    val omitSec: Int = 0,
) {
    val normalizedPort: Int get() = port.coerceIn(1, 65535)
    val normalizedDurationSec: Int get() = durationSec.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
    val normalizedOmitSec: Int get() = omitSec.coerceIn(0, MAX_OMIT_SEC)
    val normalizedChunkSize: Int get() = chunkSizeBytes.coerceIn(4096, 1024 * 1024)
    val normalizedParallelStreams: Int get() = parallelStreams.coerceIn(MIN_PARALLEL_STREAMS, MAX_PARALLEL_STREAMS)

    companion object {
        const val DEFAULT_PORT = 5201
        /** Matches the official `iperf3 -t` default so phone and desktop results are comparable. */
        const val DEFAULT_DURATION_SEC = 10
        const val MIN_DURATION_SEC = 1
        const val MAX_DURATION_SEC = 60
        val DURATION_PRESETS = listOf(5, 10, 30)

        /** UI default warm-up exclusion (`-O 1`) that drops the TCP slow-start second. */
        const val DEFAULT_UI_OMIT_SEC = 1
        const val MAX_OMIT_SEC = 5
        val OMIT_PRESETS = listOf(0, 1, 2)
        const val DEFAULT_CHUNK_SIZE = 131_072 // 128 KiB
        const val DEFAULT_CONNECT_TIMEOUT_MS = 4_000
        const val DEFAULT_PARALLEL_STREAMS = 1
        const val MIN_PARALLEL_STREAMS = 1
        const val MAX_PARALLEL_STREAMS = 16
        val PARALLEL_STREAM_PRESETS = listOf(1, 2, 4, 8)
    }
}

/**
 * Protocol stage reported during native pure-Kotlin iperf3 execution.
 */
enum class Iperf3Stage(val label: String) {
    CONNECTING("连接服务端"),
    PARAM_EXCHANGE("协商测速参数"),
    CREATE_STREAMS("建立数据通道"),
    TEST_START("准备开始"),
    TEST_RUNNING("正在测速"),
    TEST_END("结束传输"),
    EXCHANGE_RESULTS("交换统计结果"),
    COMPLETED("测速完成"),
    FAILED("测速失败"),
}

/**
 * Structured error taxonomy for iperf3 client failures.
 */
enum class Iperf3ErrorKind {
    ACCESS_DENIED,
    SERVER_ERROR,
    CONNECTION_REFUSED,
    TIMEOUT,
    DNS_ERROR,
    PROTOCOL_ERROR,
    IO_ERROR,
}

/**
 * Per-second (or final tail) throughput sample recorded during an iperf3 transfer.
 */
data class Iperf3IntervalSample(
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val bytesTransferred: Long,
    val bitrateMbps: Double,
    val streams: Int = Iperf3Config.DEFAULT_PARALLEL_STREAMS,
    val omitted: Boolean = false,
) {
    val durationSec: Double
        get() = (endSec - startSec).coerceAtLeast(0.0)

    val intervalLabel: String
        get() = String.format(
            java.util.Locale.US,
            "%.2f-%.2fs",
            startSec.coerceAtLeast(0.0),
            endSec.coerceAtLeast(startSec.coerceAtLeast(0.0)),
        )
}

/**
 * Final outcome of a single iperf3 direction test.
 */
data class Iperf3Result(
    val direction: Iperf3Direction,
    val port: Int,
    val resolvedTarget: ResolvedTarget?,
    val durationSec: Double,
    val bytesTransferred: Long,
    val clientMbps: Double,
    val serverMbps: Double? = null,
    val peakMbps: Double = clientMbps,
    val retransmits: Long? = null,
    val success: Boolean,
    val errorKind: Iperf3ErrorKind? = null,
    val errorMessage: String? = null,
    val parallelStreams: Int = Iperf3Config.DEFAULT_PARALLEL_STREAMS,
    val intervals: List<Iperf3IntervalSample> = emptyList(),
    /** Warm-up seconds actually excluded from [durationSec] / averages (`iperf3 -O`). */
    val omitSec: Int = 0,
    /** Server-reported total bytes from `EXCHANGE_RESULTS` (sender on download, receiver on upload). */
    val serverBytesTransferred: Long? = null,
    /** Server-reported measurement duration in seconds from `EXCHANGE_RESULTS`. */
    val serverDurationSec: Double? = null,
) {
    /**
     * Best-available throughput in Mbps, always taken from the **receiver** side (like the official
     * iperf3 `receiver` summary line):
     * - Download (`-R`): the phone is the receiver -> [clientMbps].
     * - Upload: the VPS is the receiver -> [serverMbps]. The client-side figure counts bytes written
     *   into the local socket send buffer (not yet delivered), which overstates upload throughput.
     * Falls back to the other side when the preferred figure is unavailable.
     */
    val effectiveMbps: Double
        get() = when (direction) {
            Iperf3Direction.UPLOAD -> serverMbps?.takeIf { it > 0.0 } ?: clientMbps.coerceAtLeast(0.0)
            else -> if (clientMbps > 0.0) clientMbps else (serverMbps ?: 0.0)
        }

    /**
     * Minimum non-omitted per-interval bitrate in Mbps (if interval samples exist).
     */
    val minIntervalMbps: Double?
        get() = intervals.filter { !it.omitted && it.durationSec > 0.0 }
            .minOfOrNull { it.bitrateMbps }

    /**
     * Maximum bitrate in Mbps across [peakMbps] and recorded intervals.
     */
    val maxIntervalMbps: Double
        get() = maxOf(
            peakMbps,
            intervals.filter { !it.omitted && it.durationSec > 0.0 }
                .maxOfOrNull { it.bitrateMbps } ?: 0.0,
        )

    /**
     * Population standard deviation of per-interval bitrates in Mbps when at least 2 intervals exist.
     */
    val intervalStdDevMbps: Double?
        get() {
            val valid = intervals.filter { !it.omitted && it.durationSec > 0.0 }
            if (valid.size < 2) return null
            val mean = valid.sumOf { it.bitrateMbps } / valid.size.toDouble()
            val variance = valid.sumOf { s ->
                val diff = s.bitrateMbps - mean
                diff * diff
            } / valid.size.toDouble()
            return kotlin.math.sqrt(variance.coerceAtLeast(0.0))
        }

    /**
     * Throughput stability score in `[0.0, 100.0]` derived from `1 - CV` (coefficient of variation).
     */
    val stabilityPercent: Double?
        get() {
            val std = intervalStdDevMbps ?: return null
            val valid = intervals.filter { !it.omitted && it.durationSec > 0.0 }
            val mean = valid.sumOf { it.bitrateMbps } / valid.size.toDouble()
            if (mean <= 0.0) return null
            return ((1.0 - (std / mean)).coerceIn(0.0, 1.0)) * 100.0
        }
}

/**
 * Live progress emitted every ~500ms while running iperf3.
 */
data class Iperf3Progress(
    val direction: Iperf3Direction,
    val stage: Iperf3Stage,
    val elapsedSec: Double,
    val targetDurationSec: Int,
    val bytesTransferred: Long,
    val instantMbps: Double,
    val averageMbps: Double,
    val peakMbps: Double,
    val throughputHistoryMbps: List<Double> = emptyList(),
    val intervals: List<Iperf3IntervalSample> = emptyList(),
    val finalResult: Iperf3Result? = null,
    val errorKind: Iperf3ErrorKind? = null,
    val errorMessage: String? = null,
    val parallelStreams: Int = Iperf3Config.DEFAULT_PARALLEL_STREAMS,
    /** `true` while the warm-up (`-O`) seconds are running and samples are not yet counted. */
    val inOmitPhase: Boolean = false,
) {
    val progressFraction: Float
        get() = if (stage == Iperf3Stage.COMPLETED) {
            1f
        } else if (targetDurationSec <= 0) {
            0f
        } else {
            (elapsedSec / targetDurationSec.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

/**
 * Native vs Broadcast IP classification.
 */
@Serializable
enum class IpOriginType(val label: String, val badgeLabel: String) {
    NATIVE("原生 IP", "原生 IP"),
    BROADCAST("广播 IP", "广播 IP"),
    ANYCAST("Anycast IP", "Anycast"),
    UNKNOWN("待确认", "待确认"),
}

/**
 * Network usage classification.
 */
@Serializable
enum class IpUsageType(val label: String) {
    ISP_RESIDENTIAL_OR_BUSINESS("ISP 家宽/商宽"),
    HOSTING_DATACENTER("Hosting 机房"),
    ANYCAST("Anycast"),
    UNKNOWN("未知属性"),
}

/**
 * Chinese & overseas local client carrier categories.
 */
enum class ClientCarrier(val label: String, val shortLabel: String) {
    CHINA_TELECOM("中国电信", "电信"),
    CHINA_UNICOM("中国联通", "联通"),
    CHINA_MOBILE("中国移动", "移动"),
    CHINA_BROADNET("中国广电", "广电"),
    CHINA_EDUCATION("中国教育网", "教育网"),
    OVERSEAS("海外网络", "海外"),
    UNKNOWN("本机网络", "未知"),
}

/**
 * Route through which a local egress IP check was observed.
 */
enum class EgressRouteKind(val label: String) {
    DIRECT("真实出口"),
    PROXY_FALLBACK("代理出口（回退）"),
    PROXY("代理出口"),
}

/**
 * Local client egress ISP info.
 *
 * SECURITY / PRIVACY: [rawIp] is held ONLY for local masked display and MUST NEVER be exported
 * into public NodeSeek Markdown reports.
 */
data class ClientNetworkInfo(
    val carrier: ClientCarrier,
    val carrierDisplayName: String = carrier.label,
    val ipFamily: IpFamily = IpFamily.IPV4,
    val countryCode: String? = null,
    val regionName: String? = null,
    val asn: String? = null,
    val ispName: String? = null,
    val maskedIp: String? = null,
    val rawIp: String? = null,
    val source: String? = null,
    val routeKind: EgressRouteKind = EgressRouteKind.DIRECT,
    val transportLabel: String? = null,
)

/**
 * Combined real-underlying + proxy/VPN client egress snapshot for simultaneous dual-IP display
 * and explicit fallback diagnostics when VPN blocks non-VPN socket binding.
 */
data class ClientEgressSnapshot(
    val directNetwork: ClientNetworkInfo? = null,
    val proxyNetwork: ClientNetworkInfo? = null,
    val proxyDetected: Boolean = false,
    val isVpnActive: Boolean = false,
    val isSystemHttpProxyActive: Boolean = false,
    val directBindFailed: Boolean = false,
    val directBindFailureReason: DirectBindFailureReason? = null,
    val directBindErrorDetail: String? = null,
    val activeTransportLabel: String? = null,
    val directTransportLabel: String? = null,
    val networkFingerprint: String = "",
) {
    val effectiveDirectOrFallback: ClientNetworkInfo?
        get() = directNetwork ?: proxyNetwork

    val isShowingProxyFallback: Boolean
        get() = proxyDetected && directNetwork == null && proxyNetwork != null

    val hasDualExitIps: Boolean
        get() = proxyDetected && directNetwork != null && proxyNetwork != null

    fun activeNetworkForTest(useProxy: Boolean): ClientNetworkInfo? =
        if (useProxy) proxyNetwork ?: directNetwork else effectiveDirectOrFallback
}

/**
 * Complete target IP intelligence & Native-vs-Broadcast report.
 */
data class IpIntelligenceReport(
    val ip: String,
    val ipFamily: IpFamily,
    val countryCode: String?,
    val countryName: String?,
    val regionName: String?,
    val cityName: String?,
    val asn: String?,
    val orgName: String?,
    val ispName: String?,
    val usageType: IpUsageType,
    val originType: IpOriginType,
    val rirCountryCode: String?,
    val rirAuthority: String?,
    val originEvidence: String,
    val isAnycast: Boolean = false,
    val geoSource: String? = null,
    val whoisSource: String? = null,
    val errorMessage: String? = null,
) {
    val hasCoreInfo: Boolean
        get() = !countryCode.isNullOrBlank() || !asn.isNullOrBlank() || !orgName.isNullOrBlank()
}

/**
 * Selectable NodeSeek Markdown report styles.
 */
enum class NetBenchMarkdownStyle(val label: String, val shortLabel: String) {
    REPLY_CARD("回帖实测卡", "回帖实测卡"),
    FULL_TABLE("完整测机报告", "完整测机报告"),
}

/**
 * Identifiers for visual benchmark cards rendered and uploaded to NodeImage for
 * embedding into [NetBenchMarkdownStyle.FULL_TABLE] reports.
 */
enum class NetBenchReportCardKind(
    val fileSlug: String,
    val altTitle: String,
) {
    IP_INTELLIGENCE(fileSlug = "netbench_ip_intel", altTitle = "IP 归属与原生/广播鉴定"),
    ICMP_PING(fileSlug = "netbench_icmp_ping", altTitle = "Ping 延迟探测波形"),
    TCP_PING(fileSlug = "netbench_tcp_ping", altTitle = "TCPing 延迟探测波形"),
    IPERF3(fileSlug = "netbench_iperf3", altTitle = "原生 iperf3 吞吐测速"),
}

/**
 * Uploaded NodeImage direct URLs mapped to each visual section of the Full Benchmark Report.
 */
data class NetBenchUploadedImages(
    val ipIntelligenceUrl: String? = null,
    val icmpPingUrl: String? = null,
    val tcpPingUrl: String? = null,
    val iperf3Url: String? = null,
) {
    val hasAnyUrl: Boolean
        get() = !ipIntelligenceUrl.isNullOrBlank() ||
            !icmpPingUrl.isNullOrBlank() ||
            !tcpPingUrl.isNullOrBlank() ||
            !iperf3Url.isNullOrBlank()

    val uploadedCount: Int
        get() = listOfNotNull(
            ipIntelligenceUrl?.takeIf { it.isNotBlank() },
            icmpPingUrl?.takeIf { it.isNotBlank() },
            tcpPingUrl?.takeIf { it.isNotBlank() },
            iperf3Url?.takeIf { it.isNotBlank() },
        ).size

    fun urlFor(kind: NetBenchReportCardKind): String? = when (kind) {
        NetBenchReportCardKind.IP_INTELLIGENCE -> ipIntelligenceUrl
        NetBenchReportCardKind.ICMP_PING -> icmpPingUrl
        NetBenchReportCardKind.TCP_PING -> tcpPingUrl
        NetBenchReportCardKind.IPERF3 -> iperf3Url
    }

    fun withUrl(kind: NetBenchReportCardKind, url: String?): NetBenchUploadedImages = when (kind) {
        NetBenchReportCardKind.IP_INTELLIGENCE -> copy(ipIntelligenceUrl = url)
        NetBenchReportCardKind.ICMP_PING -> copy(icmpPingUrl = url)
        NetBenchReportCardKind.TCP_PING -> copy(tcpPingUrl = url)
        NetBenchReportCardKind.IPERF3 -> copy(iperf3Url = url)
    }
}

/**
 * Formatting options for [NetBenchMarkdownFormatter].
 */
data class NetBenchMarkdownOptions(
    val style: NetBenchMarkdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
    val maskTargetIp: Boolean = true,
    val includeClientNetwork: Boolean = true,
    val timestampLabel: String? = null,
    val uploadedImages: NetBenchUploadedImages = NetBenchUploadedImages(),
)

/**
 * Snapshot combining all test results for Markdown report generation.
 */
data class NetBenchReport(
    val targetInput: String,
    val resolvedTarget: ResolvedTarget? = null,
    val tcpPort: Int = TcpPingConfig.DEFAULT_PORT,
    val clientNetwork: ClientNetworkInfo? = null,
    val icmpPingSummary: IcmpPingSummary? = null,
    val tcpPingSummary: TcpPingSummary? = null,
    val httpLatencyResult: HttpLatencyResult? = null,
    val ipIntelligence: IpIntelligenceReport? = null,
    val iperfDownloadResult: Iperf3Result? = null,
    val iperfUploadResult: Iperf3Result? = null,
    val testedViaProxy: Boolean = false,
    val proxyNetwork: ClientNetworkInfo? = null,
) {
    val hasAnyResult: Boolean
        get() = icmpPingSummary != null ||
            tcpPingSummary != null ||
            httpLatencyResult != null ||
            ipIntelligence != null ||
            iperfDownloadResult != null ||
            iperfUploadResult != null
}

/**
 * Persisted recent target entry stored in `filesDir/netbench_history.json`.
 */
@Serializable
data class NetBenchHistoryEntry(
    val host: String,
    val port: Int = TcpPingConfig.DEFAULT_PORT,
    val iperfPort: Int = Iperf3Config.DEFAULT_PORT,
    val resolvedIp: String? = null,
    val ipFamily: IpFamily = IpFamily.UNKNOWN,
    val lastAvgMs: Double? = null,
    val lastPingAvgMs: Double? = null,
    val lastPingLossPercent: Double? = null,
    val lastOriginType: IpOriginType? = null,
    val lastRegionSummary: String? = null,
    val testedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    /**
     * Drops corrupted latency values persisted by older builds (e.g. `745123913967336.8 ms` caused by
     * NAT-mangled ICMP payload timestamps) so they are never rendered in the recent-target chips.
     */
    fun sanitizedMetrics(): NetBenchHistoryEntry {
        fun Double?.saneRtt(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_SANE_HISTORY_RTT_MS }
        val cleanLoss = lastPingLossPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val cleanAvg = lastAvgMs.saneRtt()
        val cleanPing = lastPingAvgMs.saneRtt()
        if (cleanLoss == lastPingLossPercent && cleanAvg == lastAvgMs && cleanPing == lastPingAvgMs) return this
        return copy(lastAvgMs = cleanAvg, lastPingAvgMs = cleanPing, lastPingLossPercent = cleanLoss)
    }

    companion object {
        /** Same ceiling as `IcmpPingParser.MAX_VALID_RTT_MS`. */
        const val MAX_SANE_HISTORY_RTT_MS = 30_000.0
    }
}
