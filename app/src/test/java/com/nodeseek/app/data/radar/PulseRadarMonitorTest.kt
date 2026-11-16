package com.nodeseek.app.data.radar

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nodeseek.app.data.local.KeywordWatchRuleEntity
import com.nodeseek.app.data.local.LocalAlertEntity
import com.nodeseek.app.data.local.RadarDao
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.TrackedTopicEntity
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.data.repository.InboxRepositoryImpl
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class PulseRadarMonitorTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var radarDao: FakeRadarDao
    private lateinit var preferencesStore: RadarPreferencesStore
    private lateinit var radarRepository: RadarRepositoryImpl
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var inboxRepository: InboxRepositoryImpl
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var dispatcher: RecordingNotificationDispatcher

    private var fakeClockMillis: Long = 1_760_000_000_000L
    private var fakeMinuteOfDay: Int = 12 * 60
    private var fakeUnmeteredNetwork: Boolean = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("pulse_radar_test").toFile()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val baseUrl = server.url("/")
        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("NodePulseRadarTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )

        settingsRepository = FakeSettingsRepository()
        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        inboxRepository = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )
        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = sessionRepository::onConfigPayloadObserved,
        )

        radarDao = FakeRadarDao()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "nodepulse_radar_prefs_test.preferences_pb") },
        )
        preferencesStore = RadarPreferencesStore(dataStore)
        radarRepository = RadarRepositoryImpl(
            radarDao = radarDao,
            preferencesStore = preferencesStore,
            clockMillis = { fakeClockMillis },
        )
        dispatcher = RecordingNotificationDispatcher()
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun createMonitor(scope: CoroutineScope): PulseRadarMonitor = PulseRadarMonitor(
        appContext = ContextWrapper(null),
        scope = scope,
        apiClient = apiClient,
        inboxRepository = inboxRepository,
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        radarRepository = radarRepository,
        notificationDispatcher = dispatcher,
        watermarkStore = preferencesStore,
        clockMillis = { fakeClockMillis },
        isUnmeteredNetwork = { fakeUnmeteredNetwork },
        currentMinuteOfDay = { fakeMinuteOfDay },
    )

    private fun encodeConfig(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun buildFeedHtml(
        topics: List<Triple<Long, String, String>>,
        userJson: String? = null,
    ): String {
        val userSection = if (userJson != null) "\"user\": $userJson," else ""
        val configB64 = encodeConfig(
            """
            {
              $userSection
              "allCategory": [
                { "key": "trade", "cn_text": "交易" },
                { "key": "info", "cn_text": "情报" },
                { "key": "daily", "cn_text": "日常" }
              ]
            }
            """.trimIndent(),
        )
        val itemsHtml = topics.joinToString("\n") { (id, slug, title) ->
            """
            <div class="post-list-item">
              <div class="post-title"><a href="/post-$id-1">$title</a></div>
              <a class="post-category" href="/categories/$slug">$slug</a>
              <span class="info-author"><a href="/space/301">Poster$id</a></span>
              <span class="info-views"><span>88</span></span>
              <span class="info-comments-count"><span>2</span></span>
            </div>
            """.trimIndent()
        }
        return """
            <html><body>
              <div id="nsk-body">$itemsHtml</div>
              <script id="temp-script" type="application/json">$configB64</script>
            </body></html>
        """.trimIndent()
    }

    private fun buildThreadHtml(
        topicId: Long,
        title: String,
        currentPage: Int,
        totalPages: Int,
        commentsJson: String,
        userJson: String? = null,
    ): String {
        val userSection = if (userJson != null) "\"user\": $userJson," else ""
        val configB64 = encodeConfig(
            """
            {
              $userSection
              "allCategory": [
                { "key": "tech", "cn_text": "技术" }
              ],
              "postData": {
                "postId": $topicId,
                "postPage": $currentPage,
                "postPageCount": $totalPages,
                "title": "$title",
                "category": "tech",
                "categoryWord": "技术",
                "op": { "uid": 10, "name": "TopicAuthor" },
                "comments": [$commentsJson]
              }
            }
            """.trimIndent(),
        )
        return """
            <html><body>
              <div id="nsk-body"><div class="nsk-post"></div></div>
              <script id="temp-script" type="application/json">$configB64</script>
            </body></html>
        """.trimIndent()
    }

    @Test
    fun `keyword scan initializes watermark silently on first run, then alerts on matching new posts only once`() = runTest {
        radarRepository.addKeywordRule(
            keyword = "RN",
            excludeWords = "求购",
            categorySlug = "",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        var phase = 1
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("sortBy=postTime") && phase == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildFeedHtml(
                                listOf(
                                    Triple(100L, "trade", "[出] RN 圣何塞老牌传家宝"),
                                    Triple(101L, "info", "RN 补货历史帖"),
                                ),
                            ),
                        )

                    path.contains("sortBy=postTime") && phase == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildFeedHtml(
                                listOf(
                                    Triple(103L, "info", "RN 限时闪购补货"),
                                    Triple(102L, "trade", "求购 RN 洛杉矶机房"),
                                    Triple(101L, "info", "RN 补货历史帖"),
                                ),
                            ),
                        )

                    else -> MockResponse().setResponseCode(200).setBody(buildFeedHtml(emptyList()))
                }
            }
        }

        val monitor = createMonitor(this)

        // Poll 1: baseline capture -> 0 alerts, 0 notifications
        assertTrue(monitor.runPollCycle())
        assertEquals(101L, preferencesStore.getWatermarks().lastSeenMaxTopicId)
        assertTrue(radarRepository.alertRecordsFlow.first().isEmpty())
        assertTrue(dispatcher.keywordHits.isEmpty())

        // Poll 2: 102 is excluded ("求购"), 103 matches -> 1 alert + 1 notification
        phase = 2
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        assertEquals(103L, preferencesStore.getWatermarks().lastSeenMaxTopicId)

        val alerts = radarRepository.alertRecordsFlow.first()
        assertEquals(1, alerts.size)
        assertEquals("kw:103", alerts.single().dedupKey)
        assertEquals(LocalAlertKind.KEYWORD_HIT, alerts.single().kind)
        assertEquals("RN", alerts.single().matchedKeyword)
        assertFalse(alerts.single().isRead)
        assertEquals(1, dispatcher.keywordHits.size)
        assertEquals(103L, dispatcher.keywordHits.single().topicId)

        // Poll 3: repeating identical feed must NOT emit duplicate alert or notification
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        assertEquals(1, radarRepository.alertRecordsFlow.first().size)
        assertEquals(1, dispatcher.keywordHits.size)
    }

    @Test
    fun `keyword scan falls back cleanly to CF-free RSS feed when HTML triggers Cloudflare challenge`() = runTest {
        radarRepository.addKeywordRule(
            keyword = "Oracle",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        var rssPhase = 1
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("sortBy=postTime") -> MockResponse()
                        .setResponseCode(403)
                        .addHeader("cf-mitigated", "challenge")
                        .setBody("<html><title>Just a moment...</title></html>")

                    path.startsWith("/rss.xml") && rssPhase == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
                              <channel>
                                <item>
                                  <title>Oracle 历史基准帖</title>
                                  <link>https://www.nodeseek.com/post-200-1</link>
                                  <dc:creator>UserA</dc:creator>
                                  <category>技术</category>
                                </item>
                              </channel>
                            </rss>
                            """.trimIndent(),
                        )

                    path.startsWith("/rss.xml") && rssPhase == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
                              <channel>
                                <item>
                                  <title>Oracle 东京新号注册成功分享</title>
                                  <link>https://www.nodeseek.com/post-205-1</link>
                                  <dc:creator>CloudFan</dc:creator>
                                  <category>技术</category>
                                  <description>&lt;p&gt;一次成功经验分享&lt;/p&gt;</description>
                                </item>
                              </channel>
                            </rss>
                            """.trimIndent(),
                        )

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val monitor = createMonitor(this)
        assertTrue(monitor.runPollCycle())
        assertEquals(200L, preferencesStore.getWatermarks().lastSeenMaxTopicId)
        assertTrue(dispatcher.keywordHits.isEmpty())

        rssPhase = 2
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        assertEquals(205L, preferencesStore.getWatermarks().lastSeenMaxTopicId)
        assertEquals(1, dispatcher.keywordHits.size)
        assertEquals("一次成功经验分享", dispatcher.keywordHits.single().summarySnippet)
    }

    @Test
    fun `tracked topic resolves unknown baseline silently, filters self replies, alerts on peer replies, and clears on seen`() = runTest {
        val selfUserJson = """{"member_id":101,"member_name":"SelfTester","coin":400}"""
        // Sign in as uid=101 so self-replies are filtered out
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildFeedHtml(
                                emptyList(),
                                userJson = selfUserJson,
                            ),
                        )

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        sessionRepository.refreshSession().getOrThrow()
        assertEquals(101L, sessionRepository.sessionState.value?.uid)

        // User tracks topic 500 from page 1 with unknown baseline (-1)
        radarRepository.trackTopic(
            topicId = 500L,
            title = "Linux 网络调优记录",
            categorySlug = "tech",
            categoryName = "技术",
            authorName = "TopicAuthor",
            currentMaxFloor = -1,
            totalPages = 1,
        )

        var step = 1
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("sortBy=replyTime") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildFeedHtml(
                                topics = listOf(Triple(500L, "tech", "Linux 网络调优记录")),
                                userJson = selfUserJson,
                            ),
                        )

                    path == "/post-500-1" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildThreadHtml(
                                topicId = 500L,
                                title = "Linux 网络调优记录",
                                currentPage = 1,
                                totalPages = 2,
                                commentsJson = """{"commentId":1,"floorIndex":1,"poster":{"uid":11,"name":"A"},"markdown":"first"}""",
                                userJson = selfUserJson,
                            ),
                        )

                    path == "/post-500-2" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildThreadHtml(
                                topicId = 500L,
                                title = "Linux 网络调优记录",
                                currentPage = 2,
                                totalPages = 2,
                                commentsJson = """{"commentId":14,"floorIndex":14,"poster":{"uid":12,"name":"B"},"markdown":"existing tail"}""",
                                userJson = selfUserJson,
                            ),
                        )

                    // Step 2: only a self-reply (#15 by uid=101)
                    path == "/post-500-2" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildThreadHtml(
                                topicId = 500L,
                                title = "Linux 网络调优记录",
                                currentPage = 2,
                                totalPages = 2,
                                commentsJson = """
                                    {"commentId":14,"floorIndex":14,"poster":{"uid":12,"name":"B"},"markdown":"existing tail"},
                                    {"commentId":15,"floorIndex":15,"poster":{"uid":101,"name":"SelfTester"},"markdown":"my own follow up"}
                                """.trimIndent(),
                                userJson = selfUserJson,
                            ),
                        )

                    // Step 3: two new peer replies (#16, #17)
                    path == "/post-500-2" && step == 3 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildThreadHtml(
                                topicId = 500L,
                                title = "Linux 网络调优记录",
                                currentPage = 2,
                                totalPages = 2,
                                commentsJson = """
                                    {"commentId":15,"floorIndex":15,"poster":{"uid":101,"name":"SelfTester"},"markdown":"my own follow up"},
                                    {"commentId":16,"floorIndex":16,"poster":{"uid":202,"name":"NetGuru"},"markdown":"**试试** [TCP BBR](https://example.com)"},
                                    {"commentId":17,"floorIndex":17,"poster":{"uid":303,"name":"PeerC"},"markdown":"同问"}
                                """.trimIndent(),
                                userJson = selfUserJson,
                            ),
                        )

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        // Disable inbox push for this isolated test so mock responses stay focused
        radarRepository.updatePollingSettings { it.copy(inboxPushEnabled = false, keywordAlertsEnabled = false) }
        val monitor = createMonitor(this)

        // Step 1: Resolve baseline silently -> lastKnownFloor = 14, totalPages = 2, 0 alerts
        assertTrue(monitor.runPollCycle())
        var tracked = radarRepository.observeTrackedTopic(500L).first()!!
        assertEquals(14, tracked.lastKnownFloor)
        assertEquals(2, tracked.lastKnownTotalPages)
        assertEquals(0, tracked.unreadNewReplies)
        assertTrue(dispatcher.trackedReplies.isEmpty())

        // Step 2: Self-reply #15 advances baseline to 15 silently without alerting
        step = 2
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        tracked = radarRepository.observeTrackedTopic(500L).first()!!
        assertEquals(15, tracked.lastKnownFloor)
        assertEquals(0, tracked.unreadNewReplies)
        assertTrue(dispatcher.trackedReplies.isEmpty())

        // Step 3: Two peer replies #16 and #17 emit 1 local record targeting #16 + 1 notification with count=2
        step = 3
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        tracked = radarRepository.observeTrackedTopic(500L).first()!!
        assertEquals(17, tracked.lastKnownFloor)
        assertEquals(2, tracked.unreadNewReplies)

        val alerts = radarRepository.alertRecordsFlow.first()
        assertEquals(1, alerts.size)
        val alert = alerts.single()
        assertEquals("reply:500:16", alert.dedupKey)
        assertEquals(LocalAlertKind.TRACKED_REPLY, alert.kind)
        assertEquals(16, alert.targetFloor)
        assertEquals("NetGuru", alert.actorName)
        assertEquals("试试 TCP BBR", alert.summarySnippet)

        assertEquals(1, dispatcher.trackedReplies.size)
        assertEquals(2, dispatcher.trackedReplies.single().second)

        // Seeing the tail floor marks topic alerts read & resets unreadNewReplies to 0
        radarRepository.onTopicFloorSeen(topicId = 500L, maxSeenFloor = 17, totalPages = 2)
        tracked = radarRepository.observeTrackedTopic(500L).first()!!
        assertEquals(0, tracked.unreadNewReplies)
        assertEquals(0, radarRepository.unreadAlertCountFlow.first())
    }

    @Test
    fun `inbox polling initializes silently, deduplicates reply plus mention on same floor, and notifies unread PMs`() = runTest {
        var step = 1
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            buildFeedHtml(
                                emptyList(),
                                userJson = """{"member_id":101,"member_name":"Me","coin":100}""",
                            ),
                        )

                    path == "/api/notification/reply-to-me/list" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":5,"viewed":0,"post_id":600,"title":"历史回帖","floor_id":2,"commenter_id":202,"commenter_name":"OldPeer","created_at":"2026-11-15T08:00:00Z"}]}""")

                    path == "/api/notification/at-me/list" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":7,"viewed":0,"post_id":601,"title":"历史提及","floor_id":4,"commenter_id":203,"commenter_name":"OldMention","created_at":"2026-11-15T08:00:00Z"}]}""")

                    path == "/api/notification/message/list" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":30,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"旧未读私信","is_markdown":0,"created_at":"2026-11-15T08:00:00Z","viewed":0}]}""")

                    // Step 2: Reply id=6 & Mention id=8 share (post_id=700, floor_id=8) -> deduped to 1 notification!
                    // Plus distinct Mention id=9 at (701, 3), and new unread PM id=55 from Bob (uid=888)
                    path == "/api/notification/reply-to-me/list" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":6,"viewed":0,"post_id":700,"title":"双响主题","floor_id":8,"commenter_id":202,"commenter_name":"Alice","created_at":"2026-11-15T09:00:00Z"}]}""")

                    path == "/api/notification/at-me/list" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """{"success":true,"data":[
                                {"id":8,"viewed":0,"post_id":700,"title":"双响主题","floor_id":8,"commenter_id":202,"commenter_name":"Alice","created_at":"2026-11-15T09:00:00Z"},
                                {"id":9,"viewed":0,"post_id":701,"title":"独立提及贴","floor_id":3,"commenter_id":204,"commenter_name":"Carol","created_at":"2026-11-15T09:05:00Z"}
                            ]}""",
                        )

                    path == "/api/notification/message/list" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":55,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"**你好** [链接](https://example.com)","is_markdown":1,"created_at":"2026-11-15T09:10:00Z","viewed":0}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        radarRepository.updatePollingSettings {
            it.copy(keywordAlertsEnabled = false, replyTrackingEnabled = false, inboxPushEnabled = true)
        }
        val monitor = createMonitor(this)

        // Step 1: Silent baseline init
        assertTrue(monitor.runPollCycle())
        assertTrue(dispatcher.interactions.isEmpty())
        assertTrue(dispatcher.directMessages.isEmpty())

        // Step 2: Deduped interaction + separate mention + new PM
        step = 2
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        assertEquals(2, dispatcher.interactions.size)
        assertFalse(dispatcher.interactions[0].isMention)
        assertEquals(700L, dispatcher.interactions[0].topicId)
        assertEquals(8, dispatcher.interactions[0].floorNumber)
        assertTrue(dispatcher.interactions[1].isMention)
        assertEquals(701L, dispatcher.interactions[1].topicId)

        assertEquals(1, dispatcher.directMessages.size)
        val dm = dispatcher.directMessages.single()
        assertEquals(888L, dm.peerUid)
        assertEquals("Bob", dm.peerName)
        assertEquals(55L, dm.messageId)
        assertEquals("你好 链接", dm.previewText)

        // Step 3: Repeating same DM id=55 does NOT fire another notification
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        assertEquals(1, dispatcher.directMessages.size)
    }

    @Test
    fun `quiet hours suppresses OS push while persisting alerts, wifiOnly skips metered background, and 429 honors Retry-After`() = runTest {
        radarRepository.addKeywordRule(keyword = "DMIT").getOrThrow()

        var mode = "init"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (mode) {
                    "init" -> MockResponse().setResponseCode(200).setBody(
                        buildFeedHtml(listOf(Triple(10L, "trade", "DMIT 旧帖"))),
                    )

                    "quiet_hit" -> MockResponse().setResponseCode(200).setBody(
                        buildFeedHtml(listOf(Triple(11L, "trade", "DMIT 静默时段命中"))),
                    )

                    "rate_limit" -> MockResponse()
                        .setResponseCode(429)
                        .addHeader("Retry-After", "30")
                        .setBody("Too Many Requests")

                    else -> MockResponse().setResponseCode(200).setBody(
                        buildFeedHtml(listOf(Triple(12L, "trade", "DMIT 限流恢复"))),
                    )
                }
            }
        }

        val monitor = createMonitor(this)
        assertTrue(monitor.runPollCycle())
        assertEquals(10L, preferencesStore.getWatermarks().lastSeenMaxTopicId)

        // Enable Quiet Hours (23:00..07:00) and set clock to 23:30
        radarRepository.updatePollingSettings {
            it.copy(
                quietHoursEnabled = true,
                quietStartMinuteOfDay = 23 * 60,
                quietEndMinuteOfDay = 7 * 60,
                wifiOnlyInBackground = true,
                inboxPushEnabled = false,
                replyTrackingEnabled = false,
            )
        }
        fakeMinuteOfDay = 23 * 60 + 30
        mode = "quiet_hit"
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        // Record saved silently in Room, OS notification suppressed!
        assertEquals(1, radarRepository.alertRecordsFlow.first().size)
        assertEquals("kw:11", radarRepository.alertRecordsFlow.first().single().dedupKey)
        assertTrue(dispatcher.keywordHits.isEmpty())

        // Background + metered network -> scheduled check skips without hitting server
        monitor.onForegroundChanged(false)
        fakeUnmeteredNetwork = false
        val requestsBeforeSkip = server.requestCount
        assertFalse(monitor.runPollCycle(manualTrigger = false))
        assertEquals(requestsBeforeSkip, server.requestCount)

        // Manual check overrides metered background skip, hits 429 Retry-After=30s
        mode = "rate_limit"
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle(manualTrigger = true))
        assertTrue(monitor.runtimeStatus.value.lastErrorSummary?.contains("30s") == true)

        // Subsequent poll inside 30s backoff skips without hitting server
        val requestsAfter429 = server.requestCount
        fakeClockMillis += 10_000L
        assertFalse(monitor.runPollCycle(manualTrigger = false))
        assertFalse(monitor.runPollCycle(manualTrigger = true))
        assertEquals(requestsAfter429, server.requestCount)

        // After 30s passes, recovery poll succeeds
        mode = "recovered"
        fakeMinuteOfDay = 12 * 60
        fakeUnmeteredNetwork = true
        fakeClockMillis += 25_000L
        assertTrue(monitor.runPollCycle(manualTrigger = false))
        assertEquals(2, radarRepository.alertRecordsFlow.first().size)
        assertEquals(1, dispatcher.keywordHits.size)
    }

    @Test
    fun `radarRepository validates keyword rules, toggles topic tracking, manages read state, and prunes beyond 500 alerts`() = runTest {
        assertTrue(radarRepository.addKeywordRule("   ").isFailure)
        assertTrue(radarRepository.addKeywordRule("[bad-regex(", matchMode = KeywordMatchMode.REGEX).isFailure)

        val validRule = radarRepository.addKeywordRule("VPS", excludeWords = "求购, 求购").getOrThrow()
        assertEquals("求购", validRule.excludeWords)
        assertTrue(radarRepository.addKeywordRule("vps", excludeWords = "求购").isFailure)

        radarRepository.toggleKeywordRule(validRule.id, false)
        assertFalse(radarRepository.keywordRulesFlow.first().single().isEnabled)

        val trackedOn = radarRepository.toggleTrackTopic(
            topicId = 800L,
            title = "测试主题",
            currentMaxFloor = 5,
            totalPages = 1,
        )
        assertTrue(trackedOn)
        assertEquals(5, radarRepository.observeTrackedTopic(800L).first()?.lastKnownFloor)
        val trackedOff = radarRepository.toggleTrackTopic(topicId = 800L, title = "测试主题")
        assertFalse(trackedOff)

        // Insert 505 alerts -> capped at 500 newest
        for (i in 1..505) {
            fakeClockMillis = 1_000L + i
            assertTrue(
                radarRepository.recordAlertIfAbsent(
                    LocalAlertRecord(
                        dedupKey = "kw:$i",
                        kind = LocalAlertKind.KEYWORD_HIT,
                        topicId = i.toLong(),
                        topicTitle = "Post $i",
                        createdAtEpochMillis = fakeClockMillis,
                    ),
                ),
            )
        }
        assertEquals(500, radarRepository.alertRecordsFlow.first().size)
        assertEquals(500, radarRepository.unreadAlertCountFlow.first())

        radarRepository.markAlertReadByDedupKey("kw:505")
        assertEquals(499, radarRepository.unreadAlertCountFlow.first())
        radarRepository.clearReadAlerts()
        assertEquals(499, radarRepository.alertRecordsFlow.first().size)
        radarRepository.markAllAlertsRead()
        assertEquals(0, radarRepository.unreadAlertCountFlow.first())
        radarRepository.clearAllAlerts()
        assertTrue(radarRepository.alertRecordsFlow.first().isEmpty())
    }
}

