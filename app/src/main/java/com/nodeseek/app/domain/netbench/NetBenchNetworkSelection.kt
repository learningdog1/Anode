package com.nodeseek.app.domain.netbench

import java.util.Locale

/**
 * Physical or virtual network transport category for pure network selection logic.
 */
enum class NetBenchTransportKind(val label: String) {
    ETHERNET("以太网"),
    WIFI("Wi-Fi"),
    CELLULAR("蜂窝移动"),
    VPN("VPN 代理"),
    OTHER("本地网络"),
}

/**
 * Detected proxy / VPN environment on the device.
 */
enum class ProxyPresence {
    NONE,
    VPN_TUNNEL,
    SYSTEM_HTTP_PROXY,
    BOTH_VPN_AND_HTTP_PROXY,
    ;

    val isProxyDetected: Boolean
        get() = this != NONE

    val hasVpn: Boolean
        get() = this == VPN_TUNNEL || this == BOTH_VPN_AND_HTTP_PROXY

    val hasSystemHttpProxy: Boolean
        get() = this == SYSTEM_HTTP_PROXY || this == BOTH_VPN_AND_HTTP_PROXY
}

/**
 * Classified reason why binding directly to an underlying non-VPN network failed.
 */
enum class DirectBindFailureReason(val userHint: String) {
    VPN_LOCKDOWN_OR_BYPASS_DENIED(
        "VPN 未开启允许绕过或系统开启了「始终开启+屏蔽无 VPN 连接」，底层直连被系统拦截",
    ),
    NO_UNDERLYING_NETWORK(
        "当前未检测到可用的非 VPN 底层网络",
    ),
    NETWORK_CHANGED_OR_LOST(
        "底层网络切换（如 Wi-Fi/蜂窝切换）或连接意外中断",
    ),
    TARGET_UNREACHABLE(
        "底层直连查询接口超时或不可达",
    ),
}

/**
 * Platform-agnostic descriptor of a candidate OS network interface.
 * Produced from Android `ConnectivityManager` in production or synthetic fixtures in JVM unit tests.
 */
data class NetworkDescriptor<T>(
    val token: T,
    val networkId: String,
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val isNotRestricted: Boolean = true,
    val isValidated: Boolean = false,
    val isForeground: Boolean = false,
    val isNotSuspended: Boolean = true,
    val isActiveDefault: Boolean = false,
    val transports: Set<NetBenchTransportKind> = emptySet(),
    val hasHttpProxy: Boolean = false,
    val interfaceName: String? = null,
)

/**
 * Result of pure network selection given a snapshot of device networks.
 */
data class NetworkSelectionPlan<T>(
    val activeDefault: NetworkDescriptor<T>?,
    val directUnderlying: NetworkDescriptor<T>?,
    val allDirectCandidates: List<NetworkDescriptor<T>>,
    val proxyPresence: ProxyPresence,
    val activeTransportLabel: String?,
    val directTransportLabel: String?,
    val networkFingerprint: String,
) {
    val isVpnActive: Boolean
        get() = proxyPresence.hasVpn

    val isProxyDetected: Boolean
        get() = proxyPresence.isProxyDetected

    val hasDirectCandidate: Boolean
        get() = directUnderlying != null
}

/**
 * Pure Kotlin network selection & bind-failure classification logic for bypassing VPN proxies.
 */
object NetBenchNetworkSelector {

