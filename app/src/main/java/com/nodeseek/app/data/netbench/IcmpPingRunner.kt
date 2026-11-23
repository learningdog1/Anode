package com.nodeseek.app.data.netbench

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructCmsghdr
import android.system.StructMsghdr
import android.system.StructPollfd
import android.system.StructTimeval
import androidx.annotation.RequiresApi
import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IcmpPingConfig
import com.nodeseek.app.domain.netbench.IcmpPingProgress
import com.nodeseek.app.domain.netbench.IcmpPingSample
import com.nodeseek.app.domain.netbench.IcmpPingSummary
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.ResolvedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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

        val finishedNormally = AtomicBoolean(false)
        val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finishedNormally.get()) {
                    runCatching { process.destroyForcibly() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
            }
        }

        val timedOut = AtomicBoolean(false)
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
            timedOut.set(true)
            runCatching { process.destroyForcibly() }
        }

        try {
            withContext(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            send(PingExecutionEvent.StdoutLine(line))
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ioe: IOException) {
                    // On Android ART, destroyForcibly() closes pipe FDs from another thread while
                    // readLine() is blocked, throwing "read interrupted by close() on another thread".
                    if (!isActive) throw CancellationException("Ping cancelled", ioe)
                    if (timedOut.get()) {
                        send(PingExecutionEvent.StderrLine("ping 执行超时 (${request.timeoutMs}ms)"))
                    }
                }
            }
            watchdogJob.cancel()
            stderrJob.join()
            val exitCode = withContext(Dispatchers.IO) {
                runCatching { process.waitFor() }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    -1
                }
            }
            finishedNormally.set(true)
            cancelWatcher.cancel()
            send(PingExecutionEvent.Exited(exitCode))
        } finally {
            finishedNormally.set(true)
            cancelWatcher.cancel()
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
    const val MAX_VALID_RTT_MS = 30_000.0

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

    private val DUP_REPLY_REGEX = Regex("""(?i)\(\s*DUP!\s*\)""")
    private val WRONG_DATA_REGEX = Regex("""(?i)\bwrong\s+data\s+byte\b""")
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
    fun parseLine(
        line: String,
        fallbackSeq: Int = 1,
        maxValidRttMs: Double = MAX_VALID_RTT_MS,
    ): IcmpPingSample? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (DUP_REPLY_REGEX.containsMatchIn(trimmed) || WRONG_DATA_REGEX.containsMatchIn(trimmed)) {
            return null
        }

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

        val rawRtt = TIME_EQ_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: TIME_LT_REGEX.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it / 2.0 }
            ?: return null

        val seq = SEQ_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: fallbackSeq
        val ttl = TTL_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()

        if (!rawRtt.isFinite() || rawRtt < 0.0 || rawRtt > maxValidRttMs) {
            // Packet arrived, but payload timestamp underflowed/corrupted (e.g. emulator NAT mangling)
            return IcmpPingSample(
                seq = seq,
                success = true,
                rttMs = null,
                ttl = ttl,
                errorMessage = "RTT 异常已忽略",
            )
        }

        return IcmpPingSample(
            seq = seq,
            success = true,
            rttMs = rawRtt.coerceAtLeast(0.01),
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
    fun parseRttStatsLine(
        line: String,
        maxValidRttMs: Double = MAX_VALID_RTT_MS,
    ): PingRttStats? {
        val match = RTT_STATS_REGEX.find(line.trim()) ?: return null
        val min = match.groupValues[1].toDoubleOrNull() ?: return null
        val avg = match.groupValues[2].toDoubleOrNull() ?: return null
        val max = match.groupValues[3].toDoubleOrNull() ?: return null
        val mdev = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val parsed = PingRttStats(
            minMs = min,
            avgMs = avg,
            maxMs = max,
            mdevMs = mdev,
        )
        return parsed.takeIf { isSaneRttStats(it, maxValidRttMs) }
    }

    internal fun isSaneRttStats(
        stats: PingRttStats,
        maxValidRttMs: Double = MAX_VALID_RTT_MS,
    ): Boolean {
        if (!stats.minMs.isFinite() || !stats.avgMs.isFinite() || !stats.maxMs.isFinite()) return false
        if (stats.minMs < 0.0 || stats.avgMs < stats.minMs || stats.maxMs < stats.avgMs) return false
        if (stats.maxMs > maxValidRttMs) return false
        val mdev = stats.mdevMs
        if (mdev != null && (!mdev.isFinite() || mdev < 0.0 || mdev > maxValidRttMs)) return false
        return true
    }

    /**
     * Fills missing `icmp_seq` slots as lost packets so the live/final RTT chart reflects packet loss accurately
     * while guaranteeing output length never exceeds [expectedCount].
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
            if (existing == null ||
                (!existing.success && sample.success) ||
                (existing.success && existing.rttMs == null && sample.success && sample.rttMs != null)
            ) {
                deduplicated[sample.seq] = sample
            }
        }

        val zeroSample = deduplicated[0]
        val startSeq = if (zeroSample != null && zeroSample.rttMs != null && !deduplicated.containsKey(expectedCount)) 0 else 1
        val endSeqExclusive = startSeq + expectedCount
        return buildList(expectedCount) {
            for (seq in startSeq until endSeqExclusive) {
                add(deduplicated[seq] ?: IcmpPingSample(seq = seq, success = false, errorMessage = "丢包/超时"))
            }
        }
    }

    /**
     * Computes aggregated [IcmpPingSummary], preferring authoritative summary stats from `ping`
     * and falling back to sample-derived min/avg/max/mdev when absent or when any sample had corrupted RTT.
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
        val hasCorruptedSample = samples.any { it.success && it.rttMs == null }
        val received = packetStats?.received ?: samples.count { it.success }
        val lossPercent = packetStats?.lossPercent ?: if (sent == 0) {
            100.0
        } else {
            (((sent - received).coerceAtLeast(0)) * 100.0) / sent.toDouble()
        }

        val safeRttStats = rttStats?.takeIf { !hasCorruptedSample && isSaneRttStats(it) }
        val sampleComputed = computeRttStats(rtts)
        if (received == 0 || (safeRttStats == null && sampleComputed == null)) {
            val fallbackErr = if (received > 0 && hasCorruptedSample && errorMessage == null) {
                "设备/NAT 回包的 ping 时间戳异常，未能测得有效 RTT"
            } else {
                errorMessage ?: samples.lastOrNull()?.errorMessage
            }
            return IcmpPingSummary(
                resolvedTarget = resolvedTarget,
                sent = sent,
                received = received,
                lossPercent = if (sent == 0) 100.0 else lossPercent,
                minMs = null,
                avgMs = null,
                maxMs = null,
                jitterMdevMs = null,
                samples = samples,
                bindingMode = bindingMode,
                boundInterfaceName = boundInterfaceName,
                networkBindingNote = networkBindingNote,
                errorMessage = fallbackErr,
            )
        }

        return IcmpPingSummary(
            resolvedTarget = resolvedTarget,
            sent = sent,
            received = received,
            lossPercent = lossPercent,
            minMs = safeRttStats?.minMs ?: sampleComputed?.minMs,
            avgMs = safeRttStats?.avgMs ?: sampleComputed?.avgMs,
            maxMs = safeRttStats?.maxMs ?: sampleComputed?.maxMs,
            jitterMdevMs = safeRttStats?.mdevMs ?: sampleComputed?.mdevMs,
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
            lines.firstOrNull {
                it.isNotBlank() &&
                    !it.startsWith("PING ", ignoreCase = true) &&
                    !it.startsWith("---") &&
                    !WRONG_DATA_REGEX.containsMatchIn(it) &&
                    !DUP_REPLY_REGEX.containsMatchIn(it)
            }
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
 * Pure Kotlin wire-format codec for unprivileged Linux `SOCK_DGRAM` ICMPv4 (`IPPROTO_ICMP`)
 * and ICMPv6 (`IPPROTO_ICMPV6`) Echo Request / Echo Reply datagrams.
 *
 * On Android/Linux `SOCK_DGRAM` `IPPROTO_ICMP` (`net/ipv4/ping.c` & `net/ipv6/ping.c`),
 * the kernel maps the socket's local port into the ICMP Identifier field, recomputes wire
 * checksums, and delivers Echo Reply payloads starting directly at the 8-byte ICMP header
 * (without an outer IPv4/IPv6 header).
 */
object IcmpEchoPacketCodec {
    const val ICMPV4_ECHO_REQUEST_TYPE = 8
    const val ICMPV4_ECHO_REPLY_TYPE = 0
    const val ICMPV6_ECHO_REQUEST_TYPE = 128
    const val ICMPV6_ECHO_REPLY_TYPE = 129
    const val ICMP_HEADER_BYTES = 8
    const val DEFAULT_PAYLOAD_BYTES = 56
    const val MAX_PAYLOAD_BYTES = 1400

    data class DecodedIcmpEchoReply(
        val ipFamily: IpFamily,
        val type: Int,
        val code: Int,
        val identifier: Int,
        val sequence: Int,
        val payloadBytes: Int,
    )

    /**
     * Builds an 8-byte ICMP/ICMPv6 Echo Request header + [payloadSize]-byte payload
     * with RFC 1071 16-bit one's-complement checksum populated.
     */
    fun encodeEchoRequest(
        ipFamily: IpFamily,
        identifier: Int,
        sequence: Int,
        payloadSize: Int = DEFAULT_PAYLOAD_BYTES,
        timestampNanos: Long = System.nanoTime(),
    ): ByteArray {
        val safePayload = payloadSize.coerceIn(0, MAX_PAYLOAD_BYTES)
        val packet = ByteArray(ICMP_HEADER_BYTES + safePayload)
        val reqType = if (ipFamily == IpFamily.IPV6) ICMPV6_ECHO_REQUEST_TYPE else ICMPV4_ECHO_REQUEST_TYPE

        packet[0] = reqType.toByte()
        packet[1] = 0 // Code = 0
        packet[2] = 0
        packet[3] = 0
        packet[4] = ((identifier ushr 8) and 0xFF).toByte()
        packet[5] = (identifier and 0xFF).toByte()
        packet[6] = ((sequence ushr 8) and 0xFF).toByte()
        packet[7] = (sequence and 0xFF).toByte()

        if (safePayload >= 8) {
            for (b in 0 until 8) {
                packet[8 + b] = ((timestampNanos ushr ((7 - b) * 8)) and 0xFFL).toByte()
            }
            for (i in 8 until safePayload) {
                packet[ICMP_HEADER_BYTES + i] = (0x10 + ((i - 8) and 0x3F)).toByte()
            }
        } else {
            for (i in 0 until safePayload) {
                packet[ICMP_HEADER_BYTES + i] = (0x10 + (i and 0x3F)).toByte()
            }
        }

        val checksum = computeChecksum(packet, 0, packet.size)
        packet[2] = ((checksum ushr 8) and 0xFF).toByte()
        packet[3] = (checksum and 0xFF).toByte()
        return packet
    }

    /**
     * Computes RFC 1071 16-bit one's-complement checksum over `bytes[offset .. offset + length)`.
     */
    fun computeChecksum(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Int {
        var sum = 0
        var idx = offset
        val end = (offset + length).coerceAtMost(bytes.size)
        while (idx + 1 < end) {
            val word = ((bytes[idx].toInt() and 0xFF) shl 8) or (bytes[idx + 1].toInt() and 0xFF)
            sum += word
            idx += 2
        }
        if (idx < end) {
            sum += (bytes[idx].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    /**
     * Verifies RFC 1071 checksum integrity of an ICMPv4 packet (`computeChecksum == 0`).
     */
    fun verifyIpv4Checksum(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Boolean {
        if (length < ICMP_HEADER_BYTES || offset < 0 || offset + length > bytes.size) return false
        return computeChecksum(bytes, offset, length) == 0
    }

    /**
     * Decodes a raw `SOCK_DGRAM` `recvfrom`/`recvmsg` datagram (without outer IP header) into
     * [DecodedIcmpEchoReply], returning `null` if truncated or if it is not an Echo Reply for [expectedFamily].
     */
    fun decodeEchoReply(
        buffer: ByteArray,
        length: Int,
        expectedFamily: IpFamily,
    ): DecodedIcmpEchoReply? {
        if (length < ICMP_HEADER_BYTES || length > buffer.size) return null
        val type = buffer[0].toInt() and 0xFF
        val code = buffer[1].toInt() and 0xFF
        val expectedReplyType = if (expectedFamily == IpFamily.IPV6) {
            ICMPV6_ECHO_REPLY_TYPE
        } else {
            ICMPV4_ECHO_REPLY_TYPE
        }
        if (type != expectedReplyType || code != 0) return null

        val identifier = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val sequence = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
        return DecodedIcmpEchoReply(
            ipFamily = expectedFamily,
            type = type,
            code = code,
            identifier = identifier,
            sequence = sequence,
            payloadBytes = length - ICMP_HEADER_BYTES,
        )
    }

    /**
     * Parses TTL / Hop Limit from Linux `IP_TTL` / `IPV6_HOPLIMIT` ancillary `cmsg_data`.
     */
    fun extractTtlFromCmsgData(
        cmsgData: ByteArray?,
        byteOrder: ByteOrder = ByteOrder.nativeOrder(),
    ): Int? {
        if (cmsgData == null || cmsgData.isEmpty()) return null
        val raw = if (cmsgData.size >= 4) {
            ByteBuffer.wrap(cmsgData, 0, 4).order(byteOrder).int
        } else {
            cmsgData[0].toInt() and 0xFF
        }
        return raw.takeIf { it in 1..255 }
    }
}

/**
 * Single datagram read from a kernel ICMP Echo socket.
 */
data class IcmpRawDatagram(
    val length: Int,
    val ttl: Int? = null,
)

/**
 * Thin OS syscall boundary for unprivileged `SOCK_DGRAM` `IPPROTO_ICMP` / `IPPROTO_ICMPV6` sockets,
 * allowing [KernelBoundIcmpDirectProber] to run deterministically in plain JVM unit tests.
 */
interface IcmpKernelDatagramOps {
    @Throws(Exception::class)
    fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor

    fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean

    @Throws(Exception::class)
    fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress)

    @Throws(Exception::class)
    fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean

    @Throws(Exception::class)
    fun receivePacket(
        fd: FileDescriptor,
        buffer: ByteArray,
        ipFamily: IpFamily,
        tryReadTtl: Boolean,
    ): IcmpRawDatagram?

    fun closeQuietly(fd: FileDescriptor)
}

/**
 * Carrier exception for POSIX `errno` values, enabling deterministic JVM unit testing of
 * [AndroidOsIcmpKernelDatagramOps] where stub `android.system.ErrnoException.errno` defaults to 0.
 */
internal class IcmpPosixException(
    val errno: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class PosixErrnoTable(
    val eperm: Int = OsConstants.EPERM.takeIf { it != 0 } ?: LINUX_EPERM,
    val eintr: Int = OsConstants.EINTR.takeIf { it != 0 } ?: LINUX_EINTR,
    val eagain: Int = OsConstants.EAGAIN.takeIf { it != 0 } ?: LINUX_EAGAIN,
    val eacces: Int = OsConstants.EACCES.takeIf { it != 0 } ?: LINUX_EACCES,
    val einval: Int = OsConstants.EINVAL.takeIf { it != 0 } ?: LINUX_EINVAL,
    val enosys: Int = OsConstants.ENOSYS.takeIf { it != 0 } ?: LINUX_ENOSYS,
    val eopnotsupp: Int = OsConstants.EOPNOTSUPP.takeIf { it != 0 } ?: LINUX_EOPNOTSUPP,
    val enetunreach: Int = OsConstants.ENETUNREACH.takeIf { it != 0 } ?: LINUX_ENETUNREACH,
    val econnrefused: Int = OsConstants.ECONNREFUSED.takeIf { it != 0 } ?: LINUX_ECONNREFUSED,
    val ehostunreach: Int = OsConstants.EHOSTUNREACH.takeIf { it != 0 } ?: LINUX_EHOSTUNREACH,
) {
    companion object {
        const val LINUX_EPERM = 1
        const val LINUX_EINTR = 4
        const val LINUX_EAGAIN = 11
        const val LINUX_EACCES = 13
        const val LINUX_EINVAL = 22
        const val LINUX_ENOSYS = 38
        const val LINUX_EOPNOTSUPP = 95
        const val LINUX_ENETUNREACH = 101
        const val LINUX_ECONNREFUSED = 111
        const val LINUX_EHOSTUNREACH = 113
    }
}

internal fun extractPosixErrno(throwable: Throwable): Int? = when (throwable) {
    is IcmpPosixException -> throwable.errno
    is ErrnoException -> throwable.errno
    else -> null
}

/**
 * Low-level Android/POSIX syscall adapter consumed by [AndroidOsIcmpKernelDatagramOps].
 *
 * Isolating raw `android.system.Os` calls behind `@RequiresApi` static nested helper objects
 * (`Api29Impl`, `Api30Impl`, `Api31Impl`) avoids ART class verification failures on API 26–30
 * while allowing [AndroidOsIcmpKernelDatagramOps] to be deterministically tested across SDK
 * levels 26, 29, 30, and 31 on plain JVM.
 */
internal interface IcmpPosixSyscalls {
    val errnoTable: PosixErrnoTable
        get() = PosixErrnoTable()

    fun ensureRuntimeAvailable()

    @Throws(Exception::class)
    fun openDatagramSocket(ipFamily: IpFamily): FileDescriptor

    @Throws(Exception::class)
    fun openNonBlockingDatagramSocketApi29(ipFamily: IpFamily): FileDescriptor

    @Throws(Exception::class)
    fun setReceiveTimeoutApi29(fd: FileDescriptor, timeoutMs: Long)

    @Throws(Exception::class)
    fun ensureNonBlockingApi30(fd: FileDescriptor)

    @Throws(Exception::class)
    fun setReceiveTtlOption(fd: FileDescriptor, ipFamily: IpFamily)

    @Throws(Exception::class)
    fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress)

    @Throws(Exception::class)
    fun pollReadableOnce(fd: FileDescriptor, timeoutMs: Int): Boolean

    @Throws(Exception::class)
    fun recvFrom(fd: FileDescriptor, buffer: ByteArray, flags: Int): Int

    @Throws(Exception::class)
    fun recvMsgWithTtlApi31(
        fd: FileDescriptor,
        buffer: ByteArray,
        ipFamily: IpFamily,
        flags: Int,
    ): IcmpRawDatagram?

    fun closeQuietly(fd: FileDescriptor)
}

internal object SystemIcmpPosixSyscalls : IcmpPosixSyscalls {
    override fun ensureRuntimeAvailable() {
        // When `unitTests.isReturnDefaultValues = true` on host JVM, OsConstants are 0 and Os.socket returns null
        if (OsConstants.AF_INET == 0 && OsConstants.SOCK_DGRAM == 0) {
            throw UnsupportedOperationException("Android OsConstants unavailable on host JVM")
        }
    }

    override fun openDatagramSocket(ipFamily: IpFamily): FileDescriptor {
        val domain = if (ipFamily == IpFamily.IPV6) OsConstants.AF_INET6 else OsConstants.AF_INET
        val protocol = if (ipFamily == IpFamily.IPV6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP
        val fd = Os.socket(domain, OsConstants.SOCK_DGRAM, protocol)
        if (fd == null || !fd.valid()) {
            throw UnsupportedOperationException("Os.socket returned invalid FileDescriptor")
        }
        return fd
    }

    override fun openNonBlockingDatagramSocketApi29(ipFamily: IpFamily): FileDescriptor {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return openDatagramSocket(ipFamily)
        }
        val domain = if (ipFamily == IpFamily.IPV6) OsConstants.AF_INET6 else OsConstants.AF_INET
        val protocol = if (ipFamily == IpFamily.IPV6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP
        val fd = Api29Impl.socketNonBlocking(domain, protocol)
        if (fd == null || !fd.valid()) {
            throw UnsupportedOperationException("Os.socket returned invalid FileDescriptor")
        }
        return fd
    }

    override fun setReceiveTimeoutApi29(fd: FileDescriptor, timeoutMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29Impl.setReceiveTimeout(fd, timeoutMs)
        }
    }

    override fun ensureNonBlockingApi30(fd: FileDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Impl.ensureNonBlocking(fd)
        }
    }

    override fun setReceiveTtlOption(fd: FileDescriptor, ipFamily: IpFamily) {
        if (ipFamily == IpFamily.IPV6) {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_RECVHOPLIMIT, 1)
        } else {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, AndroidOsIcmpKernelDatagramOps.LINUX_IP_RECVTTL, 1)
        }
    }

    override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
        Os.sendto(fd, packet, 0, packet.size, 0, address, 0)
    }

    override fun pollReadableOnce(fd: FileDescriptor, timeoutMs: Int): Boolean {
        val pollfd = StructPollfd().apply {
            this.fd = fd
            this.events = OsConstants.POLLIN.toShort()
        }
        val ready = Os.poll(arrayOf(pollfd), timeoutMs)
        if (ready <= 0) return false
        val revents = pollfd.revents.toInt()
        return (revents and (OsConstants.POLLIN or OsConstants.POLLERR)) != 0
    }

    override fun recvFrom(fd: FileDescriptor, buffer: ByteArray, flags: Int): Int =
        Os.recvfrom(fd, buffer, 0, buffer.size, flags, null)

    override fun recvMsgWithTtlApi31(
        fd: FileDescriptor,
        buffer: ByteArray,
        ipFamily: IpFamily,
        flags: Int,
    ): IcmpRawDatagram? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw UnsupportedOperationException("Os.recvmsg requires API 31")
        }
        return Api31Impl.receiveWithTtl(fd, buffer, ipFamily, flags)
    }

    override fun closeQuietly(fd: FileDescriptor) {
        runCatching {
            if (fd.valid()) {
                Os.close(fd)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29Impl {
        fun socketNonBlocking(domain: Int, protocol: Int): FileDescriptor? =
            Os.socket(domain, OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK, protocol)

        fun setReceiveTimeout(fd: FileDescriptor, timeoutMs: Long) {
            val timeval = StructTimeval.fromMillis(timeoutMs.coerceAtLeast(1L))
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, timeval)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private object Api30Impl {
        fun ensureNonBlocking(fd: FileDescriptor) {
            val currentFlags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            if ((currentFlags and OsConstants.O_NONBLOCK) == 0) {
                Os.fcntlInt(fd, OsConstants.F_SETFL, currentFlags or OsConstants.O_NONBLOCK)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Impl {
        private const val LINUX_IPV6_HOPLIMIT = 52

        fun receiveWithTtl(
            fd: FileDescriptor,
            buffer: ByteArray,
            ipFamily: IpFamily,
            flags: Int,
        ): IcmpRawDatagram? {
            val iov = arrayOf(ByteBuffer.wrap(buffer))
            val controlSeed = arrayOf(StructCmsghdr(0, 0, ByteArray(16)))
            val msgHdr = StructMsghdr(null, iov, controlSeed, 0)
            val n = Os.recvmsg(fd, msgHdr, flags)
            if (n <= 0) return null
            val ttl = msgHdr.msg_control?.firstNotNullOfOrNull { cmsg ->
                val isV4Ttl = ipFamily != IpFamily.IPV6 &&
                    cmsg.cmsg_level == OsConstants.IPPROTO_IP &&
                    (cmsg.cmsg_type == OsConstants.IP_TTL || cmsg.cmsg_type == AndroidOsIcmpKernelDatagramOps.LINUX_IP_RECVTTL)
                val isV6HopLimit = ipFamily == IpFamily.IPV6 &&
                    cmsg.cmsg_level == OsConstants.IPPROTO_IPV6 &&
                    (cmsg.cmsg_type == LINUX_IPV6_HOPLIMIT || cmsg.cmsg_type == OsConstants.IPV6_RECVHOPLIMIT)
                if (isV4Ttl || isV6HopLimit) {
                    IcmpEchoPacketCodec.extractTtlFromCmsgData(cmsg.cmsg_data)
                } else {
                    null
                }
            }
            return IcmpRawDatagram(length = n, ttl = ttl)
        }
    }
}

/**
 * Production Android `android.system.Os` implementation of [IcmpKernelDatagramOps] with clean
 * SDK-level dispatch across API 26–28 (`O`–`P`), API 29 (`Q`), API 30 (`R`), and API 31+ (`S`).
 */
class AndroidOsIcmpKernelDatagramOps internal constructor(
    internal val sdkInt: Int,
    private val syscalls: IcmpPosixSyscalls,
) : IcmpKernelDatagramOps {

    constructor(sdkInt: Int = Build.VERSION.SDK_INT) : this(
        sdkInt = sdkInt,
        syscalls = SystemIcmpPosixSyscalls,
    )

    override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor {
        syscalls.ensureRuntimeAvailable()

        val fd: FileDescriptor = if (sdkInt >= Build.VERSION_CODES.Q) {
            try {
                syscalls.openNonBlockingDatagramSocketApi29(ipFamily)
            } catch (t: Throwable) {
                if (extractPosixErrno(t) != null) {
                    syscalls.openDatagramSocket(ipFamily)
                } else {
                    throw t
                }
            }
        } else {
            syscalls.openDatagramSocket(ipFamily)
        }

        try {
            when {
                sdkInt >= Build.VERSION_CODES.R -> {
                    try {
                        syscalls.ensureNonBlockingApi30(fd)
                    } catch (t: Throwable) {
                        if (extractPosixErrno(t) != null) {
                            runCatching { syscalls.setReceiveTimeoutApi29(fd, SAFETY_SO_RCVTIMEO_MS) }
                        } else {
                            throw t
                        }
                    }
                }
                sdkInt >= Build.VERSION_CODES.Q -> {
                    runCatching { syscalls.setReceiveTimeoutApi29(fd, SAFETY_SO_RCVTIMEO_MS) }
                }
                else -> {
                    // API 26–28: OsConstants.SOCK_NONBLOCK (API 29), Os.setsockoptTimeval (API 29),
                    // and Os.fcntlInt (API 30) are unavailable. Waiting is bounded by Os.poll() slices
                    // and reads pass MSG_DONTWAIT per call to Os.recvfrom().
                }
            }
        } catch (t: Throwable) {
            syscalls.closeQuietly(fd)
            throw t
        }
        return fd
    }

    override fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean {
        // Ancillary cmsg TTL extraction requires Os.recvmsg + StructMsghdr + StructCmsghdr (API 31+).
        if (sdkInt < Build.VERSION_CODES.S) return false
        return runCatching {
            syscalls.setReceiveTtlOption(fd, ipFamily)
            true
        }.getOrDefault(false)
    }

    override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
        syscalls.sendTo(fd, packet, address)
    }

    override fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean {
        val clampedMs = timeoutMs.coerceAtLeast(1)
        val deadlineNanos = System.nanoTime() + clampedMs * 1_000_000L
        val errnos = syscalls.errnoTable
        while (true) {
            val remainingMs = ((deadlineNanos - System.nanoTime() + 999_999L) / 1_000_000L).toInt()
            if (remainingMs <= 0) return false
            try {
                return syscalls.pollReadableOnce(fd, remainingMs)
            } catch (t: Throwable) {
                if (extractPosixErrno(t) == errnos.eintr) continue
                throw t
            }
        }
    }

    override fun receivePacket(
        fd: FileDescriptor,
        buffer: ByteArray,
        ipFamily: IpFamily,
        tryReadTtl: Boolean,
    ): IcmpRawDatagram? {
        val errnos = syscalls.errnoTable
        if (tryReadTtl && sdkInt >= Build.VERSION_CODES.S) {
            try {
                return syscalls.recvMsgWithTtlApi31(fd, buffer, ipFamily, LINUX_MSG_DONTWAIT)
            } catch (t: Throwable) {
                val errno = extractPosixErrno(t)
                if (errno != null) {
                    when (
                        classifyRecvMsgErrno(
                            errno = errno,
                            eagain = errnos.eagain,
                            eintr = errnos.eintr,
                            einval = errnos.einval,
                            eopnotsupp = errnos.eopnotsupp,
                            enosys = errnos.enosys,
                        )
                    ) {
                        RecvMsgErrnoAction.RETURN_NULL -> return null
                        RecvMsgErrnoAction.FALLBACK_RECVFROM -> {
                            // Kernel rejected recvmsg control buffer or syscall; fall through to non-blocking Os.recvfrom
                        }
                        RecvMsgErrnoAction.RETHROW -> throw t // Surface EHOSTUNREACH, ENETUNREACH, ECONNREFUSED, EACCES, EPERM immediately
                    }
                } else if (t is LinkageError || t is UnsupportedOperationException) {
                    // Fall through to Os.recvfrom
                } else {
                    throw t
                }
            }
        }
        return recvFromWithDontWaitFallback(fd, buffer, errnos)
    }

    private fun recvFromWithDontWaitFallback(
        fd: FileDescriptor,
        buffer: ByteArray,
        errnos: PosixErrnoTable,
    ): IcmpRawDatagram? {
        val bytesRead = try {
            syscalls.recvFrom(fd, buffer, LINUX_MSG_DONTWAIT)
        } catch (t: Throwable) {
            val errno = extractPosixErrno(t)
            when {
                errno == errnos.eagain || errno == errnos.eintr -> return null
                errno == errnos.einval || errno == errnos.eopnotsupp -> {
                    // Caller already verified POLLIN | POLLERR via Os.poll() on this exclusive FD;
                    // if an older kernel rejects MSG_DONTWAIT in flags, retry once with flags = 0.
                    try {
                        syscalls.recvFrom(fd, buffer, 0)
                    } catch (retryErr: Throwable) {
                        val retryErrno = extractPosixErrno(retryErr)
                        if (retryErrno == errnos.eagain || retryErrno == errnos.eintr) {
                            return null
                        }
                        throw retryErr
                    }
                }
                else -> throw t
            }
        }
        return if (bytesRead <= 0) null else IcmpRawDatagram(length = bytesRead, ttl = null)
    }

    override fun closeQuietly(fd: FileDescriptor) {
        syscalls.closeQuietly(fd)
    }

    internal enum class RecvMsgErrnoAction {
        RETURN_NULL,
        FALLBACK_RECVFROM,
        RETHROW,
    }

    companion object : IcmpKernelDatagramOps by AndroidOsIcmpKernelDatagramOps() {
        internal const val LINUX_IP_RECVTTL = 12
        internal const val LINUX_MSG_DONTWAIT = 0x40
        internal const val SAFETY_SO_RCVTIMEO_MS = 100L

        internal fun classifyRecvMsgErrno(
            errno: Int,
            eagain: Int = OsConstants.EAGAIN.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_EAGAIN,
            eintr: Int = OsConstants.EINTR.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_EINTR,
            einval: Int = OsConstants.EINVAL.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_EINVAL,
            eopnotsupp: Int = OsConstants.EOPNOTSUPP.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_EOPNOTSUPP,
            enosys: Int = OsConstants.ENOSYS.takeIf { it != 0 } ?: PosixErrnoTable.LINUX_ENOSYS,
        ): RecvMsgErrnoAction = when (errno) {
            eagain, eintr -> RecvMsgErrnoAction.RETURN_NULL
            einval, eopnotsupp, enosys -> RecvMsgErrnoAction.FALLBACK_RECVFROM
            else -> RecvMsgErrnoAction.RETHROW
        }
    }
}

/**
 * Outcome of attempting direct physical-network ICMP Echo probing via `Network.bindSocket(fd)`.
 */
sealed class IcmpDirectProbeOutcome {
    data class Completed(
        val samples: List<IcmpPingSample>,
        val boundInterfaceName: String?,
        val abortedEarlyOnLockdown: Boolean = false,
    ) : IcmpDirectProbeOutcome()

    data class Unavailable(
        val reason: String,
        val cause: Throwable? = null,
    ) : IcmpDirectProbeOutcome()

    data class BindRejectedByLockdown(
        val userMessage: String,
        val cause: Throwable,
    ) : IcmpDirectProbeOutcome()
}

/**
 * Abstraction for executing in-process ICMP Echo probes bound directly to a physical [NetBenchSocketChannel].
 */
fun interface IcmpDirectProber {
    suspend fun probe(
        endpoint: ResolvedEndpoint,
        plannedCount: Int,
        timeoutMs: Int,
        intervalMs: Long,
        initialChannel: NetBenchSocketChannel,
        initialInterfaceName: String?,
        isVpnActive: Boolean,
        networkBridge: NetBenchNetworkBridge,
        onSample: suspend (sample: IcmpPingSample, snapshot: List<IcmpPingSample>, boundInterfaceName: String?) -> Unit,
    ): IcmpDirectProbeOutcome
}

/**
 * Default [IcmpDirectProber] opening an unprivileged kernel ICMP Echo datagram socket (`AF_INET`/`AF_INET6`
 * + `SOCK_DGRAM` + `IPPROTO_ICMP`/`IPPROTO_ICMPV6`) and binding its [FileDescriptor] directly to the
 * underlying physical [android.net.Network] (`Network.bindSocket(fd)`), 100% bypassing TUN VPN routes.
 */
class KernelBoundIcmpDirectProber(
    ops: IcmpKernelDatagramOps? = null,
    private val pollSliceMs: Long = DEFAULT_POLL_SLICE_MS,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : IcmpDirectProber {

    private val ops: IcmpKernelDatagramOps = ops ?: AndroidOsIcmpKernelDatagramOps(sdkInt = sdkInt)

    override suspend fun probe(
        endpoint: ResolvedEndpoint,
        plannedCount: Int,
        timeoutMs: Int,
        intervalMs: Long,
        initialChannel: NetBenchSocketChannel,
        initialInterfaceName: String?,
        isVpnActive: Boolean,
        networkBridge: NetBenchNetworkBridge,
        onSample: suspend (sample: IcmpPingSample, snapshot: List<IcmpPingSample>, boundInterfaceName: String?) -> Unit,
    ): IcmpDirectProbeOutcome {
        if (!initialChannel.supportsFileDescriptorBind) {
            return IcmpDirectProbeOutcome.Unavailable(
                reason = "Channel ${initialChannel.networkId} does not support FileDescriptor binding",
            )
        }

        val ipFamily = endpoint.resolvedTarget.ipFamily
        var activeChannel = initialChannel
        var currentInterfaceName = initialInterfaceName
        var activeFd: FileDescriptor = try {
            ops.openIcmpDatagramSocket(ipFamily)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return IcmpDirectProbeOutcome.Unavailable(
                reason = "Unprivileged ICMP socket unavailable: ${t.message ?: t.javaClass.simpleName}",
                cause = t,
            )
        }

        try {
            try {
                activeChannel.bindDatagramFileDescriptor(activeFd)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
                    throwable = t,
                    isVpnActive = isVpnActive,
                    hasDirectCandidate = true,
                )
                if (isVpnActive && reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
                    return IcmpDirectProbeOutcome.BindRejectedByLockdown(
                        userMessage = "${reason.userHint}；请开启「经由代理测试」",
                        cause = t,
                    )
                }
                return IcmpDirectProbeOutcome.Unavailable(
                    reason = "Network.bindSocket(fd) failed: ${t.message ?: t.javaClass.simpleName}",
                    cause = t,
                )
            }

            var tryReadTtl = ops.enableReceiveTtlBestEffort(activeFd, ipFamily)
            val sessionIdentifier = ((System.nanoTime().toInt() xor endpoint.address.hashCode()) and 0xFFFF)
                .let { if (it == 0) 0x4E50 else it }
            val recvBuffer = ByteArray(512)
            val samples = ArrayList<IcmpPingSample>(plannedCount)
            var abortedOnLockdown = false

            for (seq in 1..plannedCount) {
                currentCoroutineContext().ensureActive()

                val outcome = executeSingleEcho(
                    seq = seq,
                    ipFamily = ipFamily,
                    targetAddress = endpoint.address,
                    identifier = sessionIdentifier,
                    timeoutMs = timeoutMs,
                    recvBuffer = recvBuffer,
                    isVpnActive = isVpnActive,
                    networkBridge = networkBridge,
                    currentFd = { activeFd },
                    currentChannel = { activeChannel },
                    tryReadTtl = { tryReadTtl },
                    onSwitchFdAndChannel = { nextFd, nextChannel, nextIface, nextTtl ->
                        ops.closeQuietly(activeFd)
                        activeFd = nextFd
                        activeChannel = nextChannel
                        currentInterfaceName = nextIface
                        tryReadTtl = nextTtl
                    },
                )

                samples += outcome.sample
                onSample(outcome.sample, samples.toList(), currentInterfaceName)

                if (outcome.isPermanentLockdown) {
                    abortedOnLockdown = true
                    break
                }

                if (seq < plannedCount && intervalMs > 0L) {
                    delay(intervalMs)
                }
            }

            return IcmpDirectProbeOutcome.Completed(
                samples = samples.toList(),
                boundInterfaceName = currentInterfaceName,
                abortedEarlyOnLockdown = abortedOnLockdown,
            )
        } finally {
            ops.closeQuietly(activeFd)
        }
    }

    private data class SingleEchoOutcome(
        val sample: IcmpPingSample,
        val isPermanentLockdown: Boolean = false,
    )

    private suspend fun executeSingleEcho(
        seq: Int,
        ipFamily: IpFamily,
        targetAddress: InetAddress,
        identifier: Int,
        timeoutMs: Int,
        recvBuffer: ByteArray,
        isVpnActive: Boolean,
        networkBridge: NetBenchNetworkBridge,
        currentFd: () -> FileDescriptor,
        currentChannel: () -> NetBenchSocketChannel,
        tryReadTtl: () -> Boolean,
        onSwitchFdAndChannel: (FileDescriptor, NetBenchSocketChannel, String?, Boolean) -> Unit,
    ): SingleEchoOutcome {
        var sendStartNanos = 0L
        var sentOk = false

        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            val sendNanos = System.nanoTime()
            val packet = IcmpEchoPacketCodec.encodeEchoRequest(
                ipFamily = ipFamily,
                identifier = identifier,
                sequence = seq,
                payloadSize = IcmpEchoPacketCodec.DEFAULT_PAYLOAD_BYTES,
                timestampNanos = sendNanos,
            )
            try {
                sendStartNanos = System.nanoTime()
                ops.sendTo(currentFd(), packet, targetAddress)
                sentOk = true
                break
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val refreshed = networkBridge.inspectCurrentNetworks()
                val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
                    throwable = t,
                    isVpnActive = isVpnActive || refreshed.plan.isVpnActive,
                    hasDirectCandidate = refreshed.plan.hasDirectCandidate,
                )
                if (reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
                    return SingleEchoOutcome(
                        sample = IcmpPingSample(
                            seq = seq,
                            success = false,
                            errorMessage = "${reason.userHint}；请开启「经由代理测试」",
                        ),
                        isPermanentLockdown = true,
                    )
                }
                val nextChannel = refreshed.directChannel
                if (attempt == 0 &&
                    reason == DirectBindFailureReason.NETWORK_CHANGED_OR_LOST &&
                    nextChannel != null &&
                    nextChannel.networkId != currentChannel().networkId &&
                    nextChannel.supportsFileDescriptorBind
                ) {
                    val reopened = runCatching {
                        val newFd = ops.openIcmpDatagramSocket(ipFamily)
                        try {
                            nextChannel.bindDatagramFileDescriptor(newFd)
                            val newTtl = ops.enableReceiveTtlBestEffort(newFd, ipFamily)
                            val newIface = refreshed.plan.directUnderlying?.interfaceName?.takeIf { it.isNotBlank() }
                            onSwitchFdAndChannel(newFd, nextChannel, newIface, newTtl)
                            true
                        } catch (bindErr: Throwable) {
                            ops.closeQuietly(newFd)
                            throw bindErr
                        }
                    }.getOrDefault(false)
                    if (reopened) continue
                }

                val errLabel = when (reason) {
                    DirectBindFailureReason.NETWORK_CHANGED_OR_LOST -> "底层网络不可达或已切换"
                    DirectBindFailureReason.NO_UNDERLYING_NETWORK -> reason.userHint
                    else -> "发送 ICMP 请求失败 (${t.message?.take(48) ?: t.javaClass.simpleName})"
                }
                return SingleEchoOutcome(
                    sample = IcmpPingSample(
                        seq = seq,
                        success = false,
                        errorMessage = errLabel,
                    ),
                )
            }
        }

        if (!sentOk) {
            return SingleEchoOutcome(IcmpPingSample(seq = seq, success = false, errorMessage = "发送 ICMP 请求失败"))
        }

        val deadlineNanos = sendStartNanos + timeoutMs.coerceAtLeast(100) * 1_000_000L
        val expectedSeq16 = seq and 0xFFFF
        val sliceCapMs = pollSliceMs.coerceIn(10L, 250L)

        while (true) {
            currentCoroutineContext().ensureActive()
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                return SingleEchoOutcome(
                    IcmpPingSample(
                        seq = seq,
                        success = false,
                        rttMs = null,
                        errorMessage = "请求超时",
                    ),
                )
            }

            val waitMs = ((remainingNanos + 999_999L) / 1_000_000L).coerceIn(1L, sliceCapMs).toInt()
            val readable = try {
                ops.pollReadable(currentFd(), waitMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return SingleEchoOutcome(
                    IcmpPingSample(
                        seq = seq,
                        success = false,
                        errorMessage = "接收等待异常 (${t.message?.take(40) ?: t.javaClass.simpleName})",
                    ),
                )
            }

            if (!readable) continue

            val datagram = try {
                ops.receivePacket(currentFd(), recvBuffer, ipFamily, tryReadTtl())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val (msg, isLockdown) = classifyReceiveErrorDetailed(
                    throwable = t,
                    isVpnActive = isVpnActive,
                )
                return SingleEchoOutcome(
                    sample = IcmpPingSample(
                        seq = seq,
                        success = false,
                        rttMs = null,
                        errorMessage = msg,
                    ),
                    isPermanentLockdown = isLockdown,
                )
            } ?: continue

            val recvEndNanos = System.nanoTime()
            val decoded = IcmpEchoPacketCodec.decodeEchoReply(
                buffer = recvBuffer,
                length = datagram.length,
                expectedFamily = ipFamily,
            ) ?: continue

            // Ignore late replies belonging to earlier timed-out sequences and keep polling until deadline
            if (decoded.sequence != expectedSeq16) {
                continue
            }

            val rawRttMs = (recvEndNanos - sendStartNanos) / 1_000_000.0
            if (!rawRttMs.isFinite() || rawRttMs < 0.0 || rawRttMs > IcmpPingParser.MAX_VALID_RTT_MS) {
                return SingleEchoOutcome(
                    IcmpPingSample(
                        seq = seq,
                        success = true,
                        rttMs = null,
                        ttl = datagram.ttl,
                        errorMessage = "RTT 异常已忽略",
                    ),
                )
            }

            return SingleEchoOutcome(
                IcmpPingSample(
                    seq = seq,
                    success = true,
                    rttMs = rawRttMs.coerceAtLeast(0.01),
                    ttl = datagram.ttl,
                ),
            )
        }
    }

    private fun classifyReceiveErrorDetailed(
        throwable: Throwable,
        isVpnActive: Boolean,
    ): Pair<String, Boolean> {
        val errno = extractPosixErrno(throwable) ?: 0
        val errnos = PosixErrnoTable()
        val combined = generateSequence(throwable) { it.cause }
            .take(6)
            .joinToString(" | ") { "${it.javaClass.simpleName}:${it.message.orEmpty()}" }
            .lowercase(Locale.US)

        val isPermDenied = (errno != 0 && (errno == errnos.eperm || errno == errnos.eacces)) ||
            "eperm" in combined ||
            "operation not permitted" in combined ||
            "eacces" in combined ||
            "permission denied" in combined
        if (isPermDenied) {
            return if (isVpnActive) {
                "${DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED.userHint}；请开启「经由代理测试」" to true
            } else {
                "ICMP 接收权限受限" to false
            }
        }

        val isHostUnreach = (errno != 0 && errno == errnos.ehostunreach) ||
            "ehostunreach" in combined ||
            "no route to host" in combined ||
            "host unreachable" in combined
        if (isHostUnreach) {
            return "目标主机不可达" to false
        }

        val isNetUnreach = (errno != 0 && errno == errnos.enetunreach) ||
            "enetunreach" in combined ||
            "network is unreachable" in combined
        if (isNetUnreach) {
            return "底层网络不可达" to false
        }

        val isRefused = (errno != 0 && errno == errnos.econnrefused) ||
            "econnrefused" in combined ||
            "connection refused" in combined ||
            "port unreachable" in combined ||
            "prohibited" in combined ||
            "filtered" in combined
        if (isRefused) {
            return "目标拒绝或过滤 ICMP 请求" to false
        }

        return "接收 ICMP 响应失败 (${throwable.message?.take(48) ?: throwable.javaClass.simpleName})" to false
    }

    companion object {
        const val DEFAULT_POLL_SLICE_MS = 50L
    }
}

/**
 * Executes real-time ICMP ping with a multi-stage VPN/proxy bypass architecture:
 * 1. **Stage 1 (`PHYSICAL_NETWORK_BOUND`)**: When bypassing proxy (`!useProxy`), opens an unprivileged
 *    kernel ICMP Echo datagram socket (`Os.socket` `SOCK_DGRAM` `IPPROTO_ICMP`/`IPPROTO_ICMPV6`) and
 *    binds it directly to the physical Wi-Fi/Cellular [android.net.Network] via `Network.bindSocket(fd)`.
 * 2. **Stage 2 (`INTERFACE_BOUND`)**: Falls back to `/system/bin/ping -I <iface>` if the kernel disables
 *    `ping_group_range` or FD binding is unavailable.
 * 3. **Stage 3 (`UNBOUND_SUBPROCESS_FALLBACK` / `SYSTEM_DEFAULT`)**: Degrades cleanly to unbound `/system/bin/ping`
 *    (with explicit routing warning if VPN is active, unless blocked by strict VPN lockdown).
 */
class IcmpPingRunner(
    private val resolver: NetBenchTargetResolver = NetBenchTargetResolver(),
    private val executor: PingProcessExecutor = SystemPingProcessExecutor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
    private val ping6BinaryAvailable: () -> Boolean = { File(IcmpPingParser.DEFAULT_PING6_BINARY).exists() },
    directProber: IcmpDirectProber? = null,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    private val directProber: IcmpDirectProber = directProber ?: KernelBoundIcmpDirectProber(sdkInt = sdkInt)

    fun run(config: IcmpPingConfig): Flow<IcmpPingProgress> = flow {
        val plannedCount = config.normalizedProbeCount
        val timeoutMs = config.normalizedTimeoutMs
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
        val directInterfaceName = directDescriptor?.interfaceName?.takeIf { it.isNotBlank() }

        // Stage 1: Kernel unprivileged ICMP Echo socket bound via Network.bindSocket(fd)
        var lockdownRejectionMessage: String? = null
        if (wantDirectPhysical) {
            val directOutcome = directProber.probe(
                endpoint = endpoint,
                plannedCount = plannedCount,
                timeoutMs = timeoutMs,
                intervalMs = initialIntervalMs,
                initialChannel = selectedChannel,
                initialInterfaceName = directInterfaceName,
                isVpnActive = vpnActive,
                networkBridge = networkBridge,
                onSample = { sample, snapshot, liveIface ->
                    val liveSummary = IcmpPingParser.computeSummary(
                        resolvedTarget = endpoint.resolvedTarget,
                        samples = snapshot,
                        bindingMode = IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND,
                        boundInterfaceName = liveIface,
                        networkBindingNote = null,
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
                },
            )

            when (directOutcome) {
                is IcmpDirectProbeOutcome.Completed -> {
                    val finalSamples = if (directOutcome.abortedEarlyOnLockdown) {
                        directOutcome.samples
                    } else {
                        IcmpPingParser.reconcileSamples(directOutcome.samples, plannedCount)
                    }
                    val effectiveSent = finalSamples.size.coerceAtLeast(1)
                    val receivedCount = finalSamples.count { it.success }
                    val finalError = if (receivedCount == 0 || directOutcome.abortedEarlyOnLockdown) {
                        val specific = directOutcome.samples.lastOrNull {
                            !it.errorMessage.isNullOrBlank() && it.errorMessage != "请求超时"
                        }?.errorMessage
                        specific ?: "全部请求超时或目标禁 Ping（100% 丢包）"
                    } else {
                        null
                    }
                    val finalSummary = IcmpPingParser.computeSummary(
                        resolvedTarget = endpoint.resolvedTarget,
                        samples = finalSamples,
                        packetStats = IcmpPingParser.PingPacketStats(
                            sent = effectiveSent,
                            received = receivedCount,
                            lossPercent = ((effectiveSent - receivedCount) * 100.0) / effectiveSent.toDouble(),
                        ),
                        bindingMode = IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND,
                        boundInterfaceName = directOutcome.boundInterfaceName,
                        networkBindingNote = null,
                        errorMessage = finalError,
                    )
                    emit(
                        IcmpPingProgress(
                            resolvedTarget = endpoint.resolvedTarget,
                            totalPlanned = plannedCount,
                            latestSample = finalSamples.lastOrNull(),
                            samples = finalSamples,
                            summary = finalSummary,
                            isFinished = true,
                            errorMessage = finalSummary.errorMessage,
                        ),
                    )
                    return@flow
                }

                is IcmpDirectProbeOutcome.BindRejectedByLockdown -> {
                    lockdownRejectionMessage = directOutcome.userMessage
                    if (directInterfaceName == null) {
                        emit(
                            failedProgress(
                                resolvedTarget = endpoint.resolvedTarget,
                                plannedCount = plannedCount,
                                errorMessage = directOutcome.userMessage,
                            ),
                        )
                        return@flow
                    }
                }

                is IcmpDirectProbeOutcome.Unavailable -> {
                    // Proceed smoothly to Stage 2 (/system/bin/ping -I <iface>) -> Stage 3 (/system/bin/ping)
                }
            }
        }

        val candidateInterface = if (wantDirectPhysical && (vpnActive || binding.plan.activeDefault?.networkId != selectedChannel.networkId)) {
            directInterfaceName
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
            val perPacketBudgetMs = maxOf(currentIntervalMs + 500L, 1_200L)
            val processTimeoutMs = (plannedCount * perPacketBudgetMs) + (timeoutSec * 1_000L) + 6_000L

            val liveSamplesBySeq = LinkedHashMap<Int, IcmpPingSample>(plannedCount)
            val stderrLines = mutableListOf<String>()
            val nonSampleStdoutLines = mutableListOf<String>()
            var packetStats: IcmpPingParser.PingPacketStats? = null
            var rttStats: IcmpPingParser.PingRttStats? = null
            var exitCode = -1
            var streamException: Throwable? = null

            try {
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
                            val sample = IcmpPingParser.parseLine(line, fallbackSeq = liveSamplesBySeq.size + 1)
                            if (sample != null) {
                                val existing = liveSamplesBySeq[sample.seq]
                                if (existing == null ||
                                    (!existing.success && sample.success) ||
                                    (existing.success && existing.rttMs == null && sample.success && sample.rttMs != null)
                                ) {
                                    liveSamplesBySeq[sample.seq] = sample
                                }
                                val snapshot = liveSamplesBySeq.values.toList().take(plannedCount)
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
                            } else if (line.isNotBlank() &&
                                !line.contains("wrong data byte", ignoreCase = true) &&
                                !line.contains("(DUP!)", ignoreCase = true)
                            ) {
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                streamException = t
            }

            val liveSamples = liveSamplesBySeq.values.toList()
            val receivedCount = packetStats?.received ?: liveSamples.count { it.success }
            if (receivedCount > 0) {
                val expectedTotal = packetStats?.sent?.takeIf { it > 0 }?.coerceAtMost(plannedCount) ?: plannedCount
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
                        errorMessage = finalSummary.errorMessage,
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
                throwable = streamException,
            )

            // If Network.bindSocket(fd) was already denied by strict VPN lockdown AND -I <iface> also failed,
            // do NOT fall back to unbound /system/bin/ping (which would route into VPN tun0 and fake 1ms RTT).
            if (lockdownRejectionMessage != null &&
                (failureKind == IcmpPingParser.PingFailureKind.INTERFACE_BIND_REJECTED ||
                    failureKind == IcmpPingParser.PingFailureKind.PERMISSION_DENIED)
            ) {
                emit(
                    failedProgress(
                        resolvedTarget = endpoint.resolvedTarget,
                        plannedCount = plannedCount,
                        errorMessage = lockdownRejectionMessage,
                    ),
                )
                return@flow
            }

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
