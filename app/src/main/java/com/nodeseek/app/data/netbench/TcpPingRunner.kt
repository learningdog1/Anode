package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.ParsedTarget
import com.nodeseek.app.domain.netbench.PreferredIpFamily
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.SuspectResolvedIpKind
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.IDN
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
 * Clean DNS fallback executed over the bound physical [NetBenchSocketChannel] when primary DNS
 * returns a proxy Fake-IP (`198.18.0.0/15`, `fc00::/18`) or sinkhole address (`0.0.0.0`, `::`).
 */
fun interface CleanDirectDnsFallback {
    suspend fun resolveClean(
        host: String,
        channel: NetBenchSocketChannel,
        preferredFamily: PreferredIpFamily,
    ): List<InetAddress>
}

/**
 * Minimal, bounds-safe RFC 1035 DNS wireformat encoder & decoder for RFC 8484 DoH (`application/dns-message`).
 */
internal object DnsWireMessageCodec {
    const val QTYPE_A = 1
    const val QTYPE_AAAA = 28
    private const val QCLASS_IN = 1

    fun encodeQuery(
        hostname: String,
        qtype: Int,
        transactionId: Int = 0x4E50,
    ): ByteArray {
        val asciiHost = runCatching { IDN.toASCII(hostname.trim().trimEnd('.')) }
            .getOrDefault(hostname.trim().trimEnd('.'))
        val labels = asciiHost.split('.').filter { it.isNotEmpty() }
        require(labels.isNotEmpty()) { "Invalid DNS hostname" }

        val out = ByteArrayOutputStream(64)
        // 12-byte RFC 1035 header: ID, Flags (RD=1 -> 0x0100), QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.writeShort16(transactionId)
        out.writeShort16(0x0100)
        out.writeShort16(1)
        out.writeShort16(0)
        out.writeShort16(0)
        out.writeShort16(0)

        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "DNS label length out of bounds" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0) // Root terminator
        out.writeShort16(qtype)
        out.writeShort16(QCLASS_IN)
        return out.toByteArray()
    }

    fun decodeAddressRecords(
        response: ByteArray,
        expectedQtype: Int,
    ): List<InetAddress> {
        if (response.size < 12) return emptyList()
        val flags = readUInt16(response, 2)
        val isResponse = (flags and 0x8000) != 0
        val rcode = flags and 0x000F
        if (!isResponse || rcode != 0) return emptyList()

        val qdCount = readUInt16(response, 4)
        val anCount = readUInt16(response, 6)
        if (anCount <= 0) return emptyList()

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(response, offset) ?: return emptyList()
            if (offset + 4 > response.size) return emptyList()
            offset += 4
        }

        val results = ArrayList<InetAddress>(anCount)
        repeat(anCount) {
            offset = skipDnsName(response, offset) ?: return results
            if (offset + 10 > response.size) return results
            val rrType = readUInt16(response, offset)
            val rrClass = readUInt16(response, offset + 2)
            val rdLength = readUInt16(response, offset + 8)
            offset += 10
            if (rdLength < 0 || offset + rdLength > response.size) return results

            if (rrClass == QCLASS_IN && rrType == expectedQtype) {
                if (expectedQtype == QTYPE_A && rdLength == 4) {
                    runCatching {
                        InetAddress.getByAddress(response.copyOfRange(offset, offset + 4))
                    }.getOrNull()?.let(results::add)
                } else if (expectedQtype == QTYPE_AAAA && rdLength == 16) {
                    runCatching {
                        InetAddress.getByAddress(response.copyOfRange(offset, offset + 16))
                    }.getOrNull()?.let(results::add)
                }
            }
            offset += rdLength
        }
        return results
    }

    private fun skipDnsName(buf: ByteArray, startOffset: Int): Int? {
        var pos = startOffset
        var steps = 0
        while (pos < buf.size && steps < 128) {
            val len = buf[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                (len and 0xC0) == 0xC0 -> {
                    // 2-byte RFC 1035 compression pointer terminates the name field in-place
                    return if (pos + 2 <= buf.size) pos + 2 else null
                }
                (len and 0xC0) != 0 -> return null
                else -> {
                    pos += 1 + len
                    if (pos > buf.size) return null
                }
            }
            steps++
        }
        return null
    }

    private fun readUInt16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun ByteArrayOutputStream.writeShort16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}