internal class RecordingNotificationDispatcher : PulseNotificationDispatcher(ContextWrapper(null)) {
    data class InteractionCall(
        val itemId: Long,
        val isMention: Boolean,
        val senderName: String,
        val topicId: Long,
        val topicTitle: String,
        val floorNumber: Int,
        val excerpt: String,
    )

    data class DirectMessageCall(
        val peerUid: Long,
        val peerName: String,
        val messageId: Long,
        val previewText: String,
    )

    val keywordHits = mutableListOf<LocalAlertRecord>()
    val trackedReplies = mutableListOf<Pair<LocalAlertRecord, Int>>()
    val interactions = mutableListOf<InteractionCall>()
    val directMessages = mutableListOf<DirectMessageCall>()

    override fun ensureChannels() = Unit

    override fun hasPostNotificationsPermission(): Boolean = true

    override fun postKeywordHitNotification(record: LocalAlertRecord) {
        keywordHits.add(record)
    }

    override fun postTrackedReplyNotification(record: LocalAlertRecord, newReplyCount: Int) {
        trackedReplies.add(record to newReplyCount)
    }

    override fun postForumInteractionNotification(
        itemId: Long,
        isMention: Boolean,
        senderName: String,
        topicId: Long,
        topicTitle: String,
        floorNumber: Int,
        excerpt: String,
    ) {
        interactions.add(
            InteractionCall(
                itemId = itemId,
                isMention = isMention,
                senderName = senderName,
                topicId = topicId,
                topicTitle = topicTitle,
                floorNumber = floorNumber,
                excerpt = excerpt,
            ),
        )
    }

