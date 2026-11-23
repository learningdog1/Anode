package com.nodeseek.app.data.netbench

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.netbench.NetworkSelectionPlan
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * Abstraction over a bound OS network interface (or the system default route),
 * keeping [IpIntelligenceClient], [TcpPingRunner], [IcmpPingRunner], and [Iperf3ClientRunner]
 * testable in plain JVM unit tests without Robolectric.
 */
interface NetBenchSocketChannel {
    val networkId: String
    val transportLabel: String?
    val isVpn: Boolean
    val isDirectPhysical: Boolean
    val bypassSystemHttpProxy: Boolean
    val socketFactory: SocketFactory

    /** Whether this channel can bind an arbitrary [FileDescriptor] (e.g. kernel ICMP datagram socket) to its underlying network. */
    val supportsFileDescriptorBind: Boolean
        get() = false

    /** Whether public-domain DNS resolving to `127.0.0.0/8` or `::1` should be treated as sinkhole hijack. */
    val rejectLoopbackDnsForPublicHosts: Boolean
        get() = false

    /** Whether automatic DoH clean DNS fallback over this channel should run when Fake-IP DNS pollution is detected. */
    val supportsCleanDohFallback: Boolean
        get() = false

    @Throws(IOException::class)
    fun createSocket(): Socket

    @Throws(IOException::class)
    fun bindDatagramFileDescriptor(fd: FileDescriptor) {
        throw UnsupportedOperationException("Channel $networkId does not support FileDescriptor binding")
    }

    @Throws(UnknownHostException::class)
    fun resolveAllByName(hostname: String): List<InetAddress>
}

/**
 * Creates an unconnected [Socket] explicitly configured with [Proxy.NO_PROXY] (immune to JVM
 * `socksProxyHost`, `http.proxyHost`, and custom [java.net.ProxySelector]s) and invokes [bindSocket]
 * before returning it, closing the socket if binding throws.
 */
@Throws(IOException::class)
internal fun createBoundNoProxySocket(bindSocket: (Socket) -> Unit): Socket {
    val socket = Socket(Proxy.NO_PROXY)
    try {
        bindSocket(socket)
        return socket
    } catch (t: Throwable) {
        runCatching { socket.close() }
        throw t
    }
}

/**
 * [SocketFactory] that creates sockets strictly with [Proxy.NO_PROXY] and optionally binds each
 * socket before connecting, preventing SOCKS/HTTP proxy hijacking during direct probes.
 */
