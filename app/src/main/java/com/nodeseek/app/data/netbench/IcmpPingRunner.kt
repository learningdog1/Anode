package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IcmpPingConfig
import com.nodeseek.app.domain.netbench.IcmpPingProgress
import com.nodeseek.app.domain.netbench.IcmpPingSample
import com.nodeseek.app.domain.netbench.IcmpPingSummary
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.ResolvedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Request describing an external ping command invocation.
 */
data class PingProcessRequest(
    val command: List<String>,
    val timeoutMs: Long,
)

/**
 * Streaming events emitted by [PingProcessExecutor].
 */
sealed class PingExecutionEvent {
    data class StdoutLine(val line: String) : PingExecutionEvent()
    data class StderrLine(val line: String) : PingExecutionEvent()
    data class Exited(val exitCode: Int) : PingExecutionEvent()
}

/**
 * Process execution abstraction allowing [IcmpPingRunner] to be deterministically tested on JVM.
 */
fun interface PingProcessExecutor {
    fun execute(request: PingProcessRequest): Flow<PingExecutionEvent>
}

/**
 * Default production executor invoking `/system/bin/ping` (or `ping6`) with safe cancellation and timeout cleanup.
 */
object SystemPingProcessExecutor : PingProcessExecutor {
    override fun execute(request: PingProcessRequest): Flow<PingExecutionEvent> = channelFlow {
        val process = try {
            ProcessBuilder(request.command).start()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            send(PingExecutionEvent.StderrLine(t.message ?: t.javaClass.simpleName))
            send(PingExecutionEvent.Exited(-1))
            return@channelFlow
        }

        val stderrJob = launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        send(PingExecutionEvent.StderrLine(line))
                    }
                }
            }
        }

        val watchdogJob = launch {
            delay(request.timeoutMs.coerceAtLeast(1_000L))
            runCatching { process.destroyForcibly() }
        }

        try {
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        send(PingExecutionEvent.StdoutLine(line))
                    }
                }
            }
            stderrJob.join()
            val exitCode = withContext(Dispatchers.IO) {
                process.waitFor()
            }
            watchdogJob.cancel()
            send(PingExecutionEvent.Exited(exitCode))
        } finally {
            watchdogJob.cancel()
            stderrJob.cancel()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
            }
        }
    }
}

/**
 * Pure functions for building `/system/bin/ping` commands and parsing per-packet + summary output.
 */
object IcmpPingParser {

    const val DEFAULT_PING_BINARY = "/system/bin/ping"
    const val DEFAULT_PING6_BINARY = "/system/bin/ping6"

    data class PingPacketStats(
        val sent: Int,
        val received: Int,
        val lossPercent: Double,
    )

    data class PingRttStats(
        val minMs: Double,
        val avgMs: Double,
        val maxMs: Double,
        val mdevMs: Double?,
    )

    enum class PingFailureKind {
        INTERFACE_BIND_REJECTED,
        INTERVAL_REJECTED,
        BINARY_OR_FLAG_UNSUPPORTED,
        PERMISSION_DENIED,
        NETWORK_UNREACHABLE,
        UNKNOWN_HOST,
        TIMEOUT_OR_UNREACHABLE,
        OTHER,
    }

    private val REPLY_LINE_GUARD_REGEX = Regex("""(?i)(?:\bbytes\s+from\b|\bicmp_seq\s*[=\s]|\bseq\s*=)""")
    private val TIME_EQ_REGEX = Regex("""(?i)\btime\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*ms\b""")
    private val TIME_LT_REGEX = Regex("""(?i)\btime\s*<\s*([0-9]+(?:\.[0-9]+)?)\s*ms\b""")
    private val SEQ_REGEX = Regex("""(?i)\b(?:icmp_)?seq\s*[=\s]\s*(\d+)""")
    private val TTL_REGEX = Regex("""(?i)\b(?:ttl|hlim)\s*=\s*(\d+)""")

    private val TIMEOUT_SEQ_REGEX = Regex(
        """(?i)(?:request\s+timeout\s+for|no\s+answer\s+yet\s+for)\s+(?:icmp_)?seq\s*[=\s]\s*(\d+)""",
    )
    private val ICMP_ERROR_SEQ_REGEX = Regex(
        """(?i)^from\s+.+?\b(?:icmp_)?seq\s*[=\s]\s*(\d+)\b.*?(destination\s+.+|time\s+to\s+live\s+exceeded|packet\s+filtered|administratively\s+prohibited)""",
    )

