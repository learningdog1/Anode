package com.nodeseek.app.netbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nodeseek.app.data.netbench.HttpLatencyRunner
import com.nodeseek.app.data.netbench.IcmpPingRunner
import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.data.netbench.Iperf3ClientRunner
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.PingExecutionEvent
import com.nodeseek.app.data.netbench.PingProcessExecutor
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.data.netbench.TcpPingRunner
import com.nodeseek.app.data.netbench.TcpProbeConnector
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.netbench.NetBenchSessionController
import com.nodeseek.app.ui.netbench.insertNetBenchMarkdownAtCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.concurrent.thread

class NetBenchOrchestrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `http latency runner probes HEAD and falls back to GET on 405`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "HEAD" -> MockResponse().setResponseCode(405)
                "GET" -> MockResponse().setResponseCode(200).setBody("ok")
                else -> MockResponse().setResponseCode(400)
            }
        }
        server.start()
        try {
            val runner = HttpLatencyRunner(baseHttpClient = OkHttpClient(), ioDispatcher = Dispatchers.IO)
            val result = runner.probe("http://127.0.0.1:${server.port}/", port = server.port)
            assertTrue(result.success)
            assertEquals(200, result.statusCode)
            assertNotNull(result.totalMs)
            assertNotNull(result.ttfbMs)
            assertTrue(result.totalMs!! > 0.0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `session controller runs tcping plus ip check generates privacy-safe markdown and records history`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/ipwhois/") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "ip": "127.0.0.1",
                      "country": "Hong Kong",
                      "country_code": "HK",
                      "city": "Central",
                      "connection": {"asn": 137872, "org": "DMIT Inc.", "isp": "DMIT Inc."}
                    }
                    """.trimIndent(),
                )
                request.path!!.startsWith("/ripestat") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": {
                        "authorities": ["apnic"],
                        "records": [[{"key": "country", "value": "HK"}]]
                      }
                    }
                    """.trimIndent(),
                )
                request.path == "/self" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "ip": "114.240.99.77",
                      "country_code": "CN",
                      "connection": {"asn": 4134, "isp": "ChinaNet", "org": "China Telecom"}
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val base = server.url("/").toString().trimEnd('/')
            val historyStore = NetBenchHistoryStore(
                historyFile = File(tempFolder.root, NetBenchHistoryStore.HISTORY_FILE_NAME),
                ioDispatcher = Dispatchers.IO,
            )
            val resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO)
            val tcpRunner = TcpPingRunner(
                resolver = resolver,
                connector = TcpProbeConnector { _, _, _ -> 32.5 },
                ioDispatcher = Dispatchers.IO,
            )
            val ipClient = IpIntelligenceClient(
                httpClient = OkHttpClient(),
                endpoints = IpIntelligenceEndpoints(
                    ipWhoIsTargetTemplate = "$base/ipwhois/{ip}",
                    ipInfoTargetTemplate = "$base/404/{ip}",
                    ipApiTargetTemplate = "$base/404/{ip}",
                    ripeStatWhoisTemplate = "$base/ripestat?resource={ip}",
                    rdapTemplates = emptyList(),
                    clientSelfUrls = listOf("ipwho.is" to "$base/self"),
                ),
                ioDispatcher = Dispatchers.IO,
            )

            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                targetResolver = resolver,
                tcpPingRunner = tcpRunner,
                ipIntelligenceClient = ipClient,
            )

            controller.updateTargetInput("127.0.0.1")
            controller.updateProbeCount(4)
            controller.startBenchmark()

            // Wait up to 4 seconds for pipeline completion
            var attempts = 0
            while (controller.uiState.value.activePhase != NetBenchPhase.COMPLETED && attempts < 80) {
                delay(50)
                attempts++
            }

            val finalState = controller.uiState.value
            assertEquals(NetBenchPhase.COMPLETED, finalState.activePhase)
            assertFalse(finalState.isRunning)
            assertTrue("maskTargetIpInReport must default to true", finalState.maskTargetIpInReport)
            assertTrue("includeClientNetworkInReport must default to true", finalState.includeClientNetworkInReport)
            assertEquals(4, finalState.tcpPingProgress?.summary?.received)
            assertEquals(IpOriginType.NATIVE, finalState.ipIntelligence?.originType)
            assertTrue(finalState.generatedMarkdown.contains("中国电信"))
            assertTrue(finalState.generatedMarkdown.contains("原生 IP"))
            assertFalse(finalState.generatedMarkdown.contains("114.240.99.77"))
            assertTrue("Target IP last octet should be masked by default", finalState.generatedMarkdown.contains("127.0.0.*"))

            // Toggle includeClientNetwork off -> removes carrier info from markdown immediately
            controller.toggleIncludeClientNetwork(false)
            assertFalse(controller.uiState.value.generatedMarkdown.contains("中国电信"))
            controller.toggleIncludeClientNetwork(true)
            assertTrue(controller.uiState.value.generatedMarkdown.contains("中国电信"))

            // Enabling iperf3 triggers server preparation prompt
            assertFalse(controller.uiState.value.showIperf3ServerPrompt)
            controller.toggleIperf3(true)
            assertTrue(controller.uiState.value.enableIperf3)
            assertTrue(controller.uiState.value.showIperf3ServerPrompt)
            controller.dismissIperf3ServerPrompt()
            assertFalse(controller.uiState.value.showIperf3ServerPrompt)
            assertTrue(controller.uiState.value.enableIperf3)

            // Switch style to FULL_TABLE and verify live regeneration
            controller.selectMarkdownStyle(NetBenchMarkdownStyle.FULL_TABLE)
            assertTrue(controller.uiState.value.generatedMarkdown.contains("测试项目"))
            assertTrue(controller.uiState.value.generatedMarkdown.contains("实测结果"))
            assertFalse(controller.uiState.value.generatedMarkdown.contains("114.240.99.77"))

            // History should be persisted
            assertEquals(1, historyStore.entries.value.size)
            assertEquals("127.0.0.1", historyStore.entries.value.first().host)
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `insertNetBenchMarkdownAtCursor inserts cleanly with blank-line separation`() {
        val initial = TextFieldValue(
            text = "刚测了一下这台机器：\n后续补充",
            selection = TextRange(10),
        )
        val block = "> 📡 **本机网络实测（中国电信 · IPv4）**"
        val updated = insertNetBenchMarkdownAtCursor(initial, block)
        assertTrue(updated.text.contains("刚测了一下这台机器：\n\n> 📡 **本机网络实测（中国电信 · IPv4）**\n\n后续补充"))
        assertEquals(
            updated.text.indexOf(block) + block.length,
            updated.selection.start,
        )
    }

    @Test
    fun `session controller updates dual egress on network switch and toggles testViaProxy cleanly`() = runBlocking {
        val directServer = MockWebServer()
        val proxyServer = MockWebServer()
        val switchedToCellular = AtomicBoolean(false)

        directServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (switchedToCellular.get()) {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"ip":"120.204.10.5","country_code":"CN","connection":{"asn":9808,"isp":"China Mobile","org":"CMNET"}}""",
                )
            } else {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"ip":"114.240.99.77","country_code":"CN","connection":{"asn":4134,"isp":"ChinaNet","org":"China Telecom"}}""",
                )
            }
        }
        proxyServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/ipwhois/") -> MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"ip":"127.0.0.1","country":"Hong Kong","country_code":"HK","connection":{"asn":137872,"org":"DMIT","isp":"DMIT"}}""",
                )
                request.path!!.startsWith("/ripestat") -> MockResponse().setResponseCode(200).setBody(
                    """{"data":{"authorities":["apnic"],"records":[[{"key":"country","value":"HK"}]]}}""",
                )
                else -> MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"ip":"45.76.200.10","country_code":"JP","connection":{"asn":20473,"isp":"Vultr","org":"Vultr"}}""",
                )
            }
        }
        directServer.start()
        proxyServer.start()

        val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val directChannel = object : NetBenchSocketChannel {
            override val networkId: String
                get() = if (switchedToCellular.get()) "cell-102" else "wifi-101"
            override val transportLabel: String
                get() = if (switchedToCellular.get()) "蜂窝移动" else "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = object : SocketFactory() {
                override fun createSocket(): Socket = object : Socket() {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        val inet = endpoint as? InetSocketAddress
                        val redirected = if (inet != null && inet.port == proxyServer.port) {
                            InetSocketAddress(InetAddress.getByName("127.0.0.1"), directServer.port)
                        } else {
                            endpoint
                        }
                        super.connect(redirected, timeout)
                    }
                }
                override fun createSocket(host: String?, port: Int) = error("unused")
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = error("unused")
                override fun createSocket(host: InetAddress?, port: Int) = error("unused")
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = error("unused")
            }
            override fun createSocket(): Socket = socketFactory.createSocket()
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val proxyChannel = object : NetBenchSocketChannel {
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
                val transport = if (switchedToCellular.get()) NetBenchTransportKind.CELLULAR else NetBenchTransportKind.WIFI
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(proxyChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        NetworkDescriptor(directChannel, directChannel.networkId, isVpn = false, hasInternet = true, isValidated = true, transports = setOf(transport)),
                    ),
                )
                return ResolvedNetworkBinding(plan, directChannel, proxyChannel)
            }

            override fun observeNetworkChanges(): Flow<Unit> = changesFlow
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val base = proxyServer.url("/").toString().trimEnd('/')
            val historyStore = NetBenchHistoryStore(
                historyFile = File(tempFolder.root, NetBenchHistoryStore.HISTORY_FILE_NAME),
                ioDispatcher = Dispatchers.IO,
            )
            val resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge)
            val tcpRunner = TcpPingRunner(
                resolver = resolver,
                connector = TcpProbeConnector { _, _, _ -> 25.0 },
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
            )
            val ipClient = IpIntelligenceClient(
                httpClient = OkHttpClient(),
                endpoints = IpIntelligenceEndpoints(
                    ipWhoIsTargetTemplate = "$base/ipwhois/{ip}",
                    ipInfoTargetTemplate = "$base/404/{ip}",
                    ipApiTargetTemplate = "$base/404/{ip}",
                    ripeStatWhoisTemplate = "$base/ripestat?resource={ip}",
                    rdapTemplates = emptyList(),
                    clientSelfUrls = listOf("ipwho.is" to "http://localhost:${proxyServer.port}/self"),
                ),
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
            )

            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                networkBridge = bridge,
                targetResolver = resolver,
                tcpPingRunner = tcpRunner,
                ipIntelligenceClient = ipClient,
            )

            var tries = 0
            while (controller.uiState.value.clientEgress == null && tries < 60) {
                delay(50)
                tries++
            }
            val initialEgress = controller.uiState.value.clientEgress
            assertNotNull(initialEgress)
            assertTrue(initialEgress!!.hasDualExitIps)
            assertEquals(ClientCarrier.CHINA_TELECOM, initialEgress.directNetwork?.carrier)
            assertEquals(ClientCarrier.OVERSEAS, initialEgress.proxyNetwork?.carrier)
            assertEquals(ClientCarrier.CHINA_TELECOM, controller.uiState.value.clientNetwork?.carrier)

            // Simulate Wi-Fi -> Cellular switch
            switchedToCellular.set(true)
            changesFlow.tryEmit(Unit)
            tries = 0
            while (controller.uiState.value.clientEgress?.directNetwork?.carrier != ClientCarrier.CHINA_MOBILE && tries < 60) {
                delay(50)
                tries++
            }
            assertEquals(ClientCarrier.CHINA_MOBILE, controller.uiState.value.clientEgress?.directNetwork?.carrier)
            assertEquals("蜂窝移动", controller.uiState.value.clientEgress?.directNetwork?.transportLabel)

            // Run benchmark with default direct mode vs testViaProxy=true
            controller.updateTargetInput("127.0.0.1")
            controller.updateProbeCount(4)
            controller.startBenchmark()
            tries = 0
            while (controller.uiState.value.activePhase != NetBenchPhase.COMPLETED && tries < 60) {
                delay(50)
                tries++
            }
            assertTrue(controller.uiState.value.generatedMarkdown.contains("中国移动"))
            assertFalse(controller.uiState.value.generatedMarkdown.contains("经由代理"))

            controller.toggleTestViaProxy(true)
            assertTrue(controller.uiState.value.testViaProxy)
            assertEquals(ClientCarrier.OVERSEAS, controller.uiState.value.clientNetwork?.carrier)
            assertTrue(controller.uiState.value.generatedMarkdown.contains("经由代理"))
            assertFalse(controller.uiState.value.generatedMarkdown.contains("45.76.200.10"))
            assertFalse(controller.uiState.value.generatedMarkdown.contains("120.204.10.5"))
        } finally {
            scope.cancel()
            directServer.shutdown()
            proxyServer.shutdown()
        }
    }

    @Test
    fun `session controller executes ICMP Ping before TCPing records ping history and formats Ping first`() = runBlocking {
        val phaseOrder = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val historyStore = NetBenchHistoryStore(
                historyFile = File(tempFolder.root, "icmp_${NetBenchHistoryStore.HISTORY_FILE_NAME}"),
                ioDispatcher = Dispatchers.IO,
            )
            val resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO)
            val icmpRunner = IcmpPingRunner(
                resolver = resolver,
                executor = PingProcessExecutor {
                    phaseOrder += "icmp"
                    flow {
                        emit(PingExecutionEvent.StdoutLine("64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=18.0 ms"))
                        emit(PingExecutionEvent.StdoutLine("64 bytes from 127.0.0.1: icmp_seq=2 ttl=64 time=20.0 ms"))
                        emit(PingExecutionEvent.StdoutLine("64 bytes from 127.0.0.1: icmp_seq=3 ttl=64 time=22.0 ms"))
                        emit(PingExecutionEvent.StdoutLine("64 bytes from 127.0.0.1: icmp_seq=4 ttl=64 time=20.0 ms"))
                        emit(PingExecutionEvent.StdoutLine("4 packets transmitted, 4 received, 0% packet loss"))
                        emit(PingExecutionEvent.StdoutLine("rtt min/avg/max/mdev = 18.0/20.0/22.0/1.414 ms"))
                        emit(PingExecutionEvent.Exited(0))
                    }
                },
                ioDispatcher = Dispatchers.IO,
            )
            val tcpRunner = TcpPingRunner(
                resolver = resolver,
                connector = TcpProbeConnector { _, _, _ ->
                    if ("tcp" !in phaseOrder) phaseOrder += "tcp"
                    31.0
                },
                ioDispatcher = Dispatchers.IO,
            )

            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                targetResolver = resolver,
                icmpPingRunner = icmpRunner,
                tcpPingRunner = tcpRunner,
            )

            assertTrue(controller.uiState.value.enableIcmpPing)
            controller.toggleIpCheck(false)
            controller.updateTargetInput("127.0.0.1")
            controller.updateProbeCount(4)
            controller.startBenchmark()

            var tries = 0
            while (controller.uiState.value.activePhase != NetBenchPhase.COMPLETED && tries < 80) {
                delay(50)
                tries++
            }

            val finalState = controller.uiState.value
            assertEquals(NetBenchPhase.COMPLETED, finalState.activePhase)
            assertEquals(listOf("icmp", "tcp"), phaseOrder)
            assertNotNull(finalState.icmpPingProgress)
            assertEquals(20.0, finalState.icmpPingProgress!!.summary.avgMs!!, 0.01)
            assertEquals(4, finalState.tcpPingProgress?.summary?.received)

            val pingIdx = finalState.generatedMarkdown.indexOf("**Ping (4次)**")
            val tcpIdx = finalState.generatedMarkdown.indexOf("**TCPing (4次)**")
            assertTrue(pingIdx in 0 until tcpIdx)

            val saved = historyStore.entries.value.single()
            assertEquals(20.0, saved.lastPingAvgMs!!, 0.01)
            assertEquals(0.0, saved.lastPingLossPercent!!, 0.01)
            assertEquals(31.0, saved.lastAvgMs!!, 0.01)

            // Verify toggleIcmpPing and clearResults
            controller.clearResults()
            assertEquals(null, controller.uiState.value.icmpPingProgress)
            controller.toggleIcmpPing(false)
            assertFalse(controller.uiState.value.enableIcmpPing)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `session controller clamps and forwards iperf3 parallel streams to runner and markdown`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val receivedParamsJson = AtomicReference<String?>(null)
        val acceptedDataSockets = AtomicInteger(0)
        val serverError = AtomicReference<Throwable?>(null)

        val serverThread = thread(isDaemon = true) {
            try {
                server.soTimeout = 6_000
                val ctrlSocket = server.accept()
                ctrlSocket.soTimeout = 6_000
                val ctrlIn = DataInputStream(ctrlSocket.getInputStream())
                val ctrlOut = DataOutputStream(ctrlSocket.getOutputStream())

                val cookie = ByteArray(37)
                ctrlIn.readFully(cookie)

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_PARAM_EXCHANGE.toInt())
                ctrlOut.flush()
                val paramLen = ctrlIn.readInt()
                val paramBytes = ByteArray(paramLen)
                ctrlIn.readFully(paramBytes)
                receivedParamsJson.set(String(paramBytes, StandardCharsets.UTF_8))

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_CREATE_STREAMS.toInt())
                ctrlOut.flush()
                val dataSockets = mutableListOf<Socket>()
                repeat(4) {
                    val ds = server.accept()
                    val dsCookie = ByteArray(37)
                    DataInputStream(ds.getInputStream()).readFully(dsCookie)
                    acceptedDataSockets.incrementAndGet()
                    dataSockets += ds
                }

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_START.toInt())
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_RUNNING.toInt())
                ctrlOut.flush()

                val chunk = ByteArray(65_536) { 0x33 }
                dataSockets.forEach { ds ->
                    ds.getOutputStream().write(chunk)
                    ds.getOutputStream().flush()
                }
                assertEquals(Iperf3ClientRunner.STATE_TEST_END, ctrlIn.readByte())

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_EXCHANGE_RESULTS.toInt())
                ctrlOut.flush()
                val clientResLen = ctrlIn.readInt()
                ctrlIn.readFully(ByteArray(clientResLen))

                val serverResJson = """
                    {
                      "cpu_util_total": 1.0,
                      "streams": [
                        {"id": 1, "bytes": 65536, "retransmits": 0, "start_time": 0.0, "end_time": 1.0},
                        {"id": 3, "bytes": 65536, "retransmits": 0, "start_time": 0.0, "end_time": 1.0},
                        {"id": 4, "bytes": 65536, "retransmits": 0, "start_time": 0.0, "end_time": 1.0},
                        {"id": 5, "bytes": 65536, "retransmits": 0, "start_time": 0.0, "end_time": 1.0}
                      ]
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                ctrlOut.writeInt(serverResJson.size)
                ctrlOut.write(serverResJson)
                ctrlOut.flush()

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_DISPLAY_RESULTS.toInt())
                ctrlOut.flush()
                runCatching { ctrlIn.readByte() }
                dataSockets.forEach { runCatching { it.close() } }
                ctrlSocket.close()
            } catch (t: Throwable) {
                serverError.set(t)
            } finally {
                runCatching { server.close() }
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val historyStore = NetBenchHistoryStore(
                historyFile = File(tempFolder.root, "iperf_p_${NetBenchHistoryStore.HISTORY_FILE_NAME}"),
                ioDispatcher = Dispatchers.IO,
            )
            val resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO)
            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                targetResolver = resolver,
                iperf3ClientRunner = Iperf3ClientRunner(resolver = resolver, ioDispatcher = Dispatchers.IO),
            )

            assertEquals(1, controller.uiState.value.iperfParallelStreams)
            controller.updateIperfParallelStreams(0)
            assertEquals(1, controller.uiState.value.iperfParallelStreams)
            controller.updateIperfParallelStreams(99)
            assertEquals(16, controller.uiState.value.iperfParallelStreams)
            controller.updateIperfParallelStreams(4)
            assertEquals(4, controller.uiState.value.iperfParallelStreams)

            controller.toggleIcmpPing(false)
            controller.toggleTcpPing(false)
            controller.toggleIpCheck(false)
            controller.toggleIperf3(true)
            controller.dismissIperf3ServerPrompt()
            controller.updateIperfPortText(server.localPort.toString())
            controller.updateIperfDurationSec(1)
            controller.updateTargetInput("127.0.0.1")
            controller.startBenchmark()

            var tries = 0
            while (controller.uiState.value.activePhase != NetBenchPhase.COMPLETED && tries < 100) {
                delay(50)
                tries++
            }

            serverThread.join(1_500)
            assertNull("Fake iperf3 server failed: ${serverError.get()}", serverError.get())

            val finalState = controller.uiState.value
            assertEquals(NetBenchPhase.COMPLETED, finalState.activePhase)
            assertEquals(4, acceptedDataSockets.get())
            assertTrue(receivedParamsJson.get().orEmpty().contains("\"parallel\":4"))
            assertEquals(4, finalState.iperfDownloadProgress?.parallelStreams)
            assertEquals(4, finalState.iperfDownloadProgress?.finalResult?.parallelStreams)
            assertTrue(finalState.generatedMarkdown.contains("-P 4"))
        } finally {
            scope.cancel()
            runCatching { server.close() }
        }
    }
}
