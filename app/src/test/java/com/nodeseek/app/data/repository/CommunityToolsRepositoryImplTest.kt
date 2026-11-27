package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.model.InvitePaymentMethod
import com.nodeseek.app.domain.model.StardustRecordType
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
import java.time.Instant
import java.util.Base64

class CommunityToolsRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var csrfTokenStore: CsrfTokenStore
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var repository: CommunityToolsRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        csrfTokenStore = CsrfTokenStore()
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("Mozilla/5.0 AnodeRepoTest/1.0"),
            csrfTokenStore = csrfTokenStore,
            additionalTargetHost = baseUrl.host,
        )
        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
        )
        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        repository = CommunityToolsRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getRecentCreditAnalytics fetches multiple pages and stops once crossing 30d cutoff`() = runTest {
        val page1 = buildString {
            append("""{"success":true,"total":50,"data":[""")
            for (i in 1..20) {
                if (i > 1) append(',')
                val day = (25 - (i % 15)).toString().padStart(2, '0')
                append("""[5,${1600 + i},"每日签到奖励","2026-11-$day 08:00:00"]""")
            }
            append("]}")
        }
        val page2 = """
            {
              "success": true,
              "total": 50,
              "data": [
                [-1, 1599, "给评论加鸡腿", "2026-11-01 09:00:00"],
                [10, 1600, "每日签到奖励", "2026-09-15 08:00:00"]
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(page1))
        server.enqueue(MockResponse().setResponseCode(200).setBody(page2))

        val now = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli()
        val result = repository.getRecentCreditAnalytics(
            currentCoins = 1650,
            windowDays = 30,
            maxPagesToFetch = 5,
            nowEpochMillis = now,
        ).getOrThrow()

        // Should have requested page-1 and page-2, then stopped because page 2 crossed cutoff
        assertEquals(2, server.requestCount)
        assertEquals("/api/account/credit/page-1", server.takeRequest().path)
        assertEquals("/api/account/credit/page-2", server.takeRequest().path)

        assertEquals(22, result.fetchedEntries.size)
        assertEquals(21, result.analytics.windowEntriesCount)
        assertEquals(20, result.analytics.checkInCount)
        assertEquals(1, result.analytics.drumstickGiftExpenseCoins)
        assertTrue(result.firstPage.hasNextPage)
    }

    @Test
    fun `getStardustLedger attaches x-dynamic-sign, maps type labels, and supports cursor pagination`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "records": [
                    {"id": 981, "diff": 10, "result": 42, "type": "transfer", "created_at": "2026-11-24T01:00:00Z", "peer_id": 202},
                    {"id": 980, "diff": -5, "result": 32, "type": "upvote", "created_at": "2026-11-23T14:20:00Z", "peer_id": 303},
                    {"id": 979, "diff": 37, "result": 37, "type": "system", "created_at": "2026-11-20T00:00:00Z", "peer_id": null}
                  ],
                  "exist_more": true,
                  "cursor": 979
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "records": [
                    {"id": 978, "diff": -500, "result": 0, "type": "buyCode", "created_at": "2026-11-18T00:00:00Z", "peer_id": null}
                  ],
                  "exist_more": false,
                  "cursor": null
                }
                """.trimIndent(),
            ),
        )

        val page1 = repository.getStardustLedger(memberId = 101L, count = 10).getOrThrow()
        assertEquals(3, page1.records.size)
        assertEquals("转账", page1.records[0].typeLabel)
        assertEquals(202L, page1.records[0].peerUid)
        assertEquals("点赞", page1.records[1].typeLabel)
        assertEquals("系统", page1.records[2].typeLabel)
        assertNull(page1.records[2].peerUid)
        assertTrue(page1.hasMore)
        assertEquals(979L, page1.nextCursor)

        val page2 = repository.getStardustLedger(
            memberId = 101L,
            count = 10,
            beforeId = page1.nextCursor,
            type = "buyCode",
        ).getOrThrow()
        assertFalse(page2.hasMore)
        assertNull(page2.nextCursor)
        assertEquals(StardustRecordType.BUY_CODE.label, page2.records.single().typeLabel)

        val req1 = server.takeRequest()
        assertFalse(req1.getHeader("x-dynamic-sign").isNullOrBlank())
        assertTrue(req1.path!!.contains("member_id=101"))

        val req2 = server.takeRequest()
        assertFalse(req2.getHeader("x-dynamic-sign").isNullOrBlank())
        assertTrue(req2.path!!.contains("before_id=979"))
        assertTrue(req2.path!!.contains("type=buyCode"))
    }

    @Test
    fun `buyInviteCode sends csrf headers, triggers session refresh, and rejects missing csrf token`() = runTest {
        // Without CSRF token -> fails immediately without hitting network
        val missingCsrf = repository.buyInviteCode(InvitePaymentMethod.COIN)
        assertTrue(missingCsrf.isFailure)
        assertTrue(missingCsrf.exceptionOrNull() is NodeSeekApiException)
        assertEquals(0, server.requestCount)

        // GET /api/invite/config returns x-csrf-token header
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("x-csrf-token", "csrf-invite-999")
                .setBody("""{"success":true,"payment":{"coin":{"enabled":true,"cost":100},"stardust":{"enabled":true,"cost":500}}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"code":"NS-INV-2026-NEW1","message":"购买成功"}"""),
        )
        // Session refresh call triggered on purchase
        val configB64 = Base64.getEncoder().encodeToString(
            """{"user":{"member_id":101,"member_name":"Alice","coin":1550,"stardust":42},"allCategory":[]}"""
                .toByteArray(Charsets.UTF_8),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$configB64</script></body></html>"""),
        )

        val config = repository.getInviteConfig().getOrThrow()
        assertEquals(100, config.coinOption?.cost)
        assertEquals(500, config.stardustOption?.cost)

        val purchase = repository.buyInviteCode(InvitePaymentMethod.COIN).getOrThrow()
        assertEquals("NS-INV-2026-NEW1", purchase.code)
        assertEquals(InvitePaymentMethod.COIN, purchase.paymentMethod)
        assertEquals(1550, sessionRepository.sessionState.value?.drumsticks)

        val _configReq = server.takeRequest()
        val buyReq = server.takeRequest()
        assertEquals("/api/invite/buyOne", buyReq.path)
        assertEquals("simple-token", buyReq.getHeader("x-csrf-challenge"))
        assertEquals("csrf-invite-999", buyReq.getHeader("x-csrf-token"))
        assertEquals("""{"payment":"coin"}""", buyReq.body.readUtf8())
    }

    @Test
    fun `listModerationRulings and detail paginate cleanly and propagate Cloudflare challenge`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "total": 25,
                  "data": [
                    {"id":701,"member_id":9901,"member_name":"spammer99","reason":"恶意刷屏","action":"封禁 30 天","created_at":"2026-11-20T10:00:00Z"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "success": true,
                  "data": {
                    "id": 701,
                    "member_id": 9901,
                    "member_name": "spammer99",
                    "reason": "恶意刷屏",
                    "action": "封禁 30 天",
                    "evidence": "连续发广告",
                    "created_at": "2026-11-20T10:00:00Z"
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("Just a moment..."),
        )

        val page = repository.listModerationRulings(page = 1, search = "spam").getOrThrow()
        assertTrue(page.hasNextPage)
        assertEquals(25, page.totalCount)
        assertEquals(701L, page.items.single().id)

        val detail = repository.getModerationRulingDetail(701L).getOrThrow()
        assertEquals("连续发广告", detail.evidence)

        val challengeErr = repository.listModerationRulings(page = 2).exceptionOrNull()
        assertNotNull(challengeErr)
        assertTrue(challengeErr is TopicAccessException.CloudflareChallengeRequired)
        assertTrue(sessionRepository.challengeRequired.value)
    }

    @Test
    fun `getRecentCreditAnalytics preserves Page 1 analytics on Page 2 API failure while propagating Page 1 failure and CF challenge`() = runTest {
        val page1 = buildString {
            append("""{"success":true,"total":40,"data":[""")
            for (i in 1..20) {
                if (i > 1) append(',')
                val day = (25 - (i % 10)).toString().padStart(2, '0')
                append("""[5,${1600 + i},"每日签到奖励","2026-11-$day 08:00:00"]""")
            }
            append("]}")
        }
        // 1. Page 1 success + Page 2 {"success":false,"message":"rate limit"} -> preserves Page 1 analytics
        server.enqueue(MockResponse().setResponseCode(200).setBody(page1))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"rate limit"}"""))

        val now = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli()
        val partialResult = repository.getRecentCreditAnalytics(
            currentCoins = 1650,
            windowDays = 30,
            maxPagesToFetch = 5,
            nowEpochMillis = now,
        ).getOrThrow()

        assertEquals(20, partialResult.fetchedEntries.size)
        assertEquals(20, partialResult.analytics.checkInCount)
        assertEquals(100, partialResult.analytics.checkInTotalCoins)
        assertTrue(partialResult.firstPage.hasNextPage)

        // 2. Page 1 failure still propagates as Result.failure(NodeSeekApiException)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"auth required"}"""))
        val page1Fail = repository.getRecentCreditAnalytics(
            currentCoins = 1650,
            windowDays = 30,
            maxPagesToFetch = 5,
            nowEpochMillis = now,
        )
        assertTrue(page1Fail.isFailure)
        assertTrue(page1Fail.exceptionOrNull() is NodeSeekApiException)

        // 3. Page 2 Cloudflare challenge still propagates
        server.enqueue(MockResponse().setResponseCode(200).setBody(page1))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("Just a moment..."),
        )
        val page2Challenge = repository.getRecentCreditAnalytics(
            currentCoins = 1650,
            windowDays = 30,
            maxPagesToFetch = 5,
            nowEpochMillis = now,
        )
        assertTrue(page2Challenge.isFailure)
        assertTrue(page2Challenge.exceptionOrNull() is TopicAccessException.CloudflareChallengeRequired)
        assertTrue(sessionRepository.challengeRequired.value)
    }
}
