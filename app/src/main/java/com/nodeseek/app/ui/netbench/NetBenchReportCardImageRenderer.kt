package com.nodeseek.app.ui.netbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.nodeseek.app.domain.netbench.EgressRouteKind
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IcmpPingSummary
import com.nodeseek.app.domain.netbench.IpClassificationEngine
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3IntervalSample
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.NetBenchReportCardKind
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.TcpPingSummary
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Offscreen-rendered PNG snapshot of a single Mobile Network Tester visual card,
 * proportioned specifically for NodeSeek forum post columns (`1000px` wide @ `2x` Retina).
 */
data class RenderedNetBenchCardImage(
    val kind: NetBenchReportCardKind,
    val pngBytes: ByteArray,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val cacheKey: String,
)

/**
 * Abstraction for planning and rendering forum-proportioned benchmark report card images.
 * Allows deterministic JVM unit testing while using crisp offscreen Android Canvas rendering in production.
 */
interface NetBenchCardImageGenerator {
    fun planCards(
        report: NetBenchReport,
        maskTargetIp: Boolean,
        includeClientNetwork: Boolean,
        darkTheme: Boolean,
    ): List<Pair<NetBenchReportCardKind, String>>

    fun renderCard(
        kind: NetBenchReportCardKind,
        report: NetBenchReport,
        maskTargetIp: Boolean,
        includeClientNetwork: Boolean,
        darkTheme: Boolean,
    ): RenderedNetBenchCardImage?
}

/**
 * Pure-Kotlin fingerprint planner + high-DPI Android [Canvas] renderer matching the visual design
 * language of [NetBenchVisualComponents] (`PaperLight` & `ObsidianDark` tokens) in compact
 * horizontal forum card proportions (`~2.0:1` to `3.1:1`, zero text truncation, transparent outer corners).
 *
 * SECURITY / PRIVACY:
 * - Local client raw/masked egress IPs are NEVER drawn onto any exported image.
 * - When [maskTargetIp] is enabled, target IP last segment and benchmark ports are masked identically
 *   to [NetBenchMarkdownFormatter].
 */
object AndroidNetBenchCardImageGenerator : NetBenchCardImageGenerator {

    private const val LOGICAL_WIDTH_DP = 500f
    private const val DENSITY_SCALE = 2.0f
    private const val OUTER_INSET_DP = 2f
    private const val CARD_CORNER_DP = 18f
    private const val CARD_PAD_H_DP = 16f
    private const val CARD_PAD_V_DP = 14f

    override fun planCards(
        report: NetBenchReport,
        maskTargetIp: Boolean,
        includeClientNetwork: Boolean,
        darkTheme: Boolean,
    ): List<Pair<NetBenchReportCardKind, String>> {
        val planned = ArrayList<Pair<NetBenchReportCardKind, String>>(4)

        report.ipIntelligence?.let { intel ->
            val clientFooter = if (includeClientNetwork) buildSafeClientFooterText(report) else null
            val targetIpShown = formatDisplayIp(intel.ip, maskTargetIp)
            val rawKey = buildString {
                append("v1|IP_INTEL|dark=").append(darkTheme)
                append("|ip=").append(targetIpShown)
                append("|fam=").append(intel.ipFamily.name)
                append("|cc=").append(intel.countryCode.orEmpty())
                append("|cn=").append(intel.countryName.orEmpty())
                append("|rn=").append(intel.regionName.orEmpty())
                append("|city=").append(intel.cityName.orEmpty())
                append("|asn=").append(intel.asn.orEmpty())
                append("|org=").append(intel.orgName.orEmpty())
                append("|isp=").append(intel.ispName.orEmpty())
                append("|usage=").append(intel.usageType.name)
                append("|origin=").append(intel.originType.name)
                append("|ev=").append(scrubTargetIpInText(intel.originEvidence, report, maskTargetIp))
                append("|geo=").append(intel.geoSource.orEmpty())
                append("|whois=").append(intel.whoisSource.orEmpty())
                append("|rir=").append(intel.rirAuthority.orEmpty())
                append("|err=").append(intel.errorMessage.orEmpty())
                append("|client=").append(clientFooter.orEmpty())
            }
            planned += NetBenchReportCardKind.IP_INTELLIGENCE to sha256Short(rawKey)
        }

        report.icmpPingSummary?.takeIf { it.sent > 0 }?.let { ping ->
            val rawKey = buildString {
                append("v1|ICMP_PING|dark=").append(darkTheme)
                append("|sent=").append(ping.sent)
                append("|recv=").append(ping.received)
                append("|loss=").append(ping.lossPercent)
                append("|avg=").append(ping.avgMs)
                append("|min=").append(ping.minMs)
                append("|max=").append(ping.maxMs)
                append("|jit=").append(ping.jitterMdevMs)
                append("|iface=").append(ping.boundInterfaceName.orEmpty())
                append("|samples=")
                ping.samples.forEach { s ->
                    append(s.seq).append(':').append(s.success).append(':').append(s.rttMs).append(';')
                }
            }
            planned += NetBenchReportCardKind.ICMP_PING to sha256Short(rawKey)
        }

        report.tcpPingSummary?.takeIf { it.sent > 0 }?.let { tcp ->
            val http = report.httpLatencyResult
            val rawKey = buildString {
                append("v1|TCP_PING|dark=").append(darkTheme)
                append("|port=").append(if (maskTargetIp) "masked" else tcp.port.toString())
                append("|sent=").append(tcp.sent)
                append("|recv=").append(tcp.received)
                append("|loss=").append(tcp.lossPercent)
                append("|avg=").append(tcp.avgMs)
                append("|min=").append(tcp.minMs)
                append("|max=").append(tcp.maxMs)
                append("|jit=").append(tcp.jitterMdevMs)
                append("|samples=")
                tcp.samples.forEach { s ->
                    append(s.seq).append(':').append(s.success).append(':').append(s.rttMs).append(';')
                }
                if (http != null) {
                    append("|http=").append(http.success).append(':').append(http.statusCode)
                        .append(':').append(http.totalMs).append(':').append(http.tlsHandshakeMs)
                        .append(':').append(http.ttfbMs)
                }
            }
            planned += NetBenchReportCardKind.TCP_PING to sha256Short(rawKey)
        }

        val succeededIperf = listOfNotNull(report.iperfDownloadResult, report.iperfUploadResult)
            .filter { it.success }
        if (succeededIperf.isNotEmpty()) {
            val rawKey = buildString {
                append("v1|IPERF3|dark=").append(darkTheme)
                append("|mask=").append(maskTargetIp)
                succeededIperf.forEach { res ->
                    append("|dir=").append(res.direction.name)
                    append(",port=").append(if (maskTargetIp) "masked" else res.port.toString())
                    append(",p=").append(res.parallelStreams)
                    append(",bytes=").append(res.bytesTransferred)
                    append(",avg=").append(res.effectiveMbps)
                    append(",peak=").append(res.maxIntervalMbps)
                    append(",srv=").append(res.serverMbps)
                    append(",retr=").append(res.retransmits)
                    res.intervals.forEach { iv ->
                        append('[').append(iv.index).append(':').append(iv.bitrateMbps)
                            .append(':').append(iv.bytesTransferred).append(']')
                    }
                }
            }
            planned += NetBenchReportCardKind.IPERF3 to sha256Short(rawKey)
        }

        return planned
    }

