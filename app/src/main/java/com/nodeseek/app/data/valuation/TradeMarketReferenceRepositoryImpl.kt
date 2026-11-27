package com.nodeseek.app.data.valuation

import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.valuation.MarketDataSource
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.TradeCandidateTopic
import com.nodeseek.app.domain.valuation.TradeMarketPremiumAnalyzer
import com.nodeseek.app.domain.valuation.TradeMarketReferenceRepository
import com.nodeseek.app.domain.valuation.TradeMarketReferenceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class TradeMarketReferenceRepositoryImpl(
    private val forumRepository: ForumRepository,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val searchMinIntervalMillis: Long = DEFAULT_SEARCH_MIN_INTERVAL_MILLIS,
    private val baseCooldownMillis: Long = DEFAULT_BASE_COOLDOWN_MILLIS,
    private val maxCooldownMillis: Long = DEFAULT_MAX_COOLDOWN_MILLIS,
    private val remoteCacheTtlMillis: Long = DEFAULT_REMOTE_CACHE_TTL_MILLIS,
) : TradeMarketReferenceRepository {

    private data class RemoteCacheEntry(
        val fetchedAtMillis: Long,
        val ttlMillis: Long,
        val topics: List<TopicSummary>,
        val isFromBoardFallback: Boolean = false,
    ) {
        fun isFresh(nowMillis: Long): Boolean =
            topics.isNotEmpty() && nowMillis >= fetchedAtMillis && (nowMillis - fetchedAtMillis) < ttlMillis

        fun isRetained(nowMillis: Long, retentionMillis: Long): Boolean =
            topics.isNotEmpty() && nowMillis >= fetchedAtMillis && (nowMillis - fetchedAtMillis) < retentionMillis
    }

    private data class SharedTradeBoardSnapshot(
        val fetchedAtMillis: Long,
        val topics: List<TopicSummary>,
    ) {
        fun isWithin(nowMillis: Long, ttlMillis: Long): Boolean =
            topics.isNotEmpty() && nowMillis >= fetchedAtMillis && (nowMillis - fetchedAtMillis) < ttlMillis
    }

    private sealed class SearchFetchOutcome {
        data class Success(val topics: List<TopicSummary>) : SearchFetchOutcome()
        data class Failure(val error: Throwable) : SearchFetchOutcome()
    }

    private val remoteCache = ConcurrentHashMap<String, RemoteCacheEntry>()
    private val throttleMutex = Mutex()
    private val singleFlightMutex = Mutex()
    private val inFlightSearches = LinkedHashMap<String, CompletableDeferred<TradeMarketReferenceState>>()

    @Volatile
    private var sharedTradeBoardSnapshot: SharedTradeBoardSnapshot? = null

    @Volatile
    private var lastSearchAttemptAtMillis: Long = 0L

    @Volatile
    private var cooldownUntilEpochMillis: Long = 0L

    @Volatile
    private var rateLimitStrikes: Int = 0

    private val staleRetentionMillis: Long
        get() = maxOf(remoteCacheTtlMillis * 4L, DEFAULT_STALE_RETENTION_MILLIS)

    private val fallbackCacheTtlMillis: Long
        get() = minOf(remoteCacheTtlMillis, DEFAULT_FALLBACK_CACHE_TTL_MILLIS)

    override suspend fun analyzeLocal(
        query: String,
        includeBuyRequests: Boolean,
    ): TradeMarketReferenceState = analyzeLocalInternal(
        query = query,
        includeBuyRequests = includeBuyRequests,
        extraRemoteTopics = emptyList(),
    )

    private suspend fun analyzeLocalInternal(
        query: String,
        includeBuyRequests: Boolean,
        extraRemoteTopics: List<TopicSummary>,
    ): TradeMarketReferenceState {
        val cleanQuery = query.trim()
        val now = clockMillis()
        val cacheKey = buildQueryKey(cleanQuery)
        val cachedEntry = remoteCache[cacheKey]
        val freshRemote = cachedEntry?.takeIf { it.isFresh(now) }
        val retainedRemote = freshRemote ?: cachedEntry?.takeIf { it.isRetained(now, staleRetentionMillis) }
        val remoteTopics = buildList {
            retainedRemote?.topics?.let(::addAll)
            addAll(extraRemoteTopics)
        }.distinctBy { it.id }

        val sharedBoardTopics = sharedTradeBoardSnapshot
            ?.takeIf { it.isWithin(now, remoteCacheTtlMillis) }
            ?.topics
            .orEmpty()

        val candidates = collectLocalAndCachedCandidates(
            cachedRemoteTopics = remoteTopics,
            sharedBoardTopics = sharedBoardTopics,
        )
        val distribution = withContext(computeDispatcher) {
            TradeMarketPremiumAnalyzer.analyze(
                query = cleanQuery,
                candidates = candidates,
                includeBuyRequests = includeBuyRequests,
            )
        }

        val activeCooldownEnd = cooldownUntilEpochMillis.takeIf { it > now } ?: 0L
        val remainingSeconds = if (activeCooldownEnd > now) {
            ceil((activeCooldownEnd - now) / 1000.0).toInt().coerceAtLeast(1)
        } else {
            0
        }

        return TradeMarketReferenceState(
            query = cleanQuery,
            distribution = distribution,
            searchStatus = if (freshRemote != null) MarketSearchStatus.SUCCESS else MarketSearchStatus.IDLE,
            cooldownUntilEpochMillis = activeCooldownEnd,
            cooldownRemainingSeconds = remainingSeconds,
            statusMessage = null,
            lastRemoteSearchedAtMillis = retainedRemote?.fetchedAtMillis,
            hasRemoteResults = freshRemote != null,
        )
    }

    override suspend fun searchRemoteAndMerge(
        query: String,
        includeBuyRequests: Boolean,
    ): TradeMarketReferenceState {
        val cleanQuery = query.trim()
        val tokens = TradeMarketPremiumAnalyzer.tokenizeQuery(cleanQuery)
        if (cleanQuery.isEmpty() || tokens.isEmpty()) {
            return analyzeLocal(cleanQuery, includeBuyRequests).copy(
                searchStatus = MarketSearchStatus.IDLE,
                statusMessage = "请输入机型或商家关键词后再搜索交易版",
            )
        }

        val cacheKey = buildQueryKey(cleanQuery)
        val now = clockMillis()

        // 1. Fresh non-empty remote cache hit -> zero network call.
        remoteCache[cacheKey]?.takeIf { it.isFresh(now) }?.let { cached ->
            return analyzeLocal(cleanQuery, includeBuyRequests).copy(
                searchStatus = MarketSearchStatus.SUCCESS,
                lastRemoteSearchedAtMillis = cached.fetchedAtMillis,
                hasRemoteResults = true,
            )
        }

        // 2. Single-flight coalescing across concurrent requests for the same query.
        var isLeader = false
        val deferred = singleFlightMutex.withLock {
            val existing = inFlightSearches[cacheKey]
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                isLeader = true
                CompletableDeferred<TradeMarketReferenceState>().also { created ->
                    inFlightSearches[cacheKey] = created
                }
            }
        }

        if (!isLeader) {
            return deferred.await()
        }

        try {
            val result = executeRemoteSearchLeader(
                cleanQuery = cleanQuery,
                cacheKey = cacheKey,
                includeBuyRequests = includeBuyRequests,
            )
            deferred.complete(result)
            return result
        } catch (c: CancellationException) {
            deferred.cancel(c)
            throw c
        } catch (t: Throwable) {
            val local = analyzeLocal(cleanQuery, includeBuyRequests)
            val fallback = local.copy(
                searchStatus = MarketSearchStatus.FAILED,
                statusMessage = formatFriendlyFailureMessage(t, localSampleCount = local.distribution?.sampleCount ?: 0),
            )
            deferred.complete(fallback)
            return fallback
        } finally {
            singleFlightMutex.withLock {
                if (inFlightSearches[cacheKey] === deferred) {
                    inFlightSearches.remove(cacheKey)
                }
            }
        }
    }

    private suspend fun executeRemoteSearchLeader(
        cleanQuery: String,
        cacheKey: String,
        includeBuyRequests: Boolean,
    ): TradeMarketReferenceState {
        // Re-check cache / cooldown / min-interval under throttleMutex without holding lock across IO.
        val cachedHit: RemoteCacheEntry?
        val blockUntil: Long
        throttleMutex.withLock {
            val now = clockMillis()
            val fresh = remoteCache[cacheKey]?.takeIf { it.isFresh(now) }
            if (fresh != null) {
                cachedHit = fresh
                blockUntil = 0L
            } else if (cooldownUntilEpochMillis > now) {
                cachedHit = null
                blockUntil = cooldownUntilEpochMillis
            } else if (lastSearchAttemptAtMillis > 0L && now - lastSearchAttemptAtMillis < searchMinIntervalMillis) {
                cachedHit = null
                blockUntil = lastSearchAttemptAtMillis + searchMinIntervalMillis
            } else {
                lastSearchAttemptAtMillis = now
                cachedHit = null
                blockUntil = 0L
            }
        }

        if (cachedHit != null) {
            return analyzeLocal(cleanQuery, includeBuyRequests).copy(
                searchStatus = MarketSearchStatus.SUCCESS,
                lastRemoteSearchedAtMillis = cachedHit.fetchedAtMillis,
                hasRemoteResults = true,
            )
        }

        if (blockUntil > 0L) {
            val now = clockMillis()
            val remainSec = ceil((blockUntil - now).coerceAtLeast(1000L) / 1000.0).toInt().coerceAtLeast(1)
            return analyzeLocal(cleanQuery, includeBuyRequests).copy(
                searchStatus = MarketSearchStatus.COOLDOWN,
                cooldownUntilEpochMillis = blockUntil,
                cooldownRemainingSeconds = remainSec,
                statusMessage = "搜索冷却中，请在 ${remainSec}s 后重试（已展示本地缓存分析）",
            )
        }

        // Step 1: Primary sanitized trade search (page 1, optional page 2 when sparse & paginated).
        val primaryQuery = sanitizeRemoteSearchQuery(cleanQuery)
        val primaryOutcome = fetchSearchPages(
            searchQuery = primaryQuery,
            analysisQuery = cleanQuery,
            includeBuyRequests = includeBuyRequests,
        )

        var searchSucceeded = false
        var lastSearchError: Throwable? = null
        var collectedSearchTopics: List<TopicSummary> = emptyList()
        var usedSimplifiedQuery: String? = null

        when (primaryOutcome) {
            is SearchFetchOutcome.Success -> {
                searchSucceeded = true
                collectedSearchTopics = primaryOutcome.topics
            }
            is SearchFetchOutcome.Failure -> {
                if (isFatalNonFallbackFailure(primaryOutcome.error)) {
                    return handleRemoteFailure(cleanQuery, includeBuyRequests, primaryOutcome.error)
                }
                lastSearchError = primaryOutcome.error
            }
        }

        // Step 2: If primary search failed (e.g. HTTP 404 on complex/special-char query) or returned
        // 0 topics, retry once with a simplified core query (primary vendor + model tokens).
        if (collectedSearchTopics.isEmpty()) {
            val simplified = buildSimplifiedCoreQuery(cleanQuery, primaryQuery)
            if (simplified != null) {
                when (
                    val retryOutcome = fetchSearchPages(
                        searchQuery = simplified,
                        analysisQuery = cleanQuery,
                        includeBuyRequests = includeBuyRequests,
                    )
                ) {
                    is SearchFetchOutcome.Success -> {
                        searchSucceeded = true
                        if (retryOutcome.topics.isNotEmpty()) {
                            collectedSearchTopics = retryOutcome.topics
                            usedSimplifiedQuery = simplified
                        }
                    }
                    is SearchFetchOutcome.Failure -> {
                        if (isFatalNonFallbackFailure(retryOutcome.error)) {
                            return handleRemoteFailure(cleanQuery, includeBuyRequests, retryOutcome.error)
                        }
                        if (lastSearchError == null) {
                            lastSearchError = retryOutcome.error
                        }
                    }
                }
            }
        }

        // Outcome A: Remote search succeeded with non-empty topics.
        if (collectedSearchTopics.isNotEmpty()) {
            val doneAt = clockMillis()
            throttleMutex.withLock {
                rateLimitStrikes = 0
                cooldownUntilEpochMillis = 0L
            }
            remoteCache[cacheKey] = RemoteCacheEntry(
                fetchedAtMillis = doneAt,
                ttlMillis = remoteCacheTtlMillis,
                topics = collectedSearchTopics,
                isFromBoardFallback = false,
            )
            val msg = if (usedSimplifiedQuery != null) {
                "已用核心词「$usedSimplifiedQuery」合并交易版搜索结果（${collectedSearchTopics.size} 条帖子）"
            } else {
                "已合并交易版搜索结果（${collectedSearchTopics.size} 条帖子）"
            }
            return analyzeLocal(cleanQuery, includeBuyRequests).copy(
                searchStatus = MarketSearchStatus.SUCCESS,
                cooldownUntilEpochMillis = 0L,
                cooldownRemainingSeconds = 0,
                statusMessage = msg,
                lastRemoteSearchedAtMillis = doneAt,
                hasRemoteResults = true,
            )
        }

        // Step 3: Search either failed (e.g. HTTP 404) or returned 0 hits -> scan recent `/categories/trade` feed pages.
        val boardFallbackResult = scanRecentTradeBoardFallback(
            cleanQuery = cleanQuery,
            includeBuyRequests = includeBuyRequests,
        )

        val fallbackTopics = boardFallbackResult.getOrNull().orEmpty()
        if (fallbackTopics.isNotEmpty()) {
            val doneAt = clockMillis()
            throttleMutex.withLock {
                rateLimitStrikes = 0
                cooldownUntilEpochMillis = 0L
            }
            val matchedCount = countMatchedTopics(cleanQuery, fallbackTopics)
            if (matchedCount > 0) {
                // Short TTL for board fallback so recovery to `/search` happens promptly and
                // 0-match board scans never poison the per-query cache.
                remoteCache[cacheKey] = RemoteCacheEntry(
                    fetchedAtMillis = doneAt,
                    ttlMillis = fallbackCacheTtlMillis,
                    topics = fallbackTopics,
                    isFromBoardFallback = true,
                )
            }
            val analyzed = analyzeLocalInternal(
                query = cleanQuery,
                includeBuyRequests = includeBuyRequests,
                extraRemoteTopics = fallbackTopics,
            )
            val sampleCount = analyzed.distribution?.sampleCount ?: 0
            val msg = when {
                !searchSucceeded && sampleCount > 0 ->
                    "站内搜索暂不可用，已扫描最近交易版（${fallbackTopics.size} 条帖子）汇总 $sampleCount 条参考"
                !searchSucceeded ->
                    "站内搜索暂不可用，已扫描最近交易版（${fallbackTopics.size} 条帖子），暂未匹配到同款溢价"
                sampleCount > 0 ->
                    "站内搜索无直接命中，已从最近交易版补充 $sampleCount 条参考样本"
                else ->
                    "已搜索交易版并扫描最新帖子，暂未匹配到同款溢价记录"
            }
            return analyzed.copy(
                searchStatus = MarketSearchStatus.SUCCESS,
                cooldownUntilEpochMillis = 0L,
                cooldownRemainingSeconds = 0,
                statusMessage = msg,
                lastRemoteSearchedAtMillis = doneAt,
                hasRemoteResults = true,
            )
        }

        // Outcome C: `/search` itself succeeded with 200 (0 hits) and board scan had no extra topics.
        // Do NOT cache an empty list so transient 0-hit lookups don't poison `remoteCache`.
        if (searchSucceeded) {
            val doneAt = clockMillis()
            throttleMutex.withLock {
                rateLimitStrikes = 0
                cooldownUntilEpochMillis = 0L
            }
            return analyzeLocal(cleanQuery, includeBuyRequests).copy(
                searchStatus = MarketSearchStatus.SUCCESS,
                cooldownUntilEpochMillis = 0L,
                cooldownRemainingSeconds = 0,
                statusMessage = "已搜索交易版（暂未找到更多同款帖子）",
                lastRemoteSearchedAtMillis = doneAt,
                hasRemoteResults = true,
            )
        }

        // Outcome D: Both `/search` and `/categories/trade` fallback failed or returned empty.
        val fallbackError = boardFallbackResult.exceptionOrNull()
        if (fallbackError != null && isFatalNonFallbackFailure(fallbackError)) {
            return handleRemoteFailure(cleanQuery, includeBuyRequests, fallbackError)
        }
        return handleRemoteFailure(
            cleanQuery = cleanQuery,
            includeBuyRequests = includeBuyRequests,
            error = lastSearchError ?: fallbackError ?: NodeSeekApiException("搜索交易版失败"),
        )
    }

    private suspend fun fetchSearchPages(
        searchQuery: String,
        analysisQuery: String,
        includeBuyRequests: Boolean,
    ): SearchFetchOutcome {
        val page1Result = forumRepository.searchTopics(
            query = searchQuery,
            categorySlug = "trade",
            sort = FeedSort.NEWEST_TOPIC,
            page = 1,
        )
        val page1 = page1Result.getOrElse { err ->
            return SearchFetchOutcome.Failure(err)
        }

        val collected = LinkedHashMap<Long, TopicSummary>()
        for (topic in page1.pinnedTopics + page1.topics) {
            if (topic.id > 0L) collected.putIfAbsent(topic.id, topic)
        }

        // Fetch page 2 only when page 1 signals more pages AND yielded sparse spread samples.
        if (page1.hasNextPage && collected.isNotEmpty()) {
            val page1Spreads = countSpreadSamples(analysisQuery, collected.values.toList(), includeBuyRequests)
            if (page1Spreads < TARGET_SEARCH_SPREAD_SAMPLES) {
                val page2Result = forumRepository.searchTopics(
                    query = searchQuery,
                    categorySlug = "trade",
                    sort = FeedSort.NEWEST_TOPIC,
                    page = 2,
                )
                page2Result.onSuccess { page2 ->
                    for (topic in page2.pinnedTopics + page2.topics) {
                        if (topic.id > 0L) collected.putIfAbsent(topic.id, topic)
                    }
                }.onFailure { err ->
                    // Treat page > 1 HTTP 404 as clean end-of-pagination; preserve page 1 on any partial error.
                    val apiErr = extractApiException(err)
                    if (apiErr != null && (apiErr.statusCode == 429 || apiErr.statusCode == 503)) {
                        recordRateLimitCooldown(apiErr)
                    }
                }
            }
        }

        return SearchFetchOutcome.Success(collected.values.toList())
    }

    private suspend fun scanRecentTradeBoardFallback(
        cleanQuery: String,
        includeBuyRequests: Boolean,
    ): Result<List<TopicSummary>> {
        val now = clockMillis()
        sharedTradeBoardSnapshot?.takeIf { it.isWithin(now, SHARED_TRADE_BOARD_FRESH_TTL_MILLIS) }?.let { cached ->
            return Result.success(cached.topics)
        }

        val collected = LinkedHashMap<Long, TopicSummary>()
        var primarySort = FeedSort.NEWEST_TOPIC

        val page1Primary = forumRepository.getFeed(
            categorySlug = "trade",
            sort = primarySort,
            page = 1,
            forceRefresh = false,
        )
        val firstPage = page1Primary.getOrElse { err ->
            if (isFatalNonFallbackFailure(err)) {
                return Result.failure(err)
            }
            primarySort = FeedSort.LATEST_REPLY
            val backupPage1 = forumRepository.getFeed(
                categorySlug = "trade",
                sort = primarySort,
                page = 1,
                forceRefresh = false,
            )
            backupPage1.getOrElse { backupErr ->
                return Result.failure(if (isFatalNonFallbackFailure(backupErr)) backupErr else err)
            }
        }

        for (topic in firstPage.pinnedTopics + firstPage.topics) {
            if (topic.id > 0L && isTradeCategoryTopic(topic)) {
                collected.putIfAbsent(topic.id, topic)
            }
        }

        var hasNextPage = firstPage.hasNextPage
        var page = 2
        while (hasNextPage && page <= TRADE_BOARD_FALLBACK_MAX_PAGES) {
            if (countSpreadSamples(cleanQuery, collected.values.toList(), includeBuyRequests) >= TARGET_FALLBACK_SPREAD_SAMPLES) {
                break
            }
            val nextPageResult = forumRepository.getFeed(
                categorySlug = "trade",
                sort = primarySort,
                page = page,
                forceRefresh = false,
            )
            val nextPage = nextPageResult.getOrElse { err ->
                // Treat page > 1 HTTP 404 or partial error as clean end-of-pagination; keep collected pages.
                val apiErr = extractApiException(err)
                if (apiErr != null && (apiErr.statusCode == 429 || apiErr.statusCode == 503)) {
                    recordRateLimitCooldown(apiErr)
                }
                break
            }
            val beforeSize = collected.size
            for (topic in nextPage.pinnedTopics + nextPage.topics) {
                if (topic.id > 0L && isTradeCategoryTopic(topic)) {
                    collected.putIfAbsent(topic.id, topic)
                }
            }
            if (collected.size == beforeSize) {
                break
            }
            hasNextPage = nextPage.hasNextPage
            page++
        }

        val topics = collected.values.take(MAX_SHARED_TRADE_BOARD_TOPICS)
        if (topics.isNotEmpty()) {
            sharedTradeBoardSnapshot = SharedTradeBoardSnapshot(
                fetchedAtMillis = clockMillis(),
                topics = topics,
            )
        }
        return Result.success(topics)
    }

    private suspend fun recordRateLimitCooldown(apiErr: NodeSeekApiException): Long {
        val now = clockMillis()
        return throttleMutex.withLock {
            rateLimitStrikes++
            val retryAfterMs = apiErr.retryAfterSeconds?.takeIf { it > 0 }?.times(1000L)
            val shift = (rateLimitStrikes - 1).coerceIn(0, 6)
            val expMs = (baseCooldownMillis * (1L shl shift)).coerceAtMost(maxCooldownMillis)
            val appliedMs = maxOf(retryAfterMs ?: 0L, expMs)
                .coerceIn(1_000L, maxCooldownMillis)
            val until = now + appliedMs
            cooldownUntilEpochMillis = maxOf(cooldownUntilEpochMillis, until)
            cooldownUntilEpochMillis
        }
    }

    private suspend fun handleRemoteFailure(
        cleanQuery: String,
        includeBuyRequests: Boolean,
        error: Throwable,
    ): TradeMarketReferenceState {
        val localState = analyzeLocal(cleanQuery, includeBuyRequests)
        val apiErr = extractApiException(error)

        if (apiErr != null && (apiErr.statusCode == 429 || apiErr.statusCode == 503)) {
            val now = clockMillis()
            val cooldownEnd = recordRateLimitCooldown(apiErr)
            val remainSec = ceil((cooldownEnd - now).coerceAtLeast(1000L) / 1000.0).toInt().coerceAtLeast(1)
            return localState.copy(
                searchStatus = MarketSearchStatus.RATE_LIMITED,
                cooldownUntilEpochMillis = cooldownEnd,
                cooldownRemainingSeconds = remainSec,
                statusMessage = "全站搜索请求过于频繁，请 ${remainSec}s 后再试（已保留本地参考）",
            )
        }

        if (isChallengeError(error)) {
            return localState.copy(
                searchStatus = MarketSearchStatus.CHALLENGE_REQUIRED,
                statusMessage = "需要通过浏览器安全验证后才可执行全站搜索",
            )
        }

        if (isSignInError(error)) {
            return localState.copy(
                searchStatus = MarketSearchStatus.SIGN_IN_REQUIRED,
                statusMessage = "全站搜索需要登录 NodeSeek 账号",
            )
        }

        val localSamples = localState.distribution?.sampleCount ?: 0
        return localState.copy(
            searchStatus = MarketSearchStatus.FAILED,
            statusMessage = formatFriendlyFailureMessage(error, localSamples),
        )
    }

    private fun formatFriendlyFailureMessage(error: Throwable, localSampleCount: Int): String {
        val apiErr = extractApiException(error)
        return when {
            apiErr?.statusCode == 404 && localSampleCount > 0 ->
                "官网搜索暂不可用（404），已保留现有 $localSampleCount 条参考样本"
            apiErr?.statusCode == 404 ->
                "官网搜索暂不可用（404），可尝试简化为「商家 + 型号」后重试"
            localSampleCount > 0 ->
                error.message?.takeIf { it.isNotBlank() }?.let { "$it（已保留现有 $localSampleCount 条参考）" }
                    ?: "搜索交易版失败，已保留现有 $localSampleCount 条参考样本"
            else ->
                error.message?.takeIf { it.isNotBlank() } ?: "搜索交易版失败，已保留本地参考"
        }
    }

    private suspend fun countSpreadSamples(
        query: String,
        topics: List<TopicSummary>,
        includeBuyRequests: Boolean,
    ): Int {
        if (topics.isEmpty()) return 0
        val candidates = topics.map { topic ->
            TradeCandidateTopic(
                topicId = topic.id,
                title = topic.title,
                source = MarketDataSource.REMOTE_SEARCH,
                authorName = topic.authorName,
                activityHint = topic.lastActivityIso,
            )
        }
        return withContext(computeDispatcher) {
            TradeMarketPremiumAnalyzer.analyze(
                query = query,
                candidates = candidates,
                includeBuyRequests = includeBuyRequests,
            ).sampleCount
        }
    }

    private suspend fun countMatchedTopics(
        query: String,
        topics: List<TopicSummary>,
    ): Int {
        if (topics.isEmpty()) return 0
        return withContext(computeDispatcher) {
            topics.count { TradeMarketPremiumAnalyzer.matchesQuery(it.title, query) }
        }
    }

    private suspend fun collectLocalAndCachedCandidates(
        cachedRemoteTopics: List<TopicSummary>,
        sharedBoardTopics: List<TopicSummary> = emptyList(),
    ): List<TradeCandidateTopic> = withContext(ioDispatcher) {
        val candidates = ArrayList<TradeCandidateTopic>()

        runCatching {
            forumRepository.getLocalCachedTopics("trade")
        }.getOrDefault(emptyList()).forEach { topic ->
            candidates += TradeCandidateTopic(
                topicId = topic.id,
                title = topic.title,
                source = MarketDataSource.FEED_CACHE,
                authorName = topic.authorName,
                activityHint = topic.lastActivityIso,
            )
        }

        val remoteTopicIds = cachedRemoteTopics.mapTo(HashSet()) { it.id }
        sharedBoardTopics.forEach { topic ->
            if (topic.id !in remoteTopicIds) {
                candidates += TradeCandidateTopic(
                    topicId = topic.id,
                    title = topic.title,
                    source = MarketDataSource.FEED_CACHE,
                    authorName = topic.authorName,
                    activityHint = topic.lastActivityIso,
                )
            }
        }

        cachedRemoteTopics.forEach { topic ->
            candidates += TradeCandidateTopic(
                topicId = topic.id,
                title = topic.title,
                source = MarketDataSource.REMOTE_SEARCH,
                authorName = topic.authorName,
                activityHint = topic.lastActivityIso,
            )
        }

        runCatching {
            bookmarkAndHistoryRepository.observeLocalBookmarks().first()
        }.getOrDefault(emptyList()).forEach { bookmark ->
            val summary = bookmark.summary
            val isTrade = summary.categorySlug.equals("trade", ignoreCase = true) ||
                summary.categoryName.trim() == "交易" ||
                (summary.categorySlug.isBlank() && summary.categoryName.isBlank() && TrustRiskEvaluator.isTradeTopic(summary))
            if (isTrade) {
                candidates += TradeCandidateTopic(
                    topicId = bookmark.topicId,
                    title = summary.title,
                    source = MarketDataSource.BOOKMARK,
                    authorName = summary.authorName,
                    activityHint = summary.lastActivityIso,
                )
            }
        }

        runCatching {
            bookmarkAndHistoryRepository.observeReadHistory().first()
        }.getOrDefault(emptyList()).forEach { entry ->
            val summaryStub = TopicSummary(
                id = entry.topicId,
                title = entry.title,
                categorySlug = entry.categorySlug,
                categoryName = entry.categoryName,
                authorName = entry.authorName,
            )
            val isTrade = entry.categorySlug.equals("trade", ignoreCase = true) ||
                entry.categoryName.trim() == "交易" ||
                (entry.categorySlug.isBlank() && entry.categoryName.isBlank() && TrustRiskEvaluator.isTradeTopic(summaryStub))
            if (isTrade) {
                candidates += TradeCandidateTopic(
                    topicId = entry.topicId,
                    title = entry.title,
                    source = MarketDataSource.READ_HISTORY,
                    authorName = entry.authorName,
                )
            }
        }

        candidates
    }

    private fun isTradeCategoryTopic(topic: TopicSummary): Boolean {
        val slug = topic.categorySlug.trim()
        val name = topic.categoryName.trim()
        return slug.equals("trade", ignoreCase = true) ||
            name == "交易" ||
            (slug.isEmpty() && name.isEmpty())
    }

    private fun extractApiException(error: Throwable): NodeSeekApiException? =
        (error as? NodeSeekApiException) ?: (error.cause as? NodeSeekApiException)

    private fun isChallengeError(error: Throwable): Boolean =
        error is TopicAccessException.CloudflareChallengeRequired ||
            error.cause is TopicAccessException.CloudflareChallengeRequired ||
            error is CloudflareChallengeException ||
            error.cause is CloudflareChallengeException

    private fun isSignInError(error: Throwable): Boolean =
        error is TopicAccessException.SignInRequired ||
            error.cause is TopicAccessException.SignInRequired

    private fun isFatalNonFallbackFailure(error: Throwable): Boolean {
        val apiErr = extractApiException(error)
        return (apiErr != null && (apiErr.statusCode == 429 || apiErr.statusCode == 503)) ||
            isChallengeError(error) ||
            isSignInError(error)
    }

    private fun buildQueryKey(cleanQuery: String): String {
        val tokens = TradeMarketPremiumAnalyzer.tokenizeQuery(cleanQuery)
        return if (tokens.isNotEmpty()) tokens.joinToString(" ") else cleanQuery.lowercase()
    }

    companion object {
        const val DEFAULT_SEARCH_MIN_INTERVAL_MILLIS: Long = 10_000L
        const val DEFAULT_BASE_COOLDOWN_MILLIS: Long = 30_000L
        const val DEFAULT_MAX_COOLDOWN_MILLIS: Long = 300_000L
        const val DEFAULT_REMOTE_CACHE_TTL_MILLIS: Long = 15 * 60_000L
        internal const val DEFAULT_FALLBACK_CACHE_TTL_MILLIS: Long = 3 * 60_000L
        internal const val DEFAULT_STALE_RETENTION_MILLIS: Long = 60 * 60_000L
        internal const val SHARED_TRADE_BOARD_FRESH_TTL_MILLIS: Long = 3 * 60_000L
        internal const val TRADE_BOARD_FALLBACK_MAX_PAGES: Int = 3
        private const val MAX_SHARED_TRADE_BOARD_TOPICS: Int = 200
        private const val TARGET_SEARCH_SPREAD_SAMPLES: Int = 3
        private const val TARGET_FALLBACK_SPREAD_SAMPLES: Int = 5
        private const val MAX_SEARCH_QUERY_LENGTH: Int = 64

        private val BRACKETED_TRADE_PREFIX_REGEX = Regex(
            """[\[【]\s*(?:出|收|求购|转|转让|已出|已售|已成交|秒出|sold|closed)\s*[\]】]""",
            RegexOption.IGNORE_CASE,
        )
        private val SEARCH_HOSTILE_CHARS_REGEX = Regex(
            """[\u0000-\u001F\u007F\[\]【】()（）{}<>/\\\u0024¥€£:：;；,，。!！?？#&=*~|"'`^%@·、]+""",
        )
        private val DOTTED_MODEL_SEPARATOR_REGEX = Regex("""(?<=[A-Za-z]{2})\.(?=[A-Za-z]{2})""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        private val GENERIC_MODEL_AND_REGION_TOKENS: Set<String> = setOf(
            "lax", "la", "losangeles", "hkg", "hk", "jp", "tyo", "tokyo", "sg", "sin",
            "us", "uk", "de", "nl", "fr", "kr", "tw", "美西", "美东", "洛杉矶", "香港",
            "日本", "东京", "新加坡", "美国", "德国", "英国",
            "pro", "eb", "lite", "mini", "micro", "tiny", "nano", "plus", "max", "ultra",
            "basic", "standard", "premium", "v1", "v2", "v3", "t1", "co", "vol",
            "cn2", "gia", "cmin2", "9929", "4837", "163", "iij", "softbank", "sb", "bgp",
            "dc6", "dc9", "dc02", "dc2", "dc3", "dc4", "plan",
        )

        /**
         * Sanitizes a market query before sending to NodeSeek `/search`:
         * removes bracketed trade markers and search-hostile punctuation (while preserving `+`
         * for `%2B` wire encoding and short brand/version dots like `V.PS`, `G9.5`), and splits
         * dotted multi-letter product codes such as `LAX.Pro.WEE` into space-separated tokens.
         */
        internal fun sanitizeRemoteSearchQuery(rawQuery: String): String {
            val normalized = normalizeFullwidthPreservingCase(rawQuery.trim())
            val cleaned = normalized
                .replace(BRACKETED_TRADE_PREFIX_REGEX, " ")
                .replace(SEARCH_HOSTILE_CHARS_REGEX, " ")
                .replace(DOTTED_MODEL_SEPARATOR_REGEX, " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
                .trim('.', '-')
                .trim()
            if (cleaned.isNotEmpty()) {
                return cleaned.take(MAX_SEARCH_QUERY_LENGTH).trim()
            }
            return TradeMarketPremiumAnalyzer.tokenizeQuery(rawQuery)
                .take(3)
                .joinToString(" ")
                .take(MAX_SEARCH_QUERY_LENGTH)
                .trim()
        }

        /**
         * Builds a simplified core fallback query (typically primary vendor + distinctive model
         * token) when a complex or overly specific query returns HTTP 404 or 0 results.
         */
        internal fun buildSimplifiedCoreQuery(
            cleanQuery: String,
            primarySearchQuery: String,
        ): String? {
            val tokens = TradeMarketPremiumAnalyzer.tokenizeQuery(cleanQuery)
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return null

            val nonPureNumeric = tokens.filterNot { tok -> tok.all { it.isDigit() } }
            val effectiveTokens = nonPureNumeric.ifEmpty { tokens }

            val candidate = when {
                effectiveTokens.size >= 3 -> {
                    val first = effectiveTokens.first()
                    val distinctive = effectiveTokens
                        .drop(1)
                        .firstOrNull { tok -> tok !in GENERIC_MODEL_AND_REGION_TOKENS && tok.length >= 2 }
                        ?: effectiveTokens[1]
                    "$first $distinctive"
                }
                effectiveTokens.size == 2 -> {
                    val first = effectiveTokens[0]
                    val second = effectiveTokens[1]
                    val cleanPair = "$first $second"
                    if (!cleanPair.equals(primarySearchQuery, ignoreCase = true)) {
                        cleanPair
                    } else if (
                        second !in GENERIC_MODEL_AND_REGION_TOKENS &&
                        second.length >= 4 &&
                        first.any { it.code >= 128 }
                    ) {
                        second
                    } else if (first.length >= 2) {
                        first
                    } else if (second.length >= 2) {
                        second
                    } else {
                        null
                    }
                }
                else -> {
                    val only = effectiveTokens.first()
                    if (!only.equals(primarySearchQuery, ignoreCase = true) && only.length >= 2) {
                        only
                    } else {
                        null
                    }
                }
            }?.trim()

            if (candidate.isNullOrBlank() || candidate.equals(primarySearchQuery, ignoreCase = true)) {
                return null
            }
            return candidate
        }

        private fun normalizeFullwidthPreservingCase(raw: String): String {
            if (raw.isEmpty()) return ""
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                val mapped = when (ch) {
                    '＋' -> '+'
                    '－' -> '-'
                    '．' -> '.'
                    in '０'..'９' -> ('0'.code + (ch.code - '０'.code)).toChar()
                    in 'Ａ'..'Ｚ' -> ('A'.code + (ch.code - 'Ａ'.code)).toChar()
                    in 'ａ'..'ｚ' -> ('a'.code + (ch.code - 'ａ'.code)).toChar()
                    else -> ch
                }
                sb.append(mapped)
            }
            return sb.toString()
        }
    }
}
