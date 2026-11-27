package com.nodeseek.app.domain.netbench

import com.nodeseek.app.domain.ssh.IpMarkdownStructuralScanner
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Classifies Java/Android/OkHttp exceptions and diagnostic strings into standardized,
 * target-IP-free [NetBenchErrorCategory] values while preserving raw diagnostic text
 * strictly for local-only inspection.
 */
object NetBenchDiagnosticClassifier {

    fun classifyThrowable(
        throwable: Throwable,
        isVpnActive: Boolean = false,
        hasDirectCandidate: Boolean = false,
        useProxy: Boolean = false,
    ): NetBenchErrorCategory {
        if (!useProxy && isVpnActive) {
            val bindReason = NetBenchNetworkSelector.classifyDirectBindFailure(
                throwable = throwable,
                isVpnActive = true,
                hasDirectCandidate = hasDirectCandidate,
            )
            if (bindReason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED ||
                bindReason == DirectBindFailureReason.NO_UNDERLYING_NETWORK
            ) {
                return NetBenchErrorCategory.VPN_OR_PERMISSION_RESTRICTED
            }
        }

        val chain = collectCausalChain(throwable)

        // 1. Exact exception hierarchy checks (most specific root causes first)
        if (chain.any { it is SSLException || it is CertificateException || it is CertPathValidatorException }) {
            return NetBenchErrorCategory.TLS_HANDSHAKE_FAILED
        }
        if (chain.any { it is UnknownHostException }) {
            return NetBenchErrorCategory.DNS_FAILED
        }
        if (chain.any { it is SocketTimeoutException }) {
            return NetBenchErrorCategory.CONNECTION_TIMEOUT
        }
        if (chain.any { it is SecurityException }) {
            return NetBenchErrorCategory.VPN_OR_PERMISSION_RESTRICTED
        }
        if (chain.any { it is NoRouteToHostException }) {
            return NetBenchErrorCategory.NETWORK_UNREACHABLE
        }
        if (chain.any { it is ProtocolException }) {
            return NetBenchErrorCategory.PROTOCOL_OR_SERVER_ERROR
        }
        if (chain.any { it is InterruptedIOException && it.message?.contains("timeout", ignoreCase = true) == true }) {
            return NetBenchErrorCategory.CONNECTION_TIMEOUT
        }

        // 2. Message / errno inspection across causal chain (handles OkHttp wrapped ConnectException / SocketException)
        val combinedMessages = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        classifyFromSignals(combinedMessages)?.let { return it }

        if (chain.any { it is ConnectException }) {
            return NetBenchErrorCategory.CONNECTION_REFUSED
        }
        if (chain.any { it is EOFException }) {
            return NetBenchErrorCategory.CONNECTION_RESET
        }
        if (chain.any { it is IllegalArgumentException }) {
            return NetBenchErrorCategory.INVALID_TARGET
        }
        return NetBenchErrorCategory.REQUEST_FAILED
    }

    fun classifyErrorText(
        rawMessage: String?,
        explicitCategory: NetBenchErrorCategory? = null,
        iperfErrorKind: Iperf3ErrorKind? = null,
        defaultCategory: NetBenchErrorCategory = NetBenchErrorCategory.REQUEST_FAILED,
    ): NetBenchErrorCategory {
        if (explicitCategory != null) return explicitCategory

        if (iperfErrorKind != null) {
            when (iperfErrorKind) {
                Iperf3ErrorKind.ACCESS_DENIED -> return NetBenchErrorCategory.SERVER_BUSY
                Iperf3ErrorKind.SERVER_ERROR,
                Iperf3ErrorKind.PROTOCOL_ERROR,
                -> return NetBenchErrorCategory.PROTOCOL_OR_SERVER_ERROR
                Iperf3ErrorKind.CONNECTION_REFUSED -> return NetBenchErrorCategory.CONNECTION_REFUSED
                Iperf3ErrorKind.TIMEOUT -> return NetBenchErrorCategory.CONNECTION_TIMEOUT
                Iperf3ErrorKind.DNS_ERROR -> return NetBenchErrorCategory.DNS_FAILED
                Iperf3ErrorKind.IO_ERROR -> {
                    // Fall through to inspect message signals before defaulting to REQUEST_FAILED
                }
            }
        }

        val trimmed = rawMessage?.trim().orEmpty()
        if (trimmed.isEmpty()) return defaultCategory

        NetBenchErrorCategory.entries.firstOrNull { it.publicLabel == trimmed }?.let { return it }
        classifyFromSignals(trimmed)?.let { return it }
        return defaultCategory
    }

    fun formatRawDiagnostic(throwable: Throwable): String {
        val primaryName = throwable.javaClass.simpleName.ifEmpty { "Exception" }
        val primaryMsg = throwable.message?.trim().orEmpty()
        val rootCause = generateSequence(throwable.cause) { it.cause }
            .take(5)
            .lastOrNull()
        val base = if (primaryMsg.isEmpty()) {
            primaryName
        } else if (primaryMsg.startsWith(primaryName, ignoreCase = true)) {
            primaryMsg
        } else {
            "$primaryName: $primaryMsg"
        }
        val withCause = if (rootCause != null) {
            val causeName = rootCause.javaClass.simpleName
            val causeMsg = rootCause.message?.trim().orEmpty()
            val causeSummary = if (causeMsg.isNotEmpty()) "$causeName: $causeMsg" else causeName
            if (!base.contains(causeSummary, ignoreCase = true)) "$base (cause: $causeSummary)" else base
        } else {
            base
        }
        return if (withCause.length > 240) withCause.take(239) + "…" else withCause
    }

    private fun collectCausalChain(root: Throwable): List<Throwable> {
        val visited = LinkedHashSet<Throwable>()
        fun visit(node: Throwable?, depth: Int) {
            if (node == null || depth > 8 || !visited.add(node)) return
            visit(node.cause, depth + 1)
            for (sup in node.suppressed) {
                visit(sup, depth + 1)
            }
        }
        visit(root, 0)
        return visited.toList()
    }

    private fun classifyFromSignals(text: String): NetBenchErrorCategory? {
        if (text.isEmpty()) return null
        return when {
            containsAnyIgnoreCase(
                text,
                "EPERM",
                "EACCES",
                "Operation not permitted",
                "Permission denied",
                "SecurityException",
                "经由代理测试",
                "权限受限",
                "无 Root 进程",
                "拒绝绑定网卡",
                "未找到可用的非 VPN",
            ) -> NetBenchErrorCategory.VPN_OR_PERMISSION_RESTRICTED

            containsAnyIgnoreCase(
                text,
                "SSLHandshake",
                "SSLPeerUnverified",
                "SSLProtocol",
                "SSLException",
                "CertPathValidator",
                "CertificateException",
                "Trust anchor",
                "PKIX",
                "Handshake failed",
                "not verified",
                "TLS 握手失败",
                "证书校验",
            ) -> NetBenchErrorCategory.TLS_HANDSHAKE_FAILED

            containsAnyIgnoreCase(
                text,
                "UnknownHost",
                "EAI_NONAME",
                "EAI_NODATA",
                "NXDOMAIN",
                "No address associated",
                "Unable to resolve host",
                "Fake-IP",
                "黑洞拦截",
                "回环劫持",
                "DNS 解析失败",
                "无法解析",
                "未解析到",
                "未返回任何解析记录",
            ) -> NetBenchErrorCategory.DNS_FAILED

            containsAnyIgnoreCase(
                text,
                "目标格式无效",
                "无效未指定地址",
                "IP 地址无效",
                "IP 地址为空",
            ) -> NetBenchErrorCategory.INVALID_TARGET

            containsAnyIgnoreCase(
                text,
                "服务器正忙",
                "服务端正忙",
                "server is busy",
                "ACCESS_DENIED",
            ) -> NetBenchErrorCategory.SERVER_BUSY

            containsAnyIgnoreCase(
                text,
                "SocketTimeout",
                "ETIMEDOUT",
                "timed out",
                "timeout",
                "连接超时",
                "请求超时",
                "响应超时",
                "禁 Ping",
                "丢包/超时",
            ) -> NetBenchErrorCategory.CONNECTION_TIMEOUT

            containsAnyIgnoreCase(
                text,
                "NoRouteToHost",
                "ENETUNREACH",
                "EHOSTUNREACH",
                "ENETDOWN",
                "EHOSTDOWN",
                "EADDRNOTAVAIL",
                "No route to host",
                "Network is unreachable",
                "Host is unreachable",
                "unreachable",
                "不可达",
            ) -> NetBenchErrorCategory.NETWORK_UNREACHABLE

            containsAnyIgnoreCase(
                text,
                "ECONNRESET",
                "EPIPE",
                "ECONNABORTED",
                "Connection reset",
                "Broken pipe",
                "Software caused connection abort",
                "unexpected end of stream",
                "stream was reset",
                "连接已重置",
                "被对端重置",
            ) -> NetBenchErrorCategory.CONNECTION_RESET

            containsAnyIgnoreCase(
                text,
                "ECONNREFUSED",
                "Connection refused",
                "ConnectException",
                "Failed to connect to",
                "拒绝连接",
                "连接被拒绝",
            ) -> NetBenchErrorCategory.CONNECTION_REFUSED

            containsAnyIgnoreCase(
                text,
                "ProtocolException",
                "非 iperf3 协议",
                "非标准 iperf3",
                "服务端返回错误",
                "服务端主动终止",
                "提前断开控制连接",
                "数据流意外中断",
                "不支持该发包间隔",
                "不支持系统 ping",
            ) -> NetBenchErrorCategory.PROTOCOL_OR_SERVER_ERROR

            containsAnyIgnoreCase(
                text,
                "WHOIS 查询服务",
                "查询服务暂不可用",
            ) -> NetBenchErrorCategory.SERVICE_UNAVAILABLE

            else -> null
        }
    }