    override fun postDirectMessageNotification(
        peerUid: Long,
        peerName: String,
        messageId: Long,
        previewText: String,
    ) {
        directMessages.add(
            DirectMessageCall(
                peerUid = peerUid,
                peerName = peerName,
                messageId = messageId,
                previewText = previewText,
            ),
        )
    }
}

internal class FakeRadarDao : RadarDao {
    private val ruleIdSeq = AtomicLong(1L)
    private val alertIdSeq = AtomicLong(1L)

    private val rulesState = MutableStateFlow<List<KeywordWatchRuleEntity>>(emptyList())
    private val trackedState = MutableStateFlow<List<TrackedTopicEntity>>(emptyList())
    private val alertsState = MutableStateFlow<List<LocalAlertEntity>>(emptyList())

    override fun observeKeywordRules(): Flow<List<KeywordWatchRuleEntity>> =
        rulesState.map { list ->
            list.sortedWith(
                compareByDescending<KeywordWatchRuleEntity> { it.createdAtEpochMillis }
                    .thenByDescending { it.id },
            )
        }

    override suspend fun getKeywordRules(): List<KeywordWatchRuleEntity> =
        rulesState.value.sortedWith(
            compareByDescending<KeywordWatchRuleEntity> { it.createdAtEpochMillis }
                .thenByDescending { it.id },
        )

