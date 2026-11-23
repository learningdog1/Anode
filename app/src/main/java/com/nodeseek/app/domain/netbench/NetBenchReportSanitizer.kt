package com.nodeseek.app.domain.netbench

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
) {
    val hasAnyResult: Boolean
        get() = icmpPing != null ||
            tcpPing != null ||
            httpProbe != null ||
            ipIntelligence != null ||
            iperfDownload != null ||
            iperfUpload != null
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
        val clientSensitiveTokens: List<String>,
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
        val displayIp = if (maskTargetIp) {
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

        // Client egress sensitive tokens (never scrub if identical to an unmasked target IP)
        val targetFormsLower = ipFormsMap.keys.map { it.lowercase() }.toSet() +
            ipFormsMap.values.map { it.lowercase() }.toSet() +
            setOf(report.targetInput.trim().lowercase())
        val rawClientCandidates = buildSet {
            report.clientNetwork?.rawIp?.trim()?.let { raw ->
                add(raw)
                addAll(NetBenchTargetParser.allCanonicalIpForms(raw))
            }
            report.proxyNetwork?.rawIp?.trim()?.let { raw ->
                add(raw)
                addAll(NetBenchTargetParser.allCanonicalIpForms(raw))
            }
            report.clientNetwork?.maskedIp?.trim()?.let(::add)
            report.proxyNetwork?.maskedIp?.trim()?.let(::add)
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
            clientSensitiveTokens = clientTokens,
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
        if (raw.isEmpty() || ctx.clientSensitiveTokens.isEmpty()) return raw
        var out = raw
        for (token in ctx.clientSensitiveTokens) {
            if (out.contains(token, ignoreCase = true)) {
                out = out.replace(token, "[已隐藏本机IP]", ignoreCase = true)
            }
        }
        return out
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
                out = out.replace(targetForm, maskedForm, ignoreCase = true)
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
        // Protect "::****" suffixes with an alphabetic boundary sentinel ('Z') so IpPrivacyMasker
        // does not re-mask the "prefix::" portion into "prefix:*:*****".
        val sentinel = "::ZZZZ_NB_MASKED_V6"
        val hadMaskedV6 = out.contains("::****")
        if (hadMaskedV6) {
            out = out.replace("::****", sentinel)
        }
        out = IpPrivacyMasker.mask(out, knownServerHost = ctx.primaryTargetIp)
        if (hadMaskedV6) {
            out = out.replace(sentinel, "::****")
        }
        out = maskAnyRemainingIpLiterals(out)
        return out
    }

    /**
     * Single-pass defense-in-depth scanner catching any remaining IPv4 or IPv6 literals
     * (including private, loopback, link-local, or uncompressed forms not covered above).
     * Idempotent on already-masked tokens (`*`).
     */
    internal fun maskAnyRemainingIpLiterals(text: String): String {
        val n = text.length
        if (n < 2) return text

        var out: StringBuilder? = null
        var flushCursor = 0
        var prevChar = '\u0000'
        var i = 0

        while (i < n) {
            val ch = text[i]

            // 1. Check IPv4 candidate `d.d.d.d`
            if (ch in '0'..'9' && !isIpv4BoundaryBlocked(prevChar)) {
                val v4End = tryScanIpv4End(text, i, n)
                if (v4End > i) {
                    val nextChar = if (v4End < n) text[v4End] else '\u0000'
                    if (!isIpv4BoundaryBlocked(nextChar)) {
                        val candidate = text.substring(i, v4End)
                        if (isStrictIpv4Token(candidate)) {
                            val masked = NetBenchTargetParser.maskTargetIpLastSegment(candidate)
                            val sb = out ?: StringBuilder(n).also { out = it }
                            if (i > flushCursor) sb.append(text, flushCursor, i)
                            sb.append(masked)
                            flushCursor = v4End
                            prevChar = '*'
                            i = v4End
                            continue
                        }
                    }
                }
            }

            // 2. Check IPv6 candidate (including IPv4-mapped `::ffff:a.b.c.d`)
            if ((isHexChar(ch) || ch == ':') && !isIpv6BoundaryBlocked(prevChar)) {
                var v6End = scanIpv6OrMappedEnd(text, i, n)
                while (v6End > i && text[v6End - 1] == '.') {
                    v6End--
                }
                val tokenLen = v6End - i
                if (tokenLen in 2..45) {
                    val nextChar = if (v6End < n) text[v6End] else '\u0000'
                    if (!isIpv6BoundaryBlocked(nextChar) && countColons(text, i, v6End) >= 2) {
                        val candidate = text.substring(i, v6End)
                        if (NetBenchTargetParser.isIpv6Literal(candidate)) {
                            val masked = NetBenchTargetParser.maskTargetIpLastSegment(candidate)
                            val sb = out ?: StringBuilder(n).also { out = it }
                            if (i > flushCursor) sb.append(text, flushCursor, i)
                            sb.append(masked)
                            flushCursor = v6End
                            prevChar = '*'
                            i = v6End
                            continue
                        }
                    }
                }
            }

            prevChar = ch
            i++
        }

        val sb = out ?: return text
        if (flushCursor < n) sb.append(text, flushCursor, n)
        return sb.toString()
    }

    private fun isIpv4BoundaryBlocked(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '.' || ch == '*'

    private fun isIpv6BoundaryBlocked(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == ':' || ch == '*'

    private fun isHexChar(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    private fun tryScanIpv4End(text: String, start: Int, n: Int): Int {
        var pos = start
        for (octet in 0 until 4) {
            if (octet > 0) {
                if (pos >= n || text[pos] != '.') return -1
                pos++
            }
            val octStart = pos
            while (pos < n && pos - octStart < 3 && text[pos] in '0'..'9') {
                pos++
            }
            if (pos == octStart) return -1
            if (pos < n && text[pos] in '0'..'9') return -1
        }
        return pos
    }

    private fun isStrictIpv4Token(candidate: String): Boolean {
        val parts = candidate.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty()) return false
            if (p.length > 1 && p[0] == '0') return false
            val v = p.toIntOrNull() ?: return false
            if (v !in 0..255) return false
        }
        return true
    }

    private fun scanIpv6OrMappedEnd(text: String, start: Int, n: Int): Int {
        var pos = start
        val limit = (start + 46).coerceAtMost(n)
        var seenDoubleColonOrTwoColons = 0
        while (pos < limit) {
            val ch = text[pos]
            if (isHexChar(ch)) {
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
