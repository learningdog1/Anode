package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.RadarWatermarkStore
import com.nodeseek.app.data.local.RadarWatermarks
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher as MockWebDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests reactive unread state synchronization, optimistic read decrements, and anti-stale guards
 * in [InboxRepositoryImpl] across notifications, direct message threads, and HTML piggybacks.
 */
class InboxUnreadStateTest {

    private lateinit var server: MockWebServer
    private lateinit var cookieBridge: WebViewCookieBridge
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var inboxRepository: InboxRepositoryImpl
    private val fakeClockMs = AtomicLong(1_700_000_000_000L)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("AnodeInboxUnreadTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )

        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        inboxRepository = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            clockMillis = { fakeClockMs.get() },
        )
        sessionRepository.unreadCountListener = inboxRepository::onPiggybackedUnreadCount

        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = sessionRepository::onConfigPayloadObserved,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun htmlWithConfig(unreadAll: Int, uid: Long = 101L, name: String = "Tester"): String {
        val json = """
            {
              "enableCustomedStyle": 1,
              "user": {
                "member_id": $uid,
                "member_name": "$name",
                "coin": 200,
                "unViewedCount": { "all": $unreadAll }
              },
              "allCategory": [{ "key": "tech", "cn_text": "技术" }]
            }
        """.trimIndent()
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return """<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$b64</script></body></html>"""
    }