    private fun containsAnyIgnoreCase(haystack: String, vararg needles: String): Boolean =
        needles.any { needle -> haystack.contains(needle, ignoreCase = true) }
}

// ── Shared Privacy-Sanitized Presentation Models ─────────────────────────────────────────────

data class SanitizedTargetPresentation(
    val hasResolvedTarget: Boolean,
    val displayHost: String?,
    val isDomain: Boolean,
    val displayIp: String?,
    val formattedEndpoint: String?,
    val ipFamily: IpFamily,
    val dnsDurationMs: Double,
    val resolvedRecordCount: Int,
    val exposedPort: Int?,
    val portSummaryLabel: String,
)

data class SanitizedClientEnvPresentation(
    val hasDetectedClientInfo: Boolean,
    val carrierDisplayName: String,
    val regionName: String?,
    val regionOrCountry: String?,
    val asn: String?,
    val compactIspName: String?,
    val ipFamily: IpFamily,
    val routeKind: EgressRouteKind,
    val transportLabel: String?,
    val proxyCarrierDisplayName: String?,
    val cardFooterText: String,
)

data class SanitizedIpIntelPresentation(
    val displayIp: String,
    val ipFamily: IpFamily,
    val countryCode: String?,
    val flagEmoji: String,
    val locationSummary: String,
    val asn: String?,
    val orgName: String?,
    val compactOrgName: String?,
    val ispName: String?,
    val compactIspName: String?,
    val usageType: IpUsageType,
    val originType: IpOriginType,
    val rirCountryCode: String?,
    val rirAuthority: String?,
    val originEvidence: String,
    val geoSource: String?,
    val whoisSource: String?,
    val hasCoreInfo: Boolean,
    val errorCategory: NetBenchErrorCategory?,
    val publicErrorMessage: String?,
)

data class SanitizedPingSample(
    val seq: Int,
    val success: Boolean,
    val rttMs: Double? = null,
    val ttl: Int? = null,
)

data class SanitizedIcmpPingPresentation(
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val hasValidLatency: Boolean,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val jitterMdevMs: Double?,
    val samples: List<SanitizedPingSample>,
    val bindingMode: IcmpNetworkBindingMode,
    val boundInterfaceName: String?,
    val errorCategory: NetBenchErrorCategory?,
    val publicErrorMessage: String?,
)

data class SanitizedTcpPingPresentation(
    val exposedPort: Int?,
    val portSummaryLabel: String,
    val tablePortLabel: String,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val hasValidLatency: Boolean,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val jitterMdevMs: Double?,
    val samples: List<SanitizedPingSample>,
    val errorCategory: NetBenchErrorCategory?,
    val publicErrorMessage: String?,
)

data class SanitizedHttpProbePresentation(
    val success: Boolean,
    val statusCode: Int?,
    val totalMs: Double?,
    val ttfbMs: Double?,
    val tlsHandshakeMs: Double?,
    val protocol: String?,
    val errorCategory: NetBenchErrorCategory?,
    val publicErrorMessage: String?,
)

data class SanitizedIperf3Presentation(
    val direction: Iperf3Direction,
    val exposedPort: Int?,
    val portSummaryLabel: String,
    val targetHeaderText: String,
    val durationSec: Double,
    val bytesTransferred: Long,
    /** Receiver-side throughput (download: phone, upload: VPS), see [Iperf3Result.effectiveMbps]. */
    val effectiveMbps: Double,
    /** Raw client-side rate (download: received, upload: written to the local socket). */
    val clientMbps: Double,
    val serverMbps: Double?,
    val maxIntervalMbps: Double,
    val minIntervalMbps: Double?,
    val intervalStdDevMbps: Double?,
    val stabilityPercent: Double?,
    val retransmits: Long?,
    val parallelStreams: Int,
    val intervals: List<Iperf3IntervalSample>,
    val success: Boolean,
    val errorCategory: NetBenchErrorCategory?,
    val publicErrorMessage: String?,
    /** Warm-up seconds excluded from statistics (`iperf3 -O`). */
    val omitSec: Int = 0,
    /** Server-reported total bytes from `EXCHANGE_RESULTS`, if available. */
    val serverBytesTransferred: Long? = null,
    /** Server-reported duration in seconds from `EXCHANGE_RESULTS`, if available. */
    val serverDurationSec: Double? = null,
) {
    /** Intervals inside the measured window (warm-up `omitted` samples removed). */
    val measuredIntervals: List<Iperf3IntervalSample>
        get() = intervals.filter { !it.omitted }
}

data class SanitizedTracerouteHopPresentation(
    val hopNumber: Int,
    val displayIp: String,
    val displayHostname: String?,
    val asn: String?,
    val locationSummary: String?,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val avgMs: Double?,
    val maxMs: Double?,
    val jitterMdevMs: Double?,
    val backboneTags: List<BackboneRouteTag>,
    val lossClassification: HopLossClassification,
    val isPrivateOrCgnat: Boolean,
    val isTargetReached: Boolean,
)

data class SanitizedTraceroutePresentation(
    val direction: TracerouteDirection,
    val targetDisplay: String,
    val sourceLabel: String,
    val toolName: String,
    val hops: List<SanitizedTracerouteHopPresentation>,
    val reachedTarget: Boolean,
    val totalHops: Int,
    val terminalAvgMs: Double?,
    val peakHopLossPercent: Double,
    val peakLossHopNumber: Int?,
    val hasRealPacketLoss: Boolean,
    val hasIntermediateRateLimitOnly: Boolean,
    val dominantBackboneTags: List<BackboneRouteTag>,
    val routeSignature: String,
    val isPeakHourWindow: Boolean,
    val timeWindowLabel: String,
    val publicErrorMessage: String?,
)

/**
 * Unified, privacy-sanitized presentation model consumed by BOTH [NetBenchMarkdownFormatter]
 * and [com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator].
 *
 * Guarantees:
 * - Local client egress `rawIp` / `maskedIp` (and proxy `rawIp` / `maskedIp`) NEVER exist here.
 * - Raw Java/OkHttp exception messages (`rawDiagnostic`, `Failed to connect to /ip:port`, etc.)
 *   are replaced uniformly with standardized [NetBenchErrorCategory] labels.
 * - When [maskTargetIp] is `true`, all target IPv4/IPv6 addresses, ports, reverse-DNS/PTR hostnames,
 *   and free-form text fields are scrubbed so no unmasked IPv4/IPv6 literal can leak.
 */
data class SanitizedNetBenchReport(
    val maskTargetIp: Boolean,
    val includeClientNetwork: Boolean,
    val testedViaProxy: Boolean,
    val target: SanitizedTargetPresentation,
    val clientEnv: SanitizedClientEnvPresentation?,
    val ipIntelligence: SanitizedIpIntelPresentation?,
    val icmpPing: SanitizedIcmpPingPresentation?,
    val tcpPing: SanitizedTcpPingPresentation?,
    val httpProbe: SanitizedHttpProbePresentation?,
    val iperfDownload: SanitizedIperf3Presentation?,
    val iperfUpload: SanitizedIperf3Presentation?,
    val forwardTraceroute: SanitizedTraceroutePresentation? = null,
    val reverseTraceroute: SanitizedTraceroutePresentation? = null,
) {
    val hasAnyResult: Boolean
        get() = icmpPing != null ||
            tcpPing != null ||
            httpProbe != null ||
            ipIntelligence != null ||
            iperfDownload != null ||
            iperfUpload != null ||
            forwardTraceroute != null ||
            reverseTraceroute != null
}

/**
 * Single source-of-truth privacy sanitizer for NetBench reports.
 */
object NetBenchReportSanitizer {

    private const val MAX_HOST_DISPLAY_WIDTH = 30
    private const val MAX_ORG_DISPLAY_WIDTH = 28

    private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")
    private val DBA_SPLIT_REGEX = Regex("""(?i)\bdba\s+(.+)$""")
    private val CORPORATE_SUFFIX_REGEX = Regex(
        """(?i)(?:,\s*|\s+)(?:llc|inc\.?|ltd\.?|limited|corp\.?|corporation|gmbh|co\.?,?\s*ltd\.?|holdings?(?:\s+corporation)?)+$""",
    )
    private val IN_ADDR_ARPA_REGEX = Regex(
        """(?i)\b(\d{1,3})\.(\d{1,3}\.\d{1,3}\.\d{1,3})\.in-addr\.arpa\.?""",
    )
    private val IP6_ARPA_REGEX = Regex(
        """(?i)\b(?:[0-9a-f]\.){4,32}ip6\.arpa\.?""",
    )
    private val DASH_IPV4_PTR_REGEX = Regex(
        """(?i)(?<![0-9])(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)-(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)-(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)-(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?=[.-][a-z])""",
    )

    private data class SanitizationContext(
        val primaryTargetIp: String?,
        val targetIpReplacementPairs: List<Pair<String, String>>,
        val targetPtrPatterns: List<Pair<String, String>>,
        val clientPtrPatterns: List<Pair<String, String>>,
        val clientSensitiveTokens: List<String>,
        val clientIpv4Ints: Set<Int>,
        val clientIpv6Keys: Set<String>,
        val portsToScrub: Set<Int>,
        val portRegexes: List<Pair<Int, Regex>>,
    )

    internal fun createScrubber(
        report: NetBenchReport,
        maskTargetIp: Boolean,
    ): (String) -> String {
        val ctx = buildContext(report)
        return { text -> scrubWithContext(text, ctx, maskTargetIp) }
    }

