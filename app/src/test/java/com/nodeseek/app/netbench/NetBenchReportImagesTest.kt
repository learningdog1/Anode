package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.IcmpPingRunner
import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.PingExecutionEvent
import com.nodeseek.app.data.netbench.PingProcessExecutor
import com.nodeseek.app.data.netbench.TcpPingRunner
import com.nodeseek.app.data.netbench.TcpProbeConnector
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.data.repository.NodeImageHostRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.IcmpPingSample
import com.nodeseek.app.domain.netbench.IcmpPingSummary
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3IntervalSample
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.NetBenchReportCardKind
import com.nodeseek.app.domain.netbench.NetBenchUploadedImages
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingSample
import com.nodeseek.app.domain.netbench.TcpPingSummary
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator
import com.nodeseek.app.ui.netbench.NetBenchCardImageGenerator
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.netbench.NetBenchSessionController
import com.nodeseek.app.ui.netbench.RenderedNetBenchCardImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NetBenchReportImagesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val validApiKey = "abcdef0123456789abcdef0123456789"

    private fun buildSampleFullReport(): NetBenchReport {
        val target = ResolvedTarget(
            displayHost = "hk-gia.example.net",
            resolvedIp = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
            dnsDurationMs = 14.2,
        )
        return NetBenchReport(
            targetInput = "hk-gia.example.net",
            resolvedTarget = target,
            tcpPort = 5201,
            clientNetwork = ClientNetworkInfo(
                carrier = ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV4,
                regionName = "上海",
                asn = "AS4134",
                maskedIp = "180.168.*.*",
                rawIp = "180.168.12.88",
                transportLabel = "Wi-Fi",
            ),
            icmpPingSummary = IcmpPingSummary(
                resolvedTarget = target,
                sent = 4,
                received = 4,
                lossPercent = 0.0,
                minMs = 107.0,
                avgMs = 127.3,
                maxMs = 162.0,
                jitterMdevMs = 21.4,
                boundInterfaceName = "wlan0",
                samples = listOf(
                    IcmpPingSample(1, true, 107.0, ttl = 54),
                    IcmpPingSample(2, true, 122.1, ttl = 54),
                    IcmpPingSample(3, true, 162.0, ttl = 54),
                    IcmpPingSample(4, true, 118.1, ttl = 54),
                ),
            ),
            tcpPingSummary = TcpPingSummary(
                resolvedTarget = target,
                port = 5201,
                sent = 4,
                received = 4,
                lossPercent = 0.0,
                minMs = 106.0,
                avgMs = 150.6,
                maxMs = 253.0,
                jitterMdevMs = 52.5,
                samples = listOf(
                    TcpPingSample(1, true, 106.0),
                    TcpPingSample(2, true, 253.0),
                    TcpPingSample(3, true, 121.4),
                    TcpPingSample(4, true, 122.0),
                ),
            ),
            iperfDownloadResult = Iperf3Result(
                direction = Iperf3Direction.DOWNLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 4.0,
                bytesTransferred = 103_250_000L,
                clientMbps = 206.5,
                serverMbps = 208.0,
                peakMbps = 224.0,
                retransmits = 0L,
                success = true,
                intervals = listOf(
                    Iperf3IntervalSample(1, 0.0, 1.0, 24_000_000L, 192.0),
                    Iperf3IntervalSample(2, 1.0, 2.0, 26_500_000L, 212.0),
                    Iperf3IntervalSample(3, 2.0, 3.0, 28_000_000L, 224.0),
                    Iperf3IntervalSample(4, 3.0, 4.0, 24_750_000L, 198.0),
                ),
            ),
            ipIntelligence = IpIntelligenceReport(
                ip = "203.0.113.42",
                ipFamily = IpFamily.IPV4,
                countryCode = "HK",
                countryName = "Hong Kong",
                regionName = "Central",
                cityName = "Hong Kong",
                asn = "AS4809",
                orgName = "China Telecom CN2 / DMIT HK",
                ispName = "DMIT",
                usageType = IpUsageType.HOSTING_DATACENTER,
                originType = IpOriginType.NATIVE,
                rirCountryCode = "HK",
                rirAuthority = "APNIC",
                originEvidence = "RIR 注册国 HK 与 GeoIP 落地国 HK 一致，判定为香港原生 IP",
                geoSource = "ipwho.is",
                whoisSource = "RIPEstat",
            ),
        )
    }

    @Test
    fun `full table markdown embeds NodeImage cards in matching sections and preserves URLs under IP scrubbing`() {
        val report = buildSampleFullReport()
        val images = NetBenchUploadedImages(
            ipIntelligenceUrl = "https://i.nodeimage.com/i/ip_card_01.png",
            icmpPingUrl = "https://i.nodeimage.com/i/ping_card_02.png",
            tcpPingUrl = "http://127.0.0.1:5201/i/tcping_card_03.png",
            iperf3Url = "https://i.nodeimage.com/i/iperf3_card_04.png",
        )

        val fullMd = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                maskTargetIp = true,
                includeClientNetwork = true,
                timestampLabel = "2026-11-21 19:30",
                uploadedImages = images,
            ),
        )

        // All 4 NodeImage URLs must appear intact (even when test URL shares port 5201)
        assertTrue(fullMd.contains("![IP 归属与原生/广播鉴定](https://i.nodeimage.com/i/ip_card_01.png)"))
        assertTrue(fullMd.contains("![Ping 延迟探测波形](https://i.nodeimage.com/i/ping_card_02.png)"))
        assertTrue(fullMd.contains("![TCPing 延迟探测波形](http://127.0.0.1:5201/i/tcping_card_03.png)"))
        assertTrue(fullMd.contains("![原生 iperf3 吞吐测速](https://i.nodeimage.com/i/iperf3_card_04.png)"))

        // Verify exact section ordering
        val ipHeadingIdx = fullMd.indexOf("### 🌐 目标节点与 IP 质量鉴定")
        val ipImageIdx = fullMd.indexOf("https://i.nodeimage.com/i/ip_card_01.png")
        val latencyHeadingIdx = fullMd.indexOf("### ⚡ 网络延迟与链路稳定性分析")
        val pingImageIdx = fullMd.indexOf("https://i.nodeimage.com/i/ping_card_02.png")
        val tcpImageIdx = fullMd.indexOf("http://127.0.0.1:5201/i/tcping_card_03.png")
        val iperfHeadingIdx = fullMd.indexOf("### 🚀 iperf3 带宽吞吐实测全过程")
        val iperfImageIdx = fullMd.indexOf("https://i.nodeimage.com/i/iperf3_card_04.png")

        assertTrue(ipHeadingIdx in 0 until ipImageIdx)
        assertTrue(ipImageIdx < latencyHeadingIdx)
        assertTrue(latencyHeadingIdx < pingImageIdx)
        assertTrue(pingImageIdx < tcpImageIdx)
        assertTrue(tcpImageIdx < iperfHeadingIdx)
        assertTrue(iperfHeadingIdx < iperfImageIdx)

        // Privacy checks: client raw/masked IP and unmasked target IP never leak
        assertFalse(fullMd.contains("180.168.12.88"))
        assertFalse(fullMd.contains("180.168.*.*"))
        assertFalse(fullMd.contains("203.0.113.42"))

        // Markdown parser recognizes all 4 standalone image cards
        val blocks = parseNodeSeekMarkdownBlocks(fullMd)
        val imageCards = blocks.filterIsInstance<MdBlock.ImageCard>()
        assertEquals(
            listOf(
                "https://i.nodeimage.com/i/ip_card_01.png",
                "https://i.nodeimage.com/i/ping_card_02.png",
                "http://127.0.0.1:5201/i/tcping_card_03.png",
                "https://i.nodeimage.com/i/iperf3_card_04.png",
            ),
            imageCards.map { it.url },
        )

        // Reply card stays compact without full-report images
        val replyMd = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.REPLY_CARD,
                uploadedImages = images,
            ),
        )
        assertFalse(replyMd.contains("nodeimage.com"))
    }

    @Test
    fun `card planner enforces client IP privacy skips failed iperf3 and keeps ICMP cache key stable across target masking`() {
        val report = buildSampleFullReport()
        val footer = AndroidNetBenchCardImageGenerator.buildSafeClientFooterText(report)
        assertNotNull(footer)
        assertTrue(footer!!.contains("中国电信"))
        assertFalse(footer.contains("180.168.12.88"))
        assertFalse(footer.contains("180.168.*.*"))

        val maskedPlan = AndroidNetBenchCardImageGenerator.planCards(
            report = report,
            maskTargetIp = true,
            includeClientNetwork = true,
            darkTheme = false,
        ).toMap()
        val unmaskedPlan = AndroidNetBenchCardImageGenerator.planCards(
            report = report,
            maskTargetIp = false,
            includeClientNetwork = true,
            darkTheme = false,
        ).toMap()

        assertEquals(4, maskedPlan.size)
        // ICMP Ping card shows interface & RTT stats only -> cache key stays identical!
        assertEquals(
            maskedPlan[NetBenchReportCardKind.ICMP_PING],
            unmaskedPlan[NetBenchReportCardKind.ICMP_PING],
        )
        // IP, TCPing, and iperf3 cards reflect masked IP/port -> cache keys rotate cleanly!
        assertNotEquals(
            maskedPlan[NetBenchReportCardKind.IP_INTELLIGENCE],
            unmaskedPlan[NetBenchReportCardKind.IP_INTELLIGENCE],
        )
        assertNotEquals(
            maskedPlan[NetBenchReportCardKind.TCP_PING],
            unmaskedPlan[NetBenchReportCardKind.TCP_PING],
        )
        assertNotEquals(
            maskedPlan[NetBenchReportCardKind.IPERF3],
            unmaskedPlan[NetBenchReportCardKind.IPERF3],
        )

        // Failed iperf3 run should NOT produce a broken gauge image card
        val failedIperfReport = report.copy(
            iperfDownloadResult = report.iperfDownloadResult?.copy(success = false, errorMessage = "Connection refused"),
        )
        val planWithoutFailedIperf = AndroidNetBenchCardImageGenerator.planCards(
            report = failedIperfReport,
            maskTargetIp = true,
            includeClientNetwork = true,
            darkTheme = false,
        ).map { it.first }
        assertFalse(NetBenchReportCardKind.IPERF3 in planWithoutFailedIperf)
    }

    @Test
    fun `session controller prompts for missing NodeImage key uploads cards via official endpoint and caches per-card URLs`() = runBlocking {
        val uploadCounter = AtomicInteger(0)
        val receivedApiKeys = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/upload" && request.method == "POST" -> {
                        receivedApiKeys += request.getHeader("X-API-Key").orEmpty()
                        val idx = uploadCounter.incrementAndGet()
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"success":true,"links":{"direct":"https://i.nodeimage.com/i/bench_$idx.png"}}""")
                    }
                    path.startsWith("/ipwhois/") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "ip": "203.0.113.42",
                          "country": "Hong Kong",
                          "country_code": "HK",
                          "city": "Central",
                          "connection": {"asn": 4809, "org": "DMIT HK", "isp": "DMIT"}
                        }
                        """.trimIndent(),
                    )
                    path.startsWith("/ripestat") -> MockResponse().setResponseCode(200).setBody(
                        """{"data":{"authorities":["apnic"],"records":[[{"key":"country","value":"HK"}]]}}""",
                    )
                    path == "/self" -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"ip":"180.168.12.88","country_code":"CN","connection":{"asn":4134,"isp":"ChinaNet"}}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val baseOrigin = server.url("/")
            val baseStr = baseOrigin.toString().trimEnd('/')
            val cookieBridge = WebViewCookieBridge(forceInMemory = true)
            val okHttp = NodeSeekApiClient.createDefaultHttpClient(
                cookieBridge = cookieBridge,
                userAgentProvider = UserAgentProvider("NodeSeekNetBenchImageTest/1.0"),
                additionalTargetHost = baseOrigin.host,
            )
            val apiClient = NodeSeekApiClient(
                httpClient = okHttp,
                baseOrigin = baseOrigin,
                rssUrl = server.url("/rss.xml"),
                nodeImageOrigin = baseOrigin,
            )
            val settingsRepo = FakeSettingsRepository() // Initially empty API Key!
            val sessionRepo = SessionRepositoryImpl(
                apiClientProvider = { apiClient },
                cookieBridge = cookieBridge,
            )
            val imageHostRepo = NodeImageHostRepositoryImpl(
                apiClientProvider = { apiClient },
                settingsRepository = settingsRepo,
                sessionRepository = sessionRepo,
            )

            val historyStore = NetBenchHistoryStore(
                historyFile = File(tempFolder.root, "netbench_img_test.json"),
                ioDispatcher = Dispatchers.IO,
            )
            val resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO)
            val icmpRunner = IcmpPingRunner(
                resolver = resolver,
                executor = PingProcessExecutor { _ ->
                    flow {
                        emit(PingExecutionEvent.StdoutLine("64 bytes from 127.0.0.1: icmp_seq=1 ttl=54 time=25.4 ms"))
                        emit(PingExecutionEvent.StdoutLine("64 bytes from 127.0.0.1: icmp_seq=2 ttl=54 time=27.0 ms"))
                        emit(PingExecutionEvent.Exited(0))
                    }
                },
                ioDispatcher = Dispatchers.IO,
            )
            val tcpRunner = TcpPingRunner(
                resolver = resolver,
                connector = TcpProbeConnector { _, _, _ -> 31.2 },
                ioDispatcher = Dispatchers.IO,
            )
            val ipClient = IpIntelligenceClient(
                httpClient = OkHttpClient(),
                endpoints = IpIntelligenceEndpoints(
                    ipWhoIsTargetTemplate = "$baseStr/ipwhois/{ip}",
                    ipInfoTargetTemplate = "$baseStr/404/{ip}",
                    ipApiTargetTemplate = "$baseStr/404/{ip}",
                    ripeStatWhoisTemplate = "$baseStr/ripestat?resource={ip}",
                    rdapTemplates = emptyList(),
                    clientSelfUrls = listOf("ipwho.is" to "$baseStr/self"),
                ),
                ioDispatcher = Dispatchers.IO,
            )

            val fakeGenerator = object : NetBenchCardImageGenerator {
                override fun planCards(
                    report: NetBenchReport,
                    maskTargetIp: Boolean,
                    includeClientNetwork: Boolean,
                    darkTheme: Boolean,
                ): List<Pair<NetBenchReportCardKind, String>> =
                    AndroidNetBenchCardImageGenerator.planCards(
                        report = report,
                        maskTargetIp = maskTargetIp,
                        includeClientNetwork = includeClientNetwork,
                        darkTheme = darkTheme,
                    )

                override fun renderCard(
                    kind: NetBenchReportCardKind,
                    report: NetBenchReport,
                    maskTargetIp: Boolean,
                    includeClientNetwork: Boolean,
                    darkTheme: Boolean,
                ): RenderedNetBenchCardImage? {
                    val key = planCards(report, maskTargetIp, includeClientNetwork, darkTheme)
                        .firstOrNull { it.first == kind }?.second ?: return null
                    val png = makeMinimalValidPngBytes()
                    return RenderedNetBenchCardImage(
                        kind = kind,
                        pngBytes = png,
                        fileName = "${kind.fileSlug}.png",
                        widthPx = 1000,
                        heightPx = 420,
                        cacheKey = key,
                    )
                }
            }

            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                targetResolver = resolver,
                icmpPingRunner = icmpRunner,
                tcpPingRunner = tcpRunner,
                ipIntelligenceClient = ipClient,
                imageHostRepository = imageHostRepo,
                settingsRepository = settingsRepo,
                cardImageGenerator = fakeGenerator,
                renderDispatcher = Dispatchers.IO,
            )

            controller.updateTargetInput("127.0.0.1")
            controller.updateProbeCount(4)
            controller.startBenchmark()

            var attempts = 0
            while (controller.uiState.value.activePhase != NetBenchPhase.COMPLETED && attempts < 80) {
                delay(50)
                attempts++
            }
            assertEquals(NetBenchPhase.COMPLETED, controller.uiState.value.activePhase)

            // 1. Toggling report images ON with empty API key prompts setup dialog without uploading
            controller.toggleIncludeReportImages(true)
            assertTrue(controller.uiState.value.showNodeImageApiKeyDialog)
            assertFalse(controller.uiState.value.includeReportImages)
            assertEquals(0, uploadCounter.get())

            // 2. Saving API Key persists key and automatically renders & uploads all 3 active cards (IP + ICMP + TCP)
            controller.saveNodeImageApiKeyAndUpload(validApiKey)
            attempts = 0
            while (
                (controller.uiState.value.isUploadingReportImages ||
                    controller.uiState.value.uploadedReportImages.uploadedCount < 3) &&
                attempts < 80
            ) {
                delay(50)
                attempts++
            }

            val stateAfterUpload = controller.uiState.value
            assertFalse(stateAfterUpload.showNodeImageApiKeyDialog)
            assertTrue(stateAfterUpload.includeReportImages)
            assertEquals(3, uploadCounter.get())
            assertEquals(listOf(validApiKey, validApiKey, validApiKey), receivedApiKeys.toList())
            assertEquals(3, stateAfterUpload.uploadedReportImages.uploadedCount)
            assertTrue(stateAfterUpload.generatedMarkdown.contains("https://i.nodeimage.com/i/bench_1.png"))
            assertTrue(stateAfterUpload.generatedMarkdown.contains("https://i.nodeimage.com/i/bench_2.png"))
            assertTrue(stateAfterUpload.generatedMarkdown.contains("https://i.nodeimage.com/i/bench_3.png"))

            // 3. Toggling maskTargetIp -> false only uploads IP & TCP cards (2 new uploads; ICMP reuses cached URL!)
            controller.toggleMaskTargetIp(false)
            attempts = 0
            while (
                (controller.uiState.value.isUploadingReportImages ||
                    controller.uiState.value.uploadedReportImages.uploadedCount < 3) &&
                attempts < 80
            ) {
                delay(50)
                attempts++
            }
            assertEquals(5, uploadCounter.get())

            // 4. Toggling maskTargetIp back to true hits 100% cache -> 0 new HTTP uploads!
            controller.toggleMaskTargetIp(true)
            delay(100)
            assertEquals(5, uploadCounter.get())
            assertEquals(3, controller.uiState.value.uploadedReportImages.uploadedCount)
            assertTrue(controller.uiState.value.generatedMarkdown.contains("https://i.nodeimage.com/i/bench_1.png"))
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    private fun makeMinimalValidPngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            ),
        )
        // IHDR + IEND chunks (valid static PNG signature for ImageUploadFormatInspector)
        out.write(pngChunk("IHDR", ByteArray(13)))
        out.write(pngChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val len = payload.size
        val out = ByteArrayOutputStream()
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        out.write(ByteArray(4))
        return out.toByteArray()
    }
}
