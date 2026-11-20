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

    const val EMPTY_CELL_PLACEHOLDER: String = "—"

    private const val MAX_HOST_DISPLAY_WIDTH: Int = 28
    private const val MAX_RESULT_CELL_DISPLAY_WIDTH: Int = 28
    private const val MAX_NOTE_CELL_DISPLAY_WIDTH: Int = 46
    private const val MAX_ORG_DISPLAY_WIDTH: Int = 24
    private const val MAX_ERROR_DISPLAY_WIDTH: Int = 36

    private val DISPLAY_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")
    private val CORPORATE_SUFFIX_REGEX = Regex(
        """(?i)(?:,\s*|\s+)(?:llc|inc\.?|ltd\.?|limited|corp\.?|corporation|gmbh|co\.?,?\s*ltd\.?|holdings?(?:\s+corporation)?|dba\s+.+)$""",
    )

    fun format(
        report: NetBenchReport,
        options: NetBenchMarkdownOptions = NetBenchMarkdownOptions(),
    ): String {
        val rawMarkdown = when (options.style) {
            NetBenchMarkdownStyle.REPLY_CARD -> formatReplyCard(report, options)
            NetBenchMarkdownStyle.FULL_TABLE -> formatFullTable(report, options)
        }
        val targetSafe = if (options.maskTargetIp) {
            scrubTargetEndpointIfNeeded(rawMarkdown, report)
        } else {
            rawMarkdown
        }
        return scrubClientIpIfNeeded(targetSafe, report)
    }

    private fun formatReplyCard(
        report: NetBenchReport,
        options: NetBenchMarkdownOptions,
    ): String {
        val timestamp = options.timestampLabel?.takeIf { it.isNotBlank() }
            ?: LocalDateTime.now(DEFAULT_ZONE).format(DISPLAY_TIME_FORMATTER)

        val lines = mutableListOf<String>()
        if (options.includeClientNetwork) {
            val envBadge = buildClientEnvBadge(report)
            lines += "> 📡 **本机网络实测（$envBadge）** · `$timestamp`"
        } else {
            lines += "> 📡 **移动端网络实测** · `$timestamp`"
        }

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
                    val reason = truncateByDisplayWidth(sanitizeInline(dl.errorMessage ?: "连接异常"), MAX_ERROR_DISPLAY_WIDTH)
                    add("下载失败 ($reason)")
                }
            }
            report.iperfUploadResult?.let { ul ->
                if (ul.success) {
                    add("上传 **${formatMbps(ul.effectiveMbps)}**")
                } else {
                    val reason = truncateByDisplayWidth(sanitizeInline(ul.errorMessage ?: "连接异常"), MAX_ERROR_DISPLAY_WIDTH)
                    add("上传失败 ($reason)")
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
        val timestamp = options.timestampLabel?.takeIf { it.isNotBlank() }
            ?: LocalDateTime.now(DEFAULT_ZONE).format(DISPLAY_TIME_FORMATTER)

        val headers = listOf("测试项目", "实测结果", "备注")
        val rows = mutableListOf<List<String>>()

        if (options.includeClientNetwork) {
            val envBadge = buildClientEnvBadge(report).ifBlank { "本机网络" }
            rows += listOf("本机网络环境", envBadge, "已自动隐藏本机 IP")
        }

        buildTargetTableRow(report, options.maskTargetIp)?.let { rows += it }

        report.tcpPingSummary?.let { tcp ->
            if (tcp.hasValidLatency) {
                val main = "**${formatMs(tcp.avgMs!!)}** (丢包 ${formatPercent(tcp.lossPercent)})"
                val note = listOf(
                    "最小 ${formatMs(tcp.minMs!!)}",
                    "最大 ${formatMs(tcp.maxMs!!)}",
                    "抖动 ${formatMs(tcp.jitterMdevMs ?: 0.0)}",
                    "${tcp.received}/${tcp.sent} 包",
                ).joinToString(" · ")
                rows += listOf("TCPing 延迟", main, note)
            } else {
                val reason = truncateByDisplayWidth(
                    sanitizeInline(tcp.errorMessage ?: "全部超时或端口不可达"),
                    MAX_ERROR_DISPLAY_WIDTH,
                )
                val note = "丢包 ${formatPercent(tcp.lossPercent)} · ${reason.ifBlank { EMPTY_CELL_PLACEHOLDER }}"
                rows += listOf("TCPing 延迟", "**超时 / 不可达**", note)
            }
        }

        report.httpLatencyResult?.let { http ->
            if (http.success && http.totalMs != null) {
                val status = http.statusCode?.let { "HTTP $it" } ?: "成功"
                val ttfbPart = http.ttfbMs?.let { "TTFB ${formatMs(it)}" }
                val tlsPart = http.tlsHandshakeMs?.takeIf { it > 0.0 }?.let { "TLS ${formatMs(it)}" }
                val protoPart = http.protocol?.trim()?.takeIf { it.isNotEmpty() }
                val note = listOfNotNull(status, ttfbPart, tlsPart, protoPart)
                    .joinToString(" · ")
                    .ifBlank { EMPTY_CELL_PLACEHOLDER }
                rows += listOf("HTTP 延迟", "**${formatMs(http.totalMs)}**", note)
            } else {
                val reason = truncateByDisplayWidth(
                    sanitizeInline(http.errorMessage ?: "请求失败"),
                    MAX_ERROR_DISPLAY_WIDTH,
                )
                rows += listOf("HTTP 延迟", "探测失败", reason.ifBlank { EMPTY_CELL_PLACEHOLDER })
            }
        }

        report.iperfDownloadResult?.let { dl ->
            if (dl.success) {
                rows += listOf(
                    "iperf3 下载 (-R)",
                    "**${formatMbps(dl.effectiveMbps)}**",
                    buildIperfTableNote(dl),
                )
            } else {
                val reason = truncateByDisplayWidth(
                    sanitizeInline(dl.errorMessage ?: "连接失败"),
                    MAX_NOTE_CELL_DISPLAY_WIDTH,
                )
                rows += listOf("iperf3 下载 (-R)", "测速失败", reason.ifBlank { EMPTY_CELL_PLACEHOLDER })
            }
        }

        report.iperfUploadResult?.let { ul ->
            if (ul.success) {
                rows += listOf(
                    "iperf3 上传",
                    "**${formatMbps(ul.effectiveMbps)}**",
                    buildIperfTableNote(ul),
                )
            } else {
                val reason = truncateByDisplayWidth(
                    sanitizeInline(ul.errorMessage ?: "连接失败"),
                    MAX_NOTE_CELL_DISPLAY_WIDTH,
                )
                rows += listOf("iperf3 上传", "测速失败", reason.ifBlank { EMPTY_CELL_PLACEHOLDER })
            }
        }

        report.ipIntelligence?.let { intel ->
            val flag = IpClassificationEngine.countryFlagEmoji(intel.countryCode)
            val loc = IpClassificationEngine.formatLocationSummary(
                countryCode = intel.countryCode,
                countryName = intel.countryName,
                regionName = intel.regionName,
                cityName = intel.cityName,
            ).trim()
            val cleanLoc = truncateByDisplayWidth(
                text = if (loc.isNotEmpty()) "$flag $loc" else EMPTY_CELL_PLACEHOLDER,
                maxWidth = MAX_RESULT_CELL_DISPLAY_WIDTH,
            )
            val cleanAsn = intel.asn?.trim()?.takeIf { it.isNotEmpty() }?.let { "`${sanitizeCodeInline(it)}`" }
            val cleanOrg = compactOrgName(intel.orgName ?: intel.ispName)
            val cleanUsage = intel.usageType.label.takeIf { intel.usageType != IpUsageType.UNKNOWN }
            val orgAndUsage = listOfNotNull(cleanAsn, cleanOrg, cleanUsage)
                .joinToString(" · ")
                .ifBlank { EMPTY_CELL_PLACEHOLDER }

            rows += listOf("归属地与 ASN", cleanLoc, orgAndUsage)

            val cleanEvidence = formatCompactOriginEvidence(intel)
            rows += listOf(
                "IP 原生鉴定",
                "**${intel.originType.label}**",
                cleanEvidence,
            )
        }

        if (rows.isEmpty()) {
            rows += listOf("测试状态", EMPTY_CELL_PLACEHOLDER, "暂无可导出的测试结果")
        }

        val sb = StringBuilder()
        sb.appendLine("### 📡 移动端网络实测报告")
        sb.appendLine()
        sb.append(renderAlignedMarkdownTable(headers = headers, rows = rows))
        sb.appendLine()
        sb.appendLine()
        sb.append("> 测试时间：$timestamp · 由 Anode 手机端测机生成")
        return sb.toString()
    }

    private fun buildTargetTableRow(
        report: NetBenchReport,
        maskTargetIp: Boolean,
    ): List<String>? {
        val resolved = report.resolvedTarget
        if (resolved != null) {
            val formattedIp = formatTargetEndpoint(
                ip = resolved.resolvedIp,
                ipFamily = resolved.ipFamily,
                port = report.tcpPort,
                maskTargetIp = maskTargetIp,
            )
            if (resolved.isDomain) {
                val safeHost = sanitizeCodeInline(
                    truncateByDisplayWidth(resolved.displayHost.trim(), MAX_HOST_DISPLAY_WIDTH),
                )
                val noteParts = mutableListOf<String>()
                noteParts += "IP `$formattedIp`"
                noteParts += resolved.ipFamily.label
                if (resolved.dnsDurationMs > 0.0) {
                    noteParts += "DNS ${formatMs(resolved.dnsDurationMs)}"
                }
                return listOf("目标节点", "`$safeHost`", noteParts.joinToString(" · "))
            } else {
                val note = if (maskTargetIp) {
                    "${resolved.ipFamily.label} · 已隐藏完整 IP/端口"
                } else {
                    "${resolved.ipFamily.label} · 端口 ${report.tcpPort}"
                }
                return listOf("目标节点", "`$formattedIp`", note)
            }
        }

        val raw = report.targetInput.trim()
        if (raw.isEmpty()) return null
        val parsed = NetBenchTargetParser.parse(raw)
        return if (parsed != null) {
            val effectivePort = parsed.effectivePort(report.tcpPort)
            if (parsed.isLiteralIp) {
                val endpoint = formatTargetEndpoint(
                    ip = parsed.host,
                    ipFamily = parsed.literalFamily,
                    port = effectivePort,
                    maskTargetIp = maskTargetIp,
                )
                val familyLabel = parsed.literalFamily.takeIf { it != IpFamily.UNKNOWN }?.label ?: "目标主机"
                val note = if (maskTargetIp) "$familyLabel · 已隐藏完整 IP/端口" else "$familyLabel · 端口 $effectivePort"
                listOf("目标节点", "`$endpoint`", note)
            } else {
                val safeHost = sanitizeCodeInline(truncateByDisplayWidth(parsed.host, MAX_HOST_DISPLAY_WIDTH))
                val shownTarget = if (maskTargetIp) safeHost else "$safeHost:$effectivePort"
                val note = if (maskTargetIp) "已隐藏测速端口" else "端口 $effectivePort"
                listOf("目标节点", "`$shownTarget`", note)
            }
        } else {
            val cleaned = sanitizeCodeInline(truncateByDisplayWidth(raw, MAX_HOST_DISPLAY_WIDTH))
            val note = if (maskTargetIp) "已隐藏测速端口" else "端口 ${report.tcpPort}"
            listOf("目标节点", "`$cleaned`", note)
        }
    }

    private fun formatTargetEndpoint(
        ip: String,
        ipFamily: IpFamily,
        port: Int,
        maskTargetIp: Boolean,
    ): String {
        return if (maskTargetIp) {
            val masked = NetBenchTargetParser.maskTargetIpLastSegment(ip)
            if (ipFamily == IpFamily.IPV6) "[$masked]" else masked
        } else {
            if (ipFamily == IpFamily.IPV6) "[$ip]:$port" else "$ip:$port"
        }
    }

    private fun buildTargetDisplay(report: NetBenchReport, maskTargetIp: Boolean): String {
        val resolved = report.resolvedTarget
        if (resolved != null) {
            val endpoint = formatTargetEndpoint(
                ip = resolved.resolvedIp,
                ipFamily = resolved.ipFamily,
                port = report.tcpPort,
                maskTargetIp = maskTargetIp,
            )
            return if (resolved.isDomain) {
                val safeHost = sanitizeCodeInline(
                    truncateByDisplayWidth(resolved.displayHost.trim(), MAX_HOST_DISPLAY_WIDTH),
                )
                "`$safeHost` (`$endpoint` · ${resolved.ipFamily.label})"
            } else {
                "`$endpoint` (${resolved.ipFamily.label})"
            }
        }

        val raw = report.targetInput.trim()
        if (raw.isEmpty()) return ""
        val parsed = NetBenchTargetParser.parse(raw)
        if (parsed != null) {
            val effectivePort = parsed.effectivePort(report.tcpPort)
            return if (parsed.isLiteralIp) {
                val endpoint = formatTargetEndpoint(
                    ip = parsed.host,
                    ipFamily = parsed.literalFamily,
                    port = effectivePort,
                    maskTargetIp = maskTargetIp,
                )
                "`$endpoint`"
            } else {
                val safeHost = sanitizeCodeInline(truncateByDisplayWidth(parsed.host, MAX_HOST_DISPLAY_WIDTH))
                if (maskTargetIp) "`$safeHost`" else "`$safeHost:$effectivePort`"
            }
        }
        val cleaned = sanitizeCodeInline(truncateByDisplayWidth(raw, MAX_HOST_DISPLAY_WIDTH))
        return if (maskTargetIp) "`$cleaned`" else "`$cleaned:${report.tcpPort}`"
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
            val reason = truncateByDisplayWidth(sanitizeInline(http.errorMessage ?: "请求异常"), MAX_ERROR_DISPLAY_WIDTH)
            return "失败 ($reason)"
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
            if (!intel.asn.isNullOrBlank()) append("`${sanitizeCodeInline(intel.asn)}`")
            val org = compactOrgName(intel.orgName ?: intel.ispName)
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

    private fun buildIperfTableNote(result: Iperf3Result): String {
        val parts = mutableListOf<String>()
        parts += "耗时 ${formatSeconds(result.durationSec)}"
        parts += "传输 ${formatBytes(result.bytesTransferred)}"
        if (result.peakMbps > result.effectiveMbps * 1.05) {
            parts += "峰值 ${formatMbps(result.peakMbps)}"
        }
        result.retransmits?.takeIf { it >= 0 }?.let { parts += "重传 $it" }
        return parts.joinToString(" · ").ifBlank { EMPTY_CELL_PLACEHOLDER }
    }

    private fun compactOrgName(rawOrg: String?): String? {
        val cleaned = rawOrg
            ?.let(::sanitizeInline)
            ?.replace(CORPORATE_SUFFIX_REGEX, "")
            ?.trim(' ', ',', '.')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return truncateByDisplayWidth(cleaned, MAX_ORG_DISPLAY_WIDTH)
    }

    private fun formatCompactOriginEvidence(intel: IpIntelligenceReport): String {
        var evidence = sanitizeInline(intel.originEvidence)
        if (evidence.isEmpty()) return EMPTY_CELL_PLACEHOLDER
        val redundantPrefix = "${intel.originType.label} "
        if (evidence.startsWith(redundantPrefix)) {
            val remainder = evidence.removePrefix(redundantPrefix).trim()
            if (remainder.startsWith("(") && remainder.endsWith(")") && remainder.length > 2) {
                evidence = remainder.substring(1, remainder.length - 1).trim()
            } else if (remainder.isNotEmpty()) {
                evidence = remainder
            }
        }
        return truncateByDisplayWidth(evidence, MAX_NOTE_CELL_DISPLAY_WIDTH).ifBlank { EMPTY_CELL_PLACEHOLDER }
    }

    /**
     * Renders a GFM Markdown table with strict column-count normalization, empty-cell placeholders,
     * pipe escaping, and East-Asian CJK visual-width padding so both raw Markdown editors and
     * rendered NodeSeek HTML tables align cleanly.
     */
    internal fun renderAlignedMarkdownTable(
        headers: List<String>,
        rows: List<List<String>>,
    ): String {
        val colCount = headers.size
        require(colCount > 0) { "Table must have at least 1 column" }

        val cleanHeaders = headers.map { normalizeTableCell(it) }
        val cleanRows = rows.map { row ->
            List(colCount) { col ->
                normalizeTableCell(row.getOrElse(col) { EMPTY_CELL_PLACEHOLDER })
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

    private fun scrubTargetEndpointIfNeeded(markdown: String, report: NetBenchReport): String {
        var result = markdown
        val targetIp = report.resolvedTarget?.resolvedIp?.trim()?.takeIf { it.isNotEmpty() }
        if (targetIp != null) {
            val maskedIp = NetBenchTargetParser.maskTargetIpLastSegment(targetIp)
            if (result.contains(targetIp, ignoreCase = true)) {
                result = result.replace(targetIp, maskedIp, ignoreCase = true)
            }
        }
        val portsToScrub = buildSet {
            add(report.tcpPort)
            report.tcpPingSummary?.port?.let(::add)
            report.iperfDownloadResult?.port?.let(::add)
            report.iperfUploadResult?.port?.let(::add)
            NetBenchTargetParser.parse(report.targetInput)?.explicitPort?.let(::add)
        }
        for (port in portsToScrub) {
            if (port in 1..65535) {
                result = result
                    .replace(":$port", "")
                    .replace("端口 $port", "已隐藏端口")
                    .replace(" $port 端口", " 对应端口")
            }
        }
        return result
    }

    private fun buildClientEnvBadge(report: NetBenchReport): String {
        val client = report.clientNetwork
        val carrierText = client?.carrierDisplayName?.takeIf { it.isNotBlank() } ?: "本机网络"
        val family = client?.ipFamily?.takeIf { it != IpFamily.UNKNOWN }
            ?: report.resolvedTarget?.ipFamily?.takeIf { it != IpFamily.UNKNOWN }
            ?: IpFamily.IPV4
        return "$carrierText · ${family.label}"
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
