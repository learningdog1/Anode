package com.nodeseek.app.domain.baseline

import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.BackboneRouteTagger
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.ReverseTracerouteCodec
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteSummary
import com.nodeseek.app.domain.ssh.NodeQualityReportFormatter
import com.nodeseek.app.domain.ssh.NodeQualitySectionKind
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import java.util.Locale

/**
 * Pure-Kotlin extractor converting Mobile NetBench reports ([NetBenchReport]) and LiteSSH script
 * transcripts ([SshExecutionRecord]) into structured [BenchmarkBaselineSnapshot] instances.
 *
 * Missing metrics remain `null` — never fabricated as zero.
 */
object BaselineSnapshotExtractor {

    private val NON_SGR_CSI_REGEX = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")
    private val OSC_REGEX = Regex("""\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)""")
    private val ASIAN_TARGET_COUNTRIES = setOf("HK", "JP", "SG", "TW", "KR", "MO")
    private val DETOUR_TRANSIT_TAGS = setOf(
        BackboneRouteTag.COGENT,
        BackboneRouteTag.HE,
        BackboneRouteTag.LUMEN,
        BackboneRouteTag.TELIA,
    )

    private val SPEED_VALUE_REGEX = Regex(
        """(\d+(?:\.\d+)?)\s*(Gbit/s|Gbps|Gbits/sec|Mbit/s|Mbps|Mbits/sec|Kbit/s|Kbps|Kbits/sec)""",
        RegexOption.IGNORE_CASE,
    )
    private val LATENCY_VALUE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*ms\b""", RegexOption.IGNORE_CASE)
    private val LOSS_VALUE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*%""")
    private val IO_VALUE_REGEX = Regex(
        """(\d+(?:\.\d+)?)\s*(GB/s|GiB/s|MB/s|MiB/s|KB/s)""",
        RegexOption.IGNORE_CASE,
    )
    private val GEEKBENCH_SINGLE_REGEX = Regex(
        """(?:Single[\s-]*Core|单核)\s*[:：|\s]\s*(\d{2,5})""",
        RegexOption.IGNORE_CASE,
    )
    private val GEEKBENCH_MULTI_REGEX = Regex(
        """(?:Multi[\s-]*Core|多核)\s*[:：|\s]\s*(\d{2,6})""",
        RegexOption.IGNORE_CASE,
    )
    private val ASN_REGEX = Regex("""\bAS(\d{1,7})\b""", RegexOption.IGNORE_CASE)
    private val IPV4_LITERAL_REGEX = Regex(
        """\b(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}\b""",
    )

    fun normalizeTargetKey(
        serverId: String? = null,
        hostOrIp: String? = null,
    ): String {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanServerId != null) return "server:$cleanServerId"
        val raw = hostOrIp?.trim().orEmpty()
        if (raw.isEmpty()) return "unknown"
        val parsed = NetBenchTargetParser.parse(raw)
        val canonical = parsed?.host?.trim()?.lowercase(Locale.ROOT)
            ?: raw.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        return "host:$canonical"
    }

    fun fromNetBenchReport(
        report: NetBenchReport,
        id: String,
        label: String? = null,
        serverId: String? = null,
        capturedAtEpochMillis: Long = System.currentTimeMillis(),
    ): BenchmarkBaselineSnapshot {
        val resolvedHost = report.resolvedTarget?.displayHost?.takeIf { it.isNotBlank() }
            ?: report.targetInput.trim().ifBlank { report.resolvedTarget?.resolvedIp.orEmpty() }
        val resolvedIp = report.resolvedTarget?.resolvedIp?.takeIf { it.isNotBlank() }
            ?: report.ipIntelligence?.ip?.takeIf { it.isNotBlank() }
        val targetKey = normalizeTargetKey(
            serverId = serverId,
            hostOrIp = resolvedHost.ifBlank { resolvedIp },
        )

        val dlResult = report.iperfDownloadResult?.takeIf { it.success }
        val ulResult = report.iperfUploadResult?.takeIf { it.success }
        val stabilityValues = listOfNotNull(dlResult?.stabilityPercent, ulResult?.stabilityPercent)
        val avgStability = if (stabilityValues.isNotEmpty()) stabilityValues.average() else null
        val retransmits = dlResult?.retransmits ?: ulResult?.retransmits

        val bandwidth = BaselineBandwidthMetrics(
            downloadMbps = dlResult?.effectiveMbps?.takeIf { it.isFinite() && it > 0.0 },
            uploadMbps = ulResult?.effectiveMbps?.takeIf { it.isFinite() && it > 0.0 },
            peakDownloadMbps = dlResult?.maxIntervalMbps?.takeIf { it.isFinite() && it > 0.0 },
            peakUploadMbps = ulResult?.maxIntervalMbps?.takeIf { it.isFinite() && it > 0.0 },
            iperf3Retransmits = retransmits,
            iperf3StabilityPercent = avgStability,
        )

        val icmp = report.icmpPingSummary
        val tcp = report.tcpPingSummary
        val http = report.httpLatencyResult
        val latency = BaselineLatencyMetrics(
            icmpAvgMs = icmp?.avgMs?.takeIf { it.isFinite() },
            icmpMinMs = icmp?.minMs?.takeIf { it.isFinite() },
            icmpMaxMs = icmp?.maxMs?.takeIf { it.isFinite() },
            icmpJitterMs = icmp?.jitterMdevMs?.takeIf { it.isFinite() },
            icmpLossPercent = icmp?.lossPercent?.takeIf { it.isFinite() },
            tcpAvgMs = tcp?.avgMs?.takeIf { it.isFinite() },
            tcpLossPercent = tcp?.lossPercent?.takeIf { it.isFinite() },
            httpTtfbMs = http?.ttfbMs?.takeIf { it.isFinite() } ?: http?.totalMs?.takeIf { it.isFinite() },
        )

        val fwd = report.forwardTraceroute
        val rev = report.reverseTraceroute
        val (detourSuspected, detourReason) = detectDetour(
            targetCountryCode = report.ipIntelligence?.countryCode,
            forwardTraceroute = fwd,
            reverseTraceroute = rev,
        )

        val routing = BaselineRoutingMetrics(
            forwardRouteSignature = fwd?.routeSignature?.takeIf { it.isNotBlank() },
            forwardBackboneTags = fwd?.dominantBackboneTags.orEmpty(),
            forwardHopCount = fwd?.totalHops?.takeIf { it > 0 },
            forwardTerminalMs = fwd?.terminalAvgMs?.takeIf { it.isFinite() },
            forwardPeakHopLossPercent = fwd?.peakHopLossPercent?.takeIf { it.isFinite() },
            forwardHasRealPacketLoss = fwd?.hasRealPacketLoss,
            reverseRouteSignature = rev?.routeSignature?.takeIf { it.isNotBlank() },
            reverseBackboneTags = rev?.dominantBackboneTags.orEmpty(),
            reverseHopCount = rev?.totalHops?.takeIf { it > 0 },
            reverseTerminalMs = rev?.terminalAvgMs?.takeIf { it.isFinite() },
            reversePeakHopLossPercent = rev?.peakHopLossPercent?.takeIf { it.isFinite() },
            reverseHasRealPacketLoss = rev?.hasRealPacketLoss,
            isDetourSuspected = detourSuspected,
            detourReason = detourReason,
        )

        val ipMetrics = report.ipIntelligence?.let { intel ->
            BaselineIpMetrics(
                ipFamily = intel.ipFamily,
                originType = intel.originType,
                usageType = intel.usageType,
                countryCode = intel.countryCode?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() },
                asn = intel.asn?.trim()?.takeIf { it.isNotEmpty() },
                ispOrOrg = (intel.orgName?.takeIf { it.isNotBlank() } ?: intel.ispName)?.trim(),
            )
        }

        val displayHost = resolvedHost.ifBlank { "未命名目标" }
        val resolvedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$displayHost · 手机端测机基线"

        return BenchmarkBaselineSnapshot(
            id = id,
            label = resolvedLabel,
            targetKey = targetKey,
            serverId = serverId?.trim()?.takeIf { it.isNotEmpty() },
            hostDisplay = displayHost,
            resolvedIp = resolvedIp,
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            presetIds = emptyList(),
            capturedAtEpochMillis = capturedAtEpochMillis,
            isPeakHourWindow = fwd?.isPeakHourWindow ?: rev?.isPeakHourWindow,
            bandwidth = bandwidth,
            latency = latency,
            routing = routing,
            ipMetrics = ipMetrics,
        )
    }

    fun fromSshRuns(
        runs: List<SshExecutionRecord>,
        id: String,
        label: String? = null,
        capturedAtEpochMillis: Long = System.currentTimeMillis(),
    ): BenchmarkBaselineSnapshot {
        val validRuns = runs.filter { it.sanitizedAnsiOutput.isNotBlank() || it.serverHost.isNotBlank() }
        val primaryRun = validRuns.firstOrNull()
        val serverId = primaryRun?.serverId?.trim()?.takeIf { it.isNotEmpty() }
        val serverHost = primaryRun?.serverHost?.trim()?.takeIf { it.isNotEmpty() }
        val serverAlias = primaryRun?.serverAlias?.trim()?.takeIf { it.isNotEmpty() }
        val hostDisplay = serverAlias ?: serverHost ?: "SSH 服务器"
        val resolvedIp = serverHost?.takeIf { NetBenchTargetParser.detectIpFamily(it) != IpFamily.UNKNOWN }
        val targetKey = normalizeTargetKey(serverId = serverId, hostOrIp = serverHost ?: serverAlias)
        val presetIds = validRuns.mapNotNull { it.presetId.trim().takeIf(String::isNotEmpty) }.distinct()

        val accumulator = SshExtractionAccumulator()
        for (run in validRuns) {
            extractSingleSshRunInto(run, accumulator)
        }

        val finalDownload = accumulator.downloadMbps
            ?: accumulator.speedNodes.mapNotNull { it.downloadMbps }.takeIf { it.isNotEmpty() }?.average()
        val finalUpload = accumulator.uploadMbps
            ?: accumulator.speedNodes.mapNotNull { it.uploadMbps }.takeIf { it.isNotEmpty() }?.average()
        val finalIcmpAvg = accumulator.icmpAvgMs
            ?: accumulator.speedNodes.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()
            ?: accumulator.reverseTerminalMs

        val (detourSuspected, detourReason) = resolveSshDetour(accumulator)
        val resolvedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$hostDisplay · SSH 基线"

        val ipMetrics = if (
            accumulator.originType != IpOriginType.UNKNOWN ||
            accumulator.usageType != IpUsageType.UNKNOWN ||
            accumulator.countryCode != null ||
            accumulator.asn != null ||
            accumulator.ispOrOrg != null
        ) {
            BaselineIpMetrics(
                ipFamily = resolvedIp?.let { NetBenchTargetParser.detectIpFamily(it) } ?: IpFamily.UNKNOWN,
                originType = accumulator.originType,
                usageType = accumulator.usageType,
                countryCode = accumulator.countryCode,
                asn = accumulator.asn,
                ispOrOrg = accumulator.ispOrOrg,
            )
        } else {
            null
        }

        return BenchmarkBaselineSnapshot(
            id = id,
            label = resolvedLabel,
            targetKey = targetKey,
            serverId = serverId,
            hostDisplay = hostDisplay,
            resolvedIp = resolvedIp ?: accumulator.detectedIp,
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            presetIds = presetIds,
            capturedAtEpochMillis = capturedAtEpochMillis,
            bandwidth = BaselineBandwidthMetrics(
                downloadMbps = finalDownload,
                uploadMbps = finalUpload,
                diskReadMbPerSec = accumulator.diskReadMbPerSec,
                diskWriteMbPerSec = accumulator.diskWriteMbPerSec,
                geekbenchSingle = accumulator.geekbenchSingle,
                geekbenchMulti = accumulator.geekbenchMulti,
                speedNodes = accumulator.speedNodes.toList(),
            ),
            latency = BaselineLatencyMetrics(
                icmpAvgMs = finalIcmpAvg,
                icmpLossPercent = accumulator.icmpLossPercent,
            ),
            routing = BaselineRoutingMetrics(
                reverseRouteSignature = accumulator.reverseRouteSignature,
                reverseBackboneTags = accumulator.reverseBackboneTags.toList(),
                reverseHopCount = accumulator.reverseHopCount,
                reverseTerminalMs = accumulator.reverseTerminalMs,
                reversePeakHopLossPercent = accumulator.reversePeakHopLossPercent,
                reverseHasRealPacketLoss = accumulator.reverseHasRealPacketLoss,
                threeNetBackrouteSummary = accumulator.threeNetBackroutes.toMap(),
                isDetourSuspected = detourSuspected,
                detourReason = detourReason,
            ),
            ipMetrics = ipMetrics,
            streamingUnlocks = accumulator.streamingByKey.values.toList(),
        )
    }

    internal fun detectDetour(
        targetCountryCode: String?,
        forwardTraceroute: TracerouteSummary?,
        reverseTraceroute: TracerouteSummary?,
    ): Pair<Boolean, String?> {
        val normalizedTargetCountry = targetCountryCode?.trim()?.uppercase(Locale.US)
        val summaries = listOfNotNull(forwardTraceroute, reverseTraceroute)
        if (summaries.isEmpty()) return false to null

        val targetIsAsia = normalizedTargetCountry in ASIAN_TARGET_COUNTRIES ||
            summaries.any { summary ->
                summary.hops.lastOrNull { !it.isTimeoutOnly }?.countryCode?.uppercase(Locale.US) in ASIAN_TARGET_COUNTRIES
            }

        for (summary in summaries) {
            val hops = summary.hops.filter { !it.isTimeoutOnly }
            val hasCnHop = hops.any { it.countryCode.equals("CN", ignoreCase = true) }
            val hasUsHop = hops.any { it.countryCode.equals("US", ignoreCase = true) }
            val hasEuHop = hops.any {
                it.countryCode?.uppercase(Locale.US) in setOf("DE", "NL", "GB", "FR")
            }
            val terminalCountry = hops.lastOrNull()?.countryCode?.uppercase(Locale.US)
            val isTargetNonUs = (normalizedTargetCountry != null && normalizedTargetCountry != "US") ||
                (terminalCountry != null && terminalCountry != "US")

            if ((targetIsAsia || (hasCnHop && isTargetNonUs)) && hasUsHop) {
                val dir = if (summary.direction == TracerouteDirection.REVERSE) "回程" else "去程"
                return true to "${dir}经由美国 (US) 绕路"
            }
            if (targetIsAsia && hasEuHop) {
                val dir = if (summary.direction == TracerouteDirection.REVERSE) "回程" else "去程"
                return true to "${dir}经由欧洲节点绕路"
            }
            if (targetIsAsia && summary.dominantBackboneTags.any { it in DETOUR_TRANSIT_TAGS }) {
                val terminalMs = summary.terminalAvgMs ?: hops.mapNotNull { it.avgMs }.maxOrNull() ?: 0.0
                if (terminalMs > 130.0) {
                    val tagsLabel = summary.dominantBackboneTags
                        .filter { it in DETOUR_TRANSIT_TAGS }
                        .joinToString("/") { it.displayLabel }
                    return true to "亚太目标出现高延迟国际绕行 ($tagsLabel · ${terminalMs.toInt()}ms)"
                }
            }
        }
        return false to null
    }

    private class SshExtractionAccumulator {
        var detectedIp: String? = null
        var downloadMbps: Double? = null
        var uploadMbps: Double? = null
        var diskReadMbPerSec: Double? = null
        var diskWriteMbPerSec: Double? = null
        var geekbenchSingle: Int? = null
        var geekbenchMulti: Int? = null
        var icmpAvgMs: Double? = null
        var icmpLossPercent: Double? = null
        val speedNodes = mutableListOf<SpeedNodeTelemetry>()

        var reverseRouteSignature: String? = null
        val reverseBackboneTags = LinkedHashSet<BackboneRouteTag>()
        var reverseHopCount: Int? = null
        var reverseTerminalMs: Double? = null
        var reversePeakHopLossPercent: Double? = null
        var reverseHasRealPacketLoss: Boolean? = null
        val threeNetBackroutes = LinkedHashMap<String, String>()
        var explicitDetourReason: String? = null

        var originType: IpOriginType = IpOriginType.UNKNOWN
        var usageType: IpUsageType = IpUsageType.UNKNOWN
        var countryCode: String? = null
        var asn: String? = null
        var ispOrOrg: String? = null

        val streamingByKey = LinkedHashMap<String, StreamingPlatformResult>()
    }

    private fun extractSingleSshRunInto(
        run: SshExecutionRecord,
        acc: SshExtractionAccumulator,
    ) {
        val raw = run.sanitizedAnsiOutput
        if (raw.isBlank()) return

        if (NodeQualityReportFormatter.isNodeQualityRun(run)) {
            val parsed = NodeQualityReportFormatter.parse(
                rawOutput = raw,
                maskIp = false,
                knownServerHost = run.serverHost,
            )
            if (parsed.hasStructuredSections) {
                for (section in parsed.sections) {
                    val clean = stripAllAnsi(section.content)
                    when (section.kind) {
                        NodeQualitySectionKind.BASIC_INFO -> parseBasicHardwareAndDisk(clean, acc)
                        NodeQualitySectionKind.IP_QUALITY -> {
                            parseIpQualityText(clean, acc)
                            parseStreamingUnlocksFromText(clean, acc)
                        }
                        NodeQualitySectionKind.NET_QUALITY -> parseNetworkSpeedText(clean, acc)
                        NodeQualitySectionKind.BACKROUTE_TRACE -> parseBackrouteText(clean, acc)
                    }
                }
                return
            }
        }

        val cleanText = stripAllAnsi(raw)
        val presetId = run.presetId.lowercase(Locale.ROOT)
        when {
            presetId == SshPresetCatalog.PRESET_YABS ||
                cleanText.contains("fio Disk Speed Tests", ignoreCase = true) ||
                cleanText.contains("iperf3 Network Speed Tests", ignoreCase = true) -> {
                parseYabsOutput(cleanText, acc)
            }
            presetId == SshPresetCatalog.PRESET_NEXT_TRACE ||
                cleanText.contains("NextTrace", ignoreCase = true) -> {
                parseNextTraceOutput(cleanText, run.serverHost, acc)
            }
            presetId == SshPresetCatalog.PRESET_MEDIA_UNLOCK ||
                cleanText.contains("RegionRestrictionCheck", ignoreCase = true) -> {
                parseStreamingUnlocksFromText(cleanText, acc)
            }
            presetId == SshPresetCatalog.PRESET_ECS ||
                presetId == SshPresetCatalog.PRESET_BENCH ||
                cleanText.contains("融合怪", ignoreCase = true) ||
                cleanText.contains("Bench.sh", ignoreCase = true) -> {
                parseBasicHardwareAndDisk(cleanText, acc)
                parseIpQualityText(cleanText, acc)
                parseStreamingUnlocksFromText(cleanText, acc)
                parseNetworkSpeedText(cleanText, acc)
                parseBackrouteText(cleanText, acc)
            }
            else -> {
                parseBasicHardwareAndDisk(cleanText, acc)
                parseIpQualityText(cleanText, acc)
                parseStreamingUnlocksFromText(cleanText, acc)
                parseNetworkSpeedText(cleanText, acc)
                parseBackrouteText(cleanText, acc)
            }
        }
    }

    internal fun stripAllAnsi(text: String): String {
        val noOsc = OSC_REGEX.replace(text, "")
        val noSgr = NodeQualityReportFormatter.stripAnsiSgrFast(noOsc)
        val noCsi = NON_SGR_CSI_REGEX.replace(noSgr, "")
        return noCsi.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun parseBasicHardwareAndDisk(
        text: String,
        acc: SshExtractionAccumulator,
    ) {
        GEEKBENCH_SINGLE_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            acc.geekbenchSingle = it
        }
        GEEKBENCH_MULTI_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            acc.geekbenchMulti = it
        }

        // Parse Bench.sh / GoECS I/O Speed average or FIO total
        val ioSpeeds = mutableListOf<Double>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains("I/O Speed", ignoreCase = true) ||
                trimmed.contains("硬盘读写") ||
                trimmed.contains("磁盘读写") ||
                trimmed.contains("Read", ignoreCase = true) ||
                trimmed.contains("Write", ignoreCase = true) ||
                trimmed.contains("Total", ignoreCase = true)
            ) {
                val readWriteMatch = Regex(
                    """(?:Read|读(?:取)?)\s*[:：=]?\s*(\d+(?:\.\d+)?)\s*(GB/s|GiB/s|MB/s|MiB/s)""",
                    RegexOption.IGNORE_CASE,
                ).find(trimmed)
                if (readWriteMatch != null) {
                    val mb = convertIoToMbPerSec(readWriteMatch.groupValues[1], readWriteMatch.groupValues[2])
                    if (mb != null) acc.diskReadMbPerSec = mb
                }
                val writeMatch = Regex(
                    """(?:Write|写(?:入)?)\s*[:：=]?\s*(\d+(?:\.\d+)?)\s*(GB/s|GiB/s|MB/s|MiB/s)""",
                    RegexOption.IGNORE_CASE,
                ).find(trimmed)
                if (writeMatch != null) {
                    val mb = convertIoToMbPerSec(writeMatch.groupValues[1], writeMatch.groupValues[2])
                    if (mb != null) acc.diskWriteMbPerSec = mb
                }

                if (trimmed.contains("I/O Speed", ignoreCase = true) ||
                    trimmed.contains("Average", ignoreCase = true) ||
                    trimmed.startsWith("Total", ignoreCase = true)
                ) {
                    val matches = IO_VALUE_REGEX.findAll(trimmed).toList()
                    if (matches.isNotEmpty()) {
                        val last = matches.last()
                        convertIoToMbPerSec(last.groupValues[1], last.groupValues[2])?.let {
                            ioSpeeds += it
                        }
                    }
                }
            }
        }
        if (acc.diskReadMbPerSec == null && ioSpeeds.isNotEmpty()) {
            acc.diskReadMbPerSec = ioSpeeds.average()
        }
        if (acc.diskWriteMbPerSec == null && ioSpeeds.isNotEmpty()) {
            acc.diskWriteMbPerSec = ioSpeeds.average()
        }
    }

    private fun parseYabsOutput(
        text: String,
        acc: SshExtractionAccumulator,
    ) {
        var inFio = false
        var inIperf = false
        var inGb = false
        val fioTotals = mutableListOf<Double>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.contains("fio Disk Speed Tests", ignoreCase = true) -> {
                    inFio = true; inIperf = false; inGb = false; continue
                }
                line.contains("iperf3 Network Speed Tests", ignoreCase = true) -> {
                    inFio = false; inIperf = true; inGb = false; continue
                }
                line.contains("Geekbench", ignoreCase = true) -> {
                    inFio = false; inIperf = false; inGb = true
                }
            }

            if (inFio) {
                val speedTokenRegex = Regex("""(\d+(?:\.\d+)?)\s*(GB/s|MB/s|KB/s)""", RegexOption.IGNORE_CASE)
                when {
                    Regex("""^Read\s*\|""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> {
                        speedTokenRegex.findAll(line).forEach { m ->
                            convertIoToMbPerSec(m.groupValues[1], m.groupValues[2])?.let {
                                acc.diskReadMbPerSec = maxOf(acc.diskReadMbPerSec ?: 0.0, it)
                            }
                        }
                    }
                    Regex("""^Write\s*\|""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> {
                        speedTokenRegex.findAll(line).forEach { m ->
                            convertIoToMbPerSec(m.groupValues[1], m.groupValues[2])?.let {
                                acc.diskWriteMbPerSec = maxOf(acc.diskWriteMbPerSec ?: 0.0, it)
                            }
                        }
                    }
                    Regex("""^Total\s*\|""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> {
                        speedTokenRegex.findAll(line).forEach { m ->
                            convertIoToMbPerSec(m.groupValues[1], m.groupValues[2])?.let {
                                fioTotals += it
                            }
                        }
                    }
                }
            } else if (inIperf && line.contains('|')) {
                val cols = line.split('|').map { it.trim() }
                if (cols.size >= 4 &&
                    !cols[0].equals("Provider", ignoreCase = true) &&
                    !cols[0].startsWith("-")
                ) {
                    val nodeName = listOf(cols[0], cols.getOrNull(1).orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    val sendMbps = parseSpeedToMbps(cols[2])
                    val recvMbps = parseSpeedToMbps(cols[3])
                    val pingMs = cols.getOrNull(4)?.let { parseLatencyMs(it) }
                    if (sendMbps != null || recvMbps != null) {
                        acc.speedNodes += SpeedNodeTelemetry(
                            nodeName = nodeName.ifBlank { "iperf3" },
                            direction = "BOTH",
                            downloadMbps = recvMbps,
                            uploadMbps = sendMbps,
                            latencyMs = pingMs,
                        )
                    }
                }
            } else if (inGb) {
                GEEKBENCH_SINGLE_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                    acc.geekbenchSingle = it
                }
                GEEKBENCH_MULTI_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                    acc.geekbenchMulti = it
                }
            }
        }
        if (acc.diskReadMbPerSec == null && fioTotals.isNotEmpty()) {
            acc.diskReadMbPerSec = fioTotals.maxOrNull()
        }
        if (acc.diskWriteMbPerSec == null && fioTotals.isNotEmpty()) {
            acc.diskWriteMbPerSec = fioTotals.maxOrNull()
        }
        parseBasicHardwareAndDisk(text, acc)
    }

    private fun parseIpQualityText(
        text: String,
        acc: SshExtractionAccumulator,
    ) {
        if (acc.detectedIp == null) {
            IPV4_LITERAL_REGEX.find(text)?.value?.let { acc.detectedIp = it }
        }
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase(Locale.ROOT)
            if (acc.originType == IpOriginType.UNKNOWN) {
                when {
                    line.contains("原生IP") || line.contains("原生 IP") || lower.contains("native ip") ->
                        acc.originType = IpOriginType.NATIVE
                    line.contains("广播IP") || line.contains("广播 IP") || lower.contains("broadcast ip") ->
                        acc.originType = IpOriginType.BROADCAST
                    lower.contains("anycast") ->
                        acc.originType = IpOriginType.ANYCAST
                }
            }
            if (acc.usageType == IpUsageType.UNKNOWN) {
                when {
                    line.contains("家宽") || line.contains("住宅") || line.contains("双ISP") ||
                        lower.contains("residential") || lower.contains("isp") ->
                        acc.usageType = IpUsageType.ISP_RESIDENTIAL_OR_BUSINESS
                    line.contains("机房") || lower.contains("hosting") || lower.contains("datacenter") ||
                        lower.contains("data center") || lower.contains("idc") ->
                        acc.usageType = IpUsageType.HOSTING_DATACENTER
                }
            }
            if (acc.asn == null) {
                ASN_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { asnNum ->
                    acc.asn = "AS$asnNum"
                }
            }
            if (acc.countryCode == null && (line.contains("国家") || lower.contains("country") || lower.contains("region") || line.contains("地区"))) {
                acc.countryCode = extractCountryCodeFromIpQualityLine(line)
            }
            if (acc.ispOrOrg == null && (line.contains("组织") || line.contains("运营商") || lower.contains("organization") || lower.startsWith("isp"))) {
                val afterColon = line.substringAfter(':', "").ifBlank { line.substringAfter('：', "") }.trim()
                if (afterColon.isNotEmpty() && !afterColon.contains("检测")) {
                    acc.ispOrOrg = afterColon.take(48)
                }
            }
        }
    }

    private fun parseNetworkSpeedText(
        text: String,
        acc: SshExtractionAccumulator,
    ) {
        val dlSamples = mutableListOf<Double>()
        val ulSamples = mutableListOf<Double>()
        val latencySamples = mutableListOf<Double>()
        val lossSamples = mutableListOf<Double>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase(Locale.ROOT)

            // Skip header/divider lines
            if (line.startsWith("---") || line.startsWith("===") ||
                (lower.contains("node name") && lower.contains("upload"))
            ) {
                continue
            }

            // Explicit Upload / Download single-metric lines
            if (lower.startsWith("download") || line.startsWith("下载") || lower.contains("recv")) {
                parseSpeedToMbps(line)?.let {
                    dlSamples += it
                    acc.downloadMbps = acc.downloadMbps ?: it
                }
            }
            if (lower.startsWith("upload") || line.startsWith("上传") || lower.contains("send")) {
                parseSpeedToMbps(line)?.let {
                    ulSamples += it
                    acc.uploadMbps = acc.uploadMbps ?: it
                }
            }

            // Multi-column speedtest rows (NodeQuality / Bench.sh / GoECS)
            val speeds = SPEED_VALUE_REGEX.findAll(line).mapNotNull { m ->
                convertSpeedToMbps(m.groupValues[1], m.groupValues[2])
            }.toList()

            if (speeds.isNotEmpty()) {
                val lat = parseLatencyMs(line)
                val loss = LOSS_VALUE_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    ?.takeIf { it in 0.0..100.0 }
                if (lat != null) latencySamples += lat
                if (loss != null) lossSamples += loss

                val nodeName = extractSpeedNodeName(line)
                if (speeds.size >= 2) {
                    // Check whether arrow symbols clarify direction (`↑ ul ... ↓ dl` or `↓ dl ... ↑ ul`)
                    val upArrowIdx = line.indexOf('↑')
                    val downArrowIdx = line.indexOf('↓')
                    val (ul, dl) = if (downArrowIdx >= 0 && upArrowIdx >= 0 && downArrowIdx < upArrowIdx) {
                        speeds[1] to speeds[0]
                    } else if (lower.contains("download") && lower.contains("upload") &&
                        lower.indexOf("download") < lower.indexOf("upload")
                    ) {
                        speeds[1] to speeds[0]
                    } else {
                        // Bench.sh / GoECS / NodeQuality table standard order: Upload, Download
                        speeds[0] to speeds[1]
                    }
                    ulSamples += ul
                    dlSamples += dl
                    acc.speedNodes += SpeedNodeTelemetry(
                        nodeName = nodeName,
                        direction = "BOTH",
                        downloadMbps = dl,
                        uploadMbps = ul,
                        latencyMs = lat,
                    )
                } else {
                    val valMbps = speeds.first()
                    val isUploadOnly = line.contains('↑') || lower.contains("upload") || line.contains("上行") || line.contains("上传")
                    val isDownloadOnly = line.contains('↓') || lower.contains("download") || line.contains("下行") || line.contains("下载")
                    when {
                        isUploadOnly && !isDownloadOnly -> {
                            ulSamples += valMbps
                            acc.speedNodes += SpeedNodeTelemetry(nodeName, "UPLOAD", uploadMbps = valMbps, latencyMs = lat)
                        }
                        isDownloadOnly && !isUploadOnly -> {
                            dlSamples += valMbps
                            acc.speedNodes += SpeedNodeTelemetry(nodeName, "DOWNLOAD", downloadMbps = valMbps, latencyMs = lat)
                        }
                        else -> {
                            dlSamples += valMbps
                            acc.speedNodes += SpeedNodeTelemetry(nodeName, "BOTH", downloadMbps = valMbps, latencyMs = lat)
                        }
                    }
                }
            } else {
                // Latency/loss-only line (e.g. Ping summary)
                if (lower.contains("ping") || lower.contains("latency") || line.contains("延迟")) {
                    parseLatencyMs(line)?.let { latencySamples += it }
                }
                if (lower.contains("loss") || line.contains("丢包")) {
                    LOSS_VALUE_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                        ?.takeIf { it in 0.0..100.0 }
                        ?.let { lossSamples += it }
                }
            }
        }

        if (acc.downloadMbps == null && dlSamples.isNotEmpty()) {
            acc.downloadMbps = dlSamples.average()
        }
        if (acc.uploadMbps == null && ulSamples.isNotEmpty()) {
            acc.uploadMbps = ulSamples.average()
        }
        if (acc.icmpAvgMs == null && latencySamples.isNotEmpty()) {
            acc.icmpAvgMs = latencySamples.average()
        }
        if (acc.icmpLossPercent == null && lossSamples.isNotEmpty()) {
            acc.icmpLossPercent = lossSamples.average()
        }
    }

    private fun parseNextTraceOutput(
        text: String,
        serverHost: String,
        acc: SshExtractionAccumulator,
    ) {
        val parsedTrace = ReverseTracerouteCodec.parseOutput(
            plainOutput = text,
            targetIp = serverHost.ifBlank { "0.0.0.0" },
        )
        if (parsedTrace.hasValidHops) {
            acc.reverseHopCount = parsedTrace.totalHops
            acc.reverseTerminalMs = parsedTrace.terminalAvgMs
            acc.reversePeakHopLossPercent = parsedTrace.peakHopLossPercent
            acc.reverseHasRealPacketLoss = parsedTrace.hasRealPacketLoss
            acc.reverseBackboneTags.addAll(parsedTrace.dominantBackboneTags)
            if (parsedTrace.routeSignature.isNotBlank()) {
                acc.reverseRouteSignature = parsedTrace.routeSignature
            }
            val (detour, reason) = detectDetour(acc.countryCode, null, parsedTrace)
            if (detour && acc.explicitDetourReason == null) {
                acc.explicitDetourReason = reason
            }
        } else {
            parseBackrouteText(text, acc)
        }
    }

    private fun parseBackrouteText(
        text: String,
        acc: SshExtractionAccumulator,
    ) {
        val detectedTags = LinkedHashSet<BackboneRouteTag>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase(Locale.ROOT)

            val carrierKey = when {
                line.contains("电信") || lower.contains("chinatelecom") || lower.contains("telecom") -> "电信"
                line.contains("联通") || lower.contains("chinaunicom") || lower.contains("unicom") -> "联通"
                line.contains("移动") || lower.contains("chinamobile") || lower.contains("mobile") -> "移动"
                else -> null
            }

            val lineTags = detectBackboneTagsInText(line)
            detectedTags.addAll(lineTags)

            if (carrierKey != null && (line.contains(':') || line.contains('：') || line.contains("->") || line.contains("→") || lineTags.isNotEmpty())) {
                val summaryValue = if (lineTags.isNotEmpty()) {
                    lineTags.joinToString(" / ") { it.displayLabel }
                } else {
                    line.substringAfter(':', "")
                        .ifBlank { line.substringAfter('：', "") }
                        .ifBlank { line }
                        .trim()
                        .take(40)
                }
                if (summaryValue.isNotEmpty()) {
                    acc.threeNetBackroutes[carrierKey] = summaryValue
                }
            }

            if (line.contains("绕美") || lower.contains("detour") ||
                (lower.contains("united states") && (line.contains("回程") || line.contains("路由")))
            ) {
                acc.explicitDetourReason = acc.explicitDetourReason ?: "回程路由存在绕美节点"
            }
        }

        acc.reverseBackboneTags.addAll(detectedTags)
        if (acc.reverseRouteSignature == null) {
            if (acc.threeNetBackroutes.isNotEmpty()) {
                acc.reverseRouteSignature = acc.threeNetBackroutes.entries.joinToString(" · ") { (k, v) -> "$k:$v" }
            } else if (detectedTags.isNotEmpty()) {
                acc.reverseRouteSignature = detectedTags.joinToString(" -> ") { it.displayLabel }
            }
        }
    }

    private fun resolveSshDetour(acc: SshExtractionAccumulator): Pair<Boolean, String?> {
        if (acc.explicitDetourReason != null) return true to acc.explicitDetourReason
        val targetIsAsia = acc.countryCode?.uppercase(Locale.US) in ASIAN_TARGET_COUNTRIES
        if (targetIsAsia && acc.reverseBackboneTags.any { it in DETOUR_TRANSIT_TAGS }) {
            val ms = acc.reverseTerminalMs ?: acc.icmpAvgMs
            if (ms == null || ms > 130.0) {
                val tagsLabel = acc.reverseBackboneTags
                    .filter { it in DETOUR_TRANSIT_TAGS }
                    .joinToString("/") { it.displayLabel }
                return true to "亚太回程检测到国际绕行骨干 ($tagsLabel)"
            }
        }
        return false to null
    }

    private val IP_QUALITY_KEYWORD_CC_REGEX = Regex(
        """(?:(?i:country|region)|国家|地区)\s*[:：|]?\s*(?:\[([A-Z]{2})]|([A-Z]{2})\b)""",
    )
    private val IP_QUALITY_KEYWORD_ANCHOR_REGEX = Regex(
        """(?:(?i:country|region)|国家|地区)""",
    )
    private val IP_QUALITY_TOKEN_CC_REGEX = Regex("""\[([A-Z]{2})]|\b([A-Z]{2})\b""")
    private val NON_COUNTRY_TWO_LETTER_TOKENS = setOf("IP", "AS")

    private const val MEASUREMENT_UNIT_LOOKAHEAD =
        """(?!\s*(?:(?:ms|us|ns|s|bps|kbps|mbps|gbps|kbit/s|mbit/s|gbit/s|kb/s|mb/s|gb/s|kib/s|mib/s|gib/s|b/s|kb|mb|gb|tb|bytes?|hops?)(?![0-9A-Za-z_])|%))"""

    private val CN2_GIA_PREFIX_REGEX = Regex("""(?<![0-9.])59\.43\.(?:\d{1,3}|\*)(?![0-9A-Za-z_])""")
    private val CN2_GIA_ASN_REGEX = Regex("""(?<![0-9A-Za-z_])as\s*4809(?![0-9A-Za-z_])""", RegexOption.IGNORE_CASE)
    private val CT_163_TOKEN_REGEX = Regex(
        """(?<![0-9.A-Za-z_])(?:ct\s*163(?![0-9.A-Za-z_])|163\s*(?:骨干|线路|china)(?![0-9.A-Za-z_])|163(?![0-9.A-Za-z_])$MEASUREMENT_UNIT_LOOKAHEAD|as\s*4134(?![0-9.A-Za-z_])|4134(?![0-9.A-Za-z_])$MEASUREMENT_UNIT_LOOKAHEAD)|\bchinanet\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CU_9929_TOKEN_REGEX = Regex(
        """(?<![0-9.A-Za-z_])(?:(?:as|cu)\s*9929(?![0-9.A-Za-z_])|9929(?![0-9.A-Za-z_])$MEASUREMENT_UNIT_LOOKAHEAD)|\bcug\s*vip\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CU_4837_TOKEN_REGEX = Regex(
        """(?<![0-9.A-Za-z_])(?:(?:as|cu)\s*4837(?![0-9.A-Za-z_])|4837(?![0-9.A-Za-z_])$MEASUREMENT_UNIT_LOOKAHEAD)""",
        RegexOption.IGNORE_CASE,
    )
    private val CMIN2_TOKEN_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:cmin2|as\s*58807)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val CMI_TOKEN_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:cmi|as\s*58453)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val CMNET_TOKEN_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:cmnet|as\s*9808)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val IIJ_TOKEN_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:iij|as\s*2497)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val SOFTBANK_ASN_REGEX = Regex("""(?<![0-9A-Za-z_])as\s*17676(?![0-9A-Za-z_])""", RegexOption.IGNORE_CASE)
    private val NTT_TOKEN_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:ntt|as\s*2914)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val COGENT_TAG_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:cogent|as\s*174)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val HE_TAG_REGEX = Regex(
        """\bhe(?:\.net)?\b|(?<![0-9A-Za-z_])as\s*6939(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val LUMEN_TAG_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:lumen|level3|as\s*3356)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )
    private val PCCW_TAG_REGEX = Regex(
        """(?<![0-9A-Za-z_])(?:pccw|as\s*3491)(?![0-9A-Za-z_])""",
        RegexOption.IGNORE_CASE,
    )

    private fun extractCountryCodeFromIpQualityLine(line: String): String? {
        IP_QUALITY_KEYWORD_CC_REGEX.find(line)?.let { m ->
            val cc = m.groupValues[1].ifBlank { m.groupValues[2] }
            if (cc.length == 2 && cc !in NON_COUNTRY_TWO_LETTER_TOKENS) return cc
        }
        val anchorEnd = IP_QUALITY_KEYWORD_ANCHOR_REGEX.find(line)?.range?.last?.plus(1)
        if (anchorEnd != null && anchorEnd < line.length) {
            val afterKeyword = line.substring(anchorEnd)
            val afterMatches = IP_QUALITY_TOKEN_CC_REGEX.findAll(afterKeyword).toList()
            afterMatches.firstNotNullOfOrNull { m ->
                m.groupValues[1].takeIf { it.length == 2 && it !in NON_COUNTRY_TWO_LETTER_TOKENS }
            }?.let { return it }
            afterMatches.firstNotNullOfOrNull { m ->
                m.groupValues[2].takeIf { it.length == 2 && it !in NON_COUNTRY_TWO_LETTER_TOKENS }
            }?.let { return it }
        }
        val allMatches = IP_QUALITY_TOKEN_CC_REGEX.findAll(line).toList()
        allMatches.firstNotNullOfOrNull { m ->
            m.groupValues[1].takeIf { it.length == 2 && it !in NON_COUNTRY_TWO_LETTER_TOKENS }
        }?.let { return it }
        return allMatches.firstNotNullOfOrNull { m ->
            m.groupValues[2].takeIf { it.length == 2 && it !in NON_COUNTRY_TWO_LETTER_TOKENS }
        }
    }

    internal fun detectBackboneTagsInText(line: String): List<BackboneRouteTag> {
        val found = LinkedHashSet<BackboneRouteTag>()
        val lower = line.lowercase(Locale.ROOT)
        if (lower.contains("cn2 gia") || lower.contains("cn2gia") || CN2_GIA_ASN_REGEX.containsMatchIn(line) || CN2_GIA_PREFIX_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.CN2_GIA
        } else if (lower.contains("cn2 gt") || lower.contains("cn2gt") || (lower.contains("cn2") && !lower.contains("gia"))) {
            found += BackboneRouteTag.CN2_GT
        }
        if (CT_163_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.CT_163
        }
        if (CU_9929_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.CU_9929
        }
        if (CU_4837_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.CU_4837
        }
        if (CMIN2_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.CMIN2
        } else if (CMI_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.CMI
        }
        if (CMNET_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.CMNET
        }
        if (IIJ_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.IIJ
        }
        if (lower.contains("softbank") || lower.contains("软银") || SOFTBANK_ASN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.SOFTBANK
        }
        if (NTT_TOKEN_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.NTT
        }
        if (COGENT_TAG_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.COGENT
        }
        if (lower.contains("hurricane") || HE_TAG_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.HE
        }
        if (LUMEN_TAG_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.LUMEN
        }
        if (PCCW_TAG_REGEX.containsMatchIn(line)) {
            found += BackboneRouteTag.PCCW
        }
        return found.toList()
    }

    private val STREAMING_PLATFORM_PATTERNS: List<Pair<String, Pair<String, Regex>>> = listOf(
        "netflix" to ("Netflix" to Regex("""\bNetflix\b""", RegexOption.IGNORE_CASE)),
        "disney" to ("Disney+" to Regex("""\bDisney\s*\+?""", RegexOption.IGNORE_CASE)),
        "youtube" to ("YouTube" to Regex("""\bYouTube(?:\s+Premium)?\b""", RegexOption.IGNORE_CASE)),
        "tiktok" to ("TikTok" to Regex("""\bTikTok\b""", RegexOption.IGNORE_CASE)),
        "chatgpt" to ("ChatGPT" to Regex("""\b(?:ChatGPT|OpenAI)\b""", RegexOption.IGNORE_CASE)),
        "claude" to ("Claude" to Regex("""\bClaude(?:\s*AI)?\b""", RegexOption.IGNORE_CASE)),
        "gemini" to ("Gemini" to Regex("""\bGemini\b""", RegexOption.IGNORE_CASE)),
        "spotify" to ("Spotify" to Regex("""\bSpotify\b""", RegexOption.IGNORE_CASE)),
        "bilibili" to ("Bilibili" to Regex("""\bBilibili\b|哔哩哔哩""", RegexOption.IGNORE_CASE)),
        "primevideo" to ("Prime Video" to Regex("""\b(?:Amazon\s+)?Prime\s*Video\b""", RegexOption.IGNORE_CASE)),
        "hbomax" to ("Max / HBO" to Regex("""\b(?:HBO\s*)?Max\b""", RegexOption.IGNORE_CASE)),
    )

    internal fun parseStreamingUnlocksFromText(
        text: String,
        outMap: MutableMap<String, StreamingPlatformResult>,
    ) {
        val acc = SshExtractionAccumulator()
        parseStreamingUnlocksFromText(text, acc)
        outMap.putAll(acc.streamingByKey)
    }

    private fun parseStreamingUnlocksFromText(
        text: String,
        acc: SshExtractionAccumulator,
    ) {
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val platformEntry = STREAMING_PLATFORM_PATTERNS.firstOrNull { (_, pair) ->
                pair.second.containsMatchIn(line)
            } ?: continue

            val (platformKey, pair) = platformEntry
            val displayName = pair.first
            val rightSide = line.substringAfter(':', "").ifBlank { line.substringAfter('：', "") }
                .ifBlank { line.substringAfter('|', "") }
                .ifBlank { line }
                .trim()
            val lowerRight = rightSide.lowercase(Locale.ROOT)

            val status = when {
                rightSide.contains("仅自制") || rightSide.contains("自制剧") ||
                    lowerRight.contains("originals only") || lowerRight.contains("originals") ->
                    StreamingUnlockStatus.ORIGINALS_ONLY
                rightSide.contains("未解锁") || rightSide.contains("不支持") || rightSide.contains("禁止") ||
                    rightSide.contains("失败") || rightSide.contains("NO", ignoreCase = false) ||
                    Regex("""\b(?:no|failed|blocked|unavailable|denied)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rightSide) ||
                    rightSide.contains("✘") || rightSide.contains("✗") || rightSide.contains("❌") ->
                    StreamingUnlockStatus.LOCKED
                rightSide.contains("原生解锁") || rightSide.contains("完整解锁") || rightSide.contains("解锁") ||
                    Regex("""\b(?:yes|unlocked|native|available)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rightSide) ||
                    rightSide.contains("✔") || rightSide.contains("✓") || rightSide.contains("✅") ->
                    StreamingUnlockStatus.UNLOCKED
                else -> StreamingUnlockStatus.UNKNOWN
            }
            if (status == StreamingUnlockStatus.UNKNOWN) continue

            val regionCode = extractUnlockRegionCode(rightSide)
            acc.streamingByKey[platformKey] = StreamingPlatformResult(
                platformKey = platformKey,
                displayName = displayName,
                status = status,
                regionCode = regionCode,
                rawNote = rightSide.take(48),
            )
        }
    }

    private fun extractUnlockRegionCode(text: String): String? {
        Regex("""Region\s*[:：]\s*([A-Z]{2})\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].uppercase(Locale.US)
        }
        Regex("""[\[(（]\s*([A-Z]{2})\s*[\])）]""").find(text)?.let {
            return it.groupValues[1].uppercase(Locale.US)
        }
        Regex("""\b(?:Yes|Unlocked|解锁)\s+([A-Z]{2})\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].uppercase(Locale.US)
        }
        return null
    }

    private fun extractSpeedNodeName(line: String): String {
        val beforeFirstSpeed = SPEED_VALUE_REGEX.find(line)?.let { m ->
            line.substring(0, m.range.first)
        } ?: line
        return beforeFirstSpeed
            .replace('|', ' ')
            .replace(':', ' ')
            .replace('：', ' ')
            .trim()
            .take(28)
            .ifBlank { "测速节点" }
    }

    private fun parseSpeedToMbps(text: String): Double? {
        val match = SPEED_VALUE_REGEX.find(text) ?: return null
        return convertSpeedToMbps(match.groupValues[1], match.groupValues[2])
    }

    private fun convertSpeedToMbps(rawVal: String, unit: String): Double? {
        val num = rawVal.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return when (unit.lowercase(Locale.ROOT)) {
            "gbit/s", "gbps", "gbits/sec" -> num * 1000.0
            "mbit/s", "mbps", "mbits/sec" -> num
            "kbit/s", "kbps", "kbits/sec" -> num / 1000.0
            else -> num
        }
    }

    private fun convertIoToMbPerSec(rawVal: String, unit: String): Double? {
        val num = rawVal.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return when (unit.lowercase(Locale.ROOT)) {
            "gb/s", "gib/s" -> num * 1000.0
            "mb/s", "mib/s" -> num
            "kb/s" -> num / 1000.0
            else -> num
        }
    }

    private fun parseLatencyMs(text: String): Double? =
        LATENCY_VALUE_REGEX.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 && it <= 30_000.0 }
}
