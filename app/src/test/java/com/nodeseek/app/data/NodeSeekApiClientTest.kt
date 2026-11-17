package com.nodeseek.app.data

import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.SameOriginHttpResponse
import com.nodeseek.app.data.network.SameOriginTransport
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.network.computeDynamicSign
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.Base64

class NodeSeekApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var cookieBridge: WebViewCookieBridge
    private lateinit var userAgentProvider: UserAgentProvider
    private lateinit var csrfTokenStore: CsrfTokenStore
    private lateinit var client: NodeSeekApiClient

    private val testUserAgent = "Mozilla/5.0 (Linux; Android 15) NodePulseUnitTest/1.0"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        cookieBridge = WebViewCookieBridge(forceInMemory = true)
        userAgentProvider = UserAgentProvider(testUserAgent)
        csrfTokenStore = CsrfTokenStore()

        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            csrfTokenStore = csrfTokenStore,
            additionalTargetHost = baseUrl.host,
        )

        client = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `signIn sends Turnstile headers, verifyTwoFactor omits them, and signOut sends x-csrf-challenge`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "ns_session=abc123; Path=/")
                .setBody("""{"success":true,"need2FA":1,"otpSession":"otp_sess_99"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true}"""),
        )

        val step1 = client.signIn("alice", "pw123", "turnstile_tok_xyz")
        assertTrue(step1.success)
        assertEquals(true, step1.need2FA)
        assertEquals("otp_sess_99", step1.otpSession)

        val req1 = server.takeRequest()
        assertEquals("/api/account/signIn", req1.path)
        assertEquals("turnstile_tok_xyz", req1.getHeader("x-captcha-token"))
        assertEquals("turnstile", req1.getHeader("x-captcha-source"))
        assertEquals(testUserAgent, req1.getHeader("User-Agent"))

        val step2 = client.verifyTwoFactor("654321", "otp_sess_99")
        assertTrue(step2.success)

        val req2 = server.takeRequest()
        assertEquals("/api/account/signIn", req2.path)
        assertNull(req2.getHeader("x-captcha-token"))
        assertNull(req2.getHeader("x-captcha-source"))
        assertTrue(req2.getHeader("Cookie")?.contains("ns_session=abc123") == true)

        client.signOut()
        val req3 = server.takeRequest()
        assertEquals("/api/account/signOut", req3.path)
        assertEquals("simple-token", req3.getHeader("x-csrf-challenge"))
    }

    @Test
    fun `attendance distinguishes null record from zero-gain record and supports fixed or random checkIn`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"record":null,"order":null,"total":500,"list":[]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"gain":5,"current":205,"message":"签到成功"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"record":{"gain":0},"order":501,"total":501,"list":[]}"""),
        )

        val before = client.getAttendanceBoard(page = 1)
        assertFalse(before.hasCheckedInToday)
        assertNull(before.record)

        val checkInRes = client.checkIn(random = false)
        assertTrue(checkInRes.success)
        assertEquals(5, checkInRes.gain)
        assertEquals("/api/attendance?random=false", server.takeRequest().let { server.takeRequest().path })

        val zeroGainBoard = client.getAttendanceBoard(page = 1)
        assertTrue(zeroGainBoard.hasCheckedInToday)
        assertEquals(0, zeroGainBoard.record?.gain)
    }

    @Test
    fun `content publish and edit attach 16-char hex csrf-token and editDiscussion omits category`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        client.createDiscussion(
            title = "新主题",
            markdownContent = "正文内容",
            categorySlug = "tech",
            requiredRank = 0,
        )
        val createReq = server.takeRequest()
        val createCsrf = createReq.getHeader("csrf-token")
        assertNotNull(createCsrf)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(createCsrf!!))
        val parsedCreateBody = Json.parseToJsonElement(createReq.body.readUtf8()).jsonObject
        assertEquals("new-discussion", parsedCreateBody["mode"]?.jsonPrimitive?.content)
        assertEquals("tech", parsedCreateBody["category"]?.jsonPrimitive?.content)
        assertEquals("0", parsedCreateBody["rank"]?.jsonPrimitive?.content)

        client.editDiscussion(
            postId = 777L,
            title = "修改后标题",
            markdownContent = "修改后正文",
            requiredRank = 2,
        )
        val editReq = server.takeRequest()
        val editCsrf = editReq.getHeader("csrf-token")
        assertNotNull(editCsrf)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(editCsrf!!))

        val parsedEditBody = Json.parseToJsonElement(editReq.body.readUtf8()).jsonObject
        assertEquals("edit-discussion", parsedEditBody["mode"]?.jsonPrimitive?.content)
        assertFalse("edit-discussion must NOT send category", parsedEditBody.containsKey("category"))
    }

    @Test
    fun `vote and stardust requests attach verified x-dynamic-sign SHA-1 header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"vote":{"id":42,"title":"Poll","uid":1,"isPublic":1,"locked":0,"multiple":0,"items":[]}}"""),
        )

        val voteResp = client.createVote(
            title = "你最常用的系统？",
            multiple = false,
            isPublic = true,
            options = listOf("Debian", "Alpine"),
        )
        assertEquals(42L, voteResp.vote?.id)

        val recorded = server.takeRequest()
        val rawBody = recorded.body.readUtf8()
        val fullUrl = recorded.requestUrl!!.toString()
        val expectedSign = computeDynamicSign(
            method = "POST",
            fullUrl = fullUrl,
            userAgent = testUserAgent,
            rawBody = rawBody,
        )
        assertEquals(expectedSign, recorded.getHeader("x-dynamic-sign"))
    }

    @Test
    fun `searchTopicsPage encodes literal plus as percent-2B on the wire`() = runTest {
        val configB64 = Base64.getEncoder().encodeToString(
            """{"allCategory":[{"key":"dev","cn_text":"Dev"}]}""".toByteArray(Charsets.UTF_8),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$configB64</script></body></html>"""),
        )

        client.searchTopicsPage(query = "c++20", categorySlug = "dev", sortBy = "postTime", page = 2)
        val req = server.takeRequest()
        assertEquals("/search?q=c%2B%2B20&page=2&sortBy=postTime&category=dev", req.path)
    }

    @Test
    fun `markReplyNotificationsViewed uses server typo key replys and supports mark-all query`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        client.markReplyNotificationsViewed(ids = listOf(11L, 12L))
        val singleReq = server.takeRequest()
        assertEquals("/api/notification/reply-to-me/markViewed", singleReq.path)
        assertEquals("""{"replys":[11,12]}""", singleReq.body.readUtf8())

        client.markReplyNotificationsViewed(ids = null)
        val allReq = server.takeRequest()
        assertEquals("/api/notification/reply-to-me/markViewed?all=true", allReq.path)
        assertEquals(0L, allReq.bodySize)
    }

    @Test
    fun `credit ledger tuple decoding and NodeImage upload work end-to-end`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"total":2,"data":[[5,1655,"每日签到奖励","2026-10-15 08:00:00"],[-1,1654,"给评论加鸡腿","2026-10-15 09:00:00"]]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"links":{"direct":"https://img.nodeimage.com/i/abc.png"}}"""),
        )

        val ledger = client.getCreditLedger(page = 1)
        assertEquals(2, ledger.data.size)
        assertEquals(5, ledger.data[0].change)
        assertEquals(1655, ledger.data[0].balance)
        assertEquals("每日签到奖励", ledger.data[0].reason)

        val validApiKey = "0123456789abcdef0123456789abcdef"
        val directUrl = client.uploadImageToNodeImage(
            imageBytes = byteArrayOf(1, 2, 3, 4),
            apiKey = validApiKey,
            mimeType = "image/png",
            deleteAfterDays = 30,
        )
        assertEquals("https://img.nodeimage.com/i/abc.png", directUrl)

        val _ledgerReq = server.takeRequest()
        val uploadReq = server.takeRequest()
        assertEquals("/api/upload", uploadReq.path)
        assertEquals(validApiKey, uploadReq.getHeader("X-API-Key"))
        assertNull("NodeImage upload must never carry forum cookies", uploadReq.getHeader("Cookie"))
        assertTrue(uploadReq.body.readUtf8().contains("name=\"deleteDays\""))
    }

    @Test
    fun `reactToComment sends browser XHR headers and post Referer and falls back to SameOriginTransport on high risk action`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":false,"message":"high risk action"}"""),
        )

        var capturedFallbackUrl: String? = null
        var capturedFallbackReferer: String? = null
        val fakeSameOrigin = object : SameOriginTransport {
            override fun canAttemptWithExistingClearance(): Boolean = true
            override fun markVerifiedByUser() = Unit
            override suspend fun fetchHtml(url: String): SameOriginHttpResponse =
                error("Unexpected fetchHtml")

            override suspend fun executeApiRequest(
                method: String,
                url: String,
                refererUrl: String,
                headers: Map<String, String>,
                body: String?,
            ): SameOriginHttpResponse {
                capturedFallbackUrl = url
                capturedFallbackReferer = refererUrl
                return SameOriginHttpResponse(
                    statusCode = 200,
                    contentType = "application/json",
                    body = """{"success":true,"current":9,"coin":188,"message":"加鸡腿成功"}""",
                )
            }
        }

        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            csrfTokenStore = csrfTokenStore,
            additionalTargetHost = server.url("/").host,
        )
        val clientWithFallback = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = server.url("/"),
            sameOriginTransport = fakeSameOrigin,
        )

        val result = clientWithFallback.reactToComment(
            commentId = 4567L,
            reactionSegment = "like",
            postId = 8910L,
        )
        assertTrue(result.success)
        assertEquals(9, result.current)
        assertEquals(188, result.coin)

        val recorded = server.takeRequest()
        assertEquals("/api/statistics/like", recorded.path)
        assertEquals("https://www.nodeseek.com/post-8910-1", recorded.getHeader("Referer"))
        assertEquals("https://www.nodeseek.com", recorded.getHeader("Origin"))
        assertEquals("XMLHttpRequest", recorded.getHeader("X-Requested-With"))
        assertEquals("same-origin", recorded.getHeader("Sec-Fetch-Site"))
        assertEquals("cors", recorded.getHeader("Sec-Fetch-Mode"))
        assertEquals("https://www.nodeseek.com/post-8910-1", capturedFallbackReferer)
        assertTrue(capturedFallbackUrl?.endsWith("/api/statistics/like") == true)
    }

    @Test
    fun `fetchThreadPage falls back to SameOriginTransport when OkHttp hits JA4 cf_clearance challenge after verification`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><head><title>Just a moment...</title></head></html>"),
        )

        val configB64 = Base64.getEncoder().encodeToString(
            """
            {
              "allCategory":[{"key":"daily","cn_text":"日常"}],
              "postData":{
                "postId":909,
                "postPage":1,
                "postPageCount":1,
                "title":"验证后成功加载的帖子",
                "category":"daily",
                "categoryWord":"日常",
                "views":50,
                "comments":[{"commentId":1,"floorIndex":0,"poster":{"uid":7,"name":"Author"},"markdown":"正文"}]
              }
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val fakeSameOrigin = object : SameOriginTransport {
            private var verified = true
            override fun canAttemptWithExistingClearance(): Boolean = verified
            override fun markVerifiedByUser() {
                verified = true
            }

            override suspend fun fetchHtml(url: String): SameOriginHttpResponse =
                SameOriginHttpResponse(
                    statusCode = 200,
                    contentType = "text/html; charset=utf-8",
                    body = """<html><body><div id="nsk-body"><div class="nsk-post"></div></div><script id="temp-script" type="application/json">$configB64</script></body></html>""",
                )

            override suspend fun executeApiRequest(
                method: String,
                url: String,
                refererUrl: String,
                headers: Map<String, String>,
                body: String?,
            ): SameOriginHttpResponse = error("Unexpected executeApiRequest")
        }

        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            csrfTokenStore = csrfTokenStore,
            additionalTargetHost = server.url("/").host,
        )
        val clientWithFallback = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = server.url("/"),
            sameOriginTransport = fakeSameOrigin,
        )

        val thread = clientWithFallback.fetchThreadPage(postId = 909L, page = 1)
        assertEquals("验证后成功加载的帖子", thread.postData.title)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `high risk action without fallback translates to Chinese message and preserves isHighRiskAction flag`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":false,"message":"high risk action"}"""),
        )
        try {
            client.reactToComment(commentId = 10L, reactionSegment = "upvote", postId = 20L)
            org.junit.Assert.fail("Expected NodeSeekApiException")
        } catch (e: NodeSeekApiException) {
            assertTrue(e.isHighRiskAction)
            assertTrue(e.message?.contains("高风险风控") == true)
        } catch (_: CloudflareChallengeException) {
            org.junit.Assert.fail("Did not expect CloudflareChallengeException")
        }
    }

    @Test
    fun `getUserProfile sends public readme query with space Referer and supports both detail and data envelopes`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"success":true,"detail":{"member_id":42,"member_name":"holybug","readme":"# Hello","nPost":9,"nComment":15,"coin":320}}
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"data":{"member_name":"altEnvelopeUser","readme":"From data envelope","nPost":3}}
                    """.trimIndent(),
                ),
        )

        val first = client.getUserProfile(uid = 42L, includeReadme = true, includeEditableSignature = false)
        assertEquals("holybug", first.resolvedDetail?.memberName)
        assertEquals("# Hello", first.resolvedDetail?.readme)
        assertEquals(9, first.resolvedDetail?.nPost)

        val second = client.getUserProfile(uid = 99L, includeReadme = true, includeEditableSignature = true)
        assertEquals("altEnvelopeUser", second.resolvedDetail?.memberName)
        assertNull(second.resolvedDetail?.memberId)

        val req1 = server.takeRequest()
        assertEquals("/api/account/getInfo/42?readme=1", req1.path)
        assertEquals("https://www.nodeseek.com/space/42", req1.getHeader("Referer"))

        val req2 = server.takeRequest()
        assertEquals("/api/account/getInfo/99?readme=1&signature=1", req2.path)
        assertEquals("https://www.nodeseek.com/setting", req2.getHeader("Referer"))
    }

    @Test
    fun `listDirectMessages parses system notifications for likes drumsticks and stardust with null or missing sender fields`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "success": true,
                      "msgArray": [
                        {
                          "id": 801,
                          "sender_id": 0,
                          "sender_name": null,
                          "receiver_id": 101,
                          "receiver_name": null,
                          "content": "<a href=\"/post-123-1\">测评贴</a> 收到 1 个点赞",
                          "is_markdown": false,
                          "viewed": 0,
                          "created_at": "2026-11-16T01:00:00Z"
                        },
                        {
                          "id": 802,
                          "sender_id": null,
                          "type": "coin",
                          "title": "鸡腿奖励",
                          "content": "Alice 给你的评论加了 1 个鸡腿",
                          "post_id": 124,
                          "floor_id": 5,
                          "viewed": 0,
                          "created_at": "2026-11-16T01:01:00Z"
                        },
                        {
                          "id": 803,
                          "sender_id": "system",
                          "system": true,
                          "content": "收到来自 Bob 的 10 个星辰",
                          "link": "/post-125-1#2",
                          "viewed": 1,
                          "created_at": "2026-11-16T01:02:00Z"
                        },
                        {
                          "id": 804,
                          "sender_id": 202,
                          "sender_name": "Alice",
                          "receiver_id": 101,
                          "receiver_name": "Me",
                          "content": "私信正文",
                          "viewed": 0,
                          "created_at": "2026-11-16T00:59:00Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val response = client.listDirectMessages()
        val threads = response.groupThreadsForViewer(viewerUid = 101L)
        assertEquals(2, threads.size)

        val sysThread = threads[0]
        assertEquals(0L, sysThread.peerUid)
        assertTrue(sysThread.isSystem)
        assertEquals("系统通知", sysThread.peerName)
        assertFalse(sysThread.viewed)
        assertEquals(2, sysThread.unreadCount)
        assertTrue(sysThread.isMarkdown)
        assertTrue(sysThread.preview.contains("收到来自 Bob 的 10 个星辰"))
        assertTrue(sysThread.preview.contains("https://www.nodeseek.com/post-125-1#2"))

        val peerThread = threads[1]
        assertEquals(202L, peerThread.peerUid)
        assertFalse(peerThread.isSystem)
        assertEquals("Alice", peerThread.peerName)
        assertFalse(peerThread.viewed)
        assertEquals(1, peerThread.unreadCount)
    }

    @Test
    fun `getConversation and unreadCount handle null talkTo broadcast system messages and split category counts`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "success": true,
                      "talkTo": null,
                      "msgArray": [
                        {
                          "id": 805,
                          "sender_id": 0,
                          "receiver_id": 0,
                          "content": "你的主题收到 5 个鸡腿",
                          "viewed": 0,
                          "created_at": "2026-11-16T02:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"unreadCount":{"reply":1,"at":1,"message":2,"system":1}}"""),
        )

        val conv = client.getConversation(peerUid = 0L)
        assertNull(conv.talkTo)
        assertEquals(1, conv.allMessages().size)
        val row = conv.allMessages().single()
        assertTrue(row.isSystemMessage(viewerUid = 101L))
        assertTrue(row.belongsToConversation(viewerUid = 101L, peerUid = 0L))
        assertEquals("鸡腿", row.inferNoticeCategory())

        val count = client.getUnreadNotificationCount()
        assertEquals(5, count)
    }

    @Test
    fun `signOut sends Accept and x-csrf-challenge and falls back to SameOriginTransport on 403`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"success":false,"message":"Forbidden"}"""),
        )

        var fallbackUrl: String? = null
        var fallbackHeaders: Map<String, String> = emptyMap()
        var clearedSessionContext = false
        val fakeSameOrigin = object : SameOriginTransport {
            override fun canAttemptWithExistingClearance(): Boolean = true
            override fun markVerifiedByUser() = Unit
            override fun clearSessionContext() {
                clearedSessionContext = true
            }

            override suspend fun fetchHtml(url: String): SameOriginHttpResponse =
                error("Unexpected fetchHtml")

            override suspend fun executeApiRequest(
                method: String,
                url: String,
                refererUrl: String,
                headers: Map<String, String>,
                body: String?,
            ): SameOriginHttpResponse {
                fallbackUrl = url
                fallbackHeaders = headers
                return SameOriginHttpResponse(
                    statusCode = 200,
                    contentType = "application/json",
                    body = """{"success":true}""",
                )
            }
        }

        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            csrfTokenStore = csrfTokenStore,
            additionalTargetHost = server.url("/").host,
        )
        val clientWithFallback = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = server.url("/"),
            sameOriginTransport = fakeSameOrigin,
        )

        clientWithFallback.signOut()

        val recorded = server.takeRequest()
        assertEquals("/api/account/signOut", recorded.path)
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals("simple-token", recorded.getHeader("x-csrf-challenge"))
        assertEquals("https://www.nodeseek.com", recorded.getHeader("Origin"))
        assertEquals("https://www.nodeseek.com/", recorded.getHeader("Referer"))
        assertTrue(fallbackUrl?.endsWith("/api/account/signOut") == true)
        assertEquals("simple-token", fallbackHeaders["x-csrf-challenge"])
        assertTrue(clearedSessionContext)
    }

    @Test
    fun `signIn supports snake_case 2FA payloads, falls back on challenge without double-submitting structured errors, and flags expired OTP`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><title>Just a moment...</title></html>"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"success":false,"message":"OTP_EXPIRED_OR_NOT_EXIST"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"success":false,"message":"用户名或密码错误"}"""),
        )

        var fallbackCount = 0
        var capturedCaptchaToken: String? = null
        val fakeSameOrigin = object : SameOriginTransport {
            override fun canAttemptWithExistingClearance(): Boolean = false
            override fun markVerifiedByUser() = Unit
            override suspend fun fetchHtml(url: String): SameOriginHttpResponse =
                error("Unexpected fetchHtml")

            override suspend fun executeApiRequest(
                method: String,
                url: String,
                refererUrl: String,
                headers: Map<String, String>,
                body: String?,
            ): SameOriginHttpResponse {
                fallbackCount += 1
                capturedCaptchaToken = headers["x-captcha-token"]
                return SameOriginHttpResponse(
                    statusCode = 200,
                    contentType = "application/json",
                    body = """{"success":false,"need_2fa":1,"otp_session":"otp_snake_42"}""",
                )
            }
        }

        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            csrfTokenStore = csrfTokenStore,
            additionalTargetHost = server.url("/").host,
        )
        val clientWithFallback = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = server.url("/"),
            sameOriginTransport = fakeSameOrigin,
        )

        val step1 = clientWithFallback.signIn("alice", "pw123", "turnstile_live_tok")
        assertEquals(true, step1.resolvedNeed2FA)
        assertEquals("otp_snake_42", step1.resolvedOtpSession)
        assertEquals("turnstile_live_tok", capturedCaptchaToken)
        assertEquals(1, fallbackCount)
        val req1 = server.takeRequest()
        assertEquals("https://www.nodeseek.com/signIn.html", req1.getHeader("Referer"))

        try {
            clientWithFallback.verifyTwoFactor("123456", "otp_snake_42")
            org.junit.Assert.fail("Expected NodeSeekApiException for expired OTP")
        } catch (e: NodeSeekApiException) {
            assertTrue(e.isOtpExpired)
            assertEquals(1, fallbackCount) // Must not retry structured OTP failure via fallback
        }
        val req2 = server.takeRequest()
        assertEquals("https://www.nodeseek.com/signIn.html", req2.getHeader("Referer"))
        assertNull(req2.getHeader("x-captcha-token"))

        try {
            clientWithFallback.signIn("alice", "wrong_pw", "turnstile_tok_2")
            org.junit.Assert.fail("Expected NodeSeekApiException for wrong password")
        } catch (e: NodeSeekApiException) {
            assertEquals("用户名或密码错误", e.serverMessage)
            assertEquals(1, fallbackCount) // Must not double-spend single-use Turnstile token on structured server error
        }
    }

    @Test
    fun `getUnreadNotificationCount supports plural camelCase keys, partial all vs sum, top-level counts, and primitive values`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"unreadCount":{"all":1,"replys":1,"atMe":1,"messages":2,"system":1}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"unViewedCount":7}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"replys":2,"atMe":1,"messages":3,"message":"ok"}"""),
        )

        assertEquals(5, client.getUnreadNotificationCount())
        assertEquals(7, client.getUnreadNotificationCount())
        assertEquals(6, client.getUnreadNotificationCount())
    }
}
