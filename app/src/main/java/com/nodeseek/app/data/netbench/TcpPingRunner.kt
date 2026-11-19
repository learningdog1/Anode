package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IpFamily
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
import java.net.Socket
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
 */
class NetBenchTargetResolver(
    private val dnsResolver: NetBenchDnsResolver = SystemNetBenchDnsResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resolve(
        rawTarget: String,
        defaultPort: Int,
        preferredFamily: PreferredIpFamily = PreferredIpFamily.AUTO,
    ): Result<ResolvedEndpoint> = withContext(ioDispatcher) {
        val parsed = NetBenchTargetParser.parse(rawTarget)
            ?: return@withContext Result.failure(IllegalArgumentException("目标格式无效，请输入有效的 IPv4、IPv6 或域名"))

        val effectivePort = parsed.effectivePort(defaultPort).coerceIn(1, 65535)
        val startNanos = System.nanoTime()

        try {
            val addresses = if (parsed.isLiteralIp) {
                listOf(InetAddress.getByName(parsed.host))
            } else {
                dnsResolver.resolveAll(parsed.host)
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
            Result.failure(UnknownHostException("DNS 解析失败：无法解析 ${parsed.host}"))
        } catch (e: IOException) {
            Result.failure(IOException("解析目标失败：${e.message ?: e.javaClass.simpleName}", e))
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

object SocketTcpProbeConnector : TcpProbeConnector {
    override suspend fun connectAndMeasureMs(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
    ): Double {
        val socket = Socket()
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
) {

    fun run(config: TcpPingConfig): Flow<TcpPingProgress> = flow {
        val plannedCount = config.normalizedProbeCount
        val timeoutMs = config.normalizedTimeoutMs
        val intervalMs = config.normalizedIntervalMs

        val resolvedResult = resolver.resolve(
            rawTarget = config.target,
            defaultPort = config.normalizedPort,
            preferredFamily = config.preferredFamily,
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

        val samples = mutableListOf<TcpPingSample>()
        for (seq in 1..plannedCount) {
            currentCoroutineContext().ensureActive()

            val sample = try {
                val rttMs = connector.connectAndMeasureMs(
                    address = endpoint.address,
                    port = endpoint.effectivePort,
                    timeoutMs = timeoutMs,
                )
                TcpPingSample(
                    seq = seq,
                    success = true,
                    rttMs = rttMs,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: SocketTimeoutException) {
                TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = "连接超时 (${timeoutMs}ms)",
                )
            } catch (e: ConnectException) {
                TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = "端口拒绝连接",
                )
            } catch (e: NoRouteToHostException) {
                TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = "目标主机不可达",
                )
            } catch (e: IOException) {
                TcpPingSample(
                    seq = seq,
                    success = false,
                    errorMessage = e.message ?: "网络 I/O 异常",
                )
            }

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
) {
    suspend fun probe(
        rawTarget: String,
        port: Int = 443,
        timeoutMs: Int = 5_000,
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

        executeRequest(url, method = "HEAD", timeoutMs = timeoutMs).let { headResult ->
            if (headResult.statusCode == 405) {
                executeRequest(url, method = "GET", timeoutMs = timeoutMs)
            } else {
                headResult
            }
        }
    }

    private fun executeRequest(
        url: String,
        method: String,
        timeoutMs: Int,
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

        val client = baseHttpClient.newBuilder()
            .eventListener(listener)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((timeoutMs * 2L).coerceAtMost(12_000L), TimeUnit.MILLISECONDS)
            .followRedirects(false)
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
        } catch (e: IOException) {
            HttpLatencyResult(
                url = url,
                success = false,
                errorMessage = e.message?.take(80) ?: "HTTP 连接失败",
            )
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
