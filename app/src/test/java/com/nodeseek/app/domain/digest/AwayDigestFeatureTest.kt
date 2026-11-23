package com.nodeseek.app.domain.digest

import com.nodeseek.app.data.local.CachedTopicDrumstickMeta
import com.nodeseek.app.data.local.InMemoryAwayDigestStore
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.ui.explore.ExploreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class AwayDigestFeatureTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `AwayDurationFormatter renders exact Chinese away durations including sub-minute and custom thresholds`() {
        assertEquals(
            "你离开了不到 1 分钟",
            AwayDurationFormatter.formatHeadline(0L),
        )
        assertEquals(
            "你离开了不到 1 分钟",
            AwayDurationFormatter.formatHeadline(35_000L),
        )
        assertEquals(
            "不到 1 分钟",
            AwayDurationFormatter.formatDurationOnly(59_999L),
        )
        assertEquals(
            "你离开了 1 分钟",
            AwayDurationFormatter.formatHeadline(60_000L),
        )
        assertEquals(
            "你离开了 45 分钟",
            AwayDurationFormatter.formatHeadline(45L * 60_000L),
        )
        assertEquals(
            "你离开了 9 小时",
            AwayDurationFormatter.formatHeadline(9L * 3_600_000L),
        )
        assertEquals(
            "你离开了 9 小时",
            AwayDurationFormatter.formatHeadline(9L * 3_600_000L + 42_000L),
        )
        assertEquals(
            "你离开了 2 小时 30 分钟",
            AwayDurationFormatter.formatHeadline(2L * 3_600_000L + 30L * 60_000L),
        )
        assertEquals(
            "你离开了 1 天",
            AwayDurationFormatter.formatHeadline(24L * 3_600_000L),
        )
        assertEquals(
            "你离开了 1 天 5 小时",
            AwayDurationFormatter.formatHeadline(29L * 3_600_000L),
        )

        // Threshold formatting & custom input validation
        assertEquals("30 分钟", AwayDurationFormatter.formatThresholdMinutes(30))
        assertEquals("45 分钟", AwayDurationFormatter.formatThresholdMinutes(45))
        assertEquals("1 小时", AwayDurationFormatter.formatThresholdMinutes(60))
        assertEquals("1 小时 30 分钟", AwayDurationFormatter.formatThresholdMinutes(90))
        assertEquals("9 小时", AwayDurationFormatter.formatThresholdMinutes(540))
        assertEquals("24 小时", AwayDurationFormatter.formatThresholdMinutes(1440))

        assertEquals(45, AwayDurationFormatter.parseCustomThresholdMinutes("", "45"))
        assertEquals(90, AwayDurationFormatter.parseCustomThresholdMinutes("1", "30"))
        assertEquals(540, AwayDurationFormatter.parseCustomThresholdMinutes("9", "0"))
        assertEquals(4320, AwayDurationFormatter.parseCustomThresholdMinutes("72", "0"))
        assertNull(AwayDurationFormatter.parseCustomThresholdMinutes("0", "4")) // < 5 min
        assertNull(AwayDurationFormatter.parseCustomThresholdMinutes("72", "1")) // > 72h
    }

    @Test
    fun `away session lifecycle detects 9 hour absence, survives brief switch, and stays consumed after dismiss`() = runTest {
        val baseTime = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()
        var now = baseTime
        val store = InMemoryAwayDigestStore()
        val forum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to listOf(
                    buildTopic(
                        id = 101L,
                        title = "DMIT TYO Pro 限时补货好价",
                        slug = "info",
                        replies = 42,
                        views = 1800,
                        iso = Instant.ofEpochMilli(baseTime + 4L * 3_600_000L).toString(),
                    ),
                ),
            ),
            drumsticksByTopicId = mapOf(101L to 12),
        )

        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )

        // 1. First-ever app open does NOT trigger an away session
        repo.onAppForegrounded(now)
        assertNull(repo.state.value.activeSession)
        assertFalse(repo.state.value.isVisible)

        // 2. Background and come back after 25 minutes (< 1h threshold): still no away session
        now += 10L * 60_000L
        repo.onAppBackgrounded(now)
        now += 25L * 60_000L
        repo.onAppForegrounded(now)
        assertNull(repo.state.value.activeSession)
        assertFalse(repo.state.value.isVisible)

        // 3. User goes to work at 09:35 and reopens 9 hours later at 18:35 -> "你离开了 9 小时"
        repo.onAppBackgrounded(now)
        val awayStart = now
        now += 9L * 3_600_000L
        repo.onAppForegrounded(now)

        val activeState = repo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(activeState.isVisible)
        assertEquals("你离开了 9 小时", activeState.headlineText)
        assertEquals(awayStart, activeState.activeSession?.awayStartMillis)
        assertEquals(1, activeState.items.size)
        assertEquals(12, activeState.items.single().drumstickCount)

        val originalSessionId = activeState.activeSession?.sessionId
        assertNotNull(originalSessionId)

        // 4. Brief app switch (2 minutes) without dismissing keeps the active digest intact
        now += 1L * 60_000L
        repo.onAppBackgrounded(now)
        now += 2L * 60_000L
        repo.onAppForegrounded(now)
        assertTrue(repo.state.value.isVisible)
        assertEquals(originalSessionId, repo.state.value.activeSession?.sessionId)

        // 5. Dismissing marks session consumed; short background/foreground or refresh never resurrects it
        repo.dismissCurrentSession()
        assertFalse(repo.state.value.isVisible)
        assertTrue(store.getActiveSession()?.isDismissed == true)

        repo.refreshCurrentSessionDigest(forceReload = true)
        assertFalse(repo.state.value.isVisible)

        now += 5L * 60_000L
        repo.onAppBackgrounded(now)
        now += 15L * 60_000L
        repo.onAppForegrounded(now)
        assertFalse(repo.state.value.isVisible)

        // 6. Cold-start process restart after another 3-hour absence creates a fresh 3-hour digest
        repo.onAppBackgrounded(now)
        now += 3L * 3_600_000L
        val coldRestartRepo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )
        coldRestartRepo.onAppForegrounded(now)
        val restartedState = coldRestartRepo.state.first { !it.isLoading }
        assertEquals("你离开了 3 小时", restartedState.activeSession?.headlineText)
    }

    @Test
    fun `multi-page scan captures morning gems across pages 1 to 3 and stops early when page 4 precedes away window`() = runTest {
        val awayStart = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()
        val reopenedAt = awayStart + 9L * 3_600_000L // 18:00Z

        val pages = mapOf(
            1 to listOf(
                buildTopic(1L, "傍晚新帖 A", "daily", replies = 8, views = 200, iso = "2026-11-22T17:30:00Z"),
                buildTopic(2L, "傍晚新帖 B", "tech", replies = 15, views = 450, iso = "2026-11-22T16:40:00Z"),
            ),
            2 to listOf(
                buildTopic(3L, "下午深度测评", "review", replies = 48, views = 2200, iso = "2026-11-22T14:00:00Z"),
                buildTopic(4L, "午间促销补货", "info", replies = 36, views = 1900, iso = "2026-11-22T12:15:00Z"),
            ),
            3 to listOf(
                buildTopic(5L, "早盘限时好价 5折闪购", "promotion", replies = 62, views = 3400, iso = "2026-11-22T09:30:00Z"),
                buildTopic(6L, "上班前发的旧帖", "daily", replies = 90, views = 5000, iso = "2026-11-22T08:15:00Z"),
            ),
            4 to listOf(
                // Every topic on page 4 is older than 09:00Z -> scan must stop here and NOT fetch pages 5 or 6!
                buildTopic(7L, "昨晚旧帖 1", "tech", replies = 120, views = 6000, iso = "2026-11-22T07:00:00Z"),
                buildTopic(8L, "昨晚旧帖 2", "trade", replies = 80, views = 4000, iso = "2026-11-22T05:30:00Z"),
            ),
            5 to listOf(
                buildTopic(9L, "不该被请求的第 5 页", "tech", replies = 200, views = 9000, iso = "2026-11-22T02:00:00Z"),
            ),
            6 to listOf(
                buildTopic(10L, "不该被请求的第 6 页", "tech", replies = 200, views = 9000, iso = "2026-11-22T01:00:00Z"),
            ),
        )

        val store = InMemoryAwayDigestStore(initialLastActiveTimestampMillis = awayStart)
        val forum = FakeDigestForumRepository(
            pagesByNumber = pages,
            hasNextUpToPage = 6,
            drumsticksByTopicId = mapOf(5L to 20, 3L to 11, 4L to 8),
        )

        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { reopenedAt },
            maxPagesToScan = 6,
        )

        repo.onAppForegrounded(reopenedAt)
        val digest = repo.state.first { !it.isLoading && it.items.isNotEmpty() }

        // Stopped early at page 4 without requesting pages 5 or 6
        assertEquals(listOf(1, 2, 3, 4), forum.requestedFeedPages)
        assertEquals(4, digest.pagesScanned)

        // Only topics 1..5 fall inside [09:00Z, 18:00Z]; topics 6, 7, 8 before 09:00Z are excluded
        val returnedIds = digest.items.map { it.topic.id }.toSet()
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), returnedIds)
        // Morning flash deal #5 (buried on page 3!) ranks #1 thanks to 20 drumsticks + 62 replies + deal boost
        assertEquals(5L, digest.items.first().topic.id)
        assertEquals(1, digest.items.first().rank)
        assertEquals(20, digest.items.first().drumstickCount)
    }

    @Test
    fun `already-read topics are automatically skipped and reading an item promotes unread reserve into top 10`() = runTest {
        val awayStart = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()
        val reopenedAt = awayStart + 9L * 3_600_000L

        // Create 14 away-window topics with descending engagement (IDs 1..14)
        val allTopics = (1L..14L).map { idx ->
            val rankWeight = (15 - idx).toInt()
            buildTopic(
                id = idx,
                title = "热门讨论 #$idx",
                slug = "tech",
                replies = rankWeight * 6,
                views = rankWeight * 250,
                iso = Instant.ofEpochMilli(awayStart + idx * 15L * 60_000L).toString(),
            )
        }
        val drumsticks = (1L..14L).associateWith { idx -> (15 - idx).toInt() }

        val historyRepo = FakeDigestHistoryRepository(
            initialReadIds = setOf(1L, 3L, 5L), // 3 high-heat topics already read previously
        )
        val store = InMemoryAwayDigestStore(initialLastActiveTimestampMillis = awayStart)
        val forum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to allTopics.subList(0, 7),
                2 to allTopics.subList(7, 14),
            ),
            hasNextUpToPage = 2,
            drumsticksByTopicId = drumsticks,
        )

        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum,
            bookmarkAndHistoryRepository = historyRepo,
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { reopenedAt },
        )

        repo.onAppForegrounded(reopenedAt)
        val initialDigest = repo.state.first { !it.isLoading && it.items.size == 10 }

        // Topics 1, 3, 5 were already read -> skipped automatically!
        assertEquals(3, initialDigest.skippedReadCount)
        assertEquals(11, initialDigest.totalUnreadInWindow)
        assertEquals(
            listOf(2L, 4L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L),
            initialDigest.items.map { it.topic.id },
        )
        assertEquals((1..10).toList(), initialDigest.items.map { it.rank })

        // Now user reads #2 (current rank #1): #2 must be skipped and #14 from reserve pool steps into #10!
        historyRepo.recordTopicVisit(
            ReadHistoryEntry(
                topicId = 2L,
                title = "热门讨论 #2",
                categoryName = "技术",
                authorName = "author_2",
                authorAvatarUrl = "",
                lastReadPage = 1,
                lastReadFloor = 5,
                visitedAtEpochMillis = reopenedAt + 5_000L,
            ),
        )

        val updatedDigest = repo.state.first { it.skippedReadCount == 4 }
        assertEquals(10, updatedDigest.items.size)
        assertFalse(updatedDigest.items.any { it.topic.id == 2L })
        assertEquals(
            listOf(4L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L),
            updatedDigest.items.map { it.topic.id },
        )
        assertEquals((1..10).toList(), updatedDigest.items.map { it.rank })
    }

    @Test
    fun `composite ranking balances drumsticks, replies, velocity, deal boost, content filter, and offline detail fallback`() = runTest {
        val awayStart = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()
        val reopenedAt = awayStart + 9L * 3_600_000L // 18:00Z

        val highDrumstickDeal = buildTopic(
            id = 201L,
            title = "搬瓦工 The Plan 限量版补货限时好价",
            slug = "info",
            replies = 28,
            views = 1600,
            iso = "2026-11-22T11:00:00Z", // 7h ago
        )
        val surgingVelocityPost = buildTopic(
            id = 202L,
            title = "突发：晚高峰亚太海底光缆路由切换实测",
            slug = "tech",
            replies = 34,
            views = 1500,
            iso = "2026-11-22T17:30:00Z", // 30m ago -> very high engagement/hour velocity
        )
        val olderSameCountPost = buildTopic(
            id = 203L,
            title = "早间网络延迟常规记录",
            slug = "tech",
            replies = 34,
            views = 1500,
            iso = "2026-11-22T09:30:00Z", // 8.5h ago -> lower velocity with same replies/drumsticks
        )
        val lowQualityChat = buildTopic(
            id = 204L,
            title = "今天下午茶喝什么",
            slug = "daily",
            replies = 12,
            views = 180,
            iso = "2026-11-22T15:00:00Z",
        )
        val mutedLotterySpam = buildTopic(
            id = 205L,
            title = "[抽奖] 水一贴送小鸡",
            slug = "daily",
            replies = 150,
            views = 4000,
            iso = "2026-11-22T16:00:00Z",
        )

        val filterRepo = FakeDigestFilterRepository(
            ContentFilterConfig(
                isEnabled = true,
                rules = listOf(ContentFilterEngine.CURATED_PRESETS.last().toRule()), // Blocks "[抽奖]"
            ),
        )

        val forum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to listOf(lowQualityChat, mutedLotterySpam, olderSameCountPost, surgingVelocityPost, highDrumstickDeal),
            ),
            drumsticksByTopicId = mapOf(
                201L to 16, // High drumsticks on deal
                202L to 4,
                203L to 4,
                204L to 0,
                205L to 30,
            ),
        )

        val repo = AwayDigestRepositoryImpl(
            store = InMemoryAwayDigestStore(initialLastActiveTimestampMillis = awayStart),
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = filterRepo,
            scope = backgroundScope,
            clockMillis = { reopenedAt },
        )

        repo.onAppForegrounded(reopenedAt)
        val ranked = repo.state.first { !it.isLoading && it.items.isNotEmpty() }.items

        // Muted lottery post 205 is excluded by ContentFilterEngine
        assertFalse(ranked.any { it.topic.id == 205L })

        // Order: 201 (16 drumsticks + deal boost) > 202 (surging velocity) > 203 (older same counts) > 204 (low chatter)
        assertEquals(listOf(201L, 202L, 203L, 204L), ranked.map { it.topic.id })
        assertTrue(ranked[0].isDealOrPromo)
        assertTrue(ranked[1].hotnessScore > ranked[2].hotnessScore)

        // Verify graceful fallback when getTopicDetail fails (offline / unauthenticated Lv-locked)
        val offlineForum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to listOf(
                    lowQualityChat.copy(drumstickCount = 0),
                    highDrumstickDeal.copy(drumstickCount = 9),
                ),
            ),
            failDetailRequests = true,
        )
        val offlineRepo = AwayDigestRepositoryImpl(
            store = InMemoryAwayDigestStore(initialLastActiveTimestampMillis = awayStart),
            forumRepository = offlineForum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { reopenedAt },
        )
        offlineRepo.onAppForegrounded(reopenedAt)
        val offlineRanked = offlineRepo.state.first { !it.isLoading && it.items.isNotEmpty() }.items
        assertEquals(listOf(201L, 204L), offlineRanked.map { it.topic.id })
        assertEquals(9, offlineRanked.first().drumstickCount)
    }

    @Test
    fun `ExploreViewModel surfaces 9-hour digest, records read history on click, and keeps dismissal across refresh`() = runTest {
        val awayStart = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()
        val reopenedAt = awayStart + 9L * 3_600_000L

        val store = InMemoryAwayDigestStore(initialLastActiveTimestampMillis = awayStart)
        val historyRepo = FakeDigestHistoryRepository()
        val forum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to (1L..5L).map { id ->
                    buildTopic(
                        id = id,
                        title = "精选未读帖 #$id",
                        slug = "review",
                        replies = (6 - id).toInt() * 10,
                        views = (6 - id).toInt() * 400,
                        iso = Instant.ofEpochMilli(awayStart + id * 3_600_000L).toString(),
                    )
                },
            ),
            drumsticksByTopicId = mapOf(1L to 14, 2L to 9, 3L to 5, 4L to 2, 5L to 1),
        )
        val sessionRepo = FakeDigestSessionRepository()
        val settingsRepo = FakeSettingsRepository()
        val filterRepo = FakeDigestFilterRepository()

        val awayRepo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum,
            bookmarkAndHistoryRepository = historyRepo,
            sessionRepository = sessionRepo,
            settingsRepository = settingsRepo,
            contentFilterRepository = filterRepo,
            scope = backgroundScope,
            clockMillis = { reopenedAt },
        )
        awayRepo.onAppForegrounded(reopenedAt)

        val viewModel = ExploreViewModel(
            forumRepository = forum,
            sessionRepository = sessionRepo,
            bookmarkAndHistoryRepository = historyRepo,
            settingsRepository = settingsRepo,
            contentFilterRepository = filterRepo,
            awayDigestRepository = awayRepo,
        )
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect {} }

        val ready = viewModel.uiState.first {
            !it.isInitialLoading && !it.awayDigest.isLoading && it.awayDigest.items.size == 5
        }
        assertTrue(ready.awayDigest.isVisible)
        assertEquals("你离开了 9 小时", ready.awayDigest.headlineText)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ready.awayDigest.items.map { it.topic.id })
        assertFalse(ready.isAwayDigestExpanded)

        // Expand inline list
        viewModel.toggleAwayDigestExpanded()
        assertTrue(viewModel.uiState.value.isAwayDigestExpanded)

        // Clicking topic #1 records read history and automatically skips #1 from the unread digest
        viewModel.onAwayDigestTopicClicked(ready.awayDigest.items.first().topic)
        val afterClick = viewModel.uiState.first { it.awayDigest.items.size == 4 }
        assertEquals(listOf(2L, 3L, 4L, 5L), afterClick.awayDigest.items.map { it.topic.id })
        assertEquals(1, afterClick.awayDigest.skippedReadCount)

        // Dismissing hides the digest and does NOT resurrect on pull-to-refresh or board switch
        viewModel.dismissAwayDigest()
        assertFalse(viewModel.uiState.value.awayDigest.isVisible)
        assertFalse(viewModel.uiState.value.isAwayDigestExpanded)

        viewModel.refresh("")
        viewModel.selectCategory("tech")
        viewModel.selectCategory("")
        val afterRefresh = viewModel.uiState.first { !it.isRefreshing }
        assertFalse(afterRefresh.awayDigest.isVisible)

        collectJob.cancel()
    }

    @Test
    fun `NodeSeekHtmlParser extracts feed drumstick likes when present on DOM rows or rotateTopics`() {
        val configB64 = Base64.getEncoder().encodeToString(
            """
            {
              "allCategory": [{ "key": "info", "cn_text": "情报" }],
              "rotateTopics": [
                {
                  "postId": 88,
                  "titleText": "置顶好价汇总",
                  "category": "info",
                  "views": 1000,
                  "comments": 20,
                  "likeCount": 15,
                  "lastCommentDate": "2026-11-22T10:00:00Z"
                }
              ]
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val html = """
            <html><body>
              <div id="nsk-body">
                <div class="post-list-item" data-likes="7">
                  <div class="post-title"><a href="/post-99-1">新加坡直连补货</a></div>
                  <a class="post-category" href="/categories/info">情报</a>
                  <span class="info-author"><a href="/space/9">HostBot</a></span>
                  <span class="info-views"><span>420</span></span>
                  <span class="info-comments-count"><span>18</span></span>
                  <time datetime="2026-11-22T12:00:00Z"></time>
                </div>
              </div>
              <script id="temp-script" type="application/json">$configB64</script>
            </body></html>
        """.trimIndent()

        val parsed = NodeSeekHtmlParser.parseFeedPage(html, "https://www.nodeseek.com/")
        assertEquals(15, parsed.pinnedTopics.single().likes)
        assertEquals(7, parsed.topics.single().likes)
    }

    @Test
    fun `custom duration thresholds from SettingsRepository and disabling AwayDigest work reactively`() = runTest {
        val baseTime = Instant.parse("2026-11-22T08:00:00Z").toEpochMilli()
        var now = baseTime
        val store = InMemoryAwayDigestStore()
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setAwayDigestTriggerMode(AwayDigestTriggerMode.CUSTOM_DURATION)
        settingsRepo.setAwayDigestThresholdMinutes(45) // User sets custom 45-minute threshold

        val requestedFeedPages = mutableListOf<Int>()
        val dynamicForum = object : ForumRepository {
            override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)
            override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
                Result.success(Category.DEFAULT_CATEGORIES)

            override suspend fun getFeed(
                categorySlug: String,
                sort: FeedSort,
                page: Int,
                includeBlocked: Boolean,
                forceRefresh: Boolean,
            ): Result<FeedPageResult> {
                if (sort == FeedSort.NEWEST_TOPIC) {
                    requestedFeedPages.add(page)
                }
                val topicTimeMillis = if (page == 1) {
                    now - 5L * 60_000L
                } else {
                    now - (page - 1) * 2L * 3_600_000L
                }
                return Result.success(
                    FeedPageResult(
                        topics = listOf(
                            buildTopic(
                                id = page.toLong(),
                                title = "动态页扫描好帖 #$page",
                                slug = "info",
                                replies = 30,
                                views = 1200,
                                iso = Instant.ofEpochMilli(topicTimeMillis).toString(),
                                drumsticks = 10,
                            ),
                        ),
                        currentPage = page,
                        hasNextPage = page < 10,
                        isFromRssFallback = false,
                    ),
                )
            }

            override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
                Result.success(FeedPageResult(topics = emptyList(), currentPage = page, hasNextPage = false))

            override suspend fun searchTopics(query: String, categorySlug: String, sort: FeedSort, page: Int): Result<FeedPageResult> =
                Result.success(FeedPageResult(topics = emptyList()))

            override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
                Result.failure(IOException("skip detail"))

            override suspend fun createTopic(categorySlug: String, title: String, markdownContent: String, requiredViewLevel: Int) =
                Result.success(Unit)
            override suspend fun editTopic(topicId: Long, title: String, markdownContent: String, requiredViewLevel: Int) =
                Result.success(Unit)
            override suspend fun postReply(topicId: Long, markdownContent: String) = Result.success(Unit)
            override suspend fun editReply(commentId: Long, markdownContent: String) = Result.success(Unit)
            override suspend fun setReplyPinned(commentId: Long, pinned: Boolean) = Result.success(Unit)
            override suspend fun getFreeDrumstickCost() = Result.success(0)
            override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType) =
                Result.success(ReactionResult(updatedCount = 1))
        }

        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = dynamicForum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )

        // Baseline initial open
        repo.onAppForegrounded(now)
        assertFalse(repo.state.value.isVisible)

        // 30 minutes (< 45 min custom threshold) does NOT trigger
        repo.onAppBackgrounded(now)
        now += 30L * 60_000L
        repo.onAppForegrounded(now)
        assertFalse(repo.state.value.isVisible)

        // User lowers threshold to 30 minutes in Settings while in foreground -> immediately promotes 30m session!
        settingsRepo.setAwayDigestThresholdMinutes(30)
        val promoted30m = repo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(promoted30m.isVisible)
        assertEquals("你离开了 30 分钟", promoted30m.headlineText)

        // Disabling Away Digest in Settings immediately hides the active digest
        settingsRepo.setAwayDigestEnabled(false)
        val disabledState = repo.state.first { !it.isVisible }
        assertFalse(disabledState.isVisible)
        assertNull(disabledState.activeSession)

        // Re-enable and configure 4 hours (240 min), then test 2h (< 4h) vs 4h vs 9h vs 24h
        settingsRepo.setAwayDigestEnabled(true)
        testScheduler.runCurrent()
        repo.dismissCurrentSession()
        settingsRepo.setAwayDigestThresholdMinutes(240) // 4 hours
        testScheduler.runCurrent()

        repo.onAppBackgrounded(now)
        now += 2L * 3_600_000L // 2h < 4h -> should NOT trigger even on cold restart in CUSTOM_DURATION mode
        val coldRepoUnder4h = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = dynamicForum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )
        coldRepoUnder4h.onAppForegrounded(now)
        assertFalse(coldRepoUnder4h.state.value.isVisible)

        // Now away for 24 hours with 9h (540 min) threshold -> triggers & scales maxPagesToScan up to 10!
        settingsRepo.setAwayDigestThresholdMinutes(540)
        coldRepoUnder4h.onAppBackgrounded(now)
        requestedFeedPages.clear()
        now += 24L * 3_600_000L
        coldRepoUnder4h.onAppForegrounded(now)

        val dayDigest = coldRepoUnder4h.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(dayDigest.isVisible)
        assertEquals("你离开了 1 天", dayDigest.headlineText)
        assertEquals(10, AwayDigestEngine.recommendedMaxPagesToScan(24L * 3_600_000L))
        assertEquals(8, AwayDigestEngine.recommendedMaxPagesToScan(12L * 3_600_000L))
        assertEquals((1..10).toList(), requestedFeedPages)
    }

    @Test
    fun `ON_APP_REOPEN triggers on cold start and Activity close after 5 minutes, ignores warm switch, and handles minute-granularity timestamps`() = runTest {
        // User closes app at 10:15:48Z (Beijing 18:15:48)
        val closeAt = Instant.parse("2026-11-22T10:15:48Z").toEpochMilli()
        var now = closeAt - 60_000L
        val store = InMemoryAwayDigestStore()
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setAwayDigestTriggerMode(AwayDigestTriggerMode.ON_APP_REOPEN)

        // Post 901 published at 10:15:55Z, whose HTML <time> was formatted at minute precision "2026-11-22 18:15" (10:15:00Z)!
        val minutePrecisionFreshTopic = buildTopic(
            id = 901L,
            title = "刚发的闪购补货神车",
            slug = "info",
            replies = 18,
            views = 600,
            iso = "2026-11-22 18:15", // Asia/Shanghai minute-precision without seconds
            drumsticks = 6,
        )
        val preCloseOldTopic = buildTopic(
            id = 902L,
            title = "半小时前旧帖",
            slug = "daily",
            replies = 5,
            views = 120,
            iso = "2026-11-22T09:40:00Z",
        )

        val forum = FakeDigestForumRepository(
            pagesByNumber = mapOf(1 to listOf(minutePrecisionFreshTopic, preCloseOldTopic)),
            drumsticksByTopicId = mapOf(901L to 6),
        )

        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )

        // 1. First-ever install launch: no prior lastActiveTimestampMillis -> does NOT trigger fake away card!
        repo.onAppForegrounded(now)
        assertNull(repo.state.value.activeSession)
        assertFalse(repo.state.value.isVisible)

        // 2. Warm background switch (Home -> WeChat -> back after 2 hours WITHOUT closing app): does NOT trigger ON_APP_REOPEN!
        now = closeAt - 2L * 3_600_000L
        repo.onAppBackgrounded(now)
        now = closeAt
        repo.onAppForegrounded(now)
        assertNull(repo.state.value.activeSession)
        assertFalse(repo.state.value.isVisible)

        // 3. Back-exit / MainActivity finish (!isChangingConfigurations) while process stays alive, reopened 5 minutes later!
        repo.onAppBackgrounded(closeAt)
        repo.onAppClosed(closeAt + 20L) // Activity onDestroy after onStop preserves closeAt
        now = closeAt + 5L * 60_000L
        repo.onAppForegrounded(now)

        val reopenDigest = repo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(reopenDigest.isVisible)
        assertEquals("你离开了 5 分钟", reopenDigest.headlineText)
        // Minute-rounded post 901 is captured, while 09:40Z old post 902 is excluded!
        assertEquals(listOf(901L), reopenDigest.items.map { it.topic.id })

        repo.dismissCurrentSession()
        assertFalse(repo.state.value.isVisible)

        // 4. Cold process restart (swipe-kill from Recents) and reopened 35 seconds later!
        val secondCloseAt = now + 60_000L
        repo.onAppBackgrounded(secondCloseAt)
        now = secondCloseAt + 35_000L // < 1 min!
        val subMinuteTopic = buildTopic(
            id = 903L,
            title = "秒出特价小鸡",
            slug = "trade",
            replies = 9,
            views = 320,
            iso = Instant.ofEpochMilli(secondCloseAt + 10_000L).toString(),
            drumsticks = 3,
        )
        val forum2 = FakeDigestForumRepository(
            pagesByNumber = mapOf(1 to listOf(subMinuteTopic)),
            drumsticksByTopicId = mapOf(903L to 3),
        )
        val coldRestartRepo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum2,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )
        coldRestartRepo.onAppForegrounded(now)
        val coldReopenDigest = coldRestartRepo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(coldReopenDigest.isVisible)
        assertEquals("你离开了不到 1 分钟", coldReopenDigest.headlineText)
        assertEquals(listOf(903L), coldReopenDigest.items.map { it.topic.id })
    }

    @Test
    fun `DURATION_OR_REOPEN triggers on either short app close+reopen or warm background exceeding custom duration`() = runTest {
        val baseTime = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
        var now = baseTime
        val store = InMemoryAwayDigestStore()
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setAwayDigestTriggerMode(AwayDigestTriggerMode.DURATION_OR_REOPEN)
        settingsRepo.setAwayDigestThresholdMinutes(120) // 2 hours

        val forum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to listOf(
                    buildTopic(
                        id = 701L,
                        title = "晚高峰直连测试报告",
                        slug = "review",
                        replies = 25,
                        views = 980,
                        iso = Instant.ofEpochMilli(baseTime + 3L * 3_600_000L).toString(), // future within test window
                        drumsticks = 8,
                    ),
                ),
            ),
            drumsticksByTopicId = mapOf(701L to 8),
        )
        // Use timestamp-less fallback topic or dynamically matching timestamps
        val dynamicForum = object : ForumRepository by forum {
            override suspend fun getFeed(
                categorySlug: String,
                sort: FeedSort,
                page: Int,
                includeBlocked: Boolean,
                forceRefresh: Boolean,
            ): Result<FeedPageResult> = Result.success(
                FeedPageResult(
                    topics = listOf(
                        buildTopic(
                            id = 701L,
                            title = "实时未读精选帖",
                            slug = "review",
                            replies = 25,
                            views = 980,
                            iso = Instant.ofEpochMilli(now - 60_000L).toString(),
                            drumsticks = 8,
                        ),
                    ),
                    currentPage = 1,
                    hasNextPage = false,
                ),
            )

            override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
                Result.failure(IOException("skip detail"))
        }

        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = dynamicForum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )

        repo.onAppForegrounded(now)
        assertFalse(repo.state.value.isVisible)

        // 1. Short warm switch (15m < 2h) without closing app -> does NOT trigger
        repo.onAppBackgrounded(now)
        now += 15L * 60_000L
        repo.onAppForegrounded(now)
        assertFalse(repo.state.value.isVisible)

        // 2. Short close + reopen (6m < 2h, via onAppClosed) -> TRIGGERS!
        repo.onAppClosed(now)
        now += 6L * 60_000L
        repo.onAppForegrounded(now)
        val afterShortClose = repo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(afterShortClose.isVisible)
        assertEquals("你离开了 6 分钟", afterShortClose.headlineText)
        repo.dismissCurrentSession()

        // 3. Long warm background stay (2h 30m >= 2h, without closing app) -> ALSO TRIGGERS!
        repo.onAppBackgrounded(now)
        now += 150L * 60_000L
        repo.onAppForegrounded(now)
        val afterWarmLongStay = repo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(afterWarmLongStay.isVisible)
        assertEquals("你离开了 2 小时 30 分钟", afterWarmLongStay.headlineText)
    }

    @Test
    fun `ancient bumped threads from curated award, latest reply, missing timestamps, and past-boundary pages never leak into digest`() = runTest {
        val awayStart = Instant.parse("2026-11-22T14:00:00Z").toEpochMilli()
        val reopenedAt = awayStart + 2L * 3_600_000L // 16:00Z (2-hour away window)

        val genuineFreshGem = buildTopic(
            id = 625_410L,
            title = "黑五 DMIT 美西直连新品首发",
            slug = "info",
            replies = 26,
            views = 1400,
            iso = "2026-11-22T15:20:00Z",
            drumsticks = 9,
        )
        // Borderline pre-window thread bumped during away window (created 3h before awayStart, replied at 15:40Z)
        val preWindowBumpedNearBoundary = buildTopic(
            id = 625_392L,
            title = "午间水贴被顶上来",
            slug = "daily",
            replies = 80,
            views = 3200,
            iso = "2026-11-22T15:40:00Z",
            drumsticks = 12,
        )
        val preWindowMarker1 = buildTopic(
            id = 625_380L,
            title = "离开前旧帖 1",
            slug = "tech",
            replies = 4,
            views = 120,
            iso = "2026-11-22T13:45:00Z",
        )
        val preWindowMarker2 = buildTopic(
            id = 625_379L,
            title = "离开前旧帖 2",
            slug = "tech",
            replies = 2,
            views = 95,
            iso = "2026-11-22T13:30:00Z",
        )
        // Old thread appearing AFTER the chronological boundary on NEWEST_TOPIC that got a recent reply
        val postBoundaryBumpedOldThread = buildTopic(
            id = 624_900L,
            title = "两天前的抽奖帖刚刚有人回复",
            slug = "daily",
            replies = 420,
            views = 18_000,
            iso = "2026-11-22T15:55:00Z",
            drumsticks = 35,
        )
        // 1-year-old curated `/award` classic bumped during the away window + one with missing timestamp
        val ancientCuratedBumped = buildTopic(
            id = 180_000L,
            title = "2025 年度 VPS 线路百科全书",
            slug = "review",
            replies = 680,
            views = 95_000,
            iso = "2026-11-22T15:50:00Z",
            drumsticks = 120,
        )
        val ancientCuratedBlankTime = buildTopic(
            id = 175_000L,
            title = "无时间戳历史精华帖",
            slug = "tech",
            replies = 500,
            views = 80_000,
            iso = "",
            drumsticks = 95,
        )
        // 6-month-old `LATEST_REPLY` thread bumped 2 minutes ago
        val ancientLatestReplyBumped = buildTopic(
            id = 350_000L,
            title = "半年老商家长期顶帖促销",
            slug = "promotion",
            replies = 390,
            views = 42_000,
            iso = "2026-11-22T15:58:00Z",
            drumsticks = 50,
        )

        val baseForum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to listOf(
                    genuineFreshGem,
                    preWindowBumpedNearBoundary,
                    preWindowMarker1,
                    preWindowMarker2,
                    postBoundaryBumpedOldThread,
                ),
            ),
            hasNextUpToPage = 1,
        )

        val realisticForum = object : ForumRepository by baseForum {
            override fun getCachedFeed(categorySlug: String, sort: FeedSort): FeedPageResult? {
                if (sort == FeedSort.LATEST_REPLY) {
                    return FeedPageResult(
                        topics = listOf(ancientLatestReplyBumped, genuineFreshGem.copy(viewCount = 1900)),
                        currentPage = 1,
                        hasNextPage = true,
                    )
                }
                return null
            }

            override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            ancientCuratedBumped,
                            ancientCuratedBlankTime,
                            genuineFreshGem.copy(drumstickCount = 15),
                        ),
                        currentPage = 1,
                        hasNextPage = true,
                    ),
                )

            override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> {
                val createdIso = when (topicId) {
                    625_410L -> "2026-11-22T15:20:00Z" // Genuinely created in away window
                    625_392L -> "2026-11-22T11:00:00Z" // Created 3h BEFORE awayStart -> must be rejected!
                    else -> return Result.failure(IOException("detail timeout on old post"))
                }
                val match = listOf(genuineFreshGem, preWindowBumpedNearBoundary).first { it.id == topicId }
                return Result.success(
                    TopicDetailPage(
                        topicId = topicId,
                        title = match.title,
                        categorySlug = match.categorySlug,
                        categoryName = match.categoryName,
                        author = FloorAuthor(uid = 1L, username = match.authorName),
                        viewCount = match.viewCount,
                        currentPage = 1,
                        totalPages = 1,
                        isCuratedAward = topicId == 625_410L,
                        openingFloor = FloorComment(
                            commentId = topicId * 10L,
                            floorNumber = 0,
                            author = FloorAuthor(uid = 1L, username = match.authorName, isTopicAuthor = true),
                            createdAtIso = createdIso,
                            markdownBody = match.title,
                            drumstickCount = match.drumstickCount,
                        ),
                        replies = emptyList(),
                        createdAtIso = createdIso,
                    ),
                )
            }
        }

        val store = InMemoryAwayDigestStore(initialLastActiveTimestampMillis = awayStart)
        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = realisticForum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { reopenedAt },
        )

        repo.onAppForegrounded(reopenedAt)
        val digest = repo.state.first { !it.isLoading }

        // Only the genuinely in-window topic 625_410 survives; all ancient/pre-window threads are blocked!
        assertEquals(listOf(625_410L), digest.items.map { it.topic.id })
        val item = digest.items.single()
        assertTrue(item.isCuratedAward)
        assertEquals(15, item.drumstickCount) // Enriched from `/award` without admitting `/award` old posts
        assertEquals(1900, item.viewCount) // Enriched from `LATEST_REPLY` without admitting old bumped posts

        // Blank timestamp topics are strictly rejected by `isWithinAwayWindow`
        assertFalse(
            AwayDigestEngine.isWithinAwayWindow(
                topic = ancientCuratedBlankTime,
                enrichedMeta = null,
                awayStartMillis = awayStart,
                reopenedAtMillis = reopenedAt,
            ),
        )
        // Pre-existing topics (<36h old) bumped during away window are also rejected once `createdAtIso` is known
        assertFalse(
            AwayDigestEngine.isWithinAwayWindow(
                topic = preWindowBumpedNearBoundary,
                enrichedMeta = CachedTopicDrumstickMeta(
                    topicId = 625_392L,
                    createdAtIso = "2026-11-22T11:00:00Z",
                ),
                awayStartMillis = awayStart,
                reopenedAtMillis = reopenedAt,
            ),
        )
    }

    @Test
    fun `stale undismissed away session expires automatically after TTL and parser extracts time title fallback`() = runTest {
        val baseTime = Instant.parse("2026-11-22T08:00:00Z").toEpochMilli()
        var now = baseTime
        val settingsRepo = FakeSettingsRepository()
        settingsRepo.setAwayDigestTriggerMode(AwayDigestTriggerMode.CUSTOM_DURATION)
        settingsRepo.setAwayDigestThresholdMinutes(240) // 4-hour threshold

        val store = InMemoryAwayDigestStore(initialLastActiveTimestampMillis = baseTime)
        val forum = FakeDigestForumRepository(
            pagesByNumber = mapOf(
                1 to listOf(
                    buildTopic(
                        id = 501L,
                        title = "昨日离开期间好帖",
                        slug = "tech",
                        replies = 20,
                        views = 800,
                        iso = Instant.ofEpochMilli(baseTime + 2L * 3_600_000L).toString(),
                        drumsticks = 6,
                    ),
                ),
            ),
            drumsticksByTopicId = mapOf(501L to 6),
        )
        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = settingsRepo,
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )

        // User reopens after 5 hours (>= 4h threshold) and does NOT dismiss the card
        now = baseTime + 5L * 3_600_000L
        repo.onAppForegrounded(now)
        val activeDigest = repo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertTrue(activeDigest.isVisible)

        // User uses app periodically (< 4h gaps) until next day (> 3h stale session TTL since reopenedAt)
        repo.onAppBackgrounded(now + 2L * 3_600_000L)
        now += 4L * 3_600_000L // 2h away (< 4h threshold), but 4h since original session reopenedAt (> 3h TTL)
        repo.onAppForegrounded(now)

        // Yesterday's undismissed session has expired instead of continuing to show old posts!
        assertFalse(repo.state.value.isVisible)
        assertTrue(store.getActiveSession()?.isDismissed == true)

        // Verify HTML parser falls back to `<time title="...">` when `datetime` attribute is absent
        val configB64 = Base64.getEncoder().encodeToString(
            """{"allCategory":[{"key":"tech","cn_text":"技术"}]}""".toByteArray(Charsets.UTF_8),
        )
        val htmlWithTitleTime = """
            <html><body>
              <div id="nsk-body">
                <div class="post-list-item">
                  <div class="post-title"><a href="/post-888-1">标题属性时间测试</a></div>
                  <a class="post-category" href="/categories/tech">技术</a>
                  <time title="2026-11-23 16:20:00">5分钟前</time>
                </div>
              </div>
              <script id="temp-script" type="application/json">$configB64</script>
            </body></html>
        """.trimIndent()
        val parsed = NodeSeekHtmlParser.parseFeedPage(htmlWithTitleTime, "https://www.nodeseek.com/")
        assertEquals("2026-11-23 16:20:00", parsed.topics.single().activityDate)
    }

    @Test
    fun `failed away digest scan resets loadedSessionId so non-force refresh can retry`() = runTest {
        val baseTime = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()
        var now = baseTime
        val store = InMemoryAwayDigestStore()
        store.setLastActiveTimestampMillis(baseTime)
        now += 4L * 3_600_000L

        var shouldFail = true
        val topicInWindow = buildTopic(
            id = 505L,
            title = "网络恢复后应可见的热帖",
            slug = "tech",
            replies = 20,
            views = 600,
            iso = Instant.ofEpochMilli(baseTime + 2L * 3_600_000L).toString(),
            drumsticks = 5,
        )
        val baseForum = FakeDigestForumRepository(pagesByNumber = mapOf(1 to listOf(topicInWindow)))
        val flakyForum = object : ForumRepository by baseForum {
            override suspend fun getFeed(
                categorySlug: String,
                sort: FeedSort,
                page: Int,
                includeBlocked: Boolean,
                forceRefresh: Boolean,
            ): Result<FeedPageResult> {
                if (shouldFail) {
                    return Result.failure(IOException("transient network failure"))
                }
                return baseForum.getFeed(categorySlug, sort, page, includeBlocked, forceRefresh)
            }
        }

        val repo = AwayDigestRepositoryImpl(
            store = store,
            forumRepository = flakyForum,
            bookmarkAndHistoryRepository = FakeDigestHistoryRepository(),
            sessionRepository = FakeDigestSessionRepository(),
            settingsRepository = FakeSettingsRepository(),
            contentFilterRepository = FakeDigestFilterRepository(),
            scope = backgroundScope,
            clockMillis = { now },
        )

        repo.onAppForegrounded(now)
        val failedState = repo.state.first { !it.isLoading && it.errorMessage != null }
        assertTrue(failedState.items.isEmpty())

        // Network recovers: non-force refreshCurrentSessionDigest(forceReload = false) MUST retry!
        shouldFail = false
        repo.refreshCurrentSessionDigest(forceReload = false)
        val recoveredState = repo.state.first { !it.isLoading && it.items.isNotEmpty() }
        assertEquals(listOf(505L), recoveredState.items.map { it.topic.id })
    }

    private fun buildTopic(
        id: Long,
        title: String,
        slug: String,
        replies: Int,
        views: Int,
        iso: String,
        drumsticks: Int = 0,
    ) = TopicSummary(
        id = id,
        title = title,
        categorySlug = slug,
        categoryName = Category.DEFAULT_CATEGORIES.firstOrNull { it.slug == slug }?.name ?: slug,
        authorId = 1000L + id,
        authorName = "author_$id",
        viewCount = views,
        replyCount = replies,
        lastActivityIso = iso,
        drumstickCount = drumsticks,
    )
}

private class FakeDigestForumRepository(
    private val pagesByNumber: Map<Int, List<TopicSummary>>,
    private val hasNextUpToPage: Int = pagesByNumber.keys.maxOrNull() ?: 1,
    private val drumsticksByTopicId: Map<Long, Int> = emptyMap(),
    private val failDetailRequests: Boolean = false,
) : ForumRepository {

    val requestedFeedPages = mutableListOf<Int>()

    override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)

    override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
        Result.success(Category.DEFAULT_CATEGORIES)

    override suspend fun getFeed(
        categorySlug: String,
        sort: FeedSort,
        page: Int,
        includeBlocked: Boolean,
        forceRefresh: Boolean,
    ): Result<FeedPageResult> {
        if (sort == FeedSort.NEWEST_TOPIC) {
            requestedFeedPages.add(page)
        }
        val topics = pagesByNumber[page].orEmpty()
        return Result.success(
            FeedPageResult(
                topics = topics,
                currentPage = page,
                hasNextPage = page < hasNextUpToPage,
                isFromRssFallback = false,
            ),
        )
    }

    override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
        Result.success(FeedPageResult(topics = emptyList(), currentPage = page, hasNextPage = false))

    override suspend fun searchTopics(
        query: String,
        categorySlug: String,
        sort: FeedSort,
        page: Int,
    ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList()))

    override suspend fun getTopicDetail(
        topicId: Long,
        page: Int,
        includeBlocked: Boolean,
    ): Result<TopicDetailPage> {
        if (failDetailRequests) {
            return Result.failure(IOException("offline"))
        }
        val match = pagesByNumber.values.flatten().firstOrNull { it.id == topicId }
            ?: return Result.failure(IOException("not found"))
        val drumsticks = drumsticksByTopicId[topicId] ?: match.drumstickCount
        return Result.success(
            TopicDetailPage(
                topicId = topicId,
                title = match.title,
                categorySlug = match.categorySlug,
                categoryName = match.categoryName,
                author = FloorAuthor(uid = match.authorId ?: 1L, username = match.authorName),
                viewCount = match.viewCount,
                currentPage = 1,
                totalPages = 1,
                openingFloor = FloorComment(
                    commentId = topicId * 10L,
                    floorNumber = 0,
                    author = FloorAuthor(uid = match.authorId ?: 1L, username = match.authorName, isTopicAuthor = true),
                    createdAtIso = match.lastActivityIso,
                    markdownBody = match.title,
                    drumstickCount = drumsticks,
                ),
                replies = emptyList(),
                createdAtIso = match.lastActivityIso,
            ),
        )
    }

    override suspend fun createTopic(
        categorySlug: String,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun editTopic(
        topicId: Long,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
    override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
    override suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun getFreeDrumstickCost(): Result<Int> = Result.success(0)
    override suspend fun reactToComment(
        topicId: Long,
        commentId: Long,
        reaction: ReactionType,
    ): Result<ReactionResult> = Result.success(ReactionResult(updatedCount = 1))
}

private class FakeDigestHistoryRepository(
    initialReadIds: Set<Long> = emptySet(),
) : BookmarkAndHistoryRepository {

    private val historyFlow = MutableStateFlow(
        initialReadIds.map { id ->
            ReadHistoryEntry(
                topicId = id,
                title = "Topic #$id",
                categoryName = "技术",
                authorName = "author",
                authorAvatarUrl = "",
                lastReadPage = 1,
                lastReadFloor = 1,
                visitedAtEpochMillis = 1_700_000_000_000L,
            )
        },
    )

    override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
        Result.success(emptyList<TopicSummary>() to false)

    override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
    override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
    override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = historyFlow.asStateFlow()

    override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> =
        historyFlow.map { list ->
            val filterSet = topicIds.toSet()
            list.filter { it.topicId in filterSet }.associateBy { it.topicId }
        }

    override suspend fun recordTopicVisit(entry: ReadHistoryEntry) {
        historyFlow.update { current ->
            current.filterNot { it.topicId == entry.topicId } + entry
        }
    }

    override suspend fun clearReadHistory() {
        historyFlow.value = emptyList()
    }
}

private class FakeDigestSessionRepository : SessionRepository {
    override val sessionState: StateFlow<AccountSession?> = MutableStateFlow(
        AccountSession(uid = 42L, username = "nodeseeker"),
    ).asStateFlow()
    override val challengeRequired: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val authVerifiedEpoch: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

    override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionState.value)
    override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
        Result.success(SignInStepResult.Authenticated)
    override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
    override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
    override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
    override suspend fun signOut(): Result<Unit> = Result.success(Unit)
}

private class FakeDigestFilterRepository(
    initial: ContentFilterConfig = ContentFilterConfig(),
) : ContentFilterRepository {
    private val state = MutableStateFlow(initial)
    override val config: StateFlow<ContentFilterConfig> = state.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        state.update { it.copy(isEnabled = enabled) }
    }

    override suspend fun upsertRule(rule: ContentBlockRule) {
        state.update { ContentFilterEngine.upsertRule(it, rule) }
    }

    override suspend fun removeRule(ruleId: String) {
        state.update { ContentFilterEngine.removeRule(it, ruleId) }
    }

    override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) = Unit
    override suspend fun toggleMutedCategory(categorySlug: String) = Unit
    override suspend fun setMutedCategories(categorySlugs: Set<String>) = Unit
    override suspend fun replaceConfig(config: ContentFilterConfig) {
        state.value = config
    }
    override suspend fun mergeConfig(config: ContentFilterConfig) = Unit
}