    override suspend fun insertKeywordRule(entity: KeywordWatchRuleEntity): Long {
        val assignedId = if (entity.id > 0L) entity.id else ruleIdSeq.getAndIncrement()
        rulesState.value = rulesState.value + entity.copy(id = assignedId)
        return assignedId
    }

    override suspend fun setKeywordRuleEnabled(ruleId: Long, enabled: Boolean) {
        rulesState.value = rulesState.value.map {
            if (it.id == ruleId) it.copy(isEnabled = enabled) else it
        }
    }

    override suspend fun deleteKeywordRule(ruleId: Long) {
        rulesState.value = rulesState.value.filterNot { it.id == ruleId }
    }

    override fun observeTrackedTopics(): Flow<List<TrackedTopicEntity>> =
        trackedState.map { list ->
            list.filter { it.isActive }
                .sortedWith(
                    compareByDescending<TrackedTopicEntity> { it.lastUpdatedEpochMillis }
                        .thenByDescending { it.createdAtEpochMillis },
                )
        }

    override fun observeTrackedTopic(topicId: Long): Flow<TrackedTopicEntity?> =
        trackedState.map { list -> list.firstOrNull { it.topicId == topicId && it.isActive } }

    override suspend fun getTrackedTopic(topicId: Long): TrackedTopicEntity? =
        trackedState.value.firstOrNull { it.topicId == topicId }

