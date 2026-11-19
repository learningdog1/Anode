package com.nodeseek.app.domain.netbench

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates privacy-safe NodeSeek Markdown benchmark reports in two styles:
 * - [NetBenchMarkdownStyle.REPLY_CARD]: Compact blockquote card ideal for 测评帖 replies.
 * - [NetBenchMarkdownStyle.FULL_TABLE]: Structured GFM Markdown table for standalone review posts.
 *
 * SECURITY / PRIVACY: The user's own egress IP (`clientNetwork.rawIp` / `maskedIp`) is NEVER
 * written into any generated public Markdown output.
 */
object NetBenchMarkdownFormatter {

    private val DISPLAY_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    fun format(
        report: NetBenchReport,
        options: NetBenchMarkdownOptions = NetBenchMarkdownOptions(),
    ): String {
        val rawMarkdown = when (options.style) {
            NetBenchMarkdownStyle.REPLY_CARD -> formatReplyCard(report, options)
            NetBenchMarkdownStyle.FULL_TABLE -> formatFullTable(report, options)
        }
        return scrubClientIpIfNeeded(rawMarkdown, report)
    }

    private fun formatReplyCard(
        report: NetBenchReport,
        options: NetBenchMarkdownOptions,
    ): String {
        val envBadge = buildClientEnvBadge(report)
        val timestamp = options.timestampLabel?.takeIf { it.isNotBlank() }
            ?: LocalDateTime.now(DEFAULT_ZONE).format(DISPLAY_TIME_FORMATTER)

        val lines = mutableListOf<String>()
        lines += "> 📡 **本机网络实测（$envBadge）** · `$timestamp`"

        val targetLine = buildTargetDisplay(report, options.maskTargetIp)
        if (targetLine.isNotEmpty()) {
            lines += "> - **目标节点**：$targetLine"
        }

        report.tcpPingSummary?.let { tcp ->
            lines += "> - **TCPing (${tcp.sent}次)**：${formatTcpPingCompact(tcp)}"
        }

        report.httpLatencyResult?.let { http ->
            lines += "> - **HTTP 探测**：${formatHttpCompact(http)}"
        }

        val iperfParts = buildList {
            report.iperfDownloadResult?.let { dl ->
                if (dl.success) {
                    add("下载 **${formatMbps(dl.effectiveMbps)}**")
                } else {
                    add("下载失败 (${sanitizeInline(dl.errorMessage ?: "连接异常")})")
                }
            }
            report.iperfUploadResult?.let { ul ->
                if (ul.success) {
                    add("上传 **${formatMbps(ul.effectiveMbps)}**")
                } else {
                    add("上传失败 (${sanitizeInline(ul.errorMessage ?: "连接异常")})")
                }
            }
        }
        if (iperfParts.isNotEmpty()) {
            lines += "> - **iperf3 测速**：${iperfParts.joinToString(" ｜ ")}"
        }

        report.ipIntelligence?.let { intel ->
            lines += "> - **IP 归属与属性**：${formatIpIntelCompact(intel)}"
        }

        return lines.joinToString("\n")
    }

