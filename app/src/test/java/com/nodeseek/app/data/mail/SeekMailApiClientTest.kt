package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayOutputStream
import org.junit.Before
import org.junit.Test

class SeekMailApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SeekMailApiClient

    private val dummyApiKey = "sk_live_test_dummy_key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // Intentionally inject polluted cookies, custom interceptors, and redirect-following on the
        // shared base OkHttpClient to prove SeekMailApiClient strips and isolates its transport.
        val pollutedBaseClient = OkHttpClient.Builder()
            .cookieJar(
                object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                        Cookie.Builder()
                            .name("nodeseek_session")
                            .value("should_never_leak")
                            .domain(url.host)
                            .path("/")
                            .build(),
                    )
                },
            )
            .addInterceptor(
                Interceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("X-NodeSeek-Leak", "leaked")
                            .header("Cookie", "forced_cookie=1")
                            .build(),
                    )
                },
            )
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        client = SeekMailApiClient(
            baseHttpClient = pollutedBaseClient,
            baseUrl = server.url("/openapi/v1"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `requests attach Bearer auth, strip Cookie and foreign interceptors, and refuse 302 redirects`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "sl-session=abc; Path=/; HttpOnly")
                .setBody("""{"data":[]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://evil.example/steal"),
        )

        client.getMailboxes(dummyApiKey)
        client.getTags(dummyApiKey)

        val req1 = server.takeRequest()
        assertEquals("/openapi/v1/mailboxes", req1.path)
        assertEquals("Bearer $dummyApiKey", req1.getHeader("Authorization"))
        assertNull(req1.getHeader("Cookie"))
        assertNull(req1.getHeader("X-NodeSeek-Leak"))

        val req2 = server.takeRequest()
        assertEquals("/openapi/v1/tags", req2.path)
        assertEquals("Bearer $dummyApiKey", req2.getHeader("Authorization"))
        assertNull("Set-Cookie from prior response must never be echoed", req2.getHeader("Cookie"))
        assertNull(req2.getHeader("X-NodeSeek-Leak"))

        try {
            client.getMailboxes(dummyApiKey)
            fail("Expected SeekMailException when server responds with 302 redirect")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.NETWORK, e.kind)
            assertEquals(302, e.httpStatus)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `rejects non-https non-loopback baseUrl and whitespace-corrupted API keys without hitting network`() = runTest {
        try {
            SeekMailApiClient(baseUrl = "http://seek.li/openapi/v1".toHttpUrl())
            fail("Expected IllegalArgumentException for plaintext non-loopback baseUrl")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        try {
            client.getMailboxes("sk_live_bad\nheader_injection")
            fail("Expected SeekMailException with INVALID_API_KEY_FORMAT")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.INVALID_API_KEY_FORMAT, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `getMailboxes, checkMailboxAvailable, createMailbox, and 400 EMAIL_ADDRESS_UNAVAILABLE`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": 10,
                          "address": "alpha@seek.li",
                          "alias": "Main",
                          "domainId": 1,
                          "ownerId": 2,
                          "isReserved": false,
                          "doNotDisturb": true,
                          "archiveCount": 3,
                          "archiveLimit": 50,
                          "clientAccessAdmin": true,
                          "clientAccessUser": true,
                          "sendDisabled": false,
                          "forwardTo": "backup@example.com",
                          "forwardKeepCopy": true,
                          "forwardDeleteDays": 7,
                          "status": 1,
                          "sortOrder": 1,
                          "isPinned": true,
                          "isLocked": false,
                          "createdAt": "2026-03-14T02:26:16Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"available":true}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":{"id":11,"address":"fresh@nodeseek.org","status":1,"createdAt":"2026-11-16T00:00:00Z"}}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"code":"EMAIL_ADDRESS_UNAVAILABLE","message":"地址已被占用"}}"""),
        )

        val mailboxes = client.getMailboxes(dummyApiKey)
        assertEquals(1, mailboxes.size)
        val box = mailboxes.single()
        assertEquals(10L, box.id)
        assertEquals("alpha@seek.li", box.address)
        assertEquals("alpha", box.localPart)
        assertEquals("seek.li", box.domain)
        assertEquals("Main", box.alias)
        assertTrue(box.doNotDisturb)
        assertTrue(box.isPinned)
        assertEquals("backup@example.com", box.forwardTo)
        assertEquals(3, box.archiveCount)
        assertEquals(50, box.archiveLimit)
        assertEquals("/openapi/v1/mailboxes", server.takeRequest().path)

        val available = client.checkMailboxAvailable(dummyApiKey, "fresh@nodeseek.org")
        assertTrue(available)
        val checkReq = server.takeRequest()
        assertEquals("POST", checkReq.method)
        assertEquals("/openapi/v1/mailboxes/check", checkReq.requestUrl?.encodedPath)
        assertEquals("fresh@nodeseek.org", checkReq.requestUrl?.queryParameter("address"))

        val created = client.createMailbox(dummyApiKey, "fresh@nodeseek.org")
        assertEquals(11L, created.id)
        assertEquals("fresh@nodeseek.org", created.address)
        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertEquals("/openapi/v1/mailboxes", createReq.path)
        val bodyJson = Json.parseToJsonElement(createReq.body.readUtf8()).jsonObject
        assertEquals("fresh@nodeseek.org", bodyJson["address"]?.jsonPrimitive?.content)

        try {
            client.createMailbox(dummyApiKey, "taken@seek.li")
            fail("Expected SeekMailException with ADDRESS_UNAVAILABLE")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.ADDRESS_UNAVAILABLE, e.kind)
            assertEquals(400, e.httpStatus)
            assertEquals("EMAIL_ADDRESS_UNAVAILABLE", e.errorCode)
            assertEquals("地址已被占用", e.message)
        }
    }

    @Test
    fun `getMails sends snake_case query params and handles Go nil data slice cleanly`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": 201,
                          "mailboxId": 12,
                          "fromAddress": "noreply@nodeseek.com",
                          "fromName": "NodeSeek",
                          "toAddress": "me@seek.li",
                          "subject": "Login OTP",
                          "bodyText": "Your verification code is 654321",
                          "bodyHtml": "",
                          "verificationCode": "654321",
                          "isRead": false,
                          "hasAttachments": true,
                          "receivedAt": "2026-11-16T01:00:00Z",
                          "size": 2048,
                          "spamScore": 5,
                          "authScore": 40,
                          "tags": [
                            {"id": 3, "name": "重要", "color": "#ef4444", "isSystem": true}
                          ]
                        }
                      ],
                      "pagination": {
                        "limit": 20,
                        "offset": 40,
                        "total": 85
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":null,"pagination":{"limit":20,"offset":0,"total":0}}"""),
        )

        val page = client.getMails(
            apiKey = dummyApiKey,
            mailboxId = 12L,
            search = "verification",
            tagId = 3L,
            limit = 20,
            offset = 40,
        )
        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertEquals(201L, item.id)
        assertEquals("654321", item.verificationCode)
        assertEquals(2048L, item.sizeBytes)
        assertFalse(item.isRead)
        assertTrue(item.hasAttachments)
        assertEquals("重要", item.tags.single().name)
        assertEquals(20, page.limit)
        assertEquals(40, page.offset)
        assertEquals(85, page.total)
        assertTrue(page.hasMore)

        val queryReq = server.takeRequest()
        val url = queryReq.requestUrl!!
        assertEquals("/openapi/v1/mails", url.encodedPath)
        assertEquals("12", url.queryParameter("mailbox_id"))
        assertEquals("verification", url.queryParameter("search"))
        assertEquals("3", url.queryParameter("tag_id"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("40", url.queryParameter("offset"))
        assertNull(url.queryParameter("mailboxId"))
        assertNull(url.queryParameter("tagId"))

        // Go nil slice ("data": null) must decode to emptyList() without throwing.
        val emptyPage = client.getMails(apiKey = dummyApiKey)
        assertTrue(emptyPage.items.isEmpty())
        assertEquals(0, emptyPage.total)
        assertFalse(emptyPage.hasMore)
    }

    @Test
    fun `getUnreadMails, getMailDetail, attachments, tags, and contacts decode accurately`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": 301,
                          "mailboxId": 5,
                          "fromAddress": "team@seek.li",
                          "fromName": "Seek Team",
                          "toAddress": "me@seek.li",
                          "subject": "Unread notice",
                          "bodyText": "Hello unread",
                          "bodyHtml": "",
                          "isRead": false,
                          "receivedAt": "2026-11-16T02:00:00Z"
                        }
                      ],
                      "page": {
                        "hasMore": true,
                        "limit": 50,
                        "nextCursor": "cursor_2"
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": {
                        "mail": {
                          "id": 301,
                          "mailboxId": 5,
                          "fromAddress": "security@seek.li",
                          "fromName": "Seek Security",
                          "toAddress": "me@seek.li",
                          "subject": "安全校验提醒",
                          "bodyText": "",
                          "bodyHtml": "<p onclick=\"evil()\">您的验证码为：774411</p><script>alert(1)</script><img src=\"https://tracker.example/open.png\">",
                          "isRead": true,
                          "hasAttachments": true,
                          "receivedAt": "2026-11-16T02:00:00Z",
                          "size": 4096,
                          "spamScore": 12,
                          "authScore": 40,
                          "scoreDetails": "{\"auth\":{\"spf\":\"pass\",\"dkim\":\"pass\",\"dmarc\":\"pass\",\"fromMismatch\":false,\"total\":40},\"final\":{\"score\":12,\"decision\":\"inbox\",\"aiUpgrade\":false}}"
                        },
                        "tags": [
                          {"id": 1, "name": "个人", "color": "#10b981", "isSystem": true}
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[{"id":"att_1","filename":"invoice.pdf","contentType":"application/pdf","size":1024},{"attachmentId":42,"name":"audit.txt","mimeType":"text/plain","sizeBytes":512}]}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("PDF_BYTES"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":1,"name":"个人","color":"#10b981","isSystem":true}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":9,"name":"Alice","email":"alice@example.com","source":"received"}]}"""),
        )

        val unread = client.getUnreadMails(apiKey = dummyApiKey, mailboxId = 5L, limit = 50, cursor = "cursor_1")
        assertEquals(1, unread.items.size)
        assertTrue(unread.hasMore)
        assertEquals("cursor_2", unread.nextCursor)
        val unreadUrl = server.takeRequest().requestUrl!!
        assertEquals("/openapi/v1/mails/unread", unreadUrl.encodedPath)
        assertEquals("5", unreadUrl.queryParameter("mailbox_id"))
        assertEquals("50", unreadUrl.queryParameter("limit"))
        assertEquals("cursor_1", unreadUrl.queryParameter("cursor"))

        val detail = client.getMailDetail(apiKey = dummyApiKey, mailId = 301L)
        assertEquals(301L, detail.summary.id)
        assertEquals("774411", detail.summary.verificationCode)
        assertEquals("个人", detail.summary.tags.single().name)
        assertTrue(detail.hasRemoteImages)
        assertFalse(detail.sanitizedBodyHtml.contains("<script", ignoreCase = true))
        assertFalse(detail.sanitizedBodyHtml.contains("onclick", ignoreCase = true))
        assertTrue(detail.bodyText.contains("774411"))
        assertNotNull(detail.authTelemetry)
        assertEquals("pass", detail.authTelemetry!!.spf)
        assertEquals(40, detail.authTelemetry!!.authTotalScore)
        assertEquals(12, detail.authTelemetry!!.finalScore)
        assertEquals("inbox", detail.authTelemetry!!.decision)
        assertEquals("/openapi/v1/mails/301", server.takeRequest().path)

        val attachments = client.getAttachments(apiKey = dummyApiKey, mailId = 301L)
        assertEquals(2, attachments.size)
        assertEquals("att_1", attachments[0].id)
        assertEquals("invoice.pdf", attachments[0].fileName)
        assertEquals("application/pdf", attachments[0].contentType)
        assertEquals(1024L, attachments[0].sizeBytes)
        assertEquals("42", attachments[1].id)
        assertEquals("audit.txt", attachments[1].fileName)
        assertEquals("text/plain", attachments[1].contentType)
        assertEquals(512L, attachments[1].sizeBytes)
        assertEquals("/openapi/v1/mails/301/attachments", server.takeRequest().path)

        val attachmentBytes = client.downloadAttachmentBytes(apiKey = dummyApiKey, mailId = 301L, attachmentId = "att_1")
        assertArrayEquals("PDF_BYTES".toByteArray(), attachmentBytes)
        assertEquals("/openapi/v1/mails/301/attachments/att_1", server.takeRequest().path)

        val tags = client.getTags(apiKey = dummyApiKey)
        assertEquals("#10b981", tags.single().color)
        assertEquals("/openapi/v1/tags", server.takeRequest().path)

        val contacts = client.getContacts(apiKey = dummyApiKey, limit = 25)
        assertEquals("alice@example.com", contacts.single().email)
        assertEquals("/openapi/v1/contacts?limit=25", server.takeRequest().path)
    }

    @Test
    fun `polymorphic error payloads map to SeekMailException and X-Ratelimit headers update latestRateLimit`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Ratelimit-Limit", "60")
                .addHeader("X-Ratelimit-Remaining", "58")
                .addHeader("X-Ratelimit-Reset", "1763251200")
                .setBody("""{"data":[]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"缺少或格式错误的 API Key"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"无效的ID"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"FORBIDDEN","message":"无权访问此文件夹"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":"NOT_FOUND","message":"邮箱不存在"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":"AUTH_RATE_LIMITED","message":"认证失败次数过多，请稍后再试"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setBody("Bad Gateway"),
        )

        client.getTags(dummyApiKey)
        val rl = client.latestRateLimit.value
        assertNotNull(rl)
        assertEquals(60, rl!!.limit)
        assertEquals(58, rl.remaining)
        assertEquals(1763251200L, rl.resetEpochSeconds)

        try {
            client.getMailboxes(dummyApiKey)
            fail("Expected 401 SeekMailException")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.UNAUTHORIZED, e.kind)
            assertEquals(401, e.httpStatus)
            assertEquals("UNAUTHORIZED", e.errorCode)
            assertEquals("缺少或格式错误的 API Key", e.message)
        }

        try {
            client.getMailDetail(dummyApiKey, 999L)
            fail("Expected 400 string error SeekMailException")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.SERVER, e.kind)
            assertEquals(400, e.httpStatus)
            assertTrue(e.message.contains("无效的ID"))
        }

        try {
            client.getMails(dummyApiKey)
            fail("Expected 403 SeekMailException")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.FORBIDDEN, e.kind)
            assertEquals(403, e.httpStatus)
        }

        try {
            client.getMailDetail(dummyApiKey, 404L)
            fail("Expected 404 SeekMailException")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.NOT_FOUND, e.kind)
            assertEquals(404, e.httpStatus)
        }

        try {
            client.getMailboxes(dummyApiKey)
            fail("Expected 429 SeekMailException")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.RATE_LIMITED, e.kind)
            assertEquals(429, e.httpStatus)
        }

        try {
            client.getMailboxes(dummyApiKey)
            fail("Expected 502 SeekMailException")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.SERVER, e.kind)
            assertEquals(502, e.httpStatus)
        }
    }

    @Test
    fun `downloadAttachmentToStream streams bytes, enforces maxBytes cap, and rejects unsafe attachment IDs`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("STREAMED_PAYLOAD_1234"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("EXCEEDS_MAX_LIMIT_PAYLOAD"))

        val output = ByteArrayOutputStream()
        val written = client.downloadAttachmentToStream(
            apiKey = dummyApiKey,
            mailId = 301L,
            attachmentId = "att_stream_1",
            outputStream = output,
            maxBytes = 1024L,
        )
        assertEquals("STREAMED_PAYLOAD_1234".length.toLong(), written)
        assertArrayEquals("STREAMED_PAYLOAD_1234".toByteArray(), output.toByteArray())
        assertEquals("/openapi/v1/mails/301/attachments/att_stream_1", server.takeRequest().path)

        try {
            client.downloadAttachmentToStream(
                apiKey = dummyApiKey,
                mailId = 301L,
                attachmentId = "att_huge",
                outputStream = ByteArrayOutputStream(),
                maxBytes = 8L,
            )
            fail("Expected SeekMailException when attachment exceeds maxBytes")
        } catch (e: SeekMailException) {
            assertEquals(SeekMailErrorKind.SERVER, e.kind)
        }
        server.takeRequest()

        val requestsBeforeInvalid = server.requestCount
        for (badId in listOf("", "   ", ".", "..")) {
            try {
                client.downloadAttachmentToStream(
                    apiKey = dummyApiKey,
                    mailId = 301L,
                    attachmentId = badId,
                    outputStream = ByteArrayOutputStream(),
                )
                fail("Expected SeekMailException for invalid attachmentId '$badId'")
            } catch (e: SeekMailException) {
                assertEquals(SeekMailErrorKind.NOT_FOUND, e.kind)
            }
        }
        assertEquals(requestsBeforeInvalid, server.requestCount)
    }
}
