package com.nodeseek.app.restock

import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.FlareSolverrOutcome
import com.nodeseek.app.domain.restock.FlareSolverrConfig
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FlareSolverrClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: FlareSolverrClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = FlareSolverrClient(
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(baseUrl: String = server.url("/").toString().trimEnd('/')): FlareSolverrConfig =
        FlareSolverrConfig(
            enabled = true,
            baseUrl = baseUrl,
            maxTimeoutMillis = 15_000,
        )

    @Test
    fun `requestGet parses solved challenge response and posts request dot get command`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                    {"status":"ok","message":"Challenge solved!","solution":{
                      "url":"https://cf-shop.example.com/cart.php?a=add&pid=118",
                      "status":200,
                      "userAgent":"Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0",
                      "response":"<html><body>CF shop page</body></html>"
                    }}
                """.trimIndent(),
            ),
        )

        val outcome = client.requestGet(
            config = config(),
            targetUrl = "https://cf-shop.example.com/cart.php?a=add&pid=118",
        )

        val solved = outcome as FlareSolverrOutcome.Solved
        assertEquals("<html><body>CF shop page</body></html>", solved.html)
        assertEquals("https://cf-shop.example.com/cart.php?a=add&pid=118", solved.finalUrl)
        assertTrue(solved.userAgent.contains("Chrome/131"))

        val recorded = server.takeRequest()
        assertEquals("/v1", recorded.path)
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"cmd\":\"request.get\""))
        assertTrue(body.contains("\"url\":\"https://cf-shop.example.com/cart.php?a=add&pid=118\""))
        assertTrue(body.contains("\"maxTimeout\":15000"))
    }

    @Test
    fun `requestGet maps error status to non-transient failure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"error","message":"Captcha detected"}""",
            ),
        )

        val outcome = client.requestGet(config(), "https://cf-shop.example.com/")

        val failure = outcome as FlareSolverrOutcome.Failure
        assertTrue(failure.reason.contains("Captcha detected"))
        assertFalse(failure.isTransient)
    }

    @Test
    fun `requestGet maps server errors and unreachable endpoints to transient failures`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        val httpFailure = client.requestGet(config(), "https://cf-shop.example.com/") as FlareSolverrOutcome.Failure
        assertTrue(httpFailure.isTransient)
        assertTrue(httpFailure.reason.contains("502"))

        server.shutdown()
        val unreachable = client.requestGet(config(), "https://cf-shop.example.com/") as FlareSolverrOutcome.Failure
        assertTrue(unreachable.isTransient)
        assertTrue(unreachable.reason.contains("不可达"))
    }

    @Test
    fun `requestGet rejects blank endpoint configuration`() = runTest {
        val outcome = client.requestGet(
            config = FlareSolverrConfig(enabled = true, baseUrl = "  "),
            targetUrl = "https://cf-shop.example.com/",
        )
        val failure = outcome as FlareSolverrOutcome.Failure
        assertTrue(failure.reason.contains("未配置"))
        assertFalse(failure.isTransient)
    }

    @Test
    fun `ping succeeds on ok sessions list and fails on error replies`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","sessions":[]}"""))
        assertTrue(client.ping(config()))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"error","message":"Unknown command"}"""))
        assertFalse(client.ping(config()))

        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        assertFalse(client.ping(config()))

        assertFalse(client.ping(FlareSolverrConfig(enabled = true, baseUrl = "")))
    }
}