    override suspend fun getActiveTrackedTopics(): List<TrackedTopicEntity> =
        trackedState.value.filter { it.isActive }
            .sortedWith(
                compareByDescending<TrackedTopicEntity> { it.lastUpdatedEpochMillis }
                    .thenByDescending { it.createdAtEpochMillis },
            )

    override suspend fun upsertTrackedTopic(entity: TrackedTopicEntity) {
        trackedState.value = trackedState.value.filterNot { it.topicId == entity.topicId } + entity
    }

    override suspend fun deleteTrackedTopic(topicId: Long) {
        trackedState.value = trackedState.value.filterNot { it.topicId == topicId }
    }

    override suspend fun markTopicFloorSeen(
        topicId: Long,
        maxSeenFloor: Int,
        totalPages: Int,
        checkedAtEpochMillis: Long,
    ) {
        trackedState.value = trackedState.value.map {
            if (it.topicId == topicId) {
                it.copy(
                    lastKnownFloor = maxOf(it.lastKnownFloor, maxSeenFloor),
                    lastKnownTotalPages = maxOf(it.lastKnownTotalPages, totalPages),
                    unreadNewReplies = 0,
                    lastCheckedEpochMillis = checkedAtEpochMillis,
                )
            } else {
                it
            }
        }
    }

