package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.IcmpErrQueueParser
import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.PingExecutionEvent
import com.nodeseek.app.data.netbench.PingProcessExecutor
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.data.netbench.ReverseDnsLookup
import com.nodeseek.app.data.netbench.TracerouteKernelOps
import com.nodeseek.app.data.netbench.TraceroutePingFallback
import com.nodeseek.app.data.netbench.TracerouteProbeProtocol
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.netbench.TracerouteRunner
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.netbench.TracerouteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class TracerouteRunnerTest {

    private class FakeChannel(
        override val networkId: String,
        override val isVpn: Boolean = false,
        override val supportsFileDescriptorBind: Boolean = false,
        private val onBindFd: (FileDescriptor) -> Unit = {},
    ) : NetBenchSocketChannel {
        override val transportLabel: String = if (isVpn) "VPN" else "蜂窝网络"
        override val isDirectPhysical: Boolean = !isVpn
        override val bypassSystemHttpProxy: Boolean = !isVpn
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override fun createSocket(): Socket = Socket()
        override fun resolveAllByName(hostname: String): List<InetAddress> = InetAddress.getAllByName(hostname).toList()
        override fun bindDatagramFileDescriptor(fd: FileDescriptor) = onBindFd(fd)
    }

    private fun directBridge(
        supportsFdBind: Boolean = true,
        isVpnActive: Boolean = false,
        directIface: String? = "rmnet_data0",
        onBindFd: (FileDescriptor) -> Unit = {},
    ): NetBenchNetworkBridge = object : NetBenchNetworkBridge {
        override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
            val directChannel = if (isVpnActive && directIface == null) null else FakeChannel(
                "net-direct",
                isVpn = false,
                supportsFileDescriptorBind = supportsFdBind,
                onBindFd = onBindFd,
            )
            val directDesc = directChannel?.let { ch ->
                NetworkDescriptor<NetBenchSocketChannel>(
                    token = ch,
                    networkId = "net-direct",
                    isVpn = false,
                    hasInternet = true,
                    isValidated = true,
                    transports = setOf(NetBenchTransportKind.CELLULAR),
                    interfaceName = directIface,
                )
            }
            val defaultChannel = FakeChannel("net-default", isVpn = isVpnActive, supportsFileDescriptorBind = false)
            val vpnDesc = NetworkDescriptor<NetBenchSocketChannel>(
                token = defaultChannel,
                networkId = "net-vpn",
                isVpn = true,
                hasInternet = true,
                isActiveDefault = true,
                interfaceName = "tun0",
            )
            val candidates = if (isVpnActive) listOfNotNull(vpnDesc, directDesc) else listOfNotNull(directDesc)
            val plan = NetBenchNetworkSelector.selectNetworkPlan(candidates)
            return ResolvedNetworkBinding(plan, directChannel, defaultChannel)
        }
    }

    @Test
    fun `IcmpErrQueueParser decodes IPv4 and IPv6 sock_extended_err offender addresses`() {
        val v4Payload = ByteArray(32).apply {
            this[4] = IcmpErrQueueParser.SO_EE_ORIGIN_ICMP.toByte()
            this[5] = IcmpErrQueueParser.ICMP_V4_TIME_EXCEEDED.toByte()
            this[6] = 0
            // sockaddr_in sin_addr at bytes 20..23 -> 59.43.138.1
            this[20] = 59
            this[21] = 43
            this[22] = 138.toByte()
            this[23] = 1
        }
        val parsedV4 = IcmpErrQueueParser.parse(v4Payload, IpFamily.IPV4)
        assertNotNull(parsedV4)
        assertEquals(11, parsedV4!!.icmpType)
        assertEquals("59.43.138.1", parsedV4.offenderIp)

        val v6Payload = ByteArray(48).apply {
            this[4] = IcmpErrQueueParser.SO_EE_ORIGIN_ICMP6.toByte()
            this[5] = IcmpErrQueueParser.ICMP_V6_TIME_EXCEEDED.toByte()
            this[6] = 0
            // sockaddr_in6 sin6_addr at bytes 24..39 -> 2402:4f00::1
            this[24] = 0x24
            this[25] = 0x02
            this[26] = 0x4f
            this[27] = 0x00
            this[39] = 0x01
        }
        val parsedV6 = IcmpErrQueueParser.parse(v6Payload, IpFamily.IPV6)
        assertNotNull(parsedV6)
        assertEquals(3, parsedV6!!.icmpType)
        assertEquals("2402:4f00:0:0:0:0:0:1", parsedV6.offenderIp)
    }

    @Test
    fun `stage1 kernel ops executes multi-round MTR with GeoIP enrichment and CN2 GIA tagging`() = runBlocking {
        val mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ip = request.path?.substringAfter("/ipwhois/") ?: ""
                return when {
                    ip.startsWith("202.97.") -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"ip":"$ip","country_code":"CN","country":"China","city":"Shanghai","connection":{"asn":4134,"org":"ChinaNet","isp":"China Telecom"}}""",
                    )
                    ip.startsWith("59.43.") -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"ip":"$ip","country_code":"US","country":"United States","city":"Los Angeles","connection":{"asn":4809,"org":"China Telecom Next Carrying Network","isp":"CN2"}}""",
                    )
                    ip == "45.12.34.56" -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"ip":"$ip","country_code":"US","country":"United States","city":"Los Angeles","connection":{"asn":137872,"org":"DMIT Inc.","isp":"DMIT"}}""",
                    )
                    else -> MockResponse().setResponseCode(200).setBody("""{"success":false}""")
                }
            }
        }
        mockServer.start()
        try {
            val base = mockServer.url("/").toString().trimEnd('/')
            val intelClient = IpIntelligenceClient(
                httpClient = OkHttpClient(),
                endpoints = IpIntelligenceEndpoints(
                    ipWhoIsTargetTemplate = "$base/ipwhois/{ip}",
                    ipInfoTargetTemplate = "$base/none/{ip}",
                    ipApiTargetTemplate = "$base/none/{ip}",
                    ripeStatWhoisTemplate = "$base/none/{ip}",
                    rdapTemplates = emptyList(),
                    clientSelfUrls = emptyList(),
                    clientDirectUrls = emptyList(),
                ),
            )
            var lastTtl = 0
            val fakeKernel = object : TracerouteKernelOps {
                override fun isSupported(): Boolean = true
                override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?): FileDescriptor = FileDescriptor()
                override fun sendEchoWithTtl(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, ttl: Int, identifier: Int, sequence: Int) {
                    lastTtl = ttl
                }
                override fun awaitReplyOrError(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, identifier: Int, expectedSequence: Int, timeoutMs: Int): TracerouteProbeReply {
                    return when (lastTtl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "192.168.1.1", expectedSequence, 1.2)
                        2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "202.97.33.1", expectedSequence, 8.5)
                        3 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.138.1", expectedSequence, 145.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "45.12.34.56", expectedSequence, 152.4)
                    }
                }
                override fun closeQuietly(fd: FileDescriptor) = Unit
            }

            val bridge = directBridge(supportsFdBind = true)
            val runner = TracerouteRunner(
                resolver = NetBenchTargetResolver(networkBridge = bridge, ioDispatcher = Dispatchers.IO),
                networkBridge = bridge,
                kernelOps = fakeKernel,
                pingExecutor = PingProcessExecutor { flow { emit(PingExecutionEvent.Exited(-1)) } },
                ipIntelligenceClient = intelClient,
                reverseDnsLookup = ReverseDnsLookup { ip -> if (ip == "59.43.138.1") "core.ctgnet.cn" else null },
                ioDispatcher = Dispatchers.IO,
            )

            val emissions = runner.run(TracerouteConfig(target = "45.12.34.56", probesPerHop = 3, maxHops = 10)).toList()
            val finalProgress = emissions.last()
            assertTrue(finalProgress.isFinished)
            assertNull(finalProgress.errorMessage)
            assertEquals(IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND, finalProgress.summary.bindingMode)
            assertTrue(finalProgress.summary.reachedTarget)
            assertEquals(4, finalProgress.summary.totalHops)
            assertEquals(3, finalProgress.summary.hops[2].sent)
            assertEquals(3, finalProgress.summary.hops[2].received)
            assertTrue(BackboneRouteTag.CN2_GIA in finalProgress.summary.dominantBackboneTags)
            assertTrue(finalProgress.summary.hops[0].isPrivateOrCgnat)
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `stage2 subprocess ping fallback handles TTL exceeded and echo reply`() = runBlocking {
        val bridge = directBridge(supportsFdBind = false, directIface = "wlan0")
        val unsupportedKernel = object : TracerouteKernelOps {
            override fun isSupported(): Boolean = false
            override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?) = error("unsupported")
            override fun sendEchoWithTtl(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, ttl: Int, identifier: Int, sequence: Int) = Unit
            override fun awaitReplyOrError(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, identifier: Int, expectedSequence: Int, timeoutMs: Int) = error("unsupported")
            override fun closeQuietly(fd: FileDescriptor) = Unit
        }
        val commandsSeen = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val fakePing = PingProcessExecutor { req ->
            flow {
                commandsSeen += req.command
                val ttlIdx = req.command.indexOf("-t")
                val ttl = req.command[ttlIdx + 1].toInt()
                if (ttl == 1) {
                    emit(PingExecutionEvent.StdoutLine("From 218.105.2.5 icmp_seq=1 Time to live exceeded"))
                } else {
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 198.51.100.2: icmp_seq=1 ttl=54 time=32.5 ms"))
                }
                emit(PingExecutionEvent.Exited(0))
            }
        }
        val intelClient = IpIntelligenceClient(
            httpClient = OkHttpClient(),
            endpoints = IpIntelligenceEndpoints(
                ipWhoIsTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ipInfoTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ipApiTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ripeStatWhoisTemplate = "http://127.0.0.1:1/none/{ip}",
                rdapTemplates = emptyList(),
                clientSelfUrls = emptyList(),
                clientDirectUrls = emptyList(),
            ),
        )
        val runner = TracerouteRunner(
            resolver = NetBenchTargetResolver(networkBridge = bridge, ioDispatcher = Dispatchers.IO),
            networkBridge = bridge,
            kernelOps = unsupportedKernel,
            pingExecutor = fakePing,
            ipIntelligenceClient = intelClient,
            reverseDnsLookup = ReverseDnsLookup { null },
            ioDispatcher = Dispatchers.IO,
        )

        val finalProgress = runner.run(TracerouteConfig(target = "198.51.100.2", probesPerHop = 2, maxHops = 5)).toList().last()
        assertTrue(finalProgress.isFinished)
        assertTrue(finalProgress.summary.reachedTarget)
        assertEquals(2, finalProgress.summary.totalHops)
        assertEquals("218.105.2.5", finalProgress.summary.hops[0].ip)
        assertTrue(BackboneRouteTag.CU_9929 in finalProgress.summary.dominantBackboneTags)
        assertTrue(commandsSeen.any { "-I" in it && "wlan0" in it })
    }

    @Test
    fun `VPN lockdown guard blocks traceroute instead of silently routing through VPN`() = runBlocking {
        val bridge = directBridge(supportsFdBind = true, isVpnActive = true, directIface = null)
        val pingCalls = AtomicInteger(0)
        val runner = TracerouteRunner(
            resolver = NetBenchTargetResolver(networkBridge = bridge, ioDispatcher = Dispatchers.IO),
            networkBridge = bridge,
            kernelOps = object : TracerouteKernelOps {
                override fun isSupported(): Boolean = true
                override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?) = FileDescriptor()
                override fun sendEchoWithTtl(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, ttl: Int, identifier: Int, sequence: Int) = Unit
                override fun awaitReplyOrError(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, identifier: Int, expectedSequence: Int, timeoutMs: Int) =
                    TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, expectedSequence, null)
                override fun closeQuietly(fd: FileDescriptor) = Unit
            },
            pingExecutor = PingProcessExecutor {
                pingCalls.incrementAndGet()
                flow { emit(PingExecutionEvent.Exited(0)) }
            },
            reverseDnsLookup = ReverseDnsLookup { null },
            ioDispatcher = Dispatchers.IO,
        )

        val finalProgress = runner.run(TracerouteConfig(target = "1.1.1.1", useProxy = false)).toList().last()
        assertTrue(finalProgress.isFinished)
        assertNotNull(finalProgress.errorMessage)
        assertTrue(finalProgress.errorMessage!!.contains("经由代理测试"))
        assertEquals(0, pingCalls.get())
    }

    @Test
    fun `TraceroutePingFallback parses uncoloned IPv4 and IPv6 Destination Unreachable and Packet filtered replies`() {
        val v4Unreach = com.nodeseek.app.data.netbench.TraceroutePingFallback.parseTtlPingOutput(
            lines = listOf("From 192.0.2.1 icmp_seq=1 Destination Net Unreachable"),
            targetIp = "198.51.100.2",
            measuredElapsedMs = 14.2,
            sequence = 1,
        )
        assertEquals(TracerouteProbeReply.Kind.DEST_UNREACHABLE, v4Unreach.kind)
        assertEquals("192.0.2.1", v4Unreach.responderIp)

        val v6Filtered = com.nodeseek.app.data.netbench.TraceroutePingFallback.parseTtlPingOutput(
            lines = listOf("From 2001:db8::1 icmp_seq=1 Packet filtered"),
            targetIp = "2001:db8::99",
            measuredElapsedMs = 21.5,
            sequence = 1,
        )
        assertEquals(TracerouteProbeReply.Kind.DEST_UNREACHABLE, v6Filtered.kind)
        assertEquals("2001:db8::1", v6Filtered.responderIp)

        val v6TimeExceeded = TraceroutePingFallback.parseTtlPingOutput(
            lines = listOf("From 2001:db8::2: icmp_seq=1 Time exceeded: Hop limit"),
            targetIp = "2001:db8::99",
            measuredElapsedMs = 9.0,
            sequence = 1,
        )
        assertEquals(TracerouteProbeReply.Kind.TIME_EXCEEDED, v6TimeExceeded.kind)
        assertEquals("2001:db8::2", v6TimeExceeded.responderIp)

        val bsdBytesFrom = TraceroutePingFallback.parseTtlPingLine(
            rawLine = "92 bytes from 59.43.138.1: Time to live exceeded",
            targetIp = "45.12.34.56",
            measuredElapsedMs = 11.4,
            sequence = 2,
        )
        assertNotNull(bsdBytesFrom)
        assertEquals(TracerouteProbeReply.Kind.TIME_EXCEEDED, bsdBytesFrom!!.kind)
        assertEquals("59.43.138.1", bsdBytesFrom.responderIp)
    }

    @Test
    fun `IcmpErrQueueParser correlates ICMP and UDP probe sequences cleanly`() {
        val icmpReq = com.nodeseek.app.data.netbench.IcmpEchoPacketCodec.encodeEchoRequest(
            ipFamily = IpFamily.IPV4,
            identifier = 0x1234,
            sequence = 42,
        )
        assertEquals(42, IcmpErrQueueParser.extractOriginalIcmpSequence(icmpReq, icmpReq.size))

        val udpPayload = IcmpErrQueueParser.encodeUdpProbePayload(sequence = 77, ttl = 5)
        assertEquals(77, IcmpErrQueueParser.decodeUdpProbeSequence(udpPayload, udpPayload.size))
        assertNull(IcmpErrQueueParser.decodeUdpProbeSequence(ByteArray(4), 4))
    }

    @Test
    fun `concurrent multi-protocol kernel falls back from ICMP to UDP and promotes winning protocol`() = runBlocking {
        val bridge = directBridge(supportsFdBind = true)
        val protocolsTried = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, TracerouteProbeProtocol>>())
        val multiProtoKernel = object : TracerouteKernelOps {
            override fun isSupported(): Boolean = true
            override val supportsConcurrentMultiProtocol: Boolean = true
            override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?) = FileDescriptor()
            override fun sendEchoWithTtl(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, ttl: Int, identifier: Int, sequence: Int) = Unit
            override fun awaitReplyOrError(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, identifier: Int, expectedSequence: Int, timeoutMs: Int) =
                error("should use probeHop")
            override fun probeHop(
                targetAddress: InetAddress,
                ipFamily: IpFamily,
                ttl: Int,
                identifier: Int,
                sequence: Int,
                timeoutMs: Int,
                channel: NetBenchSocketChannel?,
                protocol: TracerouteProbeProtocol,
                targetPort: Int,
            ): TracerouteProbeReply {
                protocolsTried += ttl to protocol
                if (protocol == TracerouteProbeProtocol.ICMP) {
                    return TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, sequence, null, protocol = protocol)
                }
                if (protocol == TracerouteProbeProtocol.UDP) {
                    return when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "192.168.1.1", sequence, 1.5, protocol = protocol)
                        2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.138.1", sequence, 14.2, protocol = protocol)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "45.12.34.56", sequence, 142.0, protocol = protocol)
                    }
                }
                return TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, sequence, null, protocol = protocol)
            }
            override fun closeQuietly(fd: FileDescriptor) = Unit
        }
        val offlineIntel = IpIntelligenceClient(
            httpClient = OkHttpClient(),
            endpoints = IpIntelligenceEndpoints(
                ipWhoIsTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ipInfoTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ipApiTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ripeStatWhoisTemplate = "http://127.0.0.1:1/none/{ip}",
                rdapTemplates = emptyList(),
                clientSelfUrls = emptyList(),
                clientDirectUrls = emptyList(),
            ),
        )
        val runner = TracerouteRunner(
            resolver = NetBenchTargetResolver(networkBridge = bridge, ioDispatcher = Dispatchers.IO),
            networkBridge = bridge,
            kernelOps = multiProtoKernel,
            pingExecutor = PingProcessExecutor { flow { emit(PingExecutionEvent.Exited(-1)) } },
            ipIntelligenceClient = offlineIntel,
            reverseDnsLookup = ReverseDnsLookup { null },
            ioDispatcher = Dispatchers.IO,
        )

        val finalProgress = runner.run(TracerouteConfig(target = "45.12.34.56", probesPerHop = 3, maxHops = 10)).toList().last()
        assertTrue(finalProgress.isFinished)
        assertNull(finalProgress.errorMessage)
        assertTrue(finalProgress.summary.reachedTarget)
        assertEquals(3, finalProgress.summary.totalHops)
        assertEquals("Kernel-UDP", finalProgress.summary.toolName)
        assertEquals("59.43.138.1", finalProgress.summary.hops[1].ip)
        assertTrue(BackboneRouteTag.CN2_GIA in finalProgress.summary.dominantBackboneTags)
    }

    @Test
    fun `kernel zero-reply batch1 automatically rescues via concurrent Stage-2 ping fallback`() = runBlocking {
        val bridge = directBridge(supportsFdBind = true, directIface = "wlan0")
        val silentKernel = object : TracerouteKernelOps {
            override fun isSupported(): Boolean = true
            override val supportsConcurrentMultiProtocol: Boolean = true
            override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?) = FileDescriptor()
            override fun sendEchoWithTtl(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, ttl: Int, identifier: Int, sequence: Int) = Unit
            override fun awaitReplyOrError(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, identifier: Int, expectedSequence: Int, timeoutMs: Int) =
                TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, expectedSequence, null)
            override fun probeHop(
                targetAddress: InetAddress,
                ipFamily: IpFamily,
                ttl: Int,
                identifier: Int,
                sequence: Int,
                timeoutMs: Int,
                channel: NetBenchSocketChannel?,
                protocol: TracerouteProbeProtocol,
                targetPort: Int,
            ) = TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, sequence, null, protocol = protocol)
            override fun closeQuietly(fd: FileDescriptor) = Unit
        }
        val rescuePing = PingProcessExecutor { req ->
            flow {
                val ttl = req.command[req.command.indexOf("-t") + 1].toInt()
                if (ttl == 1) {
                    emit(PingExecutionEvent.StdoutLine("From 59.43.80.1 icmp_seq=1 Time to live exceeded"))
                } else {
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 198.51.100.2: icmp_seq=1 ttl=55 time=25.0 ms"))
                }
                emit(PingExecutionEvent.Exited(0))
            }
        }
        val offlineIntel = IpIntelligenceClient(
            httpClient = OkHttpClient(),
            endpoints = IpIntelligenceEndpoints(
                ipWhoIsTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ipInfoTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ipApiTargetTemplate = "http://127.0.0.1:1/none/{ip}",
                ripeStatWhoisTemplate = "http://127.0.0.1:1/none/{ip}",
                rdapTemplates = emptyList(),
                clientSelfUrls = emptyList(),
                clientDirectUrls = emptyList(),
            ),
        )
        val runner = TracerouteRunner(
            resolver = NetBenchTargetResolver(networkBridge = bridge, ioDispatcher = Dispatchers.IO),
            networkBridge = bridge,
            kernelOps = silentKernel,
            pingExecutor = rescuePing,
            ipIntelligenceClient = offlineIntel,
            reverseDnsLookup = ReverseDnsLookup { null },
            ioDispatcher = Dispatchers.IO,
        )

        val finalProgress = runner.run(TracerouteConfig(target = "198.51.100.2", probesPerHop = 2, maxHops = 6)).toList().last()
        assertTrue(finalProgress.isFinished)
        assertNull(finalProgress.errorMessage)
        assertTrue(finalProgress.summary.reachedTarget)
        assertEquals("ping-TTL", finalProgress.summary.toolName)
        assertEquals(2, finalProgress.summary.totalHops)
        assertEquals("59.43.80.1", finalProgress.summary.hops[0].ip)
    }

    @Test
    fun `AndroidOsTracerouteKernelOps TCP probe distinguishes POLLERR-HUP zero soErr from real POLLOUT connect and RST`() {
        val target = InetAddress.getByName("45.12.34.56")
        val pollOut = 0x0004
        val pollErr = 0x0008
        val pollHup = 0x0010
        val econnRefused = 111

        fun runTcpCase(
            revents: Int,
            errQueueMsg: IcmpErrQueueParser.ParsedErrQueueMessage? = null,
            soErr: Int = 0,
        ): TracerouteProbeReply {
            var now = 1_000_000L
            val fakeSyscalls = object : com.nodeseek.app.data.netbench.TraceroutePosixSyscalls {
                override fun ensureSupportedRuntime(sdkInt: Int) = true
                override fun openNonBlockingIcmpSocket(ipFamily: IpFamily) = FileDescriptor()
                override fun openNonBlockingTcpSocket(ipFamily: IpFamily) = FileDescriptor()
                override fun enableRecverr(fd: FileDescriptor, ipFamily: IpFamily) = Unit
                override fun setTtl(fd: FileDescriptor, ipFamily: IpFamily, ttl: Int) = Unit
                override fun connectNonBlocking(fd: FileDescriptor, address: InetAddress, port: Int) = false
                override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) = Unit
                override fun pollEvents(fd: FileDescriptor, timeoutMs: Int) = revents
                override fun pollEventsWithMask(fd: FileDescriptor, eventsMask: Int, timeoutMs: Int): Int {
                    now += 5_000_000L
                    return revents
                }
                override fun recvEchoReply(fd: FileDescriptor, buffer: ByteArray) = -1
                override fun recvErrQueue(fd: FileDescriptor, ipFamily: IpFamily) = errQueueMsg
                override fun getSocketError(fd: FileDescriptor) = soErr
                override fun closeQuietly(fd: FileDescriptor) = Unit
            }
            val ops = com.nodeseek.app.data.netbench.AndroidOsTracerouteKernelOps(
                sdkInt = 33,
                syscalls = fakeSyscalls,
                nanoClock = { now },
            )
            return ops.probeHop(
                targetAddress = target,
                ipFamily = IpFamily.IPV4,
                ttl = 2,
                identifier = 0x1234,
                sequence = 1,
                timeoutMs = 500,
                channel = null,
                protocol = TracerouteProbeProtocol.TCP,
                targetPort = 443,
            )
        }

        // Case 1: POLLERR without errqueue offender and soErr == 0 (e.g. SO_EE_ORIGIN_LOCAL / EAGAIN) -> TIMEOUT (NOT ECHO_REPLY!)
        val errZero = runTcpCase(revents = pollErr, errQueueMsg = null, soErr = 0)
        assertEquals(TracerouteProbeReply.Kind.TIMEOUT, errZero.kind)
        assertNull(errZero.responderIp)

        // Case 2: POLLERR | POLLOUT with null offender and soErr == 0 -> STILL TIMEOUT (error bit set!)
        val errOutZero = runTcpCase(revents = pollErr or pollOut, errQueueMsg = null, soErr = 0)
        assertEquals(TracerouteProbeReply.Kind.TIMEOUT, errOutZero.kind)
        assertNull(errOutZero.responderIp)

        // Case 3: POLLHUP with soErr == 0 -> TIMEOUT
        val hupZero = runTcpCase(revents = pollHup, errQueueMsg = null, soErr = 0)
        assertEquals(TracerouteProbeReply.Kind.TIMEOUT, hupZero.kind)

        // Case 4: POLLERR with ECONNREFUSED -> target host replied RST -> ECHO_REPLY
        val rstReply = runTcpCase(revents = pollErr, errQueueMsg = null, soErr = econnRefused)
        assertEquals(TracerouteProbeReply.Kind.ECHO_REPLY, rstReply.kind)
        assertEquals("45.12.34.56", rstReply.responderIp)

        // Case 5: Clean POLLOUT (no POLLERR/POLLHUP) with soErr == 0 -> SYN-ACK connected -> ECHO_REPLY
        val connected = runTcpCase(revents = pollOut, errQueueMsg = null, soErr = 0)
        assertEquals(TracerouteProbeReply.Kind.ECHO_REPLY, connected.kind)
        assertEquals("45.12.34.56", connected.responderIp)

        // Case 6: POLLERR with ICMP Time Exceeded offender IP -> TIME_EXCEEDED with router IP
        val ttlExceeded = runTcpCase(
            revents = pollErr,
            errQueueMsg = IcmpErrQueueParser.ParsedErrQueueMessage(
                origin = IcmpErrQueueParser.SO_EE_ORIGIN_ICMP,
                icmpType = IcmpErrQueueParser.ICMP_V4_TIME_EXCEEDED,
                icmpCode = 0,
                offenderIp = "59.43.138.1",
            ),
            soErr = 113,
        )
        assertEquals(TracerouteProbeReply.Kind.TIME_EXCEEDED, ttlExceeded.kind)
        assertEquals("59.43.138.1", ttlExceeded.responderIp)
    }
}