internal class BoundNoProxySocketFactory(
    private val bindSocket: (Socket) -> Unit = {},
) : SocketFactory() {

    @Throws(IOException::class)
    override fun createSocket(): Socket = createBoundNoProxySocket(bindSocket)

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket =
        connectNewSocket(InetSocketAddress(host, port), null)

    @Throws(IOException::class)
    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = connectNewSocket(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket =
        connectNewSocket(InetSocketAddress(host, port), null)

    @Throws(IOException::class)
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = connectNewSocket(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    @Throws(IOException::class)
    private fun connectNewSocket(
        remote: InetSocketAddress,
        local: InetSocketAddress?,
    ): Socket {
        val socket = createBoundNoProxySocket(bindSocket)
        try {
            if (local != null) {
                socket.bind(local)
            }
            socket.connect(remote)
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }
}

/**
 * Default unbound channel following the OS default route (including active VPN or system HTTP/SOCKS proxy).
 */
object SystemDefaultNetworkChannel : NetBenchSocketChannel {
    override val networkId: String = "system-default"
    override val transportLabel: String? = null
    override val isVpn: Boolean = false
    override val isDirectPhysical: Boolean = false
    override val bypassSystemHttpProxy: Boolean = false
    override val socketFactory: SocketFactory
        get() = SocketFactory.getDefault()

    override fun createSocket(): Socket = Socket()

    override fun resolveAllByName(hostname: String): List<InetAddress> =
        InetAddress.getAllByName(hostname).toList()
}

/**
 * Direct channel following the OS routing table while explicitly bypassing JVM/system HTTP & SOCKS proxies
 * via [Proxy.NO_PROXY]. Used when `useProxy == false` and no specific [Network] handle is required.
 */
object DirectSystemRouteChannel : NetBenchSocketChannel {
    private val noProxyFactory = BoundNoProxySocketFactory()

    override val networkId: String = "system-direct-noproxy"
    override val transportLabel: String? = null
    override val isVpn: Boolean = false
    override val isDirectPhysical: Boolean = false
    override val bypassSystemHttpProxy: Boolean = true
    override val socketFactory: SocketFactory
        get() = noProxyFactory

    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun resolveAllByName(hostname: String): List<InetAddress> =
        InetAddress.getAllByName(hostname).toList()
}

/**
 * Result of inspecting the current OS network topology.
 */
data class ResolvedNetworkBinding(
    val plan: NetworkSelectionPlan<NetBenchSocketChannel>,
    val directChannel: NetBenchSocketChannel?,
    val proxyOrDefaultChannel: NetBenchSocketChannel,
) {
    /**
     * Selects the channel for outbound benchmark traffic based on [useProxy].
     * Returns [ChannelSelectionResult.Blocked] when VPN is active without any direct candidate.
     */
    fun selectChannel(useProxy: Boolean): ChannelSelectionResult = when {
        useProxy -> ChannelSelectionResult.Selected(proxyOrDefaultChannel, this)
        directChannel != null -> ChannelSelectionResult.Selected(directChannel, this)
        plan.isVpnActive -> ChannelSelectionResult.Blocked(
            reason = DirectBindFailureReason.NO_UNDERLYING_NETWORK,
            message = "${DirectBindFailureReason.NO_UNDERLYING_NETWORK.userHint}；请开启「经由代理测试」",
        )
        proxyOrDefaultChannel === SystemDefaultNetworkChannel ->
            ChannelSelectionResult.Selected(DirectSystemRouteChannel, this)
        else -> ChannelSelectionResult.Selected(proxyOrDefaultChannel, this)
    }

    /**
     * Produces a clear diagnostic message when a direct-bound operation fails under VPN/lockdown.
     */
    fun formatBindOrIoError(
        throwable: Throwable?,
        useProxy: Boolean,
        fallbackMessage: String,
    ): String {
        if (useProxy || !plan.isVpnActive) return fallbackMessage
        val reason = NetBenchNetworkSelector.classifyDirectBindFailure(
            throwable = throwable,
            isVpnActive = true,
            hasDirectCandidate = plan.hasDirectCandidate,
        )
        return if (reason == DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED) {
            "${reason.userHint}；请开启「经由代理测试」"
        } else {
            fallbackMessage
        }
    }
}

sealed class ChannelSelectionResult {
    data class Selected(
        val channel: NetBenchSocketChannel,
        val binding: ResolvedNetworkBinding,
    ) : ChannelSelectionResult()

    data class Blocked(
        val reason: DirectBindFailureReason,
        val message: String,
    ) : ChannelSelectionResult()
}

/**
 * Configures an [OkHttpClient.Builder] to bind sockets, DNS, and proxy policy to [channel].
 * When [channel] overrides the default route, callers should supply a dedicated [connectionPool]
 * and evict it after use to avoid pooling sockets across VPN/physical interfaces.
 */
internal fun OkHttpClient.Builder.bindToChannel(
    channel: NetBenchSocketChannel,
    connectionPool: ConnectionPool?,
): OkHttpClient.Builder = apply {
    if (channel.isDirectPhysical || channel.bypassSystemHttpProxy) {
        socketFactory(channel.socketFactory)
        dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    channel.resolveAllByName(hostname)
            },
        )
        if (connectionPool != null) {
            connectionPool(connectionPool)
        }
    }
    if (channel.bypassSystemHttpProxy) {
        proxy(Proxy.NO_PROXY)
    }
}

/**
 * Provides current network topology snapshots and change notifications.
 */
interface NetBenchNetworkBridge {
    fun inspectCurrentNetworks(): ResolvedNetworkBinding
    fun observeNetworkChanges(): Flow<Unit> = emptyFlow()
}

/**
 * Fallback bridge used in unit tests and Compose previews when no Android [Context] is supplied.
 */
object SystemDefaultNetworkBridge : NetBenchNetworkBridge {
    override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
        val plan = NetBenchNetworkSelector.selectNetworkPlan<NetBenchSocketChannel>(emptyList())
        return ResolvedNetworkBinding(
            plan = plan,
            directChannel = null,
            proxyOrDefaultChannel = SystemDefaultNetworkChannel,
        )
    }
}

/**
 * Android [ConnectivityManager]-backed channel binding TCP/ICMP sockets & DNS directly to a physical [Network]
 * while enforcing [Proxy.NO_PROXY] to defeat global SOCKS/HTTP proxy settings.
 */