/**
 * RFC 8484 DNS-over-HTTPS clean resolver bound strictly to the underlying physical [NetBenchSocketChannel].
 * Uses IP-literal DoH endpoints (`223.5.5.5` AliDNS & `1.1.1.1` Cloudflare) over TLS 443 so neither
 * phone TUN Fake-IP nor router port-53 Clash DNS redirection can poison direct-mode resolution.
 */
class DohCleanDirectDnsFallback(
    private val baseHttpClient: OkHttpClient = HttpLatencyRunner.defaultHttpClient(),
    private val dohEndpoints: List<String> = DEFAULT_DOH_ENDPOINTS,
    private val timeoutMs: Long = 1_800L,
) : CleanDirectDnsFallback {

    override suspend fun resolveClean(
        host: String,
        channel: NetBenchSocketChannel,
        preferredFamily: PreferredIpFamily,
    ): List<InetAddress> = withContext(Dispatchers.IO) {
        val qtypes = when (preferredFamily) {
            PreferredIpFamily.IPV4_ONLY -> listOf(DnsWireMessageCodec.QTYPE_A)
            PreferredIpFamily.IPV6_ONLY -> listOf(DnsWireMessageCodec.QTYPE_AAAA)
            PreferredIpFamily.AUTO -> listOf(DnsWireMessageCodec.QTYPE_A, DnsWireMessageCodec.QTYPE_AAAA)
        }

        val tempPool = ConnectionPool(1, 5, TimeUnit.SECONDS)
        val client = baseHttpClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout((timeoutMs * 2L).coerceAtMost(4_000L), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .bindToChannel(channel, tempPool)
            .build()

        try {
            for (endpointUrl in dohEndpoints) {
                currentCoroutineContext().ensureActive()
                val collected = mutableListOf<InetAddress>()
                for (qtype in qtypes) {
                    val queryBytes = runCatching { DnsWireMessageCodec.encodeQuery(host, qtype) }.getOrNull() ?: continue
                    val request = Request.Builder()
                        .url(endpointUrl)
                        .post(queryBytes.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
                        .header("Accept", "application/dns-message")
                        .header("User-Agent", "Anode-NetBench/1.0")
                        .build()
                    val decoded = runCatching {
                        client.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) emptyList()
                            else {
                                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                                DnsWireMessageCodec.decodeAddressRecords(bodyBytes, qtype)
                            }
                        }
                    }.getOrDefault(emptyList())
                    collected += decoded
                }
                if (collected.isNotEmpty()) {
                    return@withContext collected
                }
            }
            emptyList()
        } finally {
            tempPool.evictAll()
        }
    }

    companion object {
        private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()
        val DEFAULT_DOH_ENDPOINTS = listOf(
            "https://223.5.5.5/dns-query",
            "https://1.1.1.1/dns-query",
        )
        val Default = DohCleanDirectDnsFallback()
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
 * via [networkBridge], actively intercepts Fake-IP (`198.18.0.0/15`, `fc00::/18`) and sinkhole
 * responses, and falls back to clean DoH bound to the physical channel so proxy Fake-IP DNS
 * never pollutes direct Ping / TCPing / iperf3 probes.
 */
class NetBenchTargetResolver(
    private val dnsResolver: NetBenchDnsResolver = SystemNetBenchDnsResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
    private val cleanDnsFallback: CleanDirectDnsFallback? = null,
) {
    private data class ChannelDnsResult(
        val addresses: List<InetAddress>,
        val channel: NetBenchSocketChannel?,
    )

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
            if (parsed.isLiteralIp && !useProxy) {
                val literalSuspect = NetBenchTargetParser.classifySuspectIp(
                    ip = parsed.host,
                    targetHost = null,
                    checkLoopbackForPublicHost = false,
                )
                if (literalSuspect.isFakeIp) {
                    return@withContext Result.failure(
                        UnknownHostException(
                            "目标地址 ${parsed.host} 属于${literalSuspect.reasonLabel}，无法通过底层物理网络直连探测；请输入真实域名/IP 或开启「经由代理测试」",
                        ),
                    )
                }
                if (literalSuspect == SuspectResolvedIpKind.UNSPECIFIED_SINKHOLE) {
                    return@withContext Result.failure(
                        IllegalArgumentException("目标地址 ${parsed.host} 为无效未指定地址"),
                    )
                }
            }

            val dnsOutcome = when {
                parsed.isLiteralIp -> ChannelDnsResult(listOf(InetAddress.getByName(parsed.host)), null)
                dnsResolver !== SystemNetBenchDnsResolver -> ChannelDnsResult(dnsResolver.resolveAll(parsed.host), null)
                else -> resolveViaNetworkBridge(parsed.host, useProxy)
            }

            if (dnsOutcome.addresses.isEmpty()) {
                return@withContext Result.failure(UnknownHostException("域名未返回任何解析记录"))
            }

            val finalAddresses = if (!parsed.isLiteralIp && !useProxy) {
                sanitizeDirectResolvedAddresses(
                    host = parsed.host,
                    rawAddresses = dnsOutcome.addresses,
                    sourceChannel = dnsOutcome.channel,
                    preferredFamily = preferredFamily,
                )
            } else {
                dnsOutcome.addresses
            }

            val selected = selectAddress(finalAddresses, preferredFamily)
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
            val allIps = finalAddresses.map(::cleanHostAddress).distinct()

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

    private suspend fun sanitizeDirectResolvedAddresses(
        host: String,
        rawAddresses: List<InetAddress>,
        sourceChannel: NetBenchSocketChannel?,
        preferredFamily: PreferredIpFamily,
    ): List<InetAddress> {
        val checkLoopback = sourceChannel?.rejectLoopbackDnsForPublicHosts == true
        val cleanPrimary = rawAddresses.filter { addr ->
            !NetBenchTargetParser.classifySuspectIp(
                ip = cleanHostAddress(addr),
                targetHost = host,
                checkLoopbackForPublicHost = checkLoopback,
            ).isSuspect
        }

        if (cleanPrimary.isNotEmpty() && selectAddress(cleanPrimary, preferredFamily) != null) {
            return cleanPrimary
        }

        val firstSuspectPair = rawAddresses.firstNotNullOfOrNull { addr ->
            val ipStr = cleanHostAddress(addr)
            val kind = NetBenchTargetParser.classifySuspectIp(
                ip = ipStr,
                targetHost = host,
                checkLoopbackForPublicHost = checkLoopback,
            )
            if (kind.isSuspect) ipStr to kind else null
        } ?: return rawAddresses // No suspect IPs; let normal family mismatch message apply

        // Attempt clean DNS fallback bound directly to the physical channel
        val fallbackChannel = sourceChannel ?: networkBridge.inspectCurrentNetworks().directChannel ?: DirectSystemRouteChannel
        val effectiveFallback = cleanDnsFallback
            ?: DohCleanDirectDnsFallback.Default.takeIf { fallbackChannel.supportsCleanDohFallback }

        if (effectiveFallback != null) {
            val recovered = runCatching {
                effectiveFallback.resolveClean(host, fallbackChannel, preferredFamily)
            }.getOrDefault(emptyList()).filter { addr ->
                !NetBenchTargetParser.classifySuspectIp(
                    ip = cleanHostAddress(addr),
                    targetHost = host,
                    checkLoopbackForPublicHost = true,
                ).isSuspect
            }
            if (recovered.isNotEmpty() && selectAddress(recovered, preferredFamily) != null) {
                return recovered
            }
        }

        val (badIp, suspectKind) = firstSuspectPair
        val message = if (suspectKind.isFakeIp) {
            "检测到域名 $host 解析结果为${suspectKind.reasonLabel} ($badIp)，已拦截假直连探测；请检查路由器/私人 DNS 的 Fake-IP 设置，或开启「经由代理测试」"
        } else {
            "检测到域名 $host 解析结果被拦截为无效地址 ($badIp，${suspectKind.reasonLabel})，无法执行直连探测"
        }
        throw UnknownHostException(message)
    }

    private fun resolveViaNetworkBridge(host: String, useProxy: Boolean): ChannelDnsResult {
        val binding = networkBridge.inspectCurrentNetworks()
        val plan = binding.plan
        if (useProxy) {
            return try {
                ChannelDnsResult(
                    addresses = binding.proxyOrDefaultChannel.resolveAllByName(host),
                    channel = binding.proxyOrDefaultChannel,
                )
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
                ChannelDnsResult(
                    addresses = binding.proxyOrDefaultChannel.resolveAllByName(host),
                    channel = binding.proxyOrDefaultChannel,
                )
            } catch (_: UnknownHostException) {
                throw UnknownHostException("DNS 解析失败：无法解析 $host")
            }
        }

        var lastError: Throwable? = null
        var firstFakeIpCandidate: Pair<List<InetAddress>, NetBenchSocketChannel>? = null
        for (channel in directCandidates) {
            try {
                val resolved = channel.resolveAllByName(host)
                if (resolved.isNotEmpty()) {
                    val hasClean = resolved.any { addr ->
                        !NetBenchTargetParser.classifySuspectIp(
                            ip = cleanHostAddress(addr),
                            targetHost = host,
                            checkLoopbackForPublicHost = channel.rejectLoopbackDnsForPublicHosts,
                        ).isSuspect
                    }
                    if (hasClean) {
                        return ChannelDnsResult(addresses = resolved, channel = channel)
                    }
                    if (firstFakeIpCandidate == null) {
                        firstFakeIpCandidate = resolved to channel
                    }
                }
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

        // If Wi-Fi returned Fake-IP (e.g. OpenWrt router) and Cellular wasn't available or also returned Fake-IP,
        // return the first candidate so sanitizeDirectResolvedAddresses can run clean DoH fallback or report Fake-IP!
        if (firstFakeIpCandidate != null) {
            return ChannelDnsResult(
                addresses = firstFakeIpCandidate.first,
                channel = firstFakeIpCandidate.second,
            )
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
    ): Double = connectAndMeasureMs(address, port, timeoutMs, DirectSystemRouteChannel)

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

            val computed = computeRttStats(rtts)
            return TcpPingSummary(
                resolvedTarget = resolvedTarget,
                port = port,
                sent = sent,
                received = received,
                lossPercent = lossPercent,
                minMs = computed?.minMs,
                avgMs = computed?.avgMs,
                maxMs = computed?.maxMs,
                jitterMdevMs = computed?.mdevMs,
                samples = samples,
                errorMessage = errorMessage,
            )
        }
    }
}

internal data class ComputedRttStats(
    val minMs: Double,
    val avgMs: Double,
    val maxMs: Double,
    val mdevMs: Double,
)

internal fun computeRttStats(rtts: List<Double>): ComputedRttStats? {
    if (rtts.isEmpty()) return null
    val minMs = rtts.min()
    val maxMs = rtts.max()
    val avgMs = rtts.average()
    val variance = rtts.fold(0.0) { acc, v ->
        val diff = v - avgMs
        acc + diff * diff
    } / rtts.size.toDouble()
    return ComputedRttStats(
        minMs = minMs,
        avgMs = avgMs,
        maxMs = maxMs,
        mdevMs = sqrt(variance),
    )
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
