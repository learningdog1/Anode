package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.ParsedTarget
import com.nodeseek.app.domain.netbench.PreferredIpFamily
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingConfig
import com.nodeseek.app.domain.netbench.TcpPingProgress
import com.nodeseek.app.domain.netbench.TcpPingSample
import com.nodeseek.app.domain.netbench.TcpPingSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Abstraction over DNS lookup for testability and dual-stack resolution.
 */
fun interface NetBenchDnsResolver {
    suspend fun resolveAll(host: String): List<InetAddress>
}

object SystemNetBenchDnsResolver : NetBenchDnsResolver {
    override suspend fun resolveAll(host: String): List<InetAddress> =
        withContext(Dispatchers.IO) {
            InetAddress.getAllByName(host).toList()
        }
}

/**
 * Result of resolving a raw user target to an [InetAddress] + display metadata.
 */
data class ResolvedEndpoint(
    val parsed: ParsedTarget,
    val resolvedTarget: ResolvedTarget,
    val address: InetAddress,
    val effectivePort: Int,
)

/**
 * Resolves IPv4, bracketed/raw IPv6, domain names, and pasted URLs on [ioDispatcher].
 * When [useProxy] is false, resolves hostnames through the non-VPN underlying physical network
 * via [networkBridge] so Clash/v2rayNG Fake-IP DNS does not pollute direct probes.
 */
