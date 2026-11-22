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
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3IntervalSample
import com.nodeseek.app.domain.netbench.NetBenchErrorCategory
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.NetBenchReportCardKind
import com.nodeseek.app.domain.netbench.NetBenchReportSanitizer
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.SanitizedHttpProbePresentation
import com.nodeseek.app.domain.netbench.SanitizedIcmpPingPresentation
import com.nodeseek.app.domain.netbench.SanitizedIpIntelPresentation
import com.nodeseek.app.domain.netbench.SanitizedIperf3Presentation
import com.nodeseek.app.domain.netbench.SanitizedNetBenchReport
import com.nodeseek.app.domain.netbench.SanitizedPingSample
import com.nodeseek.app.domain.netbench.SanitizedTcpPingPresentation
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
 * Sanitized, inspectable presentation model for each rendered NodeImage card kind.
 * Built exclusively from [SanitizedNetBenchReport] so Markdown reports and PNG card images
 * share a single privacy-scrubbed data source.
 */
sealed class NetBenchCardPresentationModel {
    abstract val kind: NetBenchReportCardKind
    abstract val title: String
    abstract val subtitle: String
    abstract val headerPillText: String

    abstract fun fingerprintPayload(darkTheme: Boolean): String
    abstract fun allDisplayTexts(): List<String>
}

data class IpIntelligenceCardModel(
    override val title: String,
    override val subtitle: String,
    override val headerPillText: String,
    val originType: IpOriginType,
    val evidenceText: String,
    val pills: List<Pair<String, Boolean>>,
    val orgLineText: String?,
    val sourceSummaryText: String?,
    val clientFooterText: String?,
) : NetBenchCardPresentationModel() {
    override val kind: NetBenchReportCardKind = NetBenchReportCardKind.IP_INTELLIGENCE

    override fun fingerprintPayload(darkTheme: Boolean): String = buildString {
        append("v2|IP_INTEL|dark=").append(darkTheme)
        append("|sub=").append(subtitle)
        append("|origin=").append(originType.name)
        append("|pill=").append(headerPillText)
        append("|ev=").append(evidenceText)
        append("|pills=").append(pills.joinToString(";") { it.first })
        append("|org=").append(orgLineText.orEmpty())
        append("|src=").append(sourceSummaryText.orEmpty())
        append("|client=").append(clientFooterText.orEmpty())
    }

    override fun allDisplayTexts(): List<String> = buildList {
        add(title)
        add(subtitle)
        add(headerPillText)
        add(evidenceText)
        pills.forEach { add(it.first) }
        orgLineText?.takeIf { it.isNotBlank() }?.let(::add)
        sourceSummaryText?.takeIf { it.isNotBlank() }?.let(::add)
        clientFooterText?.takeIf { it.isNotBlank() }?.let(::add)
    }
}

data class LatencyCardStatCell(
    val label: String,
    val value: String,
    val isErrorAccent: Boolean,
)

data class LatencyCardModel(
    override val kind: NetBenchReportCardKind,
    override val title: String,
    override val subtitle: String,
    override val headerPillText: String,
    val hasLoss: Boolean,
    val sent: Int,
    val avgMs: Double?,
    val stats: List<LatencyCardStatCell>,
    val samples: List<SanitizedPingSample>,
    val scalePeakMs: Double,
    val waveformLeftLabel: String?,
    val waveformRightLabel: String?,
    val httpFooterTitle: String?,
    val httpFooterValueText: String?,
    val httpFooterIsError: Boolean,
) : NetBenchCardPresentationModel() {

    override fun fingerprintPayload(darkTheme: Boolean): String = buildString {
        append("v2|").append(kind.name).append("|dark=").append(darkTheme)
        append("|sub=").append(subtitle)
        append("|pill=").append(headerPillText)
        append("|stats=").append(stats.joinToString(";") { "${it.label}=${it.value}" })
        append("|samples=")
        samples.forEach { s ->
            append(s.seq).append(':').append(s.success).append(':').append(s.rttMs).append(';')
        }
        if (httpFooterTitle != null || httpFooterValueText != null) {
            append("|http=").append(httpFooterIsError).append(':').append(httpFooterValueText.orEmpty())
        }
    }

    override fun allDisplayTexts(): List<String> = buildList {
        add(title)
        add(subtitle)
        add(headerPillText)
        stats.forEach {
            add(it.value)
            add(it.label)
        }
        waveformLeftLabel?.takeIf { it.isNotBlank() }?.let(::add)
        waveformRightLabel?.takeIf { it.isNotBlank() }?.let(::add)
        httpFooterTitle?.takeIf { it.isNotBlank() }?.let(::add)
        httpFooterValueText?.takeIf { it.isNotBlank() }?.let(::add)
    }
}

data class Iperf3IntervalRowModel(
    val timeLabel: String,
    val bytesText: String,
    val rateText: String,
    val bitrateMbps: Double,
)

data class Iperf3DirectionColumnModel(
    val direction: Iperf3Direction,
    val directionTitle: String,
    val effectiveMbps: Double,
    val gaugeValueText: String,
    val gaugeUnitLabel: String,
    val transferredText: String,
    val peakText: String,
    val intervalHeaderTitle: String,
    val intervalMetaText: String,
    val maxScaleMbps: Double,
    val intervalRows: List<Iperf3IntervalRowModel>,
)

