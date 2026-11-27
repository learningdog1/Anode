package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.model.TopicAccessException
import kotlinx.coroutines.test.runTest
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

class PollAndStardustRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var cookieBridge: WebViewCookieBridge
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var repository: PollAndStardustRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("Mozilla/5.0 AnodeTest/1.0"),
            csrfTokenStore = CsrfTokenStore(),
            additionalTargetHost = baseUrl.host,
        )
        val apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
        )
        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        repository = PollAndStardustRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getVote parses pre-vote null counts and voters cleanly`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "最佳线路",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 0,
                    "items": [
                      { "vote_item_id": 11, "text": "CN2 GIA", "count": null, "voted": 0, "voters": null },
                      { "vote_item_id": 12, "text": "9929" }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val detail = repository.getVote(42L).getOrThrow()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/vote/info/42", req.path)
        assertEquals(40, req.getHeader("x-dynamic-sign")?.length)

        assertEquals(42L, detail.id)
        assertEquals("最佳线路", detail.title)
        assertTrue(detail.isPublic)
        assertFalse(detail.isLocked)
        assertFalse(detail.isMultiple)
        assertFalse(detail.hasVisibleCounts)
        assertNull(detail.options[0].count)
        assertNull(detail.options[1].count)
    }

    @Test
    fun `castVote posts voteforitem with x-dynamic-sign and immediately refreshes getVote`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "最佳线路",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 0,
                    "items": [
                      { "vote_item_id": 11, "text": "CN2 GIA", "count": 5, "voted": 1, "voters": [101] },
                      { "vote_item_id": 12, "text": "9929", "count": 2, "voted": 0, "voters": [202] }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val updated = repository.castVote(voteId = 42L, voteItemIds = listOf(11L)).getOrThrow()
        val castReq = server.takeRequest()
        assertEquals("POST", castReq.method)
        assertEquals("/api/vote/voteforitem", castReq.path)
        assertNotNull(castReq.getHeader("x-dynamic-sign"))
        assertTrue(castReq.body.readUtf8().contains("11"))

        val refreshReq = server.takeRequest()
        assertEquals("GET", refreshReq.method)
        assertEquals("/api/vote/info/42", refreshReq.path)
        assertNotNull(refreshReq.getHeader("x-dynamic-sign"))

        assertTrue(updated.hasCurrentUserVoted)
        assertEquals(7L, updated.totalVotes)
        assertEquals(listOf(101L), updated.options[0].voters)
    }

    @Test
    fun `createVote and lockVote map server DTOs and handle errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "vote": {
                    "id": 88,
                    "title": "新投票",
                    "uid": 7,
                    "isPublic": 0,
                    "locked": 0,
                    "multiple": 1,
                    "items": [
                      { "vote_item_id": 1, "text": "A" },
                      { "vote_item_id": 2, "text": "B" }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        val created = repository.createVote("新投票", multiple = true, isPublic = false, options = listOf("A", "B")).getOrThrow()
        assertEquals(88L, created.id)
        assertTrue(created.isMultiple)
        assertFalse(created.isPublic)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"vote":{"id":88,"title":"新投票","uid":7,"isPublic":0,"locked":1,"multiple":1,"items":[]}}""",
            ),
        )
        val locked = repository.lockVote(88L).getOrThrow()
        assertTrue(locked.isLocked)
    }

    @Test
    fun `prepareStardustTransfer and sendStardust attach x-dynamic-sign and map challenge or api errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"receiver_name":"Bob"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        val preview = repository.prepareStardustTransfer(202L).getOrThrow()
        assertEquals("Bob", preview.receiverName)
        val prepReq = server.takeRequest()
        assertEquals("/api/stardust/payment-prepare", prepReq.path)
        assertNotNull(prepReq.getHeader("x-dynamic-sign"))

        val sendRes = repository.sendStardust(memberId = 202L, amount = 10, refId = 123L)
        assertTrue(sendRes.isSuccess)
        val sendReq = server.takeRequest()
        assertEquals("/api/stardust/send", sendReq.path)
        assertNotNull(sendReq.getHeader("x-dynamic-sign"))
        val body = sendReq.body.readUtf8()
        assertTrue(body.contains("\"member_id\":202"))
        assertTrue(body.contains("\"diff\":10"))
        assertTrue(body.contains("\"ref_id\":123"))

        // 403 Cloudflare challenge sets session challenge flag
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html>Just a moment...</html>"))
        val failed = repository.prepareStardustTransfer(202L)
        assertTrue(failed.exceptionOrNull() is TopicAccessException.CloudflareChallengeRequired)
        assertTrue(sessionRepository.challengeRequired.value)

        // API business error propagates NodeSeekApiException
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"USER NOT FOUND"}"""))
        val apiErr = repository.prepareStardustTransfer(999L)
        assertTrue(apiErr.exceptionOrNull() is NodeSeekApiException)
    }
}
