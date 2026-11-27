package com.nodeseek.app.activity

import com.nodeseek.app.data.activity.DrandApiClient
import com.nodeseek.app.data.activity.LuckyDrawRepositoryImpl
import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.activity.LuckyViewerStatus
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DrandLuckyRepositoryTest {

    private lateinit var forumServer: MockWebServer
    private lateinit var drandServer: MockWebServer
    private lateinit var cookieBridge: WebViewCookieBridge
    private lateinit var forumClient: NodeSeekApiClient
    private lateinit var drandClient: DrandApiClient
    private lateinit var repository: LuckyDrawRepositoryImpl

    @Before
    fun setUp() {
        forumServer = MockWebServer()
        drandServer = MockWebServer()
        forumServer.start()
        drandServer.start()

        val forumBaseUrl = forumServer.url("/")
        cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("Mozilla/5.0 AnodeTest/1.0"),
            csrfTokenStore = CsrfTokenStore(),
            additionalTargetHost = forumBaseUrl.host,
        )
        cookieBridge.saveFromResponse(
            forumBaseUrl,
            listOf(
                okhttp3.Cookie.Builder()
                    .name("ns_session")
                    .value("secret_cookie_value")
                    .domain(forumBaseUrl.host)
                    .path("/")
                    .build(),
            ),
        )
        forumClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = forumBaseUrl,
            rssUrl = forumServer.url("/rss.xml"),
            nodeImageOrigin = forumBaseUrl,
        )
        drandClient = DrandApiClient(
            baseHttpClient = okHttp,
            baseUrl = drandServer.url("/"),
        )
        repository = LuckyDrawRepositoryImpl(
            apiClientProvider = { forumClient },
            drandApiClient = drandClient,
        )
    }

    @After
    fun tearDown() {
        forumServer.shutdown()
        drandServer.shutdown()
    }

    @Test
    fun `DrandApiClient uses CookieJar NO_COOKIES and strips Cookie and forum signing headers`() = runTest {
        assertEquals(CookieJar.NO_COOKIES, drandClient.httpClient.cookieJar)

        forumServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "data": [
                    { "floor_id": 1, "member_id": 101, "member_name": "Alice" },
                    { "floor_id": 2, "member_id": 202, "member_name": "Bob" }
                  ]
                }
                """.trimIndent(),
            ),
        )
        drandServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "round": 6666925,
                  "randomness": "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce",
                  "signature": "sig",
                  "previous_signature": "prev"
                }
                """.trimIndent(),
            ),
        )

        val spec = LuckyDrawSpec(
            postId = 1073388L,
            drawTimeEpochMillis = 1_795_438_800_000L,
            winnerCount = 1,
            firstFloor = 1,
            removeDuplicates = true,
        )
        val outcome = repository.resolveLuckyDraw(
            spec = spec,
            viewerUid = 101L,
            nowEpochMillis = 1_795_438_810_000L,
        ).getOrThrow()

        val floorReq = forumServer.takeRequest()
        assertEquals("GET", floorReq.method)
        assertEquals("/api/content/floor-data?postId=1073388&time=1795438800000", floorReq.path)
        assertTrue(floorReq.getHeader("Cookie")?.contains("secret_cookie_value") == true)

        val drandReq = drandServer.takeRequest()
        assertEquals("GET", drandReq.method)
        assertEquals(
            "/8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce/public/6666925",
            drandReq.path,
        )
        assertNull("Drand request must NEVER send forum Cookie", drandReq.getHeader("Cookie"))
        assertNull("Drand request must NEVER send x-dynamic-sign", drandReq.getHeader("x-dynamic-sign"))
        assertNull("Drand request must NEVER send x-csrf-token", drandReq.getHeader("x-csrf-token"))

        assertTrue(outcome.isDrawn)
        assertEquals(6_666_925L, outcome.drandRound)
        assertNotNull(outcome.beacon)
        assertEquals(1, outcome.winners.size)
    }

    @Test
    fun `pre-draw resolveLuckyDraw skips Drand request and evaluates participant eligibility`() = runTest {
        forumServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "data": [
                    { "floor_id": 0, "member_id": 10, "member_name": "OP" },
                    { "floor_id": 1, "member_id": 50, "member_name": "Participant" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val spec = LuckyDrawSpec(
            postId = 333L,
            drawTimeEpochMillis = 1_795_438_800_000L,
            winnerCount = 1,
            firstFloor = 1,
            removeDuplicates = true,
        )

        val outcome = repository.resolveLuckyDraw(
            spec = spec,
            viewerUid = 50L,
            nowEpochMillis = 1_795_438_000_000L,
        ).getOrThrow()

        assertEquals(1, forumServer.requestCount)
        assertEquals(0, drandServer.requestCount)
        assertFalse(outcome.isDrawn)
        assertNull(outcome.beacon)
        assertEquals(1, outcome.eligibleFloorCount)
        assertEquals(LuckyViewerStatus.PARTICIPATED_PENDING, outcome.viewerStatus)
    }

    @Test
    fun `404 from Drand at draw time resolves gracefully as pending beacon instead of crashing`() = runTest {
        forumServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":[{"floor_id":1,"member_id":50,"member_name":"User"}]}""",
            ),
        )
        drandServer.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val spec = LuckyDrawSpec(
            postId = 444L,
            drawTimeEpochMillis = 1_795_438_800_000L,
            winnerCount = 1,
            firstFloor = 1,
        )

        val outcome = repository.resolveLuckyDraw(
            spec = spec,
            viewerUid = 50L,
            nowEpochMillis = 1_795_438_801_000L,
        ).getOrThrow()

        assertFalse(outcome.isDrawn)
        assertNotNull(outcome.beaconPendingMessage)
        assertEquals(LuckyViewerStatus.PARTICIPATED_PENDING, outcome.viewerStatus)
    }
}