private class AndroidNetworkSocketChannel(
    private val network: Network,
    override val networkId: String,
    override val transportLabel: String?,
    override val isVpn: Boolean,
) : NetBenchSocketChannel {
    override val isDirectPhysical: Boolean = !isVpn
    override val bypassSystemHttpProxy: Boolean = !isVpn
    override val supportsFileDescriptorBind: Boolean = !isVpn
    override val rejectLoopbackDnsForPublicHosts: Boolean = !isVpn
    override val supportsCleanDohFallback: Boolean = !isVpn

    private val directBoundNoProxyFactory: SocketFactory by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BoundNoProxySocketFactory { socket -> network.bindSocket(socket) }
    }

    override val socketFactory: SocketFactory
        get() = if (isVpn) network.socketFactory else directBoundNoProxyFactory

    override fun createSocket(): Socket =
        if (isVpn) {
            network.socketFactory.createSocket()
        } else {
            createBoundNoProxySocket { socket -> network.bindSocket(socket) }
        }

    override fun bindDatagramFileDescriptor(fd: FileDescriptor) {
        network.bindSocket(fd)
    }

    override fun resolveAllByName(hostname: String): List<InetAddress> =
        network.getAllByName(hostname).toList()
}

/**
 * Android implementation inspecting [ConnectivityManager] to locate underlying non-VPN physical networks
 * (`!TRANSPORT_VPN` + `NET_CAPABILITY_INTERNET`), combining `allNetworks`, active `NOT_VPN` discovery,
 * short-TTL physical snapshot caching, and interface-name recovery across Wi-Fi/Cellular/VPN transitions.
 */
