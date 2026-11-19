package com.nodeseek.app.data.deals

import android.content.Context
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.deals.DealPostExtractor
import com.nodeseek.app.domain.deals.DealsFeedSnapshot
import com.nodeseek.app.domain.deals.DealsRepository
import com.nodeseek.app.domain.deals.ExternalDealsBridge
import com.nodeseek.app.domain.deals.PromoDealCardModel
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aggregates Black Friday & major promo VPS deals from:
 * - NodeSeek `info`, `trade`, and `promotion` category feeds (works in both authenticated & RSS guest modes)
 * - Targeted NodeSeek promo searches (`黑五`, `优惠`, `促销`, `Flash`, `BF`)
 * - External overseas deals plugged via [ExternalDealsBridge] (Feature 7 LET/LEB)
 *
 * Uses disk caching (`filesDir/deals_hub_cache.json`) and bounded, circuit-broken detail
 * enrichment so the Deals Hub renders fast without triggering NodeSeek rate limits.
 */
class DealsRepositoryImpl(
    private val forumRepository: ForumRepository,
    private val exchangeRateRepository: ExchangeRateRepository,
    cacheFile: File,
    private val externalDealsBridge: ExternalDealsBridge = ExternalDealsBridge.EMPTY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val maxDetailEnrichments: Int = DEFAULT_MAX_DETAIL_ENRICHMENTS,
    private val detailStaggerMillis: Long = DEFAULT_DETAIL_STAGGER_MILLIS,
) : DealsRepository {

    constructor(
        context: Context,
        forumRepository: ForumRepository,
        exchangeRateRepository: ExchangeRateRepository = ExchangeRateRepository.forContext(context),
        externalDealsBridge: ExternalDealsBridge = ExternalDealsBridge.EMPTY,
    ) : this(
        forumRepository = forumRepository,
        exchangeRateRepository = exchangeRateRepository,
        cacheFile = File((context.applicationContext ?: context).filesDir, DealsDiskCache.CACHE_FILE_NAME),
        externalDealsBridge = externalDealsBridge,
    )

    private val diskCache = DealsDiskCache(cacheFile)
    private val refreshMutex = Mutex()
    private var diskLoaded = false

    private val _dealsState = MutableStateFlow(DealsFeedSnapshot())
    override val dealsState: StateFlow<DealsFeedSnapshot> = _dealsState.asStateFlow()

    override suspend fun loadCachedDeals(): DealsFeedSnapshot = withContext(ioDispatcher) {
        refreshMutex.withLock {
            ensureDiskLoadedLocked()
            _dealsState.value
        }
    }

    override suspend fun refreshDeals(force: Boolean): Result<DealsFeedSnapshot> = withContext(ioDispatcher) {
        refreshMutex.withLock {
            ensureDiskLoadedLocked()
            val now = nowMillisProvider()
            val cachedSnapshot = _dealsState.value
            if (!force &&
                cachedSnapshot.deals.isNotEmpty() &&
                cachedSnapshot.updatedAtEpochMillis > 0L &&
                (now - cachedSnapshot.updatedAtEpochMillis) in 0L until FRESH_CACHE_TTL_MILLIS
            ) {
                return@withLock Result.success(cachedSnapshot)
            }

            val fxState = resolveFxRates()
            val existingById = cachedSnapshot.deals.associateBy { it.id }

            // 1. Concurrently gather feeds, targeted promo searches, and external overseas deals
            val aggregated = coroutineScope {
                val feedJobs = TARGET_FEED_CATEGORIES.map { slug ->
                    async {
                        slug to runCatching {
                            forumRepository.getFeed(
                                categorySlug = slug,
                                sort = FeedSort.NEWEST_TOPIC,
                                page = 1,
                                forceRefresh = force,
                            )
                        }.getOrElse { t ->
                            if (t is CancellationException) throw t
                            Result.failure(t)
                        }
                    }
                }

                val searchSemaphore = Semaphore(2)
                val searchJobs = TARGET_SEARCH_QUERIES.map { query ->
                    async {
                        searchSemaphore.withPermit {
                            query to runCatching {
                                forumRepository.searchTopics(
                                    query = query,
                                    categorySlug = "",
                                    sort = FeedSort.NEWEST_TOPIC,
                                    page = 1,
                                )
                            }.getOrElse { t ->
                                if (t is CancellationException) throw t
                                Result.failure(t)
                            }
                        }
                    }
                }

                val externalJob = async {
                    runCatching { externalDealsBridge.fetchExternalDeals() }
                        .onFailure { t -> if (t is CancellationException) throw t }
                }

                val feedResults = feedJobs.awaitAll()
                val searchResults = searchJobs.awaitAll()
                val externalResult = externalJob.await()

                AggregatedRawSources(
                    feedResults = feedResults,
                    searchResults = searchResults,
                    externalResult = externalResult,
                )
            }

            // 2. Deduplicate NodeSeek topics & track provenance (search hits get boost)
            val topicMap = LinkedHashMap<Long, TopicCandidate>()
            var anyNodeSeekSuccess = false
            var isRssGuestMode = false
            var firstFailureMessage: String? = null

            for ((slug, result) in aggregated.feedResults) {
                result.onSuccess { page ->
                    anyNodeSeekSuccess = true
                    if (page.isFromRssFallback) isRssGuestMode = true
                    (page.pinnedTopics + page.topics).forEach { topic ->
                        val normalized = if (topic.categorySlug.isBlank()) topic.copy(categorySlug = slug) else topic
                        val prev = topicMap[normalized.id]
                        if (prev == null) {
                            topicMap[normalized.id] = TopicCandidate(
                                topic = normalized,
                                matchedByPromoSearch = false,
                            )
                        }
                    }
                }.onFailure { err ->
                    if (firstFailureMessage == null) {
                        firstFailureMessage = err.message
                    }
                }
            }

            for ((_, result) in aggregated.searchResults) {
                result.onSuccess { page ->
                    anyNodeSeekSuccess = true
                    page.topics.forEach { topic ->
                        val prev = topicMap[topic.id]
                        if (prev == null) {
                            topicMap[topic.id] = TopicCandidate(
                                topic = topic,
                                matchedByPromoSearch = true,
                            )
                        } else if (!prev.matchedByPromoSearch) {
                            topicMap[topic.id] = prev.copy(matchedByPromoSearch = true)
                        }
                    }
                }
            }

            val externalDeals = aggregated.externalResult
                .getOrDefault(emptyList())
                .map { DealPostExtractor.normalizeWithFxRates(it, fxState) }
            val externalSucceeded = aggregated.externalResult.isSuccess && externalDeals.isNotEmpty()

            // If all sources failed and we have cached deals, keep cache cleanly with warning
            if (!anyNodeSeekSuccess && !externalSucceeded && cachedSnapshot.deals.isNotEmpty()) {
                val fallbackSnapshot = cachedSnapshot.copy(
                    isFromCache = true,
                    warningMessage = firstFailureMessage?.takeIf { it.isNotBlank() }
                        ?: "大促数据刷新受限，当前展示本地缓存",
                )
                _dealsState.value = fallbackSnapshot
                return@withLock Result.success(fallbackSnapshot)
            }

            if (!anyNodeSeekSuccess && !externalSucceeded) {
                val failure = IllegalStateException(firstFailureMessage ?: "未能获取到黑五大促数据，请稍后重试")
                return@withLock Result.failure(failure)
            }

            // 3. Initial title-level extraction & promo candidate filtering
            val candidateDeals = topicMap.values.mapNotNull { candidate ->
                val baseDeal = DealPostExtractor.fromNodeSeekTopic(
                    topic = candidate.topic,
                    openingFloorMarkdown = "",
                    fxRates = fxState,
                    nowEpochMillis = now,
                    isDetailEnriched = false,
                )
                val extractedPreview = DealPostExtractor.extract(
                    title = candidate.topic.title,
                    body = "",
                    fxRates = fxState,
                    publishedAtEpochMillis = baseDeal.publishedAtEpochMillis,
                )
                val isPromo = candidate.matchedByPromoSearch || DealPostExtractor.isLikelyPromoDeal(
                    title = candidate.topic.title,
                    body = "",
                    categorySlug = candidate.topic.categorySlug,
                    extracted = extractedPreview,
                )
                if (!isPromo) return@mapNotNull null

                // Preserve previous detail-enriched snapshot if still fresh in cache
                val cached = existingById[baseDeal.id]
                val workingDeal = if (cached != null &&
                    cached.isDetailEnriched &&
                    !force &&
                    cachedSnapshot.updatedAtEpochMillis > 0L &&
                    (now - cachedSnapshot.updatedAtEpochMillis) < DETAIL_CACHE_TTL_MILLIS
                ) {
                    DealPostExtractor.normalizeWithFxRates(
                        deal = cached.copy(
                            replyCount = maxOf(cached.replyCount, baseDeal.replyCount),
                            viewCount = maxOf(cached.viewCount, baseDeal.viewCount),
                        ),
                        fxRates = fxState,
                    )
                } else {
                    baseDeal
                }

                ScoredCandidate(
                    topic = candidate.topic,
                    deal = workingDeal,
                    score = scoreForEnrichment(candidate, workingDeal),
                )
            }

            // 4. Rate-limit-friendly enrichment for top candidates missing full detail
            val enrichIds = candidateDeals
                .filter { !it.deal.isDetailEnriched }
                .sortedWith(
                    compareByDescending<ScoredCandidate> { it.score }
                        .thenByDescending { it.deal.publishedAtEpochMillis },
                )
                .take(if (isRssGuestMode) minOf(maxDetailEnrichments, RSS_MODE_MAX_DETAIL_ENRICHMENTS) else maxDetailEnrichments)
                .map { it.topic.id }
                .toSet()

            val detailCircuitTripped = AtomicBoolean(false)
            val detailSemaphore = Semaphore(2)

            val enrichedNodeSeekDeals = coroutineScope {
                candidateDeals.map { item ->
                    async {
                        if (item.topic.id !in enrichIds || detailCircuitTripped.get()) {
                            return@async item.deal
                        }
                        detailSemaphore.withPermit {
                            if (detailCircuitTripped.get()) return@withPermit item.deal
                            if (detailStaggerMillis > 0L) {
                                delay(detailStaggerMillis)
                            }
                            val detailResult = runCatching {
                                forumRepository.getTopicDetail(topicId = item.topic.id, page = 1)
                            }.getOrElse { t ->
                                if (t is CancellationException) throw t
                                Result.failure(t)
                            }
                            detailResult.fold(
                                onSuccess = { detail ->
                                    val openingMarkdown = detail.openingFloor?.markdownBody.orEmpty()
                                    val enrichedSummary = item.topic.copy(
                                        title = detail.title.ifBlank { item.topic.title },
                                        categorySlug = detail.categorySlug.ifBlank { item.topic.categorySlug },
                                        categoryName = detail.categoryName.ifBlank { item.topic.categoryName },
                                        viewCount = maxOf(item.topic.viewCount, detail.viewCount),
                                        replyCount = maxOf(item.topic.replyCount, detail.replies.size),
                                        lastActivityIso = detail.createdAtIso?.takeIf { it.isNotBlank() }
                                            ?: item.topic.lastActivityIso,
                                    )
                                    DealPostExtractor.fromNodeSeekTopic(
                                        topic = enrichedSummary,
                                        openingFloorMarkdown = openingMarkdown,
                                        fxRates = fxState,
                                        nowEpochMillis = now,
                                        isDetailEnriched = true,
                                    )
                                },
                                onFailure = { err ->
                                    if (shouldTripDetailCircuitBreaker(err)) {
                                        detailCircuitTripped.set(true)
                                    }
                                    item.deal
                                },
                            )
                        }
                    }
                }.awaitAll()
            }

            // 5. Merge NodeSeek + External deals, dedup by id, persist to disk cache
            val mergedMap = LinkedHashMap<String, PromoDealCardModel>()
            enrichedNodeSeekDeals.forEach { deal -> mergedMap[deal.id] = deal }
            externalDeals.forEach { deal -> mergedMap[deal.id] = deal }

            val mergedList = mergedMap.values.sortedWith(
                compareByDescending<PromoDealCardModel> { it.publishedAtEpochMillis }
                    .thenByDescending { it.replyCount },
            )

            val warning = when {
                !anyNodeSeekSuccess && externalSucceeded -> "站内拉取受限，当前已展示海外情报与缓存数据"
                isRssGuestMode && detailCircuitTripped.get() -> "访客/RSS 模式：已基于标题快速解析大促信息"
                else -> null
            }

            val snapshot = DealsFeedSnapshot(
                deals = mergedList,
                updatedAtEpochMillis = now,
                fxUsdToCnyRate = fxState.rateFor(ValuationCurrency.USD),
                fxProviderDate = fxState.providerDate,
                isFromCache = false,
                warningMessage = warning,
            )
            diskCache.write(snapshot)
            _dealsState.value = snapshot
            Result.success(snapshot)
        }
    }

    private suspend fun resolveFxRates(): FxRatesState {
        return when (val outcome = runCatching { exchangeRateRepository.ensureFreshRates(force = false) }.getOrNull()) {
            is FxRefreshOutcome.Success -> outcome.state
            is FxRefreshOutcome.Fallback -> outcome.state
            null -> exchangeRateRepository.state.value
        }
    }

    private fun ensureDiskLoadedLocked() {
        if (diskLoaded) return
        diskLoaded = true
        val cached = diskCache.read()
        if (cached != null) {
            _dealsState.value = cached
        }
    }

    private fun scoreForEnrichment(
        candidate: TopicCandidate,
        deal: PromoDealCardModel,
    ): Int {
        var score = 0
        if (candidate.matchedByPromoSearch) score += 25
        if (deal.categorySlug.equals("info", ignoreCase = true) ||
            deal.categorySlug.equals("promotion", ignoreCase = true)
        ) {
            score += 15
        }
        val lowerTitle = deal.title.lowercase()
        if (lowerTitle.contains("黑五") || lowerTitle.contains("black friday") || lowerTitle.contains("bf")) {
            score += 20
        }
        if (deal.annualizedUsd != null) score += 10
        if (deal.promoCode == null || deal.orderUrl == null) score += 8
        score += (deal.replyCount.coerceAtMost(50) / 5)
        return score
    }

    private fun shouldTripDetailCircuitBreaker(err: Throwable): Boolean {
        if (err is TopicAccessException.CloudflareChallengeRequired) return true
        val msg = err.message.orEmpty().lowercase()
        return msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests") || msg.contains("cloudflare")
    }

    private data class TopicCandidate(
        val topic: TopicSummary,
        val matchedByPromoSearch: Boolean,
    )

    private data class ScoredCandidate(
        val topic: TopicSummary,
        val deal: PromoDealCardModel,
        val score: Int,
    )

    private data class AggregatedRawSources(
        val feedResults: List<Pair<String, Result<com.nodeseek.app.domain.model.FeedPageResult>>>,
        val searchResults: List<Pair<String, Result<com.nodeseek.app.domain.model.FeedPageResult>>>,
        val externalResult: Result<List<PromoDealCardModel>>,
    )

    companion object {
        val TARGET_FEED_CATEGORIES: List<String> = listOf("info", "trade", "promotion")
        val TARGET_SEARCH_QUERIES: List<String> = listOf("黑五", "优惠", "促销", "Flash", "BF")

        const val FRESH_CACHE_TTL_MILLIS: Long = 3L * 60L * 1000L
        const val DETAIL_CACHE_TTL_MILLIS: Long = 15L * 60L * 1000L
        const val DEFAULT_MAX_DETAIL_ENRICHMENTS: Int = 10
        const val RSS_MODE_MAX_DETAIL_ENRICHMENTS: Int = 4
        const val DEFAULT_DETAIL_STAGGER_MILLIS: Long = 120L
    }
}
