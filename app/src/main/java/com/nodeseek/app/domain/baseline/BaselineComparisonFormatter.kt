package com.nodeseek.app.domain.baseline

import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchReportSanitizer
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders a shareable NodeSeek Markdown Before-vs-After comparison card from [BaselineDiffReport]
 * with strict target IP privacy masking.
 */
object BaselineComparisonFormatter {

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun formatMarkdown(
        diff: BaselineDiffReport,
        maskTargetIp: Boolean = true,
        titleOverride: String? = null,
    ): String {
        val beforeDate = formatDate(diff.baseline.capturedAtEpochMillis)
        val afterDate = formatDate(diff.current.capturedAtEpochMillis)
        val rawHost = diff.current.hostDisplay.ifBlank { diff.baseline.hostDisplay }
        val displayHost = maskDisplayHost(rawHost, maskTargetIp)

        val sb = StringBuilder()
        val headerTitle = titleOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: "📊 Before vs After 购入基线对比卡"
        sb.append("## ").append(headerTitle).append('\n')
        sb.append("> **对比目标**: `").append(displayHost).append("` · ")
        sb.append("**购入基线**: `").append(diff.baseline.label).append("` (").append(beforeDate).append(") → ")
        sb.append("**当前实测**: ").append(afterDate).append(" (间隔 **").append(diff.elapsedDays).append("** 天)\n")
        sb.append("> **综合结论**: **").append(diff.overallVerdict.label).append("**\n\n")

        if (diff.highlightBullets.isNotEmpty()) {
            sb.append("### 核心变化摘要\n")
            for (bullet in diff.highlightBullets) {
                sb.append("- ").append(bullet).append('\n')
            }
            sb.append('\n')
        }

        val headers = listOf("对比维度", "购入基线 (Before)", "当前实测 (After)", "增减 / 变化结论")
        val rows = mutableListOf<List<String>>()

        for (bw in diff.bandwidthDeltas) {
            val deltaText = buildString {
                val d = bw.deltaMbps
                val p = bw.deltaPercent
                if (d != null) {
                    append(String.format(Locale.US, "%+.1f Mbps", d))
                }
                if (p != null) {
                    if (isNotEmpty()) append(" ")
                    append(String.format(Locale.US, "(%+.1f%%)", p))
                }
                append(" · ").append(trendBadge(bw.trend))
            }
            rows += listOf(
                bw.metricLabel,
                formatMbps(bw.beforeMbps),
                formatMbps(bw.afterMbps),
                deltaText,
            )
        }

        for (lat in diff.latencyDeltas) {
            val bStr = lat.beforeValue?.let { String.format(Locale.US, "%.1f %s", it, lat.unit) } ?: "-"
            val aStr = lat.afterValue?.let { String.format(Locale.US, "%.1f %s", it, lat.unit) } ?: "-"
            val dStr = lat.deltaValue?.let { String.format(Locale.US, "%+.1f %s", it, lat.unit) } ?: "-"
            rows += listOf(
                lat.metricLabel,
                bStr,
                aStr,
                "$dStr · ${trendBadge(lat.trend)}",
            )
        }

        for (shift in diff.routingShifts) {
            rows += listOf(
                shift.directionLabel,
                shift.beforeRoute?.ifBlank { "-" } ?: "-",
                shift.afterRoute?.ifBlank { "-" } ?: "-",
                shift.shiftSummary,
            )
        }

        if (diff.baseline.ipMetrics != null || diff.current.ipMetrics != null) {
            val bIp = diff.baseline.ipMetrics?.originType?.label ?: "-"
            val aIp = diff.current.ipMetrics?.originType?.label ?: "-"
            val verdict = if (diff.ipOriginChanged) "⚠️ 属性变更" else "保持不变"
            rows += listOf("IP 原生/广播", bIp, aIp, verdict)
        }

        for (unlock in diff.streamingDiffs) {
            val bText = formatUnlockCell(unlock.beforeStatus, unlock.beforeRegion)
            val aText = formatUnlockCell(unlock.afterStatus, unlock.afterRegion)
            rows += listOf(
                "流媒体 · ${unlock.displayName}",
                bText,
                aText,
                unlock.changeType.label,
            )
        }

        if (rows.isNotEmpty()) {
            sb.append(NetBenchMarkdownFormatter.renderAlignedMarkdownTable(headers, rows))
            sb.append('\n')
        }

        val rawOutput = sb.toString().trimEnd()
        return if (maskTargetIp) {
            scrubSensitiveIps(
                text = rawOutput,
                knownIps = listOfNotNull(diff.baseline.resolvedIp, diff.current.resolvedIp, diff.baseline.hostDisplay, diff.current.hostDisplay),
            )
        } else {
            rawOutput
        }
    }

    internal fun maskDisplayHost(
        rawHost: String,
        maskTargetIp: Boolean,
    ): String {
        if (!maskTargetIp) return rawHost
        val trimmed = rawHost.trim()
        if (trimmed.isEmpty()) return rawHost
        val parsed = NetBenchTargetParser.parse(trimmed)
        if (parsed != null && parsed.isLiteralIp) {
            val masked = NetBenchTargetParser.maskTargetIpLastSegment(parsed.host)
            val port = parsed.explicitPort
            return if (port != null) {
                if (parsed.literalFamily == com.nodeseek.app.domain.netbench.IpFamily.IPV6) {
                    "[$masked]:$port"
                } else {
                    "$masked:$port"
                }
            } else {
                masked
            }
        }
        return if (NetBenchTargetParser.detectIpFamily(trimmed) != com.nodeseek.app.domain.netbench.IpFamily.UNKNOWN) {
            NetBenchTargetParser.maskTargetIpLastSegment(trimmed)
        } else {
            rawHost
        }
    }

    internal fun scrubSensitiveIps(
        text: String,
        knownIps: List<String> = emptyList(),
    ): String {
        var out = text
        for (candidate in knownIps) {
            val clean = candidate.trim()
            if (clean.isEmpty()) continue
            val extractedIp = if (NetBenchTargetParser.detectIpFamily(clean) != com.nodeseek.app.domain.netbench.IpFamily.UNKNOWN) {
                clean
            } else {
                NetBenchTargetParser.parse(clean)?.takeIf { it.isLiteralIp }?.host
            }
            if (extractedIp != null) {
                val masked = NetBenchTargetParser.maskTargetIpLastSegment(extractedIp)
                for (form in NetBenchTargetParser.allCanonicalIpForms(extractedIp)) {
                    out = out.replace(form, masked, ignoreCase = true)
                }
            }
        }
        out = IpPrivacyMasker.mask(out)
        out = NetBenchReportSanitizer.maskAnyRemainingIpLiterals(out)
        return out
    }

    private fun formatDate(epochMillis: Long): String =
        DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(VALUATION_ZONE_ID).toLocalDate())

    private fun formatMbps(v: Double?): String =
        if (v == null) "-" else String.format(Locale.US, "%.1f Mbps", v)

    private fun formatUnlockCell(status: StreamingUnlockStatus, region: String?): String =
        if (region.isNullOrBlank()) status.label else "${status.label} ($region)"

    private fun trendBadge(trend: MetricTrend): String = when (trend) {
        MetricTrend.IMPROVED -> "提升"
        MetricTrend.UNCHANGED -> "稳定"
        MetricTrend.DEGRADED_WARN -> "轻度回落"
        MetricTrend.DEGRADED_SEVERE -> "明显缩水/劣化"
        MetricTrend.INFO -> "变化"
    }
}
