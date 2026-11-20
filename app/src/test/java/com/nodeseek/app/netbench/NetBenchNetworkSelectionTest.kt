package com.nodeseek.app.netbench

import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.IpClassificationEngine
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.netbench.ProxyPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

class NetBenchNetworkSelectionTest {

    @Test
    fun `selects wifi directly when no vpn or proxy is present`() {
        val candidates = listOf(
            NetworkDescriptor(
                token = "wifi-1",
                networkId = "100",
                isVpn = false,
                hasInternet = true,
                isValidated = true,
                isForeground = true,
                isActiveDefault = true,
                transports = setOf(NetBenchTransportKind.WIFI),
            ),
        )

        val plan = NetBenchNetworkSelector.selectNetworkPlan(candidates)
        assertFalse(plan.isProxyDetected)
        assertFalse(plan.isVpnActive)
        assertEquals(ProxyPresence.NONE, plan.proxyPresence)
        assertEquals("wifi-1", plan.directUnderlying?.token)
        assertEquals("Wi-Fi", plan.directTransportLabel)
    }

    @Test
    fun `excludes VPN and picks underlying validated Wi-Fi over background cellular`() {
        val candidates = listOf(
            NetworkDescriptor(
                token = "vpn-tun0",
                networkId = "200",
                isVpn = true,
                hasInternet = true,
                isValidated = true,
                isForeground = true,
                isActiveDefault = true,
                transports = setOf(NetBenchTransportKind.VPN),
                interfaceName = "tun0",
            ),
            NetworkDescriptor(
                token = "cell-bg",
                networkId = "102",
                isVpn = false,
                hasInternet = true,
                isValidated = true,
                isForeground = false,
                isActiveDefault = false,
                transports = setOf(NetBenchTransportKind.CELLULAR),
            ),
            NetworkDescriptor(
                token = "wifi-fg",
                networkId = "101",
                isVpn = false,
                hasInternet = true,
                isValidated = true,
                isForeground = true,
                isActiveDefault = false,
                transports = setOf(NetBenchTransportKind.WIFI),
            ),
        )

        val plan = NetBenchNetworkSelector.selectNetworkPlan(candidates)
        assertTrue(plan.isProxyDetected)
        assertTrue(plan.isVpnActive)
        assertEquals(ProxyPresence.VPN_TUNNEL, plan.proxyPresence)
        assertEquals("vpn-tun0", plan.activeDefault?.token)
        assertEquals("VPN 代理", plan.activeTransportLabel)
        assertEquals("wifi-fg", plan.directUnderlying?.token)
        assertEquals("Wi-Fi", plan.directTransportLabel)
        assertEquals(listOf("wifi-fg", "cell-bg"), plan.allDirectCandidates.map { it.token })
    }

    @Test
    fun `prefers validated cellular over unvalidated captive wifi while VPN is active`() {
        val candidates = listOf(
            NetworkDescriptor(
                token = "vpn",
                networkId = "300",
                isVpn = true,
                hasInternet = true,
                isActiveDefault = true,
                transports = setOf(NetBenchTransportKind.VPN),
            ),
            NetworkDescriptor(
                token = "wifi-captive",
                networkId = "101",
                isVpn = false,
                hasInternet = true,
                isValidated = false,
                isForeground = true,
                transports = setOf(NetBenchTransportKind.WIFI),
            ),
            NetworkDescriptor(
                token = "cell-5g",
                networkId = "102",
                isVpn = false,
                hasInternet = true,
                isValidated = true,
                isForeground = false,
                transports = setOf(NetBenchTransportKind.CELLULAR),
            ),
        )

        val plan = NetBenchNetworkSelector.selectNetworkPlan(candidates)
        assertEquals("cell-5g", plan.directUnderlying?.token)
        assertEquals("蜂窝移动", plan.directTransportLabel)
        assertEquals(listOf("cell-5g", "wifi-captive"), plan.allDirectCandidates.map { it.token })
    }

    @Test
    fun `excludes restricted networks and suspended networks rank lower`() {
        val candidates = listOf(
            NetworkDescriptor(
                token = "ims-restricted",
                networkId = "50",
                isVpn = false,
                hasInternet = true,
                isNotRestricted = false,
                isValidated = true,
                transports = setOf(NetBenchTransportKind.CELLULAR),
            ),
            NetworkDescriptor(
                token = "wifi-suspended",
                networkId = "101",
                isVpn = false,
                hasInternet = true,
                isValidated = true,
                isNotSuspended = false,
                transports = setOf(NetBenchTransportKind.WIFI),
            ),
            NetworkDescriptor(
                token = "cell-active",
                networkId = "102",
                isVpn = false,
                hasInternet = true,
                isValidated = true,
                isNotSuspended = true,
                transports = setOf(NetBenchTransportKind.CELLULAR),
            ),
        )

        val plan = NetBenchNetworkSelector.selectNetworkPlan(candidates)
        assertEquals(listOf("cell-active", "wifi-suspended"), plan.allDirectCandidates.map { it.token })
        assertFalse(plan.allDirectCandidates.any { it.token == "ims-restricted" })
    }

