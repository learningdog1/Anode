package com.nodeseek.app.data.netbench

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/**
 * Abstraction over a bound OS network interface (or the system default route),
 * keeping [IpIntelligenceClient], [TcpPingRunner], and [Iperf3ClientRunner]
 * testable in plain JVM unit tests without Robolectric.
 */
interface NetBenchSocketChannel {
    val networkId: String
    val transportLabel: String?
    val isVpn: Boolean
    val isDirectPhysical: Boolean
    val bypassSystemHttpProxy: Boolean
    val socketFactory: SocketFactory

    @Throws(IOException::class)
    fun createSocket(): Socket

    @Throws(UnknownHostException::class)
    fun resolveAllByName(hostname: String): List<InetAddress>
}

/**
 * Default unbound channel following the OS default route (including active VPN or system HTTP proxy).
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
 * Android [ConnectivityManager]-backed channel binding sockets & DNS directly to a physical [Network].
 */
private class AndroidNetworkSocketChannel(
    private val network: Network,
    override val networkId: String,
    override val transportLabel: String?,
    override val isVpn: Boolean,
) : NetBenchSocketChannel {
    override val isDirectPhysical: Boolean = !isVpn
    override val bypassSystemHttpProxy: Boolean = !isVpn

    override val socketFactory: SocketFactory
        get() = network.socketFactory

    override fun createSocket(): Socket = network.socketFactory.createSocket()

    override fun resolveAllByName(hostname: String): List<InetAddress> =
        network.getAllByName(hostname).toList()
}

/**
 * Android implementation inspecting [ConnectivityManager] to locate underlying non-VPN physical networks
 * (`!TRANSPORT_VPN` + `NET_CAPABILITY_INTERNET`) and observing Wi-Fi/Cellular/VPN changes.
 */
class AndroidNetBenchNetworkBridge(
    context: Context,
) : NetBenchNetworkBridge {

    private val appContext: Context = context.applicationContext ?: context
    private val connectivityManager: ConnectivityManager? = runCatching {
        appContext.getSystemService(ConnectivityManager::class.java)
    }.getOrNull()

    private val trackedNetworks = ConcurrentHashMap<Long, Network>()

    override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
        val cm = connectivityManager ?: return SystemDefaultNetworkBridge.inspectCurrentNetworks()

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

        val descriptors = mutableListOf<NetworkDescriptor<NetBenchSocketChannel>>()
        for ((handle, net) in candidateMap) {
            val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
            val linkProps = runCatching { cm.getLinkProperties(net) }.getOrNull()

            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isNotRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isForeground = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
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

            descriptors += NetworkDescriptor(
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
                interfaceName = linkProps?.interfaceName,
            )
        }

        val defaultSystemHasHttpProxy = runCatching { cm.defaultProxy != null }.getOrDefault(false)
        val plan = NetBenchNetworkSelector.selectNetworkPlan(
            candidates = descriptors,
            defaultSystemHasHttpProxy = defaultSystemHasHttpProxy,
        )

        return ResolvedNetworkBinding(
            plan = plan,
            directChannel = plan.directUnderlying?.token,
            proxyOrDefaultChannel = SystemDefaultNetworkChannel,
        )
    }

    override fun observeNetworkChanges(): Flow<Unit> {
        val cm = connectivityManager ?: return emptyFlow()
        return callbackFlow {
            val allInternetCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trackedNetworks[network.networkHandle] = network
                    trySend(Unit)
                }

                override fun onLost(network: Network) {
                    trackedNetworks.remove(network.networkHandle)
                    trySend(Unit)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    trackedNetworks[network.networkHandle] = network
                    trySend(Unit)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                    trackedNetworks[network.networkHandle] = network
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

                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
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
}
