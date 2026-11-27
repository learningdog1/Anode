package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.BackboneRouteTagger
import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.HopLossClassification
import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.ReverseTracerouteCodec
import com.nodeseek.app.domain.netbench.TracerouteConfig
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteHop
import com.nodeseek.app.domain.netbench.TracerouteProgress
import com.nodeseek.app.domain.netbench.TracerouteSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.net.InetAddress
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstraction for reverse DNS (PTR) lookup on hop IPs so JVM tests never hit live DNS.
 */
fun interface ReverseDnsLookup {
    suspend fun lookupPtr(ip: String): String?
}

object SystemReverseDnsLookup : ReverseDnsLookup {
    override suspend fun lookupPtr(ip: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val host = InetAddress.getByName(ip).canonicalHostName
            host.takeIf { it.isNotBlank() && !it.equals(ip, ignoreCase = true) }
        }.getOrNull()
    }
}

/**
 * Pure command builder and output parser for Stage-2 `/system/bin/ping -t <ttl>` traceroute fallback.
 */
object TraceroutePingFallback {
    private val FROM_HOP_IP_REGEX = Regex(
        """(?i)^(?:\d+\s+bytes\s+)?from\s+(?:[^\s(:]+\s*\()?([0-9a-fA-F:.]+?)\)?(?::\s+|\s+)(.*)$""",
    )
    private val TIME_EXCEEDED_REGEX = Regex(
        """(?i)(?:time\s+to\s+live\s+exceeded|time\s+exceeded|hop\s+limit(?:\s+exceeded)?)""",
    )
    private val UNREACHABLE_REGEX = Regex(
        """(?i)(?:destination\s+.+?unreachable|packet\s+filtered|administratively\s+prohibited|\bprohibited\b)""",
    )

    fun buildTtlPingCommand(
        targetIp: String,
        ipFamily: IpFamily,
        ttl: Int,
        timeoutSec: Int,
        interfaceName: String? = null,
        preferPing6Binary: Boolean = false,
        includeNumericFlag: Boolean = true,
    ): List<String> = buildList {
        val usePing6 = ipFamily == IpFamily.IPV6 && preferPing6Binary
        add(if (usePing6) IcmpPingParser.DEFAULT_PING6_BINARY else IcmpPingParser.DEFAULT_PING_BINARY)
        if (ipFamily == IpFamily.IPV6 && !usePing6) {
            add("-6")
        }
        if (includeNumericFlag) {
            add("-n")
        }
        add("-t")
        add(ttl.coerceIn(1, 255).toString())
        add("-c")
        add("1")
        add("-W")
        add(timeoutSec.coerceIn(1, 10).toString())
        if (!interfaceName.isNullOrBlank()) {
            add("-I")
            add(interfaceName.trim())
        }
        add(targetIp.trim())
    }

    /**
     * Parses a single streaming stdout/stderr line from `/system/bin/ping -t <ttl>` so callers can
     * record accurate RTT and terminate the subprocess immediately instead of waiting out `-W`.
     */
    fun parseTtlPingLine(
        rawLine: String,
        targetIp: String,
        measuredElapsedMs: Double,
        sequence: Int = 1,
    ): TracerouteProbeReply? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val sample = IcmpPingParser.parseLine(line, fallbackSeq = sequence)
        if (sample != null && sample.success) {
            return TracerouteProbeReply(
                kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                responderIp = targetIp,
                sequence = sequence,
                rttMs = sample.rttMs ?: measuredElapsedMs,
            )
        }
        val fromMatch = FROM_HOP_IP_REGEX.matchEntire(line)
        if (fromMatch != null) {
            val responder = fromMatch.groupValues[1].trim()
            val detail = fromMatch.groupValues[2]
            if (NetBenchTargetParser.detectIpFamily(responder) != IpFamily.UNKNOWN) {
                return when {
                    TIME_EXCEEDED_REGEX.containsMatchIn(detail) -> TracerouteProbeReply(
                        kind = TracerouteProbeReply.Kind.TIME_EXCEEDED,
                        responderIp = responder,
                        sequence = sequence,
                        rttMs = measuredElapsedMs,
                    )
                    UNREACHABLE_REGEX.containsMatchIn(detail) -> TracerouteProbeReply(
                        kind = TracerouteProbeReply.Kind.DEST_UNREACHABLE,
                        responderIp = responder,
                        sequence = sequence,
                        rttMs = measuredElapsedMs,
                    )
                    else -> TracerouteProbeReply(
                        kind = TracerouteProbeReply.Kind.TIME_EXCEEDED,
                        responderIp = responder,
                        sequence = sequence,
                        rttMs = measuredElapsedMs,
                    )
                }
            }
        }
        val isTimeExceeded = TIME_EXCEEDED_REGEX.containsMatchIn(line)
        val isUnreachable = !isTimeExceeded && UNREACHABLE_REGEX.containsMatchIn(line)
        if (isTimeExceeded || isUnreachable) {
            val ipToken = line.split(Regex("""\s+""")).firstNotNullOfOrNull { tok ->
                val stripped = tok.trim('(', ')', ',')
                val candidate = if (NetBenchTargetParser.detectIpFamily(stripped) != IpFamily.UNKNOWN) {
                    stripped
                } else {
                    stripped.trimEnd(':')
                }
                candidate.takeIf { NetBenchTargetParser.detectIpFamily(it) != IpFamily.UNKNOWN }
            }
            if (ipToken != null) {
                return TracerouteProbeReply(
                    kind = if (isUnreachable) {
                        TracerouteProbeReply.Kind.DEST_UNREACHABLE
                    } else {
                        TracerouteProbeReply.Kind.TIME_EXCEEDED
                    },
                    responderIp = ipToken,
                    sequence = sequence,
                    rttMs = measuredElapsedMs,
                )
            }
        }
        return null
    }

    fun parseTtlPingOutput(
        lines: List<String>,
        targetIp: String,
        measuredElapsedMs: Double,
        sequence: Int = 1,
    ): TracerouteProbeReply {
        for (rawLine in lines) {
            val parsed = parseTtlPingLine(
                rawLine = rawLine,
                targetIp = targetIp,
                measuredElapsedMs = measuredElapsedMs,
                sequence = sequence,
            )
            if (parsed != null) return parsed
        }
        return TracerouteProbeReply(
            kind = TracerouteProbeReply.Kind.TIMEOUT,
            responderIp = null,
            sequence = sequence,
            rttMs = null,
        )
    }
}