class NetBenchTargetResolver(
    private val dnsResolver: NetBenchDnsResolver = SystemNetBenchDnsResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
) {
    suspend fun resolve(
        rawTarget: String,
        defaultPort: Int,
        preferredFamily: PreferredIpFamily = PreferredIpFamily.AUTO,
        useProxy: Boolean = false,
    ): Result<ResolvedEndpoint> = withContext(ioDispatcher) {
        val parsed = NetBenchTargetParser.parse(rawTarget)
            ?: return@withContext Result.failure(IllegalArgumentException("目标格式无效，请输入有效的 IPv4、IPv6 或域名"))

        val effectivePort = parsed.effectivePort(defaultPort).coerceIn(1, 65535)
        val startNanos = System.nanoTime()

        try {
            val addresses = if (parsed.isLiteralIp) {
                listOf(InetAddress.getByName(parsed.host))
            } else if (dnsResolver !== SystemNetBenchDnsResolver) {
                dnsResolver.resolveAll(parsed.host)
            } else {
                resolveViaNetworkBridge(parsed.host, useProxy)
            }
            if (addresses.isEmpty()) {
                return@withContext Result.failure(UnknownHostException("域名未返回任何解析记录"))
            }

            val selected = selectAddress(addresses, preferredFamily)
                ?: return@withContext Result.failure(
                    UnknownHostException(
                        when (preferredFamily) {
                            PreferredIpFamily.IPV4_ONLY -> "该目标未解析到 IPv4 地址"
                            PreferredIpFamily.IPV6_ONLY -> "该目标未解析到 IPv6 地址"
                            PreferredIpFamily.AUTO -> "未找到可用 IP 地址"
                        },
                    ),
                )

            val dnsMs = ((System.nanoTime() - startNanos) / 1_000_000.0).coerceAtLeast(0.0)
            val resolvedIp = cleanHostAddress(selected)
            val family = when (selected) {
                is Inet4Address -> IpFamily.IPV4
                is Inet6Address -> IpFamily.IPV6
                else -> NetBenchTargetParser.detectIpFamily(resolvedIp)
            }
            val allIps = addresses.map(::cleanHostAddress).distinct()

            Result.success(
                ResolvedEndpoint(
                    parsed = parsed,
                    resolvedTarget = ResolvedTarget(
                        displayHost = parsed.host,
                        resolvedIp = resolvedIp,
                        ipFamily = family,
                        allResolvedIps = allIps,
                        dnsDurationMs = if (parsed.isLiteralIp) 0.0 else dnsMs,
                    ),
                    address = selected,
                    effectivePort = effectivePort,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: UnknownHostException) {
            Result.failure(UnknownHostException(e.message ?: "DNS 解析失败：无法解析 ${parsed.host}"))
        } catch (e: IOException) {
            Result.failure(IOException("解析目标失败：${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private fun resolveViaNetworkBridge(host: String, useProxy: Boolean): List<InetAddress> {
        val binding = networkBridge.inspectCurrentNetworks()
        val plan = binding.plan
        if (useProxy) {
            return try {
                binding.proxyOrDefaultChannel.resolveAllByName(host)
            } catch (_: UnknownHostException) {
                throw UnknownHostException("DNS 解析失败：无法解析 $host")
            }
        }

        val directCandidates = plan.allDirectCandidates.map { it.token }
        if (directCandidates.isEmpty()) {
            if (plan.isVpnActive) {
                throw UnknownHostException(
                    "检测到代理/VPN，但未找到可用的非 VPN 底层网络；可开启「经由代理测试」",
                )
            }
            return try {
                binding.proxyOrDefaultChannel.resolveAllByName(host)
            } catch (_: UnknownHostException) {
                throw UnknownHostException("DNS 解析失败：无法解析 $host")
            }
        }

        var lastError: Throwable? = null
        for (channel in directCandidates) {
            try {
                val resolved = channel.resolveAllByName(host)
                if (resolved.isNotEmpty()) return resolved
            } catch (t: Throwable) {
                lastError = t
                val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
                    throwable = t,
                    isVpnActive = plan.isVpnActive,
                    hasDirectCandidate = true,
                )
                if (reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
                    throw UnknownHostException(
                        "${reason.userHint}；请开启「经由代理测试」或在 VPN 设置中放行本应用",
                    )
                }
            }
        }

        val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
            throwable = lastError,
            isVpnActive = plan.isVpnActive,
            hasDirectCandidate = true,
        )
        throw if (plan.isVpnActive && reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
            UnknownHostException("${reason.userHint}；请开启「经由代理测试」")
        } else {
            UnknownHostException("底层网络 DNS 解析失败：无法解析 $host")
        }
    }

    private fun selectAddress(
        addresses: List<InetAddress>,
        preferredFamily: PreferredIpFamily,
    ): InetAddress? = when (preferredFamily) {
        PreferredIpFamily.IPV4_ONLY -> addresses.firstOrNull { it is Inet4Address }
        PreferredIpFamily.IPV6_ONLY -> addresses.firstOrNull { it is Inet6Address }
        PreferredIpFamily.AUTO -> addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
    }

    private fun cleanHostAddress(address: InetAddress): String {
        val raw = address.hostAddress ?: return ""
        val zoneIdx = raw.indexOf('%')
        return if (zoneIdx >= 0) raw.substring(0, zoneIdx) else raw
    }
}

/**
 * Low-level single TCP connect probe abstraction (injectable for deterministic unit tests).
 */
fun interface TcpProbeConnector {
    suspend fun connectAndMeasureMs(address: InetAddress, port: Int, timeoutMs: Int): Double
}

/**
 * Extended connector capable of binding TCP sockets to a specific [NetBenchSocketChannel].
 */
interface BoundTcpProbeConnector : TcpProbeConnector {
    suspend fun connectAndMeasureMs(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
        channel: NetBenchSocketChannel,
    ): Double
}

object SocketTcpProbeConnector : BoundTcpProbeConnector {
    override suspend fun connectAndMeasureMs(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
    ): Double = connectAndMeasureMs(address, port, timeoutMs, SystemDefaultNetworkChannel)

    override suspend fun connectAndMeasureMs(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
        channel: NetBenchSocketChannel,
    ): Double {
        val socket = channel.createSocket()
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion {
            runCatching { socket.close() }
        }
        return try {
            socket.tcpNoDelay = true
            val startNanos = System.nanoTime()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            val elapsedNanos = System.nanoTime() - startNanos
            (elapsedNanos / 1_000_000.0).coerceAtLeast(0.01)
        } finally {
            cancelHandle?.dispose()
            runCatching { socket.close() }
        }
    }
}

/**
 * Pure-Kotlin real-time TCPing latency & jitter engine emitting [Flow]<[TcpPingProgress]>.
 */
class TcpPingRunner(
    private val resolver: NetBenchTargetResolver = NetBenchTargetResolver(),
    private val connector: TcpProbeConnector = SocketTcpProbeConnector,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
) {

    fun run(config: TcpPingConfig): Flow<TcpPingProgress> = flow {
        val plannedCount = config.normalizedProbeCount
        val timeoutMs = config.normalizedTimeoutMs
        val intervalMs = config.normalizedIntervalMs

        val resolvedResult = resolver.resolve(
            rawTarget = config.target,
            defaultPort = config.normalizedPort,
            preferredFamily = config.preferredFamily,
            useProxy = config.useProxy,
        )

        val endpoint = resolvedResult.getOrElse { error ->
            val msg = error.message ?: "无法解析测试目标"
            val failedSummary = TcpPingSummary(
                resolvedTarget = null,
                port = config.normalizedPort,
                sent = 0,
                received = 0,
                lossPercent = 100.0,
                minMs = null,
                avgMs = null,
                maxMs = null,
                jitterMdevMs = null,
                samples = emptyList(),
                errorMessage = msg,
            )
            emit(
                TcpPingProgress(
                    resolvedTarget = null,
                    port = config.normalizedPort,
                    totalPlanned = plannedCount,
                    latestSample = null,
                    samples = emptyList(),
                    summary = failedSummary,
                    isFinished = true,
                    errorMessage = msg,
                ),
            )
            return@flow
        }

        val initialSelection = networkBridge.inspectCurrentNetworks().selectChannel(config.useProxy)
        var activeChannel: NetBenchSocketChannel = when (initialSelection) {
            is ChannelSelectionResult.Selected -> initialSelection.channel
            is ChannelSelectionResult.Blocked -> {
                val msg = initialSelection.message
                val failedSummary = TcpPingSummary(
                    resolvedTarget = endpoint.resolvedTarget,
                    port = endpoint.effectivePort,
                    sent = 0,
                    received = 0,
                    lossPercent = 100.0,
                    minMs = null,
                    avgMs = null,
                    maxMs = null,
                    jitterMdevMs = null,
                    samples = emptyList(),
                    errorMessage = msg,
                )
                emit(
                    TcpPingProgress(
                        resolvedTarget = endpoint.resolvedTarget,
                        port = endpoint.effectivePort,
                        totalPlanned = plannedCount,
                        latestSample = null,
                        samples = emptyList(),
                        summary = failedSummary,
                        isFinished = true,
                        errorMessage = msg,
                    ),
                )
                return@flow
            }
        }

        val samples = mutableListOf<TcpPingSample>()
        for (seq in 1..plannedCount) {
            currentCoroutineContext().ensureActive()

            val sample = executeSingleProbe(
                seq = seq,
                endpoint = endpoint,
                timeoutMs = timeoutMs,
                useProxy = config.useProxy,
                activeChannel = activeChannel,
                onChannelSwitched = { nextChannel -> activeChannel = nextChannel },
            )

            samples.add(sample)
            val snapshot = samples.toList()
            val summary = computeSummary(
                resolvedTarget = endpoint.resolvedTarget,
                port = endpoint.effectivePort,
                samples = snapshot,
            )
            emit(
                TcpPingProgress(
                    resolvedTarget = endpoint.resolvedTarget,
                    port = endpoint.effectivePort,
                    totalPlanned = plannedCount,
                    latestSample = sample,
                    samples = snapshot,
                    summary = summary,
                    isFinished = seq == plannedCount,
                    errorMessage = if (seq == plannedCount && summary.received == 0) {
                        samples.lastOrNull()?.errorMessage
                    } else {
                        null
                    },
                ),
            )

            if (seq < plannedCount && intervalMs > 0L) {
                delay(intervalMs)
            }
        }
    }.flowOn(ioDispatcher)

    private suspend fun executeSingleProbe(
        seq: Int,
        endpoint: ResolvedEndpoint,
        timeoutMs: Int,
        useProxy: Boolean,
        activeChannel: NetBenchSocketChannel,
        onChannelSwitched: (NetBenchSocketChannel) -> Unit,
    ): TcpPingSample {
        var currentChannel = activeChannel
        for (attempt in 0..1) {
            try {
                val rttMs = if (connector is BoundTcpProbeConnector) {
                    connector.connectAndMeasureMs(
                        address = endpoint.address,
                        port = endpoint.effectivePort,
                        timeoutMs = timeoutMs,
                        channel = currentChannel,
                    )
                } else {
                    connector.connectAndMeasureMs(
                        address = endpoint.address,
                        port = endpoint.effectivePort,
                        timeoutMs = timeoutMs,
                    )
                }
                return TcpPingSample(
                    seq = seq,
                    success = true,
                    rttMs = rttMs,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: SocketTimeoutException) {
                return TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = "连接超时 (${timeoutMs}ms)",
                )
            } catch (e: ConnectException) {
                if (!useProxy && connector is BoundTcpProbeConnector && attempt == 0) {
                    val refreshed = networkBridge.inspectCurrentNetworks()
                    val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
                        throwable = e,
                        isVpnActive = refreshed.plan.isVpnActive,
                        hasDirectCandidate = refreshed.plan.hasDirectCandidate,
                    )
                    if (reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
                        return TcpPingSample(
                            seq = seq,
                            success = false,
                            errorMessage = "${reason.userHint}；请开启「经由代理测试」",
                        )
                    }
                    val nextChannel = refreshed.directChannel
                    if (reason == DirectBindFailureReason.NETWORK_CHANGED_OR_LOST &&
                        nextChannel != null &&
                        nextChannel.networkId != currentChannel.networkId
                    ) {
                        currentChannel = nextChannel
                        onChannelSwitched(nextChannel)
                        continue
                    }
                }
                return TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = "端口拒绝连接",
                )
            } catch (e: NoRouteToHostException) {
                return TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = "目标主机不可达",
                )
            } catch (e: SecurityException) {
                val refreshed = networkBridge.inspectCurrentNetworks()
                val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
                    throwable = e,
                    isVpnActive = refreshed.plan.isVpnActive,
                    hasDirectCandidate = refreshed.plan.hasDirectCandidate,
                )
                return TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = if (!useProxy && refreshed.plan.isVpnActive) {
                        "${reason.userHint}；请开启「经由代理测试」"
                    } else {
                        e.message ?: "底层网络权限受限"
                    },
                )
            } catch (e: IOException) {
                if (!useProxy && connector is BoundTcpProbeConnector) {
                    val refreshed = networkBridge.inspectCurrentNetworks()
                    val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
                        throwable = e,
                        isVpnActive = refreshed.plan.isVpnActive,
                        hasDirectCandidate = refreshed.plan.hasDirectCandidate,
                    )
                    if (reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
                        return TcpPingSample(
                            seq = seq,
                            success = false,
                            errorMessage = "${reason.userHint}；请开启「经由代理测试」",
                        )
                    }
                    val nextChannel = refreshed.directChannel
                    if (attempt == 0 &&
                        reason == DirectBindFailureReason.NETWORK_CHANGED_OR_LOST &&
                        nextChannel != null &&
                        nextChannel.networkId != currentChannel.networkId
                    ) {
                        currentChannel = nextChannel
                        onChannelSwitched(nextChannel)
                        continue
                    }
                }
                return TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = e.message ?: "网络 I/O 异常",
                )
            }
        }
        return TcpPingSample(
            seq = seq,
            success = false,
            errorMessage = "网络 I/O 异常",
        )
    }

    companion object {
        /**
         * Computes standard TCPing statistics (`min`, `avg`, `max`, `mdev` jitter, `lossPercent`).
         */
        fun computeSummary(
            resolvedTarget: ResolvedTarget?,
            port: Int,
            samples: List<TcpPingSample>,
            errorMessage: String? = null,
        ): TcpPingSummary {
            val sent = samples.size
            val rtts = samples.mapNotNull { if (it.success) it.rttMs else null }
            val received = rtts.size
            val lossPercent = if (sent == 0) {
                0.0
            } else {
                ((sent - received) * 100.0) / sent.toDouble()
            }

            if (rtts.isEmpty()) {
                return TcpPingSummary(
                    resolvedTarget = resolvedTarget,
                    port = port,
                    sent = sent,
                    received = 0,
                    lossPercent = if (sent == 0) 100.0 else lossPercent,
                    minMs = null,
                    avgMs = null,
                    maxMs = null,
                    jitterMdevMs = null,
                    samples = samples,
                    errorMessage = errorMessage ?: samples.lastOrNull()?.errorMessage,
                )
            }

            val minMs = rtts.min()
            val maxMs = rtts.max()
            val avgMs = rtts.average()
            val variance = rtts.fold(0.0) { acc, v ->
                val diff = v - avgMs
                acc + diff * diff
            } / rtts.size.toDouble()
            val mdevMs = sqrt(variance)

            return TcpPingSummary(
                resolvedTarget = resolvedTarget,
                port = port,
                sent = sent,
                received = received,
                lossPercent = lossPercent,
                minMs = minMs,
                avgMs = avgMs,
                maxMs = maxMs,
                jitterMdevMs = mdevMs,
                samples = samples,
                errorMessage = errorMessage,
            )
        }
    }
}