    private val PACKET_STATS_REGEX = Regex(
        """(?i)(\d+)\s+packets?\s+transmitted,\s+(\d+)\s+(?:packets?\s+)?received,.*?([0-9]+(?:\.[0-9]+)?)%\s+packet\s+loss""",
    )
    private val RTT_STATS_REGEX = Regex(
        """(?i)(?:rtt|round-trip)\s+min/avg/max(?:/(?:mdev|stddev))?\s*=\s*([0-9]+(?:\.[0-9]+)?)/([0-9]+(?:\.[0-9]+)?)/([0-9]+(?:\.[0-9]+)?)(?:/([0-9]+(?:\.[0-9]+)?))?\s*ms""",
    )

    /**
     * Builds the argument list for `/system/bin/ping` or `/system/bin/ping6`.
     */
    fun buildCommand(
        targetIp: String,
        ipFamily: IpFamily,
        count: Int,
        intervalMs: Long,
        timeoutSec: Int,
        interfaceName: String? = null,
        preferPing6Binary: Boolean = false,
        includeNumericFlag: Boolean = true,
    ): List<String> = buildList {
        val usePing6 = ipFamily == IpFamily.IPV6 && preferPing6Binary
        add(if (usePing6) DEFAULT_PING6_BINARY else DEFAULT_PING_BINARY)
        if (ipFamily == IpFamily.IPV6 && !usePing6) {
            add("-6")
        }
        if (includeNumericFlag) {
            add("-n")
        }
        add("-c")
        add(count.coerceAtLeast(1).toString())
        if (intervalMs >= 200L) {
            add("-i")
            add(formatIntervalSeconds(intervalMs))
        }
        add("-W")
        add(timeoutSec.coerceAtLeast(1).toString())
        if (!interfaceName.isNullOrBlank()) {
            add("-I")
            add(interfaceName.trim())
        }
        add(targetIp.trim())
    }

    internal fun formatIntervalSeconds(intervalMs: Long): String {
        val clamped = intervalMs.coerceAtLeast(200L)
        if (clamped % 1000L == 0L) {
            return (clamped / 1000L).toString()
        }
        val formatted = String.format(Locale.US, "%.2f", clamped / 1000.0)
        return formatted.trimEnd('0').trimEnd('.')
    }

    /**
     * Parses a single `ping` stdout line representing either an echo reply or a per-seq failure.
     * Returns `null` for headers, blank lines, and summary lines.
     */
    fun parseLine(line: String, fallbackSeq: Int = 1): IcmpPingSample? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        TIMEOUT_SEQ_REGEX.find(trimmed)?.let { match ->
            val seq = match.groupValues[1].toIntOrNull() ?: fallbackSeq
            return IcmpPingSample(
                seq = seq,
                success = false,
                rttMs = null,
                errorMessage = "请求超时",
            )
        }

        ICMP_ERROR_SEQ_REGEX.find(trimmed)?.let { match ->
            val seq = match.groupValues[1].toIntOrNull() ?: fallbackSeq
            val detail = match.groupValues[2].lowercase(Locale.US)
            val errMsg = when {
                "time to live exceeded" in detail -> "TTL 超时 (Time to live exceeded)"
                "packet filtered" in detail || "prohibited" in detail -> "ICMP 被防火墙过滤"
                else -> "目标主机不可达"
            }
            return IcmpPingSample(
                seq = seq,
                success = false,
                rttMs = null,
                errorMessage = errMsg,
            )
        }

        if (!REPLY_LINE_GUARD_REGEX.containsMatchIn(trimmed)) return null

