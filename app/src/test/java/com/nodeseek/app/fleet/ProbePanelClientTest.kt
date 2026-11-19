package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.ProbePanelClient
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

        val client = ProbePanelClient(nowMillisProvider = { fixedNowMillis })
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

        val client = ProbePanelClient(nowMillisProvider = { fixedNowMillis })
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

        val client = ProbePanelClient(nowMillisProvider = { fixedNowMillis })
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

        val client = ProbePanelClient(nowMillisProvider = { fixedNowMillis })
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
}
