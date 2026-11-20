package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.NetBenchDnsResolver
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.data.netbench.TcpPingRunner
import com.nodeseek.app.data.netbench.TcpProbeConnector
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.netbench.PreferredIpFamily
import com.nodeseek.app.domain.netbench.TcpPingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.math.sqrt

class TcpPingRunnerTest {

    @Test
    fun `target parser handles ipv4 ipv6 domains and pasted urls`() {
        val ipv4 = NetBenchTargetParser.parse("1.1.1.1:443")
        assertNotNull(ipv4)
        assertEquals("1.1.1.1", ipv4!!.host)
        assertEquals(443, ipv4.explicitPort)
        assertTrue(ipv4.isLiteralIp)
        assertEquals(IpFamily.IPV4, ipv4.literalFamily)

        val bracketedV6 = NetBenchTargetParser.parse("[2001:db8::1]:5201")
        assertNotNull(bracketedV6)
        assertEquals("2001:db8::1", bracketedV6!!.host)
        assertEquals(5201, bracketedV6.explicitPort)
        assertTrue(bracketedV6.isLiteralIp)
        assertEquals(IpFamily.IPV6, bracketedV6.literalFamily)

        val rawV6 = NetBenchTargetParser.parse("2606:4700:4700::1111")
        assertNotNull(rawV6)
        assertEquals("2606:4700:4700::1111", rawV6!!.host)
        assertNull(rawV6.explicitPort)
        assertEquals(IpFamily.IPV6, rawV6.literalFamily)

        val url = NetBenchTargetParser.parse("https://lg.tokyo.example.com:8443/100MB.bin?test=1")
        assertNotNull(url)
        assertEquals("lg.tokyo.example.com", url!!.host)
        assertEquals(8443, url.explicitPort)
        assertFalse(url.isLiteralIp)
        assertEquals("https", url.suggestedHttpScheme)

        assertNull(NetBenchTargetParser.parse("   "))
        assertNull(NetBenchTargetParser.parse("999.999.999.999"))
        assertNull(NetBenchTargetParser.parse("example.com:99999"))
    }

    @Test
    fun `ip masking helpers obscure client and target ips properly`() {
        assertEquals("114.240.*.*", NetBenchTargetParser.maskClientIpForDisplay("114.240.88.123"))
        assertEquals("240e:3b0::****", NetBenchTargetParser.maskClientIpForDisplay("240e:3b0:1234::5678"))
        assertEquals("45.12.34.*", NetBenchTargetParser.maskTargetIpLastSegment("45.12.34.56"))
    }

    @Test
    fun `resolver selects ipv4 or ipv6 according to preferred family`() = runBlocking {
        val v4 = InetAddress.getByName("127.0.0.1")
        val v6 = InetAddress.getByName("2001:db8::1")
        val resolver = NetBenchTargetResolver(
            dnsResolver = NetBenchDnsResolver { listOf(v6, v4) },
        )

        val autoResult = resolver.resolve("dual.example.com", 443, PreferredIpFamily.AUTO).getOrThrow()
        assertEquals(IpFamily.IPV4, autoResult.resolvedTarget.ipFamily)
        assertEquals("127.0.0.1", autoResult.resolvedTarget.resolvedIp)

        val v6Result = resolver.resolve("dual.example.com", 443, PreferredIpFamily.IPV6_ONLY).getOrThrow()
        assertEquals(IpFamily.IPV6, v6Result.resolvedTarget.ipFamily)
        assertTrue(v6Result.resolvedTarget.resolvedIp.contains(":"))
    }

