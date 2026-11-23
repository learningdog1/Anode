package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.DirectBindFailureReason
import com.nodeseek.app.domain.netbench.EgressRouteKind
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

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

    @Test
    fun `detectClientEgress binds real underlying network via socketFactory and dns and exposes both real and proxy IPs`() = runBlocking {
        val directServer = MockWebServer()
        directServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setBody(
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
        }
        directServer.start()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "success": true,
                      "ip": "45.76.200.10",
                      "country_code": "JP",
                      "region": "Tokyo",
                      "connection": {"asn": 20473, "isp": "The Constant Company", "org": "Vultr"}
                    }
                    """.trimIndent(),
                )
        }

        val directDnsCalls = AtomicInteger(0)
        val directSocketCalls = AtomicInteger(0)

        val directChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = object : SocketFactory() {
                override fun createSocket(): Socket {
                    directSocketCalls.incrementAndGet()
                    return object : Socket() {
                        override fun connect(endpoint: SocketAddress?, timeout: Int) {
                            val inet = endpoint as? InetSocketAddress
                            val redirected = if (inet != null && inet.port == server.port) {
                                InetSocketAddress(InetAddress.getByName("127.0.0.1"), directServer.port)
                            } else {
                                endpoint
                            }
                            super.connect(redirected, timeout)
                        }
                    }
                }
                override fun createSocket(host: String?, port: Int) = error("unused")
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = error("unused")
                override fun createSocket(host: InetAddress?, port: Int) = error("unused")
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = error("unused")
            }

            override fun createSocket(): Socket = socketFactory.createSocket()

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
            override fun createSocket(): Socket = Socket()
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("127.0.0.1"))
        }

        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(proxyChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        NetworkDescriptor(directChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, isForeground = true, transports = setOf(NetBenchTransportKind.WIFI)),
                    ),
                )
                return ResolvedNetworkBinding(plan, directChannel, proxyChannel)
            }
        }

        try {
            val client = IpIntelligenceClient(
                httpClient = OkHttpClient(),
                endpoints = IpIntelligenceEndpoints(
                    clientSelfUrls = listOf("ipwho.is" to "http://localhost:${server.port}/self"),
                ),
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
            )

            val snapshot = client.detectClientEgress()

            assertTrue(snapshot.proxyDetected)
            assertTrue(snapshot.isVpnActive)
            assertTrue(snapshot.hasDualExitIps)
            assertFalse(snapshot.isShowingProxyFallback)
            assertFalse(snapshot.directBindFailed)

            assertTrue("Expected direct channel DNS to be called", directDnsCalls.get() > 0)
            assertTrue("Expected direct channel socketFactory to be called", directSocketCalls.get() > 0)

            assertNotNull(snapshot.directNetwork)
            assertEquals(ClientCarrier.CHINA_TELECOM, snapshot.directNetwork!!.carrier)
            assertEquals("114.240.88.123", snapshot.directNetwork!!.rawIp)
            assertEquals("114.240.*.*", snapshot.directNetwork!!.maskedIp)
            assertEquals(EgressRouteKind.DIRECT, snapshot.directNetwork!!.routeKind)
            assertEquals("Wi-Fi", snapshot.directNetwork!!.transportLabel)

            assertNotNull(snapshot.proxyNetwork)
            assertEquals(ClientCarrier.OVERSEAS, snapshot.proxyNetwork!!.carrier)
            assertEquals("45.76.200.10", snapshot.proxyNetwork!!.rawIp)
            assertEquals(EgressRouteKind.PROXY, snapshot.proxyNetwork!!.routeKind)

            // lookupLocalClientNetwork should prefer the real underlying ISP network
            val primary = client.lookupLocalClientNetwork()
            assertEquals(ClientCarrier.CHINA_TELECOM, primary.carrier)
        } finally {
            directServer.shutdown()
        }
    }

    @Test
    fun `detectClientEgress falls back to proxy exit IP with diagnostic when VPN denies bypass`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "success": true,
                      "ip": "45.76.200.10",
                      "country_code": "JP",
                      "region": "Tokyo",
                      "connection": {"asn": 20473, "isp": "The Constant Company", "org": "Vultr"}
                    }
                    """.trimIndent(),
                )
        }

        val blockedDirectChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = object : SocketFactory() {
                override fun createSocket(): Socket {
                    throw SocketException("bind failed: EPERM (Operation not permitted)")
                }
                override fun createSocket(host: String?, port: Int) = error("unused")
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = error("unused")
                override fun createSocket(host: InetAddress?, port: Int) = error("unused")
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = error("unused")
            }

            override fun createSocket(): Socket = socketFactory.createSocket()
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("127.0.0.1"))
        }

        val proxyChannel = object : NetBenchSocketChannel {
            override val networkId: String = "vpn-200"
            override val transportLabel: String = "VPN 代理"
            override val isVpn: Boolean = true
            override val isDirectPhysical: Boolean = false
            override val bypassSystemHttpProxy: Boolean = false
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("127.0.0.1"))
        }

        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(proxyChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        NetworkDescriptor(blockedDirectChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI)),
                    ),
                )
                return ResolvedNetworkBinding(plan, blockedDirectChannel, proxyChannel)
            }
        }

        val client = IpIntelligenceClient(
            httpClient = OkHttpClient(),
            endpoints = IpIntelligenceEndpoints(
                clientSelfUrls = listOf("ipwho.is" to server.url("/self").toString()),
            ),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
        )

        val snapshot = client.detectClientEgress()

        assertTrue(snapshot.proxyDetected)
        assertTrue(snapshot.directBindFailed)
        assertTrue(snapshot.isShowingProxyFallback)
        assertFalse(snapshot.hasDualExitIps)
        assertNull(snapshot.directNetwork)
        assertEquals(DirectBindFailureReason.VPN_LOCKDOWN_OR_BYPASS_DENIED, snapshot.directBindFailureReason)
        assertNotNull(snapshot.proxyNetwork)
        assertEquals(EgressRouteKind.PROXY_FALLBACK, snapshot.proxyNetwork!!.routeKind)
        assertEquals(ClientCarrier.OVERSEAS, snapshot.proxyNetwork!!.carrier)
    }

    @Test
    fun `parses domestic ipip json fallback and fails over across multiple direct candidates`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/self-ipwhois" -> MockResponse().setResponseCode(500)
                "/self-ipip" -> MockResponse().setResponseCode(200).setBody(
                    """{"ret":"ok","data":{"ip":"58.16.14.220","location":["中国","贵州","贵阳","","联通"]}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val failingWifi = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-broken"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = object : SocketFactory() {
                override fun createSocket(): Socket = throw SocketException("Network is unreachable (ENETUNREACH)")
                override fun createSocket(host: String?, port: Int) = error("unused")
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = error("unused")
                override fun createSocket(host: InetAddress?, port: Int) = error("unused")
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = error("unused")
            }
            override fun createSocket(): Socket = socketFactory.createSocket()
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }

        val workingCellular = object : NetBenchSocketChannel {
            override val networkId: String = "cell-5g"
            override val transportLabel: String = "蜂窝移动"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }

        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(failingWifi, "wifi-broken", isVpn = false, hasInternet = true, isValidated = true, isForeground = true, transports = setOf(NetBenchTransportKind.WIFI)),
                        NetworkDescriptor(workingCellular, "cell-5g", isVpn = false, hasInternet = true, isValidated = true, isForeground = false, transports = setOf(NetBenchTransportKind.CELLULAR)),
                    ),
                )
                return ResolvedNetworkBinding(plan, failingWifi, workingCellular)
            }
        }

        val base = server.url("/").toString().trimEnd('/')
        val client = IpIntelligenceClient(
            httpClient = OkHttpClient(),
            endpoints = IpIntelligenceEndpoints(
                clientSelfUrls = listOf("ipwho.is" to "$base/self-ipwhois"),
                clientDirectUrls = listOf(
                    "ipwho.is" to "$base/self-ipwhois",
                    "ipip.net" to "$base/self-ipip",
                ),
            ),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
        )

        val snapshot = client.detectClientEgress()
        assertNotNull(snapshot.directNetwork)
        assertEquals(ClientCarrier.CHINA_UNICOM, snapshot.directNetwork!!.carrier)
        assertEquals("CN", snapshot.directNetwork!!.countryCode)
        assertEquals("贵州 贵阳", snapshot.directNetwork!!.regionName)
        assertEquals("58.16.*.*", snapshot.directNetwork!!.maskedIp)
        assertEquals("蜂窝移动", snapshot.directNetwork!!.transportLabel)
    }

    @Test
    fun `hedged direct egress probing recovers quickly via domestic ipip fallback when primary overseas endpoint stalls`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/slow-overseas" -> MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setBody("""{"success":true,"ip":"114.240.88.123","country_code":"CN","connection":{"asn":4134}}""")
                "/fast-ipip" -> MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"ret":"ok","data":{"ip":"58.16.14.220","location":["中国","贵州","贵阳","","联通"]}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val workingWifi: NetBenchSocketChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-1"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(workingWifi, "wifi-1", isVpn = false, hasInternet = true, isValidated = true, isForeground = true, transports = setOf(NetBenchTransportKind.WIFI)),
                    ),
                )
                return ResolvedNetworkBinding(plan, workingWifi, workingWifi)
            }
        }

        val base = server.url("/").toString().trimEnd('/')
        val client = IpIntelligenceClient(
            httpClient = OkHttpClient(),
            endpoints = IpIntelligenceEndpoints(
                clientSelfUrls = listOf("ipwho.is" to "$base/slow-overseas"),
                clientDirectUrls = listOf(
                    "ipwho.is" to "$base/slow-overseas",
                    "ipip.net" to "$base/fast-ipip",
                ),
            ),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
        )

        val startedNanos = System.nanoTime()
        val snapshot = client.detectClientEgress()
        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L

        assertNotNull(snapshot.directNetwork)
        assertEquals(ClientCarrier.CHINA_UNICOM, snapshot.directNetwork!!.carrier)
        assertTrue(
            "Expected hedged direct probe to recover via ipip.net well under 2s overseas stall, took ${elapsedMs}ms",
            elapsedMs < 1_500L,
        )
    }
}
