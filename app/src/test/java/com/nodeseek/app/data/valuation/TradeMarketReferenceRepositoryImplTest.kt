package com.nodeseek.app.data.valuation

import com.nodeseek.app.data.local.CachedFeedSnapshot
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class TradeMarketReferenceRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeMarketForumRepository(
        private val localTopicsProvider: suspend (String) -> List<TopicSummary> = { emptyList() },
        private val searchHandler: suspend (String, String) -> Result<FeedPageResult> = { _, _ ->
            Result.success(FeedPageResult(topics = emptyList()))
        },
        private val searchHandlerWithPage: (suspend (String, String, Int) -> Result<FeedPageResult>)? = null,
        private val feedHandler: suspend (String, FeedSort, Int) -> Result<FeedPageResult> = { _, _, _ ->
            Result.success(FeedPageResult(topics = emptyList()))
        },
    ) : ForumRepository {
        var searchCallCount = 0
        val recordedSearchCalls = mutableListOf<Triple<String, String, Int>>()
        val recordedFeedCalls = mutableListOf<Triple<String, FeedSort, Int>>()

        override suspend fun getLocalCachedTopics(categorySlug: String): List<TopicSummary> =
            localTopicsProvider(categorySlug)

        override suspend fun searchTopics(
            query: String,
            categorySlug: String,
            sort: FeedSort,
            page: Int,
        ): Result<FeedPageResult> {
            searchCallCount++
            recordedSearchCalls += Triple(query, categorySlug, page)
            return searchHandlerWithPage?.invoke(query, categorySlug, page)
                ?: searchHandler(query, categorySlug)
        }

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
            recordedFeedCalls += Triple(categorySlug, sort, page)
            return feedHandler(categorySlug, sort, page)
        }

        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
            Result.failure(UnsupportedOperationException())

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
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType): Result<ReactionResult> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeMarketBookmarkRepository(
        bookmarks: List<BookmarkedTopic> = emptyList(),
        history: List<ReadHistoryEntry> = emptyList(),
    ) : BookmarkAndHistoryRepository {
        private val bookmarksFlow = MutableStateFlow(bookmarks)
        private val historyFlow = MutableStateFlow(history)
        override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = bookmarksFlow
        override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = historyFlow
        override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            Result.success(emptyList<TopicSummary>() to false)

        override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> =
            flowOf(emptyMap())

        override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
        override suspend fun clearReadHistory() = Unit
    }

    @Test
    fun `FeedDiskCache getSnapshotsForCategory reads disk snapshots cold-start without network`() = runTest {
        val dir = tempFolder.newFolder("feed_cache")
        val writerCache = FeedDiskCache(cacheDir = dir)
        writerCache.persistNowForTest(
            accountUid = 0L,
            categorySlug = "trade",
            sort = FeedSort.LATEST_REPLY,
            snapshot = CachedFeedSnapshot(
                fetchedAtEpochMillis = 1_000L,
                result = FeedPageResult(
                    topics = listOf(
                        TopicSummary(
                            id = 101L,
                            title = "[出] DMIT Pro 溢价 +50",
                            categorySlug = "trade",
                            categoryName = "交易",
                            authorName = "seller1",
                        ),
                    ),
                ),
            ),
        )
        writerCache.persistNowForTest(
            accountUid = 42L,
            categorySlug = "",
            sort = FeedSort.LATEST_REPLY,
            snapshot = CachedFeedSnapshot(
                fetchedAtEpochMillis = 2_000L,
                result = FeedPageResult(
                    topics = listOf(
                        TopicSummary(
                            id = 102L,
                            title = "[出] 大妈 LAX.Pro.Tiny 溢价 +70",
                            categorySlug = "trade",
                            categoryName = "交易",
                            authorName = "seller2",
                        ),
                    ),
                ),
            ),
        )

        val coldReader = FeedDiskCache(cacheDir = dir)
        val snapshots = coldReader.getSnapshotsForCategory("trade")
        val topicIds = snapshots.flatMap { it.result.topics }.map { it.id }.toSet()
        assertTrue(101L in topicIds)
        assertTrue(102L in topicIds)

        // Exact slug segment matching: "trade_foo" must NOT match query for "trade"
        writerCache.persistNowForTest(
            accountUid = 0L,
            categorySlug = "trade_foo",
            sort = FeedSort.NEWEST_TOPIC,
            snapshot = CachedFeedSnapshot(
                fetchedAtEpochMillis = 3_000L,
                result = FeedPageResult(
                    topics = listOf(
                        TopicSummary(
                            id = 999L,
                            title = "Wrong slug",
                            categorySlug = "trade_foo",
                            categoryName = "Other",
                            authorName = "other",
                        ),
                    ),
                ),
            ),
        )
        val isolatedSnapshots = coldReader.getSnapshotsForCategory("trade")
        val isolatedIds = isolatedSnapshots.flatMap { it.result.topics }.map { it.id }.toSet()
        assertFalse(999L in isolatedIds)
    }

    @Test
    fun `analyzeLocal combines feed cache bookmarks and history with zero network calls`() = runTest {
        val forum = FakeMarketForumRepository(
            localTopicsProvider = {
                listOf(
                    TopicSummary(1L, "[出] DMIT Pro 溢价 +40", "trade", "交易", authorName = "a"),
                )
            },
            searchHandler = { _, _ -> throw AssertionError("analyzeLocal must NOT hit searchTopics!") },
        )
        val bookmarks = FakeMarketBookmarkRepository(
            bookmarks = listOf(
                BookmarkedTopic(
                    topicId = 2L,
                    summary = TopicSummary(2L, "[出] 大妈 Pro.Wee 溢价 +80", "", "", authorName = "b"),
                ),
                // Non-trade bookmark must be ignored
                BookmarkedTopic(
                    topicId = 99L,
                    summary = TopicSummary(99L, "[曝光] DMIT Pro 溢价 +999", "expose", "曝光", authorName = "x"),
                ),
            ),
            history = listOf(
                ReadHistoryEntry(
                    topicId = 3L,
                    title = "[出] DMIT Pro.Tiny 平出",
                    categoryName = "交易",
                    authorName = "c",
                    categorySlug = "trade",
                ),
            ),
        )

        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = bookmarks,
        )
        val state = repo.analyzeLocal("DMIT Pro")
        assertEquals(0, forum.searchCallCount)
        assertFalse(state.hasRemoteResults)
        assertEquals(3, state.distribution?.sampleCount)
        assertEquals(BigDecimal("40.00"), state.distribution?.medianCny)
    }

    @Test
    fun `searchRemoteAndMerge merges caches respects 15m TTL 10s interval and coalesces concurrent calls`() = runTest {
        var nowMs = 100_000L
        val gate = CompletableDeferred<Unit>()
        val forum = FakeMarketForumRepository(
            localTopicsProvider = {
                listOf(TopicSummary(1L, "[出] DMIT Pro 溢价 +40", "trade", "交易", authorName = "local"))
            },
            searchHandler = { query, cat ->
                assertEquals("trade", cat)
                if (query == "DMIT Pro") {
                    gate.await()
                }
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            TopicSummary(1L, "[出] DMIT Pro 溢价 +40", "trade", "交易", authorName = "local"),
                            TopicSummary(2L, "[出] DMIT Pro 溢价 +100", "trade", "交易", authorName = "remote"),
                        ),
                    ),
                )
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
        )

        // Concurrent calls coalesce via single-flight
        val job1 = async { repo.searchRemoteAndMerge("DMIT Pro") }
        val job2 = async { repo.searchRemoteAndMerge("DMIT Pro") }
        gate.complete(Unit)
        val res1 = job1.await()
        val res2 = job2.await()
        assertEquals(1, forum.searchCallCount)
        assertEquals(MarketSearchStatus.SUCCESS, res1.searchStatus)
        assertEquals(2, res1.distribution?.sampleCount)
        assertEquals(2, res2.distribution?.sampleCount)
        assertEquals(1, res1.distribution?.localSampleCount)
        assertEquals(1, res1.distribution?.remoteSampleCount)

        // Second call within 15m TTL hits cache (no extra network call)
        nowMs += 30_000L
        val cachedRes = repo.searchRemoteAndMerge("DMIT Pro")
        assertEquals(1, forum.searchCallCount)
        assertTrue(cachedRes.hasRemoteResults)

        // Different query within 10s min-interval triggers COOLDOWN
        val secondAttempt = repo.searchRemoteAndMerge("瓦工 MEGABOX")
        assertEquals(MarketSearchStatus.SUCCESS, secondAttempt.searchStatus)
        assertEquals(2, forum.searchCallCount)

        nowMs += 3_000L // only 3s elapsed (< 10s min-interval)
        val throttled = repo.searchRemoteAndMerge("RackNerd")
        assertEquals(MarketSearchStatus.COOLDOWN, throttled.searchStatus)
        assertTrue(throttled.cooldownRemainingSeconds in 1..10)
        assertEquals(2, forum.searchCallCount)
    }

    @Test
    fun `searchRemoteAndMerge handles HTTP 429 Retry-After and exponential backoff while preserving local stats`() = runTest {
        var nowMs = 1_000_000L
        var nextError: Throwable = NodeSeekApiException("请求过于频繁", statusCode = 429, retryAfterSeconds = 45)
        val forum = FakeMarketForumRepository(
            localTopicsProvider = {
                listOf(TopicSummary(1L, "[出] DMIT Pro 溢价 +50", "trade", "交易", authorName = "local"))
            },
            searchHandler = { _, _ -> Result.failure(nextError) },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
        )

        // 1. HTTP 429 with Retry-After = 45s
        val first429 = repo.searchRemoteAndMerge("DMIT Pro")
        assertEquals(MarketSearchStatus.RATE_LIMITED, first429.searchStatus)
        assertEquals(45, first429.cooldownRemainingSeconds)
        assertNotNull(first429.distribution)
        assertEquals(1, first429.distribution?.sampleCount)

        // Calling again during cooldown does not hit network
        nowMs += 10_000L
        val duringCooldown = repo.searchRemoteAndMerge("DMIT Pro")
        assertEquals(MarketSearchStatus.COOLDOWN, duringCooldown.searchStatus)
        assertEquals(1, forum.searchCallCount)

        // 2. After cooldown expires, second 429 without Retry-After uses exponential backoff (30s * 2^1 = 60s)
        nowMs += 40_000L
        nextError = NodeSeekApiException("限流", statusCode = 429, retryAfterSeconds = null)
        val second429 = repo.searchRemoteAndMerge("DMIT Pro")
        assertEquals(MarketSearchStatus.RATE_LIMITED, second429.searchStatus)
        assertEquals(60, second429.cooldownRemainingSeconds)
        assertEquals(2, forum.searchCallCount)

        // 3. Third 429 strike with small Retry-After=5s must still enforce exponential floor (30s * 2^2 = 120s)
        nowMs += 65_000L
        nextError = NodeSeekApiException("限流", statusCode = 429, retryAfterSeconds = 5)
        val third429 = repo.searchRemoteAndMerge("DMIT Pro")
        assertEquals(MarketSearchStatus.RATE_LIMITED, third429.searchStatus)
        assertEquals(120, third429.cooldownRemainingSeconds)
        assertEquals(3, forum.searchCallCount)
    }

    @Test
    fun `sanitizeRemoteSearchQuery cleans hostile chars while keeping plus and brand dots and retries core query on 404`() = runTest {
        assertEquals(
            "DMIT +50",
            TradeMarketReferenceRepositoryImpl.sanitizeRemoteSearchQuery("DMIT +50"),
        )
        assertEquals(
            "DMIT LAX Pro WEE CN2 GIA 36.9 yr",
            TradeMarketReferenceRepositoryImpl.sanitizeRemoteSearchQuery("[出] DMIT/LAX.Pro.WEE (CN2:GIA) $36.9/yr"),
        )
        assertEquals(
            "V.PS Tokyo Softbank",
            TradeMarketReferenceRepositoryImpl.sanitizeRemoteSearchQuery("V.PS Tokyo Softbank"),
        )
        assertEquals(
            "Netcup RS1000 G9.5",
            TradeMarketReferenceRepositoryImpl.sanitizeRemoteSearchQuery("Netcup RS1000 G9.5"),
        )

        var nowMs = 500_000L
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { query, _, _ ->
                if (query.equals("DMIT LAX Pro WEE", ignoreCase = true)) {
                    Result.failure(NodeSeekApiException("官网请求失败 (404)", statusCode = 404))
                } else if (query.equals("dmit wee", ignoreCase = true)) {
                    Result.success(
                        FeedPageResult(
                            topics = listOf(
                                TopicSummary(301L, "[出] DMIT LAX.Pro.WEE 溢价 +70", "trade", "交易", authorName = "s1"),
                                TopicSummary(302L, "[出] DMIT LAX.Pro.Wee 溢价 +90", "trade", "交易", authorName = "s2"),
                            ),
                        ),
                    )
                } else {
                    Result.failure(NodeSeekApiException("官网请求失败 (404)", statusCode = 404))
                }
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
        )

        val state = repo.searchRemoteAndMerge("[出] DMIT/LAX.Pro.WEE")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertTrue(state.hasRemoteResults)
        assertEquals(2, state.distribution?.sampleCount)
        assertEquals(BigDecimal("80.00"), state.distribution?.medianCny)
        assertEquals(
            listOf("DMIT LAX Pro WEE", "dmit wee"),
            forum.recordedSearchCalls.map { it.first },
        )
    }

    @Test
    fun `searchRemoteAndMerge treats page-2 HTTP 404 as end of pagination and preserves page-1 samples`() = runTest {
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, page ->
                if (page == 1) {
                    Result.success(
                        FeedPageResult(
                            topics = listOf(
                                TopicSummary(401L, "[出] DMIT Pro 溢价 +60", "trade", "交易", authorName = "p1"),
                            ),
                            currentPage = 1,
                            hasNextPage = true,
                        ),
                    )
                } else {
                    Result.failure(NodeSeekApiException("官网请求失败 (404)", statusCode = 404))
                }
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { 200_000L },
        )

        val state = repo.searchRemoteAndMerge("DMIT Pro")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertTrue(state.hasRemoteResults)
        assertEquals(1, state.distribution?.sampleCount)
        assertEquals(BigDecimal("60.00"), state.distribution?.medianCny)
        assertEquals(listOf(1, 2), forum.recordedSearchCalls.map { it.third })
    }

    @Test
    fun `searchRemoteAndMerge falls back to recent trade board pages on HTTP 404 and shares board snapshot locally`() = runTest {
        var nowMs = 300_000L
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ ->
                Result.failure(NodeSeekApiException("官网请求失败 (404)", statusCode = 404))
            },
            feedHandler = { slug, sort, page ->
                assertEquals("trade", slug)
                assertEquals(FeedSort.NEWEST_TOPIC, sort)
                when (page) {
                    1 -> Result.success(
                        FeedPageResult(
                            topics = listOf(
                                TopicSummary(501L, "[出] DMIT Pro 溢价 +50", "trade", "交易", authorName = "u1"),
                            ),
                            currentPage = 1,
                            hasNextPage = true,
                        ),
                    )
                    2 -> Result.success(
                        FeedPageResult(
                            topics = listOf(
                                TopicSummary(502L, "[出] DMIT Pro 溢价 +90", "trade", "交易", authorName = "u2"),
                                TopicSummary(503L, "[出] BWG MEGABOX 溢价 +110", "trade", "交易", authorName = "u3"),
                            ),
                            currentPage = 2,
                            hasNextPage = true,
                        ),
                    )
                    else -> Result.failure(NodeSeekApiException("官网请求失败 (404)", statusCode = 404))
                }
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
        )

        val state = repo.searchRemoteAndMerge("DMIT Pro")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertTrue(state.hasRemoteResults)
        assertEquals(2, state.distribution?.sampleCount)
        assertEquals(BigDecimal("70.00"), state.distribution?.medianCny)
        assertFalse(state.statusMessage.orEmpty().contains("官网请求失败 (404)"))
        assertEquals(listOf(1, 2, 3), forum.recordedFeedCalls.map { it.third })

        // Subsequent analyzeLocal for another model reuses scanned trade board snapshot with zero extra calls
        val beforeCalls = forum.searchCallCount + forum.recordedFeedCalls.size
        val bwgLocal = repo.analyzeLocal("瓦工 MEGABOX")
        assertEquals(beforeCalls, forum.searchCallCount + forum.recordedFeedCalls.size)
        assertEquals(1, bwgLocal.distribution?.sampleCount)
        assertEquals(BigDecimal("110.00"), bwgLocal.distribution?.medianCny)
    }

    @Test
    fun `searchRemoteAndMerge retries simplified query on zero hits and does not poison cache on 404 or empty results`() = runTest {
        var nowMs = 1_000_000L
        var rackNerdShould404 = true
        var greenCloudEmpty = true
        var greenCloudFailRefresh = false

        val forum = FakeMarketForumRepository(
            localTopicsProvider = {
                listOf(TopicSummary(601L, "[出] RackNerd 溢价 +20", "trade", "交易", authorName = "local"))
            },
            searchHandlerWithPage = { query, _, _ ->
                when {
                    query.equals("瓦工 MEGABOX", ignoreCase = true) ->
                        Result.success(FeedPageResult(topics = emptyList()))
                    query.equals("megabox", ignoreCase = true) ->
                        Result.success(
                            FeedPageResult(
                                topics = listOf(
                                    TopicSummary(610L, "[出] BWG MegaBox 溢价 +80", "trade", "交易", authorName = "bwg"),
                                ),
                            ),
                        )
                    query.equals("RackNerd", ignoreCase = true) -> {
                        if (rackNerdShould404) {
                            Result.failure(NodeSeekApiException("官网请求失败 (404)", statusCode = 404))
                        } else {
                            Result.success(
                                FeedPageResult(
                                    topics = listOf(
                                        TopicSummary(602L, "[出] RackNerd 溢价 +40", "trade", "交易", authorName = "rn"),
                                    ),
                                ),
                            )
                        }
                    }
                    query.equals("GreenCloud", ignoreCase = true) -> {
                        when {
                            greenCloudFailRefresh -> Result.failure(NodeSeekApiException("服务繁忙", statusCode = 500))
                            greenCloudEmpty -> Result.success(FeedPageResult(topics = emptyList()))
                            else -> Result.success(
                                FeedPageResult(
                                    topics = listOf(
                                        TopicSummary(620L, "[出] GreenCloud 溢价 +30", "trade", "交易", authorName = "gc"),
                                    ),
                                ),
                            )
                        }
                    }
                    else -> Result.success(FeedPageResult(topics = emptyList()))
                }
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
        )

        // 1. Zero-hit primary query retries simplified alias-neutral model token ("megabox")
        val bwg = repo.searchRemoteAndMerge("瓦工 MEGABOX")
        assertEquals(MarketSearchStatus.SUCCESS, bwg.searchStatus)
        assertEquals(1, bwg.distribution?.sampleCount)
        assertEquals(BigDecimal("80.00"), bwg.distribution?.medianCny)

        // 2. Transient 404 with empty board fallback preserves local sample and does NOT poison cache
        nowMs += 15_000L
        val rn404 = repo.searchRemoteAndMerge("RackNerd")
        assertEquals(MarketSearchStatus.FAILED, rn404.searchStatus)
        assertEquals(1, rn404.distribution?.sampleCount)
        assertEquals(BigDecimal("20.00"), rn404.distribution?.medianCny)

        nowMs += 15_000L
        rackNerdShould404 = false
        val rnRecovered = repo.searchRemoteAndMerge("RackNerd")
        assertEquals(MarketSearchStatus.SUCCESS, rnRecovered.searchStatus)
        assertEquals(2, rnRecovered.distribution?.sampleCount)
        assertEquals(BigDecimal("30.00"), rnRecovered.distribution?.medianCny)

        // 3. 0-hit result does NOT poison 15-minute cache
        nowMs += 15_000L
        val gcEmpty = repo.searchRemoteAndMerge("GreenCloud")
        assertEquals(MarketSearchStatus.SUCCESS, gcEmpty.searchStatus)
        assertEquals(0, gcEmpty.distribution?.sampleCount)

        nowMs += 15_000L
        greenCloudEmpty = false
        val gcLoaded = repo.searchRemoteAndMerge("GreenCloud")
        assertEquals(MarketSearchStatus.SUCCESS, gcLoaded.searchStatus)
        assertEquals(1, gcLoaded.distribution?.sampleCount)
        assertEquals(BigDecimal("30.00"), gcLoaded.distribution?.medianCny)

        // 4. After 15m TTL expires, failed refresh preserves previously fetched remote samples (stale retention)
        nowMs += 16 * 60_000L
        greenCloudFailRefresh = true
        val gcRefreshFailed = repo.searchRemoteAndMerge("GreenCloud")
        assertEquals(MarketSearchStatus.FAILED, gcRefreshFailed.searchStatus)
        assertEquals(1, gcRefreshFailed.distribution?.sampleCount)
        assertEquals(BigDecimal("30.00"), gcRefreshFailed.distribution?.medianCny)
    }
}