        val rttMs = TIME_EQ_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.coerceAtLeast(0.01)
            ?: TIME_LT_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it / 2.0).coerceAtLeast(0.05) }
            ?: return null

        val seq = SEQ_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: fallbackSeq
        val ttl = TTL_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()

        return IcmpPingSample(
            seq = seq,
            success = true,
            rttMs = rttMs,
            ttl = ttl,
        )
    }

    /**
     * Parses the transmitted/received/loss summary line printed by iputils/toybox/BSD `ping`.
     */
    fun parsePacketStatsLine(line: String): PingPacketStats? {
        val match = PACKET_STATS_REGEX.find(line.trim()) ?: return null
        val sent = match.groupValues[1].toIntOrNull() ?: return null
        val received = match.groupValues[2].toIntOrNull() ?: return null
        val loss = match.groupValues[3].toDoubleOrNull() ?: return null
        return PingPacketStats(
            sent = sent.coerceAtLeast(0),
            received = received.coerceAtLeast(0),
            lossPercent = loss.coerceIn(0.0, 100.0),
        )
    }

    /**
     * Parses the `rtt min/avg/max/mdev` or `round-trip min/avg/max/stddev` line.
     */
    fun parseRttStatsLine(line: String): PingRttStats? {
        val match = RTT_STATS_REGEX.find(line.trim()) ?: return null
        val min = match.groupValues[1].toDoubleOrNull() ?: return null
        val avg = match.groupValues[2].toDoubleOrNull() ?: return null
        val max = match.groupValues[3].toDoubleOrNull() ?: return null
        val mdev = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        return PingRttStats(
            minMs = min,
            avgMs = avg,
            maxMs = max,
            mdevMs = mdev,
        )
    }

    /**
     * Fills missing `icmp_seq` slots as lost packets so the live/final RTT chart reflects packet loss accurately.
     */
    fun reconcileSamples(samples: List<IcmpPingSample>, expectedCount: Int): List<IcmpPingSample> {
        if (expectedCount <= 0) return samples
        if (samples.isEmpty()) {
            return (1..expectedCount).map { seq ->
                IcmpPingSample(seq = seq, success = false, errorMessage = "丢包/超时")
            }
        }

        val deduplicated = LinkedHashMap<Int, IcmpPingSample>(samples.size)
        for (sample in samples) {
            val existing = deduplicated[sample.seq]
            if (existing == null || (!existing.success && sample.success)) {
                deduplicated[sample.seq] = sample
            }
        }

        val startSeq = if (deduplicated.containsKey(0)) 0 else 1
        val endSeqExclusive = startSeq + expectedCount
        return buildList(expectedCount) {
            for (seq in startSeq until endSeqExclusive) {
                add(deduplicated[seq] ?: IcmpPingSample(seq = seq, success = false, errorMessage = "丢包/超时"))
            }
            for ((seq, sample) in deduplicated) {
                if (seq < startSeq || seq >= endSeqExclusive) {
                    add(sample)
                }
            }
        }
    }

    /**
     * Computes aggregated [IcmpPingSummary], preferring authoritative summary stats from `ping`
     * and falling back to sample-derived min/avg/max/mdev when absent (e.g. BusyBox or cancelled run).
     */
    fun computeSummary(
        resolvedTarget: ResolvedTarget?,
        samples: List<IcmpPingSample>,
        packetStats: PingPacketStats? = null,
        rttStats: PingRttStats? = null,
        bindingMode: IcmpNetworkBindingMode = IcmpNetworkBindingMode.SYSTEM_DEFAULT,
        boundInterfaceName: String? = null,
        networkBindingNote: String? = null,
        errorMessage: String? = null,
    ): IcmpPingSummary {
        val sent = packetStats?.sent ?: samples.size
        val rtts = samples.mapNotNull { if (it.success) it.rttMs else null }
        val received = packetStats?.received ?: rtts.size
        val lossPercent = packetStats?.lossPercent ?: if (sent == 0) {
            100.0
        } else {
            (((sent - received).coerceAtLeast(0)) * 100.0) / sent.toDouble()
        }

        val sampleComputed = computeRttStats(rtts)
        if (received == 0 || (rttStats == null && sampleComputed == null)) {
            return IcmpPingSummary(
                resolvedTarget = resolvedTarget,
                sent = sent,
                received = 0,
                lossPercent = if (sent == 0) 100.0 else lossPercent,
                minMs = null,
                avgMs = null,
                maxMs = null,
                jitterMdevMs = null,
                samples = samples,
                bindingMode = bindingMode,
                boundInterfaceName = boundInterfaceName,
                networkBindingNote = networkBindingNote,
                errorMessage = errorMessage ?: samples.lastOrNull()?.errorMessage,
            )
        }

        return IcmpPingSummary(
            resolvedTarget = resolvedTarget,
            sent = sent,
            received = received,
            lossPercent = lossPercent,
            minMs = rttStats?.minMs ?: sampleComputed?.minMs,
            avgMs = rttStats?.avgMs ?: sampleComputed?.avgMs,
            maxMs = rttStats?.maxMs ?: sampleComputed?.maxMs,
            jitterMdevMs = rttStats?.mdevMs ?: sampleComputed?.mdevMs,
            samples = samples,
            bindingMode = bindingMode,
            boundInterfaceName = boundInterfaceName,
            networkBindingNote = networkBindingNote,
            errorMessage = errorMessage,
        )
    }

    /**
     * Parses a complete multi-line `ping` output into an [IcmpPingSummary].
     */
    fun parseOutput(
        lines: List<String>,
        resolvedTarget: ResolvedTarget? = null,
        bindingMode: IcmpNetworkBindingMode = IcmpNetworkBindingMode.SYSTEM_DEFAULT,
        boundInterfaceName: String? = null,
        networkBindingNote: String? = null,
    ): IcmpPingSummary {
        val parsedSamples = mutableListOf<IcmpPingSample>()
        var packetStats: PingPacketStats? = null
        var rttStats: PingRttStats? = null

        for (line in lines) {
            val pkt = parsePacketStatsLine(line)
            if (pkt != null) {
                packetStats = pkt
                continue
            }
            val rtt = parseRttStatsLine(line)
            if (rtt != null) {
                rttStats = rtt
                continue
            }
            val sample = parseLine(line, fallbackSeq = parsedSamples.size + 1)
            if (sample != null) {
                parsedSamples += sample
            }
        }

        val finalSamples = if (packetStats != null && packetStats.sent > 0) {
            reconcileSamples(parsedSamples, packetStats.sent)
        } else {
            parsedSamples
        }

        val errorMsg = if ((packetStats?.received ?: finalSamples.count { it.success }) == 0 && finalSamples.isEmpty()) {
            lines.firstOrNull { it.isNotBlank() && !it.startsWith("PING ", ignoreCase = true) && !it.startsWith("---") }
        } else {
            null
        }

        return computeSummary(
            resolvedTarget = resolvedTarget,
            samples = finalSamples,
            packetStats = packetStats,
            rttStats = rttStats,
            bindingMode = bindingMode,
            boundInterfaceName = boundInterfaceName,
            networkBindingNote = networkBindingNote,
            errorMessage = errorMsg,
        )
    }

    /**
     * Classifies why a `ping` invocation exited with zero received packets so [IcmpPingRunner]
     * can degrade cleanly (drop `-I`, relax `-i`, switch `ping6` -> `ping -6`, or report error).
     */
    fun classifyFailure(
        outputLines: List<String>,
        packetStats: PingPacketStats?,
        hadInterfaceFlag: Boolean,
        hadFractionalInterval: Boolean,
        hadNumericFlag: Boolean = true,
        throwable: Throwable? = null,
    ): PingFailureKind {
        if (packetStats != null && packetStats.sent > 0 && packetStats.received == 0) {
            return PingFailureKind.TIMEOUT_OR_UNREACHABLE
        }

        val combined = buildString {
            outputLines.forEach { append(it).append(' ') }
            if (throwable?.message != null) append(throwable.message)
        }.lowercase(Locale.US)

        if (hadInterfaceFlag && (
                "so_bindtodevice" in combined ||
                    "unknown iface" in combined ||
                    "no such device" in combined ||
                    "cannot assign requested address" in combined ||
                    "operation not permitted" in combined ||
                    "permission denied" in combined ||
                    "eperm" in combined ||
                    "eacces" in combined ||
                    "network is unreachable" in combined ||
                    "enetunreach" in combined ||
                    ("bind" in combined && "invalid" in combined)
                )
        ) {
            return PingFailureKind.INTERFACE_BIND_REJECTED
        }

        if (hadFractionalInterval && (
                "cannot flood" in combined ||
                    "minimal interval" in combined ||
                    "bad timing interval" in combined ||
                    "invalid interval" in combined ||
                    ("interval" in combined && ("invalid" in combined || "bad" in combined || "integer" in combined))
                )
        ) {
            return PingFailureKind.INTERVAL_REJECTED
        }

        if (throwable is IOException ||
            "no such file" in combined ||
            "cannot run program" in combined ||
            "not found" in combined ||
            "invalid option" in combined ||
            "unrecognized option" in combined ||
            "illegal option" in combined ||
            "usage:" in combined ||
            (hadNumericFlag && "-n" in combined && "option" in combined)
        ) {
            return PingFailureKind.BINARY_OR_FLAG_UNSUPPORTED
        }

        if ("permission denied" in combined ||
            "operation not permitted" in combined ||
            "eperm" in combined ||
            "eacces" in combined ||
            "icmp open socket" in combined
        ) {
            return PingFailureKind.PERMISSION_DENIED
        }

        if ("network is unreachable" in combined ||
            "enetunreach" in combined ||
            "no route to host" in combined ||
            "ehostunreach" in combined
        ) {
            return PingFailureKind.NETWORK_UNREACHABLE
        }

        if ("unknown host" in combined ||
            "bad address" in combined ||
            "name or service not known" in combined ||
            "temporary failure in name resolution" in combined
        ) {
            return PingFailureKind.UNKNOWN_HOST
        }

        return PingFailureKind.OTHER
    }
}