    override suspend fun recordMonitorTopicProgress(
        topicId: Long,
        title: String?,
        categorySlug: String?,
        categoryName: String?,
        authorName: String?,
        newMaxFloor: Int,
        newTotalPages: Int,
        unreadDelta: Int,
        checkedAtEpochMillis: Long,
    ) {
        trackedState.value = trackedState.value.map {
            if (it.topicId == topicId) {
                it.copy(
                    title = title?.takeIf { s -> s.isNotEmpty() } ?: it.title,
                    categorySlug = categorySlug?.takeIf { s -> s.isNotEmpty() } ?: it.categorySlug,
                    categoryName = categoryName?.takeIf { s -> s.isNotEmpty() } ?: it.categoryName,
                    authorName = authorName?.takeIf { s -> s.isNotEmpty() } ?: it.authorName,
                    lastKnownFloor = maxOf(it.lastKnownFloor, newMaxFloor),
                    lastKnownTotalPages = maxOf(it.lastKnownTotalPages, newTotalPages),
                    unreadNewReplies = it.unreadNewReplies + unreadDelta,
                    lastCheckedEpochMillis = checkedAtEpochMillis,
                    lastUpdatedEpochMillis = if (unreadDelta > 0) checkedAtEpochMillis else it.lastUpdatedEpochMillis,
                )
            } else {
                it
            }
        }
    }

