package com.nodeseek.app.netbench

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nodeseek.app.data.netbench.HttpLatencyRunner
import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.TcpPingRunner
import com.nodeseek.app.data.netbench.TcpProbeConnector
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.netbench.NetBenchSessionController
import com.nodeseek.app.ui.netbench.insertNetBenchMarkdownAtCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
            assertEquals(4, finalState.tcpPingProgress?.summary?.received)
            assertEquals(IpOriginType.NATIVE, finalState.ipIntelligence?.originType)
            assertTrue(finalState.generatedMarkdown.contains("中国电信"))
            assertTrue(finalState.generatedMarkdown.contains("原生 IP"))
            assertFalse(finalState.generatedMarkdown.contains("114.240.99.77"))

            // Switch style to FULL_TABLE and verify live regeneration
            controller.selectMarkdownStyle(NetBenchMarkdownStyle.FULL_TABLE)
            assertTrue(controller.uiState.value.generatedMarkdown.contains("| 测试项目 | 实测结果 | 备注 |"))
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
}