    fun sanitize(
        report: NetBenchReport,
        maskTargetIp: Boolean = true,
        includeClientNetwork: Boolean = true,
    ): SanitizedNetBenchReport {
        val ctx = buildContext(report)
        val target = buildSanitizedTarget(report, maskTargetIp, ctx)
        val clientEnv = if (includeClientNetwork) {
            buildSanitizedClientEnv(report, maskTargetIp, target.ipFamily, ctx)
        } else {
            null
        }
        val ipIntel = report.ipIntelligence?.let { buildSanitizedIpIntel(it, maskTargetIp, ctx) }
        val icmpPing = report.icmpPingSummary?.let { buildSanitizedIcmpPing(it, maskTargetIp, ctx) }
        val tcpPing = report.tcpPingSummary?.let { buildSanitizedTcpPing(it, maskTargetIp) }
        val httpProbe = report.httpLatencyResult?.let { buildSanitizedHttpProbe(it, maskTargetIp, ctx) }
        val iperfDl = report.iperfDownloadResult?.let { buildSanitizedIperf(it, report, target, maskTargetIp, ctx) }
        val iperfUl = report.iperfUploadResult?.let { buildSanitizedIperf(it, report, target, maskTargetIp, ctx) }
        val forwardTrace = report.forwardTraceroute?.let { buildSanitizedTraceroute(it, maskTargetIp, ctx) }
        val reverseTrace = report.reverseTraceroute?.let { buildSanitizedTraceroute(it, maskTargetIp, ctx) }

        return SanitizedNetBenchReport(
            maskTargetIp = maskTargetIp,
            includeClientNetwork = includeClientNetwork,
            testedViaProxy = report.testedViaProxy,
            target = target,
            clientEnv = clientEnv,
            ipIntelligence = ipIntel,
            icmpPing = icmpPing,
            tcpPing = tcpPing,
            httpProbe = httpProbe,
            iperfDownload = iperfDl,
            iperfUpload = iperfUl,
            forwardTraceroute = forwardTrace,
            reverseTraceroute = reverseTrace,
        )
    }

    /**
     * Scrubs a free-form public text string against [report]'s client IPs, target IPs/ports/PTRs,
     * and defense-in-depth general IPv4/IPv6 literal masking when [maskTargetIp] is `true`.
     */
    fun scrubPublicText(
        raw: String,
        report: NetBenchReport,
        maskTargetIp: Boolean,
    ): String = scrubWithContext(raw, buildContext(report), maskTargetIp)

    /**
     * Final defense-in-depth output scrubber for full Markdown or rendered text blocks.
     */
    fun scrubFinalOutput(
        text: String,
        report: NetBenchReport,
        maskTargetIp: Boolean,
    ): String = scrubWithContext(text, buildContext(report), maskTargetIp)

    internal fun sanitizeHostnameForReport(
        rawHostname: String,
        report: NetBenchReport,
        maskTargetIp: Boolean,
    ): String = sanitizeHostnameInternal(rawHostname, buildContext(report), maskTargetIp)

    private fun buildSanitizedTarget(
        report: NetBenchReport,
        maskTargetIp: Boolean,
        ctx: SanitizationContext,
    ): SanitizedTargetPresentation {
        val resolved = report.resolvedTarget
        if (resolved != null) {
            val cleanIp = resolved.resolvedIp.trim()
            val displayIp = if (maskTargetIp) {
                NetBenchTargetParser.maskTargetIpLastSegment(cleanIp)
            } else {
                cleanIp
            }
            val formattedEndpoint = if (maskTargetIp) {
                if (resolved.ipFamily == IpFamily.IPV6) "[$displayIp]" else displayIp
            } else {
                if (resolved.ipFamily == IpFamily.IPV6) "[$cleanIp]:${report.tcpPort}" else "$cleanIp:${report.tcpPort}"
            }
            val isDomain = resolved.isDomain
            val safeHost = if (isDomain) {
                sanitizeHostnameInternal(resolved.displayHost.trim(), ctx, maskTargetIp)
            } else {
                null
            }
            return SanitizedTargetPresentation(
                hasResolvedTarget = true,
                displayHost = safeHost,
                isDomain = isDomain,
                displayIp = displayIp,
                formattedEndpoint = formattedEndpoint,
                ipFamily = resolved.ipFamily,
                dnsDurationMs = resolved.dnsDurationMs,
                resolvedRecordCount = resolved.allResolvedIps.size.coerceAtLeast(1),
                exposedPort = if (maskTargetIp) null else report.tcpPort,
                portSummaryLabel = if (maskTargetIp) "已隐藏端口" else "端口 ${report.tcpPort}",
            )
        }

        val raw = report.targetInput.trim()
        if (raw.isEmpty()) {
            return SanitizedTargetPresentation(
                hasResolvedTarget = false,
                displayHost = null,
                isDomain = false,
                displayIp = null,
                formattedEndpoint = null,
                ipFamily = IpFamily.UNKNOWN,
                dnsDurationMs = 0.0,
                resolvedRecordCount = 0,
                exposedPort = if (maskTargetIp) null else report.tcpPort,
                portSummaryLabel = if (maskTargetIp) "已隐藏端口" else "端口 ${report.tcpPort}",
            )
        }

        val parsed = NetBenchTargetParser.parse(raw)
        if (parsed != null) {
            val effectivePort = parsed.effectivePort(report.tcpPort)
            return if (parsed.isLiteralIp) {
                val displayIp = if (maskTargetIp) {
                    NetBenchTargetParser.maskTargetIpLastSegment(parsed.host)
                } else {
                    parsed.host
                }
                val endpoint = if (maskTargetIp) {
                    if (parsed.literalFamily == IpFamily.IPV6) "[$displayIp]" else displayIp
                } else {
                    if (parsed.literalFamily == IpFamily.IPV6) "[${parsed.host}]:$effectivePort" else "${parsed.host}:$effectivePort"
                }
                SanitizedTargetPresentation(
                    hasResolvedTarget = false,
                    displayHost = null,
                    isDomain = false,
                    displayIp = displayIp,
                    formattedEndpoint = endpoint,
                    ipFamily = parsed.literalFamily,
                    dnsDurationMs = 0.0,
                    resolvedRecordCount = 1,
                    exposedPort = if (maskTargetIp) null else effectivePort,
                    portSummaryLabel = if (maskTargetIp) "已隐藏端口" else "端口 $effectivePort",
                )
            } else {
                val safeHost = sanitizeHostnameInternal(parsed.host, ctx, maskTargetIp)
                SanitizedTargetPresentation(
                    hasResolvedTarget = false,
                    displayHost = safeHost,
                    isDomain = true,
                    displayIp = null,
                    formattedEndpoint = if (maskTargetIp) safeHost else "$safeHost:$effectivePort",
                    ipFamily = IpFamily.UNKNOWN,
                    dnsDurationMs = 0.0,
                    resolvedRecordCount = 0,
                    exposedPort = if (maskTargetIp) null else effectivePort,
                    portSummaryLabel = if (maskTargetIp) "已隐藏端口" else "端口 $effectivePort",
                )
            }
        }

        val fallbackClean = scrubWithContext(sanitizeInline(raw), ctx, maskTargetIp)
        return SanitizedTargetPresentation(
            hasResolvedTarget = false,
            displayHost = fallbackClean.ifBlank { null },
            isDomain = true,
            displayIp = null,
            formattedEndpoint = if (maskTargetIp) fallbackClean else "$fallbackClean:${report.tcpPort}",
            ipFamily = IpFamily.UNKNOWN,
            dnsDurationMs = 0.0,
            resolvedRecordCount = 0,
            exposedPort = if (maskTargetIp) null else report.tcpPort,
            portSummaryLabel = if (maskTargetIp) "已隐藏端口" else "端口 ${report.tcpPort}",
        )
    }

    private fun buildSanitizedClientEnv(
        report: NetBenchReport,
        maskTargetIp: Boolean,
        fallbackTargetFamily: IpFamily,
        ctx: SanitizationContext,
    ): SanitizedClientEnvPresentation {
        val client = report.clientNetwork
        val family = client?.ipFamily?.takeIf { it != IpFamily.UNKNOWN }
            ?: fallbackTargetFamily.takeIf { it != IpFamily.UNKNOWN }
            ?: IpFamily.IPV4

        val proxyCarrier = report.proxyNetwork?.carrierDisplayName
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }

        if (client == null) {
            return SanitizedClientEnvPresentation(
                hasDetectedClientInfo = false,
                carrierDisplayName = "本机网络",
                regionName = null,
                regionOrCountry = null,
                asn = null,
                compactIspName = null,
                ipFamily = family,
                routeKind = EgressRouteKind.DIRECT,
                transportLabel = null,
                proxyCarrierDisplayName = proxyCarrier,
                cardFooterText = "",
            )
        }

