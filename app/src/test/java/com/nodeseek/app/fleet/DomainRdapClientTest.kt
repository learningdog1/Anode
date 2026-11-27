package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.WhoisFallbackClient
import com.nodeseek.app.domain.fleet.DomainRdapParser
import com.nodeseek.app.domain.fleet.DomainWhoisParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DomainRdapClientTest {

    private lateinit var server: MockWebServer
    private val testDispatcher = UnconfinedTestDispatcher()

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
    fun `follows 302 redirect, parses RFC 9083 registrar vCard and events, and strips cookies and NodeSeek interceptors`() =
        runTest(testDispatcher) {
            val capturedRequests = mutableListOf<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    capturedRequests += request
                    return when (request.path) {
                        "/domain/example.com" -> MockResponse()
                            .setResponseCode(302)
                            .addHeader("Location", server.url("/registry/domain/example.com").toString())
                        "/registry/domain/example.com" -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                {
                                  "objectClassName": "domain",
                                  "ldhName": "EXAMPLE.COM",
                                  "status": ["client transfer prohibited", "client delete prohibited"],
                                  "events": [
                                    {"eventAction": "registration", "eventDate": "2020-05-18T12:00:00Z"},
                                    {"eventAction": "expiration", "eventDate": "2028-05-18T23:59:59Z"},
                                    {"eventAction": "last changed", "eventDate": "2025-04-01T00:00:00Z"}
                                  ],
                                  "entities": [
                                    {
                                      "objectClassName": "entity",
                                      "roles": ["registrant"],
                                      "handle": "REDACTED"
                                    },
                                    {
                                      "objectClassName": "entity",
                                      "roles": ["registrar"],
                                      "handle": "472",
                                      "publicIds": [{"type": "IANA Registrar ID", "identifier": "472"}],
                                      "vcardArray": ["vcard", [["version", {}, "text", "4.0"], ["fn", {}, "text", "Porkbun LLC"]]]
                                    }
                                  ],
                                  "nameservers": [
                                    {"objectClassName": "nameserver", "ldhName": "curitiba.ns.porkbun.com"},
                                    {"objectClassName": "nameserver", "ldhName": "fortaleza.ns.porkbun.com"}
                                  ]
                                }
                                """.trimIndent(),
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            // Simulate passing a tainted client with NodeSeek cookies & dynamic-sign interceptor:
            // DomainRdapClient MUST strip interceptors and enforce CookieJar.NO_COOKIES!
            val taintedClient = OkHttpClient.Builder()
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                        Cookie.Builder().name("session").value("SECRET_NODESEEK_COOKIE").domain(url.host).build(),
                    )
                })
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("x-dynamic-sign", "LEAKED_SIGN")
                        .header("csrf-token", "LEAKED_CSRF")
                        .build()
                    chain.proceed(req)
                })
                .build()

            val client = DomainRdapClient(
                baseHttpClient = taintedClient,
                primaryEndpointTemplate = server.url("/domain/{domain}").toString(),
                ianaBootstrapUrl = null,
                ioDispatcher = testDispatcher,
                clockMillis = { 1_750_000_000_000L },
            )

            val res = client.lookupDomain("example.com")
            assertTrue(res.isSuccess)
            val rdap = res.getOrThrow()
            assertEquals("example.com", rdap.domainName)
            assertEquals("Porkbun LLC", rdap.registrarName)
            assertEquals("472", rdap.registrarIanaId)
            assertEquals("2028-05-18", rdap.expiryDateIso)
            assertEquals("2020-05-18", rdap.registrationDateIso)
            assertEquals("2025-04-01", rdap.lastChangedDateIso)
            assertEquals(listOf("client transfer prohibited", "client delete prohibited"), rdap.statuses)
            assertEquals(listOf("curitiba.ns.porkbun.com", "fortaleza.ns.porkbun.com"), rdap.nameservers)

            assertEquals(2, capturedRequests.size)
            for (req in capturedRequests) {
                assertNull("Cookie header must NEVER be sent to RDAP", req.getHeader("Cookie"))
                assertNull("x-dynamic-sign must NEVER be sent to RDAP", req.getHeader("x-dynamic-sign"))
                assertNull("csrf-token must NEVER be sent to RDAP", req.getHeader("csrf-token"))
            }
        }

    @Test
    fun `falls back to IANA bootstrap on 500 and handles 404 and malformed JSON cleanly`() = runTest(testDispatcher) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/primary/domain/test.org" -> MockResponse().setResponseCode(500)
                "/iana/dns.json" -> MockResponse().setResponseCode(200).setBody(
                    """{"services":[[["org"],["${server.url("/org_rdap/")}"]]]}""",
                )
                "/org_rdap/domain/test.org" -> MockResponse().setResponseCode(200).setBody(
                    """{"ldhName":"test.org","events":[{"eventAction":"expiration","eventDate":"2029-01-01T00:00:00Z"}]}""",
                )
                "/primary/domain/missing.org" -> MockResponse().setResponseCode(404)
                "/primary/domain/broken.org" -> MockResponse().setResponseCode(200).setBody("{bad json")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = DomainRdapClient(
            primaryEndpointTemplate = server.url("/primary/domain/{domain}").toString(),
            ianaBootstrapUrl = server.url("/iana/dns.json").toString(),
            ioDispatcher = testDispatcher,
        )

        val fallbackOk = client.lookupDomain("test.org")
        assertTrue(fallbackOk.isSuccess)
        assertEquals("2029-01-01", fallbackOk.getOrThrow().expiryDateIso)

        val notFound = client.lookupDomain("missing.org")
        assertTrue(notFound.isFailure)
        assertTrue(notFound.exceptionOrNull()!!.message!!.contains("RDAP"))

        val badJson = client.lookupDomain("broken.org")
        assertTrue(badJson.isFailure)
    }

    @Test
    fun `lookupDomain_multiLabelCcTldSubdomain_queriesRegistrableDomain`() = runTest(testDispatcher) {
        val pathsRequested = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                pathsRequested += p
                return when (p) {
                    "/primary/domain/example.co.uk" -> MockResponse().setResponseCode(200).setBody(
                        """{"ldhName":"example.co.uk","events":[{"eventAction":"expiration","eventDate":"2030-06-15T00:00:00Z"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = DomainRdapClient(
            primaryEndpointTemplate = server.url("/primary/domain/{domain}").toString(),
            ianaBootstrapUrl = server.url("/iana/dns.json").toString(),
            ioDispatcher = testDispatcher,
        )

        val result = client.lookupDomain("a.b.example.co.uk")
        assertTrue(result.isSuccess)
        assertEquals("example.co.uk", result.getOrThrow().domainName)
        assertEquals("2030-06-15", result.getOrThrow().expiryDateIso)
        assertEquals(
            listOf(
                "/primary/domain/a.b.example.co.uk",
                "/primary/domain/b.example.co.uk",
                "/primary/domain/example.co.uk",
            ),
            pathsRequested,
        )
    }

    @Test
    fun `lookupDomain resolves DENIC de sparse RDAP response without expiration and strips trailing dots on nameservers`() =
        runTest(testDispatcher) {
            val denicBody = """
                {
                  "rdapConformance": ["rdap_level_0", "denic_version_0"],
                  "ldhName": "colmidad.de",
                  "status": ["active"],
                  "nameservers": [
                    {"ldhName": "leonard.ns.cloudflare.com.", "objectClassName": "nameserver"},
                    {"ldhName": "stella.ns.cloudflare.com.", "objectClassName": "nameserver"}
                  ],
                  "events": [
                    {"eventAction": "last changed", "eventDate": "2024-04-01T07:04:05+02:00"}
                  ],
                  "entities": [],
                  "objectClassName": "domain"
                }
            """.trimIndent()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/primary/domain/colmidad.de" -> MockResponse().setResponseCode(404).setBody(
                        """{"rdapConformance":["rdap_level_0"],"errorCode":404,"title":"No RDAP service is available for this resource"}""",
                    )
                    "/denic/domain/colmidad.de" -> MockResponse().setResponseCode(200).setBody(denicBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val client = DomainRdapClient(
                primaryEndpointTemplate = server.url("/primary/domain/{domain}").toString(),
                ianaBootstrapUrl = null,
                ioDispatcher = testDispatcher,
                directTldRdapEndpoints = mapOf("de" to server.url("/denic/").toString()),
            )

            val res = client.lookupDomain("colmidad.de")
            assertTrue("Expected DENIC .de lookup to succeed, got: ${res.exceptionOrNull()}", res.isSuccess)
            val rdap = res.getOrThrow()
            assertEquals("colmidad.de", rdap.domainName)
            assertNull(rdap.registrarName)
            assertNull(rdap.expiryDateIso)
            assertEquals("2024-04-01", rdap.lastChangedDateIso)
            assertEquals(listOf("active"), rdap.statuses)
            assertEquals(listOf("leonard.ns.cloudflare.com", "stella.ns.cloudflare.com"), rdap.nameservers)
            assertTrue(rdap.toSyncSummary().contains("注册局未公开到期日"))
        }

    @Test
    fun `lookupDomain resolves 0ne icu via IANA bootstrap when primary aggregator returns 404 and prefers last changed date`() =
        runTest(testDispatcher) {
            val centralNicBody = """
                {
                  "rdapConformance": ["icann_rdap_response_profile_1", "rdap_level_0"],
                  "objectClassName": "domain",
                  "ldhName": "0ne.icu",
                  "nameservers": [
                    {"objectClassName": "nameserver", "ldhName": "leonard.ns.cloudflare.com"},
                    {"objectClassName": "nameserver", "ldhName": "stella.ns.cloudflare.com"}
                  ],
                  "entities": [
                    {
                      "objectClassName": "entity",
                      "handle": "3862",
                      "roles": ["registrar"],
                      "vcardArray": ["vcard", [["version", {}, "text", "4.0"], ["fn", {}, "text", "Spaceship, Inc."]]],
                      "publicIds": [{"type": "IANA Registrar ID", "identifier": "3862"}]
                    }
                  ],
                  "status": ["client transfer prohibited"],
                  "events": [
                    {"eventAction": "registration", "eventDate": "2024-08-17T16:07:35.0Z"},
                    {"eventAction": "expiration", "eventDate": "2027-08-17T23:59:59.0Z"},
                    {"eventAction": "last update of RDAP database", "eventDate": "2026-11-27T14:53:50.0Z"},
                    {"eventAction": "last changed", "eventDate": "2026-09-03T10:09:30.0Z"}
                  ]
                }
            """.trimIndent()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/primary/domain/0ne.icu" -> MockResponse().setResponseCode(404).setBody(
                        """{"errorCode":404,"title":"No RDAP service is available for this resource"}""",
                    )
                    "/iana/dns.json" -> MockResponse().setResponseCode(200).setBody(
                        """{"services":[[["icu"],["${server.url("/centralnic/icu/")}"]]]}""",
                    )
                    "/centralnic/icu/domain/0ne.icu" -> MockResponse().setResponseCode(200).setBody(centralNicBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val client = DomainRdapClient(
                primaryEndpointTemplate = server.url("/primary/domain/{domain}").toString(),
                ianaBootstrapUrl = server.url("/iana/dns.json").toString(),
                ioDispatcher = testDispatcher,
            )

            val res = client.lookupDomain("0ne.icu")
            assertTrue("Expected 0ne.icu lookup to succeed via IANA bootstrap on 404", res.isSuccess)
            val rdap = res.getOrThrow()
            assertEquals("0ne.icu", rdap.domainName)
            assertEquals("Spaceship, Inc.", rdap.registrarName)
            assertEquals("3862", rdap.registrarIanaId)
            assertEquals("2024-08-17", rdap.registrationDateIso)
            assertEquals("2027-08-17", rdap.expiryDateIso)
            assertEquals("2026-09-03", rdap.lastChangedDateIso)
            assertEquals(listOf("client transfer prohibited"), rdap.statuses)
            assertEquals(listOf("leonard.ns.cloudflare.com", "stella.ns.cloudflare.com"), rdap.nameservers)
        }

    @Test
    fun `lookupDomain falls back to built-in registry RDAP and WHOIS when primary and IANA bootstrap both fail`() =
        runTest(testDispatcher) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
            }

            val whoisRawIcu = """
                Domain Name: 0NE.ICU
                Registry Domain ID: D479286336-CNIC
                Updated Date: 2026-09-03T10:09:30.0Z
                Creation Date: 2024-08-17T16:07:35.0Z
                Registry Expiry Date: 2027-08-17T23:59:59.0Z
                Registrar: Spaceship, Inc.
                Registrar IANA ID: 3862
                Domain Status: clientTransferProhibited https://icann.org/epp#clientTransferProhibited
                Name Server: LEONARD.NS.CLOUDFLARE.COM
                Name Server: STELLA.NS.CLOUDFLARE.COM
            """.trimIndent()

            val whoisRawDe = """
                % DENIC whois
                Domain: colmidad.de
                Nserver: leonard.ns.cloudflare.com
                Nserver: stella.ns.cloudflare.com
                Status: connect
                Changed: 2024-04-01T07:04:05+02:00
            """.trimIndent()

            val client = DomainRdapClient(
                primaryEndpointTemplate = server.url("/primary/domain/{domain}").toString(),
                ianaBootstrapUrl = server.url("/iana/dns.json").toString(),
                ioDispatcher = testDispatcher,
                whoisLookup = { domain ->
                    WhoisFallbackClient.lookup(
                        domain = domain,
                        clockMillis = { 1_750_000_000_000L },
                        queryTransport = { host, _ ->
                            when (host) {
                                "whois.nic.icu" -> whoisRawIcu
                                "whois.denic.de" -> whoisRawDe
                                else -> "No match for domain"
                            }
                        },
                    )
                },
            )

            val icuRes = client.lookupDomain("0ne.icu")
            assertTrue(icuRes.isSuccess)
            val icu = icuRes.getOrThrow()
            assertEquals("0ne.icu", icu.domainName)
            assertEquals("Spaceship, Inc.", icu.registrarName)
            assertEquals("3862", icu.registrarIanaId)
            assertEquals("2027-08-17", icu.expiryDateIso)
            assertEquals("2024-08-17", icu.registrationDateIso)
            assertTrue(icu.sourceUrl.startsWith("whois://whois.nic.icu/"))

            val deRes = client.lookupDomain("colmidad.de")
            assertTrue(deRes.isSuccess)
            val de = deRes.getOrThrow()
            assertEquals("colmidad.de", de.domainName)
            assertNull(de.expiryDateIso)
            assertEquals("2024-04-01", de.lastChangedDateIso)
            assertEquals(listOf("connect"), de.statuses)
            assertEquals(listOf("leonard.ns.cloudflare.com", "stella.ns.cloudflare.com"), de.nameservers)

            val notFoundWhois = DomainWhoisParser.parse(
                rawWhois = "Domain: nonexistent.de\nStatus: free\n",
                queriedDomain = "nonexistent.de",
                sourceUrl = "whois://whois.denic.de/nonexistent.de",
            )
            assertTrue(notFoundWhois.isFailure)

            val error200Rdap = DomainRdapParser.parse(
                rawJson = """{"errorCode":404,"title":"Object not found"}""",
                queriedDomain = "missing.example",
                sourceUrl = "https://rdap.example/domain/missing.example",
            )
            assertTrue(error200Rdap.isFailure)
        }
}
