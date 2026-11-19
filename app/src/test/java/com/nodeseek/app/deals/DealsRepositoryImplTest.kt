package com.nodeseek.app.deals

import com.nodeseek.app.data.deals.DealsRepositoryImpl
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.deals.DealSource
import com.nodeseek.app.domain.deals.DealTags
import com.nodeseek.app.domain.deals.ExternalDealsBridge
import com.nodeseek.app.domain.deals.PromoDealCardModel
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

class DealsRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun refreshDeals_aggregatesNodeSeekFeedsSearchEnrichmentExternalBridgeAndPersistsCache() = runTest {
        val now = 1_795_096_800_000L
        val fxRepo = ExchangeRateRepository(
            cacheFile = File(tempFolder.root, "fx_cache.json"),
            endpointUrl = "http://127.0.0.1:1/unreachable",
            ioDispatcher = Dispatchers.Unconfined,
            nowMillisProvider = { now },
        )

        val fakeForum = FakeForumRepository(
            feedsBySlug = mapOf(
                "info" to Result.success(
                    FeedPageResult(
                        topics = listOf(
                            TopicSummary(
                                id = 5001L,
                                title = "【黑五闪购】RackNerd 美西 1C1G 20G NVMe $10.99/yr",
                                categorySlug = "info",
                                categoryName = "情报",
                                authorName = "dealHunter",
                                replyCount = 42,
                                lastActivityIso = "2026-11-19T12:00:00Z",
                            ),
                            TopicSummary(
                                id = 5002L,
                                title = "求助：新手买哪个服务器比较好？",
                                categorySlug = "info",
                                categoryName = "情报",
                                authorName = "newbie",
                                replyCount = 3,
                                lastActivityIso = "2026-11-19T11:00:00Z",
                            ),
                        ),
                    ),
                ),
                "trade" to Result.success(
                    FeedPageResult(
                        topics = listOf(
                            TopicSummary(
                                id = 5003L,
                                title = "[收] 收一台搬瓦工香港",
                                categorySlug = "trade",
                                categoryName = "交易",
                                authorName = "buyer",
                                replyCount = 1,
                            ),
                        ),
                    ),
                ),
                "promotion" to Result.success(FeedPageResult(topics = emptyList())),
            ),
            searchByQuery = mapOf(
                "黑五" to Result.success(
                    FeedPageResult(
                        topics = listOf(
                            TopicSummary(
                                id = 5004L,
                                title = "DMIT 日本东京 CN2 GIA 黑五预热 年付19.9刀",
                                categorySlug = "info",
                                categoryName = "情报",
                                authorName = "promoBot",
                                replyCount = 66,
                                lastActivityIso = "2026-11-19T13:00:00Z",
                            ),
                        ),
                    ),
                ),
            ),
            detailsById = mapOf(
                5001L to Result.success(
                    TopicDetailPage(
                        topicId = 5001L,
                        title = "【黑五闪购】RackNerd 美西 1C1G 20G NVMe $10.99/yr",
                        categorySlug = "info",
                        categoryName = "情报",
                        author = FloorAuthor(uid = 1L, username = "dealHunter"),
                        openingFloor = FloorComment(
                            commentId = 9001L,
                            floorNumber = 0,
                            author = FloorAuthor(uid = 1L, username = "dealHunter"),
                            createdAtIso = "2026-11-19T12:00:00Z",
                            markdownBody = "黑五优惠码：RN-BF26\n直达购买：[点击抢购](https://www.nodeseek.com/jump?to=https%3A%2F%2Fmy.racknerd.com%2Fcart.php%3Fa%3Dadd%26pid%3D77)",
                        ),
                    ),
                ),
                5004L to Result.failure(TopicAccessException.CloudflareChallengeRequired),
            ),
        )

        val externalBridge = ExternalDealsBridge {
            listOf(
                PromoDealCardModel(
                    id = "let_888",
                    source = DealSource.OVERSEAS_LET,
                    sourceUrl = "https://lowendtalk.com/discussion/888",
                    orderUrl = "https://hosthatch.example/order",
                    merchantName = "HostHatch",
                    title = "HostHatch Hong Kong Black Friday €12/yr",
                    rawPriceText = "€12/yr",
                    priceAmount = BigDecimal("12"),
                    currency = ValuationCurrency.EUR,
                    billingCycleLabel = "年付",
                    regionTags = listOf(DealTags.REGION_HK),
                    publishedAtEpochMillis = now - 60_000L,
                ),
            )
        }

        val cacheFile = File(tempFolder.root, "deals_hub_cache.json")
        val repository = DealsRepositoryImpl(
            forumRepository = fakeForum,
            exchangeRateRepository = fxRepo,
            cacheFile = cacheFile,
            externalDealsBridge = externalBridge,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillisProvider = { now },
            detailStaggerMillis = 0L,
        )

        val result = repository.refreshDeals(force = true)
        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()

        // Noise topics 5002 (question) & 5003 ([收] want-to-buy) filtered out; 5001, 5004, let_888 kept!
        assertEquals(3, snapshot.deals.size)
        val rnDeal = snapshot.deals.first { it.id == "nodeseek_5001" }
        assertEquals("RN-BF26", rnDeal.promoCode)
        assertEquals("https://my.racknerd.com/cart.php?a=add&pid=77", rnDeal.orderUrl)
        assertTrue(rnDeal.isDetailEnriched)