    /**
     * Selects the best non-VPN underlying network with `INTERNET` capability, ranks fallback
     * direct candidates (e.g. validated cellular when Wi-Fi loses upstream), and determines
     * whether a VPN tunnel or system HTTP proxy is active.
     */
    fun <T> selectNetworkPlan(
        candidates: List<NetworkDescriptor<T>>,
        defaultSystemHasHttpProxy: Boolean = false,
    ): NetworkSelectionPlan<T> {
        val activeDefault = candidates.firstOrNull { it.isActiveDefault }
        val isVpnActive = activeDefault?.isVpn == true ||
            (activeDefault == null && candidates.any { it.isVpn && it.hasInternet })
        val hasHttpProxy = defaultSystemHasHttpProxy || activeDefault?.hasHttpProxy == true

        val proxyPresence = when {
            isVpnActive && hasHttpProxy -> ProxyPresence.BOTH_VPN_AND_HTTP_PROXY
            isVpnActive -> ProxyPresence.VPN_TUNNEL
            hasHttpProxy -> ProxyPresence.SYSTEM_HTTP_PROXY
            else -> ProxyPresence.NONE
        }

        val sortedDirect = candidates
            .asSequence()
            .filter { !it.isVpn && it.hasInternet && it.isNotRestricted }
            .sortedWith(
                compareByDescending<NetworkDescriptor<T>> { it.isNotSuspended }
                    .thenByDescending { it.isValidated }
                    .thenByDescending { it.isActiveDefault && !it.isVpn }
                    .thenByDescending { it.isForeground }
                    .thenByDescending { transportPriority(it.transports) }
                    .thenBy { it.networkId },
            )
            .toList()

        val directUnderlying = sortedDirect.firstOrNull()
        val activeTransportLabel = activeDefault?.let { formatTransportLabel(it) }
        val directTransportLabel = directUnderlying?.let { formatTransportLabel(it) }

        val fingerprint = buildString {
            append("vpn=").append(isVpnActive)
            append("|httpProxy=").append(hasHttpProxy)
            append("|active=").append(activeDefault?.networkId ?: "none")
            append("|direct=")
            sortedDirect.joinTo(this, separator = ",") { d ->
                "${d.networkId}:${d.isValidated}:${d.isForeground}:${d.isNotSuspended}:${transportPriority(d.transports)}"
            }
        }

        return NetworkSelectionPlan(
            activeDefault = activeDefault,
            directUnderlying = directUnderlying,
            allDirectCandidates = sortedDirect,
            proxyPresence = proxyPresence,
            activeTransportLabel = activeTransportLabel,
            directTransportLabel = directTransportLabel,
            networkFingerprint = fingerprint,
        )
    }

    /**
     * Pure classifier that inspects an exception chain from a direct-network socket/DNS attempt
     * and categorizes why binding/probing failed (VPN lockdown/allowBypass disabled vs network lost vs unreachable).
     */
    fun classifyDirectBindFailure(
        throwable: Throwable?,
        isVpnActive: Boolean,
        hasDirectCandidate: Boolean,
    ): DirectBindFailureReason {
        if (!hasDirectCandidate) {
            return DirectBindFailureReason.NO_UNDERLYING_NETWORK
        }

        val chain = if (throwable == null) {
            emptyList()
        } else {
            generateSequence(throwable) { it.cause }.take(8).toList()
        }
        val combinedText = chain.joinToString(" | ") { item ->
            "${item.javaClass.simpleName}:${item.message.orEmpty()}"
        }.lowercase(Locale.US)

        val hasPermissionDeny = "eperm" in combinedText ||
            "operation not permitted" in combinedText ||
            "permission denied" in combinedText ||
            "eacces" in combinedText ||
            "securityexception" in combinedText ||
            "lockdown" in combinedText ||
            "bypass" in combinedText

        if (hasPermissionDeny && (isVpnActive || "eperm" in combinedText || "operation not permitted" in combinedText || "lockdown" in combinedText)) {
            return DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED
        }

        if ("enetunreach" in combinedText ||
            "network is unreachable" in combinedText ||
            "network lost" in combinedText ||
            "networkcallback" in combinedText ||
            "econnaborted" in combinedText ||
            "software caused connection abort" in combinedText
        ) {
            return DirectBindFailureReason.NETWORK_CHANGED_OR_LOST
        }

        return DirectBindFailureReason.TARGET_UNREACHABLE
    }

    fun formatTransportLabel(descriptor: NetworkDescriptor<*>): String =
        formatTransportLabel(descriptor.transports, descriptor.isVpn)

    fun formatTransportLabel(
        transports: Set<NetBenchTransportKind>,
        isVpn: Boolean = false,
    ): String = when {
        isVpn || NetBenchTransportKind.VPN in transports -> NetBenchTransportKind.VPN.label
        NetBenchTransportKind.ETHERNET in transports -> NetBenchTransportKind.ETHERNET.label
        NetBenchTransportKind.WIFI in transports -> NetBenchTransportKind.WIFI.label
        NetBenchTransportKind.CELLULAR in transports -> NetBenchTransportKind.CELLULAR.label
        else -> NetBenchTransportKind.OTHER.label
    }

    private fun transportPriority(transports: Set<NetBenchTransportKind>): Int = when {
        NetBenchTransportKind.ETHERNET in transports -> 4
        NetBenchTransportKind.WIFI in transports -> 3
        NetBenchTransportKind.CELLULAR in transports -> 2
        else -> 1
    }
}
