package com.nodeseek.app.data

import com.nodeseek.app.data.network.CloudflareChallengeDetector
import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.network.computeDynamicSign
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class NodeSeekSigningAndUrlTest {

    @Test
    fun `computeDynamicSign matches exact SHA-1 of method, url, userAgent, and body joined by double newline`() {
        val method = "POST"
        val url = "https://www.nodeseek.com/api/stardust/send"
        val ua = "Mozilla/5.0 TestAgent/1.0"
        val body = """{"member_id":202,"diff":10,"ref_id":0}"""

        val expectedRaw = "POST\n\nhttps://www.nodeseek.com/api/stardust/send\n\nMozilla/5.0 TestAgent/1.0\n\n$body"
        val expectedHex = MessageDigest.getInstance("SHA-1")
            .digest(expectedRaw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val actual = computeDynamicSign(
            method = method,
            fullUrl = url,
            userAgent = ua,
            rawBody = body,
        )
        assertEquals(40, actual.length)
        assertEquals(expectedHex, actual)
    }

    @Test
    fun `NodeSeekUrlNormalizer encodes literal plus as percent-2B and normalizes bare host`() {
        val searchUrl = NodeSeekUrlNormalizer.buildUrl(
            path = "/search",
            queryParams = listOf(
                "q" to "c++ ipv6+nat",
                "page" to "1",
                "sortBy" to "replyTime",
            ),
        )
        assertEquals(
            "https://www.nodeseek.com/search?q=c%2B%2B%20ipv6%2Bnat&page=1&sortBy=replyTime",
            searchUrl.toString(),
        )

        assertEquals(
            "https://www.nodeseek.com/post-888-1",
            NodeSeekUrlNormalizer.normalizeNodeSeekUrl("http://nodeseek.com/post-888-1"),
        )
        assertEquals(
            "https://www.nodeseek.com/avatar/101.png",
            NodeSeekUrlNormalizer.normalizeNodeSeekUrl("/avatar/101.png"),
        )
    }

    @Test
    fun `unwrapJumpUrl unwraps valid http and https targets and blocks unsafe schemes`() {
        assertEquals(
            "https://github.com/JetBrains/kotlin",
            NodeSeekUrlNormalizer.unwrapJumpUrl(
                "https://www.nodeseek.com/jump?to=https%3A%2F%2Fgithub.com%2FJetBrains%2Fkotlin",
            ),
        )
        val unsafe = "https://www.nodeseek.com/jump?to=javascript%3Aalert(1)"
        assertEquals(unsafe, NodeSeekUrlNormalizer.unwrapJumpUrl(unsafe))
    }

    @Test
    fun `CloudflareChallengeDetector flags cf-mitigated header, 403 challenge HTML, and missing temp-script`() {
        try {
            CloudflareChallengeDetector.inspectHtmlResponse(
                statusCode = 200,
                headers = Headers.headersOf("cf-mitigated", "challenge"),
                htmlBody = "<html></html>",
            )
            fail("Expected CloudflareChallengeException on cf-mitigated: challenge")
        } catch (_: CloudflareChallengeException) {
            // Expected
        }

        try {
            CloudflareChallengeDetector.inspectHtmlResponse(
                statusCode = 200,
                headers = Headers.headersOf(),
                htmlBody = "<html><head><title>Just a moment...</title></head><body></body></html>",
            )
            fail("Expected CloudflareChallengeException when temp-script is missing")
        } catch (_: CloudflareChallengeException) {
            // Expected
        }

        // Legitimate post access-denied page (#nsk-body present + checkingPostAccess = true) must NOT throw CloudflareChallengeException
        CloudflareChallengeDetector.inspectHtmlResponse(
            statusCode = 200,
            headers = Headers.headersOf(),
            htmlBody = """<html><body><div id="nsk-body"><div id="nsk-body-left">查看本帖需要 Lv 2</div></div></body></html>""",
            checkingPostAccess = true,
        )
    }

    @Test
    fun `CoilHttpClientFactory injects NodeSeek Referer and strips cookies on external CDN hosts`() {
        MockWebServer().use { cdnServer ->
            cdnServer.enqueue(MockResponse().setResponseCode(200).setBody("fake-image"))
            val cookieBridge = WebViewCookieBridge(forceInMemory = true)
            val nodeSeekUrl = "https://www.nodeseek.com/".toHttpUrl()
            cookieBridge.saveFromResponse(
                nodeSeekUrl,
                listOf(
                    Cookie.Builder()
                        .name("session")
                        .value("secret_cookie_123")
                        .domain("nodeseek.com")
                        .path("/")
                        .build(),
                ),
            )

            val coilClient = CoilHttpClientFactory.create(
                cookieBridge = cookieBridge,
                userAgentProvider = UserAgentProvider("NodePulseTestUA/1.0"),
            )

            val imgUrl = cdnServer.url("/image/test.png")
            val req = Request.Builder()
                .url(imgUrl)
                .header("Cookie", "leaked=1")
                .header("Authorization", "Bearer leaked")
                .build()

            coilClient.newCall(req).execute().close()

            val recorded = cdnServer.takeRequest()
            assertEquals("https://www.nodeseek.com/", recorded.getHeader("Referer"))
            assertEquals("NodePulseTestUA/1.0", recorded.getHeader("User-Agent"))
            assertNull(recorded.getHeader("Cookie"))
            assertNull(recorded.getHeader("Authorization"))
        }
    }

    @Test
    fun `WebViewCookieBridge strips unreplayable sortBy cookie while preserving cf_clearance and session`() {
        val bridge = WebViewCookieBridge(forceInMemory = true)
        val url = "https://www.nodeseek.com/post-123-1".toHttpUrl()
        val futureExpiry = System.currentTimeMillis() + 3_600_000L

        bridge.saveFromResponse(
            url,
            listOf(
                Cookie.Builder().name("sortBy").value("replyTime").domain("nodeseek.com").path("/").expiresAt(futureExpiry).build(),
                Cookie.Builder().name("cf_clearance").value("clearance_tok_1").domain("nodeseek.com").path("/").expiresAt(futureExpiry).build(),
                Cookie.Builder().name("session").value("sess_tok_2").domain("nodeseek.com").path("/").expiresAt(futureExpiry).build(),
            ),
        )

        val loaded = bridge.loadForRequest(url)
        assertEquals(listOf("cf_clearance", "session"), loaded.map { it.name }.sorted())
        assertEquals(true, bridge.hasClearanceCookie(url))

        val parsedFromHeader = WebViewCookieBridge.parseCookieHeader(
            url,
            "sortBy=postTime; cf_clearance=abc123; session=xyz789",
        )
        assertEquals(listOf("cf_clearance", "session"), parsedFromHeader.map { it.name })
    }
}
