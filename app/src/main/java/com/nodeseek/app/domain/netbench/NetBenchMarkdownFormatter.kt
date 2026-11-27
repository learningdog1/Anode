package com.nodeseek.app.domain.netbench

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Generates privacy-safe, forum-ready NodeSeek Markdown benchmark reports in two styles:
 * - [NetBenchMarkdownStyle.FULL_TABLE]: Comprehensive standalone review post featuring an
 *   environment summary blockquote, target & IP quality table, deep latency/jitter/percentile
 *   analysis + per-packet RTT waveform & sequence log, iperf3 throughput comparison table +
 *   per-second interval logs (`[ ID] Interval / Transfer / Bitrate / Trend` + `[SUM] sender/receiver`),
 *   and an automated objective route health verdict.
 * - [NetBenchMarkdownStyle.REPLY_CARD]: Rich structured blockquote card + per-second iperf3 log
 *   tailored for replying inside existing benchmark threads.
 *
 * SECURITY / PRIVACY:
 * - Consumes [SanitizedNetBenchReport] produced by [NetBenchReportSanitizer] (the exact same
 *   shared presentation model used by [com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator]).
 * - Local client raw/masked egress IPs NEVER appear in generated output.
 * - Probe failures always display standardized [NetBenchErrorCategory] labels rather than raw
 *   Java/OkHttp socket exception messages.
 */
object NetBenchMarkdownFormatter {

    const val EMPTY_CELL_PLACEHOLDER: String = "—"

    private const val MAX_HOST_DISPLAY_WIDTH: Int = 30
    private const val MAX_RESULT_CELL_DISPLAY_WIDTH: Int = 34
    private const val MAX_NOTE_CELL_DISPLAY_WIDTH: Int = 58
    private const val MAX_ERROR_DISPLAY_WIDTH: Int = 42

    private val SPARK_BARS: CharArray = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')

    private val DISPLAY_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")

    fun format(
        report: NetBenchReport,
        options: NetBenchMarkdownOptions = NetBenchMarkdownOptions(),
    ): String {
        val sanitized = NetBenchReportSanitizer.sanitize(
            report = report,
            maskTargetIp = options.maskTargetIp,
            includeClientNetwork = options.includeClientNetwork,
        )
        return formatSanitized(
            sanitized = sanitized,
            options = options,
            rawReportForDefenseInDepth = report,
        )
    }

    fun formatSanitized(
        sanitized: SanitizedNetBenchReport,
        options: NetBenchMarkdownOptions = NetBenchMarkdownOptions(
            maskTargetIp = sanitized.maskTargetIp,
            includeClientNetwork = sanitized.includeClientNetwork,
        ),
        rawReportForDefenseInDepth: NetBenchReport? = null,
    ): String {
        val protectedImageUrls = LinkedHashMap<String, String>()
        val defenseScrubber: ((String) -> String)? = when {
            rawReportForDefenseInDepth != null -> NetBenchReportSanitizer.createScrubber(
                report = rawReportForDefenseInDepth,
                maskTargetIp = sanitized.maskTargetIp,
            )
            sanitized.maskTargetIp -> { text -> NetBenchReportSanitizer.maskAnyRemainingIpLiterals(text) }
            else -> null
        }
        val rawMarkdown = when (options.style) {
            NetBenchMarkdownStyle.REPLY_CARD -> formatReplyCard(sanitized, options)
            NetBenchMarkdownStyle.FULL_TABLE -> formatFullTable(
                sanitized = sanitized,
                options = options,
                protectedImageUrls = protectedImageUrls,
                defenseScrubber = defenseScrubber,
            )
        }
        var scrubbed = defenseScrubber?.invoke(rawMarkdown) ?: rawMarkdown
        for ((token, cleanUrl) in protectedImageUrls) {
            scrubbed = scrubbed.replace(token, cleanUrl)
        }
        return scrubbed
    }

    internal fun sanitizeMarkdownImageUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return null
        }
        if (trimmed.any { it == '\n' || it == '\r' || it.isISOControl() }) return null
        return trimmed
            .replace(" ", "%20")
            .replace("(", "%28")
            .replace(")", "%29")
    }

    private fun buildMarkdownImageEmbed(
        kind: NetBenchReportCardKind,
        uploadedImages: NetBenchUploadedImages,
        protectedUrls: MutableMap<String, String>,
    ): String? {
        val cleanUrl = sanitizeMarkdownImageUrl(uploadedImages.urlFor(kind)) ?: return null
        val token = "__NETBENCH_NODEIMAGE_URL_${protectedUrls.size}__"
        protectedUrls[token] = cleanUrl
        return "![${kind.altTitle}]($token)"
    }

    // ── Style 1: Reply Card (Rich Blockquote + iperf3 Interval Log) ──────────────────────────

    private fun formatReplyCard(
        sanitized: SanitizedNetBenchReport,
        options: NetBenchMarkdownOptions,
    ): String {
        val timestamp = resolveTimestampLabel(options)
        val lines = mutableListOf<String>()

        if (sanitized.clientEnv != null) {
            val envBadge = buildClientEnvBadge(sanitized)
            lines += "> 📡 **本机网络实测（$envBadge）** · `$timestamp`"
            lines += "> - **路由模式**：${buildRouteModeSummary(sanitized)}"
        } else {
            lines += "> 📡 **移动端网络实测** · `$timestamp`"
        }

        val targetLine = buildTargetDisplay(sanitized.target, sanitized.maskTargetIp)
        if (targetLine.isNotEmpty()) {
            lines += "> - **目标节点**：$targetLine"
        }

        sanitized.ipIntelligence?.let { intel ->
            lines += "> - **IP 归属与属性**：${formatIpIntelCompact(intel)}"
        }

        sanitized.icmpPing?.let { ping ->
            lines += "> - **Ping (${ping.sent}次)**：${formatIcmpPingCompact(ping)}"
        }

        sanitized.tcpPing?.let { tcp ->
            val deltaSuffix = buildTcpVsIcmpShortTag(sanitized.icmpPing, tcp)
                ?.let { "（$it）" }
                .orEmpty()
            lines += "> - **TCPing (${tcp.sent}次)**：${formatTcpPingCompact(tcp)}$deltaSuffix"
        }

        sanitized.httpProbe?.let { http ->
            lines += "> - **HTTP 探测**：${formatHttpCompact(http)}"
        }

        sanitized.forwardTraceroute?.let { trace ->
            lines += "> - **去程路由**：${formatTracerouteCompact(trace)}"
        }

        sanitized.reverseTraceroute?.let { trace ->
            lines += "> - **回程路由（VPS → 本机）**：${formatTracerouteCompact(trace)}"
        }

        val iperfStreams = listOfNotNull(
            sanitized.iperfDownload?.parallelStreams,
            sanitized.iperfUpload?.parallelStreams,
        )
        val unifiedStreams = iperfStreams.distinct().singleOrNull()?.takeIf { it > 1 }

        val iperfParts = buildList {
            sanitized.iperfDownload?.let { dl ->
                val dirStreams = if (unifiedStreams == null && dl.parallelStreams > 1) " (-P ${dl.parallelStreams})" else ""
                if (dl.success) {
                    val extras = buildList {
                        add("峰值 `${formatMbps(dl.maxIntervalMbps)}`")
                        add("流量 `${formatBytes(dl.bytesTransferred)}`")
                        dl.stabilityPercent?.let { add("稳定度 `${formatPercent(it)}`") }
                        dl.retransmits?.takeIf { it >= 0 }?.let { add("重传 `$it`") }
                    }.joinToString(" · ")
                    add("下载$dirStreams **${formatMbps(dl.effectiveMbps)}** ($extras)")
                } else {
                    val reason = truncateByDisplayWidth(
                        sanitizeInline(dl.publicErrorMessage ?: NetBenchErrorCategory.REQUEST_FAILED.publicLabel),
                        MAX_ERROR_DISPLAY_WIDTH,
                    )
                    add("下载$dirStreams 失败 ($reason)")
                }
            }
            sanitized.iperfUpload?.let { ul ->
                val dirStreams = if (unifiedStreams == null && ul.parallelStreams > 1) " (-P ${ul.parallelStreams})" else ""
                if (ul.success) {
                    val extras = buildList {
                        add("峰值 `${formatMbps(ul.maxIntervalMbps)}`")
                        add("流量 `${formatBytes(ul.bytesTransferred)}`")
                        ul.retransmits?.takeIf { it >= 0 }?.let { add("重传 `$it`") }
                    }.joinToString(" · ")
                    add("上传$dirStreams **${formatMbps(ul.effectiveMbps)}** ($extras)")
                } else {
                    val reason = truncateByDisplayWidth(
                        sanitizeInline(ul.publicErrorMessage ?: NetBenchErrorCategory.REQUEST_FAILED.publicLabel),
                        MAX_ERROR_DISPLAY_WIDTH,
                    )
                    add("上传$dirStreams 失败 ($reason)")
                }
            }
        }
        if (iperfParts.isNotEmpty()) {
            val streamSuffix = if (unifiedStreams != null) " (-P $unifiedStreams)" else ""
            lines += "> - **iperf3 测速$streamSuffix**：${iperfParts.joinToString(" ｜ ")}"
        }

        if (sanitized.hasAnyResult) {
            val briefVerdict = buildOneLineVerdict(sanitized)
            if (briefVerdict.isNotEmpty()) {
                lines += "> - **综合简评**：$briefVerdict"
            }
        }

        val sb = StringBuilder()
        sb.append(lines.joinToString("\n"))

        val successfulIperf = listOfNotNull(sanitized.iperfDownload, sanitized.iperfUpload)
            .filter { it.success }
        if (successfulIperf.isNotEmpty()) {
            sb.append("\n\n```text\n")
            successfulIperf.forEachIndexed { idx, res ->
                if (idx > 0) sb.append("\n\n")
                sb.append(buildIperf3IntervalLogText(res))
            }
            sb.append("\n```")
        }

        return sb.toString()
    }

    // ── Style 2: Full Forum Benchmark Report (Default for Standalone Posts) ──────────────────

    private fun formatFullTable(
        sanitized: SanitizedNetBenchReport,
        options: NetBenchMarkdownOptions,
        protectedImageUrls: MutableMap<String, String> = LinkedHashMap(),
        defenseScrubber: ((String) -> String)? = null,
    ): String {
        val timestamp = resolveTimestampLabel(options)
        val sb = StringBuilder()

        // 1. Title & Test Environment Blockquote Summary Card
        sb.appendLine("## 📡 移动端网络实测报告")
        sb.appendLine()
        sb.appendLine(buildEnvironmentBlockquote(sanitized, timestamp))

        val cellScrubber: (String) -> String = defenseScrubber ?: { it }

        if (!sanitized.hasAnyResult) {
            sb.appendLine()
            sb.appendLine("### 📋 测试状态")
            sb.appendLine()
            sb.appendLine(
                renderAlignedMarkdownTable(
                    headers = listOf("测试项目", "实测结果", "详细说明"),
                    rows = listOf(listOf("测试状态", EMPTY_CELL_PLACEHOLDER, "暂无可导出的测试结果")),
                    cellPreprocessor = cellScrubber,
                ),
            )
            sb.appendLine()
            sb.append(buildSignatureFooter(timestamp))
            return sb.toString()
        }

        // 2. Target Node & IP Intelligence Table + Visual Card
        val targetAndIpRows = buildTargetAndIpTableRows(sanitized)
        val ipCardEmbed = if (sanitized.ipIntelligence != null) {
            buildMarkdownImageEmbed(
                kind = NetBenchReportCardKind.IP_INTELLIGENCE,
                uploadedImages = options.uploadedImages,
                protectedUrls = protectedImageUrls,
            )
        } else {
            null
        }
        if (targetAndIpRows.isNotEmpty() || ipCardEmbed != null) {
            sb.appendLine()
            sb.appendLine("### 🌐 目标节点与 IP 质量鉴定")
            if (ipCardEmbed != null) {
                sb.appendLine()
                sb.appendLine(ipCardEmbed)
            }
            if (targetAndIpRows.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine(
                    renderAlignedMarkdownTable(
                        headers = listOf("测试项目", "实测结果", "详细说明"),
                        rows = targetAndIpRows,
                        cellPreprocessor = cellScrubber,
                    ),
                )
            }
        }

        // 3. Network Latency & Link Stability Analysis + Visual Waveform Cards
        val latencyRows = buildLatencyAnalysisRows(sanitized)
        val icmpCardEmbed = if (sanitized.icmpPing != null && sanitized.icmpPing.sent > 0) {
            buildMarkdownImageEmbed(
                kind = NetBenchReportCardKind.ICMP_PING,
                uploadedImages = options.uploadedImages,
                protectedUrls = protectedImageUrls,
            )
        } else {
            null
        }
        val tcpCardEmbed = if (sanitized.tcpPing != null && sanitized.tcpPing.sent > 0) {
            buildMarkdownImageEmbed(
                kind = NetBenchReportCardKind.TCP_PING,
                uploadedImages = options.uploadedImages,
                protectedUrls = protectedImageUrls,
            )
        } else {
            null
        }
        if (latencyRows.isNotEmpty() || icmpCardEmbed != null || tcpCardEmbed != null) {
            sb.appendLine()
            sb.appendLine("### ⚡ 网络延迟与链路稳定性分析")
            if (icmpCardEmbed != null) {
                sb.appendLine()
                sb.appendLine(icmpCardEmbed)
            }
            if (tcpCardEmbed != null) {
                sb.appendLine()
                sb.appendLine(tcpCardEmbed)
            }
            if (latencyRows.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine(
                    renderAlignedMarkdownTable(
                        headers = listOf("测试项目", "实测结果", "波动与分位统计", "链路与对比分析"),
                        rows = latencyRows,
                        cellPreprocessor = cellScrubber,
                    ),
                )
            }

            val packetLog = buildPerPacketRttLogText(
                icmp = sanitized.icmpPing,
                tcp = sanitized.tcpPing,
            )
            if (packetLog != null) {
                sb.appendLine()
                sb.appendLine("```text")
                sb.appendLine(packetLog)
                sb.appendLine("```")
            }
        }

        // 4. iperf3 Throughput & Per-Second Interval Breakdown + Visual Gauge Card
        val iperfResults = listOfNotNull(sanitized.iperfDownload, sanitized.iperfUpload)
        val iperfCardEmbed = if (iperfResults.any { it.success }) {
            buildMarkdownImageEmbed(
                kind = NetBenchReportCardKind.IPERF3,
                uploadedImages = options.uploadedImages,
                protectedUrls = protectedImageUrls,
            )
        } else {
            null
        }
        if (iperfResults.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 🚀 iperf3 带宽吞吐实测全过程")
            if (iperfCardEmbed != null) {
                sb.appendLine()
                sb.appendLine(iperfCardEmbed)
            }
            sb.appendLine()
            val iperfTableRows = iperfResults.map { buildIperfSummaryRow(it) }
            sb.appendLine(
                renderAlignedMarkdownTable(
                    headers = listOf("测速方向", "实测均速", "峰值 / 最低分段", "并发 · 时长 · 总流量", "稳定度与重传"),
                    rows = iperfTableRows,
                    cellPreprocessor = cellScrubber,
                ),
            )

            for (res in iperfResults) {
                if (res.success) {
                    val dirTitle = if (res.direction == Iperf3Direction.DOWNLOAD) {
                        val pNote = if (res.parallelStreams > 1) " · -P ${res.parallelStreams} 并发" else ""
                        "#### 📥 iperf3 下载测速每段明细（`-R` 服务端 → 手机端$pNote）"
                    } else {
                        val pNote = if (res.parallelStreams > 1) " · -P ${res.parallelStreams} 并发" else ""
                        "#### 📤 iperf3 上传测速每段明细（手机端 → 服务端$pNote）"
                    }
                    sb.appendLine()
                    sb.appendLine(dirTitle)
                    sb.appendLine()
                    sb.appendLine("```text")
                    sb.appendLine(buildIperf3IntervalLogText(res))
                    sb.appendLine("```")
                }
            }
        }

        // 4.5 Visual Traceroute / MTR & Reverse Trace (only when present)
        val traces = listOfNotNull(sanitized.forwardTraceroute, sanitized.reverseTraceroute)
        if (traces.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 🧭 路由追踪与骨干网识别 (MTR / 回程)")
            sb.appendLine()
            val summaryRows = traces.map { buildTracerouteSummaryRow(it) }
            sb.appendLine(
                renderAlignedMarkdownTable(
                    headers = listOf("方向 / 时段", "路由识别标签", "跳数与终点状态", "丢包与延迟概览"),
                    rows = summaryRows,
                    cellPreprocessor = cellScrubber,
                ),
            )
            for (trace in traces) {
                if (trace.hops.isNotEmpty()) {
                    val subHeading = if (trace.direction == TracerouteDirection.REVERSE) {
                        val src = trace.sourceLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        "#### 🔁 回程逐跳明细（VPS$src → 本机出口 · `${trace.toolName}`）"
                    } else {
                        "#### 🛫 去程逐跳明细（本机 → 目标节点 · `${trace.toolName}`）"
                    }
                    sb.appendLine()
                    sb.appendLine(subHeading)
                    sb.appendLine()
                    val hopRows = trace.hops.map { hop -> buildTracerouteHopRow(hop) }
                    sb.appendLine(
                        renderAlignedMarkdownTable(
                            headers = listOf("#", "节点 IP", "骨干标签 / ASN", "归属地", "丢包", "均延 / 峰值"),
                            rows = hopRows,
                            cellPreprocessor = cellScrubber,
                        ),
                    )
                }
            }
        }

        // 5. Comprehensive Objective Verdict & Signature Footer
        val verdictBullets = buildComprehensiveVerdict(sanitized)
        if (verdictBullets.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 🩺 综合线路体检结论")
            sb.appendLine()
            for (bullet in verdictBullets) {
                sb.appendLine("- $bullet")
            }
        }

        sb.appendLine()
        sb.appendLine("---")
        sb.append(buildSignatureFooter(timestamp))
        return sb.toString()
    }

    // ── Section Builders ─────────────────────────────────────────────────────────────────────

    private fun resolveTimestampLabel(options: NetBenchMarkdownOptions): String =
        options.timestampLabel?.takeIf { it.isNotBlank() }
            ?: LocalDateTime.now(DEFAULT_ZONE).format(DISPLAY_TIME_FORMATTER)

    private fun buildEnvironmentBlockquote(
        sanitized: SanitizedNetBenchReport,
        timestamp: String,
    ): String {
        val lines = mutableListOf<String>()
        lines += "> - **测试时间**：`$timestamp`（北京时间）"

        if (sanitized.clientEnv != null) {
            lines += "> - **测试终端网络**：${buildDetailedClientEnvSummary(sanitized.clientEnv)}"
        }

        lines += "> - **测试路由模式**：${buildRouteModeSummary(sanitized)}"

        buildDnsResolutionSummary(sanitized.target)?.let { dnsLine ->
            lines += "> - **域名解析与协议栈**：$dnsLine"
        }

        return lines.joinToString("\n")
    }

    private fun buildDetailedClientEnvSummary(client: SanitizedClientEnvPresentation): String {
        if (!client.hasDetectedClientInfo) {
            return "本机移动终端 · ${client.ipFamily.label}（已自动隐藏本机出口 IP）"
        }

        val parts = mutableListOf<String>()
        val carrier = sanitizeInline(client.carrierDisplayName).ifBlank { "本机网络" }
        parts += "**$carrier**"

        client.regionOrCountry?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() }?.let {
            parts += it
        }

        client.asn?.trim()?.takeIf { it.isNotEmpty() }?.let { normAsn ->
            parts += "`${sanitizeCodeInline(normAsn)}`"
        }

        client.compactIspName?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() }?.let {
            parts += it
        }

        parts += client.ipFamily.label

        client.transportLabel?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() }?.let {
            parts += it
        }

        parts += "已自动隐藏本机出口 IP"
        return parts.joinToString(" · ")
    }

    private fun buildRouteModeSummary(sanitized: SanitizedNetBenchReport): String {
        val client = sanitized.clientEnv
        val ping = sanitized.icmpPing
        val ifaceSuffix = ping?.boundInterfaceName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "（绑定网卡 `${sanitizeCodeInline(it)}`）" }
            .orEmpty()

        return when {
            sanitized.testedViaProxy -> {
                val proxyCarrier = client?.proxyCarrierDisplayName?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() }
                if (proxyCarrier != null) {
                    "经由代理/VPN 链路测试（代理节点：$proxyCarrier · 已隐藏代理 IP）"
                } else {
                    "经由代理/VPN 链路测试"
                }
            }
            client?.routeKind == EgressRouteKind.PROXY_FALLBACK ->
                "代理出口(直连受限，系统 VPN 策略阻止绑定物理网卡)"
            ping?.bindingMode == IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK ->
                "TCP/HTTP/iperf3 直连底层物理网卡；ICMP Ping 子进程跟随系统默认路由"
            else ->
                "底层物理网卡直连$ifaceSuffix（绕过系统代理/VPN，实测真实去程）"
        }
    }

    private fun buildDnsResolutionSummary(target: SanitizedTargetPresentation): String? {
        if (target.hasResolvedTarget && target.formattedEndpoint != null) {
            return if (target.isDomain && !target.displayHost.isNullOrBlank()) {
                val safeHost = sanitizeCodeInline(truncateByDisplayWidth(target.displayHost, MAX_HOST_DISPLAY_WIDTH))
                val parts = mutableListOf<String>()
                parts += "域名 `$safeHost` → `${target.formattedEndpoint}`"
                parts += target.ipFamily.label
                if (target.dnsDurationMs > 0.0) {
                    parts += "DNS 解析耗时 `${formatMs(target.dnsDurationMs)}`"
                }
                if (target.resolvedRecordCount > 1) {
                    parts += "共 ${target.resolvedRecordCount} 条记录"
                }
                parts.joinToString(" · ")
            } else {
                "直连目标 `${target.formattedEndpoint}` · ${target.ipFamily.label} 协议栈（免 DNS 解析）"
            }
        }
        if (target.formattedEndpoint == null) return null
        val family = target.ipFamily.takeIf { it != IpFamily.UNKNOWN }?.label ?: "自动栈"
        return "目标协议栈 $family"
    }

    private fun buildTargetAndIpTableRows(sanitized: SanitizedNetBenchReport): List<List<String>> {
        val rows = mutableListOf<List<String>>()

        sanitized.clientEnv?.let { client ->
            val envBadge = buildClientEnvBadge(sanitized).ifBlank { "本机网络" }
            val envDetail = buildList {
                client.regionName?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() }?.let { add(it) }
                client.asn?.trim()?.takeIf { it.isNotEmpty() }?.let { add("`${sanitizeCodeInline(it)}`") }
                client.transportLabel?.let(::sanitizeInline)?.takeIf { it.isNotEmpty() }?.let { add(it) }
                add("已自动隐藏本机出口 IP")
            }.joinToString(" · ")
            rows += listOf("本机网络环境", envBadge, envDetail)
        }

        buildTargetTableRow(sanitized.target, sanitized.maskTargetIp)?.let { rows += it }

        sanitized.ipIntelligence?.let { intel ->
            val cleanLoc = truncateByDisplayWidth(
                text = if (intel.locationSummary.isNotEmpty()) {
                    "${intel.flagEmoji} ${intel.locationSummary}"
                } else {
                    EMPTY_CELL_PLACEHOLDER
                },
                maxWidth = MAX_RESULT_CELL_DISPLAY_WIDTH,
            )
            val geoMeta = buildList {
                intel.ipFamily.takeIf { it != IpFamily.UNKNOWN }?.let { add(it.label) }
                intel.countryCode?.let { add("国家码 `${sanitizeCodeInline(it)}`") }
                intel.geoSource?.let { add("Geo: ${sanitizeInline(it)}") }
            }.joinToString(" · ").ifBlank { EMPTY_CELL_PLACEHOLDER }
            rows += listOf("归属地与协议栈", cleanLoc, geoMeta)

            val cleanAsn = intel.asn?.let { "`${sanitizeCodeInline(it)}`" }
            val cleanOrg = intel.compactOrgName
            val cleanIsp = intel.compactIspName
            val asnResult = cleanAsn ?: cleanOrg ?: EMPTY_CELL_PLACEHOLDER
            val orgAndUsage = buildList {
                if (cleanAsn != null && cleanOrg != null) add(cleanOrg)
                if (cleanIsp != null) add("ISP: $cleanIsp")
                if (intel.usageType != IpUsageType.UNKNOWN) add("**${intel.usageType.label}**")
            }.joinToString(" · ").ifBlank { EMPTY_CELL_PLACEHOLDER }
            rows += listOf("归属地与 ASN", asnResult, truncateByDisplayWidth(orgAndUsage, MAX_NOTE_CELL_DISPLAY_WIDTH))

            val usageResult = if (intel.usageType != IpUsageType.UNKNOWN) {
                "**${intel.usageType.label}**"
            } else {
                EMPTY_CELL_PLACEHOLDER
            }
            val usageNote = when (intel.usageType) {
                IpUsageType.ISP_RESIDENTIAL_OR_BUSINESS -> "运营商宽带属性，流媒体与风控识别友好度较高"
                IpUsageType.HOSTING_DATACENTER -> "数据中心服务器机房属性，适合建站与大带宽传输"
                IpUsageType.ANYCAST -> "Anycast 全球任播接入节点"
                IpUsageType.UNKNOWN -> "暂未识别明确机房或家宽标签"
            }
            rows += listOf("机房/家宽属性", usageResult, usageNote)

            val cleanEvidence = formatFullOriginEvidence(intel)
            rows += listOf(
                "IP 原生鉴定",
                "**${intel.originType.label}**",
                cleanEvidence,
            )
        }

        return rows
    }

    private fun buildLatencyAnalysisRows(sanitized: SanitizedNetBenchReport): List<List<String>> {
        val rows = mutableListOf<List<String>>()

        sanitized.icmpPing?.let { ping ->
            rows += buildDetailedIcmpRow(ping)
        }

        sanitized.tcpPing?.let { tcp ->
            rows += buildDetailedTcpRow(
                tcp = tcp,
                icmp = sanitized.icmpPing,
            )
        }

        sanitized.httpProbe?.let { http ->
            if (http.success && http.totalMs != null) {
                val status = http.statusCode?.let { "HTTP $it" } ?: "成功"
                val protoPart = http.protocol?.let { "`${sanitizeCodeInline(it)}`" }
                val timingBreakdown = buildList {
                    http.tlsHandshakeMs?.takeIf { it > 0.0 }?.let { add("TLS 握手 `${formatMs(it)}`") }
                    http.ttfbMs?.let { add("TTFB `${formatMs(it)}`") }
                }.joinToString(" · ").ifBlank { "首字节响应正常" }
                val detail = listOfNotNull(status, protoPart?.let { "协议 $it" }, "应用层连通正常")
                    .joinToString(" · ")
                rows += listOf("HTTP 延迟", "**${formatMs(http.totalMs)}**", timingBreakdown, detail)
            } else {
                val reason = truncateByDisplayWidth(
                    sanitizeInline(http.publicErrorMessage ?: NetBenchErrorCategory.REQUEST_FAILED.publicLabel),
                    MAX_ERROR_DISPLAY_WIDTH,
                )
                rows += listOf(
                    "HTTP 延迟",
                    "**探测失败**",
                    EMPTY_CELL_PLACEHOLDER,
                    reason.ifBlank { EMPTY_CELL_PLACEHOLDER },
                )
            }
        }

        return rows
    }

    private fun buildDetailedIcmpRow(ping: SanitizedIcmpPingPresentation): List<String> {
        if (ping.received > 0 && ping.avgMs != null && ping.minMs != null && ping.maxMs != null) {
            val main = "**${formatMs(ping.avgMs)}** · 丢包 `${formatPercent(ping.lossPercent)}`"
            val sortedRtts = ping.samples.mapNotNull { s -> s.rttMs?.takeIf { s.success } }.sorted()
            val rangeMs = (ping.maxMs - ping.minMs).coerceAtLeast(0.0)
            val p50 = computePercentile(sortedRtts, 50.0)
            val p95 = computePercentile(sortedRtts, 95.0)

            val statsCell = buildList {
                add("${formatNumber1(ping.minMs)}~${formatMs(ping.maxMs)} (极差 ${formatMs(rangeMs)})")
                add("抖动 ${formatMs(ping.jitterMdevMs ?: 0.0)}")
                if (p50 != null && p95 != null) {
                    add("P50 ${formatNumber1(p50)} / P95 ${formatMs(p95)}")
                }
            }.joinToString(" · ")

            val ttlNote = extractTtlSummary(ping.samples)
            val routeNote = when (ping.bindingMode) {
                IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND -> {
                    val iface = ping.boundInterfaceName
                    if (iface != null) "物理网卡直连 `${sanitizeCodeInline(iface)}`" else "已绑定底层物理网络"
                }
                IcmpNetworkBindingMode.INTERFACE_BOUND -> {
                    val iface = ping.boundInterfaceName
                    if (iface != null) "网卡 `${sanitizeCodeInline(iface)}`" else "已指定底层网卡"
                }
                IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK -> "默认路由"
                IcmpNetworkBindingMode.SYSTEM_DEFAULT -> "系统默认路由"
            }
            val detailCell = listOfNotNull(
                "${ping.received}/${ping.sent} 收包",
                ttlNote,
                routeNote,
            ).joinToString(" · ")

            return listOf("Ping 延迟", main, statsCell, detailCell)
        }

        val failureLabel = if (ping.sent == 0) "**执行失败**" else "**超时 / 禁 Ping**"
        val fallbackReason = if (ping.sent == 0) "请求失败" else "连接超时"
        val reason = truncateByDisplayWidth(
            sanitizeInline(ping.publicErrorMessage ?: fallbackReason),
            MAX_ERROR_DISPLAY_WIDTH,
        )
        val routeNote = if (ping.bindingMode == IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK) "默认路由" else null
        val detail = listOfNotNull("${ping.received}/${ping.sent} 收包", routeNote, reason.ifBlank { null })
            .joinToString(" · ")
            .ifBlank { EMPTY_CELL_PLACEHOLDER }
        return listOf("Ping 延迟", failureLabel, "丢包 `${formatPercent(ping.lossPercent)}`", detail)
    }

    private fun buildDetailedTcpRow(
        tcp: SanitizedTcpPingPresentation,
        icmp: SanitizedIcmpPingPresentation?,
    ): List<String> {
        val successPct = if (tcp.sent > 0) (tcp.received.toDouble() * 100.0 / tcp.sent.toDouble()) else 0.0
        val portDesc = tcp.tablePortLabel

        if (tcp.received > 0 && tcp.avgMs != null && tcp.minMs != null && tcp.maxMs != null) {
            val main = "**${formatMs(tcp.avgMs)}** · 丢包 `${formatPercent(tcp.lossPercent)}`"
            val sortedRtts = tcp.samples.mapNotNull { s -> s.rttMs?.takeIf { s.success } }.sorted()
            val rangeMs = (tcp.maxMs - tcp.minMs).coerceAtLeast(0.0)
            val p50 = computePercentile(sortedRtts, 50.0)
            val p95 = computePercentile(sortedRtts, 95.0)

            val statsCell = buildList {
                add("${formatNumber1(tcp.minMs)}~${formatMs(tcp.maxMs)} (极差 ${formatMs(rangeMs)})")
                add("抖动 ${formatMs(tcp.jitterMdevMs ?: 0.0)}")
                if (p50 != null && p95 != null) {
                    add("P50 ${formatNumber1(p50)} / P95 ${formatMs(p95)}")
                }
            }.joinToString(" · ")

            val comparison = buildTcpVsIcmpComparison(icmp, tcp)
            val detailCell = listOfNotNull(
                "$portDesc (成功率 ${formatPercent(successPct)}, ${tcp.received}/${tcp.sent})",
                comparison,
            ).joinToString(" · ")

            return listOf("TCPing 延迟", main, statsCell, detailCell)
        }

        val reason = truncateByDisplayWidth(
            sanitizeInline(tcp.publicErrorMessage ?: NetBenchErrorCategory.CONNECTION_TIMEOUT.publicLabel),
            MAX_ERROR_DISPLAY_WIDTH,
        )
        val detail = "$portDesc · 成功率 ${formatPercent(successPct)} · ${reason.ifBlank { EMPTY_CELL_PLACEHOLDER }}"
        return listOf("TCPing 延迟", "**超时 / 不可达**", "丢包 `${formatPercent(tcp.lossPercent)}`", detail)
    }

    private fun buildIperfSummaryRow(result: SanitizedIperf3Presentation): List<String> {
        val dirLabel = if (result.direction == Iperf3Direction.DOWNLOAD) {
            "iperf3 下载 (-R)"
        } else {
            "iperf3 上传"
        }

        if (!result.success) {
            val streamPrefix = if (result.parallelStreams > 1) "-P ${result.parallelStreams} 并发 · " else ""
            val reason = truncateByDisplayWidth(
                streamPrefix + sanitizeInline(result.publicErrorMessage ?: NetBenchErrorCategory.REQUEST_FAILED.publicLabel),
                MAX_NOTE_CELL_DISPLAY_WIDTH,
            )
            return listOf(
                dirLabel,
                "**测速失败**",
                EMPTY_CELL_PLACEHOLDER,
                if (result.parallelStreams > 1) "-P ${result.parallelStreams} 并发" else "单流",
                reason.ifBlank { EMPTY_CELL_PLACEHOLDER },
            )
        }

        val avgCell = buildString {
            append("**${formatMbps(result.effectiveMbps)}**")
            if (result.direction == Iperf3Direction.UPLOAD) {
                // Upload headline is already the VPS receiver figure; show the local send rate for reference.
                val cli = result.clientMbps.takeIf { it > 0.0 && result.serverMbps?.takeIf { s -> s > 0.0 } != null }
                if (cli != null) {
                    append(" (本机发送 ${formatMbps(cli)})")
                }
            } else {
                val srv = result.serverMbps?.takeIf { it > 0.0 }
                if (srv != null) {
                    append(" (服务端 ${formatMbps(srv)})")
                }
            }
        }

        val peakMinCell = buildString {
            append("峰值 `${formatMbps(result.maxIntervalMbps)}`")
            val minSeg = result.minIntervalMbps
            if (minSeg != null && result.measuredIntervals.size >= 2) {
                append(" / 最低 `${formatMbps(minSeg)}`")
            }
        }

        val streamAndVolCell = buildList {
            if (result.parallelStreams > 1) {
                add("-P ${result.parallelStreams} 并发")
            } else {
                add("单流")
            }
            add("耗时 ${formatSeconds(result.durationSec)}")
            if (result.omitSec > 0) {
                add("剔除预热 ${result.omitSec}s")
            }
            add("传输 ${formatBytes(result.bytesTransferred)}")
        }.joinToString(" · ")

        val stabilityAndRetrCell = buildList {
            val stab = result.stabilityPercent
            val std = result.intervalStdDevMbps
            if (stab != null && std != null) {
                add("稳定度 `${formatPercent(stab)}` (±${formatMbps(std)})")
            } else {
                add("单段采样")
            }
            result.retransmits?.takeIf { it >= 0 }?.let {
                add("重传 `$it` 次")
            }
        }.joinToString(" · ").ifBlank { EMPTY_CELL_PLACEHOLDER }

        return listOf(dirLabel, avgCell, peakMinCell, streamAndVolCell, stabilityAndRetrCell)
    }

    // ── Per-Second iperf3 Interval Log Formatter ─────────────────────────────────────────────

    internal fun buildIperf3IntervalLogText(result: SanitizedIperf3Presentation): String {
        val targetSummary = result.targetHeaderText
        val modeDesc = if (result.direction == Iperf3Direction.DOWNLOAD) {
            "Reverse mode (-R, Server -> Client)"
        } else {
            "Upload mode (Client -> Server)"
        }
        val streamDesc = buildString {
            append(if (result.parallelStreams > 1) "-P ${result.parallelStreams} parallel streams" else "1 stream")
            if (result.omitSec > 0) {
                append(" · -O ${result.omitSec} (warm-up omitted)")
            }
        }

        val effectiveIntervals = if (result.intervals.isNotEmpty()) {
            result.intervals
        } else {
            val safeDur = result.durationSec.coerceAtLeast(0.01)
            listOf(
                Iperf3IntervalSample(
                    index = 1,
                    startSec = 0.0,
                    endSec = safeDur,
                    bytesTransferred = result.bytesTransferred,
                    bitrateMbps = result.effectiveMbps,
                    streams = result.parallelStreams,
                ),
            )
        }

        val maxScaleMbps = maxOf(
            effectiveIntervals.maxOfOrNull { it.bitrateMbps } ?: 0.0,
            result.maxIntervalMbps,
            result.effectiveMbps,
            1.0,
        )
        val rowIdTag = if (result.parallelStreams > 1) "[SUM]" else "[  1]"
        val divider = "- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -"

        val sb = StringBuilder()
        sb.appendLine("Connecting to $targetSummary, TCP · $modeDesc · $streamDesc")
        sb.appendLine("[ ID] Interval           Transfer      Bitrate           Trend")
        sb.appendLine(divider)

        for (sample in effectiveIntervals) {
            val startStr = String.format(Locale.US, "%5.2f", sample.startSec.coerceAtLeast(0.0))
            val endStr = String.format(
                Locale.US,
                "%-5.2f",
                sample.endSec.coerceAtLeast(sample.startSec.coerceAtLeast(0.0)),
            )
            val intervalCol = "$startStr-$endStr sec".padEnd(18, ' ')
            val transferCol = formatIperfTransferField(sample.bytesTransferred).padStart(12, ' ')
            val bitrateCol = formatIperfBitrateField(sample.bitrateMbps).padStart(16, ' ')
            val trendBar = buildThroughputTrendBar(sample.bitrateMbps, maxScaleMbps)
            val omitSuffix = if (sample.omitted) "  (omitted)" else ""
            sb.appendLine("$rowIdTag $intervalCol $transferCol  $bitrateCol   $trendBar$omitSuffix")
        }

        sb.appendLine(divider)

        val totalEndSec = result.durationSec.coerceAtLeast(
            effectiveIntervals.lastOrNull()?.endSec ?: 0.01,
        )
        val sumIntervalCol = String.format(Locale.US, "%5.2f-%-5.2f sec", 0.0, totalEndSec).padEnd(18, ' ')
        val retrText = result.retransmits?.takeIf { it >= 0 }?.let { "$it Retr" } ?: ""

        val summaryTuple = if (result.direction == Iperf3Direction.DOWNLOAD) {
            val srvMbps = result.serverMbps?.takeIf { it > 0.0 } ?: result.effectiveMbps
            val srvDurationSec = result.serverDurationSec?.takeIf { it > 0.0 } ?: totalEndSec
            val srvBytes = result.serverBytesTransferred?.takeIf { it >= 0L }
                ?: if (result.serverMbps != null && result.serverMbps > 0.0) {
                    ((result.serverMbps * 1_000_000.0 * srvDurationSec) / 8.0).toLong().coerceAtLeast(result.bytesTransferred)
                } else {
                    result.bytesTransferred
                }
            IperfSummaryTuple(
                senderBytes = srvBytes,
                senderMbps = srvMbps,
                senderRole = "sender (server)",
                receiverBytes = result.bytesTransferred,
                receiverMbps = result.effectiveMbps,
                receiverRole = "receiver (client)",
            )
        } else {
            val srvMbps = result.serverMbps?.takeIf { it > 0.0 } ?: result.effectiveMbps
            val srvDurationSec = result.serverDurationSec?.takeIf { it > 0.0 } ?: totalEndSec
            val srvBytes = result.serverBytesTransferred?.takeIf { it >= 0L }
                ?: if (result.serverMbps != null && result.serverMbps > 0.0) {
                    ((result.serverMbps * 1_000_000.0 * srvDurationSec) / 8.0).toLong().coerceAtLeast(0L)
                } else {
                    result.bytesTransferred
                }
            IperfSummaryTuple(
                senderBytes = result.bytesTransferred,
                senderMbps = result.clientMbps.takeIf { it > 0.0 } ?: result.effectiveMbps,
                senderRole = "sender (client)",
                receiverBytes = srvBytes,
                receiverMbps = srvMbps,
                receiverRole = "receiver (server)",
            )
        }

        val senderTransferCol = formatIperfTransferField(summaryTuple.senderBytes).padStart(12, ' ')
        val senderBitrateCol = formatIperfBitrateField(summaryTuple.senderMbps).padStart(16, ' ')
        val senderRetrCol = if (retrText.isNotEmpty()) "${retrText.padEnd(10, ' ')} " else "           "
        sb.appendLine("[SUM] $sumIntervalCol $senderTransferCol  $senderBitrateCol   $senderRetrCol${summaryTuple.senderRole}")

        val recvTransferCol = formatIperfTransferField(summaryTuple.receiverBytes).padStart(12, ' ')
        val recvBitrateCol = formatIperfBitrateField(summaryTuple.receiverMbps).padStart(16, ' ')
        sb.append("[SUM] $sumIntervalCol $recvTransferCol  $recvBitrateCol              ${summaryTuple.receiverRole}")

        return sb.toString()
    }

    private data class IperfSummaryTuple(
        val senderBytes: Long,
        val senderMbps: Double,
        val senderRole: String,
        val receiverBytes: Long,
        val receiverMbps: Double,
        val receiverRole: String,
    )

    internal fun formatIperfTransferField(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val d = safe.toDouble()
        val gib = 1024.0 * 1024.0 * 1024.0
        val mib = 1024.0 * 1024.0
        val kib = 1024.0
        return when {
            d >= gib -> String.format(Locale.US, "%.2f GBytes", d / gib)
            d >= 100.0 * mib -> String.format(Locale.US, "%.0f MBytes", d / mib)
            d >= 10.0 * mib -> String.format(Locale.US, "%.1f MBytes", d / mib)
            d >= mib -> String.format(Locale.US, "%.2f MBytes", d / mib)
            d >= kib -> String.format(Locale.US, "%.0f KBytes", d / kib)
            else -> "$safe Bytes"
        }
    }

    internal fun formatIperfBitrateField(mbps: Double): String {
        val safe = mbps.coerceAtLeast(0.0)
        return when {
            safe >= 1000.0 -> String.format(Locale.US, "%.2f Gbits/sec", safe / 1000.0)
            safe >= 100.0 -> String.format(Locale.US, "%.0f Mbits/sec", safe)
            safe >= 10.0 -> String.format(Locale.US, "%.1f Mbits/sec", safe)
            safe >= 1.0 -> String.format(Locale.US, "%.2f Mbits/sec", safe)
            safe > 0.0 -> String.format(Locale.US, "%.0f Kbits/sec", safe * 1000.0)
            else -> "0.00 Mbits/sec"
        }
    }

    internal fun buildThroughputTrendBar(
        mbps: Double,
        maxReferenceMbps: Double,
        barWidth: Int = 10,
    ): String {
        if (barWidth <= 0) return ""
        val safeMax = maxReferenceMbps.coerceAtLeast(0.001)
        val ratio = (mbps.coerceAtLeast(0.0) / safeMax).coerceIn(0.0, 1.0)
        val filled = when {
            mbps <= 0.0 -> 0
            else -> (ratio * barWidth).roundToInt().coerceIn(1, barWidth)
        }
        return "█".repeat(filled) + "░".repeat(barWidth - filled)
    }

    // ── Per-Packet RTT Waveform & Sequence Formatter ─────────────────────────────────────────

    private fun buildPerPacketRttLogText(
        icmp: SanitizedIcmpPingPresentation?,
        tcp: SanitizedTcpPingPresentation?,
    ): String? {
        val sections = mutableListOf<String>()

        if (icmp != null && icmp.samples.isNotEmpty()) {
            val pairs = icmp.samples.map { it.success to it.rttMs }
            val spark = buildLatencySparkline(pairs)
            val ttlTag = extractTtlSummary(icmp.samples)?.let { " · $it" }.orEmpty()
            val header = "[ICMP Ping 逐包序列] ${icmp.sent} 发 ${icmp.received} 收 (丢包 ${formatPercent(icmp.lossPercent)})$ttlTag · 波动图: $spark"
            sections += buildPacketGridSection(
                header = header,
                samples = icmp.samples.map { Triple(it.seq, it.success, it.rttMs) },
            )
        }

        if (tcp != null && tcp.samples.isNotEmpty()) {
            val pairs = tcp.samples.map { it.success to it.rttMs }
            val spark = buildLatencySparkline(pairs)
            val portTag = tcp.portSummaryLabel
            val header = "[TCPing 逐包序列] $portTag · ${tcp.sent} 发 ${tcp.received} 收 (丢包 ${formatPercent(tcp.lossPercent)}) · 波动图: $spark"
            sections += buildPacketGridSection(
                header = header,
                samples = tcp.samples.map { Triple(it.seq, it.success, it.rttMs) },
            )
        }

        return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun buildPacketGridSection(
        header: String,
        samples: List<Triple<Int, Boolean, Double?>>,
    ): String {
        val sb = StringBuilder()
        sb.append(header)
        val columnsPerRow = 5
        samples.chunked(columnsPerRow).forEach { rowItems ->
            sb.append("\n  ")
            val cells = rowItems.map { (seq, ok, rtt) ->
                val seqStr = String.format(Locale.US, "#%02d", seq)
                val valStr = if (ok && rtt != null) {
                    String.format(Locale.US, "%6.1fms", rtt)
                } else if (ok) {
                    "    OK  "
                } else {
                    " TIMEOUT"
                }
                "$seqStr $valStr"
            }
            sb.append(cells.joinToString("   "))
        }
        return sb.toString()
    }

    internal fun buildLatencySparkline(samples: List<Pair<Boolean, Double?>>): String {
        if (samples.isEmpty()) return ""
        val validRtts = samples.mapNotNull { (ok, rtt) -> rtt?.takeIf { ok && it >= 0.0 } }
        if (validRtts.isEmpty()) {
            return "✕".repeat(samples.size.coerceAtMost(32))
        }
        val minRtt = validRtts.min()
        val maxRtt = validRtts.max()
        val avgRtt = validRtts.average()
        val effectiveFloor = (minRtt - maxOf(avgRtt * 0.15, 3.0)).coerceAtLeast(0.0)
        val effectiveSpan = maxOf(maxRtt - effectiveFloor, avgRtt * 0.40, 10.0)

        val sb = StringBuilder(samples.size)
        for ((ok, rtt) in samples) {
            if (!ok || rtt == null) {
                sb.append('✕')
            } else {
                val norm = ((rtt - effectiveFloor) / effectiveSpan).coerceIn(0.0, 1.0)
                val idx = (norm * (SPARK_BARS.size - 1)).roundToInt().coerceIn(0, SPARK_BARS.lastIndex)
                sb.append(SPARK_BARS[idx])
            }
        }
        return sb.toString()
    }

    internal fun computePercentile(sortedValues: List<Double>, percentile: Double): Double? {
        if (sortedValues.isEmpty()) return null
        if (sortedValues.size == 1) return sortedValues[0]
        val clamped = percentile.coerceIn(0.0, 100.0)
        val position = (clamped / 100.0) * (sortedValues.size - 1).toDouble()
        val lower = position.toInt().coerceIn(0, sortedValues.lastIndex)
        val upper = (lower + 1).coerceAtMost(sortedValues.lastIndex)
        val fraction = position - lower.toDouble()
        return sortedValues[lower] + (sortedValues[upper] - sortedValues[lower]) * fraction
    }

    private fun extractTtlSummary(samples: List<SanitizedPingSample>): String? {
        val ttls = samples.mapNotNull { s -> s.ttl?.takeIf { s.success && it > 0 } }
        if (ttls.isEmpty()) return null
        val minTtl = ttls.min()
        val maxTtl = ttls.max()
        return if (minTtl == maxTtl) "TTL $minTtl" else "TTL $minTtl~$maxTtl"
    }

    private fun buildTcpVsIcmpComparison(
        icmp: SanitizedIcmpPingPresentation?,
        tcp: SanitizedTcpPingPresentation,
    ): String? {
        if (icmp == null) return null
        if (!icmp.hasValidLatency && tcp.hasValidLatency) {
            return "节点禁 ICMP Ping，TCP 端口握手连通正常"
        }
        val pingAvg = icmp.avgMs ?: return null
        val tcpAvg = tcp.avgMs ?: return null
        val delta = tcpAvg - pingAvg
        val sign = if (delta >= 0.0) "+" else ""
        val deltaStr = String.format(Locale.US, "%s%.1f ms", sign, delta)
        return when {
            abs(delta) <= 5.0 -> "与 Ping 延迟一致 (Δ $deltaStr)"
            delta in 5.0..25.0 -> "较 Ping $deltaStr (正常握手开销)"
            delta > 25.0 -> "较 Ping 偏高 $deltaStr (存在握手排队或策略限速)"
            else -> "较 Ping $deltaStr (路由或协议优先级差异)"
        }
    }

    private fun buildTcpVsIcmpShortTag(
        icmp: SanitizedIcmpPingPresentation?,
        tcp: SanitizedTcpPingPresentation,
    ): String? {
        if (icmp == null) return null
        if (!icmp.hasValidLatency && tcp.hasValidLatency) return "禁 Ping 但 TCP 正常"
        val pingAvg = icmp.avgMs ?: return null
        val tcpAvg = tcp.avgMs ?: return null
        val delta = tcpAvg - pingAvg
        val sign = if (delta >= 0.0) "+" else ""
        return String.format(Locale.US, "较 Ping %s%.1f ms", sign, delta)
    }

    // ── Automated Objective Health Verdict ───────────────────────────────────────────────────

    private fun buildComprehensiveVerdict(sanitized: SanitizedNetBenchReport): List<String> {
        val bullets = mutableListOf<String>()

        // 1. Latency & Packet Loss Verdict
        val icmp = sanitized.icmpPing
        val tcp = sanitized.tcpPing
        if (icmp != null || tcp != null) {
            val primaryAvg = icmp?.avgMs?.takeIf { icmp.hasValidLatency }
                ?: tcp?.avgMs?.takeIf { tcp.hasValidLatency }
            val primaryJitter = icmp?.jitterMdevMs?.takeIf { icmp.hasValidLatency }
                ?: tcp?.jitterMdevMs?.takeIf { tcp.hasValidLatency }
            val effectiveLoss = when {
                tcp != null && tcp.hasValidLatency -> tcp.lossPercent
                icmp != null && icmp.hasValidLatency -> icmp.lossPercent
                tcp != null -> tcp.lossPercent
                else -> icmp?.lossPercent ?: 0.0
            }

            if (primaryAvg != null) {
                val tierDesc = when {
                    primaryAvg < 50.0 -> "亚太近距/专线级低延迟水准"
                    primaryAvg < 120.0 -> "优质直连延迟区间，SSH 交互与网页加载响应流畅"
                    primaryAvg < 200.0 -> "跨洋常规直连延迟水准，适合建站与大流量传输"
                    else -> "长距离跨洲或绕路延迟水准，实时交互略有感知延迟"
                }
                val lossDesc = when {
                    effectiveLoss <= 0.0 -> "全程 **0.0% 零丢包**"
                    effectiveLoss <= 5.0 -> "存在轻微丢包（`${formatPercent(effectiveLoss)}`）"
                    else -> "丢包率偏高（`${formatPercent(effectiveLoss)}`），跨境链路可能存在晚高峰拥塞"
                }
                val jitterVal = primaryJitter ?: 0.0
                val jitterDesc = when {
                    jitterVal < 5.0 -> "抖动极低（`${formatMs(jitterVal)}`），时延曲线平稳"
                    jitterVal < 15.0 -> "抖动处于正常范围（`${formatMs(jitterVal)}`）"
                    else -> "抖动偏大（`${formatMs(jitterVal)}`），链路存在排队跳 Ping 现象"
                }
                val icmpBlockedNote = if (icmp != null && !icmp.hasValidLatency && tcp?.hasValidLatency == true) {
                    "（注：目标已开启禁 ICMP Ping，TCP 业务端口连通正常）"
                } else {
                    ""
                }
                bullets += "**延迟与链路稳定性**：$lossDesc，实测均值 **${formatMs(primaryAvg)}**（$tierDesc），$jitterDesc$icmpBlockedNote。"
            } else {
                bullets += "**延迟与链路稳定性**：ICMP 与 TCP 探测均未收到有效响应，请排查目标防火墙、安全组端口放行或跨境阻断状态。"
            }
        }

        // 2. iperf3 Throughput & Ramp-Up Verdict
        val dl = sanitized.iperfDownload
        val ul = sanitized.iperfUpload
        if (dl != null || ul != null) {
            if (dl?.success == true || ul?.success == true) {
                val parts = mutableListOf<String>()
                if (dl?.success == true) {
                    val measured = dl.measuredIntervals
                    val firstSeg = measured.firstOrNull()?.bitrateMbps
                    val omitPrefix = if (dl.omitSec > 0) "已剔除前 ${dl.omitSec}s 慢启动，" else ""
                    val rampNote = omitPrefix + if (firstSeg != null && measured.size >= 2) {
                        val ratio = firstSeg / dl.maxIntervalMbps.coerceAtLeast(1.0)
                        if (ratio >= 0.75) "首秒即达 `${formatMbps(firstSeg)}` 起速迅猛" else "首秒 `${formatMbps(firstSeg)}` 随后爬升至峰值 `${formatMbps(dl.maxIntervalMbps)}`"
                    } else {
                        "峰值 `${formatMbps(dl.maxIntervalMbps)}`"
                    }
                    val stabNote = dl.stabilityPercent?.let { stab ->
                        when {
                            stab >= 85.0 -> "全程吞吐稳健（稳定度 `${formatPercent(stab)}`）"
                            stab >= 65.0 -> "中段存在一定速率波动（稳定度 `${formatPercent(stab)}`）"
                            else -> "分段速率起伏较大（稳定度 `${formatPercent(stab)}`），可能受限于窗口收缩或 QoS"
                        }
                    }
                    val retrNote = dl.retransmits?.takeIf { it >= 0 }?.let { retr ->
                        if (retr == 0L) "0 重传" else "TCP 重传 `$retr` 次"
                    }
                    parts += listOfNotNull(
                        "下行实测均速 **${formatMbps(dl.effectiveMbps)}**（$rampNote）",
                        stabNote,
                        retrNote,
                    ).joinToString("，")
                }
                if (ul?.success == true) {
                    val retrNote = ul.retransmits?.takeIf { it >= 0 }?.let { retr ->
                        if (retr == 0L) "0 重传" else "TCP 重传 `$retr` 次"
                    }
                    val ulSummary = listOfNotNull(
                        "上行实测均速 **${formatMbps(ul.effectiveMbps)}**（峰值 `${formatMbps(ul.maxIntervalMbps)}`）",
                        retrNote,
                    ).joinToString("，")
                    parts += ulSummary
                }
                bullets += "**带宽吞吐与稳态表现**：${parts.joinToString("；")}。"
            } else {
                val failMsg = truncateByDisplayWidth(
                    sanitizeInline((dl?.publicErrorMessage ?: ul?.publicErrorMessage).orEmpty()),
                    MAX_NOTE_CELL_DISPLAY_WIDTH,
                )
                bullets += "**带宽吞吐表现**：iperf3 测速未能完成（${failMsg.ifBlank { "服务端未就绪或连接受阻" }}）。"
            }
        }

        // 3. IP Origin & Scenario Verdict
        sanitized.ipIntelligence?.let { intel ->
            if (intel.hasCoreInfo || intel.originType != IpOriginType.UNKNOWN) {
                val loc = intel.locationSummary.ifBlank { EMPTY_CELL_PLACEHOLDER }
                val scenarioDesc = when {
                    intel.originType == IpOriginType.NATIVE && intel.usageType == IpUsageType.ISP_RESIDENTIAL_OR_BUSINESS ->
                        "兼具 **原生 IP** 与 **ISP 家宽/商宽** 双重属性，本土流媒体与 AI 平台风控友好度极佳"
                    intel.originType == IpOriginType.NATIVE ->
                        "RIR 注册国与落地一致（**原生 IP** · ${intel.usageType.label}），兼顾地区定位准确性与服务器带宽性能"
                    intel.originType == IpOriginType.BROADCAST ->
                        "属于 **广播 IP**（${intel.usageType.label}），适合常规建站、代理与高速中转；若需解锁当地严苛流媒体建议配合 DNS 解锁"
                    intel.originType == IpOriginType.ANYCAST ->
                        "采用 **Anycast 任播网络**，具备就近入口调度与抗抖动优势"
                    else ->
                        "网络属性为 **${intel.usageType.label}**，建议结合实际解锁脚本进一步核验"
                }
                bullets += "**IP 质量与场景建议**：节点归属 **$loc**，$scenarioDesc。"
            }
        }

        // 4. Routing / Backbone Verdict (when Traceroute / Reverse Trace present)
        listOfNotNull(sanitized.forwardTraceroute, sanitized.reverseTraceroute).forEach { trace ->
            val dirLabel = if (trace.direction == TracerouteDirection.REVERSE) "回程路由" else "去程路由"
            val tagBadge = if (trace.dominantBackboneTags.isNotEmpty()) {
                "**${trace.routeSignature}**"
            } else {
                trace.routeSignature.ifBlank { "常规直连" }
            }
            val lossSummary = when {
                trace.hasRealPacketLoss -> "存在真实链路丢包（峰值 `${formatPercent(trace.peakHopLossPercent)}` @ Hop ${trace.peakLossHopNumber ?: "-"}）"
                trace.hasIntermediateRateLimitOnly -> "仅中间跳控制面 ICMP 限速（终点零真实丢包）"
                else -> "全程零丢包"
            }
            val rttPart = trace.terminalAvgMs?.let { "，终点均延 `${formatMs(it)}`" }.orEmpty()
            bullets += "**$dirLabel（${trace.timeWindowLabel.ifBlank { "实时" }}）**：经由 $tagBadge（共 ${trace.totalHops} 跳$rttPart），$lossSummary。"
        }

        return bullets
    }

    private fun buildOneLineVerdict(sanitized: SanitizedNetBenchReport): String {
        val segments = mutableListOf<String>()
        val icmp = sanitized.icmpPing
        val tcp = sanitized.tcpPing
        val avg = icmp?.avgMs?.takeIf { icmp.hasValidLatency } ?: tcp?.avgMs?.takeIf { tcp.hasValidLatency }
        val loss = when {
            tcp?.hasValidLatency == true -> tcp.lossPercent
            icmp?.hasValidLatency == true -> icmp.lossPercent
            else -> null
        }
        if (avg != null && loss != null) {
            val lossText = if (loss <= 0.0) "零丢包" else "丢包 ${formatPercent(loss)}"
            segments += "$lossText · 均延 ${formatMs(avg)}"
        }
        sanitized.iperfDownload?.takeIf { it.success }?.let { dl ->
            segments += "下行 ${formatMbps(dl.effectiveMbps)}"
        }
        sanitized.iperfUpload?.takeIf { it.success }?.let { ul ->
            segments += "上行 ${formatMbps(ul.effectiveMbps)}"
        }
        sanitized.ipIntelligence?.takeIf { it.originType != IpOriginType.UNKNOWN }?.let { intel ->
            segments += intel.originType.label
        }
        sanitized.forwardTraceroute?.takeIf { it.dominantBackboneTags.isNotEmpty() }?.let { trace ->
            segments += "去程 ${trace.routeSignature}"
        }
        sanitized.reverseTraceroute?.takeIf { it.dominantBackboneTags.isNotEmpty() }?.let { trace ->
            segments += "回程 ${trace.routeSignature}"
        }
        return segments.joinToString(" · ")
    }

    private fun formatTracerouteCompact(trace: SanitizedTraceroutePresentation): String {
        if (!trace.publicErrorMessage.isNullOrBlank() && trace.hops.isEmpty()) {
            return "失败（${sanitizeInline(trace.publicErrorMessage)}）"
        }
        val parts = mutableListOf<String>()
        if (trace.routeSignature.isNotBlank()) {
            parts += "**${sanitizeInline(trace.routeSignature)}**"
        }
        parts += "${trace.totalHops}跳"
        trace.terminalAvgMs?.let { parts += "终点 `${formatMs(it)}`" }
        val lossDesc = when {
            trace.hasRealPacketLoss -> "真实丢包 `${formatPercent(trace.peakHopLossPercent)}`"
            trace.hasIntermediateRateLimitOnly -> "中间跳限速(无真实丢包)"
            else -> "零丢包"
        }
        parts += lossDesc
        if (trace.isPeakHourWindow) {
            parts += "晚高峰"
        }
        return parts.joinToString(" · ")
    }

    private fun buildTracerouteSummaryRow(trace: SanitizedTraceroutePresentation): List<String> {
        val dirCell = buildString {
            append(trace.direction.label)
            if (trace.timeWindowLabel.isNotBlank()) {
                append(" · ").append(trace.timeWindowLabel)
            }
        }
        val tagCell = if (trace.dominantBackboneTags.isNotEmpty()) {
            trace.dominantBackboneTags.joinToString(" · ") { "**${it.displayLabel}**" }
        } else {
            trace.routeSignature.ifBlank { EMPTY_CELL_PLACEHOLDER }
        }
        val hopStatus = buildString {
            append("${trace.totalHops} 跳")
            append(if (trace.reachedTarget) " · 已达终点" else " · 未达终点")
        }
        val lossAndLatency = if (!trace.publicErrorMessage.isNullOrBlank() && trace.hops.isEmpty()) {
            sanitizeInline(trace.publicErrorMessage)
        } else {
            val rttStr = trace.terminalAvgMs?.let { "终点 ${formatMs(it)}" } ?: "无终点 RTT"
            val lossStr = when {
                trace.hasRealPacketLoss -> "真实丢包 ${formatPercent(trace.peakHopLossPercent)}"
                trace.hasIntermediateRateLimitOnly -> "中间跳限速（实际零丢包）"
                else -> "零丢包"
            }
            "$rttStr · $lossStr"
        }
        return listOf(dirCell, tagCell, hopStatus, lossAndLatency)
    }

    private fun buildTracerouteHopRow(hop: SanitizedTracerouteHopPresentation): List<String> {
        val numCell = hop.hopNumber.toString()
        val ipCell = if (hop.displayIp == "*") "*" else "`${sanitizeCodeInline(hop.displayIp)}`"
        val tagAndAsn = buildList {
            if (hop.backboneTags.isNotEmpty()) {
                add(hop.backboneTags.joinToString("/") { "**${it.displayLabel}**" })
            }
            hop.asn?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ").ifBlank { EMPTY_CELL_PLACEHOLDER }
        val locCell = hop.locationSummary?.takeIf { it.isNotBlank() } ?: EMPTY_CELL_PLACEHOLDER
        val lossCell = when {
            hop.received == 0 -> "100% (超时)"
            hop.lossPercent > 0.0 && hop.lossClassification == HopLossClassification.INTERMEDIATE_RATE_LIMIT ->
                "${formatPercent(hop.lossPercent)} (限速)"
            hop.lossPercent > 0.0 -> formatPercent(hop.lossPercent)
            else -> "0.0%"
        }
        val latCell = if (hop.avgMs != null) {
            val maxPart = hop.maxMs?.let { " / ${formatMs(it)}" }.orEmpty()
            "${formatMs(hop.avgMs)}$maxPart"
        } else {
            EMPTY_CELL_PLACEHOLDER
        }
        return listOf(numCell, ipCell, tagAndAsn, locCell, lossCell, latCell)
    }

    private fun buildSignatureFooter(timestamp: String): String =
        "*📊 报告由 **Anode 移动端网络实测** 生成 · 测试时间：`$timestamp` · 已自动脱敏保护本机隐私 IP*"

    // ── Shared Formatting Helpers ────────────────────────────────────────────────────────────

    private fun buildTargetTableRow(
        target: SanitizedTargetPresentation,
        maskTargetIp: Boolean,
    ): List<String>? {
        if (target.hasResolvedTarget && target.formattedEndpoint != null) {
            if (target.isDomain && !target.displayHost.isNullOrBlank()) {
                val safeHost = sanitizeCodeInline(
                    truncateByDisplayWidth(target.displayHost, MAX_HOST_DISPLAY_WIDTH),
                )
                val noteParts = mutableListOf<String>()
                noteParts += "IP `${target.formattedEndpoint}`"
                noteParts += target.ipFamily.label
                if (target.dnsDurationMs > 0.0) {
                    noteParts += "DNS ${formatMs(target.dnsDurationMs)}"
                }
                if (maskTargetIp) {
                    noteParts += "已隐藏完整 IP/端口"
                }
                return listOf("目标节点", "`$safeHost`", noteParts.joinToString(" · "))
            } else {
                val note = if (maskTargetIp) {
                    "${target.ipFamily.label} · 已隐藏完整 IP/端口"
                } else {
                    "${target.ipFamily.label} · ${target.portSummaryLabel}"
                }
                return listOf("目标节点", "`${target.formattedEndpoint}`", note)
            }
        }

        val endpoint = target.formattedEndpoint ?: return null
        return if (!target.isDomain) {
            val familyLabel = target.ipFamily.takeIf { it != IpFamily.UNKNOWN }?.label ?: "目标主机"
            val note = if (maskTargetIp) "$familyLabel · 已隐藏完整 IP/端口" else "$familyLabel · ${target.portSummaryLabel}"
            listOf("目标节点", "`$endpoint`", note)
        } else {
            val safeShown = sanitizeCodeInline(truncateByDisplayWidth(endpoint, MAX_HOST_DISPLAY_WIDTH))
            val note = if (maskTargetIp) "已隐藏测速端口" else target.portSummaryLabel
            listOf("目标节点", "`$safeShown`", note)
        }
    }

    private fun buildTargetDisplay(
        target: SanitizedTargetPresentation,
        maskTargetIp: Boolean,
    ): String {
        if (target.hasResolvedTarget && target.formattedEndpoint != null) {
            val dnsSuffix = if (target.isDomain && target.dnsDurationMs > 0.0) {
                " · DNS `${formatMs(target.dnsDurationMs)}`"
            } else {
                ""
            }
            return if (target.isDomain && !target.displayHost.isNullOrBlank()) {
                val safeHost = sanitizeCodeInline(
                    truncateByDisplayWidth(target.displayHost, MAX_HOST_DISPLAY_WIDTH),
                )
                "`$safeHost` (`${target.formattedEndpoint}` · ${target.ipFamily.label}$dnsSuffix)"
            } else {
                "`${target.formattedEndpoint}` (${target.ipFamily.label})"
            }
        }

        val endpoint = target.formattedEndpoint ?: return ""
        val safeShown = if (target.isDomain) {
            sanitizeCodeInline(truncateByDisplayWidth(endpoint, MAX_HOST_DISPLAY_WIDTH))
        } else {
            endpoint
        }
        return "`$safeShown`"
    }

    private fun formatIcmpPingCompact(ping: SanitizedIcmpPingPresentation): String {
        if (!ping.hasValidLatency && ping.sent == 0) {
            val reason = truncateByDisplayWidth(
                sanitizeInline(ping.publicErrorMessage ?: NetBenchErrorCategory.REQUEST_FAILED.publicLabel),
                MAX_ERROR_DISPLAY_WIDTH,
            )
            return "不可用 ($reason)"
        }
        val sortedRtts = ping.samples.mapNotNull { s -> s.rttMs?.takeIf { s.success } }.sorted()
        val p95 = computePercentile(sortedRtts, 95.0)
        val spark = if (ping.samples.isNotEmpty()) {
            buildLatencySparkline(ping.samples.map { it.success to it.rttMs })
        } else {
            ""
        }
        val ttl = extractTtlSummary(ping.samples)
        return formatLatencyCompact(
            hasValidLatency = ping.hasValidLatency,
            avgMs = ping.avgMs,
            minMs = ping.minMs,
            maxMs = ping.maxMs,
            jitterMdevMs = ping.jitterMdevMs,
            p95Ms = p95,
            lossPercent = ping.lossPercent,
            ttlTag = ttl,
            sparkline = spark,
            failureLabel = "全部超时/禁 Ping",
        )
    }

    private fun formatTcpPingCompact(tcp: SanitizedTcpPingPresentation): String {
        val sortedRtts = tcp.samples.mapNotNull { s -> s.rttMs?.takeIf { s.success } }.sorted()
        val p95 = computePercentile(sortedRtts, 95.0)
        val spark = if (tcp.samples.isNotEmpty()) {
            buildLatencySparkline(tcp.samples.map { it.success to it.rttMs })
        } else {
            ""
        }
        return formatLatencyCompact(
            hasValidLatency = tcp.hasValidLatency,
            avgMs = tcp.avgMs,
            minMs = tcp.minMs,
            maxMs = tcp.maxMs,
            jitterMdevMs = tcp.jitterMdevMs,
            p95Ms = p95,
            lossPercent = tcp.lossPercent,
            ttlTag = null,
            sparkline = spark,
            failureLabel = "全部超时/不可达",
        )
    }

    private fun formatLatencyCompact(
        hasValidLatency: Boolean,
        avgMs: Double?,
        minMs: Double?,
        maxMs: Double?,
        jitterMdevMs: Double?,
        p95Ms: Double?,
        lossPercent: Double,
        ttlTag: String?,
        sparkline: String,
        failureLabel: String,
    ): String {
        if (!hasValidLatency || avgMs == null || minMs == null || maxMs == null) {
            return "$failureLabel ｜ 丢包 **${formatPercent(lossPercent)}**"
        }
        return buildString {
            append("均 **${formatMs(avgMs)}**")
            append(" ｜ 最小 `${formatMs(minMs)}`")
            append(" ｜ 最大 `${formatMs(maxMs)}`")
            if (p95Ms != null) {
                append(" ｜ P95 `${formatMs(p95Ms)}`")
            }
            append(" ｜ 抖动 `${formatMs(jitterMdevMs ?: 0.0)}`")
            append(" ｜ 丢包 `${formatPercent(lossPercent)}`")
            if (!ttlTag.isNullOrBlank()) {
                append(" ｜ `$ttlTag`")
            }
            if (sparkline.isNotEmpty()) {
                append(" ｜ `$sparkline`")
            }
        }
    }

    private fun formatHttpCompact(http: SanitizedHttpProbePresentation): String {
        if (!http.success || http.totalMs == null) {
            val reason = truncateByDisplayWidth(
                sanitizeInline(http.publicErrorMessage ?: NetBenchErrorCategory.REQUEST_FAILED.publicLabel),
                MAX_ERROR_DISPLAY_WIDTH,
            )
            return "失败 ($reason)"
        }
        val parts = mutableListOf<String>()
        val protoSuffix = http.protocol?.let { " (`${sanitizeCodeInline(it)}`)" }.orEmpty()
        http.statusCode?.let { parts += "HTTP $it$protoSuffix" }
        parts += "总计 **${formatMs(http.totalMs)}**"
        http.ttfbMs?.let { parts += "TTFB `${formatMs(it)}`" }
        http.tlsHandshakeMs?.takeIf { it > 0.0 }?.let { parts += "TLS `${formatMs(it)}`" }
        return parts.joinToString(" ｜ ")
    }

    private fun formatIpIntelCompact(intel: SanitizedIpIntelPresentation): String {
        val parts = mutableListOf<String>()
        parts += "${intel.flagEmoji} ${intel.locationSummary}".trim()
        val asnOrg = buildString {
            if (!intel.asn.isNullOrBlank()) append("`${sanitizeCodeInline(intel.asn)}`")
            val org = intel.compactOrgName ?: intel.compactIspName
            if (org != null) {
                if (isNotEmpty()) append(" ")
                append("($org)")
            }
        }
        if (asnOrg.isNotEmpty()) parts += asnOrg
        if (intel.usageType != IpUsageType.UNKNOWN) {
            parts += intel.usageType.label
        }
        val evidence = sanitizeInline(intel.originEvidence).ifBlank { intel.originType.label }
        parts += "**$evidence**"
        return parts.joinToString(" · ")
    }

    private fun formatFullOriginEvidence(intel: SanitizedIpIntelPresentation): String {
        var evidence = sanitizeInline(intel.originEvidence)
        val redundantPrefix = "${intel.originType.label} "
        if (evidence.startsWith(redundantPrefix)) {
            val remainder = evidence.removePrefix(redundantPrefix).trim()
            if (remainder.startsWith("(") && remainder.endsWith(")") && remainder.length > 2) {
                evidence = remainder.substring(1, remainder.length - 1).trim()
            } else if (remainder.isNotEmpty()) {
                evidence = remainder
            }
        }
        val extraParts = mutableListOf<String>()
        if (evidence.isNotEmpty()) extraParts += evidence
        intel.rirAuthority?.takeIf { !evidence.contains(it, ignoreCase = true) }?.let {
            extraParts += "RIR: ${sanitizeInline(it)}"
        }
        intel.whoisSource?.let {
            extraParts += "WHOIS: ${sanitizeInline(it)}"
        }
        val joined = extraParts.joinToString(" · ")
        return truncateByDisplayWidth(joined, MAX_NOTE_CELL_DISPLAY_WIDTH).ifBlank { EMPTY_CELL_PLACEHOLDER }
    }

    private fun buildClientEnvBadge(sanitized: SanitizedNetBenchReport): String {
        val client = sanitized.clientEnv ?: return "本机网络"
        val carrierText = client.carrierDisplayName.ifBlank { "本机网络" }
        val regionPart = client.regionName?.takeIf {
            it.isNotEmpty() && !carrierText.contains(it, ignoreCase = true)
        }
        val base = listOfNotNull(carrierText, regionPart, client.ipFamily.label).joinToString(" · ")
        return when {
            sanitized.testedViaProxy -> "$base · 经由代理"
            client.routeKind == EgressRouteKind.PROXY_FALLBACK -> "$base · 代理出口(直连受限)"
            else -> base
        }
    }

    // ── Aligned GFM Table Renderer ───────────────────────────────────────────────────────────

    /**
     * Renders a GFM Markdown table with strict column-count normalization, empty-cell placeholders,
     * pipe escaping, and East-Asian CJK visual-width padding so both raw Markdown editors and
     * rendered NodeSeek HTML tables align cleanly.
     */
    internal fun renderAlignedMarkdownTable(
        headers: List<String>,
        rows: List<List<String>>,
        cellPreprocessor: (String) -> String = { it },
    ): String {
        val colCount = headers.size
        require(colCount > 0) { "Table must have at least 1 column" }

        val cleanHeaders = headers.map { normalizeTableCell(cellPreprocessor(it)) }
        val cleanRows = rows.map { row ->
            List(colCount) { col ->
                val raw = row.getOrElse(col) { EMPTY_CELL_PLACEHOLDER }
                normalizeTableCell(cellPreprocessor(raw))
            }
        }

        val colWidths = IntArray(colCount) { col ->
            var maxW = maxOf(4, displayWidth(cleanHeaders[col]))
            for (row in cleanRows) {
                val cellW = displayWidth(row[col])
                if (cellW > maxW) maxW = cellW
            }
            maxW
        }

        val lines = ArrayList<String>(cleanRows.size + 2)
        lines += buildPipeRowLine(cleanHeaders, colWidths)
        lines += buildDividerRowLine(colWidths)
        for (row in cleanRows) {
            lines += buildPipeRowLine(row, colWidths)
        }
        return lines.joinToString("\n")
    }

    private fun buildPipeRowLine(cells: List<String>, colWidths: IntArray): String = buildString {
        append("| ")
        for (i in cells.indices) {
            if (i > 0) append(" | ")
            append(padEndByDisplayWidth(cells[i], colWidths[i]))
        }
        append(" |")
    }

    private fun buildDividerRowLine(colWidths: IntArray): String = buildString {
        append("| ")
        for (i in colWidths.indices) {
            if (i > 0) append(" | ")
            append(':')
            append("-".repeat((colWidths[i] - 1).coerceAtLeast(3)))
        }
        append(" |")
    }

    private fun normalizeTableCell(raw: String): String {
        val sanitized = escapeTableCell(raw)
        return sanitized.ifBlank { EMPTY_CELL_PLACEHOLDER }
    }

    /**
     * Computes East-Asian monospace display width (`ASCII = 1`, `CJK / Fullwidth / Emoji = 2`,
     * zero-width joiners / variation selectors = `0`).
     */
    internal fun displayWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            width += codePointDisplayWidth(cp)
            i += Character.charCount(cp)
        }
        return width
    }

    /**
     * Truncates plain [text] so its [displayWidth] does not exceed [maxWidth], preserving
     * surrogate pairs and regional-indicator flag emoji pairs cleanly.
     */
    internal fun truncateByDisplayWidth(text: String, maxWidth: Int): String {
        val clean = sanitizeInline(text)
        if (maxWidth <= 2 || displayWidth(clean) <= maxWidth) return clean

        val budget = (maxWidth - 1).coerceAtLeast(1)
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < clean.length) {
            val cp = Character.codePointAt(clean, i)
            val charLen = Character.charCount(cp)

            // Keep regional indicator flag pair (e.g. 🇯🇵) atomic
            if (cp in 0x1F1E6..0x1F1FF && i + charLen < clean.length) {
                val nextCp = Character.codePointAt(clean, i + charLen)
                if (nextCp in 0x1F1E6..0x1F1FF) {
                    val pairLen = charLen + Character.charCount(nextCp)
                    if (used + 2 > budget) break
                    sb.append(clean, i, i + pairLen)
                    used += 2
                    i += pairLen
                    continue
                }
            }

            val w = codePointDisplayWidth(cp)
            if (used + w > budget) break
            sb.appendCodePoint(cp)
            used += w
            i += charLen
        }
        return sb.toString().trimEnd() + "…"
    }

    private fun padEndByDisplayWidth(text: String, targetWidth: Int): String {
        val padSpaces = (targetWidth - displayWidth(text)).coerceAtLeast(0)
        return if (padSpaces == 0) text else text + " ".repeat(padSpaces)
    }

    private fun codePointDisplayWidth(cp: Int): Int = when {
        cp == 0x200D || cp in 0xFE00..0xFE0F || Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> 0
        // Each regional indicator symbol contributes 1 so a 2-codepoint flag emoji equals width 2
        cp in 0x1F1E6..0x1F1FF -> 1
        cp in 0x1100..0x115F ||
            cp in 0x2E80..0x303E ||
            cp in 0x3040..0x33BF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF ||
            cp in 0xA960..0xA97C ||
            cp in 0xAC00..0xD7FB ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE10..0xFE6B ||
            cp in 0xFF01..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F300..0x1FAFF ||
            cp in 0x20000..0x2FFFD -> 2
        else -> 1
    }

    private fun formatNumber1(v: Double): String = String.format(Locale.US, "%.1f", v)

    fun formatMs(ms: Double): String = String.format(Locale.US, "%.1f ms", ms)

    fun formatSeconds(sec: Double): String = String.format(Locale.US, "%.1f s", sec.coerceAtLeast(0.0))

    fun formatPercent(pct: Double): String = String.format(Locale.US, "%.1f%%", pct)

    fun formatMbps(mbps: Double): String = if (mbps >= 1000.0) {
        String.format(Locale.US, "%.2f Gbps", mbps / 1000.0)
    } else {
        String.format(Locale.US, "%.1f Mbps", mbps)
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        return when {
            safe >= 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
            safe >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
            safe >= 1024.0 -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            else -> "${bytes.coerceAtLeast(0L)} B"
        }
    }

    private fun sanitizeInline(text: String): String =
        text.replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(MULTI_WHITESPACE_REGEX, " ")
            .trim()

    private fun sanitizeCodeInline(text: String): String =
        sanitizeInline(text).replace("`", "").replace("|", "")

    private fun escapeTableCell(text: String): String {
        val clean = sanitizeInline(text)
        if (clean.isEmpty()) return ""
        val sb = StringBuilder(clean.length)
        var i = 0
        while (i < clean.length) {
            val ch = clean[i]
            if (ch == '\\' && i + 1 < clean.length && clean[i + 1] == '|') {
                sb.append("\\|")
                i += 2
                continue
            }
            if (ch == '|') {
                sb.append("\\|")
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