    override fun renderCard(
        kind: NetBenchReportCardKind,
        report: NetBenchReport,
        maskTargetIp: Boolean,
        includeClientNetwork: Boolean,
        darkTheme: Boolean,
    ): RenderedNetBenchCardImage? {
        val plannedKey = planCards(
            report = report,
            maskTargetIp = maskTargetIp,
            includeClientNetwork = includeClientNetwork,
            darkTheme = darkTheme,
        ).firstOrNull { it.first == kind }?.second ?: return null

        val palette = if (darkTheme) CardPalette.Dark else CardPalette.Light
        val bitmap = when (kind) {
            NetBenchReportCardKind.IP_INTELLIGENCE -> {
                val intel = report.ipIntelligence ?: return null
                val clientFooter = if (includeClientNetwork) buildSafeClientFooterText(report) else null
                renderIpIntelligenceBitmap(
                    intel = intel,
                    report = report,
                    maskTargetIp = maskTargetIp,
                    clientFooter = clientFooter,
                    palette = palette,
                )
            }
            NetBenchReportCardKind.ICMP_PING -> {
                val ping = report.icmpPingSummary?.takeIf { it.sent > 0 } ?: return null
                renderIcmpPingBitmap(ping = ping, palette = palette)
            }
            NetBenchReportCardKind.TCP_PING -> {
                val tcp = report.tcpPingSummary?.takeIf { it.sent > 0 } ?: return null
                renderTcpPingBitmap(
                    tcp = tcp,
                    httpResult = report.httpLatencyResult,
                    maskTargetIp = maskTargetIp,
                    palette = palette,
                )
            }
            NetBenchReportCardKind.IPERF3 -> {
                val succeeded = listOfNotNull(report.iperfDownloadResult, report.iperfUploadResult)
                    .filter { it.success }
                if (succeeded.isEmpty()) return null
                renderIperf3Bitmap(
                    results = succeeded,
                    maskTargetIp = maskTargetIp,
                    palette = palette,
                )
            }
        } ?: return null

        return try {
            val stream = ByteArrayOutputStream(32_768)
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            if (!ok || bytes.isEmpty()) return null
            RenderedNetBenchCardImage(
                kind = kind,
                pngBytes = bytes,
                fileName = "${kind.fileSlug}_${plannedKey.take(8)}.png",
                widthPx = bitmap.width,
                heightPx = bitmap.height,
                cacheKey = plannedKey,
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    // ── Card 1: IP Intelligence & Native / Broadcast Classifier ──────────────────────────────

    private fun renderIpIntelligenceBitmap(
        intel: IpIntelligenceReport,
        report: NetBenchReport,
        maskTargetIp: Boolean,
        clientFooter: String?,
        palette: CardPalette,
    ): Bitmap? {
        val orgLabel = (intel.orgName?.takeIf { it.isNotBlank() } ?: intel.ispName)?.trim()
        val sourceSummary = listOfNotNull(
            intel.geoSource?.takeIf { it.isNotBlank() }?.let { "Geo: $it" },
            intel.whoisSource?.takeIf { it.isNotBlank() }?.let { "WHOIS: $it" },
            intel.rirAuthority?.takeIf { it.isNotBlank() }?.let { "RIR: $it" },
        ).joinToString(" · ")
        val hasMetaLine = !orgLabel.isNullOrBlank() || sourceSummary.isNotEmpty()
        val hasFooter = !clientFooter.isNullOrBlank()

        var logicalHeight = 136f
        if (hasMetaLine) logicalHeight += 20f
        if (hasFooter) logicalHeight += 28f

        return createCardBitmap(logicalHeight, palette) { canvas, contentLeft, contentTop, contentWidth ->
            var y = contentTop

            val displayIp = formatDisplayIp(intel.ip, maskTargetIp)
            val subtitle = "$displayIp · ${intel.ipFamily.label}"
            val (pillBg, pillFg) = when (intel.originType) {
                IpOriginType.NATIVE -> palette.primaryContainer to palette.onPrimaryContainer
                IpOriginType.BROADCAST -> palette.amberContainer to palette.onAmberContainer
                IpOriginType.ANYCAST -> palette.secondaryContainer to palette.onSecondaryContainer
                IpOriginType.UNKNOWN -> palette.surfaceHigh to palette.onSurfaceVariant
            }

            drawSectionHeader(
                canvas = canvas,
                left = contentLeft,
                top = y,
                width = contentWidth,
                title = "IP 归属与原生/广播鉴定",
                subtitle = subtitle,
                iconType = HeaderIconType.GLOBE,
                pillText = intel.originType.label,
                pillBg = pillBg,
                pillFg = pillFg,
                palette = palette,
            )
            y += 44f

            // Evidence box
            val boxHeight = 32f
            val boxRect = RectF(contentLeft, y, contentLeft + contentWidth, y + boxHeight)
            drawFilledStrokedRoundRect(
                canvas = canvas,
                rect = boxRect,
                radius = 10f,
                fillColor = palette.surfaceLow,
                strokeColor = palette.outlineSubtle,
                strokeWidth = 0.8f,
            )

            val checkCx = contentLeft + 16f
            val checkCy = y + boxHeight / 2f
            drawCheckCircleIcon(canvas, checkCx, checkCy, 7.2f, palette.primary, palette.cardSurface)

            val safeEvidence = scrubTargetIpInText(
                raw = intel.originEvidence.ifBlank { intel.originType.label },
                report = report,
                maskTargetIp = maskTargetIp,
            )
            drawAutoFitText(
                canvas = canvas,
                text = safeEvidence,
                x = contentLeft + 30f,
                centerY = checkCy,
                maxWidth = contentWidth - 40f,
                preferredSp = 11.8f,
                minSp = 9.8f,
                color = palette.onSurface,
                bold = true,
            )
            y += boxHeight + 10f

            // Pills row (Location + ASN + Usage Type)
            val locationText = IpClassificationEngine.formatLocationSummary(
                countryCode = intel.countryCode,
                countryName = intel.countryName,
                regionName = intel.regionName,
                cityName = intel.cityName,
            )
            val pills = buildList {
                if (locationText.isNotBlank()) add(locationText to palette.onSurface)
                intel.asn?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it to palette.onSurfaceVariant) }
                if (intel.usageType != IpUsageType.UNKNOWN) {
                    add(intel.usageType.label to palette.onSurfaceVariant)
                }
            }
            var pillX = contentLeft
            val pillHeight = 23f
            for ((label, fgColor) in pills) {
                val measured = measurePillWidth(label, textSp = 10.6f, padH = 9.5f)
                if (pillX + measured > contentLeft + contentWidth) break
                drawTagPill(
                    canvas = canvas,
                    left = pillX,
                    top = y,
                    width = measured,
                    height = pillHeight,
                    text = label,
                    textSp = 10.6f,
                    bgColor = palette.surfaceLow,
                    fgColor = fgColor,
                    borderColor = palette.outlineSubtle,
                )
                pillX += measured + 7f
            }
            y += pillHeight + 8f

            // ISP/Org + Geo/WHOIS/RIR metadata line
            if (hasMetaLine) {
                val rightReserve = if (sourceSummary.isNotEmpty() && !orgLabel.isNullOrBlank()) 208f else 0f
                if (!orgLabel.isNullOrBlank()) {
                    val maxOrgW = (contentWidth - rightReserve - 8f).coerceAtLeast(150f)
                    drawEllipsizedText(
                        canvas = canvas,
                        text = "运营商 / 机构：$orgLabel",
                        x = contentLeft,
                        centerY = y + 7f,
                        maxWidth = maxOrgW,
                        textSp = 10.8f,
                        color = palette.onSurfaceVariant,
                        bold = false,
                    )
                }
                if (sourceSummary.isNotEmpty()) {
                    val alignRight = !orgLabel.isNullOrBlank()
                    if (alignRight) {
                        drawRightAlignedText(
                            canvas = canvas,
                            text = sourceSummary,
                            rightX = contentLeft + contentWidth,
                            centerY = y + 7f,
                            maxWidth = 206f,
                            textSp = 9.5f,
                            color = palette.onSurfaceMuted,
                        )
                    } else {
                        drawEllipsizedText(
                            canvas = canvas,
                            text = sourceSummary,
                            x = contentLeft,
                            centerY = y + 7f,
                            maxWidth = contentWidth,
                            textSp = 9.8f,
                            color = palette.onSurfaceMuted,
                            bold = false,
                        )
                    }
                }
                y += 18f
            }

            // Local client egress privacy strip (NEVER includes raw/masked IP)
            if (!clientFooter.isNullOrBlank()) {
                y += 4f
                drawHairlineDivider(canvas, contentLeft, y, contentWidth, palette)
                y += 13f
                drawShieldDotIcon(canvas, contentLeft + 6.5f, y, 6.2f, palette.primary)
                drawEllipsizedText(
                    canvas = canvas,
                    text = clientFooter,
                    x = contentLeft + 18f,
                    centerY = y,
                    maxWidth = contentWidth - 18f,
                    textSp = 10.2f,
                    color = palette.onSurfaceVariant,
                    bold = false,
                )
            }
        }
    }

    // ── Card 2: ICMP Ping Latency & Waveform ─────────────────────────────────────────────────

    private fun renderIcmpPingBitmap(
        ping: IcmpPingSummary,
        palette: CardPalette,
    ): Bitmap? {
        val subtitle = buildList {
            add("ICMP")
            ping.boundInterfaceName?.takeIf { it.isNotBlank() }?.let { add("网卡 $it") }
            add("${ping.received}/${ping.sent} 成功")
            val ttl = ping.samples.firstOrNull { it.success && it.ttl != null }?.ttl
            if (ttl != null) add("TTL $ttl")
        }.joinToString(" · ")

        val points = ping.samples.map {
            LatencySamplePoint(seq = it.seq, success = it.success, rttMs = it.rttMs)
        }
        return renderLatencyCardBitmap(
            title = "Ping 延迟探测",
            subtitle = subtitle,
            iconType = HeaderIconType.WAVEFORM,
            sent = ping.sent,
            avgMs = ping.avgMs,
            minMs = ping.minMs,
            maxMs = ping.maxMs,
            jitterMdevMs = ping.jitterMdevMs,
            lossPercent = ping.lossPercent,
            samples = points,
            httpFooter = null,
            palette = palette,
        )
    }

    // ── Card 3: TCPing Latency & Waveform (+ Optional HTTP Probe) ────────────────────────────

    private fun renderTcpPingBitmap(
        tcp: TcpPingSummary,
        httpResult: HttpLatencyResult?,
        maskTargetIp: Boolean,
        palette: CardPalette,
    ): Bitmap? {
        val portText = if (maskTargetIp) "已隐藏端口" else "端口 ${tcp.port}"
        val subtitle = "$portText · ${tcp.received}/${tcp.sent} 成功"
        val points = tcp.samples.map {
            LatencySamplePoint(seq = it.seq, success = it.success, rttMs = it.rttMs)
        }
        return renderLatencyCardBitmap(
            title = "TCPing 延迟探测",
            subtitle = subtitle,
            iconType = HeaderIconType.NETWORK_CHECK,
            sent = tcp.sent,
            avgMs = tcp.avgMs,
            minMs = tcp.minMs,
            maxMs = tcp.maxMs,
            jitterMdevMs = tcp.jitterMdevMs,
            lossPercent = tcp.lossPercent,
            samples = points,
            httpFooter = httpResult,
            palette = palette,
        )
    }

    private fun renderLatencyCardBitmap(
        title: String,
        subtitle: String,
        iconType: HeaderIconType,
        sent: Int,
        avgMs: Double?,
        minMs: Double?,
        maxMs: Double?,
        jitterMdevMs: Double?,
        lossPercent: Double,
        samples: List<LatencySamplePoint>,
        httpFooter: HttpLatencyResult?,
        palette: CardPalette,
    ): Bitmap? {
        val hasSamples = samples.isNotEmpty()
        var logicalHeight = if (hasSamples) 224f else 128f
        if (httpFooter != null) logicalHeight += 28f

        val hasLoss = lossPercent > 0.0
        val pillText = if (hasLoss) {
            "丢包 ${NetBenchMarkdownFormatter.formatPercent(lossPercent)}"
        } else {
            "0% 丢包"
        }
        val pillBg = if (hasLoss) palette.errorContainer else palette.primaryContainer
        val pillFg = if (hasLoss) palette.onErrorContainer else palette.onPrimaryContainer

        return createCardBitmap(logicalHeight, palette) { canvas, contentLeft, contentTop, contentWidth ->
            var y = contentTop
            drawSectionHeader(
                canvas = canvas,
                left = contentLeft,
                top = y,
                width = contentWidth,
                title = title,
                subtitle = subtitle,
                iconType = iconType,
                pillText = pillText,
                pillBg = pillBg,
                pillFg = pillFg,
                palette = palette,
            )
            y += 44f

            // 4-Column Telemetry Strip (proportional widths prevent min/max truncation!)
            val stripH = 48f
            val stripRect = RectF(contentLeft, y, contentLeft + contentWidth, y + stripH)
            drawFilledRoundRect(canvas, stripRect, 12f, palette.surfaceLow)

            val avgText = when {
                avgMs != null -> NetBenchMarkdownFormatter.formatMs(avgMs)
                sent == 0 -> "不可用"
                else -> "超时"
            }
            val minMaxText = if (minMs != null && maxMs != null) {
                "${String.format(Locale.US, "%.0f", minMs)}/${String.format(Locale.US, "%.0f", maxMs)} ms"
            } else {
                "—"
            }
            val jitterText = jitterMdevMs?.let { NetBenchMarkdownFormatter.formatMs(it) } ?: "—"
            val lossText = NetBenchMarkdownFormatter.formatPercent(lossPercent)

            val weights = floatArrayOf(1.00f, 1.28f, 0.94f, 0.86f)
            val weightSum = weights.sum()
            val stats = listOf(
                Triple("平均延迟", avgText, if (avgMs == null) palette.error else palette.onSurface),
                Triple("最小/最大", minMaxText, palette.onSurface),
                Triple("抖动", jitterText, palette.onSurface),
                Triple("丢包率", lossText, if (hasLoss) palette.error else palette.onSurface),
            )
            var colX = contentLeft
            for (idx in stats.indices) {
                val colW = contentWidth * (weights[idx] / weightSum)
                val (label, value, valueColor) = stats[idx]
                drawAutoFitCenteredText(
                    canvas = canvas,
                    text = value,
                    centerX = colX + colW / 2f,
                    centerY = y + 17.5f,
                    maxWidth = colW - 10f,
                    preferredSp = 13.8f,
                    minSp = 10.5f,
                    color = valueColor,
                    bold = true,
                )
                drawCenteredText(
                    canvas = canvas,
                    text = label,
                    centerX = colX + colW / 2f,
                    centerY = y + 35.5f,
                    textSp = 10.2f,
                    color = palette.onSurfaceVariant,
                    bold = false,
                )
                if (idx < stats.lastIndex) {
                    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.outlineSubtle
                        strokeWidth = 0.75f
                    }
                    canvas.drawLine(colX + colW, y + 10f, colX + colW, y + stripH - 10f, dividerPaint)
                }
                colX += colW
            }
            y += stripH + 10f

            // RTT Waveform Chart
            if (hasSamples) {
                val maxSampleRtt = samples.asSequence()
                    .filter { it.success && it.rttMs != null }
                    .mapNotNull { it.rttMs }
                    .maxOrNull()
                val scalePeakMs = ((maxSampleRtt ?: 100.0) * 1.22).coerceAtLeast(20.0)

                drawEllipsizedText(
                    canvas = canvas,
                    text = "实时 RTT 波形 (${samples.size}/$sent)",
                    x = contentLeft,
                    centerY = y + 6f,
                    maxWidth = contentWidth * 0.55f,
                    textSp = 10.0f,
                    color = palette.onSurfaceVariant,
                    bold = false,
                )
                drawRightAlignedText(
                    canvas = canvas,
                    text = "峰值刻度 ${NetBenchMarkdownFormatter.formatMs(scalePeakMs)}",
                    rightX = contentLeft + contentWidth,
                    centerY = y + 6f,
                    maxWidth = contentWidth * 0.45f,
                    textSp = 10.0f,
                    color = palette.onSurfaceVariant,
                )
                y += 16f

                val chartH = 74f
                val chartRect = RectF(contentLeft, y, contentLeft + contentWidth, y + chartH)
                drawFilledRoundRect(canvas, chartRect, 10f, palette.surfaceLow)
                drawLatencyWaveformInside(
                    canvas = canvas,
                    box = chartRect,
                    samples = samples,
                    plannedCount = max(sent, samples.size),
                    avgMs = avgMs,
                    maxRtt = scalePeakMs,
                    palette = palette,
                )
                y += chartH
            }

            // Optional HTTP probe strip
            if (httpFooter != null) {
                y += 9f
                drawHairlineDivider(canvas, contentLeft, y, contentWidth, palette)
                y += 12.5f
                drawEllipsizedText(
                    canvas = canvas,
                    text = "HTTP 探测",
                    x = contentLeft,
                    centerY = y,
                    maxWidth = 100f,
                    textSp = 10.8f,
                    color = palette.onSurface,
                    bold = true,
                )
                if (httpFooter.success && httpFooter.totalMs != null) {
                    val info = buildList {
                        httpFooter.statusCode?.let { add("HTTP $it") }
                        add(NetBenchMarkdownFormatter.formatMs(httpFooter.totalMs))
                        httpFooter.tlsHandshakeMs?.takeIf { it > 0.0 }?.let {
                            add("TLS ${NetBenchMarkdownFormatter.formatMs(it)}")
                        }
                    }.joinToString(" · ")
                    drawRightAlignedText(
                        canvas = canvas,
                        text = info,
                        rightX = contentLeft + contentWidth,
                        centerY = y,
                        maxWidth = contentWidth - 108f,
                        textSp = 10.8f,
                        color = palette.primary,
                        bold = true,
                    )
                } else {
                    drawRightAlignedText(
                        canvas = canvas,
                        text = httpFooter.errorMessage ?: "探测失败",
                        rightX = contentLeft + contentWidth,
                        centerY = y,
                        maxWidth = contentWidth - 108f,
                        textSp = 10.4f,
                        color = palette.error,
                    )
                }
            }
        }
    }