    private fun formatFullTable(
        report: NetBenchReport,
        options: NetBenchMarkdownOptions,
    ): String {
        val envBadge = buildClientEnvBadge(report)
        val timestamp = options.timestampLabel?.takeIf { it.isNotBlank() }
            ?: LocalDateTime.now(DEFAULT_ZONE).format(DISPLAY_TIME_FORMATTER)

        val sb = StringBuilder()
        sb.appendLine("### 📡 移动端网络实测报告")
        sb.appendLine()
        sb.appendLine("| 测试项目 | 实测结果 | 备注 |")
        sb.appendLine("| :--- | :--- | :--- |")
        sb.appendLine("| 本机网络环境 | ${escapeTableCell(envBadge)} | 已自动隐藏本机 IP |")

        val resolved = report.resolvedTarget
        if (resolved != null) {
            val shownIp = if (options.maskTargetIp) {
                NetBenchTargetParser.maskTargetIpLastSegment(resolved.resolvedIp)
            } else {
                resolved.resolvedIp
            }
            val endpoint = resolved.formatAddressWithPort(report.tcpPort, maskedIp = shownIp)
            val nodeCell = if (resolved.isDomain) {
                "`${escapeTableCell(resolved.displayHost)}` (`$endpoint`)"
            } else {
                "`$endpoint`"
            }
            val dnsNote = buildString {
                append("协议: ${resolved.ipFamily.label}")
                if (resolved.isDomain && resolved.dnsDurationMs > 0.0) {
                    append(" · DNS ${formatMs(resolved.dnsDurationMs)}")
                }
            }
            sb.appendLine("| 目标节点 | $nodeCell | ${escapeTableCell(dnsNote)} |")
        } else if (report.targetInput.isNotBlank()) {
            sb.appendLine("| 目标节点 | `${escapeTableCell(report.targetInput.trim())}` | 端口 ${report.tcpPort} |")
        }

        report.tcpPingSummary?.let { tcp ->
            if (tcp.hasValidLatency) {
                val main = "均 **${formatMs(tcp.avgMs!!)}** (最小 ${formatMs(tcp.minMs!!)} / 最大 ${formatMs(tcp.maxMs!!)})"
                val note = "抖动 ${formatMs(tcp.jitterMdevMs ?: 0.0)} · ${tcp.received}/${tcp.sent} 包 · 丢包 ${formatPercent(tcp.lossPercent)}"
                sb.appendLine("| TCPing 延迟 | $main | $note |")
            } else {
                val reason = sanitizeInline(tcp.errorMessage ?: "全部超时或端口不可达")
                sb.appendLine("| TCPing 延迟 | **超时 / 不可达** (丢包 ${formatPercent(tcp.lossPercent)}) | ${escapeTableCell(reason)} |")
            }
        }

        report.httpLatencyResult?.let { http ->
            if (http.success && http.totalMs != null) {
                val status = http.statusCode?.let { "HTTP $it" } ?: "成功"
                val ttfbPart = http.ttfbMs?.let { "TTFB ${formatMs(it)}" }
                val tlsPart = http.tlsHandshakeMs?.takeIf { it > 0.0 }?.let { "TLS ${formatMs(it)}" }
                val note = listOfNotNull(status, ttfbPart, tlsPart, http.protocol).joinToString(" · ")
                sb.appendLine("| HTTP 延迟 | **${formatMs(http.totalMs)}** | ${escapeTableCell(note)} |")
            } else {
                sb.appendLine("| HTTP 延迟 | 探测失败 | ${escapeTableCell(sanitizeInline(http.errorMessage ?: "请求失败"))} |")
            }
        }

        report.iperfDownloadResult?.let { dl ->
            if (dl.success) {
                val note = buildIperfTableNote(dl)
                sb.appendLine("| iperf3 下载 (-R) | **${formatMbps(dl.effectiveMbps)}** | ${escapeTableCell(note)} |")
            } else {
                sb.appendLine("| iperf3 下载 (-R) | 测速失败 | ${escapeTableCell(sanitizeInline(dl.errorMessage ?: "连接失败"))} |")
            }
        }

        report.iperfUploadResult?.let { ul ->
            if (ul.success) {
                val note = buildIperfTableNote(ul)
                sb.appendLine("| iperf3 上传 | **${formatMbps(ul.effectiveMbps)}** | ${escapeTableCell(note)} |")
            } else {
                sb.appendLine("| iperf3 上传 | 测速失败 | ${escapeTableCell(sanitizeInline(ul.errorMessage ?: "连接失败"))} |")
            }
        }

        report.ipIntelligence?.let { intel ->
            val flag = IpClassificationEngine.countryFlagEmoji(intel.countryCode)
            val loc = IpClassificationEngine.formatLocationSummary(
                countryCode = intel.countryCode,
                countryName = intel.countryName,
                regionName = intel.regionName,
                cityName = intel.cityName,
            )
            val asnCell = buildString {
                append("$flag $loc")
                if (!intel.asn.isNullOrBlank()) {
                    append(" · `${intel.asn}`")
                }
            }
            val orgAndUsage = listOfNotNull(
                intel.orgName?.takeIf { it.isNotBlank() } ?: intel.ispName?.takeIf { it.isNotBlank() },
                intel.usageType.label.takeIf { intel.usageType != IpUsageType.UNKNOWN },
            ).joinToString(" · ").ifEmpty { "—" }
            sb.appendLine("| 归属地与 ASN | ${escapeTableCell(asnCell)} | ${escapeTableCell(orgAndUsage)} |")
            sb.appendLine("| IP 原生鉴定 | **${intel.originType.label}** | ${escapeTableCell(intel.originEvidence)} |")
        }

        sb.appendLine()
        sb.append("> 测试时间：$timestamp · 由 Anode 手机端测机生成")
        return sb.toString()
    }

    private fun buildClientEnvBadge(report: NetBenchReport): String {
        val client = report.clientNetwork
        val carrierText = client?.carrierDisplayName?.takeIf { it.isNotBlank() } ?: "本机网络"
        val family = client?.ipFamily?.takeIf { it != IpFamily.UNKNOWN }
            ?: report.resolvedTarget?.ipFamily?.takeIf { it != IpFamily.UNKNOWN }
            ?: IpFamily.IPV4
        return "$carrierText · ${family.label}"
    }