    @Test
    fun `detects system HTTP proxy and combined VPN plus HTTP proxy`() {
        val wifiWithProxy = listOf(
            NetworkDescriptor(
                token = "wifi",
                networkId = "100",
                isVpn = false,
                hasInternet = true,
                isValidated = true,
                isActiveDefault = true,
                hasHttpProxy = true,
                transports = setOf(NetBenchTransportKind.WIFI),
            ),
        )
        val httpProxyPlan = NetBenchNetworkSelector.selectNetworkPlan(wifiWithProxy)
        assertEquals(ProxyPresence.SYSTEM_HTTP_PROXY, httpProxyPlan.proxyPresence)
        assertTrue(httpProxyPlan.isProxyDetected)
        assertFalse(httpProxyPlan.isVpnActive)
        assertEquals("wifi", httpProxyPlan.directUnderlying?.token)

        val vpnOnlyWithDefaultProxy = listOf(
            NetworkDescriptor(
                token = "vpn",
                networkId = "200",
                isVpn = true,
                hasInternet = true,
                isActiveDefault = true,
                transports = setOf(NetBenchTransportKind.VPN),
            ),
        )
        val combinedPlan = NetBenchNetworkSelector.selectNetworkPlan(
            candidates = vpnOnlyWithDefaultProxy,
            defaultSystemHasHttpProxy = true,
        )
        assertEquals(ProxyPresence.BOTH_VPN_AND_HTTP_PROXY, combinedPlan.proxyPresence)
        assertNull(combinedPlan.directUnderlying)
    }

    @Test
    fun `network fingerprint changes when switching between wifi and cellular`() {
        val wifiPlan = NetBenchNetworkSelector.selectNetworkPlan(
            listOf(
                NetworkDescriptor("vpn", "200", isVpn = true, hasInternet = true, isActiveDefault = true),
                NetworkDescriptor("wifi", "101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI)),
            ),
        )
        val cellularPlan = NetBenchNetworkSelector.selectNetworkPlan(
            listOf(
                NetworkDescriptor("vpn", "200", isVpn = true, hasInternet = true, isActiveDefault = true),
                NetworkDescriptor("cell", "102", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.CELLULAR)),
            ),
        )
        assertNotEquals(wifiPlan.networkFingerprint, cellularPlan.networkFingerprint)
    }

    @Test
    fun `classifies direct bind failures accurately across lockdown, network lost, and unreachable`() {
        assertEquals(
            DirectBindFailureReason.NO_UNDERLYING_NETWORK,
            NetBenchNetworkSelector.classifyDirectBindFailure(
                throwable = null,
                isVpnActive = true,
                hasDirectCandidate = false,
            ),
        )

        val nestedEperm = IOException(
            "bind failed",
            SocketException("sendto failed: EPERM (Operation not permitted)"),
        )
        assertEquals(
            DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED,
            NetBenchNetworkSelector.classifyDirectBindFailure(
                throwable = nestedEperm,
                isVpnActive = true,
                hasDirectCandidate = true,
            ),
        )

        assertEquals(
            DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED,
            NetBenchNetworkSelector.classifyDirectBindFailure(
                throwable = SecurityException("Permission denied by VPN lockdown"),
                isVpnActive = true,
                hasDirectCandidate = true,
            ),
        )

        assertEquals(
            DirectBindFailureReason.NETWORK_CHANGED_OR_LOST,
            NetBenchNetworkSelector.classifyDirectBindFailure(
                throwable = ConnectException("Network is unreachable (ENETUNREACH)"),
                isVpnActive = true,
                hasDirectCandidate = true,
            ),
        )

        assertEquals(
            DirectBindFailureReason.TARGET_UNREACHABLE,
            NetBenchNetworkSelector.classifyDirectBindFailure(
                throwable = SocketTimeoutException("connect timed out"),
                isVpnActive = true,
                hasDirectCandidate = true,
            ),
        )
    }

    @Test
    fun `classification engine resolves Chinese country names and special regions`() {
        assertEquals("CN", IpClassificationEngine.countryCodeFromZhOrName("中国", "贵州"))
        assertEquals("HK", IpClassificationEngine.countryCodeFromZhOrName("中国", "香港"))
        assertEquals("TW", IpClassificationEngine.countryCodeFromZhOrName("中国", "台湾"))
        assertEquals("JP", IpClassificationEngine.countryCodeFromZhOrName("日本"))
        assertEquals("US", IpClassificationEngine.countryCodeFromZhOrName("美国"))
    }
}