class AndroidNetBenchNetworkBridge(
    context: Context,
) : NetBenchNetworkBridge {

    private data class CachedDirectEntry(
        val network: Network,
        val descriptor: NetworkDescriptor<NetBenchSocketChannel>,
        val recordedAtNanos: Long,
    )

    private val appContext: Context = context.applicationContext ?: context
    private val connectivityManager: ConnectivityManager? = runCatching {
        appContext.getSystemService(ConnectivityManager::class.java)
    }.getOrNull()

    private val trackedNetworks = ConcurrentHashMap<Long, Network>()
    private val trackedInterfaceNames = ConcurrentHashMap<Long, String>()
    private val cachedDirectEntries = ConcurrentHashMap<Long, CachedDirectEntry>()
    private val lastActiveProbeNanos = AtomicLong(0L)

    override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
        val cm = connectivityManager ?: return SystemDefaultNetworkBridge.inspectCurrentNetworks()

        var liveDescriptors = collectDescriptorsSnapshot(cm)
        val hasLiveDirect = liveDescriptors.any { !it.isVpn && it.hasInternet && it.isNotRestricted }

        if (!hasLiveDirect && !isMainThread()) {
            val now = System.nanoTime()
            val prev = lastActiveProbeNanos.get()
            if (prev == 0L || now - prev >= ACTIVE_PROBE_COOLDOWN_NANOS) {
                if (lastActiveProbeNanos.compareAndSet(prev, now)) {
                    probeUnderlyingPhysicalNetworks(cm)
                    liveDescriptors = collectDescriptorsSnapshot(cm)
                }
            }
        }

        val freshCachedDirect = getFreshCachedDirectDescriptors(cm)
        val mergedDescriptors = NetBenchNetworkSelector.mergeNetworkCandidates(
            liveCandidates = liveDescriptors,
            cachedDirectCandidates = freshCachedDirect,
        )

        val defaultSystemHasHttpProxy = runCatching { cm.defaultProxy != null }.getOrDefault(false)
        val plan = NetBenchNetworkSelector.selectNetworkPlan(
            candidates = mergedDescriptors,
            defaultSystemHasHttpProxy = defaultSystemHasHttpProxy,
        )

        return ResolvedNetworkBinding(
            plan = plan,
            directChannel = plan.directUnderlying?.token,
            proxyOrDefaultChannel = SystemDefaultNetworkChannel,
        )
    }

    private fun collectDescriptorsSnapshot(
        cm: ConnectivityManager,
    ): List<NetworkDescriptor<NetBenchSocketChannel>> {
        val activeNetwork = runCatching { cm.activeNetwork }.getOrNull()
        val activeHandle = activeNetwork?.networkHandle

        @Suppress("DEPRECATION")
        val allListed = runCatching { cm.allNetworks.toList() }.getOrNull().orEmpty()

        val candidateMap = LinkedHashMap<Long, Network>()
        allListed.forEach { net -> candidateMap[net.networkHandle] = net }
        trackedNetworks.values.forEach { net -> candidateMap.putIfAbsent(net.networkHandle, net) }
        if (activeNetwork != null) {
            candidateMap.putIfAbsent(activeNetwork.networkHandle, activeNetwork)
        }

        val nowNanos = System.nanoTime()
        val descriptors = mutableListOf<NetworkDescriptor<NetBenchSocketChannel>>()
        for ((handle, net) in candidateMap) {
            val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull()
            if (caps == null) {
                trackedNetworks.remove(handle)
                continue
            }
            val linkProps = runCatching { cm.getLinkProperties(net) }.getOrNull()
            val descriptor = buildDescriptor(
                net = net,
                handle = handle,
                activeHandle = activeHandle,
                caps = caps,
                linkProps = linkProps,
            )
            descriptors += descriptor

            if (!descriptor.isVpn && descriptor.hasInternet && descriptor.isNotRestricted) {
                cachedDirectEntries[handle] = CachedDirectEntry(
                    network = net,
                    descriptor = descriptor,
                    recordedAtNanos = nowNanos,
                )
            }
        }
        return descriptors
    }

    private fun buildDescriptor(
        net: Network,
        handle: Long,
        activeHandle: Long?,
        caps: NetworkCapabilities,
        linkProps: LinkProperties?,
    ): NetworkDescriptor<NetBenchSocketChannel> {
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isNotRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        } else {
            activeHandle != null && activeHandle == handle
        }
        val isNotSuspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        } else {
            true
        }

        val transports = buildSet {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetBenchTransportKind.ETHERNET)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetBenchTransportKind.WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetBenchTransportKind.CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetBenchTransportKind.VPN)
            if (isEmpty()) add(NetBenchTransportKind.OTHER)
        }

        val resolvedIface = resolveInterfaceName(handle, linkProps)
        if (!resolvedIface.isNullOrBlank()) {
            trackedInterfaceNames[handle] = resolvedIface
        }

        val label = NetBenchNetworkSelector.formatTransportLabel(transports, isVpn)
        val idStr = handle.toString()
        val channel: NetBenchSocketChannel = if (isVpn) {
            SystemDefaultNetworkChannel
        } else {
            AndroidNetworkSocketChannel(
                network = net,
                networkId = idStr,
                transportLabel = label,
                isVpn = false,
            )
        }

        return NetworkDescriptor(
            token = channel,
            networkId = idStr,
            isVpn = isVpn,
            hasInternet = hasInternet,
            isNotRestricted = isNotRestricted,
            isValidated = isValidated,
            isForeground = isForeground,
            isNotSuspended = isNotSuspended,
            isActiveDefault = (activeHandle != null && activeHandle == handle),
            transports = transports,
            hasHttpProxy = linkProps?.httpProxy != null,
            interfaceName = resolvedIface,
        )
    }

    private fun resolveInterfaceName(handle: Long, linkProps: LinkProperties?): String? {
        linkProps?.interfaceName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val fromAddr = runCatching {
            linkProps?.linkAddresses?.firstNotNullOfOrNull { linkAddress ->
                NetworkInterface.getByInetAddress(linkAddress.address)?.name?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
        if (fromAddr != null) return fromAddr
        return trackedInterfaceNames[handle]
    }

    private fun getFreshCachedDirectDescriptors(
        cm: ConnectivityManager,
    ): List<NetworkDescriptor<NetBenchSocketChannel>> {
        if (cachedDirectEntries.isEmpty()) return emptyList()
        val nowNanos = System.nanoTime()
        val valid = mutableListOf<NetworkDescriptor<NetBenchSocketChannel>>()
        val iterator = cachedDirectEntries.entries.iterator()
        while (iterator.hasNext()) {
            val (handle, entry) = iterator.next()
            val ageNanos = nowNanos - entry.recordedAtNanos
            if (ageNanos > DIRECT_CACHE_TTL_NANOS) {
                iterator.remove()
                continue
            }
            val liveCaps = runCatching { cm.getNetworkCapabilities(entry.network) }.getOrNull()
            if (liveCaps == null) {
                iterator.remove()
                continue
            }
            val stillNonVpn = !liveCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                liveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                liveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (!stillNonVpn) {
                iterator.remove()
                continue
            }
            val refreshedLinkProps = runCatching { cm.getLinkProperties(entry.network) }.getOrNull()
            valid += buildDescriptor(
                net = entry.network,
                handle = handle,
                activeHandle = null,
                caps = liveCaps,
                linkProps = refreshedLinkProps,
            )
        }
        return valid
    }

    /**
     * Performs a bounded synchronous `NET_CAPABILITY_NOT_VPN + NET_CAPABILITY_INTERNET` callback query
     * on a dedicated worker [HandlerThread] when `allNetworks` has not yet surfaced an underlying
     * Wi-Fi/Cellular network while VPN is active.
     */
    private fun probeUnderlyingPhysicalNetworks(cm: ConnectivityManager) {
        val handlerThread = HandlerThread("NetBenchDirectNetProbe").apply { isDaemon = true }
        try {
            handlerThread.start()
            val looper = handlerThread.looper ?: return
            val handler = Handler(looper)
            val latch = CountDownLatch(1)

            val notVpnRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val listenCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trackedNetworks[network.networkHandle] = network
                    latch.countDown()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    trackedNetworks[network.networkHandle] = network
                    linkProperties.interfaceName?.trim()?.takeIf { it.isNotEmpty() }?.let { iface ->
                        trackedInterfaceNames[network.networkHandle] = iface
                    }
                    latch.countDown()
                }
            }

            var registeredListen = false
            var requestCallback: ConnectivityManager.NetworkCallback? = null
            try {
                registeredListen = runCatching {
                    cm.registerNetworkCallback(notVpnRequest, listenCallback, handler)
                }.isSuccess

                if (!latch.await(110L, TimeUnit.MILLISECONDS)) {
                    val activeReqCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trackedNetworks[network.networkHandle] = network
                            latch.countDown()
                        }
                    }
                    val requested = runCatching {
                        cm.requestNetwork(notVpnRequest, activeReqCallback, handler, 120)
                    }.isSuccess
                    if (requested) {
                        requestCallback = activeReqCallback
                        latch.await(130L, TimeUnit.MILLISECONDS)
                    } else if (registeredListen) {
                        // Fallback when CHANGE_NETWORK_STATE is denied on restricted ROMs:
                        // give the passive registerNetworkCallback listener a slightly wider window.
                        latch.await(120L, TimeUnit.MILLISECONDS)
                    }
                }
            } finally {
                if (registeredListen) {
                    runCatching { cm.unregisterNetworkCallback(listenCallback) }
                }
                if (requestCallback != null) {
                    runCatching { cm.unregisterNetworkCallback(requestCallback) }
                }
            }
        } catch (_: Throwable) {
            // Safe no-op on restricted ROMs or unit-test stubs
        } finally {
            runCatching { handlerThread.quitSafely() }
        }
    }

    override fun observeNetworkChanges(): Flow<Unit> {
        val cm = connectivityManager ?: return emptyFlow()
        return callbackFlow {
            fun recordNetwork(network: Network, linkProps: LinkProperties? = null) {
                trackedNetworks[network.networkHandle] = network
                linkProps?.interfaceName?.trim()?.takeIf { it.isNotEmpty() }?.let { iface ->
                    trackedInterfaceNames[network.networkHandle] = iface
                }
            }

            fun evictNetwork(network: Network) {
                val handle = network.networkHandle
                trackedNetworks.remove(handle)
                trackedInterfaceNames.remove(handle)
                cachedDirectEntries.remove(handle)
            }

            // Single superset callback (INTERNET with NOT_VPN removed) covers both physical
            // and VPN networks without duplicating events or consuming an extra system callback slot.
            val allInternetCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    recordNetwork(network)
                    trySend(Unit)
                }

                override fun onLost(network: Network) {
                    evictNetwork(network)
                    trySend(Unit)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    recordNetwork(network)
                    trySend(Unit)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    recordNetwork(network, linkProperties)
                    trySend(Unit)
                }
            }

            val defaultCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(Unit)
                }

                override fun onLost(network: Network) {
                    trySend(Unit)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    trySend(Unit)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    trySend(Unit)
                }
            }

            val allRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val registeredAll = runCatching {
                cm.registerNetworkCallback(allRequest, allInternetCallback)
            }.isSuccess
            val registeredDefault = runCatching {
                cm.registerDefaultNetworkCallback(defaultCallback)
            }.isSuccess

            awaitClose {
                if (registeredAll) {
                    runCatching { cm.unregisterNetworkCallback(allInternetCallback) }
                }
                if (registeredDefault) {
                    runCatching { cm.unregisterNetworkCallback(defaultCallback) }
                }
            }
        }.conflate()
    }

    private fun isMainThread(): Boolean =
        runCatching { Looper.getMainLooper()?.thread === Thread.currentThread() }.getOrDefault(false)

    private companion object {
        const val DIRECT_CACHE_TTL_NANOS = 25_000_000_000L // 25s
        const val ACTIVE_PROBE_COOLDOWN_NANOS = 1_500_000_000L // 1.5s
    }
}