/**
 * Executes real-time ICMP ping via Android `/system/bin/ping` (and `/system/bin/ping6`),
 * complying with [NetBenchNetworkBridge] routing/DNS rules and degrading cleanly when
 * non-root subprocess restrictions or interface-binding limits apply.
 */
class IcmpPingRunner(
    private val resolver: NetBenchTargetResolver = NetBenchTargetResolver(),
    private val executor: PingProcessExecutor = SystemPingProcessExecutor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
    private val ping6BinaryAvailable: () -> Boolean = { File(IcmpPingParser.DEFAULT_PING6_BINARY).exists() },
) {

    fun run(config: IcmpPingConfig): Flow<IcmpPingProgress> = flow {
        val plannedCount = config.normalizedProbeCount
        val timeoutSec = config.normalizedTimeoutSec
        val initialIntervalMs = config.normalizedIntervalMs

        val endpoint = resolver.resolve(
            rawTarget = config.target,
            defaultPort = 80,
            preferredFamily = config.preferredFamily,
            useProxy = config.useProxy,
        ).getOrElse { err ->
            val msg = err.message ?: "无法解析测试目标"
            emit(
                failedProgress(
                    resolvedTarget = null,
                    plannedCount = plannedCount,
                    errorMessage = msg,
                ),
            )
            return@flow
        }

        val binding = networkBridge.inspectCurrentNetworks()
        val selectedChannel = when (val selection = binding.selectChannel(config.useProxy)) {
            is ChannelSelectionResult.Selected -> selection.channel
            is ChannelSelectionResult.Blocked -> {
                emit(
                    failedProgress(
                        resolvedTarget = endpoint.resolvedTarget,
                        plannedCount = plannedCount,
                        errorMessage = selection.message,
                    ),
                )
                return@flow
            }
        }

        val directDescriptor = binding.plan.directUnderlying
        val vpnActive = binding.plan.isVpnActive
        val wantDirectPhysical = !config.useProxy && selectedChannel.isDirectPhysical
        val candidateInterface = if (wantDirectPhysical && (vpnActive || binding.plan.activeDefault?.networkId != selectedChannel.networkId)) {
            directDescriptor?.interfaceName?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        var currentInterface: String? = candidateInterface
        var currentIntervalMs: Long = initialIntervalMs
        var preferPing6: Boolean = endpoint.resolvedTarget.ipFamily == IpFamily.IPV6 && ping6BinaryAvailable()
        var includeNumericFlag = true
        var bindingMode = when {
            currentInterface != null -> IcmpNetworkBindingMode.INTERFACE_BOUND
            wantDirectPhysical && vpnActive -> IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK
            else -> IcmpNetworkBindingMode.SYSTEM_DEFAULT
        }
        var bindingNote: String? = if (wantDirectPhysical && vpnActive && currentInterface == null) {
            "无法对 /system/bin/ping 子进程调用 Network.bindSocket 绑定底层网络，当前跟随系统默认路由（开启 VPN 时可能走代理路由）；建议以 TCPing 作为直连延迟参考"
        } else if (wantDirectPhysical && vpnActive && currentInterface != null) {
            "已尝试通过 -I $currentInterface 指定底层网卡；注意系统 ping 进程无法调用 Network.bindSocket，若受策略路由影响请以 TCPing 为准"
        } else {
            null
        }

        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()

            val command = IcmpPingParser.buildCommand(
                targetIp = endpoint.resolvedTarget.resolvedIp,
                ipFamily = endpoint.resolvedTarget.ipFamily,
                count = plannedCount,
                intervalMs = currentIntervalMs,
                timeoutSec = timeoutSec,
                interfaceName = currentInterface,
                preferPing6Binary = preferPing6,
                includeNumericFlag = includeNumericFlag,
            )
            val processTimeoutMs = (plannedCount * (currentIntervalMs + 150L)) + (timeoutSec * 1_000L) + 4_000L

            val liveSamples = mutableListOf<IcmpPingSample>()
            val stderrLines = mutableListOf<String>()
            val nonSampleStdoutLines = mutableListOf<String>()
            var packetStats: IcmpPingParser.PingPacketStats? = null
            var rttStats: IcmpPingParser.PingRttStats? = null
            var exitCode = -1

            executor.execute(
                PingProcessRequest(
                    command = command,
                    timeoutMs = processTimeoutMs,
                ),
            ).collect { event ->
                when (event) {
                    is PingExecutionEvent.StdoutLine -> {
                        val line = event.line
                        val pkt = IcmpPingParser.parsePacketStatsLine(line)
                        if (pkt != null) {
                            packetStats = pkt
                            return@collect
                        }
                        val rtt = IcmpPingParser.parseRttStatsLine(line)
                        if (rtt != null) {
                            rttStats = rtt
                            return@collect
                        }
                        val sample = IcmpPingParser.parseLine(line, fallbackSeq = liveSamples.size + 1)
                        if (sample != null) {
                            liveSamples += sample
                            val snapshot = liveSamples.toList()
                            val liveSummary = IcmpPingParser.computeSummary(
                                resolvedTarget = endpoint.resolvedTarget,
                                samples = snapshot,
                                bindingMode = bindingMode,
                                boundInterfaceName = currentInterface,
                                networkBindingNote = bindingNote,
                            )
                            emit(
                                IcmpPingProgress(
                                    resolvedTarget = endpoint.resolvedTarget,
                                    totalPlanned = plannedCount,
                                    latestSample = sample,
                                    samples = snapshot,
                                    summary = liveSummary,
                                    isFinished = false,
                                ),
                            )
                        } else if (line.isNotBlank()) {
                            nonSampleStdoutLines += line
                        }
                    }

                    is PingExecutionEvent.StderrLine -> {
                        if (event.line.isNotBlank()) {
                            stderrLines += event.line
                        }
                    }

                    is PingExecutionEvent.Exited -> {
                        exitCode = event.exitCode
                    }
                }
            }

            val receivedCount = packetStats?.received ?: liveSamples.count { it.success }
            if (receivedCount > 0) {
                val expectedTotal = packetStats?.sent?.takeIf { it > 0 } ?: plannedCount
                val finalSamples = IcmpPingParser.reconcileSamples(liveSamples, expectedTotal)
                val finalSummary = IcmpPingParser.computeSummary(
                    resolvedTarget = endpoint.resolvedTarget,
                    samples = finalSamples,
                    packetStats = packetStats,
                    rttStats = rttStats,
                    bindingMode = bindingMode,
                    boundInterfaceName = currentInterface,
                    networkBindingNote = bindingNote,
                )
                emit(
                    IcmpPingProgress(
                        resolvedTarget = endpoint.resolvedTarget,
                        totalPlanned = plannedCount,
                        latestSample = finalSamples.lastOrNull(),
                        samples = finalSamples,
                        summary = finalSummary,
                        isFinished = true,
                    ),
                )
                return@flow
            }

            // Zero received packets: determine whether we can degrade flags/binary or should emit failure
            val failureKind = IcmpPingParser.classifyFailure(
                outputLines = stderrLines + nonSampleStdoutLines,
                packetStats = packetStats,
                hadInterfaceFlag = currentInterface != null,
                hadFractionalInterval = currentIntervalMs < 1_000L,
                hadNumericFlag = includeNumericFlag,
            )

            if (attempt < maxAttempts) {
                when (failureKind) {
                    IcmpPingParser.PingFailureKind.INTERFACE_BIND_REJECTED -> {
                        val failedIface = currentInterface
                        currentInterface = null
                        bindingMode = IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK
                        bindingNote = if (vpnActive) {
                            "无法将 /system/bin/ping 子进程绑定到底层网卡（$failedIface），已回退为系统默认路由；VPN 开启时 ICMP 可能走代理路由，建议以 TCPing 作为直连延迟参考"
                        } else {
                            "系统禁止 /system/bin/ping 绑定指定网卡（$failedIface），已回退为系统默认路由"
                        }
                        continue
                    }

                    IcmpPingParser.PingFailureKind.INTERVAL_REJECTED -> {
                        currentIntervalMs = 1_000L
                        continue
                    }

                    IcmpPingParser.PingFailureKind.BINARY_OR_FLAG_UNSUPPORTED -> {
                        if (endpoint.resolvedTarget.ipFamily == IpFamily.IPV6 && preferPing6) {
                            preferPing6 = false
                            continue
                        }
                        if (includeNumericFlag && "-n" in (stderrLines + nonSampleStdoutLines).joinToString(" ").lowercase(Locale.US)) {
                            includeNumericFlag = false
                            continue
                        }
                    }

                    else -> Unit
                }
            }

            val finalSamples = if ((packetStats?.sent ?: 0) > 0) {
                IcmpPingParser.reconcileSamples(liveSamples, packetStats!!.sent)
            } else {
                liveSamples.toList()
            }
            val rawDetail = (stderrLines + nonSampleStdoutLines)
                .firstOrNull { !it.startsWith("PING ", ignoreCase = true) && !it.startsWith("---") }
                ?.take(120)

            val friendlyError = when (failureKind) {
                IcmpPingParser.PingFailureKind.TIMEOUT_OR_UNREACHABLE ->
                    "全部请求超时或目标禁 Ping（100% 丢包）"
                IcmpPingParser.PingFailureKind.PERMISSION_DENIED ->
                    "系统限制无 Root 进程发送 ICMP 包${rawDetail?.let { " ($it)" } ?: ""}，请改用 TCPing 测试"
                IcmpPingParser.PingFailureKind.NETWORK_UNREACHABLE ->
                    if (vpnActive && !config.useProxy) {
                        "底层网络不可达（VPN 拦截或策略路由限制）；可尝试开启「经由代理测试」或改用 TCPing"
                    } else {
                        "目标网络不可达${rawDetail?.let { " ($it)" } ?: ""}"
                    }
                IcmpPingParser.PingFailureKind.UNKNOWN_HOST ->
                    "Ping 无法解析目标地址${rawDetail?.let { " ($it)" } ?: ""}"
                IcmpPingParser.PingFailureKind.BINARY_OR_FLAG_UNSUPPORTED ->
                    "当前环境不支持系统 ping 命令（${command.first()}），请改用 TCPing 测试"
                IcmpPingParser.PingFailureKind.INTERFACE_BIND_REJECTED ->
                    "系统拒绝绑定网卡执行 ping${rawDetail?.let { " ($it)" } ?: ""}，请改用 TCPing 测试"
                IcmpPingParser.PingFailureKind.INTERVAL_REJECTED ->
                    "当前系统 ping 不支持该发包间隔（exit=$exitCode），请改用 TCPing 测试"
                IcmpPingParser.PingFailureKind.OTHER ->
                    rawDetail ?: "ICMP Ping 执行失败（退出码 $exitCode）"
            }

            val finalSummary = IcmpPingParser.computeSummary(
                resolvedTarget = endpoint.resolvedTarget,
                samples = finalSamples,
                packetStats = packetStats,
                rttStats = rttStats,
                bindingMode = bindingMode,
                boundInterfaceName = currentInterface,
                networkBindingNote = bindingNote,
                errorMessage = friendlyError,
            )
            emit(
                IcmpPingProgress(
                    resolvedTarget = endpoint.resolvedTarget,
                    totalPlanned = plannedCount,
                    latestSample = finalSamples.lastOrNull(),
                    samples = finalSamples,
                    summary = finalSummary,
                    isFinished = true,
                    errorMessage = friendlyError,
                ),
            )
            return@flow
        }
    }.flowOn(ioDispatcher)

    private fun failedProgress(
        resolvedTarget: ResolvedTarget?,
        plannedCount: Int,
        errorMessage: String,
    ): IcmpPingProgress = IcmpPingProgress(
        resolvedTarget = resolvedTarget,
        totalPlanned = plannedCount,
        latestSample = null,
        samples = emptyList(),
        summary = IcmpPingSummary(
            resolvedTarget = resolvedTarget,
            sent = 0,
            received = 0,
            lossPercent = 100.0,
            minMs = null,
            avgMs = null,
            maxMs = null,
            jitterMdevMs = null,
            samples = emptyList(),
            errorMessage = errorMessage,
        ),
        isFinished = true,
        errorMessage = errorMessage,
    )
}