/**
 * Multi-protocol, concurrent visual Traceroute / MTR engine for non-root Android:
 * - **Stage 1 (API 31+)**: Concurrent per-TTL unprivileged kernel probing with automatic protocol
 *   adaptation (`SOCK_DGRAM IPPROTO_ICMP` -> `SOCK_DGRAM IPPROTO_UDP` -> `SOCK_STREAM IPPROTO_TCP`
 *   SYN via `IP_TTL` + `IP_RECVERR` + `MSG_ERRQUEUE`) bound directly to the underlying physical
 *   `Network` (`Network.bindSocket(fd)`) when direct mode is active.
 * - **Stage 2 (API <31 or OEM kernel fallback)**: Concurrent batched `/system/bin/ping -n -t <ttl> -c 1`
 *   with immediate early-exit parsing on the first `From ... Time to live exceeded` or Echo Reply line
 *   (avoiding `-W` idle waits and reporting true hop RTT).
 * - Strictly honors VPN lockdown guards so traffic never silently leaks through VPN `tun0`
 *   when `useProxy == false`.
 */
class TracerouteRunner(
    private val resolver: NetBenchTargetResolver = NetBenchTargetResolver(),
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
    private val kernelOps: TracerouteKernelOps = AndroidOsTracerouteKernelOps(),
    private val pingExecutor: PingProcessExecutor = SystemPingProcessExecutor,
    private val ipIntelligenceClient: IpIntelligenceClient = IpIntelligenceClient(networkBridge = networkBridge),
    private val reverseDnsLookup: ReverseDnsLookup = SystemReverseDnsLookup,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrentProbes: Int = DEFAULT_CONCURRENT_PROBES,
    private val overallTraceBudgetMs: Long = DEFAULT_OVERALL_TRACE_BUDGET_MS,
) {
    private data class HopAccumulator(
        val hopNumber: Int,
        var primaryIp: String? = null,
        val rtts: MutableList<Double> = mutableListOf(),
        var sent: Int = 0,
        var received: Int = 0,
        var reachedTarget: Boolean = false,
    )

    private data class HopEnrichment(
        val rdns: String? = null,
        val intel: IpIntelligenceReport? = null,
    )

    private class EarlyPingReplySignal(val reply: TracerouteProbeReply) : CancellationException("Early ping reply")

    fun run(
        config: TracerouteConfig,
        preResolvedEndpoint: ResolvedEndpoint? = null,
    ): Flow<TracerouteProgress> = flow {
        val maxHops = config.normalizedMaxHops
        val totalRounds = config.normalizedProbesPerHop
        val timeoutMs = config.normalizedTimeoutMs
        val tcpProbePort = config.normalizedTcpProbePort

        val endpoint = preResolvedEndpoint ?: resolver.resolve(
            rawTarget = config.target,
            defaultPort = tcpProbePort,
            preferredFamily = config.preferredFamily,
            useProxy = config.useProxy,
        ).getOrElse { err ->
            val msg = err.message ?: "无法解析目标主机"
            val emptySummary = buildSummary(
                targetInput = config.target,
                targetIp = "",
                ipFamily = IpFamily.UNKNOWN,
                bindingMode = IcmpNetworkBindingMode.SYSTEM_DEFAULT,
                boundIface = null,
                hops = emptyList(),
                enrichments = emptyMap(),
                errorMessage = msg,
            )
            emit(
                TracerouteProgress(
                    resolvedTarget = null,
                    currentHop = 0,
                    maxHops = maxHops,
                    completedRounds = 0,
                    totalRounds = totalRounds,
                    hops = emptyList(),
                    summary = emptySummary,
                    isFinished = true,
                    errorMessage = msg,
                ),
            )
            return@flow
        }

        val resolvedTarget = endpoint.resolvedTarget
        val targetIp = resolvedTarget.resolvedIp
        val ipFamily = resolvedTarget.ipFamily

        val inspection = networkBridge.inspectCurrentNetworks()
        val channelSelection = inspection.selectChannel(config.useProxy)
        if (channelSelection is ChannelSelectionResult.Blocked) {
            val msg = channelSelection.message
            val blockedSummary = buildSummary(
                targetInput = config.target,
                targetIp = targetIp,
                ipFamily = ipFamily,
                bindingMode = IcmpNetworkBindingMode.SYSTEM_DEFAULT,
                boundIface = null,
                hops = emptyList(),
                enrichments = emptyMap(),
                errorMessage = msg,
            )
            emit(
                TracerouteProgress(
                    resolvedTarget = resolvedTarget,
                    currentHop = 0,
                    maxHops = maxHops,
                    completedRounds = 0,
                    totalRounds = totalRounds,
                    hops = emptyList(),
                    summary = blockedSummary,
                    isFinished = true,
                    errorMessage = msg,
                ),
            )
            return@flow
        }

        val selectedChannel = (channelSelection as ChannelSelectionResult.Selected).channel
        val directIfaceName = inspection.plan.directUnderlying?.interfaceName
        val isVpnActive = inspection.plan.isVpnActive

        // Allow kernel Stage 1 whenever supported AND either:
        // - bound directly to physical Network (bypassing VPN), OR
        // - no VPN is active, OR
        // - user explicitly requested proxy routing.
        var useKernelStage = kernelOps.isSupported() &&
            (selectedChannel.supportsFileDescriptorBind || !isVpnActive || config.useProxy)
        val channelForKernelBind: NetBenchSocketChannel? =
            if (!config.useProxy && selectedChannel.supportsFileDescriptorBind) selectedChannel else null
        val useConcurrentMultiProtocol = useKernelStage && kernelOps.supportsConcurrentMultiProtocol

        var activeFd: FileDescriptor? = null
        val bindingMode = AtomicReference(
            when {
                config.useProxy -> IcmpNetworkBindingMode.SYSTEM_DEFAULT
                useKernelStage && channelForKernelBind != null -> IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND
                useKernelStage -> IcmpNetworkBindingMode.SYSTEM_DEFAULT
                !directIfaceName.isNullOrBlank() -> IcmpNetworkBindingMode.INTERFACE_BOUND
                isVpnActive -> IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK
                else -> IcmpNetworkBindingMode.SYSTEM_DEFAULT
            },
        )
        val boundInterfaceName = AtomicReference<String?>(if (!config.useProxy) directIfaceName else null)
        val preferPing6Binary = AtomicBoolean(false)
        val includeNumericFlag = AtomicBoolean(true)

        // Legacy single-socket test doubles open one shared FD up front.
        if (useKernelStage && !useConcurrentMultiProtocol) {
            try {
                activeFd = kernelOps.openBoundSocket(ipFamily, channelForKernelBind)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
                    throwable = t,
                    isVpnActive = isVpnActive,
                    hasDirectCandidate = inspection.plan.hasDirectCandidate,
                )
                if (isVpnActive && reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
                    if (directIfaceName.isNullOrBlank()) {
                        val msg = "${reason.userHint}；请开启「经由代理测试」"
                        val errSummary = buildSummary(
                            targetInput = config.target,
                            targetIp = targetIp,
                            ipFamily = ipFamily,
                            bindingMode = IcmpNetworkBindingMode.SYSTEM_DEFAULT,
                            boundIface = null,
                            hops = emptyList(),
                            enrichments = emptyMap(),
                            errorMessage = msg,
                        )
                        emit(
                            TracerouteProgress(
                                resolvedTarget = resolvedTarget,
                                currentHop = 0,
                                maxHops = maxHops,
                                completedRounds = 0,
                                totalRounds = totalRounds,
                                hops = emptyList(),
                                summary = errSummary,
                                isFinished = true,
                                errorMessage = msg,
                            ),
                        )
                        return@flow
                    }
                    useKernelStage = false
                    bindingMode.set(IcmpNetworkBindingMode.INTERFACE_BOUND)
                } else {
                    useKernelStage = false
                    bindingMode.set(
                        if (!config.useProxy && !directIfaceName.isNullOrBlank()) {
                            IcmpNetworkBindingMode.INTERFACE_BOUND
                        } else if (!config.useProxy && isVpnActive) {
                            IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK
                        } else {
                            IcmpNetworkBindingMode.SYSTEM_DEFAULT
                        },
                    )
                }
            }
        }

        val enrichmentCache = ConcurrentHashMap<String, HopEnrichment>()
        val accumulators = linkedMapOf<Int, HopAccumulator>()
        val hopBestProtocol = ConcurrentHashMap<Int, TracerouteProbeProtocol>()
        val protocolCounts = ConcurrentHashMap<TracerouteProbeProtocol, AtomicInteger>()
        val disabledProtocols = ConcurrentHashMap.newKeySet<TracerouteProbeProtocol>()
        var primaryKernelProtocol = TracerouteProbeProtocol.ICMP
        val identifier = ((nanoClock().toInt() xor endpoint.address.hashCode()) and 0xFFFF)
            .let { if (it == 0) 0x5452 else it }
        val seqCounter = AtomicInteger(1)
        val kernelLockdownBlocked = AtomicBoolean(false)
        val lockdownErrorMsg = AtomicReference<String?>(null)
        val traceDeadlineNanos = nanoClock() + overallTraceBudgetMs.coerceAtLeast(5_000L) * 1_000_000L
        val concurrency = maxConcurrentProbes.coerceIn(1, 12)

        fun fallbackBindingModeAfterKernelFailure(): IcmpNetworkBindingMode = when {
            config.useProxy -> IcmpNetworkBindingMode.SYSTEM_DEFAULT
            !directIfaceName.isNullOrBlank() -> IcmpNetworkBindingMode.INTERFACE_BOUND
            isVpnActive -> IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK
            else -> IcmpNetworkBindingMode.SYSTEM_DEFAULT
        }

        fun resolveToolName(): String {
            if (!useKernelStage) return "ping-TTL"
            val used = protocolCounts.entries.filter { it.value.get() > 0 }.sortedByDescending { it.value.get() }
            return when {
                used.isEmpty() -> "Kernel-${primaryKernelProtocol.label}"
                used.size == 1 -> "Kernel-${used.first().key.label}"
                else -> "Kernel-${used.first().key.label}+${used[1].key.label}"
            }
        }

        fun recordReply(ttl: Int, reply: TracerouteProbeReply): Boolean {
            val acc = accumulators.getOrPut(ttl) { HopAccumulator(hopNumber = ttl) }
            acc.sent += 1
            if (reply.kind == TracerouteProbeReply.Kind.TIMEOUT) {
                return false
            }
            acc.received += 1
            if (acc.primaryIp == null && !reply.responderIp.isNullOrBlank()) {
                acc.primaryIp = reply.responderIp
            }
            reply.rttMs?.takeIf { it.isFinite() && it >= 0.0 }?.let { acc.rtts += it }
            if (useKernelStage) {
                hopBestProtocol[ttl] = reply.protocol
                protocolCounts.getOrPut(reply.protocol) { AtomicInteger(0) }.incrementAndGet()
            }
            val responderMatchesTarget = !reply.responderIp.isNullOrBlank() &&
                ipsMatch(reply.responderIp, targetIp)
            val isTerminal = reply.kind == TracerouteProbeReply.Kind.ECHO_REPLY ||
                (reply.kind == TracerouteProbeReply.Kind.DEST_UNREACHABLE && responderMatchesTarget)
            if (isTerminal) {
                acc.reachedTarget = true
            }
            return isTerminal
        }

        suspend fun probeViaSubprocess(ttl: Int, seq: Int, perProbeTimeoutMs: Int): TracerouteProbeReply {
            val timeoutSec = ((perProbeTimeoutMs + 999) / 1000).coerceIn(1, 10)
            val ifaceForCmd = if (!config.useProxy) boundInterfaceName.get() else null
            val result = runSubprocessProbe(
                targetIp = targetIp,
                ipFamily = ipFamily,
                ttl = ttl,
                timeoutSec = timeoutSec,
                timeoutMs = perProbeTimeoutMs,
                interfaceName = ifaceForCmd,
                preferPing6Binary = preferPing6Binary.get(),
                includeNumericFlag = includeNumericFlag.get(),
                sequence = seq,
            )
            if (result.binaryUnsupported) {
                if (ipFamily == IpFamily.IPV6 && !preferPing6Binary.get()) {
                    preferPing6Binary.set(true)
                    return runSubprocessProbe(
                        targetIp = targetIp,
                        ipFamily = ipFamily,
                        ttl = ttl,
                        timeoutSec = timeoutSec,
                        timeoutMs = perProbeTimeoutMs,
                        interfaceName = ifaceForCmd,
                        preferPing6Binary = true,
                        includeNumericFlag = includeNumericFlag.get(),
                        sequence = seq,
                    ).reply
                }
                if (includeNumericFlag.get()) {
                    includeNumericFlag.set(false)
                    return runSubprocessProbe(
                        targetIp = targetIp,
                        ipFamily = ipFamily,
                        ttl = ttl,
                        timeoutSec = timeoutSec,
                        timeoutMs = perProbeTimeoutMs,
                        interfaceName = ifaceForCmd,
                        preferPing6Binary = preferPing6Binary.get(),
                        includeNumericFlag = false,
                        sequence = seq,
                    ).reply
                }
            }
            if (result.interfaceRejected && ifaceForCmd != null) {
                if (isVpnActive) {
                    kernelLockdownBlocked.set(true)
                    lockdownErrorMsg.set("${DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED.userHint}；请开启「经由代理测试」")
                    return TracerouteProbeReply(
                        kind = TracerouteProbeReply.Kind.TIMEOUT,
                        responderIp = null,
                        sequence = seq,
                        rttMs = null,
                    )
                }
                boundInterfaceName.set(null)
                bindingMode.set(IcmpNetworkBindingMode.SYSTEM_DEFAULT)
                return runSubprocessProbe(
                    targetIp = targetIp,
                    ipFamily = ipFamily,
                    ttl = ttl,
                    timeoutSec = timeoutSec,
                    timeoutMs = perProbeTimeoutMs,
                    interfaceName = null,
                    preferPing6Binary = preferPing6Binary.get(),
                    includeNumericFlag = includeNumericFlag.get(),
                    sequence = seq,
                ).reply
            }
            return result.reply
        }

        suspend fun probeKernelWithProtocol(
            ttl: Int,
            protocol: TracerouteProbeProtocol,
            perProbeTimeoutMs: Int,
        ): TracerouteProbeReply {
            val seq = seqCounter.getAndIncrement()
            return try {
                kernelOps.probeHop(
                    targetAddress = endpoint.address,
                    ipFamily = ipFamily,
                    ttl = ttl,
                    identifier = identifier,
                    sequence = seq,
                    timeoutMs = perProbeTimeoutMs,
                    channel = channelForKernelBind,
                    protocol = protocol,
                    targetPort = tcpProbePort,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val lockdown = AndroidOsTracerouteKernelOps.isLockdownBindFailure(
                    throwable = t,
                    isVpnActive = isVpnActive,
                    hasDirectCandidate = inspection.plan.hasDirectCandidate,
                )
                if (lockdown && directIfaceName.isNullOrBlank()) {
                    kernelLockdownBlocked.set(true)
                    lockdownErrorMsg.set("${DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED.userHint}；请开启「经由代理测试」")
                } else {
                    disabledProtocols.add(protocol)
                }
                TracerouteProbeReply(
                    kind = TracerouteProbeReply.Kind.TIMEOUT,
                    responderIp = null,
                    sequence = seq,
                    rttMs = null,
                    protocol = protocol,
                )
            }
        }

        suspend fun probeAdaptiveSingleHop(
            ttl: Int,
            allowProtocolFallback: Boolean,
        ): TracerouteProbeReply {
            currentCoroutineContext().ensureActive()
            if (kernelLockdownBlocked.get()) {
                return TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, seqCounter.getAndIncrement(), null)
            }
            if (!useKernelStage) {
                return probeViaSubprocess(ttl, seqCounter.getAndIncrement(), timeoutMs)
            }
            val preferred = hopBestProtocol[ttl] ?: primaryKernelProtocol
            val order = buildList {
                if (preferred !in disabledProtocols) add(preferred)
                if (allowProtocolFallback) {
                    for (proto in listOf(
                        TracerouteProbeProtocol.ICMP,
                        TracerouteProbeProtocol.UDP,
                        TracerouteProbeProtocol.TCP,
                    )) {
                        if (proto != preferred && proto !in disabledProtocols) add(proto)
                    }
                }
            }
            if (order.isEmpty()) {
                return probeViaSubprocess(ttl, seqCounter.getAndIncrement(), timeoutMs)
            }
            val primaryTimeout = timeoutMs.coerceIn(300, 1_500)
            val fallbackTimeout = (timeoutMs * 3 / 4).coerceIn(300, 950)
            var lastReply = TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, 0, null)
            order.forEachIndexed { idx, proto ->
                if (kernelLockdownBlocked.get()) return lastReply
                val tmo = if (idx == 0) primaryTimeout else fallbackTimeout
                val reply = probeKernelWithProtocol(ttl, proto, tmo)
                lastReply = reply
                if (reply.kind != TracerouteProbeReply.Kind.TIMEOUT) {
                    return reply
                }
            }
            return lastReply
        }

        suspend fun probeLegacySingleHop(ttl: Int): TracerouteProbeReply {
            currentCoroutineContext().ensureActive()
            val seq = seqCounter.getAndIncrement()
            val fd = activeFd
            if (useKernelStage && fd != null) {
                try {
                    kernelOps.sendEchoWithTtl(
                        fd = fd,
                        targetAddress = endpoint.address,
                        ipFamily = ipFamily,
                        ttl = ttl,
                        identifier = identifier,
                        sequence = seq,
                    )
                    return kernelOps.awaitReplyOrError(
                        fd = fd,
                        targetAddress = endpoint.address,
                        ipFamily = ipFamily,
                        identifier = identifier,
                        expectedSequence = seq,
                        timeoutMs = timeoutMs,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val lockdown = AndroidOsTracerouteKernelOps.isLockdownBindFailure(
                        throwable = t,
                        isVpnActive = isVpnActive,
                        hasDirectCandidate = inspection.plan.hasDirectCandidate,
                    )
                    if (lockdown && directIfaceName.isNullOrBlank()) {
                        kernelLockdownBlocked.set(true)
                        lockdownErrorMsg.set("${DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED.userHint}；请开启「经由代理测试」")
                        return TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.TIMEOUT,
                            responderIp = null,
                            sequence = seq,
                            rttMs = null,
                        )
                    }
                    useKernelStage = false
                    activeFd?.let { kernelOps.closeQuietly(it) }
                    activeFd = null
                    bindingMode.set(fallbackBindingModeAfterKernelFailure())
                }
            }
            return probeViaSubprocess(ttl, seq, timeoutMs)
        }

        try {
            // Phase 1: Path Discovery (TTL 1..maxHops)
            var discoveredLastTtl = 0
            var reachedTarget = false
            var consecutiveTimeoutsAfterReply = 0
            var sawAnyReply = false

            if (useKernelStage && !useConcurrentMultiProtocol) {
                // Serialized path for single-socket / legacy TracerouteKernelOps implementations
                for (ttl in 1..maxHops) {
                    currentCoroutineContext().ensureActive()
                    if (nanoClock() >= traceDeadlineNanos && sawAnyReply) break
                    val reply = probeLegacySingleHop(ttl)
                    if (kernelLockdownBlocked.get()) break

                    discoveredLastTtl = ttl
                    val terminal = recordReply(ttl, reply)
                    if (reply.kind != TracerouteProbeReply.Kind.TIMEOUT) {
                        sawAnyReply = true
                        consecutiveTimeoutsAfterReply = 0
                        if (terminal) reachedTarget = true
                    } else {
                        consecutiveTimeoutsAfterReply++
                    }

                    val currentHops = materializeHops(accumulators, enrichmentCache)
                    val interimSummary = buildSummary(
                        targetInput = config.target,
                        targetIp = targetIp,
                        ipFamily = ipFamily,
                        bindingMode = bindingMode.get(),
                        boundIface = boundInterfaceName.get(),
                        hops = currentHops,
                        enrichments = enrichmentCache,
                        toolName = resolveToolName(),
                    )
                    emit(
                        TracerouteProgress(
                            resolvedTarget = resolvedTarget,
                            currentHop = ttl,
                            maxHops = maxHops,
                            completedRounds = if (ttl == maxHops || reachedTarget) 1 else 0,
                            totalRounds = totalRounds,
                            hops = currentHops,
                            summary = interimSummary,
                            isFinished = false,
                        ),
                    )

                    if (reachedTarget) break
                    if (sawAnyReply && consecutiveTimeoutsAfterReply >= CONSECUTIVE_TIMEOUT_STOP_AFTER_REPLY) break
                    if (!sawAnyReply && consecutiveTimeoutsAfterReply >= CONSECUTIVE_TIMEOUT_STOP_INITIAL) break
                }
            } else {
                // Batched concurrent multi-TTL path discovery (production Android kernel multi-protocol & Stage-2 ping)
                val sem = Semaphore(concurrency)
                var batchStart = 1
                var checkedSubprocessRescue = false

                while (batchStart <= maxHops && !reachedTarget && !kernelLockdownBlocked.get()) {
                    currentCoroutineContext().ensureActive()
                    if (nanoClock() >= traceDeadlineNanos && discoveredLastTtl > 0) break

                    val batchEnd = (batchStart + concurrency - 1).coerceAtMost(maxHops)
                    val batchTtls = (batchStart..batchEnd).toList()

                    var batchReplies: Map<Int, TracerouteProbeReply> = coroutineScope {
                        batchTtls.map { ttl ->
                            async {
                                sem.withPermit {
                                    ttl to probeAdaptiveSingleHop(ttl, allowProtocolFallback = true)
                                }
                            }
                        }.awaitAll().toMap()
                    }

                    if (kernelLockdownBlocked.get()) break

                    // If kernel multi-protocol yielded zero replies on the very first batch (e.g. OEM ROM
                    // silently drops MSG_ERRQUEUE), automatically rescue with concurrent Stage-2 ping -t.
                    if (useKernelStage && !checkedSubprocessRescue && batchStart == 1 &&
                        batchReplies.values.all { it.kind == TracerouteProbeReply.Kind.TIMEOUT }
                    ) {
                        checkedSubprocessRescue = true
                        val pingRescueReplies: Map<Int, TracerouteProbeReply> = coroutineScope {
                            batchTtls.map { ttl ->
                                async {
                                    sem.withPermit {
                                        ttl to probeViaSubprocess(ttl, seqCounter.getAndIncrement(), timeoutMs)
                                    }
                                }
                            }.awaitAll().toMap()
                        }
                        if (kernelLockdownBlocked.get()) break
                        if (pingRescueReplies.values.any { it.kind != TracerouteProbeReply.Kind.TIMEOUT }) {
                            useKernelStage = false
                            bindingMode.set(fallbackBindingModeAfterKernelFailure())
                            batchReplies = pingRescueReplies
                        }
                    }

                    // Promote whichever kernel protocol responded most reliably in batch 1
                    if (useKernelStage && batchStart == 1) {
                        val winningProto = batchReplies.values
                            .filter { it.kind != TracerouteProbeReply.Kind.TIMEOUT }
                            .groupingBy { it.protocol }
                            .eachCount()
                            .maxByOrNull { it.value }
                            ?.key
                        if (winningProto != null) {
                            primaryKernelProtocol = winningProto
                        }
                    }

                    // Apply replies in strict TTL order and stop cleanly at the earliest terminal hop
                    var stopDiscovery = false
                    for (ttl in batchTtls) {
                        val reply = batchReplies[ttl] ?: TracerouteProbeReply(
                            TracerouteProbeReply.Kind.TIMEOUT,
                            null,
                            0,
                            null,
                        )
                        discoveredLastTtl = ttl
                        val terminal = recordReply(ttl, reply)
                        if (reply.kind != TracerouteProbeReply.Kind.TIMEOUT) {
                            sawAnyReply = true
                            consecutiveTimeoutsAfterReply = 0
                            if (terminal) {
                                reachedTarget = true
                                stopDiscovery = true
                                break
                            }
                        } else {
                            consecutiveTimeoutsAfterReply++
                            if (sawAnyReply && consecutiveTimeoutsAfterReply >= CONSECUTIVE_TIMEOUT_STOP_AFTER_REPLY) {
                                stopDiscovery = true
                                break
                            }
                            if (!sawAnyReply && consecutiveTimeoutsAfterReply >= CONSECUTIVE_TIMEOUT_STOP_INITIAL) {
                                stopDiscovery = true
                                break
                            }
                        }
                    }

                    val currentHops = materializeHops(accumulators, enrichmentCache)
                    val interimSummary = buildSummary(
                        targetInput = config.target,
                        targetIp = targetIp,
                        ipFamily = ipFamily,
                        bindingMode = bindingMode.get(),
                        boundIface = boundInterfaceName.get(),
                        hops = currentHops,
                        enrichments = enrichmentCache,
                        toolName = resolveToolName(),
                    )
                    emit(
                        TracerouteProgress(
                            resolvedTarget = resolvedTarget,
                            currentHop = discoveredLastTtl,
                            maxHops = maxHops,
                            completedRounds = if (discoveredLastTtl == maxHops || reachedTarget || stopDiscovery) 1 else 0,
                            totalRounds = totalRounds,
                            hops = currentHops,
                            summary = interimSummary,
                            isFinished = false,
                        ),
                    )

                    if (stopDiscovery) break
                    batchStart = batchEnd + 1
                }
            }

            if (kernelLockdownBlocked.get()) {
                val errMsg = lockdownErrorMsg.get()
                val errSummary = buildSummary(
                    targetInput = config.target,
                    targetIp = targetIp,
                    ipFamily = ipFamily,
                    bindingMode = bindingMode.get(),
                    boundIface = boundInterfaceName.get(),
                    hops = emptyList(),
                    enrichments = enrichmentCache,
                    toolName = resolveToolName(),
                    errorMessage = errMsg,
                )
                emit(
                    TracerouteProgress(
                        resolvedTarget = resolvedTarget,
                        currentHop = 0,
                        maxHops = maxHops,
                        completedRounds = 0,
                        totalRounds = totalRounds,
                        hops = emptyList(),
                        summary = errSummary,
                        isFinished = true,
                        errorMessage = errMsg,
                    ),
                )
                return@flow
            }

            // Trim trailing consecutive timeouts if we stopped due to timeout streak
            val lastActiveHop = accumulators.values.lastOrNull { it.received > 0 }?.hopNumber ?: discoveredLastTtl
            val activeRangeEnd = lastActiveHop.coerceAtLeast(1).coerceAtMost(discoveredLastTtl.coerceAtLeast(1))
            if (activeRangeEnd < discoveredLastTtl) {
                val toRemove = accumulators.keys.filter { it > activeRangeEnd }
                toRemove.forEach { accumulators.remove(it) }
            }

            // Phase 2: MTR Multi-Round Probing across discovered hops
            var completedRounds = 1
            if (accumulators.values.any { it.received > 0 }) {
                if (useKernelStage && !useConcurrentMultiProtocol) {
                    for (round in 2..totalRounds) {
                        currentCoroutineContext().ensureActive()
                        if (nanoClock() >= traceDeadlineNanos) break
                        for (ttl in 1..activeRangeEnd) {
                            currentCoroutineContext().ensureActive()
                            val reply = probeLegacySingleHop(ttl)
                            if (kernelLockdownBlocked.get()) break
                            recordReply(ttl, reply)
                        }
                        if (kernelLockdownBlocked.get()) break
                        completedRounds = round
                        val roundHops = materializeHops(accumulators, enrichmentCache)
                        val roundSummary = buildSummary(
                            targetInput = config.target,
                            targetIp = targetIp,
                            ipFamily = ipFamily,
                            bindingMode = bindingMode.get(),
                            boundIface = boundInterfaceName.get(),
                            hops = roundHops,
                            enrichments = enrichmentCache,
                            toolName = resolveToolName(),
                        )
                        emit(
                            TracerouteProgress(
                                resolvedTarget = resolvedTarget,
                                currentHop = activeRangeEnd,
                                maxHops = activeRangeEnd,
                                completedRounds = round,
                                totalRounds = totalRounds,
                                hops = roundHops,
                                summary = roundSummary,
                                isFinished = false,
                            ),
                        )
                    }
                } else {
                    val sem = Semaphore(concurrency)
                    for (round in 2..totalRounds) {
                        currentCoroutineContext().ensureActive()
                        if (nanoClock() >= traceDeadlineNanos) break

                        val roundReplies: Map<Int, TracerouteProbeReply> = coroutineScope {
                            (1..activeRangeEnd).map { ttl ->
                                async {
                                    sem.withPermit {
                                        val allowFallback = round == 2 && accumulators[ttl]?.primaryIp == null
                                        ttl to probeAdaptiveSingleHop(ttl, allowProtocolFallback = allowFallback)
                                    }
                                }
                            }.awaitAll().toMap()
                        }
                        if (kernelLockdownBlocked.get()) break

                        for (ttl in 1..activeRangeEnd) {
                            val reply = roundReplies[ttl] ?: TracerouteProbeReply(
                                TracerouteProbeReply.Kind.TIMEOUT,
                                null,
                                0,
                                null,
                            )
                            recordReply(ttl, reply)
                        }
                        completedRounds = round
                        val roundHops = materializeHops(accumulators, enrichmentCache)
                        val roundSummary = buildSummary(
                            targetInput = config.target,
                            targetIp = targetIp,
                            ipFamily = ipFamily,
                            bindingMode = bindingMode.get(),
                            boundIface = boundInterfaceName.get(),
                            hops = roundHops,
                            enrichments = enrichmentCache,
                            toolName = resolveToolName(),
                        )
                        emit(
                            TracerouteProgress(
                                resolvedTarget = resolvedTarget,
                                currentHop = activeRangeEnd,
                                maxHops = activeRangeEnd,
                                completedRounds = round,
                                totalRounds = totalRounds,
                                hops = roundHops,
                                summary = roundSummary,
                                isFinished = false,
                            ),
                        )
                    }
                }
            }

            // Phase 3: Bounded concurrent GeoIP + rDNS enrichment for unique public hop IPs
            val uniquePublicIps = accumulators.values
                .mapNotNull { it.primaryIp }
                .distinct()
                .filter { !BackboneRouteTagger.isPrivateOrCgnat(it) }
            withTimeoutOrNull(ENRICH_TOTAL_TIMEOUT_MS) {
                enrichPublicIpsBounded(uniquePublicIps, enrichmentCache)
            }

            val finalHops = materializeHops(accumulators, enrichmentCache)
            val allTimedOut = finalHops.none { !it.isTimeoutOnly }
            val finalError = if (allTimedOut) {
                "去程路由探测全部超时（当前网络或运营商可能限制了 ICMP/UDP/TCP TTL 回包）"
            } else {
                null
            }
            val finalSummary = buildSummary(
                targetInput = config.target,
                targetIp = targetIp,
                ipFamily = ipFamily,
                bindingMode = bindingMode.get(),
                boundIface = boundInterfaceName.get(),
                hops = finalHops,
                enrichments = enrichmentCache,
                toolName = resolveToolName(),
                errorMessage = finalError,
            )
            emit(
                TracerouteProgress(
                    resolvedTarget = resolvedTarget,
                    currentHop = activeRangeEnd,
                    maxHops = activeRangeEnd,
                    completedRounds = completedRounds,
                    totalRounds = totalRounds,
                    hops = finalHops,
                    summary = finalSummary,
                    isFinished = true,
                    errorMessage = finalError,
                ),
            )
        } finally {
            activeFd?.let { kernelOps.closeQuietly(it) }
        }
    }.flowOn(ioDispatcher)

    private data class SubprocessProbeOutcome(
        val reply: TracerouteProbeReply,
        val interfaceRejected: Boolean = false,
        val binaryUnsupported: Boolean = false,
    )

    private suspend fun runSubprocessProbe(
        targetIp: String,
        ipFamily: IpFamily,
        ttl: Int,
        timeoutSec: Int,
        timeoutMs: Int,
        interfaceName: String?,
        preferPing6Binary: Boolean,
        includeNumericFlag: Boolean,
        sequence: Int,
    ): SubprocessProbeOutcome {
        val command = TraceroutePingFallback.buildTtlPingCommand(
            targetIp = targetIp,
            ipFamily = ipFamily,
            ttl = ttl,
            timeoutSec = timeoutSec,
            interfaceName = interfaceName,
            preferPing6Binary = preferPing6Binary,
            includeNumericFlag = includeNumericFlag,
        )
        val lines = ArrayList<String>(8)
        val stderrLines = ArrayList<String>(4)
        var earlyReply: TracerouteProbeReply? = null
        val startNanos = nanoClock()
        try {
            pingExecutor.execute(
                PingProcessRequest(
                    command = command,
                    timeoutMs = timeoutMs.toLong() + 900L,
                ),
            ).collect { ev ->
                when (ev) {
                    is PingExecutionEvent.StdoutLine -> {
                        lines += ev.line
                        val elapsedMs = ((nanoClock() - startNanos).coerceAtLeast(0L)) / 1_000_000.0
                        val parsed = TraceroutePingFallback.parseTtlPingLine(
                            rawLine = ev.line,
                            targetIp = targetIp,
                            measuredElapsedMs = elapsedMs,
                            sequence = sequence,
                        )
                        if (parsed != null) {
                            throw EarlyPingReplySignal(parsed)
                        }
                    }
                    is PingExecutionEvent.StderrLine -> {
                        stderrLines += ev.line
                        val elapsedMs = ((nanoClock() - startNanos).coerceAtLeast(0L)) / 1_000_000.0
                        val parsed = TraceroutePingFallback.parseTtlPingLine(
                            rawLine = ev.line,
                            targetIp = targetIp,
                            measuredElapsedMs = elapsedMs,
                            sequence = sequence,
                        )
                        if (parsed != null) {
                            throw EarlyPingReplySignal(parsed)
                        }
                    }
                    is PingExecutionEvent.Exited -> Unit
                }
            }
        } catch (signal: EarlyPingReplySignal) {
            earlyReply = signal.reply
        }

        if (earlyReply != null) {
            return SubprocessProbeOutcome(reply = earlyReply)
        }

        val elapsedMs = ((nanoClock() - startNanos).coerceAtLeast(0L)) / 1_000_000.0
        val allLines = lines + stderrLines
        val failureKind = IcmpPingParser.classifyFailure(
            outputLines = allLines,
            packetStats = null,
            hadInterfaceFlag = !interfaceName.isNullOrBlank(),
            hadFractionalInterval = false,
            hadNumericFlag = includeNumericFlag,
        )
        val reply = TraceroutePingFallback.parseTtlPingOutput(
            lines = allLines,
            targetIp = targetIp,
            measuredElapsedMs = elapsedMs,
            sequence = sequence,
        )
        val ifaceRejected = reply.kind == TracerouteProbeReply.Kind.TIMEOUT &&
            failureKind == IcmpPingParser.PingFailureKind.INTERFACE_BIND_REJECTED
        val binUnsupported = reply.kind == TracerouteProbeReply.Kind.TIMEOUT &&
            failureKind == IcmpPingParser.PingFailureKind.BINARY_OR_FLAG_UNSUPPORTED
        return SubprocessProbeOutcome(
            reply = reply,
            interfaceRejected = ifaceRejected,
            binaryUnsupported = binUnsupported,
        )
    }

    private suspend fun enrichPublicIpsBounded(
        ips: List<String>,
        cache: ConcurrentHashMap<String, HopEnrichment>,
    ) {
        val pending = ips.filter { !cache.containsKey(it) }
        if (pending.isEmpty()) return
        val semaphore = Semaphore(ENRICH_CONCURRENCY)
        coroutineScope {
            pending.map { ip ->
                async {
                    semaphore.withPermit {
                        val rdns = runCatching {
                            withTimeoutOrNull(ENRICH_TIMEOUT_MS) { reverseDnsLookup.lookupPtr(ip) }
                        }.getOrNull()
                        val intel = runCatching {
                            withTimeoutOrNull(ENRICH_TIMEOUT_MS) { ipIntelligenceClient.lookupTargetIp(ip) }
                        }.getOrNull()
                        cache[ip] = HopEnrichment(rdns = rdns, intel = intel)
                    }
                }
            }.awaitAll()
        }
    }

    private fun materializeHops(
        accumulators: Map<Int, HopAccumulator>,
        enrichments: Map<String, HopEnrichment>,
    ): List<TracerouteHop> {
        val rawHops = accumulators.values.sortedBy { it.hopNumber }.map { acc ->
            val ip = acc.primaryIp
            val priv = BackboneRouteTagger.isPrivateOrCgnat(ip)
            val enrich = ip?.let { enrichments[it] }
            val intel = enrich?.intel
            val rdns = enrich?.rdns
            val rtts = acc.rtts
            val lossPct = if (acc.sent > 0) {
                ((acc.sent - acc.received).toDouble() / acc.sent.toDouble()) * 100.0
            } else {
                100.0
            }
            val tags = BackboneRouteTagger.classifyHop(
                ip = ip,
                asn = intel?.asn,
                rdns = rdns,
                orgName = intel?.orgName,
                ispName = intel?.ispName,
            )
            TracerouteHop(
                hopNumber = acc.hopNumber,
                ip = ip,
                hostname = rdns,
                asn = intel?.asn,
                orgName = intel?.orgName,
                ispName = intel?.ispName,
                countryCode = intel?.countryCode,
                countryName = intel?.countryName,
                regionName = intel?.regionName,
                cityName = intel?.cityName,
                sent = acc.sent,
                received = acc.received,
                lossPercent = lossPct.coerceIn(0.0, 100.0),
                minMs = rtts.minOrNull(),
                avgMs = if (rtts.isNotEmpty()) rtts.sum() / rtts.size.toDouble() else null,
                maxMs = rtts.maxOrNull(),
                p95Ms = ReverseTracerouteCodec.computePercentile(rtts, 95.0),
                jitterMdevMs = ReverseTracerouteCodec.computeStdDev(rtts),
                backboneTags = tags,
                isPrivateOrCgnat = priv,
                isTargetReached = acc.reachedTarget,
            )
        }
        return BackboneRouteTagger.classifyHopLosses(rawHops)
    }

    private fun buildSummary(
        targetInput: String,
        targetIp: String,
        ipFamily: IpFamily,
        bindingMode: IcmpNetworkBindingMode,
        boundIface: String?,
        hops: List<TracerouteHop>,
        @Suppress("UNUSED_PARAMETER") enrichments: Map<String, HopEnrichment>,
        toolName: String = if (bindingMode == IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND) "Kernel-ICMP" else "ping-TTL",
        errorMessage: String? = null,
    ): TracerouteSummary {
        val now = clockMillis()
        val peakWindow = BackboneRouteTagger.detectPeakWindow(now, zoneId)
        val pathClass = BackboneRouteTagger.classifyPath(hops, TracerouteDirection.FORWARD)
        val reached = hops.any { it.isTargetReached }
        val terminalAvg = hops.lastOrNull { it.isTargetReached }?.avgMs
            ?: hops.lastOrNull { !it.isTimeoutOnly }?.avgMs
        val peakHop = hops.filter { it.sent > 0 && it.received > 0 }.maxByOrNull { it.lossPercent }
        val hasRealLoss = hops.any { it.lossClassification == HopLossClassification.REAL_PACKET_LOSS }
        val hasRateLimitOnly = !hasRealLoss &&
            hops.any { it.lossClassification == HopLossClassification.INTERMEDIATE_RATE_LIMIT }

        return TracerouteSummary(
            direction = TracerouteDirection.FORWARD,
            targetInput = targetInput,
            targetIp = targetIp,
            ipFamily = ipFamily,
            sourceLabel = "手机端直连",
            toolName = toolName,
            bindingMode = bindingMode,
            boundInterfaceName = boundIface,
            hops = hops,
            reachedTarget = reached,
            totalHops = hops.size,
            terminalAvgMs = terminalAvg,
            peakHopLossPercent = peakHop?.lossPercent ?: 0.0,
            peakLossHopNumber = peakHop?.takeIf { it.lossPercent > 0.0 }?.hopNumber,
            hasRealPacketLoss = hasRealLoss,
            hasIntermediateRateLimitOnly = hasRateLimitOnly,
            dominantBackboneTags = pathClass.dominantTags,
            routeSignature = pathClass.routeSignature,
            isPeakHourWindow = peakWindow.isPeakHourWindow,
            timeWindowLabel = peakWindow.label,
            testedAtEpochMillis = now,
            errorMessage = errorMessage,
        )
    }

    private fun ipsMatch(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val formsA = NetBenchTargetParser.allCanonicalIpForms(a)
        val formsB = NetBenchTargetParser.allCanonicalIpForms(b)
        return formsA.any { it in formsB }
    }

    companion object {
        private const val DEFAULT_CONCURRENT_PROBES = 6
        private const val DEFAULT_OVERALL_TRACE_BUDGET_MS = 25_000L
        private const val CONSECUTIVE_TIMEOUT_STOP_AFTER_REPLY = 6
        private const val CONSECUTIVE_TIMEOUT_STOP_INITIAL = 10
        private const val ENRICH_CONCURRENCY = 6
        private const val ENRICH_TIMEOUT_MS = 2_500L
        private const val ENRICH_TOTAL_TIMEOUT_MS = 6_000L
    }
}
