package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.PingExecutionEvent
import com.nodeseek.app.data.netbench.PingProcessExecutor
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.data.netbench.ReverseDnsLookup
import com.nodeseek.app.data.netbench.TracerouteKernelOps
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.netbench.TracerouteRunner
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteSummary
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.netbench.NetBenchSessionController
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Socket
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.net.SocketFactory

@OptIn(ExperimentalCoroutinesApi::class)
class NetBenchTracerouteIntegrationAndPrivacyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `controller runs forward and reverse traceroute and scrubs client public IP across both markdown styles`() = runBlocking {
        val mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/self" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "ip": "114.240.88.123",
                          "country_code": "CN",
                          "region": "Beijing",
                          "connection": {"asn": 4134, "isp": "ChinaNet", "org": "China Telecom"}
                        }
                        """.trimIndent(),
                    )
                    path.startsWith("/ipwhois/59.43.") -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"ip":"59.43.138.1","country_code":"CN","country":"China","city":"Shanghai","connection":{"asn":4809,"org":"CTGNet","isp":"China Telecom CN2"}}""",
                    )
                    path.startsWith("/ipwhois/45.12.34.56") -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"ip":"45.12.34.56","country_code":"US","country":"United States","city":"Los Angeles","connection":{"asn":137872,"org":"DMIT Inc.","isp":"DMIT"}}""",
                    )
                    else -> MockResponse().setResponseCode(200).setBody("""{"success":false}""")
                }
            }
        }
        mockServer.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val base = mockServer.url("/").toString().trimEnd('/')
            val bridge = object : NetBenchNetworkBridge {
                override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                    val channel = object : NetBenchSocketChannel {
                        override val networkId = "cell-1"
                        override val transportLabel = "蜂窝网络"
                        override val isVpn = false
                        override val isDirectPhysical = true
                        override val bypassSystemHttpProxy = true
                        override val supportsFileDescriptorBind = true
                        override val socketFactory: SocketFactory = SocketFactory.getDefault()
                        override fun createSocket(): Socket = Socket()
                        override fun resolveAllByName(hostname: String): List<InetAddress> = InetAddress.getAllByName(hostname).toList()
                        override fun bindDatagramFileDescriptor(fd: FileDescriptor) = Unit
                    }
                    val direct = NetworkDescriptor<NetBenchSocketChannel>(
                        token = channel,
                        networkId = "cell-1",
                        isVpn = false,
                        hasInternet = true,
                        isValidated = true,
                        isActiveDefault = true,
                        transports = setOf(NetBenchTransportKind.CELLULAR),
                        interfaceName = "rmnet0",
                    )
                    val plan = NetBenchNetworkSelector.selectNetworkPlan(listOf(direct))
                    return ResolvedNetworkBinding(plan, channel, channel)
                }
            }

            val intelClient = IpIntelligenceClient(
                httpClient = OkHttpClient(),
                networkBridge = bridge,
                endpoints = IpIntelligenceEndpoints(
                    ipWhoIsTargetTemplate = "$base/ipwhois/{ip}",
                    ipInfoTargetTemplate = "$base/none/{ip}",
                    ipApiTargetTemplate = "$base/none/{ip}",
                    ripeStatWhoisTemplate = "$base/none/{ip}",
                    rdapTemplates = emptyList(),
                    clientSelfUrls = listOf("ipwho.is" to "$base/self"),
                    clientDirectUrls = listOf("ipwho.is" to "$base/self"),
                ),
            )

            var currentTtl = 0
            val peakClockMillis = ZonedDateTime.of(2026, 5, 1, 21, 15, 0, 0, ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli()
            val tracerouteRunner = TracerouteRunner(
                resolver = NetBenchTargetResolver(networkBridge = bridge, ioDispatcher = Dispatchers.IO),
                networkBridge = bridge,
                kernelOps = object : TracerouteKernelOps {
                    override fun isSupported(): Boolean = true
                    override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?) = FileDescriptor()
                    override fun sendEchoWithTtl(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, ttl: Int, identifier: Int, sequence: Int) {
                        currentTtl = ttl
                    }
                    override fun awaitReplyOrError(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, identifier: Int, expectedSequence: Int, timeoutMs: Int): TracerouteProbeReply {
                        return when (currentTtl) {
                            1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.138.1", expectedSequence, 22.0)
                            else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "45.12.34.56", expectedSequence, 148.0)
                        }
                    }
                    override fun closeQuietly(fd: FileDescriptor) = Unit
                },
                pingExecutor = PingProcessExecutor { flow { emit(PingExecutionEvent.Exited(-1)) } },
                ipIntelligenceClient = intelClient,
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = { peakClockMillis },
                ioDispatcher = Dispatchers.IO,
            )

            val vault = SshServerVault(
                vaultFile = File(tempFolder.root, "nb_ssh_vault.json"),
                cipher = InMemoryTestCipher(),
                ioDispatcher = Dispatchers.IO,
            )
            val vps = vault.saveServer(
                SshServerDraft(
                    alias = "LAX-Pro",
                    host = "45.12.34.56",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pw",
                ),
            )
            val sshTransport = object : SshTransport {
                override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                    emit(SshStreamEvent.Connected)
                    emit(
                        SshStreamEvent.OutputChunk(
                            """
                            __ANODE_RTRACE_TOOL__=mtr
                            HOST: vps                         Loss%   Snt   Last   Avg  Best  Wrst StDev
                              1.|-- 59.43.244.1                0.0%     5   1.2   1.3   1.1   1.5   0.1
                              2.|-- 59.43.138.1                0.0%     5 135.0 136.0 134.0 138.0   1.2
                              3.|-- 114.240.88.123             0.0%     5 149.0 150.0 148.0 152.0   1.1
                            """.trimIndent(),
                        ),
                    )
                    emit(SshStreamEvent.Completed(0))
                }
            }
            val sshManager = LiteSshSessionManager(
                vaultRepository = vault,
                executor = LiteSshExecutor(transport = sshTransport),
                externalScope = sshScope,
                enableElapsedTicker = false,
            )

            val historyStore = NetBenchHistoryStore(
                historyFile = File(tempFolder.root, NetBenchHistoryStore.HISTORY_FILE_NAME),
                ioDispatcher = Dispatchers.IO,
            )
            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                networkBridge = bridge,
                ipIntelligenceClient = intelClient,
                sshVaultRepository = vault,
                ioDispatcher = Dispatchers.IO,
                tracerouteRunner = tracerouteRunner,
                sshSessionManager = sshManager,
            )

            // Wait briefly for initial egress pre-warm
            var waitCount = 0
            while (controller.uiState.value.clientEgress?.directNetwork?.rawIp == null && waitCount++ < 40) {
                delay(50)
            }
            assertEquals("114.240.88.123", controller.uiState.value.clientEgress?.directNetwork?.rawIp)

            controller.updateTargetInput("45.12.34.56")
            controller.toggleIcmpPing(false)
            controller.toggleTcpPing(false)
            controller.toggleIpCheck(false)
            controller.toggleTraceroute(true)
            controller.updateTracerouteProbesPerHop(3)
            controller.startBenchmark()

            waitCount = 0
            while (controller.uiState.value.isRunning && waitCount++ < 60) {
                delay(50)
            }
            assertEquals(NetBenchPhase.COMPLETED, controller.uiState.value.activePhase)
            assertNotNull(controller.uiState.value.tracerouteProgress?.summary)

            controller.runReverseTraceroute(vps)
            waitCount = 0
            while (controller.uiState.value.isRunningReverseTrace && waitCount++ < 60) {
                delay(50)
            }
            val rev = controller.uiState.value.reverseTracerouteResult
            assertNotNull(rev)
            assertTrue(BackboneRouteTag.CN2_GIA in rev!!.dominantBackboneTags)

            // Check both FULL_TABLE and REPLY_CARD, with maskTargetIp = true and false
            val report = com.nodeseek.app.domain.netbench.NetBenchReport(
                targetInput = "45.12.34.56",
                clientNetwork = controller.uiState.value.clientNetwork,
                forwardTraceroute = controller.uiState.value.tracerouteProgress?.summary,
                reverseTraceroute = rev,
            )

            for (style in NetBenchMarkdownStyle.entries) {
                for (maskTarget in listOf(true, false)) {
                    val md = NetBenchMarkdownFormatter.format(
                        report = report,
                        options = NetBenchMarkdownOptions(style = style, maskTargetIp = maskTarget),
                    )
                    assertTrue("Expected CN2 GIA in $style/$maskTarget", md.contains("CN2 GIA"))
                    assertFalse("Client raw IP leaked in $style/$maskTarget", md.contains("114.240.88.123"))
                    assertFalse("Client masked IP leaked in $style/$maskTarget", md.contains("114.240.*.*"))
                }
            }

            // Verify @Serializable round-trip for M2b R7 compatibility
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val encoded = json.encodeToString(TracerouteSummary.serializer(), rev)
            val decoded = json.decodeFromString(TracerouteSummary.serializer(), encoded)
            assertEquals(rev, decoded)

            // Verify disabling forward/reverse MTR switches persists preferences and skips markdown sections
            controller.toggleTraceroute(false)
            controller.toggleReverseTraceroute(false)
            delay(80)
            assertFalse(controller.uiState.value.enableTraceroute)
            assertFalse(controller.uiState.value.enableReverseTraceroute)
            assertFalse(controller.uiState.value.generatedMarkdown.contains("去程"))
            assertFalse(controller.uiState.value.generatedMarkdown.contains("回程"))
            assertFalse(historyStore.preferences.value.enableForwardTraceroute)
            assertFalse(historyStore.preferences.value.enableReverseTraceroute)

            // Re-enable reverse MTR only -> startBenchmark executes reverse MTR and skips forward MTR
            controller.toggleReverseTraceroute(true)
            delay(80)
            assertTrue(historyStore.preferences.value.enableReverseTraceroute)
            controller.startBenchmark()
            waitCount = 0
            while (controller.uiState.value.isRunning && waitCount++ < 60) {
                delay(50)
            }
            assertEquals(NetBenchPhase.COMPLETED, controller.uiState.value.activePhase)
            assertEquals(null, controller.uiState.value.tracerouteProgress)
            assertNotNull(controller.uiState.value.reverseTracerouteResult)
            assertFalse(controller.uiState.value.generatedMarkdown.contains("去程逐跳明细"))
            assertTrue(controller.uiState.value.generatedMarkdown.contains("回程逐跳明细"))

            // Reload fresh controller from same historyStore file -> switch states persist across restarts
            val reloadedStore = NetBenchHistoryStore(
                historyFile = File(tempFolder.root, NetBenchHistoryStore.HISTORY_FILE_NAME),
                ioDispatcher = Dispatchers.IO,
            )
            val reloadedController = NetBenchSessionController(
                scope = scope,
                historyStore = reloadedStore,
                networkBridge = bridge,
                ipIntelligenceClient = intelClient,
                sshVaultRepository = vault,
                ioDispatcher = Dispatchers.IO,
                tracerouteRunner = tracerouteRunner,
                sshSessionManager = sshManager,
            )
            assertFalse(reloadedController.uiState.value.enableTraceroute)
            assertTrue(reloadedController.uiState.value.enableReverseTraceroute)
        } finally {
            scope.cancel()
            sshScope.cancel()
            mockServer.shutdown()
        }
    }

    @Test
    fun `sanitizer scrubs non-canonical IPv6 variants and preserves non-matching IPv4 hops without substring corruption`() {
        val v6Report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "2001:db8::99",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV6,
                maskedIp = "240e:390::****",
                rawIp = "240e:390::1",
            ),
            reverseTraceroute = TracerouteSummary(
                direction = TracerouteDirection.REVERSE,
                targetInput = "240e:390::1",
                targetIp = "240e:390::1",
                ipFamily = IpFamily.IPV6,
                toolName = "mtr",
                hops = listOf(
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 1,
                        ip = "240e:390:0:0::1",
                        sent = 3,
                        received = 3,
                        avgMs = 120.0,
                    ),
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 2,
                        ip = "240e:0390::1",
                        hostname = "host-240E:0390:0000:0000:0000:0000:0000:0001.example",
                        sent = 3,
                        received = 3,
                        avgMs = 150.0,
                    ),
                ),
            ),
        )
        val v6Md = NetBenchMarkdownFormatter.format(
            report = v6Report,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, maskTargetIp = false),
        )
        assertFalse("Leaked 240e:390:0:0::1:\n$v6Md", v6Md.contains("240e:390:0:0::1", ignoreCase = true))
        assertFalse("Leaked 240e:0390::1:\n$v6Md", v6Md.contains("240e:0390::1", ignoreCase = true))
        assertFalse("Leaked padded v6:\n$v6Md", v6Md.contains("240e:0390:0000", ignoreCase = true))
        assertTrue(v6Md.contains("[已隐藏本机IP]"))

        val sanitizedV6 = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.sanitize(
            report = v6Report,
            maskTargetIp = false,
        )
        val hop2Host = sanitizedV6.reverseTraceroute!!.hops[1].displayHostname.orEmpty()
        assertFalse("Leaked 240E:0390 in hop2 displayHostname: $hop2Host", hop2Host.contains("240E:0390", ignoreCase = true))
        assertTrue("Expected [已隐藏本机IP] in hop2 displayHostname: $hop2Host", hop2Host.contains("[已隐藏本机IP]"))
        assertEquals("host-[已隐藏本机IP].example", hop2Host)

        assertEquals(
            "Connected from [已隐藏本机IP].com",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "Connected from 240e:390::1.com",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "[已隐藏本机IP].dead",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "240e:390::1.dead",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "[已隐藏本机IP].cn and [已隐藏本机IP].arpa",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "240e:390::1.cn and 240e:390::1.arpa",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "From [已隐藏本机IP]: Time exceeded",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "From 240e:390:0:0::1: Time exceeded",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "From [已隐藏本机IP]: Time exceeded",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "From 240E:0390:0000::0001: Time exceeded",
                report = v6Report,
                maskTargetIp = false,
            ),
        )

        val maskedCom = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("240e:390::1.com")
        assertFalse("Expected 240e:390::1 masked in $maskedCom", maskedCom.contains("240e:390::1", ignoreCase = true))
        assertEquals("240e:390::****.com", maskedCom)

        val maskedDead = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("240e:390::1.dead")
        assertEquals("240e:390::****.dead", maskedDead)

        val maskedTrailingColon = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(
            "From 2606:4700:4700::1111: Time exceeded",
        )
        assertFalse("Expected 1111 masked in $maskedTrailingColon", maskedTrailingColon.contains("1111"))
        assertEquals("From 2606:4700:4700::****: Time exceeded", maskedTrailingColon)

        val maskedDoubleColon = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(
            "prefix 240e:390:: end",
        )
        assertEquals("prefix 240e:390::**** end", maskedDoubleColon)

        val scrubbedPublicWithTargetMask = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
            raw = "From 2606:4700:4700::1111: Time exceeded",
            report = v6Report,
            maskTargetIp = true,
        )
        assertFalse("Expected 1111 masked in $scrubbedPublicWithTargetMask", scrubbedPublicWithTargetMask.contains("1111"))

        val mappedV4SuffixMasked = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(
            "::ffff:192.168.1.1.dead",
        )
        assertEquals("::ffff:192.168.1.*.dead", mappedV4SuffixMasked)

        val v4Report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                maskedIp = "1.2.*.*",
                rawIp = "1.2.3.4",
            ),
            reverseTraceroute = TracerouteSummary(
                direction = TracerouteDirection.REVERSE,
                targetInput = "1.2.3.4",
                targetIp = "1.2.3.4",
                ipFamily = IpFamily.IPV4,
                toolName = "mtr",
                hops = listOf(
                    com.nodeseek.app.domain.netbench.TracerouteHop(hopNumber = 1, ip = "11.2.3.4", sent = 3, received = 3, avgMs = 10.0),
                    com.nodeseek.app.domain.netbench.TracerouteHop(hopNumber = 2, ip = "1.2.3.45", sent = 3, received = 3, avgMs = 20.0),
                    com.nodeseek.app.domain.netbench.TracerouteHop(hopNumber = 3, ip = "1.2.3.4", sent = 3, received = 3, avgMs = 30.0, isTargetReached = true),
                ),
            ),
        )
        val v4Md = NetBenchMarkdownFormatter.format(
            report = v4Report,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, maskTargetIp = false),
        )
        assertTrue("Expected 11.2.3.4 intact:\n$v4Md", v4Md.contains("`11.2.3.4`"))
        assertTrue("Expected 1.2.3.45 intact:\n$v4Md", v4Md.contains("`1.2.3.45`"))
        assertFalse("Expected hop 3 client IP scrubbed:\n$v4Md", v4Md.contains("`1.2.3.4`"))
        assertFalse("Expected no substring corruption:\n$v4Md", v4Md.contains("1[已隐藏本机IP]") || v4Md.contains("[已隐藏本机IP]5"))
    }

    @Test
    fun `sanitizer preserves IPv4-mapped IPv6 prefixes with mask or port and scrubs label-colon client IPv6`() {
        val v6Report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "2001:db8::99",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV6,
                maskedIp = "240e:390::****",
                rawIp = "240e:390::1",
            ),
        )

        // Bug 1: IPv4-mapped IPv6 must not get split by dot-backtracking into `::****.<rest>`
        assertEquals(
            "From ::ffff:203.0.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "From ::ffff:203.0.113.42",
                report = v6Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "Connected to ::ffff:45.12.*.* ok",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "Connected to ::ffff:45.12.34.56 ok",
                report = v6Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "From ::ffff:192.168.1.*:8080",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "From ::ffff:192.168.1.1:8080",
                report = v6Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "::ffff:1.2.3.*:8080",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(
                "::ffff:1.2.3.4:8080",
            ),
        )
        assertEquals(
            "::ffff:203.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(
                "::ffff:203.*.*.*",
            ),
        )
        assertEquals(
            "240e:390::****.dead:443",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(
                "240e:390::1.dead:443",
            ),
        )

        // Bug 2: Single non-hex label colon before client IPv6 / masked token must be scrubbed
        assertEquals(
            "Client IPv6:[已隐藏本机IP] connected",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "Client IPv6:240e:390::1 connected",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "ip:[已隐藏本机IP]",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "ip:240e:390::1",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "host:[已隐藏本机IP]",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "host:240e:390::1",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "Client IPv6:[已隐藏本机IP] connected",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "Client IPv6:240e:390::**** connected",
                report = v6Report,
                maskTargetIp = false,
            ),
        )

        // Ensure valid colon prefixes (hex hextet or '::') are NOT over-scrubbed
        assertEquals(
            "2001:db8:240e:390::1",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "2001:db8:240e:390::1",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "dead:240e:390::1",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "dead:240e:390::1",
                report = v6Report,
                maskTargetIp = false,
            ),
        )
        val doubleColonClientReport = v6Report.copy(
            clientNetwork = v6Report.clientNetwork?.copy(rawIp = "240e:390::", maskedIp = "240e:390::****"),
        )
        assertEquals(
            "240e:390:::1",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "240e:390:::1",
                report = doubleColonClientReport,
                maskTargetIp = false,
            ),
        )

        // Client IPv4-mapped IPv6 with :port and label colon
        val v4ClientReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                maskedIp = "1.2.*.*",
                rawIp = "1.2.3.4",
            ),
        )
        assertEquals(
            "From [已隐藏本机IP]:8080",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "From ::ffff:1.2.3.4:8080",
                report = v4ClientReport,
                maskTargetIp = false,
            ),
        )
        assertEquals(
            "addr:[已隐藏本机IP]",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "addr:::ffff:1.2.3.4",
                report = v4ClientReport,
                maskTargetIp = false,
            ),
        )
    }

    @Test
    fun `markdown formatter and sanitizer preserve masked IPv4-embedded IPv6 hops and targets idempotently`() {
        val v6Report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "2001:db8::99",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV6,
                maskedIp = "240e:390::****",
                rawIp = "240e:390::1",
            ),
        )

        val hopReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "::ffff:203.0.113.42",
            forwardTraceroute = TracerouteSummary(
                direction = TracerouteDirection.FORWARD,
                targetInput = "::ffff:203.0.113.42",
                targetIp = "::ffff:203.0.113.42",
                ipFamily = IpFamily.IPV6,
                toolName = "icmp-ttl",
                hops = listOf(
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 1,
                        ip = "::ffff:192.168.1.1",
                        sent = 3,
                        received = 3,
                        avgMs = 1.0,
                    ),
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 2,
                        ip = "::ffff:203.0.113.42",
                        sent = 3,
                        received = 3,
                        avgMs = 5.0,
                        isTargetReached = true,
                    ),
                ),
            ),
        )
        for (style in NetBenchMarkdownStyle.entries) {
            val md = NetBenchMarkdownFormatter.format(
                report = hopReport,
                options = NetBenchMarkdownOptions(style = style, maskTargetIp = true),
            )
            if (style == NetBenchMarkdownStyle.FULL_TABLE) {
                assertTrue("Expected ::ffff:192.168.1.* in $style:\n$md", md.contains("`::ffff:192.168.1.*`"))
                assertTrue("Expected ::ffff:203.0.113.* in $style:\n$md", md.contains("`::ffff:203.0.113.*`"))
            } else {
                assertTrue("Expected ::ffff:203.0.113.* in $style:\n$md", md.contains("::ffff:203.0.113.*"))
            }
            assertFalse("Corrupted ffff:192:*:* in $style:\n$md", md.contains("ffff:192:*:*"))
            assertFalse("Corrupted ffff:203:*:* in $style:\n$md", md.contains("ffff:203:*:*"))
        }

        val targetMaskedReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "::ffff:203.0.113.42",
        )
        assertEquals(
            "From ::ffff:203.0.113.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "From ::ffff:203.0.113.42",
                report = targetMaskedReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "::ffff:203.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "::ffff:203.*.*.*",
                report = v6Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "::ffff:192.168.1.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "::ffff:192.168.1.*",
                report = v6Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "::ffff:192.168.1.*.dead",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "::ffff:192.168.1.1.dead:443",
                report = v6Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "::ffff:192.168.1.*.dead:443",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "::ffff:192.168.1.1.dead:443",
                report = v6Report.copy(tcpPort = 8080),
                maskTargetIp = true,
            ),
        )

        assertEquals(
            "From ::ffff:203.0.*.*",
            com.nodeseek.app.domain.ssh.IpPrivacyMasker.mask(
                com.nodeseek.app.domain.ssh.IpPrivacyMasker.mask("From ::ffff:203.0.113.42"),
            ),
        )
        assertEquals(
            "64:ff9b::192.168.1.*",
            com.nodeseek.app.domain.ssh.IpPrivacyMasker.mask("64:ff9b::192.168.1.*"),
        )
        assertEquals(
            "2001:db8::ffff:192.168.1.*",
            com.nodeseek.app.domain.ssh.IpPrivacyMasker.mask("2001:db8::ffff:192.168.1.*"),
        )
        assertEquals(
            "240e:390::****",
            com.nodeseek.app.domain.ssh.IpPrivacyMasker.mask("240e:390::****"),
        )
    }

    @Test
    fun `sanitizer and masker handle markdown asterisk bold italic wrappers without client IP leaks or prefix corruption`() {
        val v6ClientReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "2001:db8::99",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV6,
                maskedIp = "240e:390::****",
                rawIp = "240e:390::1",
            ),
        )
        val doubleColonV6ClientReport = v6ClientReport.copy(
            clientNetwork = v6ClientReport.clientNetwork?.copy(
                rawIp = "240e:390::",
                maskedIp = "240e:390::****",
            ),
        )
        val v4ClientReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                maskedIp = "203.0.*.*",
                rawIp = "203.0.113.42",
            ),
        )

        for (maskTarget in listOf(false, true)) {
            assertEquals(
                "**[已隐藏本机IP]**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**240e:390::1**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*[已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*240e:390::1*",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::1*",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**[已隐藏本机IP]**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**240e:390:0:0::1**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**[已隐藏本机IP]**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**203.0.113.42**",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*[已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*203.0.113.42*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**[已隐藏本机IP]**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**::ffff:203.0.113.42**",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::****",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "Client IPv6:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "Client IPv6:240e:390::****",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
        }

        // Non-client IPv6 wrapped with '*' must mask cleanly without skipping the first hextet
        assertEquals(
            "**2001:4860:*:***",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**2001:4860::1**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*2001:4860:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*2001:4860::1*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*2001:4860:*:*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*2001:4860::1",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "2001:4860:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "2001:4860::1*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )

        // Already-masked non-client tokens and partial-masked tokens remain idempotent
        for (maskedSample in listOf(
            "240e:390::****",
            "240e:390:*:*",
            "240e:390:*:dead::1",
            "240e:390:*::dead",
            "240e:390::1:****",
            "192.168.*.1",
            "::ffff:203.*.*.*",
        )) {
            assertEquals(
                maskedSample,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = maskedSample,
                    report = v4ClientReport,
                    maskTargetIp = true,
                ),
            )
            assertEquals(
                maskedSample,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(maskedSample),
            )
        }
        assertEquals(
            "203.0.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "203.0.*.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "203.0.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("203.0.*.*"),
        )
        assertEquals(
            "[已隐藏本机IP]",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "203.0.*.*",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )

        // Markdown-emphasized label colons, `::`-ending IPv6 in Markdown, `:` + Markdown emphasis,
        // and trailing period inside closing `*` wrapper
        for (maskTarget in listOf(false, true)) {
            assertEquals(
                "**IPv6**:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**IPv6**:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**Client IPv6**:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**Client IPv6**:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**IP**:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**IP**:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*IP*:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*IP*:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "***IPv6***:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "***IPv6***:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**[已隐藏本机IP]**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**240e:390::**",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*[已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*240e:390::*",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP]:**bold**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::1:**bold**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP]:*italic*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::1:*italic*",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP]:*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::1:*",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**[已隐藏本机IP].**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**240e:390::1.**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
        }

        // Non-client IPv6 with Markdown-wrapped labels, `::` inside Markdown, `:` + Markdown, and `.**`
        assertEquals(
            "**IPv6**:2001:4860:*:*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**IPv6**:2001:4860::1",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**2001:4860:*:***",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**2001:4860::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "2001:4860:*:*:**bold**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "2001:4860::1:**bold**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**2001:4860:*:*.**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**2001:4860::1.**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**2001:4860::******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**2001:4860::**"),
            ),
        )
        assertEquals(
            "**240e:390::******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**240e:390::******",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )

        // Iteration 8 regressions: punctuation-prefixed bold after IPv6, multi-word bold,
        // sentence-ending & .**-wrapped IPv4/IPv4-mapped IPv6, and non-asterisk/hex label colons
        for (maskTarget in listOf(false, true)) {
            assertEquals(
                "[已隐藏本机IP]:**[CN2 GIA]**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::1:**[CN2 GIA]**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP]:**(中国电信)**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::1:**(中国电信)**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP]:**/api**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "240e:390::1:**/api**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**Client [已隐藏本机IP]**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**Client 240e:390::**",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**Client [已隐藏本机IP]:**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**Client 240e:390::1:**",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP].",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "203.0.113.42.",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**[已隐藏本机IP].**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**203.0.113.42.**",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*[已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*203.0.113.42.*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**[已隐藏本机IP].**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**::ffff:203.0.113.42.**",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*[已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*::ffff:203.0.113.42.*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "`IPv6`:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "`IPv6`:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[IPv6]:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "[IPv6]:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "(IPv6):[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "(IPv6):240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "\"IPv6\":[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "\"IPv6\":240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**dead**:[已隐藏本机IP]",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**dead**:240e:390::1",
                    report = v6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
        }

        // Non-client counterparts & idempotency / false-positive guards
        assertEquals(
            "2001:4860:*:*:**[CN2 GIA]**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "2001:4860::1:**[CN2 GIA]**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Client 2001:4860:*:***",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Client 2001:4860::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Client 2001:4860:*:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Client 2001:4860::1:**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "198.51.*.*.",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "198.51.100.77.",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**198.51.*.*.**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**198.51.100.77.**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*198.51.100.77.*",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**::ffff:198.51.*.*.**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**::ffff:198.51.100.77.**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*::ffff:198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*::ffff:198.51.100.77.*",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "`IPv6`:2001:4860:*:*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "`IPv6`:2001:4860::1",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**dead**:2001:4860:*:*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**dead**:2001:4860::1",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "[已隐藏本机IP].",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "203.0.*.*.",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**[已隐藏本机IP].**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**203.0.*.*.**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "203.0.*.*.",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "203.0.*.*.",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "203.0.*.*.",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("203.0.*.*."),
        )
        assertEquals(
            "**Client [已隐藏本机IP]",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Client 240e:390::****",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        for (unchanged in listOf(
            "1.2.3.4.5",
            "1.2.3.4.example",
            "1.2.3.4.*",
            "1.2.3.4.*.5",
            "1.2.3.* 5.6.7.8.*",
            "198.51.*.*",
            "::ffff:192.168.1.*",
            "240e:390::****",
            "240e:390::*",
            "1.2.3.* 240e:390::*",
            "**Client 240e:390::****",
            "**Client 240e:390:*:***",
            "**Client 240e:390:*:*:**",
            "**bold** 240e:390::**",
            "240e:390::1:****",
            "::ffff:203.*.*.*",
            "64:ff9b::192.168.1.*",
        )) {
            assertEquals(
                unchanged,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = unchanged,
                    report = v4ClientReport,
                    maskTargetIp = true,
                ),
            )
            assertEquals(
                unchanged,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(unchanged),
            )
        }

        // Iteration 9 regressions:
        // 1) Single-star Markdown italic with period around/after IPv4 & IPv4-mapped IPv6
        for (maskTarget in listOf(false, true)) {
            assertEquals(
                "*Client [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Client 203.0.113.42.*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*Client [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Client ::ffff:203.0.113.42.*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP].*italic*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "203.0.113.42.*italic*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "[已隐藏本机IP].*italic*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "::ffff:203.0.113.42.*italic*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
        }
        // Non-client Bug 1 vectors under maskTargetIp = true
        assertEquals(
            "*Client 203.0.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Client 203.0.113.42.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Client ::ffff:203.0.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Client ::ffff:203.0.113.42.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "203.0.*.*.*italic*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "203.0.113.42.*italic*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "::ffff:203.0.*.*.*italic*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "::ffff:203.0.113.42.*italic*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Client 203.0.113.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Client 203.0.113.42.*"),
        )
        assertEquals(
            "*Client ::ffff:203.0.113.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Client ::ffff:203.0.113.42.*"),
        )
        assertEquals(
            "203.0.113.*.*italic*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("203.0.113.42.*italic*"),
        )
        assertEquals(
            "::ffff:203.0.113.*.*italic*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("::ffff:203.0.113.42.*italic*"),
        )

        // 2) Same-line bolded IP followed by an already-masked `::**` token
        assertEquals(
            "**Hop1 2001:4860:*:*** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Hop1 2001:4860::** 2001:4860:1::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**IP 203.0.*.*** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**IP 203.0.113.42** 2001:4860:1::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**IP [已隐藏本机IP]** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**IP 203.0.113.42** 2001:4860:1::**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Hop1 2001:4860::****** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**Hop1 2001:4860::** 2001:4860:1::**"),
        )
        assertEquals(
            "**IP 203.0.113.*** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**IP 203.0.113.42** 2001:4860:1::**"),
        )

        // Idempotency across scrubPublicText and maskAnyRemainingIpLiterals for Iteration 9 vectors
        for (sample in listOf(
            "*Client 203.0.113.42.*",
            "*Client ::ffff:203.0.113.42.*",
            "203.0.113.42.*italic*",
            "::ffff:203.0.113.42.*italic*",
            "*Client 203.0.113.42.* 5.6.7.8.*",
            "203.0.113.42.*italic* 1.2.3.4.*",
            "**Hop1 2001:4860::** 2001:4860:1::**",
            "**IP 203.0.113.42** 2001:4860:1::**",
            "**Hop1 2001:4860::** **Hop2 2001:4860:1::**",
        )) {
            val sanOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = sample,
                report = v6ClientReport,
                maskTargetIp = true,
            )
            val sanTwice = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = sanOnce,
                report = v6ClientReport,
                maskTargetIp = true,
            )
            assertEquals("Expected scrubPublicText idempotency for: $sample", sanOnce, sanTwice)

            val marOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(sample)
            val marTwice = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(marOnce)
            assertEquals("Expected maskAnyRemainingIpLiterals idempotency for: $sample", marOnce, marTwice)
        }

        // Iteration 10 regressions:
        val v4Client198Report = v4ClientReport.copy(
            clientNetwork = v4ClientReport.clientNetwork?.copy(
                rawIp = "198.51.100.2",
                maskedIp = "198.51.*.*",
            ),
        )

        // 1) Nested bold+italic `***` stack handling & unbalanced `***a**` split
        for (maskTarget in listOf(false, true)) {
            assertEquals(
                "**a *b [已隐藏本机IP]***",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**a *b 240e:390::***",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**a *b [已隐藏本机IP].*** 2001:4860:1::**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**a *b 203.0.113.42.*** 2001:4860:1::**",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**bold *italic [已隐藏本机IP]*** 2001:4860:1::**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**bold *italic 203.0.113.42*** 2001:4860:1::**",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "***a** [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "***a** 203.0.113.42.*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "***a** [已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "***a** 240e:390::*",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )

            // 2) Already-masked /16 IPv4 & /32 IPv6 terminal '*' must not pop `*...*`
            assertEquals(
                "*Hop1 203.0.*.* Hop2 [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Hop1 203.0.*.* Hop2 198.51.100.2.*",
                    report = v4Client198Report,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*Hop1 2001:4860:*:* Hop2 [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Hop1 2001:4860:*:* Hop2 198.51.100.2.*",
                    report = v4Client198Report,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*Hop1 203.0.*.* Hop2 [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Hop1 203.0.*.* Hop2 ::ffff:198.51.100.2.*",
                    report = v4Client198Report,
                    maskTargetIp = maskTarget,
                ),
            )

            // 3) Single-star italic wrapping `::`-ending IPv6
            assertEquals(
                "*Client [已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Client 240e:390::*",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )

            // 4) Closing `**` followed by `.` or `:`
            assertEquals(
                "**IP [已隐藏本机IP]**. 2001:4860:1::**",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**IP 203.0.113.42**. 2001:4860:1::**",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
        }

        // Non-client Iteration 10 vectors under maskTargetIp = true and maskAnyRemainingIpLiterals
        assertEquals(
            "**a *b 2001:4860:*:****",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a *b 2001:4860::***",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*a **b 2001:4860:*:****",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*a **b 2001:4860::***",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**a *b 203.0.*.*.*** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a *b 203.0.113.42.*** 2001:4860:1::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**bold *italic 203.0.*.**** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**bold *italic 203.0.113.42*** 2001:4860:1::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "***a** 203.0.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "***a** 203.0.113.42.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Hop1 203.0.*.* Hop2 198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Hop1 203.0.*.* Hop2 198.51.100.2.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Hop1 2001:4860:*:* Hop2 198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Hop1 2001:4860:*:* Hop2 198.51.100.2.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Hop1 203.0.*.* Hop2 ::ffff:198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Hop1 203.0.*.* Hop2 ::ffff:198.51.100.2.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Client 240e:390:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Client 240e:390::*",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Hop1 2001:4860:*:** 2001:4860:1::*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Hop1 2001:4860::* 2001:4860:1::*",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**IP 203.0.*.***. 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**IP 203.0.113.42**. 2001:4860:1::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Hop1 2001:4860:*:***: 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Hop1 2001:4860::**: 2001:4860:1::**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )

        assertEquals(
            "**a *b 2001:4860::*******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a *b 2001:4860::***"),
        )
        assertEquals(
            "*a **b 2001:4860::*******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*a **b 2001:4860::***"),
        )
        assertEquals(
            "**a *b 203.0.113.*.*** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a *b 203.0.113.42.*** 2001:4860:1::**"),
        )
        assertEquals(
            "**bold *italic 203.0.113.**** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**bold *italic 203.0.113.42*** 2001:4860:1::**"),
        )
        assertEquals(
            "***a** 203.0.113.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("***a** 203.0.113.42.*"),
        )
        assertEquals(
            "*Hop1 203.0.*.* Hop2 198.51.100.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Hop1 203.0.*.* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 2001:4860:*:* Hop2 198.51.100.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Hop1 2001:4860:*:* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 203.0.*.* Hop2 ::ffff:198.51.100.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Hop1 203.0.*.* Hop2 ::ffff:198.51.100.2.*"),
        )
        assertEquals(
            "*Client 240e:390::*****",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Client 240e:390::*"),
        )
        assertEquals(
            "*Hop1 2001:4860::***** 2001:4860:1::*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Hop1 2001:4860::* 2001:4860:1::*"),
        )
        assertEquals(
            "**IP 203.0.113.***. 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**IP 203.0.113.42**. 2001:4860:1::**"),
        )
        assertEquals(
            "**Hop1 2001:4860::******: 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**Hop1 2001:4860::**: 2001:4860:1::**"),
        )

        // Preserved tokens & idempotency across Iteration 10 vectors
        for (unchanged in listOf(
            "240e:390::*",
            "1.2.3.* 240e:390::*",
            "*italic* 240e:390::*",
            "**bold** 240e:390::*",
        )) {
            assertEquals(
                unchanged,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = unchanged,
                    report = v4ClientReport,
                    maskTargetIp = true,
                ),
            )
            assertEquals(
                unchanged,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(unchanged),
            )
        }
        for (sample in listOf(
            "**a *b 2001:4860::***",
            "*a **b 2001:4860::***",
            "**a *b 240e:390::***",
            "**a *b 203.0.113.42.*** 2001:4860:1::**",
            "**bold *italic 203.0.113.42*** 2001:4860:1::**",
            "**a *b 1.2.3.*** 2001:4860:1::**",
            "***a** 203.0.113.42.*",
            "***a** 240e:390::*",
            "*Hop1 203.0.*.* Hop2 198.51.100.2.*",
            "*Hop1 2001:4860:*:* Hop2 198.51.100.2.*",
            "*Hop1 203.0.*.* Hop2 ::ffff:198.51.100.2.*",
            "*Client 240e:390::*",
            "*Hop1 2001:4860::* 2001:4860:1::*",
            "**IP 203.0.113.42**. 2001:4860:1::**",
            "**Hop1 2001:4860::**: 2001:4860:1::**",
        )) {
            val sanOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = sample,
                report = v4ClientReport,
                maskTargetIp = true,
            )
            val sanTwice = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = sanOnce,
                report = v4ClientReport,
                maskTargetIp = true,
            )
            assertEquals("Expected scrubPublicText Iteration 10 idempotency for: $sample", sanOnce, sanTwice)

            val marOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(sample)
            val marTwice = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(marOnce)
            assertEquals("Expected maskAnyRemainingIpLiterals Iteration 10 idempotency for: $sample", marOnce, marTwice)
        }

        // Iteration 11 regressions:
        val target203Report = v4Client198Report.copy(
            targetInput = "203.0.113.42",
        )
        val targetMappedReport = v4Client198Report.copy(
            targetInput = "::ffff:203.0.113.42",
        )

        // Bug 1: scrubWithContext Step 1 target-IP masking (`d.d.d.*` / `::ffff:d.d.d.*`) + already-masked `/24`/`/120`
        assertEquals(
            "*Target 203.0.113.* Hop2 [已隐藏本机IP].*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Target 203.0.113.42 Hop2 198.51.100.2.*",
                report = target203Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Target ::ffff:203.0.113.* Hop2 [已隐藏本机IP].*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Target ::ffff:203.0.113.42 Hop2 198.51.100.2.*",
                report = targetMappedReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Target 203.0.113.* Hop2 240e:390:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Target 203.0.113.42 Hop2 240e:390::*",
                report = target203Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Target 203.0.113.* Hop2 2001:4860:*:***",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Target 203.0.113.42 Hop2 2001:4860::**",
                report = target203Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Target 203.0.113.** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Target 203.0.113.42* 2001:4860:1::**",
                report = target203Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Target 203.0.113.*** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Target 203.0.113.42** 2001:4860:1::**",
                report = target203Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Target 203.0.113.** 2001:4860:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Target 203.0.113.42* 2001:4860:1::*",
                report = target203Report,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "***Target 203.0.113.*** 240e:390:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "***Target 203.0.113.42** 240e:390::*",
                report = target203Report,
                maskTargetIp = true,
            ),
        )

        for (maskTarget in listOf(false, true)) {
            assertEquals(
                "*Client 1.2.3.* [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Client 1.2.3.* 198.51.100.2.*",
                    report = v4Client198Report,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*Hop1 203.0.113.* Hop2 [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Hop1 203.0.113.* Hop2 198.51.100.2.*",
                    report = v4Client198Report,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "*Hop1 ::ffff:203.0.113.* Hop2 [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "*Hop1 ::ffff:203.0.113.* Hop2 198.51.100.2.*",
                    report = v4Client198Report,
                    maskTargetIp = maskTarget,
                ),
            )
            // Bug 2 with client raw IP
            assertEquals(
                "**a [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**a 203.0.113.42.*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "**a [已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "**a 240e:390::*",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "***a [已隐藏本机IP].*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "***a 203.0.113.42.*",
                    report = v4ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
            assertEquals(
                "***a [已隐藏本机IP]*",
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = "***a 240e:390::*",
                    report = doubleColonV6ClientReport,
                    maskTargetIp = maskTarget,
                ),
            )
        }

        // Non-client Bug 1 & Bug 2 under maskTargetIp = true
        assertEquals(
            "*Client 1.2.3.* 198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Client 1.2.3.* 198.51.100.2.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Hop1 203.0.113.* Hop2 198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Hop1 203.0.113.* Hop2 198.51.100.2.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "*Hop1 ::ffff:203.0.113.* Hop2 198.51.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "*Hop1 ::ffff:203.0.113.* Hop2 198.51.100.2.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**Hop1 203.0.113.* Hop2 2001:4860:*:***",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**Hop1 203.0.113.* Hop2 2001:4860::**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**a 203.0.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a 203.0.113.42.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**a 240e:390:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a 240e:390::*",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "***a 203.0.*.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "***a 203.0.113.42.*",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "***a 240e:390:*:**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "***a 240e:390::*",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "***a 2001:4860:*:***",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "***a 2001:4860::**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**a *b *c 2001:4860:*:****",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a *b *c 2001:4860::***",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**a **b 2001:4860:*:****",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a **b 2001:4860::***",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**a 203.0.*.*.* 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a 203.0.113.42.* 2001:4860:1::**",
                report = v6ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "**a 240e:390:*:** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "**a 240e:390::* 2001:4860:1::**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )
        assertEquals(
            "***a 2001:4860:*:*** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "***a 2001:4860::** 2001:4860:1::**",
                report = v4ClientReport,
                maskTargetIp = true,
            ),
        )

        // maskAnyRemainingIpLiterals Bug 1 & Bug 2
        assertEquals(
            "*Client 1.2.3.* 198.51.100.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Client 1.2.3.* 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 203.0.113.* Hop2 198.51.100.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Hop1 203.0.113.* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 ::ffff:203.0.113.* Hop2 198.51.100.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("*Hop1 ::ffff:203.0.113.* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "**Hop1 203.0.113.* Hop2 2001:4860::******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**Hop1 203.0.113.* Hop2 2001:4860::**"),
        )
        assertEquals(
            "**a 203.0.113.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a 203.0.113.42.*"),
        )
        assertEquals(
            "**a 240e:390::*****",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a 240e:390::*"),
        )
        assertEquals(
            "***a 203.0.113.*.*",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("***a 203.0.113.42.*"),
        )
        assertEquals(
            "***a 240e:390::*****",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("***a 240e:390::*"),
        )
        assertEquals(
            "***a 2001:4860::******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("***a 2001:4860::**"),
        )
        assertEquals(
            "**a *b *c 2001:4860::*******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a *b *c 2001:4860::***"),
        )
        assertEquals(
            "**a **b 2001:4860::*******",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a **b 2001:4860::***"),
        )
        assertEquals(
            "**a 203.0.113.*.* 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a 203.0.113.42.* 2001:4860:1::**"),
        )
        assertEquals(
            "**a 240e:390::***** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("**a 240e:390::* 2001:4860:1::**"),
        )
        assertEquals(
            "***a 2001:4860::****** 2001:4860:1::**",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals("***a 2001:4860::** 2001:4860:1::**"),
        )

        // All 18 preserved tokens & idempotency across Iteration 11 vectors
        for (unchanged in listOf(
            "1.2.3.4.*",
            "1.2.3.* 5.6.7.8.*",
            "1.2.3.4.*.5",
            "1.2.3.4.5",
            "1.2.3.4.example",
            "203.0.*.*.",
            "::ffff:192.168.1.*",
            "240e:390::****",
            "240e:390::*",
            "1.2.3.* 240e:390::*",
            "**bold** 240e:390::**",
            "**Client 240e:390::****",
            "64:ff9b::192.168.1.*",
            "240e:390:*:*",
            "240e:390:*:dead::1",
            "240e:390:*::dead",
            "240e:390::1:****",
            "::ffff:203.*.*.*",
        )) {
            assertEquals(
                "scrubPublicText preserved token altered: $unchanged",
                unchanged,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                    raw = unchanged,
                    report = v4Client198Report,
                    maskTargetIp = true,
                ),
            )
            assertEquals(
                "maskAnyRemainingIpLiterals preserved token altered: $unchanged",
                unchanged,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(unchanged),
            )
        }

        for (sample in listOf(
            "*Target 203.0.113.42 Hop2 198.51.100.2.*",
            "*Target ::ffff:203.0.113.42 Hop2 198.51.100.2.*",
            "*Target 203.0.113.42 Hop2 240e:390::*",
            "**Target 203.0.113.42 Hop2 2001:4860::**",
            "*Target 203.0.113.42* 2001:4860:1::**",
            "**Target 203.0.113.42** 2001:4860:1::**",
            "**Target 203.0.113.42* 2001:4860:1::*",
            "***Target 203.0.113.42** 240e:390::*",
            "*Client 1.2.3.* 198.51.100.2.*",
            "*Hop1 203.0.113.* Hop2 198.51.100.2.*",
            "*Hop1 ::ffff:203.0.113.* Hop2 198.51.100.2.*",
            "**Hop1 203.0.113.* Hop2 2001:4860::**",
            "**a 203.0.113.42.*",
            "**a 240e:390::*",
            "***a 203.0.113.42.*",
            "***a 240e:390::*",
            "***a 2001:4860::**",
            "**a *b *c 2001:4860::***",
            "**a **b 2001:4860::***",
            "*a **2001:4860::***",
            "**a 203.0.113.42.* 2001:4860:1::**",
            "**a 240e:390::* 2001:4860:1::**",
            "***a 2001:4860::** 2001:4860:1::**",
            "**a 1.2.3.** 2001:4860:1::**",
            "***a 1.2.3.*** 2001:4860:1::**",
        )) {
            val targetOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = sample,
                report = target203Report,
                maskTargetIp = true,
            )
            val targetTwice = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = targetOnce,
                report = target203Report,
                maskTargetIp = true,
            )
            assertEquals("Expected target203Report idempotency for: $sample", targetOnce, targetTwice)

            val mappedOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = sample,
                report = targetMappedReport,
                maskTargetIp = true,
            )
            val mappedTwice = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = mappedOnce,
                report = targetMappedReport,
                maskTargetIp = true,
            )
            assertEquals("Expected targetMappedReport idempotency for: $sample", mappedOnce, mappedTwice)

            val marOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(sample)
            val marTwice = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(marOnce)
            assertEquals("Expected maskAnyRemainingIpLiterals Iteration 11 idempotency for: $sample", marOnce, marTwice)
        }
    }

    @Test
    fun `sanitizer passes G1 to G7 structural matrix across scrubPublicText createScrubber and maskAnyRemainingIpLiterals`() {
        val v4Client198Report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                maskedIp = "198.51.*.*",
                rawIp = "198.51.100.2",
            ),
        )
        val neutralReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
        )
        val target2001ShortReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "2001::",
        )
        val target2001GoogleReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "2001:4860::1",
            clientNetwork = v4Client198Report.clientNetwork,
        )
        val target203PtrReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "203.0.113.42",
            clientNetwork = v4Client198Report.clientNetwork,
        )

        val scrubberTrue = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(
            report = v4Client198Report,
            maskTargetIp = true,
        )
        val scrubberFalse = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(
            report = v4Client198Report,
            maskTargetIp = false,
        )

        // G1 + G3 + G6 + G7 across scrubPublicText(false), scrubPublicText(true), createScrubber, maskAnyRemainingIpLiterals
        val client198Cases = listOf(
            "**Hop1 240e:390::1:***** Hop2 198.51.100.2.*" to Triple(
                "**Hop1 240e:390::1:***** Hop2 [已隐藏本机IP].*",
                "**Hop1 240e:390::1:***** Hop2 [已隐藏本机IP].*",
                "**Hop1 240e:390::1:***** Hop2 198.51.100.*.*",
            ),
            "***Hop1 240e:390::1:****** Hop2 198.51.100.2.*" to Triple(
                "***Hop1 240e:390::1:****** Hop2 [已隐藏本机IP].*",
                "***Hop1 240e:390::1:****** Hop2 [已隐藏本机IP].*",
                "***Hop1 240e:390::1:****** Hop2 198.51.100.*.*",
            ),
            "*a **240e:390::1:***** Hop2 198.51.100.2.*" to Triple(
                "*a **240e:390::1:***** Hop2 [已隐藏本机IP].*",
                "*a **240e:390::1:***** Hop2 [已隐藏本机IP].*",
                "*a **240e:390::1:***** Hop2 198.51.100.*.*",
            ),
            "**Hop1 240e:390::1:**** Hop2 198.51.100.2.*" to Triple(
                "**Hop1 240e:390::1:**** Hop2 [已隐藏本机IP].*",
                "**Hop1 240e:390::1:**** Hop2 [已隐藏本机IP].*",
                "**Hop1 240e:390::1:**** Hop2 198.51.100.*.*",
            ),
            "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 198.51.100.2.*" to Triple(
                "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 [已隐藏本机IP].*",
                "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 [已隐藏本机IP].*",
                "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 198.51.100.*.*",
            ),
            "*Hop1 240e:390:*:**** Hop2 198.51.100.2.*" to Triple(
                "*Hop1 240e:390:*:**** Hop2 [已隐藏本机IP].*",
                "*Hop1 240e:390:*:**** Hop2 [已隐藏本机IP].*",
                "*Hop1 240e:390:*:**** Hop2 198.51.100.*.*",
            ),
            "**Hop1 240e:390:*:***** Hop2 198.51.100.2.*" to Triple(
                "**Hop1 240e:390:*:***** Hop2 [已隐藏本机IP].*",
                "**Hop1 240e:390:*:***** Hop2 [已隐藏本机IP].*",
                "**Hop1 240e:390:*:***** Hop2 198.51.100.*.*",
            ),
            "*Hop1 ::ffff:203.*.*.* Hop2 198.51.100.2.*" to Triple(
                "*Hop1 ::ffff:203.*.*.* Hop2 [已隐藏本机IP].*",
                "*Hop1 ::ffff:203.*.*.* Hop2 [已隐藏本机IP].*",
                "*Hop1 ::ffff:203.*.*.* Hop2 198.51.100.*.*",
            ),
            "*Hop1 203.*.*.* Hop2 198.51.100.2.*" to Triple(
                "*Hop1 203.*.*.* Hop2 [已隐藏本机IP].*",
                "*Hop1 203.*.*.* Hop2 [已隐藏本机IP].*",
                "*Hop1 203.*.*.* Hop2 198.51.100.*.*",
            ),
            "*Hop1 2001:: Hop2 198.51.100.2.*" to Triple(
                "*Hop1 2001:: Hop2 [已隐藏本机IP].*",
                "*Hop1 2001:*:* Hop2 [已隐藏本机IP].*",
                "*Hop1 2001::**** Hop2 198.51.100.*.*",
            ),
            "*Hop1 2001:*:* Hop2 198.51.100.2.*" to Triple(
                "*Hop1 2001:*:* Hop2 [已隐藏本机IP].*",
                "*Hop1 2001:*:* Hop2 [已隐藏本机IP].*",
                "*Hop1 2001:*:* Hop2 198.51.100.*.*",
            ),
            "*Hop1 2001::* Hop2 198.51.100.2.*" to Triple(
                "*Hop1 2001::* Hop2 198.51.100.2.*",
                "*Hop1 2001:*:** Hop2 198.51.100.2.*",
                "*Hop1 2001::***** Hop2 198.51.100.2.*",
            ),
            "*Hop1 2001::* Hop2 198.51.100.2" to Triple(
                "*Hop1 2001::* Hop2 [已隐藏本机IP]",
                "*Hop1 2001:*:** Hop2 [已隐藏本机IP]",
                "*Hop1 2001::***** Hop2 198.51.100.*",
            ),
            "**Hop1 \u001B[36m240e:390::1:*****\u001B[0m Hop2 198.51.100.2.*" to Triple(
                "**Hop1 \u001B[36m240e:390::1:*****\u001B[0m Hop2 [已隐藏本机IP].*",
                "**Hop1 \u001B[36m240e:390::1:*****\u001B[0m Hop2 [已隐藏本机IP].*",
                "**Hop1 \u001B[36m240e:390::1:*****\u001B[0m Hop2 198.51.100.*.*",
            ),
            "*a 2*3=6 198.51.100.2.*" to Triple(
                "*a 2*3=6 [已隐藏本机IP].*",
                "*a 2*3=6 [已隐藏本机IP].*",
                "*a 2*3=6 198.51.100.*.*",
            ),
            ((1..25).joinToString(" ") { "*$it" } + " 198.51.100.2.*") to Triple(
                (1..25).joinToString(" ") { "*$it" } + " [已隐藏本机IP].*",
                (1..25).joinToString(" ") { "*$it" } + " [已隐藏本机IP].*",
                (1..25).joinToString(" ") { "*$it" } + " 198.51.100.*.*",
            ),
            "**Hop1 64:ff9b::203.0.*.* Hop2 ::ffff:203.*.*.* Hop3 240e:390::1:***** Hop4 198.51.100.2.*" to Triple(
                "**Hop1 64:ff9b::203.0.*.* Hop2 ::ffff:203.*.*.* Hop3 240e:390::1:***** Hop4 [已隐藏本机IP].*",
                "**Hop1 64:ff9b::203.0.*.* Hop2 ::ffff:203.*.*.* Hop3 240e:390::1:***** Hop4 [已隐藏本机IP].*",
                "**Hop1 64:ff9b::203.0.*.* Hop2 ::ffff:203.*.*.* Hop3 240e:390::1:***** Hop4 198.51.100.*.*",
            ),
        )
        for ((input, expectedTriple) in client198Cases) {
            val (expFalse, expTrue, expMar) = expectedTriple
            val outFalse = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v4Client198Report, maskTargetIp = false)
            assertEquals("scrubPublicText(false) failed for: $input", expFalse, outFalse)
            assertEquals("createScrubber(false) failed for: $input", expFalse, scrubberFalse(input))

            val outTrue = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v4Client198Report, maskTargetIp = true)
            assertEquals("scrubPublicText(true) failed for: $input", expTrue, outTrue)
            assertEquals("createScrubber(true) failed for: $input", expTrue, scrubberTrue(input))
            assertEquals(
                "scrubPublicText(true) idempotency failed for: $input",
                outTrue,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(outTrue, v4Client198Report, maskTargetIp = true),
            )
            assertEquals("createScrubber(true) idempotency failed for: $input", outTrue, scrubberTrue(outTrue))

            val outMar = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(input)
            assertEquals("maskAnyRemainingIpLiterals failed for: $input", expMar, outMar)
            assertEquals(
                "maskAnyRemainingIpLiterals idempotency failed for: $input",
                outMar,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(outMar),
            )
        }

        // G1 non-client `2001:4860::**` / `2001::` / `::2` & Step 1 target boundary guards
        val stNonClientCases = listOf(
            "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::**" to "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::**",
            "***Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::**" to "***Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860:*:***",
            "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::*" to "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860:*:**",
            "**Hop1 240e:390:*:**** Hop2 2001:4860::**" to "**Hop1 240e:390:*:**** Hop2 2001:4860:*:***",
            "**Hop1 203.*.*.* Hop2 2001:4860::**" to "**Hop1 203.*.*.* Hop2 2001:4860:*:***",
            "*Hop1 2001::" to "*Hop1 2001:*:*",
            "**Hop1 2001::" to "**Hop1 2001:*:*",
            "2001::" to "2001:*:*",
            "::2" to "0:0:*:*",
            "*Hop1 ::2" to "*Hop1 0:0:*:*",
            // G6 multi-line isolation
            "*Line1\n203.0.113.42.*" to "*Line1\n203.0.113.42.*",
            "**Line1\n240e:390::**" to "**Line1\n240e:390::**",
            "*".repeat(80) + "\n203.0.113.42.*" to "*".repeat(80) + "\n203.0.113.42.*",
            "*".repeat(80) + "\n*Hop1 203.0.113.42.*" to "*".repeat(80) + "\n*Hop1 203.0.*.*.*",
        )
        for ((input, expected) in stNonClientCases) {
            val once = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, neutralReport, maskTargetIp = true)
            assertEquals("scrubPublicText(neutral, true) failed for: $input", expected, once)
            assertEquals(
                "scrubPublicText(neutral, true) idempotency failed for: $input",
                once,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(once, neutralReport, maskTargetIp = true),
            )
        }

        // Step 1 targetIpReplacementPairs boundary safety + PTR preservation
        assertEquals(
            "203.0.113.*.sslip.io ec2-203-0-113-*.compute.amazonaws.com",
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = "203.0.113.42.sslip.io ec2-203-0-113-42.compute.amazonaws.com",
                report = target203PtrReport,
                maskTargetIp = true,
            ),
        )
        val noTargetPrefixLeak = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
            raw = "target=2001:: other=2001::1",
            report = target2001ShortReport,
            maskTargetIp = true,
        )
        assertFalse("2001:: must not corrupt 2001::1 into 2001::****1: $noTargetPrefixLeak", noTargetPrefixLeak.contains("2001::****1"))
        assertEquals("target=2001::**** other=2001:1:*:*", noTargetPrefixLeak)

        val targetWrappedOnce = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
            raw = "**Target 2001:4860::1* Hop2 198.51.100.2.*",
            report = target2001GoogleReport,
            maskTargetIp = true,
        )
        assertEquals("**Target 2001:4860::***** Hop2 [已隐藏本机IP].*", targetWrappedOnce)
        assertEquals(
            targetWrappedOnce,
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
                raw = targetWrappedOnce,
                report = target2001GoogleReport,
                maskTargetIp = true,
            ),
        )
    }

    @Test
    fun `G8 end-to-end markdown formatter and card renderer scrub adversarial Markdown plus IP strings`() {
        val advHost = "**Hop1 240e:390::1:***** Hop2 198.51.100.2.*"
        val report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "203.0.113.42",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通 *Client 203.*.*.* 198.51.100.2.*",
                ipFamily = IpFamily.IPV4,
                maskedIp = "198.51.*.*",
                rawIp = "198.51.100.2",
            ),
            forwardTraceroute = TracerouteSummary(
                direction = TracerouteDirection.FORWARD,
                targetInput = "203.0.113.42",
                targetIp = "203.0.113.42",
                ipFamily = IpFamily.IPV4,
                toolName = "icmp-ttl",
                hops = listOf(
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 1,
                        ip = "59.43.130.1",
                        hostname = advHost,
                        sent = 3,
                        received = 3,
                        avgMs = 18.0,
                    ),
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 2,
                        ip = "203.0.113.42",
                        hostname = "203.0.113.42.sslip.io",
                        sent = 3,
                        received = 3,
                        avgMs = 42.0,
                        isTargetReached = true,
                    ),
                ),
            ),
        )

        for (style in NetBenchMarkdownStyle.entries) {
            for (maskTarget in listOf(false, true)) {
                val md = NetBenchMarkdownFormatter.format(
                    report = report,
                    options = NetBenchMarkdownOptions(style = style, maskTargetIp = maskTarget),
                )
                assertFalse("Client IP leaked in $style/$maskTarget:\n$md", md.contains("198.51.100.2"))
                assertFalse("Client masked IP leaked in $style/$maskTarget:\n$md", md.contains("198.51.*.*"))
                if (maskTarget) {
                    assertFalse("Target IP leaked in $style/maskTarget=true:\n$md", md.contains("203.0.113.42"))
                }
            }
        }

        val sanitized = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.sanitize(report, maskTargetIp = true)
        val cards = com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator.buildAllCardPresentations(sanitized)
        for (card in cards) {
            val drawn = com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator.extractRenderedCardTexts(
                kind = card.kind,
                report = report,
                maskTargetIp = true,
                includeClientNetwork = true,
                darkTheme = false,
            )
            val allText = (card.allDisplayTexts() + drawn).joinToString("\n")
            assertFalse("Card ${card.kind} leaked client IP:\n$allText", allText.contains("198.51.100.2"))
            assertFalse("Card ${card.kind} leaked client masked IP:\n$allText", allText.contains("198.51.*.*"))
            assertFalse("Card ${card.kind} leaked target IP:\n$allText", allText.contains("203.0.113.42"))
        }
    }

    @Test
    fun `sanitizer and markdown formatter fix Iteration 13 trailing IPv4 1-colon prefix double-colon star guard and tagged v6 masks`() {
        val v4Client198Report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                maskedIp = "198.51.*.*",
                rawIp = "198.51.100.2",
            ),
        )
        val v6ClientMaskedReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV6,
                maskedIp = "240e:390::****",
                rawIp = "240e:390::1234",
            ),
        )
        val scrubberFalse = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(
            report = v4Client198Report,
            maskTargetIp = false,
        )
        val scrubberTrue = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(
            report = v4Client198Report,
            maskTargetIp = true,
        )

        // Bug 1 & Bug 2 & Bug 4 across all entrypoints (scrubPublicText(false/true), createScrubber(false/true), maskAnyRemainingIpLiterals)
        val bug1And2And4Cases = listOf(
            "240e:390::****:198.51.100.2" to Triple(
                "240e:390::****:[已隐藏本机IP]",
                "240e:390::****:[已隐藏本机IP]",
                "240e:390::****:198.51.100.*",
            ),
            "240e:390:*:198.51.100.2" to Triple(
                "240e:390:*:[已隐藏本机IP]",
                "240e:390:*:[已隐藏本机IP]",
                "240e:390:*:198.51.100.*",
            ),
            "240e:390:****:198.51.100.2" to Triple(
                "240e:390:****:[已隐藏本机IP]",
                "240e:390:****:[已隐藏本机IP]",
                "240e:390:****:198.51.100.*",
            ),
            "240e:390::1:****:198.51.100.2" to Triple(
                "240e:390::1:****:[已隐藏本机IP]",
                "240e:390::1:****:[已隐藏本机IP]",
                "240e:390::1:****:198.51.100.*",
            ),
            "[240e:390::****]:198.51.100.2" to Triple(
                "[240e:390::****]:[已隐藏本机IP]",
                "[240e:390::****]:[已隐藏本机IP]",
                "[240e:390::****]:198.51.100.*",
            ),
            "*Hop1 240e:390::****:198.51.100.2.*" to Triple(
                "*Hop1 240e:390::****:[已隐藏本机IP].*",
                "*Hop1 240e:390::****:[已隐藏本机IP].*",
                "*Hop1 240e:390::****:198.51.100.*.*",
            ),
            "240e:390::****:198.51.100.*" to Triple(
                "240e:390::****:198.51.100.*",
                "240e:390::****:198.51.100.*",
                "240e:390::****:198.51.100.*",
            ),
            "240e:390::****:1.2.3.example" to Triple(
                "240e:390::****:1.2.3.example",
                "240e:390::****:1.2.3.example",
                "240e:390::****:1.2.3.example",
            ),
            "aa:198.51.100.2" to Triple(
                "aa:[已隐藏本机IP]",
                "aa:[已隐藏本机IP]",
                "aa:198.51.100.*",
            ),
            "1234:198.51.100.2" to Triple(
                "1234:[已隐藏本机IP]",
                "1234:[已隐藏本机IP]",
                "1234:198.51.100.*",
            ),
            "dead:198.51.100.2" to Triple(
                "dead:[已隐藏本机IP]",
                "dead:[已隐藏本机IP]",
                "dead:198.51.100.*",
            ),
            "cafe:203.0.113.42" to Triple(
                "cafe:203.0.113.42",
                "cafe:203.0.*.*",
                "cafe:203.0.113.*",
            ),
            "240e:390::1:****[CN2]" to Triple(
                "240e:390::1:****[CN2]",
                "240e:390::1:****[CN2]",
                "240e:390::1:****[CN2]",
            ),
            "240e:390::1:****(电信)" to Triple(
                "240e:390::1:****(电信)",
                "240e:390::1:****(电信)",
                "240e:390::1:****(电信)",
            ),
            "240e:390::1:****\"quoted\"" to Triple(
                "240e:390::1:****\"quoted\"",
                "240e:390::1:****\"quoted\"",
                "240e:390::1:****\"quoted\"",
            ),
            "240e:390::1:****【CN2】" to Triple(
                "240e:390::1:****【CN2】",
                "240e:390::1:****【CN2】",
                "240e:390::1:****【CN2】",
            ),
        )

        for ((input, expectedTriple) in bug1And2And4Cases) {
            val (expFalse, expTrue, expMar) = expectedTriple
            val outFalse = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v4Client198Report, maskTargetIp = false)
            assertEquals("scrubPublicText(false) failed for: $input", expFalse, outFalse)
            assertEquals("createScrubber(false) failed for: $input", expFalse, scrubberFalse(input))

            val outTrue = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v4Client198Report, maskTargetIp = true)
            assertEquals("scrubPublicText(true) failed for: $input", expTrue, outTrue)
            assertEquals("createScrubber(true) failed for: $input", expTrue, scrubberTrue(input))
            assertEquals(
                "scrubPublicText(true) idempotency failed for: $input",
                outTrue,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(outTrue, v4Client198Report, maskTargetIp = true),
            )
            assertEquals("createScrubber(true) idempotency failed for: $input", outTrue, scrubberTrue(outTrue))

            val outMar = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(input)
            assertEquals("maskAnyRemainingIpLiterals failed for: $input", expMar, outMar)
            assertEquals(
                "maskAnyRemainingIpLiterals idempotency failed for: $input",
                outMar,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(outMar),
            )
        }

        // Bug 4: client masked token immediately before [tag] / (tag)
        for ((input, expectedScrubbed) in listOf(
            "240e:390::****[CN2]" to "[已隐藏本机IP][CN2]",
            "240e:390::****(电信)" to "[已隐藏本机IP](电信)",
        )) {
            assertEquals(
                expectedScrubbed,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v6ClientMaskedReport, maskTargetIp = false),
            )
            val scrubbedTrue = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v6ClientMaskedReport, maskTargetIp = true)
            assertEquals(expectedScrubbed, scrubbedTrue)
            assertEquals(
                scrubbedTrue,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(scrubbedTrue, v6ClientMaskedReport, maskTargetIp = true),
            )
            assertEquals(
                expectedScrubbed,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(v6ClientMaskedReport, maskTargetIp = true)(input),
            )
        }

        // Bug 3: `::`-terminated IPv6 targets must not corrupt `prefix::****` or `prefix::*`
        val targetDb8Report = com.nodeseek.app.domain.netbench.NetBenchReport(targetInput = "2001:db8::")
        val target240eReport = com.nodeseek.app.domain.netbench.NetBenchReport(targetInput = "240e:390::")
        val target2001ShortReport = com.nodeseek.app.domain.netbench.NetBenchReport(targetInput = "2001::")
        val db8Scrubber = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(targetDb8Report, maskTargetIp = true)

        val db8Cases = listOf(
            "2001:db8::****" to "2001:db8::****",
            "2001:db8::*" to "2001:db8::*",
            "*2001:db8::*" to "*2001:db8:*:**",
            "**2001:db8::**" to "**2001:db8:*:***",
        )
        for ((input, expected) in db8Cases) {
            val once = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, targetDb8Report, maskTargetIp = true)
            assertEquals("scrubPublicText(db8, true) mismatch for: $input", expected, once)
            assertEquals("createScrubber(db8, true) mismatch for: $input", expected, db8Scrubber(input))
            assertEquals(
                "scrubPublicText(db8, true) idempotency failed for: $input",
                once,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(once, targetDb8Report, maskTargetIp = true),
            )
            assertEquals("createScrubber(db8, true) idempotency failed for: $input", once, db8Scrubber(once))
        }

        val out240e = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
            raw = "240e:390::**** and 240e:390::1",
            report = target240eReport,
            maskTargetIp = true,
        )
        assertEquals("240e:390::**** and 240e:390:*:*", out240e)
        assertEquals(
            out240e,
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(out240e, target240eReport, maskTargetIp = true),
        )

        val out2001Short = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(
            raw = "2001::1 and 2001:: and 2001::* and dead:2001::",
            report = target2001ShortReport,
            maskTargetIp = true,
        )
        assertEquals("2001:1:*:* and 2001::**** and 2001::* and dead:2001:*:*", out2001Short)
        assertEquals(
            out2001Short,
            com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(out2001Short, target2001ShortReport, maskTargetIp = true),
        )

        // End-to-end NetBenchMarkdownFormatter.format with `::`-terminated IPv6 target
        val db8MarkdownReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "2001:db8::",
            resolvedTarget = com.nodeseek.app.domain.netbench.ResolvedTarget(
                displayHost = "2001:db8::",
                resolvedIp = "2001:db8::",
                ipFamily = IpFamily.IPV6,
            ),
            clientNetwork = v4Client198Report.clientNetwork,
        )
        for (style in NetBenchMarkdownStyle.entries) {
            val md = NetBenchMarkdownFormatter.format(
                report = db8MarkdownReport,
                options = NetBenchMarkdownOptions(style = style, maskTargetIp = true),
            )
            assertFalse("NetBenchMarkdownFormatter($style) corrupted ::**** to ********:\n$md", md.contains("2001:db8::********"))
            assertTrue("NetBenchMarkdownFormatter($style) should retain masked [2001:db8::****]:\n$md", md.contains("2001:db8::****"))
            assertFalse("NetBenchMarkdownFormatter($style) leaked client IP:\n$md", md.contains("198.51.100.2"))
        }
    }

    @Test
    fun `sanitizer and markdown formatter fix Iteration 14 wildcard closer leaks and long hex-colon trailing IPv4`() {
        val v4Client198Report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                maskedIp = "198.51.*.*",
                rawIp = "198.51.100.2",
            ),
        )
        val scrubberFalse = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(
            report = v4Client198Report,
            maskTargetIp = false,
        )
        val scrubberTrue = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.createScrubber(
            report = v4Client198Report,
            maskTargetIp = true,
        )
        val longHexPrefix120 = "deadbeef:".repeat(14)

        val cases = listOf(
            // Bug A: colon/dot-bounded wildcard hextets/octets must not pop openStack
            "*Hop1 240e:390:*:198.51.100.2.*" to Triple(
                "*Hop1 240e:390:*:[已隐藏本机IP].*",
                "*Hop1 240e:390:*:[已隐藏本机IP].*",
                "*Hop1 240e:390:*:198.51.100.*.*",
            ),
            "*Hop1 240e:390:****:198.51.100.2.*" to Triple(
                "*Hop1 240e:390:****:[已隐藏本机IP].*",
                "*Hop1 240e:390:****:[已隐藏本机IP].*",
                "*Hop1 240e:390:****:198.51.100.*.*",
            ),
            "*Hop1 240e:390:1:****:198.51.100.2.*" to Triple(
                "*Hop1 240e:390:1:****:[已隐藏本机IP].*",
                "*Hop1 240e:390:1:****:[已隐藏本机IP].*",
                "*Hop1 240e:390:1:****:198.51.100.*.*",
            ),
            "*Hop1 240e:390::*:198.51.100.2.*" to Triple(
                "*Hop1 240e:390::*:[已隐藏本机IP].*",
                "*Hop1 240e:390::*:[已隐藏本机IP].*",
                "*Hop1 240e:390::*:198.51.100.*.*",
            ),
            "*Hop1 240e:390::1:*****:198.51.100.2.*" to Triple(
                "*Hop1 240e:390::1:*****:[已隐藏本机IP].*",
                "*Hop1 240e:390::1:*****:[已隐藏本机IP].*",
                "*Hop1 240e:390::1:*****:198.51.100.*.*",
            ),
            "**Hop1 240e:390:*:198.51.100.2.**" to Triple(
                "**Hop1 240e:390:*:[已隐藏本机IP].**",
                "**Hop1 240e:390:*:[已隐藏本机IP].**",
                "**Hop1 240e:390:*:198.51.100.*.**",
            ),
            "**Hop1 240e:390:****:198.51.100.2.**" to Triple(
                "**Hop1 240e:390:****:[已隐藏本机IP].**",
                "**Hop1 240e:390:****:[已隐藏本机IP].**",
                "**Hop1 240e:390:****:198.51.100.*.**",
            ),
            "**Hop1 240e:390:1:****:198.51.100.2.**" to Triple(
                "**Hop1 240e:390:1:****:[已隐藏本机IP].**",
                "**Hop1 240e:390:1:****:[已隐藏本机IP].**",
                "**Hop1 240e:390:1:****:198.51.100.*.**",
            ),
            "**Hop1 240e:390::*:198.51.100.2.**" to Triple(
                "**Hop1 240e:390::*:[已隐藏本机IP].**",
                "**Hop1 240e:390::*:[已隐藏本机IP].**",
                "**Hop1 240e:390::*:198.51.100.*.**",
            ),
            "*Hop1 192.168.*.1:198.51.100.2.*" to Triple(
                "*Hop1 192.168.*.1:[已隐藏本机IP].*",
                "*Hop1 192.168.*.1:[已隐藏本机IP].*",
                "*Hop1 192.168.*.1:198.51.100.*.*",
            ),
            "*Hop1 192.168.*:198.51.100.2.*" to Triple(
                "*Hop1 192.168.*:[已隐藏本机IP].*",
                "*Hop1 192.168.*:[已隐藏本机IP].*",
                "*Hop1 192.168.*:198.51.100.*.*",
            ),
            "**a:**: 2001:4860:1::**" to Triple(
                "**a:**: 2001:4860:1::**",
                "**a:**: 2001:4860:1::**",
                "**a:**: 2001:4860:1::**",
            ),
            "**a.**. 2001:4860:1::**" to Triple(
                "**a.**. 2001:4860:1::**",
                "**a.**. 2001:4860:1::**",
                "**a.**. 2001:4860:1::**",
            ),
            // Bug B: >=41/46-char and >100-char hex/colon prefixes before IPv4
            "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.100.2" to Triple(
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:[已隐藏本机IP]",
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:[已隐藏本机IP]",
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.100.*",
            ),
            "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:203.0.113.42" to Triple(
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:203.0.113.42",
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:203.0.*.*",
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:203.0.113.*",
            ),
            "0123456789abcdef0123456789abcdef0123456789ab:198.51.100.2" to Triple(
                "0123456789abcdef0123456789abcdef0123456789ab:[已隐藏本机IP]",
                "0123456789abcdef0123456789abcdef0123456789ab:[已隐藏本机IP]",
                "0123456789abcdef0123456789abcdef0123456789ab:198.51.100.*",
            ),
            "abcd:abcd:abcd:abcd:abcd:abcd:abcd:abcd:198.51.100.2" to Triple(
                "abcd:abcd:abcd:abcd:abcd:abcd:abcd:abcd:[已隐藏本机IP]",
                "abcd:abcd:abcd::****:[已隐藏本机IP]",
                "abcd:abcd:abcd:abcd:abcd:abcd:abcd:abcd:198.51.100.*",
            ),
            "${longHexPrefix120}198.51.100.2" to Triple(
                "${longHexPrefix120}[已隐藏本机IP]",
                "${longHexPrefix120}[已隐藏本机IP]",
                "${longHexPrefix120}198.51.100.*",
            ),
            "*Hop1 deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.100.2.*" to Triple(
                "*Hop1 deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:[已隐藏本机IP].*",
                "*Hop1 deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:[已隐藏本机IP].*",
                "*Hop1 deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.100.*.*",
            ),
        )

        for ((input, expectedTriple) in cases) {
            val (expFalse, expTrue, expMar) = expectedTriple
            val outFalse = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v4Client198Report, maskTargetIp = false)
            assertEquals("scrubPublicText(false) failed for: $input", expFalse, outFalse)
            assertEquals("createScrubber(false) failed for: $input", expFalse, scrubberFalse(input))
            assertEquals(
                "scrubPublicText(false) idempotency failed for: $input",
                outFalse,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(outFalse, v4Client198Report, maskTargetIp = false),
            )

            val outTrue = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(input, v4Client198Report, maskTargetIp = true)
            assertEquals("scrubPublicText(true) failed for: $input", expTrue, outTrue)
            assertEquals("createScrubber(true) failed for: $input", expTrue, scrubberTrue(input))
            assertEquals(
                "scrubPublicText(true) idempotency failed for: $input",
                outTrue,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.scrubPublicText(outTrue, v4Client198Report, maskTargetIp = true),
            )
            assertEquals("createScrubber(true) idempotency failed for: $input", outTrue, scrubberTrue(outTrue))

            val outMar = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(input)
            assertEquals("maskAnyRemainingIpLiterals failed for: $input", expMar, outMar)
            assertEquals(
                "maskAnyRemainingIpLiterals idempotency failed for: $input",
                outMar,
                com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.maskAnyRemainingIpLiterals(outMar),
            )
        }

        // End-to-end NetBenchMarkdownFormatter.format(FULL_TABLE) with 45-char hex/colon prefix + trailing client IPv4
        val mdReport = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = v4Client198Report.clientNetwork,
            forwardTraceroute = TracerouteSummary(
                direction = TracerouteDirection.FORWARD,
                targetInput = "45.12.34.56",
                targetIp = "45.12.34.56",
                ipFamily = IpFamily.IPV4,
                toolName = "icmp-ttl",
                hops = listOf(
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 1,
                        ip = "45.12.34.56",
                        asn = "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.100.2",
                        sent = 3,
                        received = 3,
                        avgMs = 10.0,
                        isTargetReached = true,
                    ),
                ),
            ),
        )
        val fullTableMd = NetBenchMarkdownFormatter.format(
            report = mdReport,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, maskTargetIp = true),
        )
        assertFalse("NetBenchMarkdownFormatter(FULL_TABLE) leaked client IPv4:\n$fullTableMd", fullTableMd.contains("198.51.100.2"))
        assertTrue(
            "NetBenchMarkdownFormatter(FULL_TABLE) should scrub trailing client IPv4:\n$fullTableMd",
            fullTableMd.contains("deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:[已隐藏本机IP]"),
        )
    }

    @Test
    fun `sanitize_reverseTraceroute_scrubsClientPtrAndInAddrArpaRegardlessOfMaskTargetIp`() {
        val report = com.nodeseek.app.domain.netbench.NetBenchReport(
            targetInput = "45.12.34.56",
            clientNetwork = com.nodeseek.app.domain.netbench.ClientNetworkInfo(
                carrier = com.nodeseek.app.domain.netbench.ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                rawIp = "114.240.88.123",
                maskedIp = "114.240.88.*",
            ),
            reverseTraceroute = TracerouteSummary(
                direction = TracerouteDirection.REVERSE,
                targetInput = "114.240.88.123",
                targetIp = "114.240.88.123",
                ipFamily = IpFamily.IPV4,
                toolName = "mtr",
                hops = listOf(
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 1,
                        ip = "59.43.138.1",
                        hostname = "gw.59.43.138.1",
                        sent = 3,
                        received = 3,
                        avgMs = 12.0,
                    ),
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 2,
                        ip = "114.240.88.123",
                        hostname = "114-240-88-123.broadband.isp.net",
                        sent = 3,
                        received = 3,
                        avgMs = 148.0,
                        isTargetReached = true,
                    ),
                    com.nodeseek.app.domain.netbench.TracerouteHop(
                        hopNumber = 3,
                        ip = "114.240.88.123",
                        hostname = "123.88.240.114.in-addr.arpa",
                        sent = 3,
                        received = 3,
                        avgMs = 149.0,
                    ),
                ),
            ),
        )

        for (maskTarget in listOf(false, true)) {
            val sanitized = com.nodeseek.app.domain.netbench.NetBenchReportSanitizer.sanitize(
                report = report,
                maskTargetIp = maskTarget,
            )
            val hop2Host = sanitized.reverseTraceroute!!.hops[1].displayHostname.orEmpty()
            val hop3Host = sanitized.reverseTraceroute!!.hops[2].displayHostname.orEmpty()
            assertFalse("maskTargetIp=$maskTarget leaked dash PTR in $hop2Host", hop2Host.contains("114-240-88-123"))
            assertFalse("maskTargetIp=$maskTarget leaked arpa PTR in $hop3Host", hop3Host.contains("123.88.240.114"))
            assertTrue(hop2Host.contains("[已隐藏本机IP]"))
            assertTrue(hop3Host.contains("[已隐藏本机IP]"))

            val md = NetBenchMarkdownFormatter.format(
                report = report,
                options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, maskTargetIp = maskTarget),
            )
            assertFalse("maskTargetIp=$maskTarget leaked client dash PTR in markdown:\n$md", md.contains("114-240-88-123"))
            assertFalse("maskTargetIp=$maskTarget leaked client arpa PTR in markdown:\n$md", md.contains("123.88.240.114"))
        }
    }
}