        val dmitDeal = snapshot.deals.first { it.id == "nodeseek_5004" }
        assertEquals(BigDecimal("19.90"), dmitDeal.annualizedUsd)
        assertTrue(DealTags.ROUTE_CN2_GIA in dmitDeal.routeTags)

        val letDeal = snapshot.deals.first { it.id == "let_888" }
        assertNotNull("External deal should be annualized via FxRatesState", letDeal.annualizedUsd)
        assertNotNull(letDeal.annualizedCny)
        assertTrue(cacheFile.exists())

        // Verify cold-start disk cache reload in a fresh repository instance
        val secondRepo = DealsRepositoryImpl(
            forumRepository = fakeForum,
            exchangeRateRepository = fxRepo,
            cacheFile = cacheFile,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillisProvider = { now },
            detailStaggerMillis = 0L,
        )
        val cachedSnapshot = secondRepo.loadCachedDeals()
        assertEquals(3, cachedSnapshot.deals.size)
        assertTrue(cachedSnapshot.isFromCache)
        assertEquals("RN-BF26", cachedSnapshot.deals.first { it.id == "nodeseek_5001" }.promoCode)
    }

    @Test
    fun refreshDeals_isolatesExternalBridgeFailureAndFallsBackToCacheWhenOffline() = runTest {
        val now = 1_795_096_800_000L
        val fxRepo = ExchangeRateRepository(
            cacheFile = File(tempFolder.root, "fx_cache_2.json"),
            endpointUrl = "http://127.0.0.1:1/unreachable",
            ioDispatcher = Dispatchers.Unconfined,
            nowMillisProvider = { now },
        )
        val cacheFile = File(tempFolder.root, "deals_hub_cache_2.json")

        val onlineForum = FakeForumRepository(
            feedsBySlug = mapOf(
                "info" to Result.success(
                    FeedPageResult(
                        topics = listOf(
                            TopicSummary(
                                id = 6001L,
                                title = "黑五特惠 ClawCloud 香港 1C1G $12/yr",
                                categorySlug = "info",
                                categoryName = "情报",
                                authorName = "tester",
                                replyCount = 15,
                                lastActivityIso = "2026-11-19T12:00:00Z",
                            ),
                        ),
                        isFromRssFallback = true,
                    ),
                ),
            ),
        )

        val failingBridge = ExternalDealsBridge { throw RuntimeException("LET timeout") }
        val repo = DealsRepositoryImpl(
            forumRepository = onlineForum,
            exchangeRateRepository = fxRepo,
            cacheFile = cacheFile,
            externalDealsBridge = failingBridge,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillisProvider = { now },
            detailStaggerMillis = 0L,
        )

        // External bridge throws, yet NodeSeek RSS deal still loads cleanly!
        val first = repo.refreshDeals(force = true)
        assertTrue(first.isSuccess)
        assertEquals(1, first.getOrThrow().deals.size)
        assertEquals("nodeseek_6001", first.getOrThrow().deals.first().id)

        // Next refresh completely offline: returns cached snapshot with non-null warning
        val offlineForum = FakeForumRepository(
            feedsBySlug = mapOf(
                "info" to Result.failure(IllegalStateException("Network offline")),
                "trade" to Result.failure(IllegalStateException("Network offline")),
                "promotion" to Result.failure(IllegalStateException("Network offline")),
            ),
        )
        val offlineRepo = DealsRepositoryImpl(
            forumRepository = offlineForum,
            exchangeRateRepository = fxRepo,
            cacheFile = cacheFile,
            externalDealsBridge = failingBridge,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillisProvider = { now + 10_000L },
            detailStaggerMillis = 0L,
        )
        val fallback = offlineRepo.refreshDeals(force = true)
        assertTrue(fallback.isSuccess)
        val snap = fallback.getOrThrow()
        assertTrue(snap.isFromCache)
        assertEquals(1, snap.deals.size)
        assertNotNull(snap.warningMessage)
    }

    private class FakeForumRepository(
        private val feedsBySlug: Map<String, Result<FeedPageResult>>,
        private val searchByQuery: Map<String, Result<FeedPageResult>> = emptyMap(),
        private val detailsById: Map<Long, Result<TopicDetailPage>> = emptyMap(),
    ) : ForumRepository {
        override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> = Result.success(Category.DEFAULT_CATEGORIES)
        override suspend fun getFeed(
            categorySlug: String,
            sort: FeedSort,
            page: Int,
            includeBlocked: Boolean,
            forceRefresh: Boolean,
        ): Result<FeedPageResult> = feedsBySlug[categorySlug] ?: Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun searchTopics(
            query: String,
            categorySlug: String,
            sort: FeedSort,
            page: Int,
        ): Result<FeedPageResult> = searchByQuery[query] ?: Result.failure(IllegalStateException("Guest search unavailable"))

        override suspend fun getTopicDetail(
            topicId: Long,
            page: Int,
            includeBlocked: Boolean,
        ): Result<TopicDetailPage> = detailsById[topicId] ?: Result.failure(IllegalStateException("Not found"))

        override suspend fun createTopic(categorySlug: String, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> = Result.success(Unit)
        override suspend fun editTopic(topicId: Long, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> = Result.success(Unit)
        override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getFreeDrumstickCost(): Result<Int> = Result.success(5)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType): Result<ReactionResult> =
            Result.failure(UnsupportedOperationException())
    }
}