        val carrier = scrubWithContext(sanitizeInline(client.carrierDisplayName), ctx, maskTargetIp)
            .ifBlank { "本机网络" }
        val cleanRegionOnly = client.regionName?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(it, ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val regionRaw = cleanRegionOnly ?: IpClassificationEngine.countryNameZh(client.countryCode)
        val region = regionRaw
            ?.let { scrubWithContext(it, ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() && !carrier.contains(it, ignoreCase = true) }

        val asn = client.asn?.trim()?.takeIf { it.isNotEmpty() }?.let { rawAsn ->
            val norm = IpClassificationEngine.normalizeAsn(rawAsn) ?: sanitizeInline(rawAsn)
            scrubWithContext(norm, ctx, maskTargetIp).takeIf { it.isNotEmpty() }
        }
        val compactIsp = compactOrgName(client.ispName)
            ?.let { scrubWithContext(it, ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() && !it.equals(carrier, ignoreCase = true) }
        val transport = client.transportLabel
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }

        val footerPrefix = when {
            report.testedViaProxy -> "经由代理出口"
            client.routeKind == EgressRouteKind.PROXY_FALLBACK -> "代理出口(直连受限)"
            else -> "本机出口"
        }
        val cardRegion = cleanRegionOnly?.takeIf { !carrier.contains(it, ignoreCase = true) }
        val cardFooter = listOfNotNull(
            "$footerPrefix：$carrier",
            cardRegion,
            family.label,
            "生成报告已自动隐藏本机 IP",
        ).joinToString(" · ")

        return SanitizedClientEnvPresentation(
            hasDetectedClientInfo = true,
            carrierDisplayName = carrier,
            regionName = cleanRegionOnly,
            regionOrCountry = region,
            asn = asn,
            compactIspName = compactIsp,
            ipFamily = family,
            routeKind = client.routeKind,
            transportLabel = transport,
            proxyCarrierDisplayName = proxyCarrier,
            cardFooterText = cardFooter,
        )
    }

    private fun buildSanitizedIpIntel(
        intel: IpIntelligenceReport,
        maskTargetIp: Boolean,
        ctx: SanitizationContext,
    ): SanitizedIpIntelPresentation {
        val rawIp = intel.ip.trim()
        val displayIp = if (matchesClientIpSemantically(rawIp, ctx)) {
            "[已隐藏本机IP]"
        } else if (maskTargetIp) {
            NetBenchTargetParser.maskTargetIpLastSegment(rawIp)
        } else {
            scrubClientTokensOnly(rawIp, ctx)
        }
        val countryCode = intel.countryCode?.trim()?.takeIf { it.isNotEmpty() }
        val flag = IpClassificationEngine.countryFlagEmoji(countryCode)
        val rawLocation = IpClassificationEngine.formatLocationSummary(
            countryCode = countryCode,
            countryName = intel.countryName,
            regionName = intel.regionName,
            cityName = intel.cityName,
        ).trim()
        val locationSummary = scrubWithContext(rawLocation, ctx, maskTargetIp)

        val asn = intel.asn?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val orgFull = intel.orgName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val ispFull = intel.ispName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val compactOrg = compactOrgName(orgFull)
        val compactIsp = compactOrgName(ispFull)?.takeIf { !it.equals(compactOrg, ignoreCase = true) }

        val rirAuth = intel.rirAuthority?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val geoSrc = intel.geoSource?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val whoisSrc = intel.whoisSource?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val evidence = scrubWithContext(sanitizeInline(intel.originEvidence), ctx, maskTargetIp)
            .ifBlank { intel.originType.label }

        val errCategory = intel.errorMessage?.takeIf { it.isNotBlank() }?.let { rawErr ->
            NetBenchDiagnosticClassifier.classifyErrorText(
                rawMessage = rawErr,
                defaultCategory = NetBenchErrorCategory.SERVICE_UNAVAILABLE,
            )
        }

        return SanitizedIpIntelPresentation(
            displayIp = displayIp,
            ipFamily = intel.ipFamily,
            countryCode = countryCode,
            flagEmoji = flag,
            locationSummary = locationSummary,
            asn = asn,
            orgName = orgFull,
            compactOrgName = compactOrg,
            ispName = ispFull,
            compactIspName = compactIsp,
            usageType = intel.usageType,
            originType = intel.originType,
            rirCountryCode = intel.rirCountryCode?.trim()?.takeIf { it.isNotEmpty() },
            rirAuthority = rirAuth,
            originEvidence = evidence,
            geoSource = geoSrc,
            whoisSource = whoisSrc,
            hasCoreInfo = intel.hasCoreInfo,
            errorCategory = errCategory,
            publicErrorMessage = errCategory?.publicLabel,
        )
    }

    private fun buildSanitizedIcmpPing(
        ping: IcmpPingSummary,
        maskTargetIp: Boolean,
        ctx: SanitizationContext,
    ): SanitizedIcmpPingPresentation {
        val iface = ping.boundInterfaceName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }

        val errSource = ping.errorMessage?.takeIf { it.isNotBlank() }
            ?: ping.samples.lastOrNull { !it.success && !it.errorMessage.isNullOrBlank() }?.errorMessage
        val errCategory = if (!ping.hasValidLatency) {
            NetBenchDiagnosticClassifier.classifyErrorText(
                rawMessage = errSource,
                defaultCategory = if (ping.sent > 0) {
                    NetBenchErrorCategory.CONNECTION_TIMEOUT
                } else {
                    NetBenchErrorCategory.REQUEST_FAILED
                },
            )
        } else {
            null
        }

        return SanitizedIcmpPingPresentation(
            sent = ping.sent,
            received = ping.received,
            lossPercent = ping.lossPercent,
            hasValidLatency = ping.hasValidLatency,
            minMs = ping.minMs,
            avgMs = ping.avgMs,
            maxMs = ping.maxMs,
            jitterMdevMs = ping.jitterMdevMs,
            samples = ping.samples.map { s ->
                SanitizedPingSample(
                    seq = s.seq,
                    success = s.success,
                    rttMs = s.rttMs,
                    ttl = s.ttl,
                )
            },
            bindingMode = ping.bindingMode,
            boundInterfaceName = iface,
            errorCategory = errCategory,
            publicErrorMessage = errCategory?.publicLabel,
        )
    }

    private fun buildSanitizedTcpPing(
        tcp: TcpPingSummary,
        maskTargetIp: Boolean,
    ): SanitizedTcpPingPresentation {
        val lastFailedSample = tcp.samples.lastOrNull { !it.success }
        val explicitCat = tcp.errorCategory ?: lastFailedSample?.errorCategory
        val errSource = tcp.errorMessage?.takeIf { it.isNotBlank() }
            ?: lastFailedSample?.errorMessage
            ?: tcp.rawDiagnostic
            ?: lastFailedSample?.rawDiagnostic
        val errCategory = if (!tcp.hasValidLatency) {
            NetBenchDiagnosticClassifier.classifyErrorText(
                rawMessage = errSource,
                explicitCategory = explicitCat,
                defaultCategory = if (tcp.sent > 0) {
                    NetBenchErrorCategory.CONNECTION_TIMEOUT
                } else {
                    NetBenchErrorCategory.REQUEST_FAILED
                },
            )
        } else {
            null
        }

        return SanitizedTcpPingPresentation(
            exposedPort = if (maskTargetIp) null else tcp.port,
            portSummaryLabel = if (maskTargetIp) "已隐藏端口" else "端口 ${tcp.port}",
            tablePortLabel = if (maskTargetIp) "已隐藏端口" else "TCP ${tcp.port}",
            sent = tcp.sent,
            received = tcp.received,
            lossPercent = tcp.lossPercent,
            hasValidLatency = tcp.hasValidLatency,
            minMs = tcp.minMs,
            avgMs = tcp.avgMs,
            maxMs = tcp.maxMs,
            jitterMdevMs = tcp.jitterMdevMs,
            samples = tcp.samples.map { s ->
                SanitizedPingSample(
                    seq = s.seq,
                    success = s.success,
                    rttMs = s.rttMs,
                )
            },
            errorCategory = errCategory,
            publicErrorMessage = errCategory?.publicLabel,
        )
    }

    private fun buildSanitizedHttpProbe(
        http: HttpLatencyResult,
        maskTargetIp: Boolean,
        ctx: SanitizationContext,
    ): SanitizedHttpProbePresentation {
        val safeProto = http.protocol?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { scrubWithContext(sanitizeInline(it), ctx, maskTargetIp) }
            ?.takeIf { it.isNotEmpty() }
        val errCategory = if (!http.success || http.totalMs == null) {
            NetBenchDiagnosticClassifier.classifyErrorText(
                rawMessage = http.errorMessage ?: http.rawDiagnostic,
                explicitCategory = http.errorCategory,
                defaultCategory = NetBenchErrorCategory.REQUEST_FAILED,
            )
        } else {
            null
        }
        return SanitizedHttpProbePresentation(
            success = http.success && http.totalMs != null,
            statusCode = http.statusCode,
            totalMs = http.totalMs,
            ttfbMs = http.ttfbMs,
            tlsHandshakeMs = http.tlsHandshakeMs,
            protocol = safeProto,
            errorCategory = errCategory,
            publicErrorMessage = errCategory?.publicLabel,
        )
    }

    private fun buildSanitizedIperf(
        result: Iperf3Result,
        report: NetBenchReport,
        sanitizedTarget: SanitizedTargetPresentation,
        maskTargetIp: Boolean,
        ctx: SanitizationContext,
    ): SanitizedIperf3Presentation {
        val targetHeader = buildSanitizedIperfTargetHeader(result, sanitizedTarget, report, maskTargetIp, ctx)
        val errCategory = if (!result.success) {
            NetBenchDiagnosticClassifier.classifyErrorText(
                rawMessage = result.errorMessage,
                iperfErrorKind = result.errorKind,
                defaultCategory = NetBenchErrorCategory.REQUEST_FAILED,
            )
        } else {
            null
        }
        return SanitizedIperf3Presentation(
            direction = result.direction,
            exposedPort = if (maskTargetIp) null else result.port,
            portSummaryLabel = if (maskTargetIp) "已隐藏端口" else "端口 ${result.port}",
            targetHeaderText = targetHeader,
            durationSec = result.durationSec,
            bytesTransferred = result.bytesTransferred,
            effectiveMbps = result.effectiveMbps,
            clientMbps = result.clientMbps,
            serverMbps = result.serverMbps,
            maxIntervalMbps = result.maxIntervalMbps,
            minIntervalMbps = result.minIntervalMbps,
            intervalStdDevMbps = result.intervalStdDevMbps,
            stabilityPercent = result.stabilityPercent,
            retransmits = result.retransmits,
            parallelStreams = result.parallelStreams,
            intervals = result.intervals,
            success = result.success,
            errorCategory = errCategory,
            publicErrorMessage = errCategory?.publicLabel,
            omitSec = result.omitSec,
            serverBytesTransferred = result.serverBytesTransferred,
            serverDurationSec = result.serverDurationSec,
        )
    }

