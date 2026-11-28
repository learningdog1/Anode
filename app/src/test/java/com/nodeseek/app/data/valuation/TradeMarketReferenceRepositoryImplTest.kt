package com.nodeseek.app.data.valuation

import com.nodeseek.app.data.local.CachedFeedSnapshot
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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
        private val detailHandler: suspend (Long, Int) -> Result<TopicDetailPage> = { _, _ ->
            Result.failure(UnsupportedOperationException())
        },
    ) : ForumRepository {
        var searchCallCount = 0
        val recordedSearchCalls = mutableListOf<Triple<String, String, Int>>()
        val recordedFeedCalls = mutableListOf<Triple<String, FeedSort, Int>>()
        val recordedDetailCalls: MutableList<Pair<Long, Int>> = Collections.synchronizedList(mutableListOf())

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

        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> {
            recordedDetailCalls += topicId to page
            return detailHandler(topicId, page)
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

    private fun makeDetailPage(topicId: Long, title: String, opBody: String): TopicDetailPage {
        val opAuthor = FloorAuthor(uid = topicId, username = "seller_$topicId", isTopicAuthor = true)
        return TopicDetailPage(
            topicId = topicId,
            title = title,
            categorySlug = "trade",
            categoryName = "交易",
            author = opAuthor,
            currentPage = 1,
            totalPages = 1,
            openingFloor = FloorComment(
                commentId = topicId * 10,
                floorNumber = 0,
                author = opAuthor,
                createdAtIso = "2026-06-01T00:00:00Z",
                markdownBody = opBody,
            ),
        )
    }

    @Test
    fun `searchRemoteAndMerge fetches OP bodies for concise ask-only and spread-no-ask peers skips complete titles and caches across lookups`() = runTest {
        var nowMs = 1_780_272_000_000L // 2026-06-01
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            // Concise matched peer: no spread & no ask in title -> MUST fetch body (Priority 0)
                            TopicSummary(701L, "[出] DMIT LAX Pro Wee 带原油", "trade", "交易", authorName = "s1"),
                            // Concise matched peer: no spread & no ask in title -> MUST fetch body (Priority 0, ask only in body)
                            TopicSummary(702L, "[出] DMIT LAX Pro Wee 吃灰出", "trade", "交易", authorName = "s2"),
                            // Ask-only in title (missing renewal/expiry) -> MUST fetch body (Priority 1) to upgrade to derived spread
                            TopicSummary(703L, "[出] DMIT LAX Pro Wee 明盘 290", "trade", "交易", authorName = "s3"),
                            // Explicit spread without total ask in title -> MUST fetch body (Priority 2) to derive total price
                            TopicSummary(704L, "[出] DMIT LAX Pro Wee 溢价 +60", "trade", "交易", authorName = "s4"),
                            // Already has complete ask + renewal + expiry in title -> MUST NOT fetch body
                            TopicSummary(706L, "[出] DMIT LAX Pro Wee 310r \$36.5/年 2026-11-28到期", "trade", "交易", authorName = "s6"),
                            // Unmatched model -> MUST NOT fetch body
                            TopicSummary(705L, "[出] 搬瓦工 MEGABOX 带原油", "trade", "交易", authorName = "s5"),
                        ),
                    ),
                )
            },
            detailHandler = { id, page ->
                assertEquals(1, page)
                when (id) {
                    701L -> Result.success(
                        makeDetailPage(
                            topicId = 701L,
                            title = "[出] DMIT LAX Pro Wee 带原油",
                            opBody = "明盘 300\n续费 \$36.5/年\n2026-11-28到期",
                        ),
                    )
                    702L -> Result.success(
                        makeDetailPage(
                            topicId = 702L,
                            title = "[出] DMIT LAX Pro Wee 吃灰出",
                            opBody = "一口价 270 带原油",
                        ),
                    )
                    703L -> Result.success(
                        makeDetailPage(
                            topicId = 703L,
                            title = "[出] DMIT LAX Pro Wee 明盘 290",
                            opBody = "续费 \$36.5/年，2026-11-28到期，带原油",
                        ),
                    )
                    704L -> Result.success(
                        makeDetailPage(
                            topicId = 704L,
                            title = "[出] DMIT LAX Pro Wee 溢价 +60",
                            opBody = "续费 \$36.5/年，2026-11-28到期，带原油",
                        ),
                    )
                    else -> Result.failure(AssertionError("Unexpected detail fetch for topic $id"))
                }
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
            nowDateProvider = { LocalDate.of(2026, 6, 1) },
        )

        val state = repo.searchRemoteAndMerge("DMIT LAX Pro Wee")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertEquals(setOf(701L, 702L, 703L, 704L), forum.recordedDetailCalls.map { it.first }.toSet())

        val dist = state.distribution!!
        assertEquals(5, dist.matchedTopicCount)
        assertEquals(5, dist.pricedTopicCount)
        assertEquals(4, dist.sampleCount) // 704 (+60 explicit), 701 (derived from body), 703 (upgraded from ask-only via body), 706 (derived from title)
        assertEquals(3, dist.derivedSampleCount)
        assertEquals(1, dist.derivedAskSampleCount)
        val derived701 = dist.observations.first { it.topicId == 701L }
        assertTrue(derived701.isDerived)
        assertFalse(derived701.isAskDerived)
        assertEquals(BigDecimal("300.00"), derived701.askPriceCny)
        assertNotNull(derived701.residualCny)
        assertTrue(derived701.matchedExpression.contains("推算:"))
        val derived703 = dist.observations.first { it.topicId == 703L }
        assertTrue("Ask-only title 703 should upgrade to derived spread after body fetch", derived703.isDerived)
        assertEquals(BigDecimal("290.00"), derived703.askPriceCny)
        assertNotNull(derived703.residualCny)
        val explicit704 = dist.observations.first { it.topicId == 704L }
        assertFalse(explicit704.isDerived)
        assertTrue("Explicit-spread peer 704 should derive total ask from OP body", explicit704.isAskDerived)
        assertEquals(BigDecimal("190.50"), explicit704.askPriceCny)
        assertEquals(BigDecimal("130.50"), explicit704.residualCny)
        // Ask observations include 704 (190.50 derived), 702 (270), 703 (290), 701 (300), 706 (310) -> median 290.00
        assertEquals(5, dist.askSampleCount)
        assertEquals(BigDecimal("290.00"), dist.askMedianCny)

        // Expire 15m remote cache TTL (bodyCache TTL is 30m): second lookup MUST reuse cached bodies with 0 extra detail calls
        nowMs += 16 * 60_000L
        val afterTtl = repo.searchRemoteAndMerge("DMIT LAX Pro Wee")
        assertEquals(MarketSearchStatus.SUCCESS, afterTtl.searchStatus)
        assertEquals(4, forum.recordedDetailCalls.size)
        assertEquals(4, afterTtl.distribution?.sampleCount)
        assertEquals(5, afterTtl.distribution?.askSampleCount)
    }

    @Test
    fun `loose matched ultra-short peers fetch OP bodies and are confirmed or rejected by strict matchesQuery`() = runTest {
        val nowMs = 1_780_272_000_000L // 2026-06-01
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            // Ultra-short loose match, no pricing -> body confirms LAX Pro + supplies price & renewal/expiry
                            TopicSummary(851L, "出大妈wee", "trade", "交易", authorName = "u1"),
                            // Ultra-short loose match, ask-only in title -> body confirms LAX Pro + supplies renewal/expiry
                            TopicSummary(852L, "大妈 wee 300", "trade", "交易", authorName = "u2"),
                            // Ultra-short loose match -> body reveals TYO EB (wrong region/tier) -> MUST be rejected after body arrives
                            TopicSummary(853L, "出大妈wee 260", "trade", "交易", authorName = "u3"),
                            // Conflicting location in title -> rejected by matchesQueryLoosely, MUST NOT fetch body
                            TopicSummary(854L, "出大妈 HKG Pro Wee 300", "trade", "交易", authorName = "u4"),
                        ),
                    ),
                )
            },
            detailHandler = { id, page ->
                assertEquals(1, page)
                when (id) {
                    851L -> Result.success(
                        makeDetailPage(
                            topicId = 851L,
                            title = "出大妈wee",
                            opBody = "美西 LAX.Pro.WEE，明盘 280，续费 \$36.5/年，2026-11-28到期",
                        ),
                    )
                    852L -> Result.success(
                        makeDetailPage(
                            topicId = 852L,
                            title = "大妈 wee 300",
                            opBody = "洛杉矶 LAX Pro Wee，续费 \$36.5/年，2026-11-28到期",
                        ),
                    )
                    853L -> Result.success(
                        makeDetailPage(
                            topicId = 853L,
                            title = "出大妈wee 260",
                            opBody = "日本东京 TYO.EB.WEE，续费 \$39.9/年，2026-11-28到期",
                        ),
                    )
                    else -> Result.failure(AssertionError("Conflicting peer $id must NOT be fetched"))
                }
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
            nowDateProvider = { LocalDate.of(2026, 6, 1) },
        )

        val state = repo.searchRemoteAndMerge("DMIT LAX Pro WEE")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertEquals(setOf(851L, 852L, 853L), forum.recordedDetailCalls.map { it.first }.toSet())

        val dist = state.distribution!!
        assertEquals("Only strictly confirmed peers 851 & 852 should remain", 2, dist.matchedTopicCount)
        assertEquals(2, dist.sampleCount)
        assertEquals(2, dist.derivedSampleCount)
        assertEquals(setOf(851L, 852L), dist.observations.map { it.topicId }.toSet())
        assertTrue("Wrong-region peer 853 must be rejected after strict body check", dist.observations.none { it.topicId == 853L })
        assertTrue(dist.askObservations.none { it.topicId == 853L })
    }

    @Test
    fun `body fetch prioritizes no-pricing peers ahead of ask-only peers within cap of 6`() = runTest {
        val nowMs = 1_780_272_000_000L
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            // 4 no-pricing peers (lower topicIds) -> MUST all be prioritized ahead of ask-only peers!
                            TopicSummary(861L, "[出] DMIT LAX Pro Wee 带原油 A", "trade", "交易", authorName = "n1"),
                            TopicSummary(862L, "[出] DMIT LAX Pro Wee 带原油 B", "trade", "交易", authorName = "n2"),
                            TopicSummary(863L, "[出] DMIT LAX Pro Wee 带原油 C", "trade", "交易", authorName = "n3"),
                            TopicSummary(864L, "[出] DMIT LAX Pro Wee 带原油 D", "trade", "交易", authorName = "n4"),
                            // 4 ask-only peers (higher topicIds) -> only top 2 newest (874, 873) fit remaining cap!
                            TopicSummary(871L, "[出] DMIT LAX Pro Wee 明盘 280", "trade", "交易", authorName = "a1"),
                            TopicSummary(872L, "[出] DMIT LAX Pro Wee 明盘 290", "trade", "交易", authorName = "a2"),
                            TopicSummary(873L, "[出] DMIT LAX Pro Wee 明盘 300", "trade", "交易", authorName = "a3"),
                            TopicSummary(874L, "[出] DMIT LAX Pro Wee 明盘 310", "trade", "交易", authorName = "a4"),
                        ),
                    ),
                )
            },
            detailHandler = { id, _ ->
                Result.success(makeDetailPage(id, "t", "续费 \$36.5/年，2026-11-28到期"))
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
            nowDateProvider = { LocalDate.of(2026, 6, 1) },
        )

        val state = repo.searchRemoteAndMerge("DMIT LAX Pro Wee")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertEquals(6, forum.recordedDetailCalls.size)
        assertEquals(
            "All 4 no-pricing peers plus top 2 newest ask-only peers should be fetched",
            setOf(861L, 862L, 863L, 864L, 873L, 874L),
            forum.recordedDetailCalls.map { it.first }.toSet(),
        )
        // Ask-only peers 873 & 874 upgraded to derived spreads; 871 & 872 remain ask-only
        assertEquals(2, state.distribution?.derivedSampleCount)
        assertEquals(4, state.distribution?.askSampleCount)
    }

    @Test
    fun `body fetch enforces cap of 6 and concurrency of 2 never throws on failures and respects 429 cooldown`() = runTest {
        var nowMs = 1_780_272_000_000L
        val inFlight = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val conciseTopics = (1L..10L).map { idx ->
            val label = ('A'.code + idx.toInt() - 1).toChar()
            TopicSummary(800L + idx, "[出] DMIT LAX Pro Wee 带原油 $label", "trade", "交易", authorName = "u$idx")
        }
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ -> Result.success(FeedPageResult(topics = conciseTopics)) },
            detailHandler = { id, _ ->
                val cur = inFlight.incrementAndGet()
                maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, cur) }
                try {
                    delay(20L)
                    when (id) {
                        810L -> Result.success(makeDetailPage(id, "t", "一口价 310"))
                        809L -> Result.failure(IOException("transient timeout"))
                        808L -> Result.failure(TopicAccessException.PrivateTopic)
                        807L -> Result.failure(NodeSeekApiException("not found", statusCode = 404))
                        else -> Result.success(makeDetailPage(id, "t", "明盘 290"))
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
            nowDateProvider = { LocalDate.of(2026, 6, 1) },
        )

        val state = repo.searchRemoteAndMerge("DMIT LAX Pro Wee")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertEquals("Body fetches must be capped at 6 per lookup", 6, forum.recordedDetailCalls.size)
        assertEquals(
            "Top 6 newest topicIds should be selected",
            setOf(810L, 809L, 808L, 807L, 806L, 805L),
            forum.recordedDetailCalls.map { it.first }.toSet(),
        )
        assertTrue("Concurrency must be <= 2, observed ${maxObservedConcurrency.get()}", maxObservedConcurrency.get() <= 2)
        // 810, 806, 805 succeeded with ask prices; 809/808/807 failed without crashing
        assertEquals(3, state.distribution?.askSampleCount)

        // Now verify HTTP 429 during body fetch records cooldown and aborts remaining fetches without throwing
        val rateLimitedForum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ -> Result.success(FeedPageResult(topics = conciseTopics.take(5))) },
            detailHandler = { _, _ ->
                Result.failure(NodeSeekApiException("Too Many Requests", statusCode = 429, retryAfterSeconds = 45))
            },
        )
        val rateLimitedRepo = TradeMarketReferenceRepositoryImpl(
            forumRepository = rateLimitedForum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
            bodyFetchConcurrency = 1,
        )
        val rlState = rateLimitedRepo.searchRemoteAndMerge("DMIT LAX Pro Wee")
        assertEquals(MarketSearchStatus.SUCCESS, rlState.searchStatus)
        assertEquals("Should abort remaining body fetches after first 429", 1, rateLimitedForum.recordedDetailCalls.size)
        // Subsequent lookup during cooldown must honor cooldown with 0 extra network calls
        nowMs += 5_000L
        val cooled = rateLimitedRepo.searchRemoteAndMerge("瓦工 MEGABOX")
        assertEquals(MarketSearchStatus.COOLDOWN, cooled.searchStatus)
        assertTrue(cooled.cooldownRemainingSeconds > 0)
    }

    @Test
    fun `analyzeLocal uses bookmark offline snapshot bodies and cached topic bodies with zero network calls`() = runTest {
        val bookmarkTopic = BookmarkedTopic(
            topicId = 901L,
            summary = TopicSummary(901L, "[出] DMIT LAX Pro Wee 带原油", "trade", "交易", authorName = "bm"),
            offlineDetailSnapshot = makeDetailPage(
                topicId = 901L,
                title = "[出] DMIT LAX Pro Wee 带原油",
                opBody = "明盘 300 续费 \$36.5/年 2026-11-28到期",
            ),
        )
        val historyEntry = ReadHistoryEntry(
            topicId = 902L,
            title = "[出] DMIT LAX Pro Wee 吃灰",
            categoryName = "交易",
            authorName = "hist",
            categorySlug = "trade",
        )
        val forum = FakeMarketForumRepository(
            searchHandler = { _, _ -> throw AssertionError("analyzeLocal must NOT call searchTopics") },
            detailHandler = { _, _ -> throw AssertionError("analyzeLocal must NOT call getTopicDetail") },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(
                bookmarks = listOf(bookmarkTopic),
                history = listOf(historyEntry),
            ),
            nowDateProvider = { LocalDate.of(2026, 6, 1) },
        )
        repo.cacheTopicBody(902L, "一口价 260")

        val state = repo.analyzeLocal("DMIT LAX Pro Wee")
        assertEquals(0, forum.searchCallCount)
        assertTrue(forum.recordedDetailCalls.isEmpty())
        val dist = state.distribution!!
        assertEquals(2, dist.matchedTopicCount)
        assertEquals(2, dist.pricedTopicCount)
        assertEquals(1, dist.sampleCount)
        assertEquals(1, dist.derivedSampleCount)
        assertEquals(2, dist.askSampleCount)
        assertEquals(BigDecimal("280.00"), dist.askMedianCny)
    }

    @Test
    fun `repo uses ExchangeRateRepository Frankfurter cache or overrides and falls back to static rates offline`() = runTest {
        val fxCacheFile = tempFolder.newFile("valuation_fx_cache.json")
        fxCacheFile.writeText("")
        val fxRepo = ExchangeRateRepository(cacheFile = fxCacheFile)
        // Custom override USD = 8.00 CNY (distinct from static 7.25)
        fxRepo.setManualOverride(ValuationCurrency.USD, BigDecimal("8.00"))

        val forum = FakeMarketForumRepository(
            localTopicsProvider = {
                listOf(
                    TopicSummary(
                        id = 950L,
                        title = "[出] DMIT LAX Pro Wee 300r \$36.5/年 2026-11-28到期",
                        categorySlug = "trade",
                        categoryName = "交易",
                        authorName = "fx_seller",
                    ),
                )
            },
        )
        val fixedDate = LocalDate.of(2026, 6, 1) // 180 days before 2026-11-28

        // 1. With ExchangeRateRepository (USD=8.00): residual = 36.5 * 8.00 * 180 / 365 = 144.00 -> spread = +156.00
        val repoWithFx = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            exchangeRateRepository = fxRepo,
            nowDateProvider = { fixedDate },
        )
        val withFx = repoWithFx.analyzeLocal("DMIT LAX Pro Wee")
        assertEquals(BigDecimal("156.00"), withFx.distribution?.medianCny)
        assertEquals(BigDecimal("144.00"), withFx.distribution?.observations?.single()?.residualCny)

        // 2. Without ExchangeRateRepository (offline static default USD=7.25): residual = 36.5 * 7.25 * 180 / 365 = 130.50 -> spread = +169.50
        val repoOffline = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            exchangeRateRepository = null,
            nowDateProvider = { fixedDate },
        )
        val offline = repoOffline.analyzeLocal("DMIT LAX Pro Wee")
        assertEquals(BigDecimal("169.50"), offline.distribution?.medianCny)
        assertEquals(BigDecimal("130.50"), offline.distribution?.observations?.single()?.residualCny)
    }

    @Test
    fun `excludeTopicId removes current topic from analysis and avoids fetching its OP body`() = runTest {
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            TopicSummary(980L, "[出] DMIT LAX Pro Wee 带原油", "trade", "交易", authorName = "self"),
                            TopicSummary(981L, "[出] DMIT LAX Pro Wee 溢价 +50", "trade", "交易", authorName = "peer"),
                        ),
                    ),
                )
            },
            detailHandler = { id, _ ->
                if (id == 980L) throw AssertionError("Excluded current topic 980L must NOT have its body fetched")
                Result.success(makeDetailPage(id, "t", "body"))
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { 1_780_272_000_000L },
        )

        val state = repo.searchRemoteAndMerge("DMIT LAX Pro Wee", excludeTopicId = 980L)
        assertTrue(
            "Excluded current topic 980L must NOT have its body fetched",
            forum.recordedDetailCalls.none { it.first == 980L },
        )
        val dist = state.distribution!!
        assertEquals(1, dist.matchedTopicCount)
        assertEquals(1, dist.sampleCount)
        assertTrue(dist.observations.none { it.topicId == 980L })
        assertTrue(dist.matchedUnparsedTopics.none { it.topicId == 980L })
    }

    @Test
    fun `body fetch prioritizes no-pricing then ask-only then spread-no-ask ahead of loose-confirm within cap of 6`() = runTest {
        val nowMs = 1_780_272_000_000L
        val forum = FakeMarketForumRepository(
            searchHandlerWithPage = { _, _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            // Priority 0: 2 no-pricing peers
                            TopicSummary(881L, "[出] DMIT LAX Pro Wee 带原油 A", "trade", "交易", authorName = "n1"),
                            TopicSummary(882L, "[出] DMIT LAX Pro Wee 带原油 B", "trade", "交易", authorName = "n2"),
                            // Priority 1: 2 ask-only peers
                            TopicSummary(883L, "[出] DMIT LAX Pro Wee 明盘 280", "trade", "交易", authorName = "a1"),
                            TopicSummary(884L, "[出] DMIT LAX Pro Wee 明盘 290", "trade", "交易", authorName = "a2"),
                            // Priority 2: 3 explicit-spread-no-ask peers -> top 2 newest (887, 886) fit remaining cap of 6
                            TopicSummary(885L, "[出] DMIT LAX Pro Wee 溢价 +40", "trade", "交易", authorName = "s1"),
                            TopicSummary(886L, "[出] DMIT LAX Pro Wee 溢价 +50", "trade", "交易", authorName = "s2"),
                            TopicSummary(887L, "[出] DMIT LAX Pro Wee 溢价 +60", "trade", "交易", authorName = "s3"),
                            // Complete spread + ask in title -> never fetched
                            TopicSummary(889L, "[出] DMIT LAX Pro Wee 溢价 +70 明盘 300", "trade", "交易", authorName = "c1"),
                        ),
                    ),
                )
            },
            detailHandler = { id, _ ->
                Result.success(makeDetailPage(id, "t", "续费 \$36.5/年，2026-11-28到期"))
            },
        )
        val repo = TradeMarketReferenceRepositoryImpl(
            forumRepository = forum,
            bookmarkAndHistoryRepository = FakeMarketBookmarkRepository(),
            clockMillis = { nowMs },
            nowDateProvider = { LocalDate.of(2026, 6, 1) },
        )

        val state = repo.searchRemoteAndMerge("DMIT LAX Pro Wee")
        assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
        assertEquals(6, forum.recordedDetailCalls.size)
        assertEquals(
            setOf(881L, 882L, 883L, 884L, 886L, 887L),
            forum.recordedDetailCalls.map { it.first }.toSet(),
        )
        val dist = state.distribution!!
        assertEquals(2, dist.derivedAskSampleCount)
        assertTrue(dist.observations.first { it.topicId == 887L }.isAskDerived)
        assertEquals(BigDecimal("190.50"), dist.observations.first { it.topicId == 887L }.askPriceCny)
    }
}
