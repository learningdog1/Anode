package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.RadarWatermarkStore
import com.nodeseek.app.data.local.RadarWatermarks
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies `(accountKey, sessionEpoch)` isolation in [InboxRepositoryImpl] across in-flight
 * private message, notification, conversation, mark-read, unread-count, foreground sync,
 * popup, and watermark-persistence operations during account switches (`A -> B`),
 * sign-out (`A -> guest`), same-account re-login (`A -> guest -> A`), and re-authentication.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxRepositoryImplTest {

    private class InMemoryWatermarkStore(
        initial: RadarWatermarks = RadarWatermarks(),
    ) : RadarWatermarkStore {
        private val lock = Any()
        private var current: RadarWatermarks = initial
        var onBeforeGet: (() -> Unit)? = null
        var onBeforeUpdate: (() -> Unit)? = null

        override suspend fun getWatermarks(): RadarWatermarks {
            onBeforeGet?.invoke()
            return synchronized(lock) { current }
        }

        override suspend fun updateWatermarks(
            transform: (RadarWatermarks) -> RadarWatermarks,
        ): RadarWatermarks {
            onBeforeUpdate?.invoke()
            return synchronized(lock) {
                current = transform(current)
                current
            }
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var cookieBridge: WebViewCookieBridge
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var watermarkStore: InMemoryWatermarkStore
    private lateinit var inboxRepository: InboxRepositoryImpl
    private val fakeClockMs = AtomicLong(1_710_000_000_000L)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("AnodeInboxSessionIsolationTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )

        watermarkStore = InMemoryWatermarkStore()
        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        inboxRepository = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            clockMillis = { fakeClockMs.get() },
            watermarkStore = watermarkStore,
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

    private fun htmlWithConfig(
        uid: Long?,
        name: String = "User$uid",
        unreadAll: Int = 0,
    ): String {
        val userSection = if (uid != null && uid > 0L) {
            """
            "user": {
              "member_id": $uid,
              "member_name": "$name",
              "coin": 300,
              "unViewedCount": { "all": $unreadAll }
            },
            """.trimIndent()
        } else {
            """"user": null,"""
        }
        val json = """
            {
              "enableCustomedStyle": 1,
              $userSection
              "allCategory": [{ "key": "tech", "cn_text": "技术" }]
            }
        """.trimIndent()
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return """<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$b64</script></body></html>"""
    }

    @Test
    fun `in-flight getMessageThreads from Account A completing after switch to Account B leaves B state cache and popups untouched`() = runTest {
        val activeSessionHtml = AtomicReference(htmlWithConfig(uid = 101L, name = "Alice", unreadAll = 0))
        val dmRequestCount = AtomicInteger(0)
        val accountADmEntered = CountDownLatch(1)
        val releaseAccountADm = CountDownLatch(1)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(activeSessionHtml.get())
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":1}}""")
                    "/api/notification/message/list" -> {
                        when (dmRequestCount.incrementAndGet()) {
                            // 1: Account A initial baseline load (empty)
                            1 -> MockResponse().setResponseCode(200).setBody("""{"success":true,"msgArray":[]}""")
                            // 2: Account A slow in-flight refresh carrying private DM from peer 701
                            2 -> {
                                accountADmEntered.countDown()
                                assertTrue(releaseAccountADm.await(5, TimeUnit.SECONDS))
                                MockResponse().setResponseCode(200).setBody(
                                    """
                                    {
                                      "success": true,
                                      "msgArray": [
                                        {"id": 5001, "sender_id": 701, "sender_name": "SecretPeerA", "receiver_id": 101, "receiver_name": "Alice", "content": "Account A secret message", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T10:00:00Z"}
                                      ]
                                    }
                                    """.trimIndent(),
                                )
                            }
                            // 3+: Account B thread list carrying Account B's own DM from peer 802
                            else -> MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "success": true,
                                  "msgArray": [
                                    {"id": 6001, "sender_id": 802, "sender_name": "PeerForB", "receiver_id": 202, "receiver_name": "Bob", "content": "Hello Account B", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T10:01:00Z"}
                                  ]
                                }
                                """.trimIndent(),
                            )
                        }
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
            // Sign in as Account A (uid = 101) and establish DM popup baseline
            sessionRepository.refreshSession().getOrThrow()
            val tokenA = inboxRepository.currentSessionTokenForTesting()
            assertEquals("uid:101", tokenA.accountKey)
            assertEquals(101L, tokenA.uid)
            inboxRepository.syncForegroundInbox().getOrThrow()

            // Start in-flight getMessageThreads() under Account A
            val inFlightThreadsA = async(Dispatchers.IO) {
                inboxRepository.getMessageThreads()
            }
            assertTrue(accountADmEntered.await(5, TimeUnit.SECONDS))

            // Switch directly to Account B (uid = 202) while Account A's request is in flight
            activeSessionHtml.set(htmlWithConfig(uid = 202L, name = "Bob", unreadAll = 1))
            sessionRepository.refreshSession().getOrThrow()
            val tokenB = inboxRepository.currentSessionTokenForTesting()
            assertEquals("uid:202", tokenB.accountKey)
            assertEquals(202L, tokenB.uid)
            assertTrue(tokenB.sessionEpoch > tokenA.sessionEpoch)

            // Populate Account B's inbox threads & persisted watermarks
            val threadsB = inboxRepository.getMessageThreads().getOrThrow()
            assertEquals(1, threadsB.size)
            assertEquals(802L, threadsB.single().peerUid)
            assertEquals("Hello Account B", threadsB.single().previewText)
            assertEquals(1, inboxRepository.unreadTotalCount.value)
            watermarkStore.updateWatermarks {
                it.copy(
                    inboxAccountUid = 202L,
                    dmInitialized = true,
                    lastNotifiedDmMap = mapOf(802L to 6001L),
                )
            }
            collectedPopups.clear()

            // Now let Account A's late response complete!
            releaseAccountADm.countDown()
            val staleResultA = inFlightThreadsA.await()

            assertTrue(staleResultA.isFailure)
            val currentThreads = inboxRepository.messageThreads.value
            assertEquals(1, currentThreads.size)
            assertEquals(802L, currentThreads.single().peerUid)
            assertEquals("Hello Account B", currentThreads.single().previewText)
            assertEquals(1, inboxRepository.unreadTotalCount.value)
            assertEquals(0, inboxRepository.cachedMessageCountForPeerForTesting(701L))
            assertEquals(1, inboxRepository.cachedMessageCountForPeerForTesting(802L))
            assertTrue(collectedPopups.isEmpty())
            assertEquals(202L, watermarkStore.getWatermarks().inboxAccountUid)
            assertEquals(mapOf(802L to 6001L), watermarkStore.getWatermarks().lastNotifiedDmMap)
        } finally {
            popupJob.cancel()
        }
    }

    @Test
    fun `in-flight getMessageThreads from Account A completing after logout keeps guest inbox empty`() = runTest {
        val signedOut = AtomicBoolean(false)
        val dmRequestCount = AtomicInteger(0)
        val accountADmEntered = CountDownLatch(1)
        val releaseAccountADm = CountDownLatch(1)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> {
                        val html = if (signedOut.get()) {
                            htmlWithConfig(uid = null)
                        } else {
                            htmlWithConfig(uid = 101L, name = "Alice", unreadAll = 2)
                        }
                        MockResponse().setResponseCode(200).setBody(html)
                    }
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":2}}""")
                    "/api/notification/message/list" -> {
                        if (dmRequestCount.incrementAndGet() == 1) {
                            MockResponse().setResponseCode(200).setBody("""{"success":true,"msgArray":[]}""")
                        } else {
                            accountADmEntered.countDown()
                            assertTrue(releaseAccountADm.await(5, TimeUnit.SECONDS))
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "success": true,
                                  "msgArray": [
                                    {"id": 5010, "sender_id": 777, "sender_name": "OldContact", "receiver_id": 101, "receiver_name": "Alice", "content": "Old private PM preview", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T11:00:00Z"}
                                  ]
                                }
                                """.trimIndent(),
                            )
                        }
                    }
                    "/api/account/signOut" -> {
                        signedOut.set(true)
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val collectedPopups = mutableListOf<InAppMessagePopup>()
        val wakeEvents = mutableListOf<Int>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { collectedPopups += it }
        }
        val wakeJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.unreadIncreaseEvents.collect { wakeEvents += it }
        }
        try {
            sessionRepository.refreshSession().getOrThrow()
            val tokenBeforeLogout = inboxRepository.currentSessionTokenForTesting()
            inboxRepository.syncForegroundInbox().getOrThrow()

            val inFlightThreads = async(Dispatchers.IO) {
                inboxRepository.getMessageThreads()
            }
            assertTrue(accountADmEntered.await(5, TimeUnit.SECONDS))

            // Sign out to guest while getMessageThreads() is still in flight
            sessionRepository.signOut().getOrThrow()
            assertNull(sessionRepository.sessionState.value)
            val guestToken = inboxRepository.currentSessionTokenForTesting()
            assertFalse(guestToken.isAuthenticated)
            assertEquals(0L, guestToken.uid)
            assertTrue(guestToken.sessionEpoch > tokenBeforeLogout.sessionEpoch)
            assertTrue(inboxRepository.messageThreads.value.isEmpty())
            assertEquals(0, inboxRepository.unreadTotalCount.value)

            // Stale piggyback while signed out must also be ignored
            inboxRepository.onPiggybackedUnreadCount(5)
            assertEquals(0, inboxRepository.unreadTotalCount.value)
            assertTrue(wakeEvents.isEmpty())

            // Unblock late Account A response after logout
            releaseAccountADm.countDown()
            val lateResult = inFlightThreads.await()

            assertTrue(lateResult.isFailure)
            assertTrue(inboxRepository.messageThreads.value.isEmpty())
            assertEquals(0, inboxRepository.unreadTotalCount.value)
            assertEquals(0, inboxRepository.cachedMessageCountForPeerForTesting(777L))
            assertTrue(collectedPopups.isEmpty())
        } finally {
            popupJob.cancel()
            wakeJob.cancel()
        }
    }

    @Test
    fun `same-account logout and re-login increments sessionEpoch and discards stale notifications conversation threads and unread count`() = runTest {
        val signedOut = AtomicBoolean(false)
        val unreadAllInHtml = AtomicInteger(3)
        val repliesEntered = CountDownLatch(1)
        val threadsEntered = CountDownLatch(1)
        val conversationEntered = CountDownLatch(1)
        val unreadCountEntered = CountDownLatch(1)
        val releaseStaleResponses = CountDownLatch(1)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> {
                        val html = if (signedOut.get()) {
                            htmlWithConfig(uid = null)
                        } else {
                            htmlWithConfig(uid = 101L, name = "Alice", unreadAll = unreadAllInHtml.get())
                        }
                        MockResponse().setResponseCode(200).setBody(html)
                    }
                    "/api/notification/reply-to-me/list" -> {
                        repliesEntered.countDown()
                        assertTrue(releaseStaleResponses.await(5, TimeUnit.SECONDS))
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "data": [
                                {"id": 9101, "viewed": 0, "post_id": 500, "title": "旧会话未读回复", "floor_id": 2, "commenter_id": 301, "commenter_name": "OldReplier", "created_at": "2026-11-21T09:00:00Z"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    "/api/notification/message/list" -> {
                        threadsEntered.countDown()
                        assertTrue(releaseStaleResponses.await(5, TimeUnit.SECONDS))
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 9201, "sender_id": 303, "sender_name": "OldPeer", "receiver_id": 101, "receiver_name": "Alice", "content": "旧会话未读私信", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T09:05:00Z"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    "/api/notification/message/with/303" -> {
                        conversationEntered.countDown()
                        assertTrue(releaseStaleResponses.await(5, TimeUnit.SECONDS))
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "talkTo": {"member_id": 303, "member_name": "OldPeer"},
                              "msgArray": [
                                {"id": 9201, "sender_id": 303, "sender_name": "OldPeer", "receiver_id": 101, "receiver_name": "Alice", "content": "旧会话私聊详情", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T09:05:00Z"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    "/api/notification/unread-count" -> {
                        unreadCountEntered.countDown()
                        assertTrue(releaseStaleResponses.await(5, TimeUnit.SECONDS))
                        MockResponse().setResponseCode(200)
                            .setBody("""{"success":true,"unreadCount":{"all":9}}""")
                    }
                    "/api/account/signOut" -> {
                        signedOut.set(true)
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Session 1 for Account A (uid = 101)
        sessionRepository.refreshSession().getOrThrow()
        val firstSessionToken = inboxRepository.currentSessionTokenForTesting()
        assertEquals(101L, firstSessionToken.uid)
        assertEquals(3, inboxRepository.unreadTotalCount.value)

        val staleRepliesDeferred = async(Dispatchers.IO) {
            inboxRepository.getNotifications(NotificationKind.REPLIES)
        }
        val staleThreadsDeferred = async(Dispatchers.IO) {
            inboxRepository.getMessageThreads()
        }
        val staleConversationDeferred = async(Dispatchers.IO) {
            inboxRepository.getConversation(peerUid = 303L)
        }
        val staleCountDeferred = async(Dispatchers.IO) {
            inboxRepository.refreshUnreadCount()
        }

        assertTrue(repliesEntered.await(5, TimeUnit.SECONDS))
        assertTrue(threadsEntered.await(5, TimeUnit.SECONDS))
        assertTrue(conversationEntered.await(5, TimeUnit.SECONDS))
        assertTrue(unreadCountEntered.await(5, TimeUnit.SECONDS))

        // Transition A -> guest -> A while all 4 requests from Session 1 are in flight!
        sessionRepository.signOut().getOrThrow()
        val guestToken = inboxRepository.currentSessionTokenForTesting()
        assertTrue(guestToken.sessionEpoch > firstSessionToken.sessionEpoch)

        signedOut.set(false)
        unreadAllInHtml.set(0)
        sessionRepository.refreshSession().getOrThrow()
        val reloginToken = inboxRepository.currentSessionTokenForTesting()
        assertEquals(101L, reloginToken.uid)
        assertEquals(firstSessionToken.accountKey, reloginToken.accountKey)
        assertTrue(reloginToken.sessionEpoch > guestToken.sessionEpoch)
        assertEquals(0, inboxRepository.unreadTotalCount.value)

        // Release all 4 Session-1 responses after Account A is already in Session 2 (epoch + 2)
        releaseStaleResponses.countDown()

        assertTrue(staleRepliesDeferred.await().isFailure)
        assertTrue(staleThreadsDeferred.await().isFailure)
        assertTrue(staleConversationDeferred.await().isFailure)
        assertEquals(0, staleCountDeferred.await().getOrThrow())

        // None of Session 1's data may leak into Session 2 despite identical uid == 101!
        assertTrue(inboxRepository.replyNotifications.value.isEmpty())
        assertTrue(inboxRepository.messageThreads.value.isEmpty())
        assertEquals(0, inboxRepository.cachedMessageCountForPeerForTesting(303L))
        assertEquals(0, inboxRepository.unreadTotalCount.value)
    }

    @Test
    fun `in-flight mark-read and sendDirectMessage operations from Account A do not corrupt Account B on completion or failure rollback`() = runTest {
        val activeSessionHtml = AtomicReference(htmlWithConfig(uid = 101L, name = "Alice", unreadAll = 3))
        val listPhase = AtomicInteger(1)
        val markReplyEntered = CountDownLatch(1)
        val markThreadEntered = CountDownLatch(1)
        val markMsgFailEntered = CountDownLatch(1)
        val sendDmEntered = CountDownLatch(1)
        val releaseInFlightMutations = CountDownLatch(1)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(activeSessionHtml.get())
                    "/api/notification/reply-to-me/list" -> {
                        val commenter = if (listPhase.get() == 1) "SenderForA" else "SenderForB"
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "data": [
                                {"id": 101, "viewed": 0, "post_id": 500, "title": "ReplyTitle", "floor_id": 2, "commenter_id": 301, "commenter_name": "$commenter", "created_at": "2026-11-21T12:00:00Z"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    "/api/notification/message/list" -> {
                        val receiver = if (listPhase.get() == 1) 101 else 202
                        val preview = if (listPhase.get() == 1) "Unread for A" else "Unread for B"
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 501, "sender_id": 303, "sender_name": "Peer303", "receiver_id": $receiver, "receiver_name": "Viewer", "content": "$preview", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T12:01:00Z"},
                                {"id": 601, "sender_id": 404, "sender_name": "Peer404", "receiver_id": $receiver, "receiver_name": "Viewer", "content": "$preview 2", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T12:02:00Z"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    "/api/notification/reply-to-me/markViewed" -> {
                        markReplyEntered.countDown()
                        assertTrue(releaseInFlightMutations.await(5, TimeUnit.SECONDS))
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    "/api/notification/message/markViewed" -> {
                        val body = request.body.readUtf8()
                        if (body.contains("501")) {
                            markThreadEntered.countDown()
                            assertTrue(releaseInFlightMutations.await(5, TimeUnit.SECONDS))
                            MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                        } else {
                            markMsgFailEntered.countDown()
                            assertTrue(releaseInFlightMutations.await(5, TimeUnit.SECONDS))
                            MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"boom"}""")
                        }
                    }
                    "/api/notification/message/send" -> {
                        sendDmEntered.countDown()
                        assertTrue(releaseInFlightMutations.await(5, TimeUnit.SECONDS))
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":0}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Load Account A's notifications and threads
        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        inboxRepository.getMessageThreads().getOrThrow()
        assertEquals(3, inboxRepository.unreadTotalCount.value)

        // Start 3 mark-read mutations (2 succeeding, 1 failing 500) + 1 sendDirectMessage under Account A
        val markReplyA = async(Dispatchers.IO) {
            inboxRepository.markNotificationsRead(NotificationKind.REPLIES, ids = listOf(101L))
        }
        val markThreadA = async(Dispatchers.IO) {
            inboxRepository.markThreadRead(peerUid = 303L)
        }
        val markMsgFailA = async(Dispatchers.IO) {
            inboxRepository.markMessagesRead(messageIds = listOf(601L))
        }
        val sendDmA = async(Dispatchers.IO) {
            inboxRepository.sendDirectMessage(receiverUid = 303L, content = "Hello from A", isMarkdown = false)
        }

        assertTrue(markReplyEntered.await(5, TimeUnit.SECONDS))
        assertTrue(markThreadEntered.await(5, TimeUnit.SECONDS))
        assertTrue(markMsgFailEntered.await(5, TimeUnit.SECONDS))
        assertTrue(sendDmEntered.await(5, TimeUnit.SECONDS))

        // Switch to Account B (uid = 202) and load Account B's items (with overlapping IDs 101, 501, 601)
        listPhase.set(2)
        activeSessionHtml.set(htmlWithConfig(uid = 202L, name = "Bob", unreadAll = 3))
        sessionRepository.refreshSession().getOrThrow()
        inboxRepository.getNotifications(NotificationKind.REPLIES).getOrThrow()
        inboxRepository.getMessageThreads().getOrThrow()
        assertEquals(3, inboxRepository.unreadTotalCount.value)
        assertFalse(inboxRepository.replyNotifications.value.single().isRead)
        assertTrue(inboxRepository.messageThreads.value.all { !it.isRead })

        // Unblock Account A's in-flight network calls (200 OKs and 500 rollback)
        releaseInFlightMutations.countDown()

        assertTrue(markReplyA.await().isFailure)
        assertTrue(markThreadA.await().isFailure)
        assertTrue(markMsgFailA.await().isFailure)
        assertTrue(sendDmA.await().isFailure)

        // Account B's unread counts, reply items, and DM threads must remain completely untouched!
        assertEquals(3, inboxRepository.unreadTotalCount.value)
        assertFalse(inboxRepository.replyNotifications.value.single().isRead)
        assertEquals("SenderForB", inboxRepository.replyNotifications.value.single().senderName)
        assertEquals(2, inboxRepository.messageThreads.value.size)
        assertTrue(inboxRepository.messageThreads.value.all { !it.isRead })

        // And a fresh HTML piggyback / API count on Account B is NOT suppressed by Account A's stale guard window!
        inboxRepository.onPiggybackedUnreadCount(4)
        assertEquals(4, inboxRepository.unreadTotalCount.value)
    }

    @Test
    fun `authVerifiedEpoch rotation on same uid increments sessionEpoch and discards pre-auth in-flight response while keeping fresh config count`() = runTest {
        val unreadInHtml = AtomicInteger(1)
        val inFlightEntered = CountDownLatch(1)
        val releasePreAuthResponse = CountDownLatch(1)

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200)
                        .setBody(htmlWithConfig(uid = 101L, name = "Alice", unreadAll = unreadInHtml.get()))
                    "/api/notification/message/list" -> {
                        inFlightEntered.countDown()
                        assertTrue(releasePreAuthResponse.await(5, TimeUnit.SECONDS))
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 7701, "sender_id": 909, "sender_name": "StalePreAuthPeer", "receiver_id": 101, "receiver_name": "Alice", "content": "Stale pre-reauth payload", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T13:00:00Z"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        val preAuthToken = inboxRepository.currentSessionTokenForTesting()
        assertEquals(1, inboxRepository.unreadTotalCount.value)

        val preAuthThreadsDeferred = async(Dispatchers.IO) {
            inboxRepository.getMessageThreads()
        }
        assertTrue(inFlightEntered.await(5, TimeUnit.SECONDS))

        // Re-authenticate in WebView as the same uid = 101 (e.g. session cookie replaced), bumping authVerifiedEpoch
        unreadInHtml.set(5)
        sessionRepository.onWebViewAuthCompleted().getOrThrow()

        val postAuthToken = inboxRepository.currentSessionTokenForTesting()
        assertEquals(101L, postAuthToken.uid)
        assertTrue(postAuthToken.sessionEpoch > preAuthToken.sessionEpoch)
        // Fresh unread count from onWebViewAuthCompleted config is preserved (5)
        assertEquals(5, inboxRepository.unreadTotalCount.value)

        // Pre-auth getMessageThreads completes late -> discarded!
        releasePreAuthResponse.countDown()
        assertTrue(preAuthThreadsDeferred.await().isFailure)
        assertTrue(inboxRepository.messageThreads.value.isEmpty())
        assertEquals(0, inboxRepository.cachedMessageCountForPeerForTesting(909L))
        assertEquals(5, inboxRepository.unreadTotalCount.value)
    }

    @Test
    fun `late watermarkStore persistence from Account A blocked at updateWatermarks boundary does not overwrite Account B watermarks`() = runTest {
        val activeSessionHtml = AtomicReference(htmlWithConfig(uid = 101L, name = "Alice", unreadAll = 1))
        val dmPhase = AtomicInteger(1)
        val persistEntered = CountDownLatch(1)
        val allowPersistProceed = CountDownLatch(1)
        val blockNextUpdate = AtomicBoolean(false)

        watermarkStore.onBeforeUpdate = {
            if (blockNextUpdate.compareAndSet(true, false)) {
                persistEntered.countDown()
                allowPersistProceed.await(5, TimeUnit.SECONDS)
            }
        }

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(activeSessionHtml.get())
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":1}}""")
                    "/api/notification/message/list" -> {
                        val body = if (dmPhase.get() == 1) {
                            """{"success":true,"msgArray":[]}"""
                        } else {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 8801, "sender_id": 606, "sender_name": "Peer606", "receiver_id": 101, "receiver_name": "Alice", "content": "New DM triggering watermark persist", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T14:00:00Z"}
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

        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { }
        }
        try {
            sessionRepository.refreshSession().getOrThrow()
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertEquals(101L, watermarkStore.getWatermarks().inboxAccountUid)

            // Next DM load emits popup for 8801 and pauses right before watermarkStore.updateWatermarks transform!
            dmPhase.set(2)
            blockNextUpdate.set(true)
            val persistRaceJob = async(Dispatchers.IO) {
                inboxRepository.getMessageThreads()
            }
            assertTrue(persistEntered.await(5, TimeUnit.SECONDS))

            // Switch to Account B and persist Account B's watermarks while Account A's persist is paused
            activeSessionHtml.set(htmlWithConfig(uid = 202L, name = "Bob", unreadAll = 0))
            sessionRepository.refreshSession().getOrThrow()
            watermarkStore.updateWatermarks {
                RadarWatermarks(
                    inboxAccountUid = 202L,
                    lastNotifiedReplyId = 50L,
                    lastNotifiedMentionId = 60L,
                    dmInitialized = true,
                    lastNotifiedDmMap = mapOf(999L to 7000L),
                )
            }

            // Resume Account A's paused updateWatermarks -> transform checks session token and becomes a no-op!
            allowPersistProceed.countDown()
            persistRaceJob.await()

            val finalWatermarks = watermarkStore.getWatermarks()
            assertEquals(202L, finalWatermarks.inboxAccountUid)
            assertEquals(50L, finalWatermarks.lastNotifiedReplyId)
            assertEquals(60L, finalWatermarks.lastNotifiedMentionId)
            assertEquals(mapOf(999L to 7000L), finalWatermarks.lastNotifiedDmMap)
        } finally {
            popupJob.cancel()
        }
    }

    @Test
    fun `in-flight syncForegroundInbox and baseline seeding from Account A neither seed A watermarks nor throttle Account B`() = runTest {
        val activeSessionHtml = AtomicReference(htmlWithConfig(uid = 101L, name = "Alice", unreadAll = 1))
        val getWatermarkEntered = CountDownLatch(1)
        val allowGetWatermarkProceed = CountDownLatch(1)
        val blockNextGet = AtomicBoolean(true)
        val bDmPhase = AtomicInteger(1)

        // Seed store with high watermarks belonging to Account A (101)
        watermarkStore.updateWatermarks {
            RadarWatermarks(
                inboxAccountUid = 101L,
                lastNotifiedReplyId = 9000L,
                lastNotifiedMentionId = 9000L,
                dmInitialized = true,
                lastNotifiedDmMap = mapOf(888L to 9000L),
            )
        }
        watermarkStore.onBeforeGet = {
            if (blockNextGet.compareAndSet(true, false)) {
                getWatermarkEntered.countDown()
                allowGetWatermarkProceed.await(5, TimeUnit.SECONDS)
            }
        }

        server.dispatcher = object : MockWebDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody(activeSessionHtml.get())
                    "/api/notification/unread-count" -> MockResponse().setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":1}}""")
                    "/api/notification/reply-to-me/list",
                    "/api/notification/at-me/list",
                    -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    "/api/notification/message/list" -> {
                        val body = if (bDmPhase.get() == 1) {
                            """{"success":true,"msgArray":[]}"""
                        } else {
                            """
                            {
                              "success": true,
                              "msgArray": [
                                {"id": 500, "sender_id": 888, "sender_name": "Peer888", "receiver_id": 202, "receiver_name": "Bob", "content": "New DM for B with id < 9000", "is_markdown": false, "viewed": 0, "created_at": "2026-11-21T15:00:00Z"}
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
            val staleSyncA = async(Dispatchers.IO) {
                inboxRepository.syncForegroundInbox()
            }
            assertTrue(getWatermarkEntered.await(5, TimeUnit.SECONDS))

            // Switch to Account B while Account A's syncForegroundInbox is suspended in ensurePersistedBaselineSeeded
            activeSessionHtml.set(htmlWithConfig(uid = 202L, name = "Bob", unreadAll = 0))
            sessionRepository.refreshSession().getOrThrow()

            // Let Account A's syncForegroundInbox resume -> must abort cleanly without seeding 9000L or setting lastForegroundCheckMs!
            allowGetWatermarkProceed.countDown()
            assertTrue(staleSyncA.await().isSuccess)

            // Account B immediately runs syncForegroundInbox at the EXACT same clock timestamp (no 12s throttle or 20s backoff!)
            inboxRepository.syncForegroundInbox().getOrThrow()
            assertEquals(202L, watermarkStore.getWatermarks().inboxAccountUid)

            // Account B receives a DM from peer 888 with id = 500 (< Account A's 9000 watermark) -> MUST emit popup!
            bDmPhase.set(2)
            fakeClockMs.addAndGet(50_000L)
            inboxRepository.syncForegroundInbox().getOrThrow()

            assertEquals(1, collectedPopups.size)
            assertEquals(500L, collectedPopups.single().itemId)
            assertEquals("New DM for B with id < 9000", collectedPopups.single().previewText)
        } finally {
            popupJob.cancel()
        }
    }

    @Test
    fun `requireAuthenticatedSessionToken propagates CloudflareChallengeException instead of false 401`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><head><title>Just a moment...</title></head></html>"),
        )

        val result = inboxRepository.getMessageThreads()
        assertTrue(result.isFailure)
        assertTrue(
            "Expected CloudflareChallengeRequired, got: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is com.nodeseek.app.domain.model.TopicAccessException.CloudflareChallengeRequired,
        )
    }
}
