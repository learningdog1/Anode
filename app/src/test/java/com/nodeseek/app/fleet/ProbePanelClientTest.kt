package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.ProbeCredentialBoundaryException
import com.nodeseek.app.data.fleet.ProbePanelClient
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ProbePanelClientTest {

    private lateinit var server: MockWebServer
    private val fixedNowMillis = 1_763_560_845_000L // 2025-11-19T14:00:45Z

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `Nezha v0 parses server details with Authorization header and online threshold`() = runTest {
        val nowSec = fixedNowMillis / 1000L
        val staleSec = nowSec - 600L // 10 minutes ago -> offline

        var capturedAuthHeader: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/server/details") {
                    capturedAuthHeader = request.getHeader("Authorization")
                    return MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "code": 0,
                          "message": "success",
                          "result": [
                            {
                              "id": 101,
                              "name": "JP-Tokyo-GIA",
                              "tag": "Asia",
                              "last_active": ${nowSec - 15},
                              "host": {
                                "Platform": "debian",
                                "PlatformVersion": "12.7",
                                "CPU": ["AMD EPYC 7763 2 Virtual Core"],
                                "MemTotal": 2147483648,
                                "DiskTotal": 42949672960,
                                "CountryCode": "jp"
                              },
                              "status": {
                                "CPU": 24.5,
                                "MemUsed": 536870912,
                                "DiskUsed": 10737418240,
                                "NetInSpeed": 125000,
                                "NetOutSpeed": 512000,
                                "NetInTransfer": 107374182400,
                                "NetOutTransfer": 214748364800,
                                "Uptime": 864000,
                                "Load1": 0.35
                              }
                            },
                            {
                              "id": 102,
                              "name": "US-LA-Backup",
                              "last_active": $staleSec,
                              "host": {
                                "Platform": "ubuntu",
                                "PlatformVersion": "24.04",
                                "MemTotal": 1073741824,
                                "DiskTotal": 21474836480,
                                "CountryCode": "us"
                              },
                              "status": {
                                "CPU": 0.0,
                                "MemUsed": 0,
                                "DiskUsed": 0,
                                "Uptime": 0
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val client = ProbePanelClient(
            nowMillisProvider = { fixedNowMillis },
            allowLoopbackHttpWithCredentials = true,
        )
        val config = ProbePanelConfig(
            id = "p-v0",
            name = "Nezha V0 Panel",
            baseUrl = server.url("/").toString(),
            panelType = ProbePanelType.NEZHA_V0,
            apiToken = "raw-nezha-v0-secret",
        )

        val fetched = client.fetchPanelServers(config)
        assertEquals(ProbePanelType.NEZHA_V0, fetched.detectedPanelType)
        assertEquals("raw-nezha-v0-secret", capturedAuthHeader)
        assertEquals(2, fetched.snapshots.size)

        val jp = fetched.snapshots[0]
        assertEquals("p-v0:101", jp.probeKey)
        assertEquals("JP-Tokyo-GIA", jp.name)
        assertEquals("JP", jp.countryCode)
        assertEquals("🇯🇵", jp.resolvedFlagEmoji)
        assertEquals("debian 12.7", jp.os)
        assertEquals(2, jp.cpuCores)
        assertEquals(24.5, jp.cpuPercent, 0.01)
        assertEquals(536870912L, jp.memUsedBytes)
        assertEquals(2147483648L, jp.memTotalBytes)
        assertEquals(125000L, jp.netInSpeedBps)
        assertEquals(512000L, jp.netOutSpeedBps)
        assertEquals(107374182400L, jp.monthlyTransferInBytes)
        assertEquals(214748364800L, jp.monthlyTransferOutBytes)
        assertEquals(864000L, jp.uptimeSeconds)
        assertTrue(jp.isOnline)

        val us = fetched.snapshots[1]
        assertEquals("US", us.countryCode)
        assertFalse(us.isOnline)
    }

    @Test
    fun `Nezha v0 falls back to server list endpoint when details is unavailable`() = runTest {
        val nowSec = fixedNowMillis / 1000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/server/details" -> MockResponse().setResponseCode(404)
                "/api/v1/server/list" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "code": 0,
                      "result": [
                        {
                          "id": 7,
                          "name": "HK-Lite",
                          "last_active": ${nowSec - 10}
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = ProbePanelClient(
            nowMillisProvider = { fixedNowMillis },
            allowLoopbackHttpWithCredentials = true,
        )
        val fetched = client.fetchPanelServers(
            ProbePanelConfig(
                id = "p-v0-list",
                name = "V0 List",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.NEZHA_V0,
                apiToken = "tok-v0",
            ),
        )
        assertEquals(1, fetched.snapshots.size)
        assertEquals("HK-Lite", fetched.snapshots.first().name)
        assertTrue(fetched.snapshots.first().isOnline)
    }

    @Test
    fun `Nezha v1 parses lowercase state and host fields with Bearer auth and cookie fallback`() = runTest {
        val seenHeaders = mutableListOf<Pair<String?, String?>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/server") {
                    val auth = request.getHeader("Authorization")
                    val cookie = request.getHeader("Cookie")
                    seenHeaders += auth to cookie
                    // Require cookie auth to test automatic Bearer -> nz-jwt Cookie fallback
                    if (cookie == "nz-jwt=jwt-secret-token") {
                        return MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "data": [
                                {
                                  "id": 9,
                                  "name": "HK-CN2-V1",
                                  "last_active": "2025-11-19T14:00:35Z",
                                  "geoip": { "country_code": "hk" },
                                  "public_note": "{\"planDataMod\":{\"trafficVol\":\"1TB\"}}",
                                  "host": {
                                    "platform": "ubuntu",
                                    "platform_version": "24.04",
                                    "cpu": ["Intel Xeon Gold 6133 4 Core"],
                                    "mem_total": 4294967296,
                                    "disk_total": 85899345920
                                  },
                                  "state": {
                                    "cpu": 31.8,
                                    "mem_used": 1073741824,
                                    "disk_used": 21474836480,
                                    "net_in_speed": 204800,
                                    "net_out_speed": 1048576,
                                    "net_in_transfer": 53687091200,
                                    "net_out_transfer": 107374182400,
                                    "uptime": 432000,
                                    "load_1": 0.42
                                  }
                                }
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    return MockResponse().setResponseCode(401).setBody("""{"success":false}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val client = ProbePanelClient(
            nowMillisProvider = { fixedNowMillis },
            allowLoopbackHttpWithCredentials = true,
        )
        val fetched = client.fetchPanelServers(
            ProbePanelConfig(
                id = "p-v1",
                name = "Nezha V1",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = "jwt-secret-token",
            ),
        )

        assertEquals(2, seenHeaders.size)
        assertEquals("Bearer jwt-secret-token", seenHeaders[0].first)
        assertEquals("nz-jwt=jwt-secret-token", seenHeaders[1].second)

        assertEquals(ProbePanelType.NEZHA_V1, fetched.detectedPanelType)
        assertEquals(1, fetched.snapshots.size)
        val snap = fetched.snapshots.first()
        assertEquals("HK-CN2-V1", snap.name)
        assertEquals("HK", snap.countryCode)
        assertEquals("🇭🇰", snap.resolvedFlagEmoji)
        assertEquals(31.8, snap.cpuPercent, 0.01)
        assertEquals(1073741824L, snap.memUsedBytes)
        assertEquals(4294967296L, snap.memTotalBytes)
        assertEquals(53687091200L, snap.monthlyTransferInBytes)
        assertEquals(107374182400L, snap.monthlyTransferOutBytes)
        assertEquals(1099511627776L, snap.trafficLimitBytes)
        assertEquals(4, snap.cpuCores)
        assertTrue(snap.isOnline)
    }

    @Test
    fun `Komari merges api nodes inventory with live recent telemetry`() = runTest {
        var capturedApiKey: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/nodes" -> {
                    capturedApiKey = request.getHeader("X-API-Key")
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "status": "success",
                          "data": [
                            {
                              "uuid": "komari-sg-1",
                              "name": "SG-BGP-Mini",
                              "region": "🇸🇬",
                              "os": "Debian GNU/Linux 12",
                              "cpu_name": "AMD EPYC 7B13",
                              "cpu_cores": 2,
                              "mem_total": 2147483648,
                              "disk_total": 42949672960,
                              "traffic_limit": 1099511627776
                            },
                            {
                              "uuid": "komari-de-2",
                              "name": "DE-FRA-Storage",
                              "region": "🇩🇪",
                              "os": "Alpine 3.20",
                              "cpu_cores": 1,
                              "mem_total": 1073741824,
                              "disk_total": 107374182400
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                }
                "/api/recent" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": "success",
                      "data": {
                        "online": ["komari-sg-1"],
                        "data": {
                          "komari-sg-1": {
                            "cpu": { "usage": 19.6 },
                            "ram": { "used": 671088640, "total": 2147483648 },
                            "disk": { "used": 10737418240, "total": 42949672960 },
                            "network": { "down": 300000, "up": 700000, "totalDown": 21474836480, "totalUp": 42949672960 },
                            "uptime": 259200,
                            "updated_at": "2025-11-19T14:00:40Z"
                          },
                          "komari-de-2": {
                            "cpu": { "usage": 0.0 },
                            "ram": { "used": 0 },
                            "uptime": 0
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = ProbePanelClient(
            nowMillisProvider = { fixedNowMillis },
            allowLoopbackHttpWithCredentials = true,
        )
        val fetched = client.fetchPanelServers(
            ProbePanelConfig(
                id = "p-komari",
                name = "Komari APAC",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.KOMARI,
                apiToken = "komari-api-key-123",
            ),
        )

        assertEquals("komari-api-key-123", capturedApiKey)
        assertEquals(ProbePanelType.KOMARI, fetched.detectedPanelType)
        assertEquals(2, fetched.snapshots.size)

        val sg = fetched.snapshots.first { it.remoteServerId == "komari-sg-1" }
        assertEquals("SG", sg.countryCode)
        assertEquals("🇸🇬", sg.resolvedFlagEmoji)
        assertEquals(19.6, sg.cpuPercent, 0.01)
        assertEquals(671088640L, sg.memUsedBytes)
        assertEquals(300000L, sg.netInSpeedBps)
        assertEquals(700000L, sg.netOutSpeedBps)
        assertEquals(21474836480L, sg.monthlyTransferInBytes)
        assertEquals(42949672960L, sg.monthlyTransferOutBytes)
        assertEquals(1099511627776L, sg.trafficLimitBytes)
        assertTrue(sg.isOnline)

        val de = fetched.snapshots.first { it.remoteServerId == "komari-de-2" }
        assertEquals("DE", de.countryCode)
        assertFalse(de.isOnline)
    }

    @Test
    fun `AUTO discovery detects Nezha v0, Nezha v1, and Komari accurately during testConnection`() = runTest {
        val nowSec = fixedNowMillis / 1000L

        // Case 1: AUTO -> Nezha v0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/server/details" -> MockResponse().setResponseCode(200).setBody(
                    """{"code":0,"result":[{"id":1,"name":"V0-Node","last_active":${nowSec - 5},"host":{"MemTotal":1024},"status":{"CPU":5.0,"MemUsed":256,"Uptime":100}}]}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = ProbePanelClient(nowMillisProvider = { fixedNowMillis })
        val v0Res = client.testConnection(
            ProbePanelConfig(id = "auto1", name = "Auto", baseUrl = server.url("/").toString(), panelType = ProbePanelType.AUTO),
        )
        assertTrue(v0Res.success)
        assertEquals(ProbePanelType.NEZHA_V0, v0Res.detectedPanelType)
        assertEquals(1, v0Res.serverCount)
        assertEquals(1, v0Res.onlineCount)

        // Case 2: AUTO -> Nezha v1
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/server" -> MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":[{"id":2,"name":"V1-Node","last_active":"2025-11-19T14:00:40Z","host":{"mem_total":2048},"state":{"cpu":10.0,"mem_used":512,"uptime":500}}]}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val v1Res = client.testConnection(
            ProbePanelConfig(id = "auto2", name = "Auto", baseUrl = server.url("/").toString(), panelType = ProbePanelType.AUTO),
        )
        assertTrue(v1Res.success)
        assertEquals(ProbePanelType.NEZHA_V1, v1Res.detectedPanelType)
        assertEquals(1, v1Res.serverCount)

        // Case 3: AUTO -> Komari
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/nodes" -> MockResponse().setResponseCode(200).setBody(
                    """{"status":"success","data":[{"uuid":"k-1","name":"Komari-Node","region":"🇯🇵","cpu_cores":2,"mem_total":2048}]}""",
                )
                "/api/client" -> MockResponse().setResponseCode(200).setBody(
                    """{"status":"success","data":{"k-1":{"cpu":{"usage":8.0},"ram":{"used":256},"online":true,"uptime":300}}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val komariRes = client.testConnection(
            ProbePanelConfig(id = "auto3", name = "Auto", baseUrl = server.url("/").toString(), panelType = ProbePanelType.AUTO),
        )
        assertTrue(komariRes.success)
        assertEquals(ProbePanelType.KOMARI, komariRes.detectedPanelType)
        assertEquals(1, komariRes.onlineCount)
        assertNotNull(komariRes.sampleServers.firstOrNull())
    }

    @Test
    fun `normalizeBaseUrl trims trailing slashes and pasted API endpoints`() {
        assertEquals(
            "https://probe.example.com",
            ProbePanelClient.normalizeBaseUrl("probe.example.com/"),
        )
        assertEquals(
            "https://probe.example.com",
            ProbePanelClient.normalizeBaseUrl("https://probe.example.com/api/v1/server"),
        )
        assertEquals(
            "http://127.0.0.1:8008",
            ProbePanelClient.normalizeBaseUrl("http://127.0.0.1:8008/api/nodes/"),
        )
    }

    @Test
    fun `default ProbePanelClient rejects http baseUrl when apiToken is non-blank before making any network call`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"success","data":[]}"""))
        val prodClient = ProbePanelClient(nowMillisProvider = { fixedNowMillis })

        val rejectedConfigs = listOf(
            ProbePanelConfig(
                id = "http-ext-komari",
                name = "Insecure Komari",
                baseUrl = "http://probe.example.com:8008",
                panelType = ProbePanelType.KOMARI,
                apiToken = "komari-secret-key",
            ),
            ProbePanelConfig(
                id = "http-loopback-v0",
                name = "Loopback HTTP V0",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.NEZHA_V0,
                apiToken = "nezha-v0-secret",
            ),
            ProbePanelConfig(
                id = "http-loopback-v1-cookie",
                name = "Loopback HTTP V1 Cookie",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = "nz-jwt=cookie-secret",
            ),
            ProbePanelConfig(
                id = "http-loopback-auto",
                name = "Loopback HTTP Auto",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.AUTO,
                apiToken = "auto-secret-token",
            ),
            ProbePanelConfig(
                id = "crlf-token",
                name = "Header Injection",
                baseUrl = "https://probe.example.com",
                panelType = ProbePanelType.KOMARI,
                apiToken = "bad\r\nX-Injected: 1",
            ),
            ProbePanelConfig(
                id = "userinfo-url",
                name = "Embedded Userinfo",
                baseUrl = "https://admin:pass@probe.example.com",
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = "jwt-tok",
            ),
        )

        for (cfg in rejectedConfigs) {
            try {
                prodClient.fetchPanelServers(cfg)
                fail("Expected ProbeCredentialBoundaryException for ${cfg.id}")
            } catch (e: ProbeCredentialBoundaryException) {
                assertTrue("Expected Chinese security error for ${cfg.id}, got: ${e.message}", e.message!!.contains("安全校验失败"))
            }

            val connResult = prodClient.testConnection(cfg)
            assertFalse("testConnection must fail for ${cfg.id}", connResult.success)
            assertTrue(connResult.errorMessage.orEmpty().contains("安全校验失败"))
        }

        // Zero bytes may be transmitted over plaintext HTTP when credentials are configured
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ProbePanelClient follows same-origin https redirects while preserving auth headers and stripping foreign cookies`() = runTest {
        MockWebServer().use { httpsServer ->
            httpsServer.useHttps(tlsFixture.sslContext.socketFactory, false)
            httpsServer.start()

            val recordedRequests = mutableListOf<RecordedRequest>()
            httpsServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recordedRequests += request
                    return when (request.path) {
                        "/api/nodes" -> MockResponse()
                            .setResponseCode(307)
                            .addHeader("Location", "/edge/api/nodes")
                            .addHeader("Set-Cookie", "panel_track=should_not_persist; Path=/")
                        "/edge/api/nodes" -> MockResponse()
                            .setResponseCode(302)
                            .addHeader("Location", "https://probe.example.com:${httpsServer.port}/v2/api/nodes")
                        "/v2/api/nodes" -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"status":"success","data":[{"uuid":"n-1","name":"HTTPS-Redirect-Node","region":"🇯🇵","cpu_cores":2,"mem_total":2048,"online":true,"uptime":120}]}""",
                            )
                        "/api/recent", "/api/client" -> MockResponse().setResponseCode(404)
                        "/api/v1/server" -> MockResponse()
                            .setResponseCode(301)
                            .addHeader("Location", "/v2/api/v1/server")
                        "/v2/api/v1/server" -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"success":true,"data":[{"id":11,"name":"V1-Cookie-Redirect","last_active":"2025-11-19T14:00:40Z","host":{"mem_total":1024},"state":{"cpu":8.0,"mem_used":256,"uptime":300}}]}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            // Pollute base OkHttpClient with foreign cookies, foreign interceptor, and auto-redirects
            val pollutedHttpsClient = buildTlsOkHttpClient()
                .newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(
                    object : CookieJar {
                        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                        override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                            Cookie.Builder()
                                .name("nodeseek_session")
                                .value("must_never_leak")
                                .domain(url.host)
                                .path("/")
                                .build(),
                        )
                    },
                )
                .addInterceptor(
                    Interceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("X-Foreign-Leak", "leaked")
                                .header("Cookie", "injected=1")
                                .build(),
                        )
                    },
                )
                .build()

            val client = ProbePanelClient(
                httpClient = pollutedHttpsClient,
                nowMillisProvider = { fixedNowMillis },
            )

            // 1. Komari with Authorization + X-API-Key across 307 + 302 same-origin HTTPS hops
            val komariConfig = ProbePanelConfig(
                id = "https-komari",
                name = "HTTPS Komari",
                baseUrl = "https://probe.example.com:${httpsServer.port}",
                panelType = ProbePanelType.KOMARI,
                apiToken = "komari-sec-999",
            )
            val komariConn = client.testConnection(komariConfig)
            assertTrue(komariConn.success)
            assertEquals(1, komariConn.serverCount)

            val nodeHops = recordedRequests.take(3)
            assertEquals(listOf("/api/nodes", "/edge/api/nodes", "/v2/api/nodes"), nodeHops.map { it.path })
            for (hopReq in nodeHops) {
                assertEquals("Bearer komari-sec-999", hopReq.getHeader("Authorization"))
                assertEquals("komari-sec-999", hopReq.getHeader("X-API-Key"))
                assertNull("Foreign CookieJar/Set-Cookie must never attach", hopReq.getHeader("Cookie"))
                assertNull("Foreign interceptor must be stripped", hopReq.getHeader("X-Foreign-Leak"))
            }

            // 2. Nezha v1 with explicit Cookie credential across 301 same-origin HTTPS hop
            recordedRequests.clear()
            val v1Fetched = client.fetchPanelServers(
                ProbePanelConfig(
                    id = "https-v1",
                    name = "HTTPS Nezha V1",
                    baseUrl = "https://probe.example.com:${httpsServer.port}",
                    panelType = ProbePanelType.NEZHA_V1,
                    apiToken = "nz-jwt=my-jwt-cookie",
                ),
            )
            assertEquals(1, v1Fetched.snapshots.size)
            assertEquals(listOf("/api/v1/server", "/v2/api/v1/server"), recordedRequests.map { it.path })
            for (hopReq in recordedRequests) {
                assertEquals("nz-jwt=my-jwt-cookie", hopReq.getHeader("Cookie"))
                assertNull(hopReq.getHeader("X-Foreign-Leak"))
            }
        }
    }

    @Test
    fun `ProbePanelClient aborts immediately without sending headers on cross-host cross-port or https-to-http redirects`() = runTest {
        MockWebServer().use { probeHttpsServer ->
            MockWebServer().use { evilHttpsServer ->
                probeHttpsServer.useHttps(tlsFixture.sslContext.socketFactory, false)
                evilHttpsServer.useHttps(tlsFixture.sslContext.socketFactory, false)
                probeHttpsServer.start()
                evilHttpsServer.start()

                evilHttpsServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"success","data":[]}"""))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"success","data":[]}"""))

                var nextRedirectLocation = ""
                var nextRedirectStatus = 302
                probeHttpsServer.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/loop") {
                            return MockResponse().setResponseCode(302).addHeader("Location", "/loop")
                        }
                        return MockResponse()
                            .setResponseCode(nextRedirectStatus)
                            .addHeader("Location", nextRedirectLocation)
                    }
                }

                val client = ProbePanelClient(
                    httpClient = buildTlsOkHttpClient(),
                    nowMillisProvider = { fixedNowMillis },
                )
                val probeOrigin = "https://probe.example.com:${probeHttpsServer.port}"

                // 1. Cross-host HTTPS redirect (302 -> https://evil.example) in AUTO mode must abort on hop 1
                nextRedirectStatus = 302
                nextRedirectLocation = "https://evil.example:${evilHttpsServer.port}/api/nodes"
                val beforeProbeCount = probeHttpsServer.requestCount
                try {
                    client.fetchPanelServers(
                        ProbePanelConfig(
                            id = "cross-host",
                            name = "Cross Host",
                            baseUrl = probeOrigin,
                            panelType = ProbePanelType.AUTO,
                            apiToken = "secret-token-1",
                        ),
                    )
                    fail("Expected ProbeCredentialBoundaryException on cross-host redirect")
                } catch (e: ProbeCredentialBoundaryException) {
                    assertTrue(e.message!!.contains("跨源"))
                }
                assertEquals("AUTO discovery must abort immediately on security boundary error", beforeProbeCount + 1, probeHttpsServer.requestCount)
                assertEquals("Evil host must receive ZERO requests", 0, evilHttpsServer.requestCount)

                // 2. Protocol-relative cross-host redirect (307 -> //evil.example:port/steal)
                nextRedirectStatus = 307
                nextRedirectLocation = "//evil.example:${evilHttpsServer.port}/steal"
                val protoRelConn = client.testConnection(
                    ProbePanelConfig(
                        id = "proto-rel",
                        name = "Proto Relative",
                        baseUrl = probeOrigin,
                        panelType = ProbePanelType.KOMARI,
                        apiToken = "secret-token-2",
                    ),
                )
                assertFalse(protoRelConn.success)
                assertEquals(0, evilHttpsServer.requestCount)

                // 3. Same-host cross-port HTTPS redirect (https://probe.example.com:port1 -> https://probe.example.com:port2)
                nextRedirectStatus = 307
                nextRedirectLocation = "https://probe.example.com:${evilHttpsServer.port}/api/v1/server"
                try {
                    client.fetchPanelServers(
                        ProbePanelConfig(
                            id = "cross-port",
                            name = "Cross Port",
                            baseUrl = probeOrigin,
                            panelType = ProbePanelType.NEZHA_V1,
                            apiToken = "nz-jwt=secret-cookie",
                        ),
                    )
                    fail("Expected ProbeCredentialBoundaryException on cross-port redirect")
                } catch (_: ProbeCredentialBoundaryException) {
                    // Expected
                }
                assertEquals("Different port must receive ZERO requests", 0, evilHttpsServer.requestCount)

                // 4. HTTPS -> HTTP scheme downgrade redirect (with credentials AND unauthenticated!)
                nextRedirectStatus = 301
                nextRedirectLocation = "http://probe.example.com:${server.port}/api/nodes"
                try {
                    client.fetchPanelServers(
                        ProbePanelConfig(
                            id = "downgrade-auth",
                            name = "Downgrade Auth",
                            baseUrl = probeOrigin,
                            panelType = ProbePanelType.KOMARI,
                            apiToken = "secret-token-3",
                        ),
                    )
                    fail("Expected ProbeCredentialBoundaryException on HTTPS -> HTTP downgrade")
                } catch (e: ProbeCredentialBoundaryException) {
                    assertTrue(e.message!!.contains("降级"))
                }
                try {
                    client.fetchPanelServers(
                        ProbePanelConfig(
                            id = "downgrade-public",
                            name = "Downgrade Public",
                            baseUrl = probeOrigin,
                            panelType = ProbePanelType.KOMARI,
                            apiToken = null,
                        ),
                    )
                    fail("Expected ProbeCredentialBoundaryException on unauthenticated HTTPS -> HTTP downgrade")
                } catch (e: ProbeCredentialBoundaryException) {
                    assertTrue(e.message!!.contains("降级"))
                }
                assertEquals("Plaintext HTTP server must receive ZERO downgraded requests", 0, server.requestCount)

                // 5. Bounded redirect loop protection
                nextRedirectStatus = 302
                nextRedirectLocation = "$probeOrigin/loop"
                try {
                    client.fetchPanelServers(
                        ProbePanelConfig(
                            id = "redirect-loop",
                            name = "Loop",
                            baseUrl = probeOrigin,
                            panelType = ProbePanelType.KOMARI,
                            apiToken = "secret-token-4",
                        ),
                    )
                    fail("Expected ProbeCredentialBoundaryException on redirect loop")
                } catch (e: ProbeCredentialBoundaryException) {
                    assertTrue(e.message!!.contains("重定向次数过多"))
                }
            }
        }
    }

    @Test
    fun `Komari fetches real rpc2 common getNodesLatestStatus flat metrics and ignores misleading static updated_at`() = runTest {
        var capturedRpcBody: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/nodes" && request.method == "GET" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "code": 200,
                      "message": "success",
                      "data": [
                        {
                          "uuid": "komari-tyo-online",
                          "name": "Tokyo-GIA",
                          "region": "JP",
                          "os": "Ubuntu 24.04",
                          "cpu_name": "AMD EPYC 7763",
                          "cpu_cores": 4,
                          "mem_total": 4294967296,
                          "disk_total": 53687091200,
                          "traffic_limit": 1099511627776,
                          "updated_at": "2024-01-01T00:00:00Z"
                        },
                        {
                          "uuid": "komari-lax-offline",
                          "name": "LAX-Offline-JustEditedInDb",
                          "region": "US",
                          "os": "Debian 12",
                          "cpu_cores": 2,
                          "mem_total": 2147483648,
                          "disk_total": 21474836480,
                          "updated_at": "2025-11-19T14:00:44Z"
                        },
                        {
                          "uuid": "komari-hk-never-reported",
                          "name": "HK-NeverConnected",
                          "region": "HK",
                          "os": "Alpine",
                          "cpu_cores": 1,
                          "mem_total": 1073741824,
                          "disk_total": 10737418240,
                          "updated_at": "2025-11-19T14:00:44Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                request.path == "/api/rpc2" && request.method == "POST" -> {
                    capturedRpcBody = request.body.readUtf8()
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "result": {
                            "komari-tyo-online": {
                              "client": "komari-tyo-online",
                              "time": "2025-11-19T14:00:43Z",
                              "cpu": 37.4,
                              "ram": 1610612736,
                              "ram_total": 4294967296,
                              "disk": 12884901888,
                              "disk_total": 53687091200,
                              "net_in": 850000,
                              "net_out": 420000,
                              "net_total_down": 64424509440,
                              "net_total_up": 32212254720,
                              "load": 0.68,
                              "online": true,
                              "uptime": 604800
                            },
                            "komari-lax-offline": {
                              "client": "komari-lax-offline",
                              "time": "2025-11-18T10:00:00Z",
                              "cpu": 82.0,
                              "ram": 1073741824,
                              "ram_total": 2147483648,
                              "disk": 5368709120,
                              "disk_total": 21474836480,
                              "net_in": 999999,
                              "net_out": 888888,
                              "online": false,
                              "uptime": 1200
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = ProbePanelClient(
            nowMillisProvider = { fixedNowMillis },
            allowLoopbackHttpWithCredentials = true,
        )
        val fetched = client.fetchPanelServers(
            ProbePanelConfig(
                id = "p-komari-rpc2",
                name = "Komari RPC2",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.AUTO,
            ),
        )

        assertEquals(ProbePanelType.KOMARI, fetched.detectedPanelType)
        assertNotNull(capturedRpcBody)
        assertTrue(capturedRpcBody!!.contains("common:getNodesLatestStatus"))
        assertEquals(3, fetched.snapshots.size)
        assertEquals(1, fetched.snapshots.count { it.isOnline })
        assertEquals(2, fetched.snapshots.count { !it.isOnline })

        val tyo = fetched.snapshots.first { it.remoteServerId == "komari-tyo-online" }
        assertTrue(tyo.isOnline)
        assertEquals(37.4, tyo.cpuPercent, 0.01)
        assertEquals(1610612736L, tyo.memUsedBytes)
        assertEquals(4294967296L, tyo.memTotalBytes)
        assertEquals(12884901888L, tyo.diskUsedBytes)
        assertEquals(53687091200L, tyo.diskTotalBytes)
        assertEquals(850000L, tyo.netInSpeedBps)
        assertEquals(420000L, tyo.netOutSpeedBps)
        assertEquals(64424509440L, tyo.monthlyTransferInBytes)
        assertEquals(32212254720L, tyo.monthlyTransferOutBytes)
        assertEquals(0.68, tyo.load1 ?: 0.0, 0.001)
        assertEquals(604800L, tyo.uptimeSeconds)

        val lax = fetched.snapshots.first { it.remoteServerId == "komari-lax-offline" }
        assertFalse(lax.isOnline)
        assertEquals(0.0, lax.cpuPercent, 0.001)
        assertEquals(0L, lax.netInSpeedBps)
        assertEquals(0L, lax.netOutSpeedBps)

        val hk = fetched.snapshots.first { it.remoteServerId == "komari-hk-never-reported" }
        assertFalse(hk.isOnline)
    }

    @Test
    fun `Komari falls back to per-node api recent uuid ring buffer with PascalCase network fields when rpc2 and ws are unavailable`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/nodes" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": "success",
                      "data": [
                        {
                          "uuid": "uuid-active",
                          "name": "Active-Recent-Node",
                          "region": "SG",
                          "mem_total": 2147483648,
                          "disk_total": 32212254720,
                          "updated_at": "2024-05-01T00:00:00Z"
                        },
                        {
                          "uuid": "uuid-empty-window",
                          "name": "Offline-Recent-Node",
                          "region": "DE",
                          "mem_total": 1073741824,
                          "disk_total": 10737418240,
                          "updated_at": "2025-11-19T14:00:44Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                "/api/recent/uuid-active" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": "success",
                      "data": [
                        {
                          "cpu": { "Usage": 22.5 },
                          "ram": { "Used": 536870912, "Total": 2147483648 },
                          "disk": { "Used": 8589934592, "Total": 32212254720 },
                          "network": { "Down": 128000, "Up": 64000, "TotalDown": 1073741824, "TotalUp": 536870912 },
                          "load": { "Load1": 0.31 },
                          "uptime": 7200,
                          "updated_at": "2025-11-19T14:00:40Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                "/api/recent/uuid-empty-window" -> MockResponse().setResponseCode(200).setBody(
                    """{"status":"success","data":[]}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = ProbePanelClient(
            nowMillisProvider = { fixedNowMillis },
            allowLoopbackHttpWithCredentials = true,
        )
        val fetched = client.fetchPanelServers(
            ProbePanelConfig(
                id = "p-komari-per-node",
                name = "Komari Legacy HTTP",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.KOMARI,
            ),
        )

        assertEquals(2, fetched.snapshots.size)
        val active = fetched.snapshots.first { it.remoteServerId == "uuid-active" }
        assertTrue(active.isOnline)
        assertEquals(22.5, active.cpuPercent, 0.01)
        assertEquals(536870912L, active.memUsedBytes)
        assertEquals(8589934592L, active.diskUsedBytes)
        assertEquals(128000L, active.netInSpeedBps)
        assertEquals(64000L, active.netOutSpeedBps)
        assertEquals(0.31, active.load1 ?: 0.0, 0.001)

        val offline = fetched.snapshots.first { it.remoteServerId == "uuid-empty-window" }
        assertFalse(offline.isOnline)
    }

    companion object {
        private lateinit var tlsTempDir: File
        private lateinit var tlsFixture: ProbeTestTlsFixture

        private val LOOPBACK_TEST_HOSTS = setOf(
            "probe.example.com",
            "evil.example",
            "localhost",
            "127.0.0.1",
        )

        private data class ProbeTestTlsFixture(
            val sslContext: SSLContext,
            val trustManager: X509TrustManager,
        )

        @JvmStatic
        @BeforeClass
        fun setUpTls() {
            tlsTempDir = Files.createTempDirectory("probe_panel_tls_test").toFile()
            val keystoreFile = File(tlsTempDir, "probe_test.p12")
            val passwordChars = "changeit".toCharArray()
            val keytoolBin = File(System.getProperty("java.home"), "bin/keytool")
                .takeIf { it.isFile && it.canExecute() }
                ?.absolutePath
                ?: "keytool"
            val proc = ProcessBuilder(
                keytoolBin,
                "-genkeypair",
                "-alias",
                "probetest",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-sigalg",
                "SHA256withECDSA",
                "-validity",
                "7",
                "-dname",
                "CN=probe.example.com",
                "-ext",
                "SAN=DNS:probe.example.com,DNS:evil.example,DNS:localhost,IP:127.0.0.1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystoreFile.absolutePath,
                "-storepass",
                String(passwordChars),
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "keytool failed generating test cert: $out" }

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                keystoreFile.inputStream().use { load(it, passwordChars) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, passwordChars)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
            }
            tlsFixture = ProbeTestTlsFixture(sslContext = sslContext, trustManager = trustManager)
        }

        @JvmStatic
        @AfterClass
        fun tearDownTls() {
            if (::tlsTempDir.isInitialized) {
                tlsTempDir.deleteRecursively()
            }
        }

        private fun buildTlsOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .sslSocketFactory(tlsFixture.sslContext.socketFactory, tlsFixture.trustManager)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (hostname.lowercase() in LOOPBACK_TEST_HOSTS) {
                            listOf(InetAddress.getByName("127.0.0.1"))
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                },
            )
            .build()
    }
}