    override suspend fun markTopicCheckedWithoutNewReplies(
        topicId: Long,
        totalPages: Int?,
        checkedAtEpochMillis: Long,
    ) {
        trackedState.value = trackedState.value.map {
            if (it.topicId == topicId) {
                it.copy(
                    lastKnownTotalPages = if (totalPages != null) maxOf(it.lastKnownTotalPages, totalPages) else it.lastKnownTotalPages,
                    lastCheckedEpochMillis = checkedAtEpochMillis,
                )
            } else {
                it
            }
        }
    }

    override fun observeAlertRecords(limit: Int): Flow<List<LocalAlertEntity>> =
        alertsState.map { list ->
            list.sortedWith(
                compareByDescending<LocalAlertEntity> { it.createdAtEpochMillis }
                    .thenByDescending { it.id },
            ).take(limit)
        }

    override fun observeUnreadAlertCount(): Flow<Int> =
        alertsState.map { list -> list.count { !it.isRead } }

    override suspend fun insertAlertIfAbsent(entity: LocalAlertEntity): Long {
        if (alertsState.value.any { it.dedupKey == entity.dedupKey }) {
            return -1L
        }
        val assignedId = if (entity.id > 0L) entity.id else alertIdSeq.getAndIncrement()
        alertsState.value = alertsState.value + entity.copy(id = assignedId)
        return assignedId
    }

