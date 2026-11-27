package com.nodeseek.app.data.alerts

import com.nodeseek.app.data.local.SoftwareSettingsSecretCipher
import com.nodeseek.app.domain.alerts.AlertEventCategory
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.BarkForwardConfig
import com.nodeseek.app.domain.alerts.ForwardableAlertEvent
import com.nodeseek.app.domain.alerts.TelegramForwardConfig
import com.nodeseek.app.domain.alerts.WebhookForwardConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class AlertForwarderImplTest {

    private lateinit var tempDir: File
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("alert_forwarder_test").toFile()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `AlertForwardingStore encrypts secrets on disk and round-trips cleanly`() = runTest {
        val storeFile = File(tempDir, "alert_forwarding_config.json")
        val cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        val store = AlertForwardingStore(
            storeFile = storeFile,
            secretCipher = cipher,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        store.updateConfig {
            AlertForwardingConfig(
                enabled = true,
                enabledEvents = setOf(AlertEventCategory.PROBE_OFFLINE, AlertEventCategory.RESTOCK_AVAILABLE),
                telegram = TelegramForwardConfig(
                    enabled = true,
                    botToken = "999999:SuperSecretBotToken",
                    chatId = "-100112233",
                ),
                bark = BarkForwardConfig(
                    enabled = true,
                    serverUrl = "https://bark.example.com",
                    deviceKey = "SuperSecretBarkKey",
                ),
                webhook = WebhookForwardConfig(
                    enabled = true,
                    url = "https://webhook.example.com/secret-path",
                    secretValue = "SuperSecretWebhookHmac",
                ),
            )
        }

        val rawJson = storeFile.readText(Charsets.UTF_8)
        assertFalse(rawJson.contains("SuperSecretBotToken"))
        assertFalse(rawJson.contains("SuperSecretBarkKey"))
        assertFalse(rawJson.contains("https://webhook.example.com/secret-path"))
        assertFalse(rawJson.contains("SuperSecretWebhookHmac"))
        assertTrue(rawJson.contains("v1:sw:"))

        val reloadedStore = AlertForwardingStore(
            storeFile = storeFile,
            secretCipher = cipher,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val loaded = reloadedStore.ensureLoaded()
        assertTrue(loaded.enabled)
        assertEquals("999999:SuperSecretBotToken", loaded.telegram.botToken)
        assertEquals("SuperSecretBarkKey", loaded.bark.deviceKey)
        assertEquals("https://webhook.example.com/secret-path", loaded.webhook.url)
        assertEquals("SuperSecretWebhookHmac", loaded.webhook.secretValue)
        assertEquals(setOf(AlertEventCategory.PROBE_OFFLINE, AlertEventCategory.RESTOCK_AVAILABLE), loaded.enabledEvents)
    }

    @Test
    fun `AlertForwarderImpl strips cookies, interceptors, and redirects while isolating partial failures`() = runTest {
        val store = AlertForwardingStore(
            storeFile = File(tempDir, "fwd_http.json"),
            secretCipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val poisonedBaseClient = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                    Cookie.Builder()
                        .name("session")
                        .value("nodeseek_secret_cookie")
                        .domain(url.host)
                        .build(),
                )
            })
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("x-csrf-token", "leaked_csrf")
                        .build(),
                )
            })
            .build()

        val recorded = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return when (request.path) {
                    "/push" -> MockResponse().setResponseCode(500).setBody("""{"message":"bark down"}""")
                    "/hook-ok" -> MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                    "/hook-redirect" -> MockResponse().setResponseCode(302).addHeader("Location", "/should-not-follow")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val forwarder = AlertForwarderImpl(
            store = store,
            baseHttpClient = poisonedBaseClient,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(CookieJar.NO_COOKIES, forwarder.httpClient.cookieJar)
        assertFalse(forwarder.httpClient.followRedirects)
        assertFalse(forwarder.httpClient.followSslRedirects)
        assertTrue(forwarder.httpClient.interceptors.isEmpty())
        assertTrue(forwarder.httpClient.networkInterceptors.isEmpty())

        // Master switch disabled -> forward returns emptyList without network calls
        val probeEvent = ForwardableAlertEvent.ProbeOffline("Panel", "SG-1", "p1:1")
        assertTrue(forwarder.forward(probeEvent).isEmpty())
        assertEquals(0, recorded.size)

        // Enable master switch with PROBE_OFFLINE only, Bark (500) + Webhook (200)
        forwarder.updateConfig {
            AlertForwardingConfig(
                enabled = true,
                enabledEvents = setOf(AlertEventCategory.PROBE_OFFLINE),
                bark = BarkForwardConfig(
                    enabled = true,
                    serverUrl = server.url("").toString().trimEnd('/'),
                    deviceKey = "dk_1",
                ),
                webhook = WebhookForwardConfig(
                    enabled = true,
                    url = server.url("/hook-ok").toString(),
                    secretValue = "wh_sec",
                ),
            )
        }

        // Disabled event category -> skipped
        assertTrue(forwarder.forward(ForwardableAlertEvent.ProbeRecovered("Panel", "SG-1", "p1:1")).isEmpty())
        assertEquals(0, recorded.size)

        // Enabled event -> dispatches Bark (fails 500) and Webhook (succeeds 200) in parallel
        val results = forwarder.forward(probeEvent)
        assertEquals(2, results.size)
        val barkRes = results.first { it.channelName == AlertForwarderImpl.CHANNEL_BARK }
        val hookRes = results.first { it.channelName == AlertForwarderImpl.CHANNEL_WEBHOOK }
        assertFalse(barkRes.success)
        assertEquals(500, barkRes.httpStatus)
        assertEquals("bark down", barkRes.errorMessage)
        assertTrue(hookRes.success)
        assertEquals(200, hookRes.httpStatus)

        // Verify zero cookies and zero NodeSeek CSRF headers leaked
        assertEquals(2, recorded.size)
        recorded.forEach { req ->
            assertNull(req.getHeader("Cookie"))
            assertNull(req.getHeader("x-csrf-token"))
        }

        // Redirect (302) must NOT be followed
        forwarder.updateConfig {
            it.copy(
                bark = it.bark.copy(enabled = false),
                webhook = it.webhook.copy(url = server.url("/hook-redirect").toString()),
            )
        }
        val redirectResults = forwarder.sendTestAlert()
        assertEquals(1, redirectResults.size)
        assertFalse(redirectResults.single().success)
        assertEquals(302, redirectResults.single().httpStatus)
        assertFalse(recorded.any { it.path == "/should-not-follow" })
    }

    @Test
    fun `AlertForwardingStore encrypts webhook customHeaders on disk and blocks loopback SSRF in production mode`() = runTest {
        val storeFile = File(tempDir, "alerts_encrypted_headers.json")
        val cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        val store = AlertForwardingStore(
            storeFile = storeFile,
            secretCipher = cipher,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        store.updateConfig {
            AlertForwardingConfig(
                enabled = true,
                webhook = WebhookForwardConfig(
                    enabled = true,
                    url = "https://hooks.example.com/notify",
                    secretValue = "top-secret-hmac",
                    customHeaders = mapOf(
                        "X-Api-Key" to "super-secret-custom-header-value",
                    ),
                ),
            )
        }
        val rawDiskJson = storeFile.readText(Charsets.UTF_8)
        assertFalse("Disk JSON must not leak plaintext custom header secret", rawDiskJson.contains("super-secret-custom-header-value"))
        assertTrue(rawDiskJson.contains("webhookCustomHeadersEncrypted"))

        val reloadedStore = AlertForwardingStore(
            storeFile = storeFile,
            secretCipher = cipher,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val loaded = reloadedStore.ensureLoaded()
        assertEquals("super-secret-custom-header-value", loaded.webhook.customHeaders["X-Api-Key"])

        // Legacy plaintext migration on load
        val legacyFile = File(tempDir, "alerts_legacy_plaintext.json")
        legacyFile.writeText(
            """{"enabled":true,"webhookEnabled":true,"webhookCustomHeaders":{"Authorization":"Bearer legacy-secret"}}""",
            Charsets.UTF_8,
        )
        val legacyStore = AlertForwardingStore(
            storeFile = legacyFile,
            secretCipher = cipher,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val migrated = legacyStore.ensureLoaded()
        assertEquals("Bearer legacy-secret", migrated.webhook.customHeaders["Authorization"])
        assertFalse("Migrated file must scrub legacy plaintext secret", legacyFile.readText(Charsets.UTF_8).contains("Bearer legacy-secret"))

        // Production AlertForwarderImpl (allowLoopbackTargets = false) rejects loopback Webhook URLs without hitting MockWebServer
        val prodForwarder = AlertForwarderImpl(
            store = store,
            baseHttpClient = OkHttpClient(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            allowLoopbackTargets = false,
        )
        prodForwarder.updateConfig {
            it.copy(
                webhook = it.webhook.copy(url = server.url("/ssrf-probe").toString()),
            )
        }
        val res = prodForwarder.sendTestAlert()
        assertEquals(1, res.size)
        assertFalse(res.single().success)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `AlertForwarderImpl DNS SSRF guard blocks NAT64 metadata IPv6 answers`() = runTest {
        val store = AlertForwardingStore(
            storeFile = File(tempDir, "alerts_nat64_dns.json"),
            secretCipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        // 64:ff9b::169.254.169.254 -> 0064:ff9b:0000:0000:0000:0000:a9fe:a9fe
        val nat64MetadataAddress = InetAddress.getByAddress(
            byteArrayOf(
                0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(),
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                169.toByte(), 254.toByte(), 169.toByte(), 254.toByte(),
            ),
        )
        val rebindingDns = Dns { hostname ->
            assertEquals("evil-rebind.example", hostname)
            listOf(nat64MetadataAddress)
        }
        val forwarder = AlertForwarderImpl(
            store = store,
            baseHttpClient = OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .build(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            allowLoopbackTargets = false,
            allowPrivateNetworkTargets = false,
            dnsResolver = rebindingDns,
        )
        forwarder.updateConfig {
            AlertForwardingConfig(
                enabled = true,
                webhook = WebhookForwardConfig(
                    enabled = true,
                    url = "https://evil-rebind.example/hook",
                ),
            )
        }

        val results = forwarder.sendTestAlert()
        assertEquals(1, results.size)
        val result = results.single()
        assertFalse("DNS answer returning 64:ff9b::169.254.169.254 must be blocked", result.success)
        assertTrue(
            "Expected SSRF DNS block error, got: ${result.errorMessage}",
            result.errorMessage?.contains("Blocked non-public alert target host") == true,
        )
    }

    @Test
    fun `AlertForwarderImpl DNS SSRF guard blocks NAT64 64_ff9b_1 slash-56 and slash-64 answers under production flags`() = runTest {
        val store = AlertForwardingStore(
            storeFile = File(tempDir, "alerts_nat64_local_dns.json"),
            secretCipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        // /64 IMDS: 64:ff9b:1:aaaa:00a9:fea9:fe00::
        val nat64Slash64Imds = InetAddress.getByAddress(
            byteArrayOf(
                0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(),
                0x00, 0x01, 0xAA.toByte(), 0xAA.toByte(),
                0x00, 169.toByte(), 254.toByte(), 169.toByte(),
                254.toByte(), 0x00, 0x00, 0x00,
            ),
        )
        // /56 Loopback: 64:ff9b:1:aa7f:0000:0100::
        val nat64Slash56Loopback = InetAddress.getByAddress(
            byteArrayOf(
                0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(),
                0x00, 0x01, 0xAA.toByte(), 0x7F,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
            ),
        )

        var currentAnswer = nat64Slash64Imds
        val forwarder = AlertForwarderImpl(
            store = store,
            baseHttpClient = OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .build(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            allowLoopbackTargets = false,
            allowPrivateNetworkTargets = true,
            dnsResolver = Dns { listOf(currentAnswer) },
        )
        forwarder.updateConfig {
            AlertForwardingConfig(
                enabled = true,
                webhook = WebhookForwardConfig(
                    enabled = true,
                    url = "https://rebind.example/hook",
                ),
            )
        }

        val imdsResult = forwarder.sendTestAlert().single()
        assertFalse("DNS answer returning 64:ff9b:1:aaaa:00a9:fea9:fe00:: must be blocked", imdsResult.success)
        assertTrue(
            "Expected SSRF DNS block error, got: ${imdsResult.errorMessage}",
            imdsResult.errorMessage?.contains("Blocked non-public alert target host") == true,
        )

        currentAnswer = nat64Slash56Loopback
        val loopResult = forwarder.sendTestAlert().single()
        assertFalse("DNS answer returning 64:ff9b:1:aa7f:0000:0100:: must be blocked", loopResult.success)
        assertTrue(
            "Expected SSRF DNS block error, got: ${loopResult.errorMessage}",
            loopResult.errorMessage?.contains("Blocked non-public alert target host") == true,
        )

        // Non-zero-prefix /96 under 64:ff9b:1::/48 -> 64:ff9b:1:0:dead::169.254.169.254
        currentAnswer = InetAddress.getByAddress(
            byteArrayOf(
                0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(),
                0x00, 0x01, 0x00, 0x00,
                0xDE.toByte(), 0xAD.toByte(), 0x00, 0x00,
                169.toByte(), 254.toByte(), 169.toByte(), 254.toByte(),
            ),
        )
        val nonZeroB8ImdsResult = forwarder.sendTestAlert().single()
        assertFalse("DNS answer returning 64:ff9b:1:0:dead::169.254.169.254 must be blocked", nonZeroB8ImdsResult.success)
        assertTrue(
            "Expected SSRF DNS block error, got: ${nonZeroB8ImdsResult.errorMessage}",
            nonZeroB8ImdsResult.errorMessage?.contains("Blocked non-public alert target host") == true,
        )

        // Zero b[8] with non-zero 6..11 /96 -> 64:ff9b:1:101:1::127.0.0.1
        currentAnswer = InetAddress.getByAddress(
            byteArrayOf(
                0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(),
                0x00, 0x01, 0x01, 0x01,
                0x00, 0x01, 0x00, 0x00,
                127, 0, 0, 1,
            ),
        )
        val zeroB8NonZeroPrefixLoopResult = forwarder.sendTestAlert().single()
        assertFalse("DNS answer returning 64:ff9b:1:101:1::127.0.0.1 must be blocked", zeroB8NonZeroPrefixLoopResult.success)
        assertTrue(
            "Expected SSRF DNS block error, got: ${zeroB8NonZeroPrefixLoopResult.errorMessage}",
            zeroB8NonZeroPrefixLoopResult.errorMessage?.contains("Blocked non-public alert target host") == true,
        )
    }
}