data class Iperf3CardModel(
    override val title: String,
    override val subtitle: String,
    override val headerPillText: String,
    val isDual: Boolean,
    val columns: List<Iperf3DirectionColumnModel>,
) : NetBenchCardPresentationModel() {
    override val kind: NetBenchReportCardKind = NetBenchReportCardKind.IPERF3

    override fun fingerprintPayload(darkTheme: Boolean): String = buildString {
        append("v2|IPERF3|dark=").append(darkTheme)
        append("|sub=").append(subtitle)
        append("|pill=").append(headerPillText)
        columns.forEach { col ->
            append("|dir=").append(col.direction.name)
            append(",val=").append(col.gaugeValueText).append(col.gaugeUnitLabel)
            append(",tx=").append(col.transferredText)
            append(",pk=").append(col.peakText)
            append(",meta=").append(col.intervalMetaText)
            col.intervalRows.forEach { iv ->
                append('[').append(iv.timeLabel).append(':').append(iv.rateText).append(':').append(iv.bytesText).append(']')
            }
        }
    }

    override fun allDisplayTexts(): List<String> = buildList {
        add(title)
        add(subtitle)
        add(headerPillText)
        columns.forEach { col ->
            add(col.directionTitle)
            add(col.gaugeValueText)
            add(col.gaugeUnitLabel)
            add(col.transferredText)
            add(col.peakText)
            add(col.intervalHeaderTitle)
            col.intervalMetaText.takeIf { it.isNotBlank() }?.let(::add)
            col.intervalRows.forEach { row ->
                add(row.timeLabel)
                if (!isDual) add(row.bytesText)
                add(row.rateText)
            }
        }
    }
}

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
 * - Consumes ONLY [SanitizedNetBenchReport] produced by [NetBenchReportSanitizer].
 * - Local client raw/masked egress IPs are NEVER drawn onto any exported image.
 * - Probe failures render standardized Chinese [NetBenchErrorCategory] labels rather than raw
 *   OkHttp/socket exception strings.
 * - When [maskTargetIp] is enabled, target IPv4/IPv6 addresses, PTR hostnames, and benchmark ports
 *   are masked identically to [NetBenchMarkdownFormatter].
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
        val sanitized = NetBenchReportSanitizer.sanitize(
            report = report,
            maskTargetIp = maskTargetIp,
            includeClientNetwork = includeClientNetwork,
        )
        return buildAllCardPresentations(sanitized).map { model ->
            model.kind to sha256Short(model.fingerprintPayload(darkTheme))
        }
    }

    override fun renderCard(
        kind: NetBenchReportCardKind,
        report: NetBenchReport,
        maskTargetIp: Boolean,
        includeClientNetwork: Boolean,
        darkTheme: Boolean,
    ): RenderedNetBenchCardImage? {
        val sanitized = NetBenchReportSanitizer.sanitize(
            report = report,
            maskTargetIp = maskTargetIp,
            includeClientNetwork = includeClientNetwork,
        )
        val model = buildCardPresentation(kind, sanitized) ?: return null
        val plannedKey = sha256Short(model.fingerprintPayload(darkTheme))
        val palette = if (darkTheme) CardPalette.Dark else CardPalette.Light

        val textScrubber: (String) -> String = { text ->
            NetBenchReportSanitizer.scrubPublicText(text, report, maskTargetIp)
        }
        val bitmap = renderModelToBitmap(model, palette, textScrubber) ?: return null

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

    /**
     * Builds all applicable sanitized card presentation models from [sanitized].
     */
    fun buildAllCardPresentations(sanitized: SanitizedNetBenchReport): List<NetBenchCardPresentationModel> =
        NetBenchReportCardKind.entries.mapNotNull { kind ->
            buildCardPresentation(kind, sanitized)
        }

    fun buildAllCardPresentations(
        report: NetBenchReport,
        maskTargetIp: Boolean,
        includeClientNetwork: Boolean,
    ): List<NetBenchCardPresentationModel> = buildAllCardPresentations(
        NetBenchReportSanitizer.sanitize(
            report = report,
            maskTargetIp = maskTargetIp,
            includeClientNetwork = includeClientNetwork,
        ),
    )

    fun buildCardPresentation(
        kind: NetBenchReportCardKind,
        sanitized: SanitizedNetBenchReport,
    ): NetBenchCardPresentationModel? {
        return when (kind) {
            NetBenchReportCardKind.IP_INTELLIGENCE -> {
                val intel = sanitized.ipIntelligence ?: return null
                buildIpIntelligenceCardModel(intel, sanitized.clientEnv?.cardFooterText)
            }
            NetBenchReportCardKind.ICMP_PING -> {
                val ping = sanitized.icmpPing?.takeIf { it.sent > 0 } ?: return null
                buildIcmpPingCardModel(ping)
            }
            NetBenchReportCardKind.TCP_PING -> {
                val tcp = sanitized.tcpPing?.takeIf { it.sent > 0 } ?: return null
                buildTcpPingCardModel(tcp, sanitized.httpProbe)
            }
            NetBenchReportCardKind.IPERF3 -> {
                val succeeded = listOfNotNull(sanitized.iperfDownload, sanitized.iperfUpload)
                    .filter { it.success }
                if (succeeded.isEmpty()) return null
                buildIperf3CardModel(succeeded)
            }
        }
    }

    /**
     * Executes the exact Canvas layout & text-drawing pipeline for [kind] against a recording
     * text sink, returning every text element drawn onto the card (usable in plain JVM unit tests).
     */
    internal fun extractRenderedCardTexts(
        kind: NetBenchReportCardKind,
        report: NetBenchReport,
        maskTargetIp: Boolean,
        includeClientNetwork: Boolean,
        darkTheme: Boolean = false,
    ): List<String> {
        val sanitized = NetBenchReportSanitizer.sanitize(
            report = report,
            maskTargetIp = maskTargetIp,
            includeClientNetwork = includeClientNetwork,
        )
        val model = buildCardPresentation(kind, sanitized) ?: return emptyList()
        val palette = if (darkTheme) CardPalette.Dark else CardPalette.Light
        val recorded = mutableListOf<String>()
        val textScrubber: (String) -> String = { text ->
            NetBenchReportSanitizer.scrubPublicText(text, report, maskTargetIp)
        }
        val contentLeft = OUTER_INSET_DP + CARD_PAD_H_DP
        val contentTop = OUTER_INSET_DP + CARD_PAD_V_DP
        val contentWidth = LOGICAL_WIDTH_DP - (OUTER_INSET_DP + CARD_PAD_H_DP) * 2f
        drawModelContent(
            canvas = Canvas(),
            model = model,
            palette = palette,
            contentLeft = contentLeft,
            contentTop = contentTop,
            contentWidth = contentWidth,
            textScrubber = textScrubber,
            recordedTexts = recorded,
        )
        return recorded
    }

    // ── Sanitized Card Presentation Builders ─────────────────────────────────────────────────

    private fun buildIpIntelligenceCardModel(
        intel: SanitizedIpIntelPresentation,
        clientFooterText: String?,
    ): IpIntelligenceCardModel {
        val orgLabel = (intel.orgName?.takeIf { it.isNotBlank() } ?: intel.ispName)?.trim()
        val sourceSummary = listOfNotNull(
            intel.geoSource?.takeIf { it.isNotBlank() }?.let { "Geo: $it" },
            intel.whoisSource?.takeIf { it.isNotBlank() }?.let { "WHOIS: $it" },
            intel.rirAuthority?.takeIf { it.isNotBlank() }?.let { "RIR: $it" },
        ).joinToString(" · ")
        val evidence = if (!intel.hasCoreInfo && !intel.publicErrorMessage.isNullOrBlank()) {
            intel.publicErrorMessage
        } else {
            intel.originEvidence.ifBlank { intel.originType.label }
        }
        val pills = buildList {
            if (intel.locationSummary.isNotBlank()) add(intel.locationSummary to true)
            intel.asn?.takeIf { it.isNotBlank() }?.let { add(it to false) }
            if (intel.usageType != IpUsageType.UNKNOWN) {
                add(intel.usageType.label to false)
            }
        }
        return IpIntelligenceCardModel(
            title = "IP 归属与原生/广播鉴定",
            subtitle = "${intel.displayIp} · ${intel.ipFamily.label}",
            headerPillText = intel.originType.label,
            originType = intel.originType,
            evidenceText = evidence,
            pills = pills,
            orgLineText = orgLabel?.takeIf { it.isNotBlank() }?.let { "运营商 / 机构：$it" },
            sourceSummaryText = sourceSummary.takeIf { it.isNotEmpty() },
            clientFooterText = clientFooterText?.takeIf { it.isNotBlank() },
        )
    }

    private fun buildIcmpPingCardModel(ping: SanitizedIcmpPingPresentation): LatencyCardModel {
        val subtitle = buildList {
            add("ICMP")
            ping.boundInterfaceName?.takeIf { it.isNotBlank() }?.let { add("网卡 $it") }
            add("${ping.received}/${ping.sent} 成功")
            val ttl = ping.samples.firstOrNull { it.success && it.ttl != null }?.ttl
            if (ttl != null) add("TTL $ttl")
        }.joinToString(" · ")

        return buildLatencyCardModel(
            kind = NetBenchReportCardKind.ICMP_PING,
            title = "Ping 延迟探测",
            subtitle = subtitle,
            sent = ping.sent,
            avgMs = ping.avgMs,
            minMs = ping.minMs,
            maxMs = ping.maxMs,
            jitterMdevMs = ping.jitterMdevMs,
            lossPercent = ping.lossPercent,
            failureAvgLabel = ping.publicErrorMessage,
            samples = ping.samples,
            httpProbe = null,
        )
    }

    private fun buildTcpPingCardModel(
        tcp: SanitizedTcpPingPresentation,
        httpProbe: SanitizedHttpProbePresentation?,
    ): LatencyCardModel {
        val subtitle = "${tcp.portSummaryLabel} · ${tcp.received}/${tcp.sent} 成功"
        return buildLatencyCardModel(
            kind = NetBenchReportCardKind.TCP_PING,
            title = "TCPing 延迟探测",
            subtitle = subtitle,
            sent = tcp.sent,
            avgMs = tcp.avgMs,
            minMs = tcp.minMs,
            maxMs = tcp.maxMs,
            jitterMdevMs = tcp.jitterMdevMs,
            lossPercent = tcp.lossPercent,
            failureAvgLabel = tcp.publicErrorMessage,
            samples = tcp.samples,
            httpProbe = httpProbe,
        )
    }

    private fun buildLatencyCardModel(
        kind: NetBenchReportCardKind,
        title: String,
        subtitle: String,
        sent: Int,
        avgMs: Double?,
        minMs: Double?,
        maxMs: Double?,
        jitterMdevMs: Double?,
        lossPercent: Double,
        failureAvgLabel: String?,
        samples: List<SanitizedPingSample>,
        httpProbe: SanitizedHttpProbePresentation?,
    ): LatencyCardModel {
        val hasLoss = lossPercent > 0.0
        val pillText = if (hasLoss) {
            "丢包 ${NetBenchMarkdownFormatter.formatPercent(lossPercent)}"
        } else {
            "0% 丢包"
        }
        val avgText = when {
            avgMs != null -> NetBenchMarkdownFormatter.formatMs(avgMs)
            sent == 0 -> failureAvgLabel ?: "不可用"
            else -> failureAvgLabel ?: "超时"
        }
        val minMaxText = if (minMs != null && maxMs != null) {
            "${String.format(Locale.US, "%.0f", minMs)}/${String.format(Locale.US, "%.0f", maxMs)} ms"
        } else {
            "—"
        }
        val jitterText = jitterMdevMs?.let { NetBenchMarkdownFormatter.formatMs(it) } ?: "—"
        val lossText = NetBenchMarkdownFormatter.formatPercent(lossPercent)

        val stats = listOf(
            LatencyCardStatCell("平均延迟", avgText, isErrorAccent = avgMs == null),
            LatencyCardStatCell("最小/最大", minMaxText, isErrorAccent = false),
            LatencyCardStatCell("抖动", jitterText, isErrorAccent = false),
            LatencyCardStatCell("丢包率", lossText, isErrorAccent = hasLoss),
        )

        val maxSampleRtt = samples.asSequence()
            .filter { it.success && it.rttMs != null }
            .mapNotNull { it.rttMs }
            .maxOrNull()
        val scalePeakMs = ((maxSampleRtt ?: 100.0) * 1.22).coerceAtLeast(20.0)
        val hasSamples = samples.isNotEmpty()

        val httpFooterTitle = if (httpProbe != null) "HTTP 探测" else null
        val (httpFooterValue, httpIsError) = when {
            httpProbe == null -> null to false
            httpProbe.success && httpProbe.totalMs != null -> {
                val info = buildList {
                    httpProbe.statusCode?.let { add("HTTP $it") }
                    add(NetBenchMarkdownFormatter.formatMs(httpProbe.totalMs))
                    httpProbe.tlsHandshakeMs?.takeIf { it > 0.0 }?.let {
                        add("TLS ${NetBenchMarkdownFormatter.formatMs(it)}")
                    }
                }.joinToString(" · ")
                info to false
            }
            else -> {
                (httpProbe.publicErrorMessage ?: NetBenchErrorCategory.REQUEST_FAILED.publicLabel) to true
            }
        }

        return LatencyCardModel(
            kind = kind,
            title = title,
            subtitle = subtitle,
            headerPillText = pillText,
            hasLoss = hasLoss,
            sent = sent,
            avgMs = avgMs,
            stats = stats,
            samples = samples,
            scalePeakMs = scalePeakMs,
            waveformLeftLabel = if (hasSamples) "实时 RTT 波形 (${samples.size}/$sent)" else null,
            waveformRightLabel = if (hasSamples) "峰值刻度 ${NetBenchMarkdownFormatter.formatMs(scalePeakMs)}" else null,
            httpFooterTitle = httpFooterTitle,
            httpFooterValueText = httpFooterValue,
            httpFooterIsError = httpIsError,
        )
    }

    private fun buildIperf3CardModel(results: List<SanitizedIperf3Presentation>): Iperf3CardModel {
        val primaryPortLabel = results.first().portSummaryLabel
        val maxStreams = results.maxOfOrNull { it.parallelStreams } ?: 1
        val streamSuffix = if (maxStreams > 1) " · -P $maxStreams 并行流" else ""
        val subtitle = "纯 Kotlin TCP 协议 · $primaryPortLabel$streamSuffix"
        val isDual = results.size >= 2
        val maxRows = if (isDual) 3 else 6

        val columns = results.take(2).map { res ->
            val isDownload = res.direction == Iperf3Direction.DOWNLOAD
            val dirArrow = if (isDownload) "↓ " else "↑ "
            val mbps = res.effectiveMbps
            val (valStr, unitStr) = if (mbps >= 1000.0) {
                String.format(Locale.US, "%.2f", mbps / 1000.0) to "Gbps"
            } else {
                String.format(Locale.US, "%.1f", mbps) to "Mbps"
            }
            val displayedIntervals = selectDisplayIntervals(res, maxRows = maxRows)
            val totalIntervals = res.measuredIntervals.size.coerceAtLeast(displayedIntervals.size)
            val ivHeaderTitle = if (isDual) {
                "逐秒明细 (${totalIntervals}段)"
            } else {
                "${res.direction.flagLabel} · 逐秒测速明细 (${totalIntervals}段)"
            }
            val meta = buildList {
                res.omitSec.takeIf { it > 0 }?.let { add("剔除预热 ${it}s") }
                res.stabilityPercent?.let { add("稳定度 ${NetBenchMarkdownFormatter.formatPercent(it)}") }
                res.retransmits?.takeIf { it >= 0 }?.let { add("重传 $it") }
            }.joinToString(" · ")
            val maxMbps = maxOf(
                res.measuredIntervals.maxOfOrNull { it.bitrateMbps } ?: 0.0,
                displayedIntervals.maxOfOrNull { it.bitrateMbps } ?: 0.0,
                res.maxIntervalMbps,
                1.0,
            )
            val rows = displayedIntervals.map { sample ->
                Iperf3IntervalRowModel(
                    timeLabel = String.format(Locale.US, "%.0f-%.0fs", sample.startSec, sample.endSec),
                    bytesText = NetBenchMarkdownFormatter.formatBytes(sample.bytesTransferred),
                    rateText = NetBenchMarkdownFormatter.formatMbps(sample.bitrateMbps),
                    bitrateMbps = sample.bitrateMbps,
                )
            }
            Iperf3DirectionColumnModel(
                direction = res.direction,
                directionTitle = "$dirArrow${res.direction.flagLabel}",
                effectiveMbps = mbps,
                gaugeValueText = valStr,
                gaugeUnitLabel = "$unitStr · 平均",
                transferredText = "已传输 ${NetBenchMarkdownFormatter.formatBytes(res.bytesTransferred)}",
                peakText = "峰值 ${NetBenchMarkdownFormatter.formatMbps(res.maxIntervalMbps)}",
                intervalHeaderTitle = ivHeaderTitle,
                intervalMetaText = meta,
                maxScaleMbps = maxMbps,
                intervalRows = rows,
            )
        }

        return Iperf3CardModel(
            title = "原生 iperf3 吞吐测速",
            subtitle = subtitle,
            headerPillText = if (isDual) "双向测速完成" else "测速完成",
            isDual = isDual,
            columns = columns,
        )
    }

    // ── Bitmap Sizing & Dispatch ─────────────────────────────────────────────────────────────

    private fun computeLogicalHeightDp(model: NetBenchCardPresentationModel): Float = when (model) {
        is IpIntelligenceCardModel -> {
            var h = 136f
            if (!model.orgLineText.isNullOrBlank() || !model.sourceSummaryText.isNullOrBlank()) h += 20f
            if (!model.clientFooterText.isNullOrBlank()) h += 28f
            h
        }
        is LatencyCardModel -> {
            var h = if (model.samples.isNotEmpty()) 224f else 128f
            if (model.httpFooterTitle != null) h += 28f
            h
        }
        is Iperf3CardModel -> if (model.isDual) 266f else 216f
    }

    private fun renderModelToBitmap(
        model: NetBenchCardPresentationModel,
        palette: CardPalette,
        textScrubber: (String) -> String,
    ): Bitmap? {
        val logicalHeight = computeLogicalHeightDp(model)
        return createCardBitmap(logicalHeight, palette) { canvas, contentLeft, contentTop, contentWidth ->
            drawModelContent(
                canvas = canvas,
                model = model,
                palette = palette,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentWidth = contentWidth,
                textScrubber = textScrubber,
                recordedTexts = null,
            )
        }
    }

    private fun drawModelContent(
        canvas: Canvas,
        model: NetBenchCardPresentationModel,
        palette: CardPalette,
        contentLeft: Float,
        contentTop: Float,
        contentWidth: Float,
        textScrubber: (String) -> String,
        recordedTexts: MutableList<String>?,
    ) {
        val ctx = TextDrawSession(textScrubber, recordedTexts)
        when (model) {
            is IpIntelligenceCardModel -> drawIpIntelligenceCard(
                canvas = canvas,
                model = model,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentWidth = contentWidth,
                palette = palette,
                session = ctx,
            )
            is LatencyCardModel -> drawLatencyCard(
                canvas = canvas,
                model = model,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentWidth = contentWidth,
                palette = palette,
                session = ctx,
            )
            is Iperf3CardModel -> drawIperf3Card(
                canvas = canvas,
                model = model,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentWidth = contentWidth,
                palette = palette,
                session = ctx,
            )
        }
    }

    private class TextDrawSession(
        private val scrubber: (String) -> String,
        private val sink: MutableList<String>?,
    ) {
        fun prepare(raw: String): String {
            val clean = scrubber(raw)
            sink?.add(clean)
            return clean
        }
    }

    // ── Card 1: IP Intelligence & Native / Broadcast Classifier ──────────────────────────────

    private fun drawIpIntelligenceCard(
        canvas: Canvas,
        model: IpIntelligenceCardModel,
        contentLeft: Float,
        contentTop: Float,
        contentWidth: Float,
        palette: CardPalette,
        session: TextDrawSession,
    ) {
        var y = contentTop
        val (pillBg, pillFg) = when (model.originType) {
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
            title = model.title,
            subtitle = model.subtitle,
            iconType = HeaderIconType.GLOBE,
            pillText = model.headerPillText,
            pillBg = pillBg,
            pillFg = pillFg,
            palette = palette,
            session = session,
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

        drawAutoFitText(
            canvas = canvas,
            text = model.evidenceText,
            x = contentLeft + 30f,
            centerY = checkCy,
            maxWidth = contentWidth - 40f,
            preferredSp = 11.8f,
            minSp = 9.8f,
            color = palette.onSurface,
            bold = true,
            session = session,
        )
        y += boxHeight + 10f

        // Pills row (Location + ASN + Usage Type)
        var pillX = contentLeft
        val pillHeight = 23f
        for ((label, isPrimarySurface) in model.pills) {
            val fgColor = if (isPrimarySurface) palette.onSurface else palette.onSurfaceVariant
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
                session = session,
            )
            pillX += measured + 7f
        }
        y += pillHeight + 8f

        // ISP/Org + Geo/WHOIS/RIR metadata line
        val orgLine = model.orgLineText
        val sourceSummary = model.sourceSummaryText.orEmpty()
        if (!orgLine.isNullOrBlank() || sourceSummary.isNotEmpty()) {
            val rightReserve = if (sourceSummary.isNotEmpty() && !orgLine.isNullOrBlank()) 208f else 0f
            if (!orgLine.isNullOrBlank()) {
                val maxOrgW = (contentWidth - rightReserve - 8f).coerceAtLeast(150f)
                drawEllipsizedText(
                    canvas = canvas,
                    text = orgLine,
                    x = contentLeft,
                    centerY = y + 7f,
                    maxWidth = maxOrgW,
                    textSp = 10.8f,
                    color = palette.onSurfaceVariant,
                    bold = false,
                    session = session,
                )
            }
            if (sourceSummary.isNotEmpty()) {
                val alignRight = !orgLine.isNullOrBlank()
                if (alignRight) {
                    drawRightAlignedText(
                        canvas = canvas,
                        text = sourceSummary,
                        rightX = contentLeft + contentWidth,
                        centerY = y + 7f,
                        maxWidth = 206f,
                        textSp = 9.5f,
                        color = palette.onSurfaceMuted,
                        session = session,
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
                        session = session,
                    )
                }
            }
            y += 18f
        }

        // Local client egress privacy strip (NEVER includes raw/masked IP)
        if (!model.clientFooterText.isNullOrBlank()) {
            y += 4f
            drawHairlineDivider(canvas, contentLeft, y, contentWidth, palette)
            y += 13f
            drawShieldDotIcon(canvas, contentLeft + 6.5f, y, 6.2f, palette.primary)
            drawEllipsizedText(
                canvas = canvas,
                text = model.clientFooterText,
                x = contentLeft + 18f,
                centerY = y,
                maxWidth = contentWidth - 18f,
                textSp = 10.2f,
                color = palette.onSurfaceVariant,
                bold = false,
                session = session,
            )
        }
    }

    // ── Cards 2 & 3: ICMP Ping / TCPing Latency & Waveform (+ Optional HTTP Probe) ───────────

    private fun drawLatencyCard(
        canvas: Canvas,
        model: LatencyCardModel,
        contentLeft: Float,
        contentTop: Float,
        contentWidth: Float,
        palette: CardPalette,
        session: TextDrawSession,
    ) {
        val iconType = if (model.kind == NetBenchReportCardKind.ICMP_PING) {
            HeaderIconType.WAVEFORM
        } else {
            HeaderIconType.NETWORK_CHECK
        }
        val pillBg = if (model.hasLoss) palette.errorContainer else palette.primaryContainer
        val pillFg = if (model.hasLoss) palette.onErrorContainer else palette.onPrimaryContainer

        var y = contentTop
        drawSectionHeader(
            canvas = canvas,
            left = contentLeft,
            top = y,
            width = contentWidth,
            title = model.title,
            subtitle = model.subtitle,
            iconType = iconType,
            pillText = model.headerPillText,
            pillBg = pillBg,
            pillFg = pillFg,
            palette = palette,
            session = session,
        )
        y += 44f

        // 4-Column Telemetry Strip
        val stripH = 48f
        val stripRect = RectF(contentLeft, y, contentLeft + contentWidth, y + stripH)
        drawFilledRoundRect(canvas, stripRect, 12f, palette.surfaceLow)

        val weights = floatArrayOf(1.00f, 1.28f, 0.94f, 0.86f)
        val weightSum = weights.sum()
        var colX = contentLeft
        for (idx in model.stats.indices) {
            val colW = contentWidth * (weights[idx] / weightSum)
            val cell = model.stats[idx]
            val valueColor = if (cell.isErrorAccent) palette.error else palette.onSurface
            drawAutoFitCenteredText(
                canvas = canvas,
                text = cell.value,
                centerX = colX + colW / 2f,
                centerY = y + 17.5f,
                maxWidth = colW - 10f,
                preferredSp = 13.8f,
                minSp = 10.5f,
                color = valueColor,
                bold = true,
                session = session,
            )
            drawCenteredText(
                canvas = canvas,
                text = cell.label,
                centerX = colX + colW / 2f,
                centerY = y + 35.5f,
                textSp = 10.2f,
                color = palette.onSurfaceVariant,
                bold = false,
                session = session,
            )
            if (idx < model.stats.lastIndex) {
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
        if (model.samples.isNotEmpty()) {
            model.waveformLeftLabel?.let { leftLabel ->
                drawEllipsizedText(
                    canvas = canvas,
                    text = leftLabel,
                    x = contentLeft,
                    centerY = y + 6f,
                    maxWidth = contentWidth * 0.55f,
                    textSp = 10.0f,
                    color = palette.onSurfaceVariant,
                    bold = false,
                    session = session,
                )
            }
            model.waveformRightLabel?.let { rightLabel ->
                drawRightAlignedText(
                    canvas = canvas,
                    text = rightLabel,
                    rightX = contentLeft + contentWidth,
                    centerY = y + 6f,
                    maxWidth = contentWidth * 0.45f,
                    textSp = 10.0f,
                    color = palette.onSurfaceVariant,
                    session = session,
                )
            }
            y += 16f

            val chartH = 74f
            val chartRect = RectF(contentLeft, y, contentLeft + contentWidth, y + chartH)
            drawFilledRoundRect(canvas, chartRect, 10f, palette.surfaceLow)
            drawLatencyWaveformInside(
                canvas = canvas,
                box = chartRect,
                samples = model.samples,
                plannedCount = max(model.sent, model.samples.size),
                avgMs = model.avgMs,
                maxRtt = model.scalePeakMs,
                palette = palette,
            )
            y += chartH
        }

        // Optional HTTP probe strip (standardized category on failure, never raw exception!)
        if (model.httpFooterTitle != null && model.httpFooterValueText != null) {
            y += 9f
            drawHairlineDivider(canvas, contentLeft, y, contentWidth, palette)
            y += 12.5f
            drawEllipsizedText(
                canvas = canvas,
                text = model.httpFooterTitle,
                x = contentLeft,
                centerY = y,
                maxWidth = 100f,
                textSp = 10.8f,
                color = palette.onSurface,
                bold = true,
                session = session,
            )
            drawRightAlignedText(
                canvas = canvas,
                text = model.httpFooterValueText,
                rightX = contentLeft + contentWidth,
                centerY = y,
                maxWidth = contentWidth - 108f,
                textSp = if (model.httpFooterIsError) 10.4f else 10.8f,
                color = if (model.httpFooterIsError) palette.error else palette.primary,
                bold = !model.httpFooterIsError,
                session = session,
            )
        }
    }

    // ── Card 4: Native Pure-Kotlin iperf3 Throughput & Interval Breakdown ────────────────────

    private fun drawIperf3Card(
        canvas: Canvas,
        model: Iperf3CardModel,
        contentLeft: Float,
        contentTop: Float,
        contentWidth: Float,
        palette: CardPalette,
        session: TextDrawSession,
    ) {
        var y = contentTop
        drawSectionHeader(
            canvas = canvas,
            left = contentLeft,
            top = y,
            width = contentWidth,
            title = model.title,
            subtitle = model.subtitle,
            iconType = HeaderIconType.SPEED,
            pillText = model.headerPillText,
            pillBg = palette.primaryContainer,
            pillFg = palette.onPrimaryContainer,
            palette = palette,
            session = session,
        )
        y += 46f

        if (!model.isDual) {
            val col = model.columns.first()
            val leftW = 180f
            val gap = 12f
            val rightX = contentLeft + leftW + gap
            val rightW = contentWidth - leftW - gap
            val panelH = 138f

            drawSingleIperfGaugeColumn(
                canvas = canvas,
                left = contentLeft,
                top = y,
                width = leftW,
                height = panelH,
                column = col,
                gaugeDiameter = 98f,
                palette = palette,
                session = session,
            )

            val panelRect = RectF(rightX, y, rightX + rightW, y + panelH)
            drawFilledRoundRect(canvas, panelRect, 11f, palette.surfaceLow)
            drawIntervalBreakdownInside(
                canvas = canvas,
                left = rightX,
                top = y,
                width = rightW,
                height = panelH,
                column = col,
                compactColumns = false,
                palette = palette,
                session = session,
            )
        } else {
            val gap = 14f
            val colW = (contentWidth - gap) / 2f
            model.columns.take(2).forEachIndexed { idx, col ->
                val colLeft = contentLeft + idx * (colW + gap)
                drawSingleIperfGaugeColumn(
                    canvas = canvas,
                    left = colLeft,
                    top = y,
                    width = colW,
                    height = 112f,
                    column = col,
                    gaugeDiameter = 84f,
                    palette = palette,
                    session = session,
                )
                val ivTop = y + 118f
                val ivH = 70f
                val ivRect = RectF(colLeft, ivTop, colLeft + colW, ivTop + ivH)
                drawFilledRoundRect(canvas, ivRect, 10f, palette.surfaceLow)
                drawIntervalBreakdownInside(
                    canvas = canvas,
                    left = colLeft,
                    top = ivTop,
                    width = colW,
                    height = ivH,
                    column = col,
                    compactColumns = true,
                    palette = palette,
                    session = session,
                )
            }

            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.outlineSubtle
                strokeWidth = 0.8f
            }
            val midX = contentLeft + colW + gap / 2f
            canvas.drawLine(midX, y + 6f, midX, y + 184f, divPaint)
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
        session: TextDrawSession,
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
            cx = left + tileSize / 2f,
            cy = top + tileSize / 2f,
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
            session = session,
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
            session = session,
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
            session = session,
        )
    }

    private fun drawLatencyWaveformInside(
        canvas: Canvas,
        box: RectF,
        samples: List<SanitizedPingSample>,
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
        column: Iperf3DirectionColumnModel,
        gaugeDiameter: Float,
        palette: CardPalette,
        session: TextDrawSession,
    ) {
        drawEllipsizedText(
            canvas = canvas,
            text = column.directionTitle,
            x = left,
            centerY = top + 7f,
            maxWidth = width,
            textSp = 11.4f,
            color = palette.onSurface,
            bold = true,
            session = session,
        )

        val mbps = column.effectiveMbps
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
                    arcLeft,
                    arcTop + gaugeDiameter,
                    arcLeft + gaugeDiameter,
                    arcTop,
                    palette.primary,
                    palette.secondary,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawArc(arcRect, 160f, 220f * fraction, false, activePaint)
        }

        val gaugeCenterX = arcLeft + gaugeDiameter / 2f
        val gaugeCenterY = arcTop + gaugeDiameter / 2f
        drawAutoFitCenteredText(
            canvas = canvas,
            text = column.gaugeValueText,
            centerX = gaugeCenterX,
            centerY = gaugeCenterY - 2f,
            maxWidth = gaugeDiameter - 20f,
            preferredSp = if (gaugeDiameter >= 94f) 19.5f else 17.0f,
            minSp = 13.0f,
            color = palette.onSurface,
            bold = true,
            session = session,
        )
        drawCenteredText(
            canvas = canvas,
            text = column.gaugeUnitLabel,
            centerX = gaugeCenterX,
            centerY = gaugeCenterY + 14.5f,
            textSp = 9.8f,
            color = palette.onSurfaceVariant,
            bold = false,
            session = session,
        )

        val footerY = top + height - 6f
        drawEllipsizedText(
            canvas = canvas,
            text = column.transferredText,
            x = left,
            centerY = footerY,
            maxWidth = width * 0.52f,
            textSp = 9.5f,
            color = palette.onSurfaceVariant,
            bold = false,
            session = session,
        )
        drawRightAlignedText(
            canvas = canvas,
            text = column.peakText,
            rightX = left + width,
            centerY = footerY,
            maxWidth = width * 0.48f,
            textSp = 9.5f,
            color = palette.onSurfaceVariant,
            session = session,
        )
    }

    private fun drawIntervalBreakdownInside(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        column: Iperf3DirectionColumnModel,
        compactColumns: Boolean,
        palette: CardPalette,
        session: TextDrawSession,
    ) {
        val padH = 10f
        val padV = 8f
        val innerLeft = left + padH
        val innerWidth = width - padH * 2f
        val bottom = top + height
        var y = top + padV

        val titleShare = if (compactColumns) 0.44f else 0.54f
        drawEllipsizedText(
            canvas = canvas,
            text = column.intervalHeaderTitle,
            x = innerLeft,
            centerY = y + 6f,
            maxWidth = if (column.intervalMetaText.isNotEmpty()) innerWidth * titleShare else innerWidth,
            textSp = if (compactColumns) 9.5f else 10.2f,
            color = palette.onSurface,
            bold = true,
            session = session,
        )
        if (column.intervalMetaText.isNotEmpty()) {
            drawRightAlignedText(
                canvas = canvas,
                text = column.intervalMetaText,
                rightX = innerLeft + innerWidth,
                centerY = y + 6f,
                maxWidth = innerWidth * (1f - titleShare),
                textSp = 9.1f,
                color = palette.onSurfaceVariant,
                session = session,
            )
        }
        y += 18f

        val availH = (bottom - padV - y).coerceAtLeast(18f)
        val rowCount = column.intervalRows.size.coerceAtLeast(1)
        val rowStep = (availH / rowCount).coerceIn(13.5f, 18.5f)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.surfaceHighest
            style = Paint.Style.FILL
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.primary, 0.84f)
            style = Paint.Style.FILL
        }

        for (sample in column.intervalRows) {
            val cy = y + rowStep / 2f
            val timeColW = if (compactColumns) 34f else 40f
            val rateColW = if (compactColumns) 62f else 68f
            val bytesColW = if (compactColumns) 0f else 52f

            drawEllipsizedText(
                canvas = canvas,
                text = sample.timeLabel,
                x = innerLeft,
                centerY = cy,
                maxWidth = timeColW,
                textSp = 9.2f,
                color = palette.onSurfaceVariant,
                bold = false,
                session = session,
            )

            val barLeft = innerLeft + timeColW + 4f
            val barRight = innerLeft + innerWidth - rateColW - bytesColW - 6f
            val barW = (barRight - barLeft).coerceAtLeast(24f)
            val barH = 5.6f
            val trackRect = RectF(barLeft, cy - barH / 2f, barLeft + barW, cy + barH / 2f)
            canvas.drawRoundRect(trackRect, barH / 2f, barH / 2f, trackPaint)

            val ratio = (sample.bitrateMbps / column.maxScaleMbps).toFloat().coerceIn(0.05f, 1f)
            val activeRect = RectF(barLeft, cy - barH / 2f, barLeft + barW * ratio, cy + barH / 2f)
            canvas.drawRoundRect(activeRect, barH / 2f, barH / 2f, fillPaint)

            if (!compactColumns) {
                drawRightAlignedText(
                    canvas = canvas,
                    text = sample.bytesText,
                    rightX = innerLeft + innerWidth - rateColW - 4f,
                    centerY = cy,
                    maxWidth = bytesColW,
                    textSp = 9.0f,
                    color = palette.onSurfaceVariant,
                    session = session,
                )
            }

            drawRightAlignedText(
                canvas = canvas,
                text = sample.rateText,
                rightX = innerLeft + innerWidth,
                centerY = cy,
                maxWidth = rateColW,
                textSp = 9.5f,
                color = palette.onSurface,
                bold = true,
                session = session,
            )

            y += rowStep
        }
    }

    private fun selectDisplayIntervals(
        result: SanitizedIperf3Presentation,
        maxRows: Int,
    ): List<Iperf3IntervalSample> {
        // Warm-up (`-O`) samples are excluded from the forum card, matching the averaged window.
        val raw = result.measuredIntervals
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
        session: TextDrawSession,
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
            centerX = left + width / 2f,
            centerY = top + height / 2f,
            textSp = textSp,
            color = fgColor,
            bold = bold,
            session = session,
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
        val fm = paint.fontMetrics ?: return centerY
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
        session: TextDrawSession,
    ) {
        val safeText = session.prepare(text)
        val paint = textPaint(textSp, color, bold).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(safeText, centerX, baselineForCenterY(paint, centerY), paint)
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
        session: TextDrawSession,
    ) {
        val safeText = session.prepare(text)
        var sp = preferredSp
        var paint = textPaint(sp, color, bold)
        while (sp > minSp && paint.measureText(safeText) > maxWidth) {
            sp = (sp - 0.5f).coerceAtLeast(minSp)
            paint = textPaint(sp, color, bold)
        }
        val fitted = ellipsizeToWidth(safeText, paint, maxWidth)
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
        session: TextDrawSession,
    ) {
        val safeText = session.prepare(text)
        var sp = preferredSp
        var paint = textPaint(sp, color, bold)
        while (sp > minSp && paint.measureText(safeText) > maxWidth) {
            sp = (sp - 0.5f).coerceAtLeast(minSp)
            paint = textPaint(sp, color, bold)
        }
        val fitted = ellipsizeToWidth(safeText, paint, maxWidth)
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
        session: TextDrawSession,
    ) {
        val safeText = session.prepare(text)
        val paint = textPaint(textSp, color, bold)
        val fitted = ellipsizeToWidth(safeText, paint, maxWidth)
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
        session: TextDrawSession,
    ) {
        val safeText = session.prepare(text)
        val paint = textPaint(textSp, color, bold)
        val fitted = ellipsizeToWidth(safeText, paint, maxWidth)
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

    // ── Privacy & Digest Helpers (Delegating to Shared NetBenchReportSanitizer) ──────────────

    internal fun formatDisplayIp(rawIp: String, maskTargetIp: Boolean): String {
        val trimmed = rawIp.trim()
        return if (maskTargetIp) NetBenchTargetParser.maskTargetIpLastSegment(trimmed) else trimmed
    }

    internal fun scrubTargetIpInText(
        raw: String,
        report: NetBenchReport,
        maskTargetIp: Boolean,
    ): String = NetBenchReportSanitizer.scrubPublicText(raw, report, maskTargetIp)

    internal fun buildSafeClientFooterText(report: NetBenchReport): String? {
        val sanitized = NetBenchReportSanitizer.sanitize(
            report = report,
            maskTargetIp = true,
            includeClientNetwork = true,
        )
        return sanitized.clientEnv?.cardFooterText?.takeIf { it.isNotBlank() }
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