    private fun buildSanitizedIperfTargetHeader(
        result: Iperf3Result,
        sanitizedTarget: SanitizedTargetPresentation,
        report: NetBenchReport,
        maskTargetIp: Boolean,
        ctx: SanitizationContext,
    ): String {
        val resolved = result.resolvedTarget ?: report.resolvedTarget
        if (resolved != null) {
            val ipText = if (maskTargetIp) {
                val masked = NetBenchTargetParser.maskTargetIpLastSegment(resolved.resolvedIp)
                if (resolved.ipFamily == IpFamily.IPV6) "[$masked]" else masked
            } else {
                if (resolved.ipFamily == IpFamily.IPV6) "[${resolved.resolvedIp}]" else resolved.resolvedIp
            }
            val portPart = if (maskTargetIp) "" else ", port ${result.port}"
            return if (resolved.isDomain) {
                val safeHost = sanitizeHostnameInternal(resolved.displayHost.trim(), ctx, maskTargetIp)
                    .let { truncateAsciiAware(it, MAX_HOST_DISPLAY_WIDTH) }
                "host $safeHost ($ipText)$portPart"
            } else {
                "host $ipText$portPart"
            }
        }
        val hostOrIp = sanitizedTarget.displayHost ?: sanitizedTarget.displayIp
        if (!hostOrIp.isNullOrBlank()) {
            val shown = if (sanitizedTarget.isDomain) {
                truncateAsciiAware(hostOrIp, MAX_HOST_DISPLAY_WIDTH)
            } else {
                hostOrIp
            }
            val portPart = if (maskTargetIp) "" else ", port ${result.port}"
            return "host $shown$portPart"
        }
        return "target host"
    }

    private fun buildSanitizedTraceroute(
        summary: TracerouteSummary,
        maskTargetIp: Boolean,
        ctx: SanitizationContext,
    ): SanitizedTraceroutePresentation {
        val targetDisp = if (summary.direction == TracerouteDirection.REVERSE) {
            "[已隐藏本机IP]"
        } else {
            val raw = summary.targetIp.ifBlank { summary.targetInput }
            if (maskTargetIp && raw.isNotBlank()) {
                NetBenchTargetParser.maskTargetIpLastSegment(raw)
            } else {
                scrubClientTokensOnly(raw, ctx)
            }
        }
        val safeSource = scrubWithContext(sanitizeInline(summary.sourceLabel), ctx, maskTargetIp)
        val safeSignature = scrubWithContext(sanitizeInline(summary.routeSignature), ctx, maskTargetIp)
        val safeTimeWindow = scrubWithContext(sanitizeInline(summary.timeWindowLabel), ctx, maskTargetIp)
        val safeError = summary.errorMessage?.takeIf { it.isNotBlank() }?.let { rawErr ->
            scrubWithContext(sanitizeInline(rawErr), ctx, maskTargetIp)
        }

        val sanitizedHops = summary.hops.map { hop ->
            val rawIp = hop.ip?.trim().orEmpty()
            val dispIp = when {
                rawIp.isEmpty() -> "*"
                matchesClientIpSemantically(rawIp, ctx) -> "[已隐藏本机IP]"
                else -> {
                    val clientScrubbed = scrubClientTokensOnly(rawIp, ctx)
                    if (clientScrubbed != rawIp) {
                        clientScrubbed
                    } else if (maskTargetIp) {
                        NetBenchTargetParser.maskTargetIpLastSegment(rawIp)
                    } else {
                        rawIp
                    }
                }
            }
            val safeHost = hop.hostname?.trim()?.takeIf { it.isNotEmpty() }?.let { host ->
                sanitizeHostnameInternal(host, ctx, maskTargetIp).takeIf { it.isNotEmpty() }
            }
            val safeAsn = hop.asn?.trim()?.takeIf { it.isNotEmpty() }?.let {
                scrubWithContext(sanitizeInline(it), ctx, maskTargetIp)
            }
            val safeLoc = hop.locationSummary?.takeIf { it.isNotBlank() }?.let {
                scrubWithContext(sanitizeInline(it), ctx, maskTargetIp)
            }
            SanitizedTracerouteHopPresentation(
                hopNumber = hop.hopNumber,
                displayIp = dispIp,
                displayHostname = safeHost,
                asn = safeAsn,
                locationSummary = safeLoc,
                sent = hop.sent,
                received = hop.received,
                lossPercent = hop.lossPercent,
                avgMs = hop.avgMs,
                maxMs = hop.maxMs,
                jitterMdevMs = hop.jitterMdevMs,
                backboneTags = hop.backboneTags,
                lossClassification = hop.lossClassification,
                isPrivateOrCgnat = hop.isPrivateOrCgnat,
                isTargetReached = hop.isTargetReached,
            )
        }

        return SanitizedTraceroutePresentation(
            direction = summary.direction,
            targetDisplay = targetDisp,
            sourceLabel = safeSource,
            toolName = summary.toolName,
            hops = sanitizedHops,
            reachedTarget = summary.reachedTarget,
            totalHops = summary.totalHops,
            terminalAvgMs = summary.terminalAvgMs,
            peakHopLossPercent = summary.peakHopLossPercent,
            peakLossHopNumber = summary.peakLossHopNumber,
            hasRealPacketLoss = summary.hasRealPacketLoss,
            hasIntermediateRateLimitOnly = summary.hasIntermediateRateLimitOnly,
            dominantBackboneTags = summary.dominantBackboneTags,
            routeSignature = safeSignature,
            isPeakHourWindow = summary.isPeakHourWindow,
            timeWindowLabel = safeTimeWindow,
            publicErrorMessage = safeError,
        )
    }

    // ── Context Construction & Multi-Layer Scrubbing ─────────────────────────────────────────

    private fun buildContext(report: NetBenchReport): SanitizationContext {
        val parsedInput = NetBenchTargetParser.parse(report.targetInput.trim())
        val parsedHttpUrl = report.httpLatencyResult?.url?.let { NetBenchTargetParser.parse(it) }

        val rawTargetIps = LinkedHashSet<String>()
        fun collectResolved(rt: ResolvedTarget?) {
            if (rt == null) return
            rt.resolvedIp.trim().takeIf { it.isNotEmpty() }?.let(rawTargetIps::add)
            rt.allResolvedIps.forEach { ip ->
                ip.trim().takeIf { it.isNotEmpty() }?.let(rawTargetIps::add)
            }
            if (NetBenchTargetParser.detectIpFamily(rt.displayHost) != IpFamily.UNKNOWN) {
                rawTargetIps.add(rt.displayHost.trim())
            }
        }
        collectResolved(report.resolvedTarget)
        collectResolved(report.icmpPingSummary?.resolvedTarget)
        collectResolved(report.tcpPingSummary?.resolvedTarget)
        collectResolved(report.iperfDownloadResult?.resolvedTarget)
        collectResolved(report.iperfUploadResult?.resolvedTarget)
        report.ipIntelligence?.ip?.trim()?.takeIf { it.isNotEmpty() }?.let(rawTargetIps::add)
        if (parsedInput?.isLiteralIp == true && parsedInput.host.isNotBlank()) {
            rawTargetIps.add(parsedInput.host.trim())
        }
        if (parsedHttpUrl?.isLiteralIp == true && parsedHttpUrl.host.isNotBlank()) {
            rawTargetIps.add(parsedHttpUrl.host.trim())
        }

        val ipFormsMap = LinkedHashMap<String, String>()
        val ptrMap = LinkedHashMap<String, String>()
        for (rawIp in rawTargetIps) {
            val masked = NetBenchTargetParser.maskTargetIpLastSegment(rawIp)
            for (form in NetBenchTargetParser.allCanonicalIpForms(rawIp)) {
                if (form.isNotEmpty() && !form.equals(masked, ignoreCase = true)) {
                    ipFormsMap[form] = NetBenchTargetParser.maskTargetIpLastSegment(form)
                }
            }
            val v4Octets = NetBenchTargetParser.parseIpv4Octets(rawIp)
            if (v4Octets != null) {
                val (a, b, c, d) = v4Octets
                ptrMap["$d.$c.$b.$a.in-addr.arpa"] = "*.$c.$b.$a.in-addr.arpa"
                ptrMap["$a-$b-$c-$d"] = "$a-$b-$c-*"
                ptrMap["$d-$c-$b-$a"] = "*-$c-$b-$a"
                val zpA = a.toString().padStart(3, '0')
                val zpB = b.toString().padStart(3, '0')
                val zpC = c.toString().padStart(3, '0')
                val zpD = d.toString().padStart(3, '0')
                ptrMap["$zpA-$zpB-$zpC-$zpD"] = "$zpA-$zpB-$zpC-*"
                ptrMap["$d.$c.$b.$a."] = "*.$c.$b.$a."
            }
            val v6Hextets = NetBenchTargetParser.parseIpv6Hextets(rawIp)
            if (v6Hextets != null) {
                val maskedDash = masked.replace(':', '-')
                ptrMap[v6Hextets.joinToString("-") { it.toString(16) }] = maskedDash
                ptrMap[v6Hextets.joinToString("-") { it.toString(16).padStart(4, '0') }] = maskedDash
            }
        }

        val sortedIpPairs = ipFormsMap.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
        val sortedPtrPairs = ptrMap.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }

        // Client egress sensitive tokens & semantic IP representations (never scrub if identical to an unmasked target IP)
        val targetFormsLower = ipFormsMap.keys.map { it.lowercase() }.toSet() +
            ipFormsMap.values.map { it.lowercase() }.toSet() +
            setOf(report.targetInput.trim().lowercase())
        val targetIpv4Ints = HashSet<Int>()
        val targetIpv6Keys = HashSet<String>()
        for (targetIp in rawTargetIps) {
            val clean = targetIp.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            BackboneRouteTagger.parseIpv4ToIntOrNull(clean)?.let(targetIpv4Ints::add)
            NetBenchTargetParser.parseIpv6Hextets(clean)?.let { hextets ->
                targetIpv6Keys.add(canonicalIpv6Key(hextets))
                unwrapIpv4MappedInt(hextets)?.let(targetIpv4Ints::add)
            }
        }

        val clientIpv4Ints = LinkedHashSet<Int>()
        val clientIpv6Keys = LinkedHashSet<String>()
        val rawClientIps = LinkedHashSet<String>()
        fun collectClientSemanticIp(raw: String?) {
            val clean = raw?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.takeIf { it.isNotEmpty() }
                ?: return
            var added = false
            BackboneRouteTagger.parseIpv4ToIntOrNull(clean)?.let { v4 ->
                if (v4 !in targetIpv4Ints) {
                    clientIpv4Ints.add(v4)
                    added = true
                }
            }
            NetBenchTargetParser.parseIpv6Hextets(clean)?.let { hextets ->
                val key = canonicalIpv6Key(hextets)
                if (key !in targetIpv6Keys) {
                    clientIpv6Keys.add(key)
                    added = true
                }
                unwrapIpv4MappedInt(hextets)?.let { v4 ->
                    if (v4 !in targetIpv4Ints) {
                        clientIpv4Ints.add(v4)
                        added = true
                    }
                }
            }
            if (added) {
                rawClientIps.add(clean)
            }
        }
        collectClientSemanticIp(report.clientNetwork?.rawIp)
        collectClientSemanticIp(report.proxyNetwork?.rawIp)
        collectClientSemanticIp(report.reverseTraceroute?.targetIp)