    override suspend fun trimAlertsToLimit(maxRows: Int) {
        alertsState.value = alertsState.value
            .sortedWith(
                compareByDescending<LocalAlertEntity> { it.createdAtEpochMillis }
                    .thenByDescending { it.id },
            )
            .take(maxRows)
    }

    override suspend fun setAlertRead(alertId: Long, read: Boolean) {
        alertsState.value = alertsState.value.map {
            if (it.id == alertId) it.copy(isRead = read) else it
        }
    }

    override suspend fun markAlertReadByDedupKey(dedupKey: String) {
        alertsState.value = alertsState.value.map {
            if (it.dedupKey == dedupKey) it.copy(isRead = true) else it
        }
    }

    override suspend fun markAlertsReadForTopic(topicId: Long) {
        alertsState.value = alertsState.value.map {
            if (it.topicId == topicId) it.copy(isRead = true) else it
        }
    }

    override suspend fun markAllAlertsRead() {
        alertsState.value = alertsState.value.map { it.copy(isRead = true) }
    }

    override suspend fun deleteAlert(alertId: Long) {
        alertsState.value = alertsState.value.filterNot { it.id == alertId }
    }

    override suspend fun clearReadAlerts() {
        alertsState.value = alertsState.value.filterNot { it.isRead }
    }

    override suspend fun clearAllAlerts() {
        alertsState.value = emptyList()
    }
}