    // ── Card 4: Native Pure-Kotlin iperf3 Throughput & Interval Breakdown ────────────────────

    private fun renderIperf3Bitmap(
        results: List<Iperf3Result>,
        maskTargetIp: Boolean,
        palette: CardPalette,
    ): Bitmap? {
        val primaryPort = results.first().port
        val maxStreams = results.maxOfOrNull { it.parallelStreams } ?: 1
        val portText = if (maskTargetIp) "已隐藏端口" else "端口 $primaryPort"
        val streamSuffix = if (maxStreams > 1) " · -P $maxStreams 并行流" else ""
        val subtitle = "纯 Kotlin TCP 协议 · $portText$streamSuffix"
        val isDual = results.size >= 2

        if (!isDual) {
            val res = results.first()
            val intervals = selectDisplayIntervals(res, maxRows = 6)
            val logicalHeight = 216f
            return createCardBitmap(logicalHeight, palette) { canvas, contentLeft, contentTop, contentWidth ->
                var y = contentTop
                drawSectionHeader(
                    canvas = canvas,
                    left = contentLeft,
                    top = y,
                    width = contentWidth,
                    title = "原生 iperf3 吞吐测速",
                    subtitle = subtitle,
                    iconType = HeaderIconType.SPEED,
                    pillText = "测速完成",
                    pillBg = palette.primaryContainer,
                    pillFg = palette.onPrimaryContainer,
                    palette = palette,
                )
                y += 46f

                val leftW = 180f
                val gap = 12f
                val rightX = contentLeft + leftW + gap
                val rightW = contentWidth - leftW - gap
                val panelH = 138f

                // Left: Direction label + Arc Gauge + Transfer/Peak
                drawSingleIperfGaugeColumn(
                    canvas = canvas,
                    left = contentLeft,
                    top = y,
                    width = leftW,
                    height = panelH,
                    result = res,
                    gaugeDiameter = 98f,
                    palette = palette,
                )

                // Right: Per-second interval breakdown panel
                val panelRect = RectF(rightX, y, rightX + rightW, y + panelH)
                drawFilledRoundRect(canvas, panelRect, 11f, palette.surfaceLow)
                drawIntervalBreakdownInside(
                    canvas = canvas,
                    rect = panelRect,
                    result = res,
                    displayedIntervals = intervals,
                    compactColumns = false,
                    palette = palette,
                )
            }
        } else {
            val logicalHeight = 266f
            return createCardBitmap(logicalHeight, palette) { canvas, contentLeft, contentTop, contentWidth ->
                var y = contentTop
                drawSectionHeader(
                    canvas = canvas,
                    left = contentLeft,
                    top = y,
                    width = contentWidth,
                    title = "原生 iperf3 吞吐测速",
                    subtitle = subtitle,
                    iconType = HeaderIconType.SPEED,
                    pillText = "双向测速完成",
                    pillBg = palette.primaryContainer,
                    pillFg = palette.onPrimaryContainer,
                    palette = palette,
                )
                y += 46f

                val gap = 14f
                val colW = (contentWidth - gap) / 2f
                results.take(2).forEachIndexed { idx, res ->
                    val colLeft = contentLeft + idx * (colW + gap)
                    drawSingleIperfGaugeColumn(
                        canvas = canvas,
                        left = colLeft,
                        top = y,
                        width = colW,
                        height = 112f,
                        result = res,
                        gaugeDiameter = 84f,
                        palette = palette,
                    )
                    val ivTop = y + 118f
                    val ivH = 70f
                    val ivRect = RectF(colLeft, ivTop, colLeft + colW, ivTop + ivH)
                    drawFilledRoundRect(canvas, ivRect, 10f, palette.surfaceLow)
                    drawIntervalBreakdownInside(
                        canvas = canvas,
                        rect = ivRect,
                        result = res,
                        displayedIntervals = selectDisplayIntervals(res, maxRows = 3),
                        compactColumns = true,
                        palette = palette,
                    )
                }

                // Subtle center vertical divider
                val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.outlineSubtle
                    strokeWidth = 0.8f
                }
                val midX = contentLeft + colW + gap / 2f
                canvas.drawLine(midX, y + 6f, midX, y + 184f, divPaint)
            }
        }
    }

    // ── Shared Sub-Component Renderers ───────────────────────────────────────────────────────

    private fun createCardBitmap(
        logicalHeightDp: Float,
        palette: CardPalette,
        drawContent: (canvas: Canvas, contentLeft: Float, contentTop: Float, contentWidth: Float) -> Unit,
    ): Bitmap? {
        val widthPx = (LOGICAL_WIDTH_DP * DENSITY_SCALE).roundToInt()
        val heightPx = (logicalHeightDp * DENSITY_SCALE).roundToInt()
        if (widthPx <= 0 || heightPx <= 0) return null

        val bitmap = runCatching {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.save()
        canvas.scale(DENSITY_SCALE, DENSITY_SCALE)

        val cardRect = RectF(
            OUTER_INSET_DP,
            OUTER_INSET_DP,
            LOGICAL_WIDTH_DP - OUTER_INSET_DP,
            logicalHeightDp - OUTER_INSET_DP,
        )
        drawFilledStrokedRoundRect(
            canvas = canvas,
            rect = cardRect,
            radius = CARD_CORNER_DP,
            fillColor = palette.cardSurface,
            strokeColor = palette.cardBorder,
            strokeWidth = 1.1f,
        )

        val contentLeft = OUTER_INSET_DP + CARD_PAD_H_DP
        val contentTop = OUTER_INSET_DP + CARD_PAD_V_DP
        val contentWidth = LOGICAL_WIDTH_DP - (OUTER_INSET_DP + CARD_PAD_H_DP) * 2f

        drawContent(canvas, contentLeft, contentTop, contentWidth)
        canvas.restore()
        return bitmap
    }

    private enum class HeaderIconType {
        WAVEFORM,
        NETWORK_CHECK,
        SPEED,
        GLOBE,
    }

    private fun drawSectionHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        title: String,
        subtitle: String,
        iconType: HeaderIconType,
        pillText: String,
        pillBg: Int,
        pillFg: Int,
        palette: CardPalette,
    ) {
        val tileSize = 34f
        val tileRect = RectF(left, top, left + tileSize, top + tileSize)
        drawFilledStrokedRoundRect(
            canvas = canvas,
            rect = tileRect,
            radius = 10f,
            fillColor = palette.iconTileBg,
            strokeColor = palette.iconTileBorder,
            strokeWidth = 0.8f,
        )
        drawHeaderVectorIcon(
            canvas = canvas,
            cx = tileRect.centerX(),
            cy = tileRect.centerY(),
            type = iconType,
            color = palette.primary,
        )

        val pillW = measurePillWidth(pillText, textSp = 10.6f, padH = 10f)
        val pillH = 23f
        val pillLeft = left + width - pillW
        val pillTop = top + (tileSize - pillH) / 2f
        drawTagPill(
            canvas = canvas,
            left = pillLeft,
            top = pillTop,
            width = pillW,
            height = pillH,
            text = pillText,
            textSp = 10.6f,
            bgColor = pillBg,
            fgColor = pillFg,
            borderColor = Color.TRANSPARENT,
            bold = true,
            cornerRadius = pillH / 2f,
        )

        val textLeft = left + tileSize + 10f
        val textMaxW = (pillLeft - textLeft - 10f).coerceAtLeast(100f)
        drawEllipsizedText(
            canvas = canvas,
            text = title,
            x = textLeft,
            centerY = top + 10.5f,
            maxWidth = textMaxW,
            textSp = 14.5f,
            color = palette.onSurface,
            bold = true,
        )
        drawEllipsizedText(
            canvas = canvas,
            text = subtitle,
            x = textLeft,
            centerY = top + 26.5f,
            maxWidth = textMaxW,
            textSp = 11.0f,
            color = palette.onSurfaceVariant,
            bold = false,
        )
    }

    private fun drawLatencyWaveformInside(
        canvas: Canvas,
        box: RectF,
        samples: List<LatencySamplePoint>,
        plannedCount: Int,
        avgMs: Double?,
        maxRtt: Double,
        palette: CardPalette,
    ) {
        val padH = 10f
        val padV = 9f
        val left = box.left + padH
        val top = box.top + padV
        val w = (box.width() - padH * 2f).coerceAtLeast(10f)
        val h = (box.height() - padV * 2f).coerceAtLeast(10f)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.gridLine
            strokeWidth = 0.85f
        }
        for (step in 1..3) {
            val gy = top + h * (step / 4f)
            canvas.drawLine(left, gy, left + w, gy, gridPaint)
        }

        if (avgMs != null && avgMs > 0.0) {
            val avgNorm = (avgMs / maxRtt).toFloat().coerceIn(0f, 1f)
            val avgY = top + h * (1f - avgNorm)
            val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.avgDashLine
                strokeWidth = 1.15f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
            }
            canvas.drawLine(left, avgY, left + w, avgY, dashPaint)
        }

        val slots = maxOf(plannedCount, samples.size, 4)
        val slotW = w / slots.toFloat()
        val barW = (slotW * 0.56f).coerceIn(3.5f, 18f)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                top,
                0f,
                top + h,
                withAlpha(palette.primary, 0.78f),
                withAlpha(palette.primary, 0.20f),
                Shader.TileMode.CLAMP,
            )
        }
        val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(palette.error, 0.72f)
        }

        val sparkPath = Path()
        var hasSparkStart = false
        var successDots = 0
        val dotCenters = ArrayList<Pair<Float, Float>>(samples.size)

        for (index in samples.indices) {
            val sample = samples[index]
            val cx = left + slotW * index + slotW / 2f
            val bx = cx - barW / 2f
            if (sample.success && sample.rttMs != null) {
                val norm = (sample.rttMs / maxRtt).toFloat().coerceIn(0.05f, 1f)
                val barH = h * norm
                val by = top + h - barH
                canvas.drawRoundRect(RectF(bx, by, bx + barW, top + h), 4f, 4f, barPaint)
                dotCenters += cx to by
                successDots++
                if (!hasSparkStart) {
                    sparkPath.moveTo(cx, by)
                    hasSparkStart = true
                } else {
                    sparkPath.lineTo(cx, by)
                }
            } else {
                val failH = h * 0.22f
                val fy = top + h - failH
                canvas.drawRoundRect(RectF(bx, fy, bx + barW, top + h), 3f, 3f, failPaint)
                hasSparkStart = false
            }
        }

        if (successDots >= 2) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(palette.primary, 0.86f)
                style = Paint.Style.STROKE
                strokeWidth = 1.85f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(sparkPath, linePaint)
        }

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primary
            style = Paint.Style.FILL
        }
        for ((dx, dy) in dotCenters) {
            canvas.drawCircle(dx, dy, 2.6f, dotPaint)
        }
    }

    private fun drawSingleIperfGaugeColumn(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        result: Iperf3Result,
        gaugeDiameter: Float,
        palette: CardPalette,
    ) {
        val isDownload = result.direction == Iperf3Direction.DOWNLOAD
        val dirArrow = if (isDownload) "↓ " else "↑ "
        drawEllipsizedText(
            canvas = canvas,
            text = "$dirArrow${result.direction.flagLabel}",
            x = left,
            centerY = top + 7f,
            maxWidth = width,
            textSp = 11.4f,
            color = palette.onSurface,
            bold = true,
        )

        val mbps = result.effectiveMbps
        val scaleMax = when {
            mbps <= 100.0 -> 100.0
            mbps <= 300.0 -> 300.0
            mbps <= 500.0 -> 500.0
            mbps <= 1000.0 -> 1000.0
            mbps <= 2500.0 -> 2500.0
            else -> 10_000.0
        }
        val fraction = (mbps / scaleMax).toFloat().coerceIn(0f, 1f)

        val strokeW = 9.0f
        val arcLeft = left + (width - gaugeDiameter) / 2f
        val arcTop = top + 20f
        val arcRect = RectF(arcLeft, arcTop, arcLeft + gaugeDiameter, arcTop + gaugeDiameter)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.surfaceHighest
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(arcRect, 160f, 220f, false, trackPaint)

        if (fraction > 0.005f) {
            val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                shader = LinearGradient(
                    arcRect.left,
                    arcRect.bottom,
                    arcRect.right,
                    arcRect.top,
                    palette.primary,
                    palette.secondary,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawArc(arcRect, 160f, 220f * fraction, false, activePaint)
        }

        val (valStr, unitStr) = if (mbps >= 1000.0) {
            String.format(Locale.US, "%.2f", mbps / 1000.0) to "Gbps"
        } else {
            String.format(Locale.US, "%.1f", mbps) to "Mbps"
        }
        val gaugeCenterX = arcRect.centerX()
        val gaugeCenterY = arcRect.centerY()
        drawAutoFitCenteredText(
            canvas = canvas,
            text = valStr,
            centerX = gaugeCenterX,
            centerY = gaugeCenterY - 2f,
            maxWidth = gaugeDiameter - 20f,
            preferredSp = if (gaugeDiameter >= 94f) 19.5f else 17.0f,
            minSp = 13.0f,
            color = palette.onSurface,
            bold = true,
        )
        drawCenteredText(
            canvas = canvas,
            text = "$unitStr · 平均",
            centerX = gaugeCenterX,
            centerY = gaugeCenterY + 14.5f,
            textSp = 9.8f,
            color = palette.onSurfaceVariant,
            bold = false,
        )

        val footerY = top + height - 6f
        drawEllipsizedText(
            canvas = canvas,
            text = "已传输 ${NetBenchMarkdownFormatter.formatBytes(result.bytesTransferred)}",
            x = left,
            centerY = footerY,
            maxWidth = width * 0.52f,
            textSp = 9.5f,
            color = palette.onSurfaceVariant,
            bold = false,
        )
        drawRightAlignedText(
            canvas = canvas,
            text = "峰值 ${NetBenchMarkdownFormatter.formatMbps(result.maxIntervalMbps)}",
            rightX = left + width,
            centerY = footerY,
            maxWidth = width * 0.48f,
            textSp = 9.5f,
            color = palette.onSurfaceVariant,
        )
    }

    private fun drawIntervalBreakdownInside(
        canvas: Canvas,
        rect: RectF,
        result: Iperf3Result,
        displayedIntervals: List<Iperf3IntervalSample>,
        compactColumns: Boolean,
        palette: CardPalette,
    ) {
        val padH = 10f
        val padV = 8f
        val left = rect.left + padH
        val width = rect.width() - padH * 2f
        var y = rect.top + padV

        val totalIntervals = result.intervals.size.coerceAtLeast(displayedIntervals.size)
        val headerTitle = if (compactColumns) {
            "逐秒明细 (${totalIntervals}段)"
        } else {
            "${result.direction.flagLabel} · 逐秒测速明细 (${totalIntervals}段)"
        }
        val meta = buildList {
            result.stabilityPercent?.let { add("稳定度 ${NetBenchMarkdownFormatter.formatPercent(it)}") }
            result.retransmits?.takeIf { it >= 0 }?.let { add("重传 $it") }
        }.joinToString(" · ")

        val titleShare = if (compactColumns) 0.44f else 0.54f
        drawEllipsizedText(
            canvas = canvas,
            text = headerTitle,
            x = left,
            centerY = y + 6f,
            maxWidth = if (meta.isNotEmpty()) width * titleShare else width,
            textSp = if (compactColumns) 9.5f else 10.2f,
            color = palette.onSurface,
            bold = true,
        )
        if (meta.isNotEmpty()) {
            drawRightAlignedText(
                canvas = canvas,
                text = meta,
                rightX = left + width,
                centerY = y + 6f,
                maxWidth = width * (1f - titleShare),
                textSp = 9.1f,
                color = palette.onSurfaceVariant,
            )
        }
        y += 18f

        val maxMbps = maxOf(
            result.intervals.maxOfOrNull { it.bitrateMbps } ?: 0.0,
            displayedIntervals.maxOfOrNull { it.bitrateMbps } ?: 0.0,
            result.maxIntervalMbps,
            1.0,
        )
        val availH = (rect.bottom - padV - y).coerceAtLeast(18f)
        val rowCount = displayedIntervals.size.coerceAtLeast(1)
        val rowStep = (availH / rowCount).coerceIn(13.5f, 18.5f)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.surfaceHighest
            style = Paint.Style.FILL
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.primary, 0.84f)
            style = Paint.Style.FILL
        }

        for (sample in displayedIntervals) {
            val cy = y + rowStep / 2f
            val timeLabel = String.format(Locale.US, "%.0f-%.0fs", sample.startSec, sample.endSec)
            val timeColW = if (compactColumns) 34f else 40f
            val rateText = NetBenchMarkdownFormatter.formatMbps(sample.bitrateMbps)
            val rateColW = if (compactColumns) 62f else 68f
            val bytesColW = if (compactColumns) 0f else 52f

            drawEllipsizedText(
                canvas = canvas,
                text = timeLabel,
                x = left,
                centerY = cy,
                maxWidth = timeColW,
                textSp = 9.2f,
                color = palette.onSurfaceVariant,
                bold = false,
            )

            val barLeft = left + timeColW + 4f
            val barRight = left + width - rateColW - bytesColW - 6f
            val barW = (barRight - barLeft).coerceAtLeast(24f)
            val barH = 5.6f
            val trackRect = RectF(barLeft, cy - barH / 2f, barLeft + barW, cy + barH / 2f)
            canvas.drawRoundRect(trackRect, barH / 2f, barH / 2f, trackPaint)

            val ratio = (sample.bitrateMbps / maxMbps).toFloat().coerceIn(0.05f, 1f)
            val activeRect = RectF(barLeft, cy - barH / 2f, barLeft + barW * ratio, cy + barH / 2f)
            canvas.drawRoundRect(activeRect, barH / 2f, barH / 2f, fillPaint)

            if (!compactColumns) {
                drawRightAlignedText(
                    canvas = canvas,
                    text = NetBenchMarkdownFormatter.formatBytes(sample.bytesTransferred),
                    rightX = left + width - rateColW - 4f,
                    centerY = cy,
                    maxWidth = bytesColW,
                    textSp = 9.0f,
                    color = palette.onSurfaceVariant,
                )
            }

            drawRightAlignedText(
                canvas = canvas,
                text = rateText,
                rightX = left + width,
                centerY = cy,
                maxWidth = rateColW,
                textSp = 9.5f,
                color = palette.onSurface,
                bold = true,
            )

            y += rowStep
        }
    }

    private fun selectDisplayIntervals(
        result: Iperf3Result,
        maxRows: Int,
    ): List<Iperf3IntervalSample> {
        val raw = result.intervals
        if (raw.isEmpty()) {
            return listOf(
                Iperf3IntervalSample(
                    index = 1,
                    startSec = 0.0,
                    endSec = result.durationSec.coerceAtLeast(1.0),
                    bytesTransferred = result.bytesTransferred,
                    bitrateMbps = result.effectiveMbps,
                    streams = result.parallelStreams,
                ),
            )
        }
        if (raw.size <= maxRows) return raw
        return List(maxRows) { slot ->
            val idx = ((slot.toDouble() * (raw.size - 1).toDouble()) / (maxRows - 1).coerceAtLeast(1).toDouble())
                .roundToInt()
                .coerceIn(0, raw.lastIndex)
            raw[idx]
        }
    }

    // ── Vector Icon Primitives ───────────────────────────────────────────────────────────────

    private fun drawHeaderVectorIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        type: HeaderIconType,
        color: Int,
    ) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.75f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (type) {
            HeaderIconType.WAVEFORM -> {
                val heights = floatArrayOf(5f, 10f, 14f, 8.5f, 4.5f)
                val spacing = 3.3f
                val startX = cx - spacing * 2f
                for (i in heights.indices) {
                    val x = startX + i * spacing
                    val half = heights[i] / 2f
                    canvas.drawLine(x, cy - half, x, cy + half, stroke)
                }
            }
            HeaderIconType.NETWORK_CHECK -> {
                val arc = RectF(cx - 7.5f, cy - 6.5f, cx + 7.5f, cy + 8.5f)
                canvas.drawArc(arc, 195f, 150f, false, stroke)
                canvas.drawLine(cx, cy + 2.5f, cx + 4.4f, cy - 3.2f, stroke)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy + 2.8f, 1.6f, fill)
            }
            HeaderIconType.SPEED -> {
                val arc = RectF(cx - 7.5f, cy - 7.0f, cx + 7.5f, cy + 8.0f)
                canvas.drawArc(arc, 155f, 230f, false, stroke)
                val rad = Math.toRadians(-35.0)
                canvas.drawLine(
                    cx,
                    cy + 1.5f,
                    cx + (cos(rad) * 5.2).toFloat(),
                    cy + 1.5f + (sin(rad) * 5.2).toFloat(),
                    stroke,
                )
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy + 1.5f, 1.6f, fill)
            }
            HeaderIconType.GLOBE -> {
                val r = 7.2f
                canvas.drawCircle(cx, cy, r, stroke)
                val meridian = RectF(cx - 3.6f, cy - r, cx + 3.6f, cy + r)
                canvas.drawOval(meridian, stroke)
                canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
            }
        }
    }

    private fun drawCheckCircleIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        circleColor: Int,
        checkColor: Int,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = circleColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, fill)
        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = checkColor
            style = Paint.Style.STROKE
            strokeWidth = 1.65f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(cx - radius * 0.42f, cy + radius * 0.02f)
            lineTo(cx - radius * 0.10f, cy + radius * 0.34f)
            lineTo(cx + radius * 0.46f, cy - radius * 0.28f)
        }
        canvas.drawPath(path, check)
    }

    private fun drawShieldDotIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
    ) {
        val path = Path().apply {
            moveTo(cx, cy - size)
            lineTo(cx + size * 0.82f, cy - size * 0.55f)
            lineTo(cx + size * 0.72f, cy + size * 0.22f)
            quadTo(cx, cy + size * 0.98f, cx, cy + size)
            quadTo(cx, cy + size * 0.98f, cx - size * 0.72f, cy + size * 0.22f)
            lineTo(cx - size * 0.82f, cy - size * 0.55f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, fill)
    }

    // ── Canvas Shape & Typography Helpers ────────────────────────────────────────────────────

    private fun drawFilledRoundRect(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        fillColor: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun drawFilledStrokedRoundRect(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        fillColor: Int,
        strokeColor: Int,
        strokeWidth: Float,
    ) {
        drawFilledRoundRect(canvas, rect, radius, fillColor)
        if (Color.alpha(strokeColor) > 0 && strokeWidth > 0f) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = strokeColor
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
            }
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    private fun drawHairlineDivider(
        canvas: Canvas,
        left: Float,
        y: Float,
        width: Float,
        palette: CardPalette,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 0.85f
            shader = LinearGradient(
                left,
                y,
                left + width,
                y,
                intArrayOf(
                    Color.TRANSPARENT,
                    palette.outlineSubtle,
                    palette.outlineSubtle,
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.12f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawLine(left, y, left + width, y, paint)
    }

    private fun measurePillWidth(text: String, textSp: Float, padH: Float): Float {
        val p = textPaint(textSp, Color.BLACK, bold = true)
        return p.measureText(text) + padH * 2f
    }

    private fun drawTagPill(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        text: String,
        textSp: Float,
        bgColor: Int,
        fgColor: Int,
        borderColor: Int,
        bold: Boolean = true,
        cornerRadius: Float = 8f,
    ) {
        val rect = RectF(left, top, left + width, top + height)
        drawFilledStrokedRoundRect(
            canvas = canvas,
            rect = rect,
            radius = cornerRadius,
            fillColor = bgColor,
            strokeColor = borderColor,
            strokeWidth = 0.75f,
        )
        drawCenteredText(
            canvas = canvas,
            text = text,
            centerX = rect.centerX(),
            centerY = rect.centerY(),
            textSp = textSp,
            color = fgColor,
            bold = bold,
        )
    }

    private fun textPaint(textSp: Float, color: Int, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.textSize = textSp
            this.color = color
            this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.fontFeatureSettings = "tnum"
        }

    private fun baselineForCenterY(paint: Paint, centerY: Float): Float {
        val fm = paint.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textSp: Float,
        color: Int,
        bold: Boolean,
    ) {
        val paint = textPaint(textSp, color, bold).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, centerX, baselineForCenterY(paint, centerY), paint)
    }

    private fun drawAutoFitCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        preferredSp: Float,
        minSp: Float,
        color: Int,
        bold: Boolean,
    ) {
        var sp = preferredSp
        var paint = textPaint(sp, color, bold)
        while (sp > minSp && paint.measureText(text) > maxWidth) {
            sp = (sp - 0.5f).coerceAtLeast(minSp)
            paint = textPaint(sp, color, bold)
        }
        val fitted = ellipsizeToWidth(text, paint, maxWidth)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(fitted, centerX, baselineForCenterY(paint, centerY), paint)
    }

    private fun drawAutoFitText(
        canvas: Canvas,
        text: String,
        x: Float,
        centerY: Float,
        maxWidth: Float,
        preferredSp: Float,
        minSp: Float,
        color: Int,
        bold: Boolean,
    ) {
        var sp = preferredSp
        var paint = textPaint(sp, color, bold)
        while (sp > minSp && paint.measureText(text) > maxWidth) {
            sp = (sp - 0.5f).coerceAtLeast(minSp)
            paint = textPaint(sp, color, bold)
        }
        val fitted = ellipsizeToWidth(text, paint, maxWidth)
        canvas.drawText(fitted, x, baselineForCenterY(paint, centerY), paint)
    }

    private fun drawEllipsizedText(
        canvas: Canvas,
        text: String,
        x: Float,
        centerY: Float,
        maxWidth: Float,
        textSp: Float,
        color: Int,
        bold: Boolean,
    ) {
        val paint = textPaint(textSp, color, bold)
        val fitted = ellipsizeToWidth(text, paint, maxWidth)
        canvas.drawText(fitted, x, baselineForCenterY(paint, centerY), paint)
    }

    private fun drawRightAlignedText(
        canvas: Canvas,
        text: String,
        rightX: Float,
        centerY: Float,
        maxWidth: Float,
        textSp: Float,
        color: Int,
        bold: Boolean = false,
    ) {
        val paint = textPaint(textSp, color, bold)
        val fitted = ellipsizeToWidth(text, paint, maxWidth)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(fitted, rightX, baselineForCenterY(paint, centerY), paint)
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || text.isEmpty()) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val budget = (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(0f)
        val count = paint.breakText(text, true, budget, null)
        return if (count <= 0) ellipsis else text.substring(0, count).trimEnd() + ellipsis
    }

    // ── Privacy & Digest Helpers ─────────────────────────────────────────────────────────────

    internal fun formatDisplayIp(rawIp: String, maskTargetIp: Boolean): String {
        val trimmed = rawIp.trim()
        return if (maskTargetIp) NetBenchTargetParser.maskTargetIpLastSegment(trimmed) else trimmed
    }

    internal fun scrubTargetIpInText(
        raw: String,
        report: NetBenchReport,
        maskTargetIp: Boolean,
    ): String {
        if (!maskTargetIp) return raw
        var out = raw
        val ips = buildSet {
            report.resolvedTarget?.resolvedIp?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            report.ipIntelligence?.ip?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        for (ip in ips) {
            val masked = NetBenchTargetParser.maskTargetIpLastSegment(ip)
            if (masked != ip && out.contains(ip, ignoreCase = true)) {
                out = out.replace(ip, masked, ignoreCase = true)
            }
        }
        return out
    }

    /**
     * Builds the client network footer strip WITHOUT ever exposing local raw or masked IP addresses.
     */
    internal fun buildSafeClientFooterText(report: NetBenchReport): String? {
        val client = report.clientNetwork ?: return null
        val carrier = client.carrierDisplayName.trim().ifEmpty { "本机网络" }
        val family = client.ipFamily.takeIf { it != IpFamily.UNKNOWN }?.label ?: "IPv4"
        val region = client.regionName?.trim()?.takeIf {
            it.isNotEmpty() && !carrier.contains(it, ignoreCase = true)
        }
        val prefix = when {
            report.testedViaProxy -> "经由代理出口"
            client.routeKind == EgressRouteKind.PROXY_FALLBACK -> "代理出口(直连受限)"
            else -> "本机出口"
        }
        val parts = listOfNotNull(
            "$prefix：$carrier",
            region,
            family,
            "生成报告已自动隐藏本机 IP",
        )
        return parts.joinToString(" · ")
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(24)
        for (i in 0 until 12) {
            sb.append(String.format(Locale.US, "%02x", digest[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun withAlpha(argb: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    private data class CardPalette(
        val cardSurface: Int,
        val cardBorder: Int,
        val primary: Int,
        val secondary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val amberContainer: Int,
        val onAmberContainer: Int,
        val error: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
        val surfaceLow: Int,
        val surfaceHigh: Int,
        val surfaceHighest: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val onSurfaceMuted: Int,
        val outlineSubtle: Int,
        val iconTileBg: Int,
        val iconTileBorder: Int,
        val gridLine: Int,
        val avgDashLine: Int,
    ) {
        companion object {
            val Light = CardPalette(
                cardSurface = 0xFFFFFFFF.toInt(),
                cardBorder = 0xC8C0C7CD.toInt(),
                primary = 0xFF2D637E.toInt(),
                secondary = 0xFF4E616D.toInt(),
                primaryContainer = 0xFFCDE7FB.toInt(),
                onPrimaryContainer = 0xFF001E2E.toInt(),
                secondaryContainer = 0xFFD1E5F3.toInt(),
                onSecondaryContainer = 0xFF0A1E28.toInt(),
                amberContainer = 0xFFFFF4DC.toInt(),
                onAmberContainer = 0xFF9A5200.toInt(),
                error = 0xFFBA1A1A.toInt(),
                errorContainer = 0xFFFFDAD6.toInt(),
                onErrorContainer = 0xFF410002.toInt(),
                surfaceLow = 0xFFF1F4F9.toInt(),
                surfaceHigh = 0xFFE5E8EE.toInt(),
                surfaceHighest = 0xFFDFE3E8.toInt(),
                onSurface = 0xFF171C1F.toInt(),
                onSurfaceVariant = 0xFF40484D.toInt(),
                onSurfaceMuted = 0xFF6A7379.toInt(),
                outlineSubtle = 0x8CC0C7CD.toInt(),
                iconTileBg = 0xFFEAF3FA.toInt(),
                iconTileBorder = 0x452D637E,
                gridLine = 0x72C0C7CD,
                avgDashLine = 0xCC70787E.toInt(),
            )

            val Dark = CardPalette(
                cardSurface = 0xFF1C2024.toInt(),
                cardBorder = 0xCC40484D.toInt(),
                primary = 0xFF9ACAEC.toInt(),
                secondary = 0xFFB5C9D7.toInt(),
                primaryContainer = 0xFF114B66.toInt(),
                onPrimaryContainer = 0xFFCDE7FB.toInt(),
                secondaryContainer = 0xFF364955.toInt(),
                onSecondaryContainer = 0xFFD1E5F3.toInt(),
                amberContainer = 0xFF2B200E.toInt(),
                onAmberContainer = 0xFFFBBF24.toInt(),
                error = 0xFFFFB4AB.toInt(),
                errorContainer = 0xFF93000A.toInt(),
                onErrorContainer = 0xFFFFDAD6.toInt(),
                surfaceLow = 0xFF14181B.toInt(),
                surfaceHigh = 0xFF262A2E.toInt(),
                surfaceHighest = 0xFF313539.toInt(),
                onSurface = 0xFFDFE3E7.toInt(),
                onSurfaceVariant = 0xFFC0C7CD.toInt(),
                onSurfaceMuted = 0xFF8A9297.toInt(),
                outlineSubtle = 0x8C40484D.toInt(),
                iconTileBg = 0xFF182A35.toInt(),
                iconTileBorder = 0x559ACAEC,
                gridLine = 0x7240484D,
                avgDashLine = 0xCC8A9297.toInt(),
            )
        }
    }
}
