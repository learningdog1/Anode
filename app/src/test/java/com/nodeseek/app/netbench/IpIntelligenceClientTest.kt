package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IpIntelligenceClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildClient(): IpIntelligenceClient {
        val base = server.url("/").toString().trimEnd('/')
        return IpIntelligenceClient(
            httpClient = OkHttpClient(),
            endpoints = IpIntelligenceEndpoints(
                ipWhoIsTargetTemplate = "$base/ipwhois/{ip}",
                ipInfoTargetTemplate = "$base/ipinfo/{ip}",
                ipApiTargetTemplate = "$base/ipapi/{ip}",
                ripeStatWhoisTemplate = "$base/ripestat?resource={ip}",
                rdapTemplates = listOf("$base/rdap/{ip}"),
                clientSelfUrls = listOf(
                    "ipwho.is" to "$base/self-ipwhois",
                    "ipinfo.io" to "$base/self-ipinfo",
                    "ipapi.co" to "$base/self-ipapi",
                ),
            ),
            ioDispatcher = Dispatchers.IO,
        )
    }

    @Test
    fun `classifies NATIVE IP when GeoIP country matches RIPEstat WHOIS registration country`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/ipwhois/") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "ip": "43.249.10.20",
                      "country": "Hong Kong",
                      "country_code": "HK",
                      "region": "Hong Kong",
                      "city": "Central",
                      "connection": {
                        "asn": 137872,
                        "org": "DMIT Cloud Services",
                        "isp": "DMIT Inc."
                      }
                    }
                    """.trimIndent(),
                )
                request.path!!.startsWith("/ripestat") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": {
                        "authorities": ["apnic"],
                        "records": [
                          [
                            {"key": "inetnum", "value": "43.249.10.0 - 43.249.10.255"},
                            {"key": "country", "value": "HK"},
                            {"key": "descr", "value": "DMIT Cloud Hong Kong"}
                          ]
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val report = buildClient().lookupTargetIp("43.249.10.20")

        assertEquals("43.249.10.20", report.ip)
        assertEquals(IpFamily.IPV4, report.ipFamily)
        assertEquals("HK", report.countryCode)
        assertEquals("HK", report.rirCountryCode)
        assertEquals("APNIC", report.rirAuthority)
        assertEquals("AS137872", report.asn)
        assertEquals(IpOriginType.NATIVE, report.originType)
        assertEquals(IpUsageType.HOSTING_DATACENTER, report.usageType)
        assertTrue(report.originEvidence.contains("原生 IP (注册地与归属地一致 HK"))
        assertEquals("ipwho.is", report.geoSource)
        assertEquals("RIPEstat", report.whoisSource)
    }

    @Test
    fun `classifies BROADCAST IP when WHOIS registered in US but GeoIP located in HK`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/ipwhois/") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "ip": "154.17.88.99",
                      "country": "Hong Kong",
                      "country_code": "HK",
                      "region": "Hong Kong",
                      "city": "Hong Kong",
                      "connection": {
                        "asn": 49304,
                        "org": "xTom Hong Kong",
                        "isp": "xTom"
                      }
                    }
                    """.trimIndent(),
                )
                request.path!!.startsWith("/ripestat") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": {
                        "authorities": ["arin"],
                        "records": [
                          [
                            {"key": "NetRange", "value": "154.17.88.0 - 154.17.88.255"},
                            {"key": "Country", "value": "US"}
                          ]
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val report = buildClient().lookupTargetIp("154.17.88.99")

        assertEquals(IpOriginType.BROADCAST, report.originType)
        assertEquals("US", report.rirCountryCode)
        assertEquals("HK", report.countryCode)
        assertTrue(
            "Expected broadcast evidence US -> HK, got: ${report.originEvidence}",
            report.originEvidence.contains("广播 IP (注册地 US -> 播放在 HK"),
        )
    }

    @Test
    fun `falls back across geoip and rdap sources and detects Anycast`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                // Force ipwho.is failure to verify ipinfo widget fallback
                request.path!!.startsWith("/ipwhois/") -> MockResponse().setResponseCode(429)
                request.path!!.startsWith("/ipinfo/") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": {
                        "ip": "1.1.1.1",
                        "country": "AU",
                        "region": "Queensland",
                        "city": "Brisbane",
                        "anycast": true,
                        "asn": {"asn": "AS13335", "name": "Cloudflare, Inc.", "type": "hosting"},
                        "privacy": {"hosting": true}
                      }
                    }
                    """.trimIndent(),
                )
                // Force RIPEstat failure to verify RDAP fallback
                request.path!!.startsWith("/ripestat") -> MockResponse().setResponseCode(500)
                request.path!!.startsWith("/rdap/") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "country": "AU",
                      "name": "APNIC-LABS",
                      "port43": "whois.apnic.net"
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val report = buildClient().lookupTargetIp("1.1.1.1")

        assertEquals("ipinfo.io", report.geoSource)
        assertEquals("RDAP", report.whoisSource)
        assertEquals("APNIC", report.rirAuthority)
        assertEquals("AS13335", report.asn)
        assertTrue(report.isAnycast)
        assertEquals(IpOriginType.ANYCAST, report.originType)
        assertEquals(IpUsageType.ANYCAST, report.usageType)
    }

    @Test
    fun `falls back to third geoip provider ipapi and rdap for broadcast classification`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/ipwhois/") -> MockResponse().setResponseCode(500)
                request.path!!.startsWith("/ipinfo/") -> MockResponse().setResponseCode(429)
                request.path!!.startsWith("/ipapi/") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "ip": "45.76.200.10",
                      "country_code": "JP",
                      "country_name": "Japan",
                      "region": "Tokyo",
                      "city": "Tokyo",
                      "asn": "AS20473",
                      "org": "The Constant Company, LLC"
                    }
                    """.trimIndent(),
                )
                request.path!!.startsWith("/ripestat") -> MockResponse().setResponseCode(503)
                request.path!!.startsWith("/rdap/") -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "country": "US",
                      "name": "CHOOPA",
                      "port43": "whois.arin.net"
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val report = buildClient().lookupTargetIp("45.76.200.10")

        assertEquals("ipapi.co", report.geoSource)
        assertEquals("RDAP", report.whoisSource)
        assertEquals("ARIN", report.rirAuthority)
        assertEquals(IpOriginType.BROADCAST, report.originType)
        assertEquals(IpUsageType.HOSTING_DATACENTER, report.usageType)
        assertTrue(report.originEvidence.contains("广播 IP (注册地 US -> 播放在 JP"))
    }

    @Test
    fun `detects local client egress carriers and masks client IP`() = runBlocking {
        // Test China Telecom via ipwho.is
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/self-ipwhois" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "ip": "114.240.88.123",
                      "country_code": "CN",
                      "region": "Beijing",
                      "connection": {
                        "asn": 4134,
                        "isp": "ChinaNet",
                        "org": "China Telecom"
                      }
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val telecom = buildClient().lookupLocalClientNetwork()
        assertEquals(ClientCarrier.CHINA_TELECOM, telecom.carrier)
        assertEquals("中国电信", telecom.carrierDisplayName)
        assertEquals(IpFamily.IPV4, telecom.ipFamily)
        assertEquals("114.240.*.*", telecom.maskedIp)
        assertEquals("114.240.88.123", telecom.rawIp)
        assertFalse(telecom.maskedIp!!.contains("88.123"))

        // Test fallback to ipinfo.io for China Unicom (AS4837)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/self-ipwhois" -> MockResponse().setResponseCode(500)
                "/self-ipinfo" -> MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "ip": "2408:8207:1234::99",
                      "country": "CN",
                      "org": "AS4837 CHINA UNICOM China169 Backbone"
                    }
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val unicom = buildClient().lookupLocalClientNetwork()
        assertEquals(ClientCarrier.CHINA_UNICOM, unicom.carrier)
        assertEquals("中国联通", unicom.carrierDisplayName)
        assertEquals(IpFamily.IPV6, unicom.ipFamily)
        assertEquals("2408:8207::****", unicom.maskedIp)

        // Test China Mobile (AS9808), China Broadnet (AS139774), and Overseas (US)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/self-ipwhois" -> MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"ip":"120.204.10.5","country_code":"CN","connection":{"asn":9808,"isp":"China Mobile","org":"CMNET"}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val mobile = buildClient().lookupLocalClientNetwork()
        assertEquals(ClientCarrier.CHINA_MOBILE, mobile.carrier)
        assertEquals("中国移动", mobile.carrierDisplayName)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/self-ipwhois" -> MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"ip":"103.80.12.9","country_code":"CN","connection":{"asn":139774,"isp":"China Broadcasting Network","org":"CBN"}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val broadnet = buildClient().lookupLocalClientNetwork()
        assertEquals(ClientCarrier.CHINA_BROADNET, broadnet.carrier)
        assertEquals("中国广电", broadnet.carrierDisplayName)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/self-ipwhois" -> MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"ip":"172.58.11.22","country_code":"US","connection":{"asn":21928,"isp":"T-Mobile USA","org":"T-Mobile"}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val overseas = buildClient().lookupLocalClientNetwork()
        assertEquals(ClientCarrier.OVERSEAS, overseas.carrier)
        assertTrue(overseas.carrierDisplayName.startsWith("海外网络"))
    }
}