    @Test
    fun `real localhost ServerSocket produces zero loss and valid latency stats`() = runBlocking {
        val running = AtomicBoolean(true)
        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val acceptThread = thread(isDaemon = true) {
            while (running.get()) {
                try {
                    val client = server.accept()
                    client.close()
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            val runner = TcpPingRunner(ioDispatcher = Dispatchers.IO)
            val emissions = runner.run(
                TcpPingConfig(
                    target = "127.0.0.1",
                    port = server.localPort,
                    probeCount = 6,
                    timeoutMs = 1500,
                    intervalMs = 10L,
                ),
            ).toList()

            assertEquals(6, emissions.size)
            for (i in emissions.indices) {
                assertEquals(i + 1, emissions[i].samples.size)
                assertEquals(i == emissions.lastIndex, emissions[i].isFinished)
            }

            val finalProgress = emissions.last()
            val summary = finalProgress.summary
            assertNotNull(finalProgress.resolvedTarget)
            assertEquals(IpFamily.IPV4, finalProgress.resolvedTarget!!.ipFamily)
            assertEquals("127.0.0.1", finalProgress.resolvedTarget!!.resolvedIp)
            assertEquals(6, summary.sent)
            assertEquals(6, summary.received)
            assertEquals(0.0, summary.lossPercent, 0.001)
            assertNotNull(summary.minMs)
            assertNotNull(summary.avgMs)
            assertNotNull(summary.maxMs)
            assertNotNull(summary.jitterMdevMs)
            assertTrue(summary.minMs!! > 0.0)
            assertTrue(summary.minMs!! <= summary.avgMs!!)
            assertTrue(summary.avgMs!! <= summary.maxMs!!)
            assertTrue(summary.jitterMdevMs!! >= 0.0)
        } finally {
            running.set(false)
            runCatching { server.close() }
            acceptThread.join(500)
        }
    }

    @Test
    fun `closed port reports 100 percent packet loss cleanly`() = runBlocking {
        // Reserve then immediately close an ephemeral port so connections are refused
        val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val runner = TcpPingRunner(ioDispatcher = Dispatchers.IO)
        val emissions = runner.run(
            TcpPingConfig(
                target = "127.0.0.1",
                port = unusedPort,
                probeCount = 4,
                timeoutMs = 800,
                intervalMs = 5L,
            ),
        ).toList()

        assertEquals(4, emissions.size)
        val finalSummary = emissions.last().summary
        assertEquals(4, finalSummary.sent)
        assertEquals(0, finalSummary.received)
        assertEquals(100.0, finalSummary.lossPercent, 0.001)
        assertNull(finalSummary.avgMs)
        assertNull(finalSummary.minMs)
        assertNull(finalSummary.maxMs)
        assertNull(finalSummary.jitterMdevMs)
        assertTrue(emissions.last().isFinished)
    }

    @Test
    fun `partial packet loss computes exact stats and mdev jitter`() = runBlocking {
        val sequence = AtomicInteger(0)
        // 5 probes: 20ms, timeout, 40ms, refused, 60ms -> received=3, loss=40%, avg=40, min=20, max=60
        // variance = ((20-40)^2 + (40-40)^2 + (60-40)^2) / 3 = 800 / 3, mdev = sqrt(800/3)
        val scriptedConnector = TcpProbeConnector { _, _, _ ->
            when (sequence.incrementAndGet()) {
                1 -> 20.0
                2 -> throw SocketTimeoutException("timeout")
                3 -> 40.0
                4 -> throw ConnectException("refused")
                else -> 60.0
            }
        }

        val runner = TcpPingRunner(
            connector = scriptedConnector,
            ioDispatcher = Dispatchers.IO,
        )
        val emissions = runner.run(
            TcpPingConfig(
                target = "127.0.0.1",
                port = 443,
                probeCount = 5,
                timeoutMs = 500,
                intervalMs = 0L,
            ),
        ).toList()

        assertEquals(5, emissions.size)
        val summary = emissions.last().summary
        assertEquals(5, summary.sent)
        assertEquals(3, summary.received)
        assertEquals(40.0, summary.lossPercent, 0.001)
        assertEquals(20.0, summary.minMs!!, 0.001)
        assertEquals(40.0, summary.avgMs!!, 0.001)
        assertEquals(60.0, summary.maxMs!!, 0.001)
        assertEquals(sqrt(800.0 / 3.0), summary.jitterMdevMs!!, 0.001)
    }

    @Test
    fun `resolver and tcping default to direct physical channel and switch to proxy only when useProxy is true`() = runBlocking {
        val running = AtomicBoolean(true)
        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val acceptThread = thread(isDaemon = true) {
            while (running.get()) {
                try {
                    server.accept().close()
                } catch (_: Exception) {
                    break
                }
            }
        }

        val directSocketCalls = AtomicInteger(0)
        val directDnsCalls = AtomicInteger(0)
        val proxySocketCalls = AtomicInteger(0)
        val proxyDnsCalls = AtomicInteger(0)

        val directChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket {
                directSocketCalls.incrementAndGet()
                return Socket()
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> {
                directDnsCalls.incrementAndGet()
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val proxyChannel = object : NetBenchSocketChannel {
            override val networkId: String = "vpn-200"
            override val transportLabel: String = "VPN 代理"
            override val isVpn: Boolean = true
            override val isDirectPhysical: Boolean = false
            override val bypassSystemHttpProxy: Boolean = false
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket {
                proxySocketCalls.incrementAndGet()
                return Socket()
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> {
                proxyDnsCalls.incrementAndGet()
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(proxyChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        NetworkDescriptor(directChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI)),
                    ),
                )
                return ResolvedNetworkBinding(plan, directChannel, proxyChannel)
            }
        }

        try {
            val resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge)
            val runner = TcpPingRunner(
                resolver = resolver,
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
            )

            val directEmissions = runner.run(
                TcpPingConfig(
                    target = "vps.example.com",
                    port = server.localPort,
                    probeCount = 4,
                    intervalMs = 0L,
                    useProxy = false,
                ),
            ).toList()
            assertEquals(4, directEmissions.last().summary.received)
            assertEquals(1, directDnsCalls.get())
            assertEquals(4, directSocketCalls.get())
            assertEquals(0, proxyDnsCalls.get())
            assertEquals(0, proxySocketCalls.get())

            val proxyEmissions = runner.run(
                TcpPingConfig(
                    target = "vps.example.com",
                    port = server.localPort,
                    probeCount = 4,
                    intervalMs = 0L,
                    useProxy = true,
                ),
            ).toList()
            assertEquals(4, proxyEmissions.last().summary.received)
            assertEquals(1, proxyDnsCalls.get())
            assertEquals(4, proxySocketCalls.get())
        } finally {
            running.set(false)
            runCatching { server.close() }
            acceptThread.join(500)
        }
    }

    @Test
    fun `tcping recovers on mid-test Wi-Fi to cellular switch and surfaces VPN lockdown diagnostics`() = runBlocking {
        val running = AtomicBoolean(true)
        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val acceptThread = thread(isDaemon = true) {
            while (running.get()) {
                try {
                    server.accept().close()
                } catch (_: Exception) {
                    break
                }
            }
        }

        val wifiLost = AtomicBoolean(false)
        val wifiChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket {
                if (wifiLost.get()) {
                    throw SocketException("Network is unreachable (ENETUNREACH)")
                }
                wifiLost.set(true)
                return Socket()
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val cellCalls = AtomicInteger(0)
        val cellChannel = object : NetBenchSocketChannel {
            override val networkId: String = "cell-102"
            override val transportLabel: String = "蜂窝移动"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket {
                cellCalls.incrementAndGet()
                return Socket()
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val vpnChannel = object : NetBenchSocketChannel {
            override val networkId: String = "vpn-200"
            override val transportLabel: String = "VPN 代理"
            override val isVpn: Boolean = true
            override val isDirectPhysical: Boolean = false
            override val bypassSystemHttpProxy: Boolean = false
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }

        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val physical: NetworkDescriptor<NetBenchSocketChannel> = if (wifiLost.get()) {
                    NetworkDescriptor<NetBenchSocketChannel>(cellChannel, "cell-102", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.CELLULAR))
                } else {
                    NetworkDescriptor<NetBenchSocketChannel>(wifiChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI))
                }
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor<NetBenchSocketChannel>(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        physical,
                    ),
                )
                return ResolvedNetworkBinding(plan, physical.token, vpnChannel)
            }
        }

        try {
            val runner = TcpPingRunner(
                resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge),
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
            )
            val handoffResults = runner.run(
                TcpPingConfig(target = "127.0.0.1", port = server.localPort, probeCount = 4, intervalMs = 0L),
            ).toList()
            assertEquals(4, handoffResults.last().summary.received)
            assertEquals(3, cellCalls.get())

            // Now test EPERM lockdown failure
            val lockdownChannel = object : NetBenchSocketChannel {
                override val networkId: String = "wifi-locked"
                override val transportLabel: String = "Wi-Fi"
                override val isVpn: Boolean = false
                override val isDirectPhysical: Boolean = true
                override val bypassSystemHttpProxy: Boolean = true
                override val socketFactory: SocketFactory = SocketFactory.getDefault()
                override fun createSocket(): Socket = throw SocketException("bind failed: EPERM (Operation not permitted)")
                override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            }
            val lockdownBridge = object : NetBenchNetworkBridge {
                override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                    val plan = NetBenchNetworkSelector.selectNetworkPlan(
                        listOf(
                            NetworkDescriptor(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                            NetworkDescriptor(lockdownChannel, "wifi-locked", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI)),
                        ),
                    )
                    return ResolvedNetworkBinding(plan, lockdownChannel, vpnChannel)
                }
            }
            val lockdownRunner = TcpPingRunner(
                resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = lockdownBridge),
                ioDispatcher = Dispatchers.IO,
                networkBridge = lockdownBridge,
            )
            val lockdownResult = lockdownRunner.run(
                TcpPingConfig(target = "127.0.0.1", port = server.localPort, probeCount = 4, intervalMs = 0L),
            ).toList().last()
            assertEquals(0, lockdownResult.summary.received)
            assertTrue(lockdownResult.errorMessage!!.contains("经由代理测试"))
        } finally {
            running.set(false)
            runCatching { server.close() }
            acceptThread.join(500)
        }
    }
}
