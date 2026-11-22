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
import com.nodeseek.app.data.repository.SessionSnapshotStore
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarBackgroundPollOutcome
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
            userAgentProvider = UserAgentProvider("AnodeRadarTest/1.0"),
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

    @Test
    fun `inbox polling holds unread DM watermark while POST_NOTIFICATIONS is denied and notifies once granted`() = runTest {
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

                    path == "/api/notification/message/list" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[]}""")

                    path == "/api/notification/message/list" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":88,"sender_id":999,"sender_name":"Alice","receiver_id":101,"receiver_name":"Me","content":"权限未授予时的私信","is_markdown":0,"created_at":"2026-11-17T01:00:00Z","viewed":0}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        radarRepository.updatePollingSettings {
            it.copy(keywordAlertsEnabled = false, replyTrackingEnabled = false, inboxPushEnabled = true)
        }
        val monitor = createMonitor(this)

        // Step 1: Initialize empty baseline silently.
        assertTrue(monitor.runPollCycle())
        assertTrue(preferencesStore.getWatermarks().dmInitialized)

        // Step 2: Permission denied -> DM arrives, monitor must NOT emit or burn watermark #88.
        dispatcher.notificationPermissionGranted = false
        step = 2
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        assertTrue(dispatcher.directMessages.isEmpty())
        assertEquals(null, preferencesStore.getWatermarks().lastNotifiedDmMap[999L])

        // Step 3: Permission granted -> next poll delivers DM #88 and advances watermark!
        dispatcher.notificationPermissionGranted = true
        fakeClockMillis += 60_000L
        assertTrue(monitor.runPollCycle())
        assertEquals(1, dispatcher.directMessages.size)
        assertEquals(88L, dispatcher.directMessages.single().messageId)
        assertEquals(88L, preferencesStore.getWatermarks().lastNotifiedDmMap[999L])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `foreground loop stops when backgrounded so WorkManager owns background polling and resumes on foreground return`() = runTest {
        radarRepository.updatePollingSettings {
            it.copy(
                isEnabled = true,
                keywordAlertsEnabled = false,
                replyTrackingEnabled = false,
                inboxPushEnabled = false,
                foregroundIntervalMinutes = 2,
                backgroundIntervalMinutes = 15,
            )
        }
        // Warm up DataStore so pollingSettingsFlow/getPollingSettings resolve immediately.
        radarRepository.getPollingSettings()

        var loopClock = 1_760_000_000_000L
        val loopScheduler = TestCoroutineScheduler()
        val loopScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(loopScheduler))
        val monitor = PulseRadarMonitor(
            appContext = ContextWrapper(null),
            scope = loopScope,
            apiClient = apiClient,
            inboxRepository = inboxRepository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            notificationDispatcher = dispatcher,
            watermarkStore = preferencesStore,
            clockMillis = { loopClock + loopScheduler.currentTime },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            currentMinuteOfDay = { fakeMinuteOfDay },
        )

        suspend fun settleLoop() {
            repeat(5) {
                loopScheduler.runCurrent()
                withContext(Dispatchers.Default) { delay(5L) }
            }
            loopScheduler.runCurrent()
        }

        suspend fun awaitPollAdvance(previousCheckAt: Long): Long {
            repeat(100) {
                loopScheduler.runCurrent()
                val current = monitor.runtimeStatus.value.lastCheckEpochMillis
                if (current > previousCheckAt) {
                    settleLoop()
                    return current
                }
                withContext(Dispatchers.Default) { delay(10L) }
            }
            return monitor.runtimeStatus.value.lastCheckEpochMillis
        }

        try {
            // Cold start in background must NOT launch an in-process foreground polling loop.
            monitor.onForegroundChanged(false)
            monitor.start()
            settleLoop()
            assertFalse(monitor.isForegroundLoopActive())
            loopScheduler.advanceTimeBy(60 * 60_000L)
            settleLoop()
            assertEquals(0L, monitor.runtimeStatus.value.lastCheckEpochMillis)

            // Entering foreground starts the fast foreground loop and executes initial poll after startup delay.
            monitor.onForegroundChanged(true)
            settleLoop()
            assertTrue(monitor.isForegroundLoopActive())
            loopScheduler.advanceTimeBy(2_000L)
            val firstCheckAt = awaitPollAdvance(0L)
            assertTrue(firstCheckAt > 0L)

            // Advancing 2m in foreground triggers a second foreground poll.
            loopScheduler.advanceTimeBy(2 * 60_000L + 500L)
            val secondCheckAt = awaitPollAdvance(firstCheckAt)
            assertTrue(secondCheckAt > firstCheckAt)

            // Transitioning to background exits the in-process loop cleanly; advancing 1 hour does 0 polls.
            monitor.onForegroundChanged(false)
            settleLoop()
            assertFalse(monitor.isForegroundLoopActive())
            loopScheduler.advanceTimeBy(60 * 60_000L)
            settleLoop()
            assertEquals(secondCheckAt, monitor.runtimeStatus.value.lastCheckEpochMillis)

            // Returning to foreground resumes the loop and runs a poll promptly since interval elapsed.
            monitor.onForegroundChanged(true)
            settleLoop()
            assertTrue(monitor.isForegroundLoopActive())
            loopScheduler.advanceTimeBy(2_000L)
            val resumedCheckAt = awaitPollAdvance(secondCheckAt)
            assertTrue(resumedCheckAt > secondCheckAt)

            monitor.stop()
        } finally {
            loopScope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `runBackgroundPollPass serializes with foreground polls via pollMutex, suppresses in-app popups, and auto-refreshes session`() = runTest {
        radarRepository.addKeywordRule(keyword = "Claw").getOrThrow()

        var step = 1
        val firstRequestEntered = CompletableDeferred<Unit>()
        val allowFirstRequestFinish = CompletableDeferred<Unit>()
        val activeFeedRequests = AtomicLong(0L)
        val maxConcurrentFeedRequests = AtomicLong(0L)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val userJson = """{"member_id":101,"member_name":"Me","coin":100}"""
                return when {
                    path.contains("sortBy=postTime") -> {
                        val concurrent = activeFeedRequests.incrementAndGet()
                        maxConcurrentFeedRequests.updateAndGet { prev -> maxOf(prev, concurrent) }
                        try {
                            if (step == 2 && !firstRequestEntered.isCompleted) {
                                firstRequestEntered.complete(Unit)
                                kotlinx.coroutines.runBlocking {
                                    allowFirstRequestFinish.await()
                                }
                            }
                            val topics = if (step == 1) {
                                listOf(Triple(10L, "trade", "Claw 基准帖"))
                            } else {
                                listOf(Triple(11L, "trade", "Claw 限时特惠"))
                            }
                            MockResponse().setResponseCode(200).setBody(buildFeedHtml(topics, userJson))
                        } finally {
                            activeFeedRequests.decrementAndGet()
                        }
                    }

                    path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(emptyList(), userJson))

                    path == "/api/notification/message/list" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":1,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"init","is_markdown":0,"created_at":"2026-11-15T08:00:00Z","viewed":1}]}""")

                    path == "/api/notification/message/list" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":2,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"后台 DM 推送","is_markdown":0,"created_at":"2026-11-15T09:00:00Z","viewed":0}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        val inboxWithWatermark = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            watermarkStore = preferencesStore,
        )
        val monitor = PulseRadarMonitor(
            appContext = ContextWrapper(null),
            scope = this,
            apiClient = apiClient,
            inboxRepository = inboxWithWatermark,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            notificationDispatcher = dispatcher,
            watermarkStore = preferencesStore,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            currentMinuteOfDay = { fakeMinuteOfDay },
        )

        // Step 1: Initialize baselines via background pass even when sessionState started null.
        assertEquals(null, sessionRepository.sessionState.value)
        val initOutcome = monitor.runBackgroundPollPass()
        assertTrue(initOutcome is RadarBackgroundPollOutcome.Completed)
        assertEquals(101L, sessionRepository.sessionState.value?.uid)

        // Step 2: Launch foreground poll and background poll concurrently while collecting inAppPopupEvents.
        step = 2
        fakeClockMillis += 60_000L
        val emittedPopups = mutableListOf<InAppMessagePopup>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxWithWatermark.inAppPopupEvents.collect { emittedPopups.add(it) }
        }
        try {
            val bgDeferred = async(Dispatchers.IO) { monitor.runBackgroundPollPass() }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    firstRequestEntered.await()
                }
            }
            val fgDeferred = async(Dispatchers.IO) { monitor.runPollCycle(manualTrigger = true) }
            // Give foreground coroutine a moment to block on pollMutex, then release background request.
            withContext(Dispatchers.Default) { delay(50L) }
            assertEquals(1L, maxConcurrentFeedRequests.get())
            allowFirstRequestFinish.complete(Unit)

            val bgOutcome = bgDeferred.await()
            assertTrue(fgDeferred.await())
            assertTrue(bgOutcome is RadarBackgroundPollOutcome.Completed)
            assertEquals(1L, maxConcurrentFeedRequests.get())

            // Alerts and OS notifications must be emitted exactly once across both serialized calls.
            assertEquals(1, radarRepository.alertRecordsFlow.first().size)
            assertEquals(1, dispatcher.keywordHits.size)
            assertEquals(1, dispatcher.directMessages.size)
            assertEquals(2L, dispatcher.directMessages.single().messageId)
            // Background pass suppressed in-app popup emission; subsequent foreground poll had no new DM > 2L.
            // Note: because bg pass did NOT advance InboxRepositoryImpl's in-memory DM watermark, fg poll saw DM #2!
            // Verify bg pass itself emitted 0 popups when running standalone below.
        } finally {
            popupJob.cancel()
        }

        // Step 3: Quiet hours suppresses OS push in background pass while still saving local alert.
        radarRepository.updatePollingSettings {
            it.copy(
                quietHoursEnabled = true,
                quietStartMinuteOfDay = 23 * 60,
                quietEndMinuteOfDay = 7 * 60,
            )
        }
        fakeMinuteOfDay = 23 * 60 + 15
        fakeClockMillis += 60_000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val userJson = """{"member_id":101,"member_name":"Me","coin":100}"""
                return when {
                    path.contains("sortBy=postTime") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(listOf(Triple(12L, "trade", "Claw 夜间静音命中")), userJson))
                    path == "/api/notification/message/list" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":3,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"夜间私信","is_markdown":0,"created_at":"2026-11-15T23:15:00Z","viewed":0}]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }
        val quietPopups = mutableListOf<InAppMessagePopup>()
        val quietJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxWithWatermark.inAppPopupEvents.collect { quietPopups.add(it) }
        }
        try {
            val quietOutcome = monitor.runBackgroundPollPass()
            assertTrue(quietOutcome is RadarBackgroundPollOutcome.Completed)
            assertEquals(2, radarRepository.alertRecordsFlow.first().size)
            // OS notifications must remain at 1 (quiet hours suppressed kw:12 and dm:3)
            assertEquals(1, dispatcher.keywordHits.size)
            assertEquals(1, dispatcher.directMessages.size)
            // In-app popups must be 0 in background pass even with active subscriber
            assertTrue(quietPopups.isEmpty())
        } finally {
            quietJob.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `eager unconfined and multi-threaded cold-start transitions never throw UninitializedPropertyAccessException`() = runTest {
        radarRepository.updatePollingSettings {
            it.copy(
                isEnabled = true,
                keywordAlertsEnabled = false,
                replyTrackingEnabled = false,
                inboxPushEnabled = false,
            )
        }
        radarRepository.getPollingSettings()

        val uncaughtErrors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val handler = CoroutineExceptionHandler { _, throwable ->
            uncaughtErrors.add(throwable)
        }

        // 1. Deterministic immediate-dispatch check (`Dispatchers.Unconfined`): executes the launched
        //    coroutine body synchronously BEFORE `scope.launch` returns to the caller frame, which
        //    deterministically triggered `UninitializedPropertyAccessException: lateinit property launched`
        //    prior to the CoroutineStart.LAZY + context Job fix.
        val unconfinedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
        val eagerMonitor = createMonitor(unconfinedScope)
        try {
            eagerMonitor.onForegroundChanged(false)
            eagerMonitor.start()
            assertFalse(eagerMonitor.isForegroundLoopActive())

            eagerMonitor.onForegroundChanged(true)
            assertTrue("Uncaught exception on eager start: $uncaughtErrors", uncaughtErrors.isEmpty())
            assertTrue(eagerMonitor.isForegroundLoopActive())

            eagerMonitor.onForegroundChanged(false)
            assertFalse(eagerMonitor.isForegroundLoopActive())

            eagerMonitor.triggerImmediateCheck()
            assertTrue("Uncaught exception on immediate check: $uncaughtErrors", uncaughtErrors.isEmpty())
            eagerMonitor.stop()
            assertFalse(eagerMonitor.isForegroundLoopActive())
        } finally {
            unconfinedScope.cancel()
        }

        // 2. Multi-threaded cold-start race simulation on real Dispatchers.IO with pre-warmed worker threads
        //    and concurrent SystemJobService/WorkManager `runBackgroundPollPass` contention.
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
        val concurrentMonitor = createMonitor(ioScope)
        try {
            withContext(Dispatchers.Default) {
                // Pre-warm Dispatchers.IO worker pool so launched tasks can run on another core immediately.
                val warmups = (1..8).map {
                    async(Dispatchers.IO) { delay(5L) }
                }
                warmups.forEach { it.await() }

                repeat(80) { round ->
                    val jobs = listOf(
                        launch(Dispatchers.Default) {
                            concurrentMonitor.onForegroundChanged(false)
                            concurrentMonitor.start()
                            concurrentMonitor.onForegroundChanged(true)
                        },
                        launch(Dispatchers.IO) {
                            concurrentMonitor.triggerImmediateCheck()
                        },
                        launch(Dispatchers.IO) {
                            if (round % 4 == 0) {
                                concurrentMonitor.runBackgroundPollPass()
                            }
                        },
                        launch(Dispatchers.Default) {
                            concurrentMonitor.onForegroundChanged(false)
                            if (round % 5 == 0) {
                                concurrentMonitor.stop()
                            }
                        },
                    )
                    jobs.forEach { it.join() }
                }
            }
            concurrentMonitor.stop()
            assertFalse(concurrentMonitor.isForegroundLoopActive())
            assertTrue(
                "Expected zero uncaught exceptions under concurrent cold-start contention, got: $uncaughtErrors",
                uncaughtErrors.isEmpty(),
            )
        } finally {
            ioScope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `background inbox loop delivers reply mention and DM system notifications when app leaves foreground with zero keyword rules`() = runTest {
        var phase = 1
        var serverUnreadCount = 0
        val selfUserJson = """{"member_id":101,"member_name":"Me","coin":100}"""

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(emptyList(), userJson = selfUserJson))

                    path == "/api/notification/unread-count" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":$serverUnreadCount}}""")

                    path == "/api/notification/reply-to-me/list" && phase >= 3 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":301,"viewed":0,"post_id":900,"title":"后台收到新回复","floor_id":6,"commenter_id":202,"commenter_name":"Alice","created_at":"2026-11-22T10:02:00Z"}]}""")

                    path == "/api/notification/message/list" && phase == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":0,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"后台无ID私信测试","is_markdown":0,"created_at":"2026-11-22T10:01:00Z","viewed":0}]}""")

                    path == "/api/notification/message/list" && phase >= 3 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":0,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"后台无ID私信测试","is_markdown":0,"created_at":"2026-11-22T10:01:00Z","viewed":0}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        assertEquals(101L, sessionRepository.sessionState.value?.uid)

        // Default settings with 0 keyword rules and 0 tracked topics
        radarRepository.getPollingSettings()

        val loopScheduler = TestCoroutineScheduler()
        val loopScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(loopScheduler))
        val sharedInbox = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            watermarkStore = preferencesStore,
        )
        val monitor = PulseRadarMonitor(
            appContext = ContextWrapper(null),
            scope = loopScope,
            apiClient = apiClient,
            inboxRepository = sharedInbox,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            notificationDispatcher = dispatcher,
            watermarkStore = preferencesStore,
            clockMillis = { fakeClockMillis + loopScheduler.currentTime },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            currentMinuteOfDay = { fakeMinuteOfDay },
        )

        suspend fun advanceUntil(maxVirtualMs: Long, predicate: suspend () -> Boolean) {
            var advanced = 0L
            while (advanced <= maxVirtualMs) {
                loopScheduler.runCurrent()
                if (predicate()) return
                withContext(Dispatchers.Default) { delay(10L) }
                loopScheduler.runCurrent()
                if (predicate()) return
                val step = minOf(500L, (maxVirtualMs - advanced).coerceAtLeast(0L))
                if (step == 0L) break
                loopScheduler.advanceTimeBy(step)
                advanced += step
            }
            repeat(100) {
                loopScheduler.runCurrent()
                if (predicate()) return
                withContext(Dispatchers.Default) { delay(10L) }
            }
            assertTrue("Timed out waiting for condition after advancing ${maxVirtualMs}ms", predicate())
        }

        val popups = mutableListOf<InAppMessagePopup>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            sharedInbox.inAppPopupEvents.collect { popups.add(it) }
        }
        try {
            // User enters background immediately after signing in -> baseline seeds promptly in background
            monitor.onForegroundChanged(false)
            monitor.start()
            loopScheduler.runCurrent()
            assertFalse(monitor.isForegroundLoopActive())
            assertTrue(monitor.isBackgroundInboxLoopActive())

            advanceUntil(5_000L) { preferencesStore.getWatermarks().dmInitialized }
            assertTrue(dispatcher.directMessages.isEmpty())
            assertTrue(dispatcher.interactions.isEmpty())

            // Phase 2: New DM arrives (even with unread-count == 0 and id == 0!) while app is in background
            phase = 2
            advanceUntil(20_000L) { dispatcher.directMessages.size == 1 }
            assertEquals(1, dispatcher.directMessages.size)
            assertEquals(888L, dispatcher.directMessages.single().peerUid)
            assertEquals("后台无ID私信测试", dispatcher.directMessages.single().previewText)
            assertTrue(popups.isEmpty())

            // Phase 3: New reply arrives while app stays in background -> system notification fires, DM not duplicated
            phase = 3
            serverUnreadCount = 1
            advanceUntil(50_000L) { dispatcher.interactions.size == 1 }
            assertEquals(1, dispatcher.interactions.size)
            assertEquals(900L, dispatcher.interactions.single().topicId)
            assertEquals(6, dispatcher.interactions.single().floorNumber)
            assertEquals(1, dispatcher.directMessages.size)
            assertTrue(popups.isEmpty())

            monitor.stop()
        } finally {
            popupJob.cancel()
            loopScope.cancel()
        }
    }

    @Test
    fun `cold process restart restores persisted session snapshot and delivers inbox notifications even when HTML hits Cloudflare`() = runTest {
        val snapshotHolder = object : SessionSnapshotStore {
            var saved: AccountSession? = null
            override fun load(): AccountSession? = saved
            override fun save(session: AccountSession) {
                saved = session
            }
            override fun clear() {
                saved = null
            }
        }

        val baseUrl = server.url("/")
        val sharedCookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = sharedCookieBridge,
            userAgentProvider = UserAgentProvider("AnodeColdRestartTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )

        lateinit var client1: NodeSeekApiClient
        val session1 = SessionRepositoryImpl(
            apiClientProvider = { client1 },
            cookieBridge = sharedCookieBridge,
            sessionSnapshotStore = snapshotHolder,
        )
        client1 = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = session1::onConfigPayloadObserved,
        )

        var coldRestarted = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    !coldRestarted && path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "session=valid_auth_token; Path=/")
                        .setBody(
                            buildFeedHtml(
                                emptyList(),
                                userJson = """{"member_id":101,"member_name":"PersistentUser","coin":500}""",
                            ),
                        )

                    // After cold restart, all HTML & RSS routes hit Cloudflare 403 challenge,
                    // while JSON `/api/notification/*` remains reachable with session cookie!
                    coldRestarted && (
                        path == "/" ||
                            path.contains("sortBy=") ||
                            path.startsWith("/post-") ||
                            path.startsWith("/rss.xml")
                        ) ->
                        MockResponse()
                            .setResponseCode(403)
                            .addHeader("cf-mitigated", "challenge")
                            .setBody("<html><title>Just a moment...</title></html>")

                    !coldRestarted && path.startsWith("/api/notification/") ->
                        MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")

                    coldRestarted && path == "/api/notification/reply-to-me/list" ->
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"success":true,"data":[{"id":77,"viewed":0,"post_id":888,"title":"冷重启后台回复","floor_id":9,"commenter_id":303,"commenter_name":"Carol","created_at":"2026-11-22T11:00:00Z"}]}""")

                    coldRestarted && path == "/api/notification/message/list" ->
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"success":true,"msgArray":[{"id":99,"sender_id":404,"sender_name":"Dave","receiver_id":101,"receiver_name":"PersistentUser","content":"进程被杀后收到的私信","is_markdown":0,"created_at":"2026-11-22T11:01:00Z","viewed":0}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        // Process 1: Sign in and seed baseline watermarks in foreground
        session1.refreshSession().getOrThrow()
        assertEquals(101L, snapshotHolder.load()?.uid)
        val inbox1 = InboxRepositoryImpl(
            apiClientProvider = { client1 },
            sessionRepository = session1,
            watermarkStore = preferencesStore,
        )
        inbox1.syncForegroundInbox().getOrThrow()
        assertEquals(101L, preferencesStore.getWatermarks().inboxAccountUid)
        assertTrue(preferencesStore.getWatermarks().dmInitialized)

        // Process 2 (cold background WorkManager start): new SessionRepositoryImpl + InboxRepositoryImpl + PulseRadarMonitor
        coldRestarted = true
        radarRepository.addKeywordRule(keyword = "DMIT").getOrThrow()
        radarRepository.trackTopic(topicId = 555L, title = "Tracked", currentMaxFloor = 3, totalPages = 1)

        lateinit var client2: NodeSeekApiClient
        val coldSession = SessionRepositoryImpl(
            apiClientProvider = { client2 },
            cookieBridge = sharedCookieBridge,
            sessionSnapshotStore = snapshotHolder,
        )
        assertEquals(101L, coldSession.sessionState.value?.uid)
        client2 = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = coldSession::onConfigPayloadObserved,
        )
        val coldInbox = InboxRepositoryImpl(
            apiClientProvider = { client2 },
            sessionRepository = coldSession,
            watermarkStore = preferencesStore,
        )
        val coldMonitor = PulseRadarMonitor(
            appContext = ContextWrapper(null),
            scope = this,
            apiClient = client2,
            inboxRepository = coldInbox,
            sessionRepository = coldSession,
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            notificationDispatcher = dispatcher,
            watermarkStore = preferencesStore,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { true },
            currentMinuteOfDay = { fakeMinuteOfDay },
        )

        val outcome = coldMonitor.runBackgroundPollPass()
        assertTrue(outcome is RadarBackgroundPollOutcome.Completed)
        val completed = outcome as RadarBackgroundPollOutcome.Completed
        assertEquals(2, completed.inboxNotificationsTriggered)
        assertEquals(1, dispatcher.interactions.size)
        assertEquals(888L, dispatcher.interactions.single().topicId)
        assertEquals(9, dispatcher.interactions.single().floorNumber)
        assertEquals(1, dispatcher.directMessages.size)
        assertEquals(99L, dispatcher.directMessages.single().messageId)
        assertEquals("进程被杀后收到的私信", dispatcher.directMessages.single().previewText)

        // Sign out clears persisted snapshot
        coldRestarted = false
        coldSession.signOutLocalOnly().getOrThrow()
        assertEquals(null, snapshotHolder.load())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `foreground popup advances shared watermark without duplicate OS notification then background pass notifies new arrivals`() = runTest {
        var stage = 0
        val selfUserJson = """{"member_id":101,"member_name":"Me","coin":100}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(emptyList(), userJson = selfUserJson))

                    path == "/api/notification/unread-count" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":$stage}}""")

                    path == "/api/notification/reply-to-me/list" && stage == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":501,"viewed":0,"post_id":700,"title":"前台弹窗帖","floor_id":3,"commenter_id":202,"commenter_name":"Alice","created_at":"2026-11-22T12:00:00Z"}]}""")

                    path == "/api/notification/reply-to-me/list" && stage == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":502,"viewed":0,"post_id":701,"title":"后台系统通知帖","floor_id":4,"commenter_id":203,"commenter_name":"Bob","created_at":"2026-11-22T12:01:00Z"},{"id":501,"viewed":0,"post_id":700,"title":"前台弹窗帖","floor_id":3,"commenter_id":202,"commenter_name":"Alice","created_at":"2026-11-22T12:00:00Z"}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        val sharedInbox = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            watermarkStore = preferencesStore,
        )
        val monitor = PulseRadarMonitor(
            appContext = ContextWrapper(null),
            scope = this,
            apiClient = apiClient,
            inboxRepository = sharedInbox,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            notificationDispatcher = dispatcher,
            watermarkStore = preferencesStore,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            currentMinuteOfDay = { fakeMinuteOfDay },
        )

        val popups = mutableListOf<InAppMessagePopup>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            sharedInbox.inAppPopupEvents.collect { popups.add(it) }
        }
        try {
            // Stage 0: Seed foreground baseline
            sharedInbox.syncForegroundInbox(forceListSync = true).getOrThrow()
            assertEquals(0L, preferencesStore.getWatermarks().lastNotifiedReplyId)
            assertTrue(popups.isEmpty())

            // Stage 1: Foreground reply arrives while RESUMED collector is active -> emits popup, no OS duplicate
            stage = 1
            sharedInbox.syncForegroundInbox(forceListSync = true).getOrThrow()
            assertEquals(1, popups.size)
            assertEquals(501L, popups.single().itemId)
            assertEquals(501L, preferencesStore.getWatermarks().lastNotifiedReplyId)

            val afterForegroundOutcome = monitor.runBackgroundPollPass()
            assertTrue(afterForegroundOutcome is RadarBackgroundPollOutcome.Completed)
            assertEquals(0, dispatcher.interactions.size)

            // Stage 2: User pauses/backgrounds app (unsubscribes RESUMED collector) -> new reply triggers OS notification
            popupJob.cancel()
            stage = 2
            val backgroundOutcome = monitor.runBackgroundPollPass()
            assertTrue(backgroundOutcome is RadarBackgroundPollOutcome.Completed)
            assertEquals(1, popups.size)
            assertEquals(1, dispatcher.interactions.size)
            assertEquals(502L, dispatcher.interactions.single().itemId)
            assertEquals(701L, dispatcher.interactions.single().topicId)
            assertEquals(4, dispatcher.interactions.single().floorNumber)
        } finally {
            popupJob.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `synthetic negative DM watermark for id zero never poisons real positive message IDs or foreground popups`() = runTest {
        var dmStage = 0
        val selfUserJson = """{"member_id":101,"member_name":"Me","coin":100}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(emptyList(), userJson = selfUserJson))

                    path == "/api/notification/unread-count" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":0}}""")

                    path == "/api/notification/message/list" -> {
                        val body = when (dmStage) {
                            1 -> """{"success":true,"msgArray":[{"id":0,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"后台无ID私信A","is_markdown":0,"created_at":"2026-11-22T12:00:00Z","viewed":0}]}"""
                            2 -> """{"success":true,"msgArray":[{"id":0,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"前台无ID私信B","is_markdown":0,"created_at":"2026-11-22T12:01:00Z","viewed":0}]}"""
                            3 -> """{"success":true,"msgArray":[{"id":105,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"真实正整数ID私信105","is_markdown":0,"created_at":"2026-11-22T12:02:00Z","viewed":0}]}"""
                            4 -> """{"success":true,"msgArray":[{"id":106,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"冷重启前台私信106","is_markdown":0,"created_at":"2026-11-22T12:03:00Z","viewed":0}]}"""
                            else -> """{"success":true,"msgArray":[]}"""
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        val sharedInbox = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            watermarkStore = preferencesStore,
        )
        val monitor = PulseRadarMonitor(
            appContext = ContextWrapper(null),
            scope = this,
            apiClient = apiClient,
            inboxRepository = sharedInbox,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            notificationDispatcher = dispatcher,
            watermarkStore = preferencesStore,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            currentMinuteOfDay = { fakeMinuteOfDay },
        )

        // Stage 0: Seed baseline in background
        assertTrue(monitor.runBackgroundPollPass() is RadarBackgroundPollOutcome.Completed)

        // Stage 1: id=0 DM arrives in background -> negative watermark stored, 1 OS DM notification
        dmStage = 1
        assertTrue(monitor.runBackgroundPollPass() is RadarBackgroundPollOutcome.Completed)
        assertEquals(1, dispatcher.directMessages.size)
        val syntheticMarkerA = preferencesStore.getWatermarks().lastNotifiedDmMap[888L] ?: 0L
        assertTrue("Expected negative synthetic marker, got $syntheticMarkerA", syntheticMarkerA < 0L)

        // User enters foreground (RESUMED) -> no duplicate popup for already-notified id=0 DM A
        val popups = mutableListOf<InAppMessagePopup>()
        var popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            sharedInbox.inAppPopupEvents.collect { popups.add(it) }
        }
        try {
            sharedInbox.syncForegroundInbox(forceListSync = true).getOrThrow()
            assertTrue("Expected zero duplicate popups on foreground return, got $popups", popups.isEmpty())

            // Stage 2: Second id=0 DM arrives in foreground -> pops in-app, updates negative marker, no OS duplicate
            dmStage = 2
            sharedInbox.syncForegroundInbox(forceListSync = true).getOrThrow()
            assertEquals(1, popups.size)
            assertEquals("前台无ID私信B", popups.single().previewText)
            val syntheticMarkerB = preferencesStore.getWatermarks().lastNotifiedDmMap[888L] ?: 0L
            assertTrue(syntheticMarkerB < 0L && syntheticMarkerB != syntheticMarkerA)

            popupJob.cancel()
            assertTrue(monitor.runBackgroundPollPass() is RadarBackgroundPollOutcome.Completed)
            assertEquals(1, dispatcher.directMessages.size)

            // Stage 3: Real positive id=105 DM arrives in background -> NOT poisoned by synthetic marker!
            dmStage = 3
            assertTrue(monitor.runBackgroundPollPass() is RadarBackgroundPollOutcome.Completed)
            assertEquals(2, dispatcher.directMessages.size)
            assertEquals(105L, dispatcher.directMessages.last().messageId)
            assertEquals("真实正整数ID私信105", dispatcher.directMessages.last().previewText)
            assertEquals(105L, preferencesStore.getWatermarks().lastNotifiedDmMap[888L])

            // Stage 4: Cold-restarted InboxRepositoryImpl seeds from persisted watermarks and still pops id=106!
            val coldInbox = InboxRepositoryImpl(
                apiClientProvider = { apiClient },
                sessionRepository = sessionRepository,
                watermarkStore = preferencesStore,
            )
            val coldPopups = mutableListOf<InAppMessagePopup>()
            popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                coldInbox.inAppPopupEvents.collect { coldPopups.add(it) }
            }
            dmStage = 4
            coldInbox.syncForegroundInbox(forceListSync = true).getOrThrow()
            assertEquals(1, coldPopups.size)
            assertEquals(106L, coldPopups.single().itemId)
        } finally {
            popupJob.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `foreground return suppresses duplicate popups for background-notified replies and resets background unread baseline`() = runTest {
        var stage = 0
        var serverUnreadCount = 0
        val selfUserJson = """{"member_id":101,"member_name":"Me","coin":100}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(emptyList(), userJson = selfUserJson))

                    path == "/api/notification/unread-count" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"unreadCount":{"all":$serverUnreadCount}}""")

                    path == "/api/notification/reply-to-me/list" -> {
                        val body = when (stage) {
                            1 -> """{"success":true,"data":[
                                {"id":603,"viewed":0,"post_id":801,"title":"回复3","floor_id":4,"commenter_id":202,"commenter_name":"A","created_at":"2026-11-22T12:03:00Z"},
                                {"id":602,"viewed":0,"post_id":801,"title":"回复2","floor_id":3,"commenter_id":202,"commenter_name":"A","created_at":"2026-11-22T12:02:00Z"},
                                {"id":601,"viewed":0,"post_id":801,"title":"回复1","floor_id":2,"commenter_id":202,"commenter_name":"A","created_at":"2026-11-22T12:01:00Z"}
                            ]}"""
                            2 -> """{"success":true,"data":[
                                {"id":603,"viewed":1,"post_id":801,"title":"回复3","floor_id":4,"commenter_id":202,"commenter_name":"A","created_at":"2026-11-22T12:03:00Z"},
                                {"id":602,"viewed":1,"post_id":801,"title":"回复2","floor_id":3,"commenter_id":202,"commenter_name":"A","created_at":"2026-11-22T12:02:00Z"},
                                {"id":601,"viewed":1,"post_id":801,"title":"回复1","floor_id":2,"commenter_id":202,"commenter_name":"A","created_at":"2026-11-22T12:01:00Z"}
                            ]}"""
                            3 -> """{"success":true,"data":[
                                {"id":604,"viewed":0,"post_id":802,"title":"二进后台新回复","floor_id":5,"commenter_id":205,"commenter_name":"Eve","created_at":"2026-11-22T12:05:00Z"}
                            ]}"""
                            else -> """{"success":true,"data":[]}"""
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        sessionRepository.refreshSession().getOrThrow()
        val loopScheduler = TestCoroutineScheduler()
        val loopScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(loopScheduler))
        val sharedInbox = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            watermarkStore = preferencesStore,
        )
        val monitor = PulseRadarMonitor(
            appContext = ContextWrapper(null),
            scope = loopScope,
            apiClient = apiClient,
            inboxRepository = sharedInbox,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            notificationDispatcher = dispatcher,
            watermarkStore = preferencesStore,
            clockMillis = { fakeClockMillis + loopScheduler.currentTime },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            currentMinuteOfDay = { fakeMinuteOfDay },
        )

        suspend fun advanceUntil(maxVirtualMs: Long, predicate: suspend () -> Boolean) {
            var advanced = 0L
            while (advanced <= maxVirtualMs) {
                loopScheduler.runCurrent()
                if (predicate()) return
                withContext(Dispatchers.Default) { delay(10L) }
                loopScheduler.runCurrent()
                if (predicate()) return
                val step = minOf(500L, (maxVirtualMs - advanced).coerceAtLeast(0L))
                if (step == 0L) break
                loopScheduler.advanceTimeBy(step)
                advanced += step
            }
            repeat(100) {
                loopScheduler.runCurrent()
                if (predicate()) return
                withContext(Dispatchers.Default) { delay(10L) }
            }
            assertTrue("Timed out waiting for condition", predicate())
        }

        try {
            // Seed in foreground first
            sharedInbox.syncForegroundInbox(forceListSync = true).getOrThrow()

            // Background #1: unread-count jumps to 3 -> 3 OS notifications emitted
            monitor.onForegroundChanged(false)
            monitor.start()
            stage = 1
            serverUnreadCount = 3
            advanceUntil(20_000L) { dispatcher.interactions.size == 3 }
            assertEquals(3, dispatcher.interactions.size)

            // Return to foreground (RESUMED) -> must NOT re-emit popups for 601..603, and user reads all (unread=0)
            monitor.onForegroundChanged(true)
            val popups = mutableListOf<InAppMessagePopup>()
            val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                sharedInbox.inAppPopupEvents.collect { popups.add(it) }
            }
            sharedInbox.syncForegroundInbox().getOrThrow()
            assertTrue("Expected no duplicate foreground popups after background notify, got $popups", popups.isEmpty())

            stage = 2
            serverUnreadCount = 0
            sharedInbox.syncForegroundInbox(forceListSync = true).getOrThrow()
            popupJob.cancel()

            // Background #2: unread-count becomes 1 (less than Background #1's 3!) -> immediately notifies 604!
            stage = 3
            serverUnreadCount = 1
            monitor.onForegroundChanged(false)
            advanceUntil(20_000L) { dispatcher.interactions.size == 4 }
            assertEquals(4, dispatcher.interactions.size)
            assertEquals(604L, dispatcher.interactions.last().itemId)
            monitor.stop()
        } finally {
            loopScope.cancel()
        }
    }

    @Test
    fun `HTML Cloudflare backoff isolates web scraping without blocking subsequent background inbox poll passes`() = runTest {
        var htmlRequests = 0
        var inboxStep = 1
        val selfUserJson = """{"member_id":101,"member_name":"Me","coin":100}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/" && inboxStep == 0 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(emptyList(), userJson = selfUserJson))

                    path == "/" || path.contains("sortBy=") || path.startsWith("/rss.xml") -> {
                        htmlRequests++
                        MockResponse()
                            .setResponseCode(403)
                            .addHeader("cf-mitigated", "challenge")
                            .setBody("<html><title>Just a moment...</title></html>")
                    }

                    path == "/api/notification/reply-to-me/list" && inboxStep == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":801,"viewed":0,"post_id":950,"title":"CF退避期间收到的回复","floor_id":7,"commenter_id":301,"commenter_name":"Frank","created_at":"2026-11-22T12:10:00Z"}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        inboxStep = 0
        sessionRepository.refreshSession().getOrThrow()
        radarRepository.addKeywordRule(keyword = "DMIT").getOrThrow()
        val monitor = createMonitor(this)

        // Pass 1: HTML + RSS hit Cloudflare 403 (sets 60s htmlCfBackoff), while JSON inbox seeds baseline
        inboxStep = 1
        val pass1 = monitor.runBackgroundPollPass()
        assertTrue(pass1 is RadarBackgroundPollOutcome.Completed)
        val htmlCallsAfterPass1 = htmlRequests
        assertTrue(htmlCallsAfterPass1 > 0)

        // Pass 2 (10s later, still inside 60s HTML CF backoff): HTML is skipped, JSON inbox STILL delivers 801!
        fakeClockMillis += 10_000L
        inboxStep = 2
        val pass2 = monitor.runBackgroundPollPass()
        assertTrue("Expected Completed instead of Skipped(BACKOFF_ACTIVE), got $pass2", pass2 is RadarBackgroundPollOutcome.Completed)
        assertEquals(htmlCallsAfterPass1, htmlRequests)
        assertEquals(1, (pass2 as RadarBackgroundPollOutcome.Completed).inboxNotificationsTriggered)
        assertEquals(1, dispatcher.interactions.size)
        assertEquals(801L, dispatcher.interactions.single().itemId)
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

    var notificationPermissionGranted: Boolean = true

    override fun ensureChannels() = Unit

    override fun hasPostNotificationsPermission(): Boolean = notificationPermissionGranted

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

    override suspend fun insertKeywordRules(entities: List<KeywordWatchRuleEntity>): List<Long> =
        entities.map { insertKeywordRule(it) }

    override suspend fun setKeywordRuleEnabled(ruleId: Long, enabled: Boolean) {
        rulesState.value = rulesState.value.map {
            if (it.id == ruleId) it.copy(isEnabled = enabled) else it
        }
    }

    override suspend fun deleteKeywordRule(ruleId: Long) {
        rulesState.value = rulesState.value.filterNot { it.id == ruleId }
    }

    override suspend fun clearKeywordRules() {
        rulesState.value = emptyList()
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

    override suspend fun getAllTrackedTopics(): List<TrackedTopicEntity> =
        trackedState.value.sortedWith(
            compareByDescending<TrackedTopicEntity> { it.lastUpdatedEpochMillis }
                .thenByDescending { it.createdAtEpochMillis },
        )

    override suspend fun upsertTrackedTopic(entity: TrackedTopicEntity) {
        trackedState.value = trackedState.value.filterNot { it.topicId == entity.topicId } + entity
    }

    override suspend fun upsertTrackedTopics(entities: List<TrackedTopicEntity>) {
        for (entity in entities) {
            upsertTrackedTopic(entity)
        }
    }

    override suspend fun deleteTrackedTopic(topicId: Long) {
        trackedState.value = trackedState.value.filterNot { it.topicId == topicId }
    }

    override suspend fun clearTrackedTopics() {
        trackedState.value = emptyList()
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