        val clientPtrMap = LinkedHashMap<String, String>()
        for (rawClientIp in rawClientIps) {
            val v4Octets = NetBenchTargetParser.parseIpv4Octets(rawClientIp)
            if (v4Octets != null) {
                val (a, b, c, d) = v4Octets
                clientPtrMap["$d.$c.$b.$a.in-addr.arpa"] = "[已隐藏本机IP].in-addr.arpa"
                clientPtrMap["$a-$b-$c-$d"] = "[已隐藏本机IP]"
                clientPtrMap["$d-$c-$b-$a"] = "[已隐藏本机IP]"
                val zpA = a.toString().padStart(3, '0')
                val zpB = b.toString().padStart(3, '0')
                val zpC = c.toString().padStart(3, '0')
                val zpD = d.toString().padStart(3, '0')
                clientPtrMap["$zpA-$zpB-$zpC-$zpD"] = "[已隐藏本机IP]"
                clientPtrMap["$zpD-$zpC-$zpB-$zpA"] = "[已隐藏本机IP]"
                clientPtrMap["$d.$c.$b.$a."] = "[已隐藏本机IP]."
            }
            val v6Hextets = NetBenchTargetParser.parseIpv6Hextets(rawClientIp)
            if (v6Hextets != null) {
                clientPtrMap[v6Hextets.joinToString("-") { it.toString(16) }] = "[已隐藏本机IP]"
                clientPtrMap[v6Hextets.joinToString("-") { it.toString(16).padStart(4, '0') }] = "[已隐藏本机IP]"
                val reversedNibbles = v6Hextets
                    .joinToString("") { it.toString(16).padStart(4, '0') }
                    .reversed()
                    .toCharArray()
                    .joinToString(".")
                clientPtrMap["$reversedNibbles.ip6.arpa"] = "[已隐藏本机IP].ip6.arpa"
            }
        }
        val sortedClientPtrPairs = clientPtrMap.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }

        val rawClientCandidates = buildSet {
            report.clientNetwork?.maskedIp?.trim()?.let(::add)
            report.proxyNetwork?.maskedIp?.trim()?.let(::add)
            report.reverseTraceroute?.targetIp?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                NetBenchTargetParser.maskClientIpForDisplay(raw)?.let(::add)
            }
        }
        val clientTokens = rawClientCandidates
            .filter { token ->
                token.isNotEmpty() &&
                    token != "****" &&
                    token.lowercase() !in targetFormsLower
            }
            .sortedByDescending { it.length }

        val ports = buildSet {
            add(report.tcpPort)
            report.tcpPingSummary?.port?.let(::add)
            report.iperfDownloadResult?.port?.let(::add)
            report.iperfUploadResult?.port?.let(::add)
            parsedInput?.explicitPort?.let(::add)
            parsedHttpUrl?.explicitPort?.let(::add)
        }.filter { it in 1..65535 }.toSet()

        val portRegexes = ports.map { port ->
            port to Regex(
                """(\]|(?:\d{1,3}\.){3}(?:\d{1,3}|\*)|[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-*]+)+|localhost):$port\b""",
                RegexOption.IGNORE_CASE,
            )
        }

        return SanitizationContext(
            primaryTargetIp = rawTargetIps.firstOrNull(),
            targetIpReplacementPairs = sortedIpPairs,
            targetPtrPatterns = sortedPtrPairs,
            clientPtrPatterns = sortedClientPtrPairs,
            clientSensitiveTokens = clientTokens,
            clientIpv4Ints = clientIpv4Ints,
            clientIpv6Keys = clientIpv6Keys,
            portsToScrub = ports,
            portRegexes = portRegexes,
        )
    }

    private fun sanitizeHostnameInternal(
        rawHostname: String,
        ctx: SanitizationContext,
        maskTargetIp: Boolean,
    ): String {
        val clean = sanitizeInline(rawHostname).replace("`", "").replace("|", "")
        if (clean.isEmpty()) return ""
        return scrubWithContext(clean, ctx, maskTargetIp)
    }

    private fun scrubClientTokensOnly(raw: String, ctx: SanitizationContext): String {
        if (raw.isEmpty()) return raw
        if (
            ctx.clientIpv4Ints.isEmpty() &&
            ctx.clientIpv6Keys.isEmpty() &&
            ctx.clientSensitiveTokens.isEmpty() &&
            ctx.clientPtrPatterns.isEmpty()
        ) {
            return raw
        }

        val clientTokensLower = if (ctx.clientSensitiveTokens.isEmpty()) {
            emptySet()
        } else {
            ctx.clientSensitiveTokens.mapTo(HashSet(ctx.clientSensitiveTokens.size * 2)) { it.lowercase() }
        }

        var out = IpMarkdownStructuralScanner.scanAndTransform(
            text = raw,
            skipAnsiSgr = true,
            onMaskedIpToken = if (clientTokensLower.isEmpty()) {
                null
            } else {
                IpMarkdownStructuralScanner.MaskedIpTokenHandler { text, start, end ->
                    if (text.substring(start, end).lowercase() in clientTokensLower) {
                        "[已隐藏本机IP]"
                    } else {
                        null
                    }
                }
            },
            onRawIpv4 = if (ctx.clientIpv4Ints.isEmpty()) {
                null
            } else {
                IpMarkdownStructuralScanner.RawIpv4Handler { text, start, end, result ->
                    if (IpMarkdownStructuralScanner.isStrictIpv4Range(text, start, end)) {
                        val v4Int = BackboneRouteTagger.parseIpv4ToIntOrNull(text.substring(start, end))
                        if (v4Int != null && v4Int in ctx.clientIpv4Ints) {
                            result.replacement = "[已隐藏本机IP]"
                            result.nextPrevPlainChar = ']'
                        }
                    }
                }
            },
            onRawIpv6 = if (ctx.clientIpv6Keys.isEmpty() && ctx.clientIpv4Ints.isEmpty()) {
                null
            } else {
                IpMarkdownStructuralScanner.RawIpv6Handler { text, start, n, totalOpen, result ->
                    val v6End = resolveValidIpv6TokenEnd(text, start, n, totalOpen)
                    if (v6End > start) {
                        val hextets = NetBenchTargetParser.parseIpv6Hextets(text.substring(start, v6End))
                        val matched = hextets != null && (
                            canonicalIpv6Key(hextets) in ctx.clientIpv6Keys ||
                                (unwrapIpv4MappedInt(hextets)?.let { it in ctx.clientIpv4Ints } == true)
                            )
                        if (matched) {
                            result.replacement = "[已隐藏本机IP]"
                            result.nextPrevPlainChar = ']'
                        }
                        result.endIndex = v6End
                    } else {
                        if (IpMarkdownStructuralScanner.scanHexColonRunSkipEnd(text, start, n) - start > 1) {
                            result.skipRunOnMiss = true
                        }
                    }
                }
            },
        )

        // Scrub client PTR / reverse-DNS / dash-encoded / arpa patterns regardless of maskTargetIp
        for ((ptrPattern, maskedPtr) in ctx.clientPtrPatterns) {
            if (out.contains(ptrPattern, ignoreCase = true)) {
                out = replaceClientPtrWithBoundary(out, ptrPattern, maskedPtr)
            }
        }

        // Fallback only for any synthetic non-IP client tokens not representable as IP/masked-IP tokens
        for (token in ctx.clientSensitiveTokens) {
            if (!looksLikeIpOrMaskedIpToken(token) && out.contains(token, ignoreCase = true)) {
                out = replaceTokenWithBoundary(
                    text = out,
                    token = token,
                    replacement = "[已隐藏本机IP]",
                )
            }
        }
        return out
    }

    private fun replaceClientPtrWithBoundary(
        text: String,
        pattern: String,
        replacement: String,
    ): String {
        if (pattern.isEmpty() || text.length < pattern.length) return text
        var idx = text.indexOf(pattern, startIndex = 0, ignoreCase = true)
        if (idx < 0) return text

        val isHexDashPtr = '-' in pattern && pattern.any { it in 'a'..'f' || it in 'A'..'F' }
        val isArpaPtr = pattern.endsWith(".in-addr.arpa", ignoreCase = true) ||
            pattern.endsWith(".ip6.arpa", ignoreCase = true)
        val sb = StringBuilder(text.length)
        var cursor = 0
        while (idx >= 0) {
            val end = idx + pattern.length
            val prevBlocked = idx > 0 && run {
                val prev = text[idx - 1]
                if (isHexDashPtr) IpMarkdownStructuralScanner.isHexChar(prev) else prev.isDigit()
            }
            val nextBlocked = end < text.length && run {
                val next = text[end]
                when {
                    isArpaPtr -> next.isLetterOrDigit() || next == '_'
                    isHexDashPtr -> IpMarkdownStructuralScanner.isHexChar(next)
                    else -> next.isDigit()
                }
            }
            if (!prevBlocked && !nextBlocked) {
                if (idx > cursor) sb.append(text, cursor, idx)
                sb.append(replacement)
                cursor = end
            } else {
                if (end > cursor) sb.append(text, cursor, end)
                cursor = end
            }
            idx = text.indexOf(pattern, startIndex = end, ignoreCase = true)
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun looksLikeIpOrMaskedIpToken(token: String): Boolean {
        for (ch in token) {
            if (!IpMarkdownStructuralScanner.isHexChar(ch) && ch != '.' && ch != ':' && ch != '*') {
                return false
            }
        }
        return token.any { it == '.' || it == ':' }
    }

    private fun matchesClientIpSemantically(ipCandidate: String, ctx: SanitizationContext): Boolean {
        if (ctx.clientIpv4Ints.isEmpty() && ctx.clientIpv6Keys.isEmpty()) return false
        val clean = ipCandidate.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (clean.isEmpty()) return false
        BackboneRouteTagger.parseIpv4ToIntOrNull(clean)?.let { v4 ->
            if (v4 in ctx.clientIpv4Ints) return true
        }
        NetBenchTargetParser.parseIpv6Hextets(clean)?.let { hextets ->
            if (canonicalIpv6Key(hextets) in ctx.clientIpv6Keys) return true
            unwrapIpv4MappedInt(hextets)?.let { mappedV4 ->
                if (mappedV4 in ctx.clientIpv4Ints) return true
            }
        }
        return false
    }

    private fun replaceTokenWithBoundary(
        text: String,
        token: String,
        replacement: String,
    ): String {
        if (token.isEmpty() || text.length < token.length) return text
        var idx = text.indexOf(token, startIndex = 0, ignoreCase = true)
        if (idx < 0) return text

        val sb = StringBuilder(text.length)
        var cursor = 0
        while (idx >= 0) {
            val end = idx + token.length
            val prevBlocked = idx > 0 && (text[idx - 1].isLetterOrDigit() || text[idx - 1] == '_')
            val nextBlocked = end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')
            if (!prevBlocked && !nextBlocked) {
                if (idx > cursor) sb.append(text, cursor, idx)
                sb.append(replacement)
                cursor = end
            } else {
                if (end > cursor) sb.append(text, cursor, end)
                cursor = end
            }
            idx = text.indexOf(token, startIndex = end, ignoreCase = true)
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun replaceTargetIpWithBoundary(
        text: String,
        targetForm: String,
        maskedForm: String,
    ): String {
        if (targetForm.isEmpty() || text.length < targetForm.length) return text
        var idx = text.indexOf(targetForm, startIndex = 0, ignoreCase = true)
        if (idx < 0) return text

        val isIpv6 = ':' in targetForm
        val isDoubleColonTerminatedV6 = isIpv6 && targetForm.endsWith("::")
        val sb = StringBuilder(text.length)
        var cursor = 0
        while (idx >= 0) {
            val end = idx + targetForm.length
            val prevBlocked = if (idx > 0) {
                val prev = text[idx - 1]
                if (isIpv6) {
                    IpMarkdownStructuralScanner.isHexChar(prev) ||
                        (prev == ':' && !IpMarkdownStructuralScanner.isLabelColonBoundary(text, idx))
                } else {
                    prev in '0'..'9' ||
                        (prev == '.' && idx >= 2 && text[idx - 2] in '0'..'9')
                }
            } else {
                false
            }
            val nextBlocked = if (end < text.length) {
                val next = text[end]
                if (isIpv6) {
                    IpMarkdownStructuralScanner.isHexChar(next) ||
                        (next == ':' && !isShortMarkdownStarAfterColon(text, end)) ||
                        (
                            isDoubleColonTerminatedV6 &&
                                IpMarkdownStructuralScanner.peekNextNonSgrChar(text, end, text.length) == '*'
                            )
                } else {
                    next in '0'..'9' ||
                        (next == '.' && end + 1 < text.length && text[end + 1] in '0'..'9')
                }
            } else {
                false
            }
            if (!prevBlocked && !nextBlocked) {
                if (idx > cursor) sb.append(text, cursor, idx)
                sb.append(maskedForm)
                cursor = end
            } else {
                if (end > cursor) sb.append(text, cursor, end)
                cursor = end
            }
            idx = text.indexOf(targetForm, startIndex = end, ignoreCase = true)
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun isShortMarkdownStarAfterColon(text: String, colonIdx: Int): Boolean {
        val nextIdx = IpMarkdownStructuralScanner.skipSgrSequences(text, colonIdx + 1, text.length)
        if (nextIdx >= text.length || text[nextIdx] != '*') return false
        var starEnd = nextIdx + 1
        while (starEnd < text.length && text[starEnd] == '*') {
            starEnd++
        }
        return (starEnd - nextIdx) in 1..3
    }

    private fun canonicalIpv6Key(hextets: IntArray): String =
        hextets.joinToString(":") { it.toString(16).padStart(4, '0') }

    private fun unwrapIpv4MappedInt(hextets: IntArray): Int? {
        if (hextets.size != 8) return null
        val isV4Mapped = hextets[0] == 0 &&
            hextets[1] == 0 &&
            hextets[2] == 0 &&
            hextets[3] == 0 &&
            hextets[4] == 0 &&
            hextets[5] == 0xFFFF
        if (!isV4Mapped) return null
        return (hextets[6] shl 16) or hextets[7]
    }

    private fun scrubWithContext(
        raw: String,
        ctx: SanitizationContext,
        maskTargetIp: Boolean,
    ): String {
        if (raw.isEmpty()) return raw
        var out = scrubClientTokensOnly(raw, ctx)
        if (!maskTargetIp) return out

        // 1. Replace all known target IPv4/IPv6 forms with canonical last-segment masks
        for ((targetForm, maskedForm) in ctx.targetIpReplacementPairs) {
            if (out.contains(targetForm, ignoreCase = true)) {
                out = replaceTargetIpWithBoundary(out, targetForm, maskedForm)
            }
        }

        // 2. Replace reverse-DNS / ARPA / dash-encoded PTR forms
        for ((ptrPattern, maskedPtr) in ctx.targetPtrPatterns) {
            if (out.contains(ptrPattern, ignoreCase = true)) {
                out = out.replace(ptrPattern, maskedPtr, ignoreCase = true)
            }
        }
        if (out.contains(".in-addr.arpa", ignoreCase = true)) {
            out = IN_ADDR_ARPA_REGEX.replace(out) { m -> "*.${m.groupValues[2]}.in-addr.arpa" }
        }
        if (out.contains(".ip6.arpa", ignoreCase = true)) {
            out = IP6_ARPA_REGEX.replace(out, "****.ip6.arpa")
        }
        if (out.contains('-')) {
            out = DASH_IPV4_PTR_REGEX.replace(out) { m ->
                "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}-*"
            }
        }

        // 3. Scrub endpoint port suffixes without corrupting HH:mm timestamps
        for ((port, endpointPortRegex) in ctx.portRegexes) {
            out = out
                .replace(endpointPortRegex, "$1")
                .replace(", port $port", "")
                .replace("(port $port)", "(已隐藏端口)")
                .replace("port $port", "port ***")
                .replace("-p $port", "-p ***")
                .replace("TCP $port", "TCP 对应端口")
                .replace("端口 $port", "已隐藏端口")
                .replace(" $port 端口", " 对应端口")
        }

        // 4. Defense-in-depth: run IpPrivacyMasker + exhaustive IPv4/IPv6 literal scanner
        out = IpPrivacyMasker.mask(out, knownServerHost = ctx.primaryTargetIp)
        out = maskAnyRemainingIpLiterals(out)
        return out
    }

    /**
     * Single-pass defense-in-depth scanner catching any remaining IPv4 or IPv6 literals
     * (including private, loopback, link-local, or uncompressed forms not covered above).
     * Idempotent on already-masked tokens (`*`).
     */
    internal fun maskAnyRemainingIpLiterals(text: String): String =
        IpMarkdownStructuralScanner.scanAndTransform(
            text = text,
            skipAnsiSgr = true,
            onMaskedIpToken = null,
            onRawIpv4 = { s, start, end, result ->
                if (IpMarkdownStructuralScanner.isStrictIpv4Range(s, start, end)) {
                    result.replacement = NetBenchTargetParser.maskTargetIpLastSegment(s.substring(start, end))
                    result.nextPrevPlainChar = '*'
                }
            },
            onRawIpv6 = { s, start, n, totalOpen, result ->
                val v6End = resolveValidIpv6TokenEnd(s, start, n, totalOpen)
                if (v6End > start) {
                    result.replacement = NetBenchTargetParser.maskTargetIpLastSegment(s.substring(start, v6End))
                    result.nextPrevPlainChar = '*'
                    result.endIndex = v6End
                } else {
                    if (IpMarkdownStructuralScanner.scanHexColonRunSkipEnd(s, start, n) - start > 1) {
                        result.skipRunOnMiss = true
                    }
                }
            },
        )

    private fun resolveValidIpv6TokenEnd(
        text: String,
        start: Int,
        n: Int,
        totalOpen: Int,
    ): Int {
        val rawEnd = scanIpv6OrMappedEnd(text, start, n)
        if (rawEnd - start < 2) return -1

        fun tryCandidate(endIdx: Int): Int {
            if (endIdx - start !in 2..45) return -1
            if (countColons(text, start, endIdx) < 2) return -1
            if (!isAcceptableIpv6End(text, start, endIdx, n, totalOpen)) return -1
            val candidate = text.substring(start, endIdx)
            return if (NetBenchTargetParser.isIpv6Literal(candidate)) endIdx else -1
        }

        fun tryStripMappedPortSuffix(spanEnd: Int): Int {
            if (spanEnd - start < 5) return -1
            var lastDot = -1
            var lastColon = -1
            for (k in spanEnd - 1 downTo start) {
                val c = text[k]
                if (lastColon < 0 && c == ':') lastColon = k
                if (lastDot < 0 && c == '.') {
                    lastDot = k
                    break
                }
            }
            if (lastDot <= start || lastColon <= lastDot) return -1
            val portLen = spanEnd - (lastColon + 1)
            if (portLen !in 1..5) return -1
            var portValue = 0
            for (k in lastColon + 1 until spanEnd) {
                val c = text[k]
                if (c !in '0'..'9') return -1
                portValue = portValue * 10 + (c - '0')
            }
            if (portValue !in 0..65535) return -1
            if (spanEnd < n) {
                val next = IpMarkdownStructuralScanner.peekNextNonSgrCharForIpRightBoundary(text, spanEnd, n)
                if (IpMarkdownStructuralScanner.isIpv6BoundaryBlocked(next)) return -1
            }
            if (lastColon - start !in 2..45) return -1
            if (countColons(text, start, lastColon) < 2) return -1
            val candidate = text.substring(start, lastColon)
            return if (NetBenchTargetParser.isIpv6Literal(candidate)) lastColon else -1
        }

        var trimmedEnd = rawEnd
        while (trimmedEnd > start && text[trimmedEnd - 1] == '.') {
            trimmedEnd--
        }
        tryCandidate(trimmedEnd).let { if (it > start) return it }

        if (trimmedEnd > start + 2 && text[trimmedEnd - 1] == ':' && text[trimmedEnd - 2] != ':') {
            tryCandidate(trimmedEnd - 1).let { if (it > start) return it }
            tryStripMappedPortSuffix(trimmedEnd - 1).let { if (it > start) return it }
        } else {
            tryStripMappedPortSuffix(trimmedEnd).let { if (it > start) return it }
        }

        // Backtrack across '.' boundaries (e.g. `<ipv6>.example`, `<ipv6>.com`, `<ipv6>.dead`,
        // `::ffff:a.b.c.d.example`) when the full span failed boundary or IPv6 validation.
        for (idx in trimmedEnd - 1 downTo start + 2) {
            if (text[idx] == '.') {
                if (idx + 1 < n && text[idx + 1] in '0'..'9') {
                    continue
                }
                tryCandidate(idx).let { if (it > start) return it }
                tryStripMappedPortSuffix(idx).let { if (it > start) return it }
                if (idx > start + 2 && text[idx - 1] == ':' && text[idx - 2] != ':') {
                    tryCandidate(idx - 1).let { if (it > start) return it }
                }
            }
        }
        return -1
    }

    private fun isAcceptableIpv6End(
        text: String,
        start: Int,
        endIdx: Int,
        n: Int,
        totalOpen: Int,
    ): Boolean {
        if (endIdx >= n) return true
        val next = IpMarkdownStructuralScanner.peekNextNonSgrCharForIpRightBoundary(text, endIdx, n)
        if (next == '\u0000') return true
        if (next == '*' && text[endIdx - 1] == ':') {
            val starStart = IpMarkdownStructuralScanner.skipSgrSequences(text, endIdx, n)
            var starEnd = starStart
            while (starEnd < n && text[starEnd] == '*') {
                starEnd++
            }
            val starRunLen = starEnd - starStart
            val afterStars = IpMarkdownStructuralScanner.peekNextNonSgrChar(text, starEnd, n)
            val afterColonAfterStar = if (afterStars == ':') {
                val colonIdx = IpMarkdownStructuralScanner.skipSgrSequences(text, starEnd, n)
                IpMarkdownStructuralScanner.peekNextNonSgrChar(text, colonIdx + 1, n)
            } else {
                '\u0000'
            }
            val isColonWildcardAfterStar = afterStars == ':' &&
                afterColonAfterStar != '\u0000' &&
                !afterColonAfterStar.isWhitespace()
            return !isColonWildcardAfterStar &&
                starRunLen in 1..3 &&
                (totalOpen >= starRunLen || IpMarkdownStructuralScanner.isItalicOpeningAfterStar(afterStars))
        }
        if (next == '.') {
            val dots = countDots(text, start, endIdx)
            val afterDotIdx = IpMarkdownStructuralScanner.skipSgrSequences(text, endIdx + 1, n)
            val afterDot = if (afterDotIdx < n) text[afterDotIdx] else '\u0000'
            if (afterDot in '0'..'9') return false
            if (afterDot == '*') {
                if (dots in 1..2) return false
                if (dots >= 3) {
                    return IpMarkdownStructuralScanner.isRawIpv4RightBoundaryAllowed(
                        text = text,
                        ipv4End = endIdx,
                        totalOpen = totalOpen,
                        n = n,
                        skipAnsiSgr = true,
                    )
                }
            }
            return true
        }
        if (!IpMarkdownStructuralScanner.isIpv6BoundaryBlocked(next)) return true
        if (next == ':' && endIdx > start + 1 && text[endIdx - 1] != ':') {
            val afterColonIdx = IpMarkdownStructuralScanner.skipSgrSequences(text, endIdx + 1, n)
            val afterColon = if (afterColonIdx < n) text[afterColonIdx] else '\u0000'
            if (afterColon == '*') {
                if (countDots(text, start, endIdx) > 0) return true
                var starEnd = afterColonIdx + 1
                while (starEnd < n && text[starEnd] == '*') {
                    starEnd++
                }
                val starCount = starEnd - afterColonIdx
                // Never split a valid `:****` masked suffix away from its IPv6 prefix
                return starCount in 1..3 ||
                    (starEnd < n && (text[starEnd].isLetterOrDigit() || text[starEnd] == '_'))
            }
            return !IpMarkdownStructuralScanner.isIpv6BoundaryBlocked(afterColon)
        }
        return false
    }

    private fun scanIpv6OrMappedEnd(text: String, start: Int, n: Int): Int {
        var pos = start
        val limit = (start + 46).coerceAtMost(n)
        var seenDoubleColonOrTwoColons = 0
        while (pos < limit) {
            val ch = text[pos]
            if (IpMarkdownStructuralScanner.isHexChar(ch)) {
                pos++
            } else if (ch == ':') {
                seenDoubleColonOrTwoColons++
                pos++
            } else if (ch == '.' && seenDoubleColonOrTwoColons >= 2) {
                pos++
            } else {
                break
            }
        }
        return pos
    }

    private fun countColons(text: String, start: Int, end: Int): Int {
        var count = 0
        for (idx in start until end) {
            if (text[idx] == ':') count++
        }
        return count
    }

    private fun countDots(text: String, start: Int, end: Int): Int {
        var count = 0
        for (idx in start until end) {
            if (text[idx] == '.') count++
        }
        return count
    }

    internal fun compactOrgName(rawOrg: String?): String? {
        var cleaned = rawOrg?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() } ?: return null
        val dbaMatch = DBA_SPLIT_REGEX.find(cleaned)
        if (dbaMatch != null) {
            cleaned = dbaMatch.groupValues[1].trim()
        }
        cleaned = cleaned
            .replace(CORPORATE_SUFFIX_REGEX, "")
            .trim(' ', ',', '.')
        if (cleaned.isEmpty()) return null
        return truncateAsciiAware(cleaned, MAX_ORG_DISPLAY_WIDTH)
    }

    private fun truncateAsciiAware(text: String, maxWidth: Int): String {
        val clean = sanitizeInline(text)
        if (clean.length <= maxWidth / 2) return clean
        return NetBenchMarkdownFormatter.truncateByDisplayWidth(clean, maxWidth)
    }

    private fun sanitizeInline(text: String): String =
        text.replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(MULTI_WHITESPACE_REGEX, " ")
            .trim()
}