    private fun buildTargetDisplay(report: NetBenchReport, maskTargetIp: Boolean): String {
        val resolved = report.resolvedTarget
        if (resolved != null) {
            val shownIp = if (maskTargetIp) {
                NetBenchTargetParser.maskTargetIpLastSegment(resolved.resolvedIp)
            } else {
                resolved.resolvedIp
            }
            val endpoint = resolved.formatAddressWithPort(report.tcpPort, maskedIp = shownIp)
            return if (resolved.isDomain) {
                "`${resolved.displayHost}` (`$endpoint` · ${resolved.ipFamily.label})"
            } else {
                "`$endpoint` (${resolved.ipFamily.label})"
            }
        }
        val raw = report.targetInput.trim()
        return if (raw.isNotEmpty()) "`$raw:${report.tcpPort}`" else ""
    }

    private fun formatTcpPingCompact(tcp: TcpPingSummary): String {
        if (!tcp.hasValidLatency) {
            return "全部超时/不可达 ｜ 丢包 **${formatPercent(tcp.lossPercent)}**"
        }
        return buildString {
            append("均 **${formatMs(tcp.avgMs!!)}**")
            append(" ｜ 最小 `${formatMs(tcp.minMs!!)}`")
            append(" ｜ 最大 `${formatMs(tcp.maxMs!!)}`")
            append(" ｜ 抖动 `${formatMs(tcp.jitterMdevMs ?: 0.0)}`")
            append(" ｜ 丢包 `${formatPercent(tcp.lossPercent)}`")
        }
    }

    private fun formatHttpCompact(http: HttpLatencyResult): String {
        if (!http.success || http.totalMs == null) {
            return "失败 (${sanitizeInline(http.errorMessage ?: "请求异常")})"
        }
        val parts = mutableListOf<String>()
        http.statusCode?.let { parts += "HTTP $it" }
        parts += "总计 **${formatMs(http.totalMs)}**"
        http.ttfbMs?.let { parts += "TTFB `${formatMs(it)}`" }
        http.tlsHandshakeMs?.takeIf { it > 0.0 }?.let { parts += "TLS `${formatMs(it)}`" }
        return parts.joinToString(" ｜ ")
    }

    private fun formatIpIntelCompact(intel: IpIntelligenceReport): String {
        val flag = IpClassificationEngine.countryFlagEmoji(intel.countryCode)
        val loc = IpClassificationEngine.formatLocationSummary(
            countryCode = intel.countryCode,
            countryName = intel.countryName,
            regionName = intel.regionName,
            cityName = intel.cityName,
        )
        val parts = mutableListOf<String>()
        parts += "$flag $loc"
        val asnOrg = buildString {
            if (!intel.asn.isNullOrBlank()) append("`${intel.asn}`")
            val org = intel.orgName?.takeIf { it.isNotBlank() } ?: intel.ispName?.takeIf { it.isNotBlank() }
            if (org != null) {
                if (isNotEmpty()) append(" ")
                append("(${sanitizeInline(org)})")
            }
        }
        if (asnOrg.isNotEmpty()) parts += asnOrg
        if (intel.usageType != IpUsageType.UNKNOWN) {
            parts += intel.usageType.label
        }
        parts += "**${intel.originEvidence}**"
        return parts.joinToString(" · ")
    }

    private fun buildIperfTableNote(result: Iperf3Result): String {
        val parts = mutableListOf<String>()
        parts += "耗时 ${String.format(Locale.US, "%.1fs", result.durationSec)}"
        parts += "传输 ${formatBytes(result.bytesTransferred)}"
        if (result.peakMbps > result.effectiveMbps * 1.05) {
            parts += "峰值 ${formatMbps(result.peakMbps)}"
        }
        result.retransmits?.takeIf { it >= 0 }?.let { parts += "重传 $it" }
        return parts.joinToString(" · ")
    }

    /**
     * Defense-in-depth safeguard ensuring the local client's raw IP can never appear in output
     * (unless the user intentionally benchmarked that exact IP as the target).
     */
    private fun scrubClientIpIfNeeded(markdown: String, report: NetBenchReport): String {
        val clientRawIp = report.clientNetwork?.rawIp?.trim()?.takeIf { it.isNotEmpty() } ?: return markdown
        val targetIp = report.resolvedTarget?.resolvedIp?.trim()
        if (clientRawIp.equals(targetIp, ignoreCase = true) ||
            clientRawIp.equals(report.targetInput.trim(), ignoreCase = true)
        ) {
            return markdown
        }
        return if (markdown.contains(clientRawIp, ignoreCase = true)) {
            markdown.replace(clientRawIp, "[已隐藏本机IP]", ignoreCase = true)
        } else {
            markdown
        }
    }

    fun formatMs(ms: Double): String = String.format(Locale.US, "%.1f ms", ms)

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
        text.replace('\n', ' ').replace('\r', ' ').trim()

    private fun escapeTableCell(text: String): String =
        sanitizeInline(text).replace("|", "\\|")
}