    @Test
    fun `single reply click decrements unreadTotalCount immediately before POST finishes and blocks stale HTML piggyback`() = runTest {
        val markPostReceived = CountDownLatch(1)
        val allowMarkPostResponse = CountDownLatch(1)
        val markReplyBodies = mutableListOf<String>()

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 3))
                    "/api/notification/reply-to-me/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "data": [
                            {"id": 101, "viewed": 0, "post_id": 501, "title": "帖子A", "floor_id": 2, "commenter_id": 201, "commenter_name": "Alice", "created_at": "2026-11-16T01:00:00Z"},
                            {"id": 102, "viewed": 0, "post_id": 502, "title": "帖子B", "floor_id": 4, "commenter_id": 202, "commenter_name": "Bob", "created_at": "2026-11-16T01:01:00Z"},
                            {"id": 103, "viewed": 0, "post_id": 503, "title": "帖子C", "floor_id": 6, "commenter_id": 203, "commenter_name": "Carol", "created_at": "2026-11-16T01:02:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/reply-to-me/markViewed" -> {
                        synchronized(markReplyBodies) {
                            markReplyBodies += request.body.readUtf8()
                        }
                        markPostReceived.countDown()
                        allowMarkPostResponse.await(5, TimeUnit.SECONDS)
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    // Lagging unread-count endpoint still returns stale 3 right after markViewed
                    "/api/notification/unread-count" ->
                        MockResponse().setResponseCode(200).setBody("""{"success":true,"unreadCount":{"all":3}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        assertEquals(3, inboxRepository.unreadTotalCount.value)

        val replies = inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        assertEquals(3, replies.size)
        assertTrue(replies.all { !it.isRead })

        // Trigger mark-read while holding POST in flight on MockWebServer
        val markDeferred = async(Dispatchers.IO) {
            inboxRepository.markNotificationsRead(NotificationKind.REPLIES, ids = listOf(101L))
        }
        assertTrue(markPostReceived.await(5, TimeUnit.SECONDS))

        // Synchronously updated BEFORE POST response returns!
        assertEquals(2, inboxRepository.unreadTotalCount.value)
        assertTrue(inboxRepository.replyNotifications.value.first { it.id == 101L }.isRead)
        assertFalse(inboxRepository.replyNotifications.value.first { it.id == 102L }.isRead)

        // Concurrent HTML thread/feed load carries stale unViewedCount.all = 3 -> MUST NOT bounce back!
        sessionRepository.refreshSession().getOrThrow()
        assertEquals(2, inboxRepository.unreadTotalCount.value)

        // Unblock POST and let lagging /api/notification/unread-count (3) respond -> still stays 2!
        allowMarkPostResponse.countDown()
        markDeferred.await().getOrThrow()

        assertEquals(listOf("""{"replys":[101]}"""), markReplyBodies)
        assertEquals(2, inboxRepository.unreadTotalCount.value)
        assertTrue(inboxRepository.replyNotifications.value.first { it.id == 101L }.isRead)
    }

    @Test
    fun `category mark-all-read zeroes category and suppresses stale list and piggyback while allowing genuinely new IDs`() = runTest {
        val includeNewReply = AtomicBoolean(false)
        val serverCount = AtomicInteger(2)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 2))
                    "/api/notification/reply-to-me/list" -> {
                        val extraNew = if (includeNewReply.get()) {
                            """,{"id": 105, "viewed": 0, "post_id": 605, "title": "新回复", "floor_id": 1, "commenter_id": 209, "commenter_name": "Dave", "created_at": "2026-11-16T02:00:00Z"}"""
                        } else {
                            ""
                        }
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "data": [
                                {"id": 101, "viewed": 0, "post_id": 601, "title": "旧回复1", "floor_id": 2, "commenter_id": 201, "commenter_name": "Alice", "created_at": "2026-11-16T01:00:00Z"},
                                {"id": 102, "viewed": 0, "post_id": 602, "title": "旧回复2", "floor_id": 3, "commenter_id": 202, "commenter_name": "Bob", "created_at": "2026-11-16T01:01:00Z"}$extraNew
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    "/api/notification/reply-to-me/markViewed?all=true" -> {
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":${serverCount.get()}}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        assertEquals(2, inboxRepository.unreadTotalCount.value)

        // Server lags on unread-count (still returns 2 even after markViewed?all=true)
        inboxRepository.markNotificationsRead(NotificationKind.REPLIES, ids = null).getOrThrow()
        assertEquals(0, inboxRepository.unreadTotalCount.value)
        assertTrue(inboxRepository.replyNotifications.value.all { it.isRead })

        // Resuming InboxScreen reloads list where server still lagged viewed=0 for IDs 101 & 102 -> MUST stay read and total MUST stay 0!
        val staleReloaded = inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        assertTrue(staleReloaded.all { it.isRead })
        assertEquals(0, inboxRepository.unreadTotalCount.value)

        // Stale piggybacked HTML count = 2 within guard window -> still suppressed at 0
        inboxRepository.onPiggybackedUnreadCount(2)
        assertEquals(0, inboxRepository.unreadTotalCount.value)

        // Server catches up to 0, then a genuinely NEW reply (#105 > watermark 102) arrives -> surfaces immediately!
        serverCount.set(0)
        assertEquals(0, inboxRepository.refreshUnreadCount().getOrThrow())

        includeNewReply.set(true)
        val withNew = inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        assertTrue(withNew.first { it.id == 101L }.isRead)
        assertTrue(withNew.first { it.id == 102L }.isRead)
        assertFalse(withNew.first { it.id == 105L }.isRead)
        assertEquals(1, inboxRepository.unreadTotalCount.value)

        // And confirmed API poll with new notification (count = 1) is accepted immediately
        serverCount.set(1)
        assertEquals(1, inboxRepository.refreshUnreadCount().getOrThrow())
    }

    @Test
    fun `markThreadRead and getConversation sync messageThreads and decrement unreadTotalCount cleanly`() = runTest {
        val markBodies = mutableListOf<String>()
        val serverUnreadAll = AtomicInteger(4)
        val serverMarkedPeer303Viewed = AtomicBoolean(false)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 4))
                    "/api/notification/at-me/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "data": [
                            {"id": 901, "viewed": 0, "post_id": 700, "title": "提及帖", "floor_id": 8, "commenter_id": 210, "commenter_name": "Mentioner", "created_at": "2026-11-16T01:00:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/message/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "msgArray": [
                            {"id": 501, "sender_id": 303, "sender_name": "PeerA", "receiver_id": 101, "receiver_name": "Tester", "content": "hi 1", "is_markdown": false, "viewed": ${if (serverMarkedPeer303Viewed.get()) 1 else 0}, "created_at": "2026-11-16T01:05:00Z"},
                            {"id": 502, "sender_id": 303, "sender_name": "PeerA", "receiver_id": 101, "receiver_name": "Tester", "content": "hi 2", "is_markdown": false, "viewed": ${if (serverMarkedPeer303Viewed.get()) 1 else 0}, "created_at": "2026-11-16T01:06:00Z"},
                            {"id": 601, "sender_id": 404, "sender_name": "PeerB", "receiver_id": 101, "receiver_name": "Tester", "content": "hello from B", "is_markdown": false, "viewed": 0, "created_at": "2026-11-16T01:07:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    // Server auto-marks PeerB conversation viewed=1 when GET /with/404 is fetched
                    "/api/notification/message/with/404" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "talkTo": {"member_id": 404, "member_name": "PeerB"},
                          "msgArray": [
                            {"id": 601, "sender_id": 404, "sender_name": "PeerB", "receiver_id": 101, "receiver_name": "Tester", "content": "hello from B", "is_markdown": false, "viewed": 1, "created_at": "2026-11-16T01:07:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/message/markViewed" -> {
                        synchronized(markBodies) {
                            markBodies += request.body.readUtf8()
                        }
                        serverMarkedPeer303Viewed.set(true)
                        serverUnreadAll.set(2) // 1 mention + 1 DM from PeerB remain
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    "/api/notification/at-me/markViewed" ->
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":${serverUnreadAll.get()}}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.getNotifications(NotificationKind.MENTIONS).getOrThrow()
        val initialThreads = inboxRepository.getMessageThreads().getOrThrow()
        assertEquals(4, inboxRepository.unreadTotalCount.value)
        assertEquals(2, initialThreads.count { !it.isRead })

        // Click PeerA (303) which has 2 unread incoming messages (501, 502)
        inboxRepository.markThreadRead(peerUid = 303L).getOrThrow()

        assertEquals(listOf("""{"messages":[501,502]}"""), markBodies)
        assertTrue(inboxRepository.messageThreads.value.first { it.peerUid == 303L }.isRead)
        assertFalse(inboxRepository.messageThreads.value.first { it.peerUid == 404L }.isRead)
        // Decrements by 2 (4 -> 2), leaving 1 mention + 1 PeerB DM
        assertEquals(2, inboxRepository.unreadTotalCount.value)

        // Open PeerB conversation where server auto-marks viewed=1 on GET -> thread reconciles & count drops 2 -> 1!
        val peerBMessages = inboxRepository.getConversation(peerUid = 404L).getOrThrow()
        assertTrue(peerBMessages.single().isRead)
        assertTrue(inboxRepository.messageThreads.value.first { it.peerUid == 404L }.isRead)
        assertEquals(1, inboxRepository.unreadTotalCount.value)

        // Only mention #901 remains; marking all mentions read brings unreadTotalCount to 0!
        inboxRepository.markNotificationsRead(NotificationKind.MENTIONS, ids = listOf(901L)).getOrThrow()
        // Note mock server did not decrement mention on unread-count (still returns 2), guard keeps 0!
        assertEquals(0, inboxRepository.unreadTotalCount.value)
    }

    @Test
    fun `stale piggyback guard expires after cooldown window and allows server count updates`() = runTest {
        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 1))
                    "/api/notification/at-me/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "data": [
                            {"id": 50, "viewed": 0, "post_id": 800, "title": "@测试", "floor_id": 1, "commenter_id": 220, "commenter_name": "Sender", "created_at": "2026-11-16T01:00:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/at-me/markViewed" ->
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    "/api/notification/unread-count" ->
                        MockResponse().setResponseCode(200).setBody("""{"success":true,"unreadCount":{"all":0}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.getNotifications(NotificationKind.MENTIONS).getOrThrow()
        assertEquals(1, inboxRepository.unreadTotalCount.value)

        inboxRepository.markNotificationsRead(NotificationKind.MENTIONS, ids = listOf(50L)).getOrThrow()
        assertEquals(0, inboxRepository.unreadTotalCount.value)

        // 5s later (inside 15s piggyback guard): stale HTML piggyback=1 is rejected
        fakeClockMs.addAndGet(5_000L)
        inboxRepository.onPiggybackedUnreadCount(1)
        assertEquals(0, inboxRepository.unreadTotalCount.value)

        // Past 15s cooldown: new HTML piggyback=1 is accepted
        fakeClockMs.addAndGet(11_000L)
        inboxRepository.onPiggybackedUnreadCount(1)
        assertEquals(1, inboxRepository.unreadTotalCount.value)
    }

    @Test
    fun `markNotificationsRead failure rolls back optimistic read status and unread count for both single and mark-all`() = runTest {
        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 2))
                    "/api/notification/reply-to-me/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "data": [
                            {"id": 301, "viewed": 0, "post_id": 900, "title": "失败回滚测试1", "floor_id": 1, "commenter_id": 221, "commenter_name": "TesterB", "created_at": "2026-11-16T01:00:00Z"},
                            {"id": 302, "viewed": 0, "post_id": 901, "title": "失败回滚测试2", "floor_id": 2, "commenter_id": 222, "commenter_name": "TesterC", "created_at": "2026-11-16T01:01:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/reply-to-me/markViewed",
                    "/api/notification/reply-to-me/markViewed?all=true",
                    ->
                        MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"server error"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        assertEquals(2, inboxRepository.unreadTotalCount.value)

        // 1. Single-item failure rollback
        val singleResult = inboxRepository.markNotificationsRead(NotificationKind.REPLIES, ids = listOf(301L))
        assertTrue(singleResult.isFailure)
        assertFalse(inboxRepository.replyNotifications.value.first { it.id == 301L }.isRead)
        assertFalse(inboxRepository.replyNotifications.value.first { it.id == 302L }.isRead)
        assertEquals(2, inboxRepository.unreadTotalCount.value)

        // 2. Mark-all (ids = null) failure rollback
        val markAllResult = inboxRepository.markNotificationsRead(NotificationKind.REPLIES, ids = null)
        assertTrue(markAllResult.isFailure)
        assertTrue(inboxRepository.replyNotifications.value.all { !it.isRead })
        assertEquals(2, inboxRepository.unreadTotalCount.value)
    }

    @Test
    fun `sign out clears shared notification lists threads and unreadTotalCount`() = runTest {
        val signedOut = AtomicBoolean(false)
        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> {
                        val html = if (signedOut.get()) {
                            val json = """{"user":null,"allCategory":[{"key":"tech","cn_text":"技术"}]}"""
                            val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
                            """<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$b64</script></body></html>"""
                        } else {
                            htmlWithConfig(unreadAll = 2)
                        }
                        MockResponse().setResponseCode(200).setBody(html)
                    }
                    "/api/notification/reply-to-me/list" -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"id":1,"viewed":0,"post_id":10,"title":"A","floor_id":1,"commenter_id":2,"commenter_name":"U","created_at":"2026-11-16T01:00:00Z"}]}""",
                    )
                    "/api/notification/message/list" -> MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"msgArray":[{"id":2,"sender_id":3,"sender_name":"P","receiver_id":101,"receiver_name":"Tester","content":"hi","is_markdown":false,"viewed":0,"created_at":"2026-11-16T01:00:00Z"}]}""",
                    )
                    "/api/account/signOut" -> {
                        signedOut.set(true)
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        inboxRepository.getMessageThreads().getOrThrow()
        assertEquals(2, inboxRepository.unreadTotalCount.value)
        assertEquals(1, inboxRepository.replyNotifications.value.size)
        assertEquals(1, inboxRepository.messageThreads.value.size)

        sessionRepository.signOut().getOrThrow()
        assertEquals(0, inboxRepository.unreadTotalCount.value)
        assertTrue(inboxRepository.replyNotifications.value.isEmpty())
        assertTrue(inboxRepository.mentionNotifications.value.isEmpty())
        assertTrue(inboxRepository.messageThreads.value.isEmpty())
    }

    @Test
    fun `system notifications for likes drumsticks and stardust appear in messageThreads open cleanly and clear unreadTotalCount`() = runTest {
        val markBodies = mutableListOf<String>()
        val serverCount = AtomicInteger(3)
        val withZeroCalled = AtomicBoolean(false)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 3))
                    "/api/notification/message/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "msgArray": [
                            {"id": 801, "sender_id": 0, "sender_name": null, "receiver_id": 101, "content": "<a href=\"/post-500-1\">主题A</a> 收到 1 个点赞", "is_markdown": false, "viewed": 0, "created_at": "2026-11-16T01:00:00Z"},
                            {"id": 802, "sender_id": null, "type": "coin", "content": "Alice 给你的帖子加了 1 个鸡腿", "post_id": 501, "floor_id": 3, "viewed": 0, "created_at": "2026-11-16T01:01:00Z"},
                            {"id": 803, "sender_id": "system", "system": true, "receiver_id": 0, "content": "收到 10 个星辰打赏", "link": "/post-502-1#4", "viewed": 0, "created_at": "2026-11-16T01:02:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/message/with/0" -> {
                        withZeroCalled.set(true)
                        MockResponse().setResponseCode(404).setBody("""{"success":false,"message":"not found"}""")
                    }
                    "/api/notification/message/markViewed" -> {
                        synchronized(markBodies) {
                            markBodies += request.body.readUtf8()
                        }
                        serverCount.set(0)
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":${serverCount.get()}}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        val threads = inboxRepository.getMessageThreads().getOrThrow()
        assertEquals(3, inboxRepository.unreadTotalCount.value)
        assertEquals(1, threads.size)

        val sysThread = threads.single()
        assertEquals(0L, sysThread.peerUid)
        assertTrue(sysThread.isSystem)
        assertEquals("系统通知", sysThread.peerName)
        assertFalse(sysThread.isRead)
        assertEquals(3, sysThread.unreadCount)

        // Even when /api/notification/message/with/0 returns 404, cached system notices render cleanly.
        val messages = inboxRepository.getConversation(0L).getOrThrow()
        assertTrue(withZeroCalled.get())
        assertEquals(3, messages.size)
        assertTrue(messages.all { it.isSystem && !it.isRead })
        assertEquals("点赞", messages[0].noticeType)
        assertEquals("鸡腿", messages[1].noticeType)
        assertEquals("星辰", messages[2].noticeType)
        assertTrue(messages[1].body.contains("https://www.nodeseek.com/post-501-1#3"))
        assertTrue(messages[2].body.contains("https://www.nodeseek.com/post-502-1#4"))

        // Marking system messages read MUST update peerUid == 0L thread state and decrement total to 0!
        inboxRepository.markMessagesRead(messages.map { it.id }).getOrThrow()
        assertEquals(listOf("""{"messages":[801,802,803]}"""), markBodies)
        val updatedThread = inboxRepository.messageThreads.value.single { it.peerUid == 0L }
        assertTrue(updatedThread.isRead)
        assertEquals(0, updatedThread.unreadCount)
        assertEquals(0, inboxRepository.unreadTotalCount.value)

        // Stale reload of message/list & piggyback during guard window keep peerUid == 0L read & total == 0
        val staleThreads = inboxRepository.getMessageThreads().getOrThrow()
        assertTrue(staleThreads.single().isRead)
        inboxRepository.onPiggybackedUnreadCount(3)
        assertEquals(0, inboxRepository.unreadTotalCount.value)
    }

    @Test
    fun `getConversation for peerUid 0 handles null talkTo auto-viewed system notices and blocks sending to uid 0`() = runTest {
        val sendAttempted = AtomicBoolean(false)
        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 1))
                    "/api/notification/message/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "msgArray": [
                            {"id": 910, "sender_id": 0, "sender_name": "", "receiver_id": 0, "content": "你的帖子收到星辰", "viewed": 0, "created_at": "2026-11-16T02:00:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/message/with/0" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "talkTo": null,
                          "msgArray": [
                            {"id": 910, "sender_id": 0, "sender_name": "", "receiver_id": 0, "content": "你的帖子收到星辰", "viewed": 1, "created_at": "2026-11-16T02:00:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    "/api/notification/message/send" -> {
                        sendAttempted.set(true)
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        val initialThreads = inboxRepository.getMessageThreads().getOrThrow()
        assertFalse(initialThreads.single().isRead)
        assertEquals(1, inboxRepository.unreadTotalCount.value)

        val messages = inboxRepository.getConversation(0L).getOrThrow()
        assertEquals(1, messages.size)
        assertTrue(messages.single().isRead)
        assertTrue(inboxRepository.messageThreads.value.single().isRead)
        assertEquals(0, inboxRepository.unreadTotalCount.value)

        val sendResult = inboxRepository.sendDirectMessage(receiverUid = 0L, content = "reply", isMarkdown = false)
        assertTrue(sendResult.isFailure)
        assertFalse(sendAttempted.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `syncForegroundInbox captures cold-start baseline silently, throttles polling, deduplicates interactions and DMs, and resets on signOut`() = runTest {
        val phase = AtomicInteger(1)
        val serverUnreadAll = AtomicInteger(3)
        val listCalls = AtomicInteger(0)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = serverUnreadAll.get()))
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":${serverUnreadAll.get()}}}""")
                    "/api/notification/reply-to-me/list" -> {
                        listCalls.incrementAndGet()
                        val body = if (phase.get() == 1) {
                            """
                            {
                              "success": true,
                              "data": [
                                {"id": 101, "viewed": 0, "post_id": 500, "title": "旧未读回复", "floor_id": 3, "commenter_id": 201, "commenter_name": "OldReplier", "created_at": "2026-11-16T01:00:00Z"}
                              ]
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "success": true,
                              "data": [
                                {"id": 103, "viewed": 0, "post_id": 705, "title": "自己的测试回复", "floor_id": 2, "commenter_id": 101, "commenter_name": "Tester", "created_at": "2026-11-16T02:02:00Z"},
                                {"id": 102, "viewed": 0, "post_id": 700, "title": "双响帖子", "floor_id": 24, "commenter_id": 202, "commenter_name": "Alice", "created_at": "2026-11-16T02:00:00Z"},
                                {"id": 101, "viewed": 0, "post_id": 500, "title": "旧未读回复", "floor_id": 3, "commenter_id": 201, "commenter_name": "OldReplier", "created_at": "2026-11-16T01:00:00Z"}
                              ]
                            }
                            """.trimIndent()
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    "/api/notification/at-me/list" -> {
                        listCalls.incrementAndGet()
                        val body = if (phase.get() == 1) {
                            """
                            {
                              "success": true,
                              "data": [
                                {"id": 201, "viewed": 0, "post_id": 501, "title": "旧未读提及", "floor_id": 5, "commenter_id": 203, "commenter_name": "OldMentioner", "created_at": "2026-11-16T01:05:00Z"}
                              ]
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "success": true,
                              "data": [
                                {"id": 203, "viewed": 0, "post_id": 701, "title": "第二页提及", "floor_id": 12, "commenter_id": 204, "commenter_name": "Carol", "created_at": "2026-11-16T02:01:00Z"},
                                {"id": 202, "viewed": 0, "post_id": 700, "title": "双响帖子", "floor_id": 24, "commenter_id": 202, "commenter_name": "Alice", "created_at": "2026-11-16T02:00:00Z"},
                                {"id": 201, "viewed": 0, "post_id": 501, "title": "旧未读提及", "floor_id": 5, "commenter_id": 203, "commenter_name": "OldMentioner", "created_at": "2026-11-16T01:05:00Z"}
                              ]
                            }
                            """.trimIndent()
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    "/api/notification/message/list" -> {
                        listCalls.incrementAndGet()
                        val body = if (phase.get() == 1) {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 301, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "冷启动历史私信", "is_markdown": false, "viewed": 0, "created_at": "2026-11-16T01:10:00Z"}
                              ]
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 302, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "**收到** [详情](https://example.com)", "is_markdown": true, "viewed": 0, "created_at": "2026-11-16T02:05:00Z"},
                                {"id": 301, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "冷启动历史私信", "is_markdown": false, "viewed": 0, "created_at": "2026-11-16T01:10:00Z"}
                              ]
                            }
                            """.trimIndent()
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    "/api/account/signOut" -> MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val collectedPopups = mutableListOf<InAppMessagePopup>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { collectedPopups += it }
        }
        try {
            sessionRepository.refreshSession().getOrThrow()

            // 1. Cold-start baseline sync absorbs historical unread #101, #201, #301 silently -> 0 popups!
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertTrue(collectedPopups.isEmpty())
            assertEquals(3, listCalls.get())

            // 2. Call inside 12s throttle window -> 0 network requests!
            fakeClockMs.addAndGet(5_000L)
            val requestsBeforeThrottle = server.requestCount
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertEquals(requestsBeforeThrottle, server.requestCount)

            // 3. Call past throttle window when unreadCount unchanged (3 -> 3) -> only unread-count called, 0 list calls!
            fakeClockMs.addAndGet(15_000L)
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertEquals(3, listCalls.get())
            assertTrue(collectedPopups.isEmpty())

            // 4. New reply #102 (700#24), paired mention #202 (700#24), self-reply #103, distinct mention #203 (701#12), new DM #302
            phase.set(2)
            serverUnreadAll.set(6)
            fakeClockMs.addAndGet(15_000L)
            inboxRepository.syncForegroundInbox().getOrThrow()

            assertEquals(3, collectedPopups.size)
            val replyPopup = collectedPopups[0]
            assertEquals(InAppMessageKind.REPLY, replyPopup.kind)
            assertEquals(102L, replyPopup.itemId)
            assertEquals(700L, replyPopup.topicId)
            assertEquals(24, replyPopup.targetFloor)
            assertEquals(3, replyPopup.targetPage)
            assertEquals("Alice", replyPopup.senderName)

            val mentionPopup = collectedPopups[1]
            assertEquals(InAppMessageKind.MENTION, mentionPopup.kind)
            assertEquals(203L, mentionPopup.itemId)
            assertEquals(701L, mentionPopup.topicId)
            assertEquals(12, mentionPopup.targetFloor)
            assertEquals(2, mentionPopup.targetPage)
            assertEquals("Carol", mentionPopup.senderName)

            val dmPopup = collectedPopups[2]
            assertEquals(InAppMessageKind.DIRECT_MESSAGE, dmPopup.kind)
            assertEquals(302L, dmPopup.itemId)
            assertEquals(888L, dmPopup.peerUid)
            assertEquals("Bob", dmPopup.peerName)
            assertEquals("收到 详情", dmPopup.previewText)

            // 5. Repeating poll with identical payload must NOT emit duplicate popups
            fakeClockMs.addAndGet(50_000L)
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertEquals(3, collectedPopups.size)

            // 6. Sign-out + sign-in resets baseline so existing items become silent baseline again
            sessionRepository.signOut().getOrThrow()
            sessionRepository.refreshSession().getOrThrow()
            fakeClockMs.addAndGet(15_000L)
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertEquals(3, collectedPopups.size)
        } finally {
            collectJob.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `piggyback unread increase signals wake event, read conversation suppresses retroactive DM popup, and 429 backs off`() = runTest {
        val serverUnreadAll = AtomicInteger(0)
        val dmPhase = AtomicInteger(1)
        val returnRateLimit = AtomicBoolean(false)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (returnRateLimit.get() && request.path == "/api/notification/unread-count") {
                    return MockResponse()
                        .setResponseCode(429)
                        .addHeader("Retry-After", "30")
                        .setBody("Too Many Requests")
                }
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = serverUnreadAll.get()))
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":${serverUnreadAll.get()}}}""")
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/message/list" -> {
                        val body = if (dmPhase.get() == 1) {
                            """{"success":true,"msgArray":[]}"""
                        } else {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 401, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "正在聊天中收到的消息", "is_markdown": false, "viewed": 0, "created_at": "2026-11-16T03:00:00Z"}
                              ]
                            }
                            """.trimIndent()
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    "/api/notification/message/with/888" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "talkTo": {"member_id": 888, "member_name": "Bob"},
                          "msgArray": [
                            {"id": 401, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "正在聊天中收到的消息", "is_markdown": false, "viewed": 1, "created_at": "2026-11-16T03:00:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val collectedPopups = mutableListOf<InAppMessagePopup>()
        val wakeSignals = mutableListOf<Int>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { collectedPopups += it }
        }
        val wakeJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.unreadIncreaseEvents.collect { wakeSignals += it }
        }
        try {
            sessionRepository.refreshSession().getOrThrow()
            inboxRepository.syncForegroundInbox().getOrThrow()

            // Piggyback increase (0 -> 1) emits wake signal once baselines are ready
            inboxRepository.onPiggybackedUnreadCount(1)
            assertEquals(listOf(1), wakeSignals)

            // User already opened DirectMessageScreen(888) and viewed #401 -> subsequent getMessageThreads does NOT popup #401
            dmPhase.set(2)
            inboxRepository.getConversation(888L).getOrThrow()
            inboxRepository.getMessageThreads().getOrThrow()
            assertTrue(collectedPopups.isEmpty())

            // HTTP 429 Retry-After=30s sets backoff window -> subsequent syncForegroundInbox skips hitting server
            returnRateLimit.set(true)
            fakeClockMs.addAndGet(15_000L)
            assertTrue(inboxRepository.syncForegroundInbox().isFailure)
            val countAfter429 = server.requestCount
            fakeClockMs.addAndGet(10_000L)
            assertTrue(inboxRepository.syncForegroundInbox().isSuccess)
            assertEquals(countAfter429, server.requestCount)

            // After 30s passes, polling resumes cleanly
            returnRateLimit.set(false)
            serverUnreadAll.set(0)
            fakeClockMs.addAndGet(25_000L)
            assertTrue(inboxRepository.syncForegroundInbox().isSuccess)
            assertTrue(server.requestCount > countAfter429)
        } finally {
            popupJob.cancel()
            wakeJob.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `periodic DM sync emits popup even when unread-count stays zero`() = runTest {
        val dmPhase = AtomicInteger(1)
        val dmListCalls = AtomicInteger(0)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 0))
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":0}}""")
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/message/list" -> {
                        dmListCalls.incrementAndGet()
                        val body = if (dmPhase.get() == 1) {
                            """{"success":true,"msgArray":[]}"""
                        } else {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 901, "sender_id": 777, "sender_name": "PeerZ", "receiver_id": 101, "receiver_name": "Tester", "content": "你好，请问出吗", "is_markdown": false, "viewed": 0, "created_at": "2026-11-17T01:00:00Z"}
                              ]
                            }
                            """.trimIndent()
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val collectedPopups = mutableListOf<InAppMessagePopup>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { collectedPopups += it }
        }
        try {
            sessionRepository.refreshSession().getOrThrow()
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertEquals(1, dmListCalls.get())
            assertTrue(collectedPopups.isEmpty())

            // New DM arrives, while /api/notification/unread-count continues returning 0.
            dmPhase.set(2)
            fakeClockMs.addAndGet(46_000L)
            inboxRepository.syncForegroundInbox().getOrThrow()

            assertEquals(2, dmListCalls.get())
            assertEquals(1, collectedPopups.size)
            val dmPopup = collectedPopups.single()
            assertEquals(InAppMessageKind.DIRECT_MESSAGE, dmPopup.kind)
            assertEquals(901L, dmPopup.itemId)
            assertEquals(777L, dmPopup.peerUid)
            assertEquals("PeerZ", dmPopup.peerName)
            assertEquals("你好，请问出吗", dmPopup.previewText)
        } finally {
            popupJob.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `background getMessageThreads without active popup subscriber does not burn DM popup on resume`() = runTest {
        val dmPhase = AtomicInteger(1)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 0))
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":0}}""")
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/message/list" -> {
                        val body = if (dmPhase.get() == 1) {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 500, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "历史已读消息", "is_markdown": false, "viewed": 1, "created_at": "2026-11-17T00:10:00Z"}
                              ]
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 501, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "后台到达的新私信", "is_markdown": false, "viewed": 0, "created_at": "2026-11-17T00:20:00Z"},
                                {"id": 500, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "历史已读消息", "is_markdown": false, "viewed": 1, "created_at": "2026-11-17T00:10:00Z"}
                              ]
                            }
                            """.trimIndent()
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // First initialize baselines silently while UI collector is attached, then detach UI (backgrounded).
        val firstPopups = mutableListOf<InAppMessagePopup>()
        val firstJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { firstPopups += it }
        }
        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.syncForegroundInbox().getOrThrow()
        assertTrue(firstPopups.isEmpty())
        firstJob.cancel()

        // Background PulseRadarMonitor polls getMessageThreads() while inAppPopupEvents has 0 subscribers.
        dmPhase.set(2)
        inboxRepository.getMessageThreads().getOrThrow()

        // User brings app back to foreground and attaches collector -> syncForegroundInbox flushes #501 immediately!
        val resumedPopups = mutableListOf<InAppMessagePopup>()
        val resumedJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { resumedPopups += it }
        }
        try {
            fakeClockMs.addAndGet(2_000L) // Even inside 12s throttle window!
            inboxRepository.syncForegroundInbox().getOrThrow()

            assertEquals(1, resumedPopups.size)
            assertEquals(501L, resumedPopups.single().itemId)
            assertEquals("后台到达的新私信", resumedPopups.single().previewText)
        } finally {
            resumedJob.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cold start with persisted watermark store suppresses old DMs and pops newly arrived DM`() = runTest {
        val watermarkStore = object : RadarWatermarkStore {
            private var state = RadarWatermarks(
                inboxAccountUid = 101L,
                lastNotifiedReplyId = 10L,
                lastNotifiedMentionId = 20L,
                dmInitialized = true,
                lastNotifiedDmMap = mapOf(888L to 500L),
            )

            override suspend fun getWatermarks(): RadarWatermarks = state

            override suspend fun updateWatermarks(
                transform: (RadarWatermarks) -> RadarWatermarks,
            ): RadarWatermarks {
                state = transform(state)
                return state
            }
        }

        val coldSessionRepo = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        val coldInboxRepo = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = coldSessionRepo,
            clockMillis = { fakeClockMs.get() },
            watermarkStore = watermarkStore,
        )
        val baseUrl = server.url("/")
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("AnodeColdStartTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )
        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = coldSessionRepo::onConfigPayloadObserved,
        )

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(htmlWithConfig(unreadAll = 2))
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":2}}""")
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/message/list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "success": true,
                          "msgArray": [
                            {"id": 500, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "冷启动前已知未读", "is_markdown": false, "viewed": 0, "created_at": "2026-11-17T00:00:00Z"},
                            {"id": 502, "sender_id": 888, "sender_name": "Bob", "receiver_id": 101, "receiver_name": "Tester", "content": "杀进程期间到达的新私信", "is_markdown": false, "viewed": 0, "created_at": "2026-11-17T00:30:00Z"}
                          ]
                        }
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val collectedPopups = mutableListOf<InAppMessagePopup>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            coldInboxRepo.inAppPopupEvents.collect { collectedPopups += it }
        }
        try {
            coldSessionRepo.refreshSession().getOrThrow()
            coldInboxRepo.syncForegroundInbox().getOrThrow()

            assertEquals(1, collectedPopups.size)
            assertEquals(502L, collectedPopups.single().itemId)
            assertEquals("杀进程期间到达的新私信", collectedPopups.single().previewText)
            assertEquals(502L, watermarkStore.getWatermarks().lastNotifiedDmMap[888L])
        } finally {
            popupJob.cancel()
        }
    }
}