/**
 * Lightweight HTTP/HTTPS latency probe measuring TLS handshake, TTFB, and total response time.
 */
class HttpLatencyRunner(
    private val baseHttpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
) {
    suspend fun probe(
        rawTarget: String,
        port: Int = 443,
        timeoutMs: Int = 5_000,
        useProxy: Boolean = false,
    ): HttpLatencyResult = withContext(ioDispatcher) {
        val parsed = NetBenchTargetParser.parse(rawTarget)
            ?: return@withContext HttpLatencyResult(
                url = rawTarget,
                success = false,
                errorMessage = "目标格式无效",
            )

        val effectivePort = parsed.effectivePort(port).coerceIn(1, 65535)
        val scheme = parsed.suggestedHttpScheme ?: when (effectivePort) {
            80, 8080 -> "http"
            else -> "https"
        }
        val hostFormatted = if (parsed.literalFamily == IpFamily.IPV6) "[${parsed.host}]" else parsed.host
        val omitPort = (scheme == "http" && effectivePort == 80) || (scheme == "https" && effectivePort == 443)
        val url = if (omitPort) "$scheme://$hostFormatted/" else "$scheme://$hostFormatted:$effectivePort/"

        val binding = networkBridge.inspectCurrentNetworks()
        val channel = when (val selection = binding.selectChannel(useProxy)) {
            is ChannelSelectionResult.Selected -> selection.channel
            is ChannelSelectionResult.Blocked -> {
                return@withContext HttpLatencyResult(
                    url = url,
                    success = false,
                    errorMessage = selection.message,
                )
            }
        }

        executeRequest(
            url = url,
            method = "HEAD",
            timeoutMs = timeoutMs,
            channel = channel,
            binding = binding,
            useProxy = useProxy,
        ).let { headResult ->
            if (headResult.statusCode == 405) {
                executeRequest(
                    url = url,
                    method = "GET",
                    timeoutMs = timeoutMs,
                    channel = channel,
                    binding = binding,
                    useProxy = useProxy,
                )
            } else {
                headResult
            }
        }
    }

    private fun executeRequest(
        url: String,
        method: String,
        timeoutMs: Int,
        channel: NetBenchSocketChannel,
        binding: ResolvedNetworkBinding,
        useProxy: Boolean,
    ): HttpLatencyResult {
        var callStartNanos = 0L
        var tlsStartNanos = 0L
        var tlsEndNanos = 0L
        var headersStartNanos = 0L

        val listener = object : EventListener() {
            override fun callStart(call: Call) {
                callStartNanos = System.nanoTime()
            }

            override fun secureConnectStart(call: Call) {
                tlsStartNanos = System.nanoTime()
            }

            override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                tlsEndNanos = System.nanoTime()
            }

            override fun responseHeadersStart(call: Call) {
                headersStartNanos = System.nanoTime()
            }
        }

        val needsCustomChannel = channel.isDirectPhysical || channel.bypassSystemHttpProxy
        val tempPool = if (needsCustomChannel) ConnectionPool(1, 5, TimeUnit.SECONDS) else null
        val client = baseHttpClient.newBuilder()
            .eventListener(listener)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((timeoutMs * 2L).coerceAtMost(12_000L), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .bindToChannel(channel, tempPool)
            .build()

        val request = Request.Builder()
            .url(url)
            .method(method, null)
            .header("User-Agent", "Anode-NetBench/1.0")
            .header("Connection", "close")
            .build()

        return try {
            val wallStart = System.nanoTime()
            client.newCall(request).execute().use { response: Response ->
                val endNanos = System.nanoTime()
                val startRef = if (callStartNanos > 0L) callStartNanos else wallStart
                val totalMs = ((endNanos - startRef) / 1_000_000.0).coerceAtLeast(0.1)
                val ttfbMs = if (headersStartNanos > startRef) {
                    (headersStartNanos - startRef) / 1_000_000.0
                } else {
                    totalMs
                }
                val tlsMs = if (tlsEndNanos > tlsStartNanos && tlsStartNanos > 0L) {
                    (tlsEndNanos - tlsStartNanos) / 1_000_000.0
                } else {
                    null
                }
                HttpLatencyResult(
                    url = url,
                    statusCode = response.code,
                    totalMs = totalMs,
                    ttfbMs = ttfbMs,
                    tlsHandshakeMs = tlsMs,
                    protocol = response.protocol.toString(),
                    success = true,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: SocketTimeoutException) {
            HttpLatencyResult(url = url, success = false, errorMessage = "HTTP 请求超时")
        } catch (e: SecurityException) {
            HttpLatencyResult(
                url = url,
                success = false,
                errorMessage = binding.formatBindOrIoError(e, useProxy, "HTTP 权限受限"),
            )
        } catch (e: IOException) {
            HttpLatencyResult(
                url = url,
                success = false,
                errorMessage = binding.formatBindOrIoError(e, useProxy, e.message?.take(80) ?: "HTTP 连接失败"),
            )
        } finally {
            tempPool?.evictAll()
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
