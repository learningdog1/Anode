package com.nodeseek.app.data.trust

import android.content.Context
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.SameOriginTransport
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.parser.ConfigPayloadDto
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.trust.ExposureCandidate
import com.nodeseek.app.domain.trust.ExposureCheckScope
import com.nodeseek.app.domain.trust.ExposureHitTopic
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustFetchStatus
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.net.SocketTimeoutException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Resilient [SellerTrustRepository] aggregating seller profile, recent trade topics,
 * a shared page-1 `/categories/expose` board snapshot (zero `/search` traffic by default),
 * optional on-demand single-query deep `/search` verification, and persistent device-local
 * seller notes (`filesDir/seller_trust_notes.json`).
 *
 * Stability & anti-rate-limit guarantees:
 * - **Zero `/search` calls on default inspection**: Default card loads match against a shared
 *   30-minute `/categories/expose` board snapshot (or existing `ForumRepository` feed cache),
 *   preventing NodeSeek `/search` 429 rate-limits across sellers.
 * - **On-demand single-query deep search**: Explicit user-triggered [searchSellerExposureDeep]
 *   issues at most 1 `/search` call governed by an isolated [searchRateLimiter] with a 10s
 *   minimum interval and separate 30s+ cooldown bucket so `/search` rate limits never block
 *   profile/topic checks.
 * - **Two-tier cache**: Memory LRU + persistent disk JSON (`seller_trust_cache_v1.json`) with
 *   long positive TTL (default 18h), medium partial TTL (15m), short negative TTL (3m) for
 *   failed/empty results, and 7-day stale positive fallback during rate-limit cooldowns.
 * - **Single-flight deduplication**: Concurrent calls for the same seller key coalesce into one pipeline.
 * - **Global concurrency cap & per-site pacing**: Outbound requests are bounded (`maxGlobalConcurrency = 2`)
 *   and spaced (`minSiteIntervalMillis = 350ms`) via [SellerTrustRateLimiter].
 * - **429 / 503 / timeout backoff & cooldown**: Respects `Retry-After` (delta-seconds & HTTP-date),
 *   applies exponential backoff + jitter, enters cooldown on rate limits, and serves cached or
 *   structured retry-later states without hammering the origin server.
 * - **Minimal payload path**: Fetches lightweight `/api/account/getInfo/{uid}` (without `readme=1`
 *   or `/api/block-list/list`) first to resolve canonical username and skips
 *   `/api/content/list-discussions` when `nPost == 0`.
 */
class SellerTrustRepositoryImpl internal constructor(
    private val remoteDataSource: SellerTrustRemoteDataSource,
    notesFile: File,
    cacheFile: File = defaultCacheFileFor(notesFile),
    exposeBoardFile: File = defaultExposeBoardFileFor(cacheFile),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val partialCacheTtlMillis: Long = DEFAULT_PARTIAL_CACHE_TTL_MILLIS,
    private val negativeCacheTtlMillis: Long = DEFAULT_NEGATIVE_CACHE_TTL_MILLIS,
    private val manualRefreshMinIntervalMillis: Long = DEFAULT_MANUAL_REFRESH_MIN_INTERVAL_MILLIS,
    private val failedRetryMinIntervalMillis: Long = DEFAULT_FAILED_RETRY_MIN_INTERVAL_MILLIS,
    private val exposeBoardTtlMillis: Long = DEFAULT_EXPOSE_BOARD_TTL_MILLIS,
    private val exposeBoardRefreshMinIntervalMillis: Long = DEFAULT_EXPOSE_BOARD_REFRESH_MIN_INTERVAL_MILLIS,
    private val exposeBoardStaleRetentionMillis: Long = DEFAULT_EXPOSE_BOARD_STALE_RETENTION_MILLIS,
    private val searchMinIntervalMillis: Long = DEFAULT_SEARCH_MIN_INTERVAL_MILLIS,
    private val maxAutoTopicPages: Int = DEFAULT_MAX_AUTO_TOPIC_PAGES,
    private val scanMoreTopicPages: Int = DEFAULT_SCAN_MORE_TOPIC_PAGES,
    maxMemoryCacheEntries: Int = SellerTrustCacheStore.DEFAULT_MAX_MEMORY_ENTRIES,
    maxDiskCacheEntries: Int = SellerTrustCacheStore.DEFAULT_MAX_DISK_ENTRIES,
    internal val rateLimiter: SellerTrustRateLimiter = SellerTrustRateLimiter(
        clockMillis = clockMillis,
    ),
    internal val searchRateLimiter: SellerTrustRateLimiter = SellerTrustRateLimiter(
        maxGlobalConcurrency = 1,
        minSiteIntervalMillis = 0L,
        maxTransientRetries = 0,
        baseCooldownMillis = DEFAULT_SEARCH_BASE_COOLDOWN_MILLIS,
        overloadBaseCooldownMillis = DEFAULT_SEARCH_BASE_COOLDOWN_MILLIS,
        clockMillis = clockMillis,
    ),
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
) : SellerTrustRepository {

    constructor(
        userRepository: UserRepository,
        forumRepository: ForumRepository,
        notesFile: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        clockMillis: () -> Long = System::currentTimeMillis,
        cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
    ) : this(
        remoteDataSource = RepositorySellerTrustRemoteDataSource(
            userRepository = userRepository,
            forumRepository = forumRepository,
        ),
        notesFile = notesFile,
        cacheFile = defaultCacheFileFor(notesFile),
        ioDispatcher = ioDispatcher,
        clockMillis = clockMillis,
        cacheTtlMillis = cacheTtlMillis,
        zoneId = zoneId,
    )

    constructor(
        context: Context,
        userRepository: UserRepository,
        forumRepository: ForumRepository,
    ) : this(
        userRepository = userRepository,
        forumRepository = forumRepository,
        notesFile = File((context.applicationContext ?: context).filesDir, NOTES_FILE_NAME),
    )

    private data class QueuedInspectTarget(
        val key: String,
        val uid: Long,
        val usernameHint: String,
    )

    internal val cacheStore = SellerTrustCacheStore(
        cacheFile = cacheFile,
        ioDispatcher = ioDispatcher,
        clockMillis = clockMillis,
        maxMemoryEntries = maxMemoryCacheEntries,
        maxDiskEntries = maxDiskCacheEntries,
    )

    private val noteStore = SellerTrustNoteStore(
        notesFile = notesFile,
        ioDispatcher = ioDispatcher,
        clockMillis = clockMillis,
    )

    private val exposeBoardStore = ExposeBoardSnapshotStore(
        snapshotFile = exposeBoardFile,
        ioDispatcher = ioDispatcher,
        clockMillis = clockMillis,
        freshTtlMillis = exposeBoardTtlMillis,
        staleRetentionMillis = exposeBoardStaleRetentionMillis,
    )

    private val singleFlight = KeyedSingleFlight<String, SellerTrustReport>(ioDispatcher)
    private val exposeBoardSingleFlight = KeyedSingleFlight<String, Result<FeedPageResult>>(ioDispatcher)
    private val deepSearchSingleFlight = KeyedSingleFlight<String, SellerTrustReport>(ioDispatcher)
    private val topicScanSingleFlight = KeyedSingleFlight<String, SellerTrustReport>(ioDispatcher)
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val attemptMutex = Mutex()
    private val lastRemoteAttemptByKey = HashMap<String, Long>()
    private val scannedTopicsMutex = Mutex()
    private val scannedTopicIdsByUid = HashMap<Long, MutableSet<Long>>()
    private val scannedTradeTopicIdsByUid = HashMap<Long, MutableSet<Long>>()

    private val deepSearchGateMutex = Mutex()
    private var lastDeepSearchAttemptAtMillis: Long = 0L

    private val queueMutex = Mutex()
    private val pendingQueue = LinkedHashSet<QueuedInspectTarget>()
    private var queueWorkerJob: Job? = null

    internal val usesDedicatedHttpDataSource: Boolean
        get() = remoteDataSource is HttpSellerTrustRemoteDataSource

    override suspend fun inspectSeller(
        uid: Long,
        usernameHint: String,
        forceRefresh: Boolean,
    ): SellerTrustReport = withContext(ioDispatcher) {
        val cleanHint = sanitizeUsernameHint(usernameHint)
        val initialCacheKey = buildCacheKey(uid = uid, username = cleanHint)
        val now = clockMillis()

        if (initialCacheKey.isEmpty()) {
            return@withContext buildUnavailableReport(
                uid = 0L,
                username = "未知卖家",
                localNote = null,
                nowEpochMillis = now,
                fetchStatus = TrustFetchStatus.FAILED_RETRYABLE,
                cooldownUntilEpochMillis = 0L,
                failureMessage = "缺少有效的卖家 UID 或用户名",
            )
        }

        val candidateKeys = buildCandidateKeys(uid = uid, cleanHint = cleanHint)
        val siteHost = remoteDataSource.siteHost

        // 1. Active site rate-limit / overload cooldown check (applies even to manual forceRefresh).
        val activeCooldownUntil = rateLimiter.getActiveCooldownUntil(siteHost, now)
        if (activeCooldownUntil > now) {
            return@withContext resolveCooldownResponse(
                uid = uid,
                cleanHint = cleanHint,
                rawUsernameHint = usernameHint,
                candidateKeys = candidateKeys,
                cooldownUntil = activeCooldownUntil,
                nowMillis = now,
            )
        }

        // 2. Cache check when not forcing refresh (memory LRU + persistent disk cache, incl. negative cache).
        if (!forceRefresh) {
            findFreshCachedEntry(candidateKeys, now)?.let { entry ->
                return@withContext materializeCachedEntry(entry)
            }
        } else {
            // 3. Manual refresh throttle protection: prevent rapid "刷新核验" spam from hammering the site.
            val freshCached = findFreshCachedEntry(candidateKeys, now)
            val lastAttemptAt = getLastRemoteAttemptMillis(candidateKeys)
            val referenceAt = maxOf(lastAttemptAt, freshCached?.cachedAtEpochMillis ?: 0L)
            val hasPositiveCache = freshCached != null && !freshCached.isNegative && freshCached.report.anyRemoteSucceeded
            val minRefreshInterval = if (hasPositiveCache) {
                manualRefreshMinIntervalMillis
            } else {
                failedRetryMinIntervalMillis
            }

            if (referenceAt > 0L && now >= referenceAt && (now - referenceAt) < minRefreshInterval) {
                val throttleUntil = referenceAt + minRefreshInterval
                val remainingSec = ((throttleUntil - now + 999L) / 1_000L).coerceAtLeast(1L)
                if (freshCached != null) {
                    val base = materializeCachedEntry(freshCached)
                    return@withContext base.copy(
                        cooldownUntilEpochMillis = throttleUntil,
                        isRefreshThrottled = true,
                        failureMessage = "手动刷新受节流保护，请 ${remainingSec} 秒后再试",
                    )
                }
                val fallbackStale = cacheStore.getStalePositiveFallback(candidateKeys, now)
                if (fallbackStale != null) {
                    val synced = syncWithLatestNote(fallbackStale.report, uid)
                    return@withContext synced.copy(
                        isFromCache = true,
                        fetchStatus = TrustFetchStatus.STALE_CACHE_FALLBACK,
                        cooldownUntilEpochMillis = throttleUntil,
                        isRefreshThrottled = true,
                        failureMessage = "手动刷新受节流保护，请 ${remainingSec} 秒后再试",
                    )
                }
                val note = if (uid > 0L) noteStore.getNote(uid) else null
                return@withContext buildUnavailableReport(
                    uid = uid,
                    username = fallbackDisplayName(uid, usernameHint),
                    localNote = note,
                    nowEpochMillis = now,
                    fetchStatus = TrustFetchStatus.RATE_LIMITED,
                    cooldownUntilEpochMillis = throttleUntil,
                    failureMessage = "重试过于频繁，请 ${remainingSec} 秒后再试",
                    isRefreshThrottled = true,
                )
            }
        }

        // 4. Coalesce concurrent requests for the same seller key (in-flight deduplication).
        val flightReport = singleFlight.execute(initialCacheKey) {
            executeRemoteInspection(
                uid = uid,
                cleanHint = cleanHint,
                rawUsernameHint = usernameHint,
                initialCacheKey = initialCacheKey,
                forceRefresh = forceRefresh,
            )
        }

        syncWithLatestNote(flightReport, flightReport.uid.takeIf { it > 0L } ?: uid)
    }

    override suspend fun searchSellerExposureDeep(
        uid: Long,
        usernameHint: String,
    ): SellerTrustReport = withContext(ioDispatcher) {
        val cleanHint = sanitizeUsernameHint(usernameHint)
        val baseReport = getCachedReportOnly(uid = uid, usernameHint = usernameHint)
            ?: inspectSeller(uid = uid, usernameHint = usernameHint, forceRefresh = false)

        val targetUsername = baseReport.username.trim()
            .takeUnless { PLACEHOLDER_USER_REGEX.matches(it) }
            ?: cleanHint
        if (targetUsername.isEmpty()) {
            return@withContext baseReport.copy(
                deepSearchNoticeMessage = "未获取到卖家用户名，无法执行历史曝光深搜",
            )
        }

        val siteHost = remoteDataSource.siteHost
        val now = clockMillis()
        val preBlockUntil = getDeepSearchBlockUntil(siteHost, now)
        if (preBlockUntil > now) {
            val remSec = ((preBlockUntil - now + 999L) / 1_000L).coerceAtLeast(1L)
            return@withContext baseReport.copy(
                deepSearchCooldownUntilEpochMillis = preBlockUntil,
                deepSearchNoticeMessage = "为避免触发官网搜索限流，请 ${remSec} 秒后再试（也可使用下方 Google 查曝光）",
            )
        }

        val effectiveUid = baseReport.uid.takeIf { it > 0L } ?: uid
        val flightKey = buildCacheKey(uid = effectiveUid, username = targetUsername)
            .ifEmpty { "deep:${targetUsername.lowercase()}" }

        val updatedReport = deepSearchSingleFlight.execute(flightKey) {
            executeDeepExposureSearch(
                baseReport = baseReport,
                targetUid = effectiveUid,
                targetUsername = targetUsername,
            )
        }

        syncWithLatestNote(updatedReport, effectiveUid)
    }

    override suspend fun scanMoreSellerTradeTopics(
        uid: Long,
        usernameHint: String,
    ): SellerTrustReport = withContext(ioDispatcher) {
        val baseReport = getCachedReportOnly(uid = uid, usernameHint = usernameHint)
            ?: inspectSeller(uid = uid, usernameHint = usernameHint, forceRefresh = false)

        val effectiveUid = baseReport.uid.takeIf { it > 0L } ?: uid
        if (effectiveUid <= 0L || !baseReport.topicsSucceeded || !baseReport.hasMoreTopicPages) {
            return@withContext baseReport.copy(
                topicScanCooldownUntilEpochMillis = 0L,
                topicScanNoticeMessage = null,
            )
        }

        val siteHost = remoteDataSource.siteHost
        val now = clockMillis()
        val activeCooldown = rateLimiter.getActiveCooldownUntil(siteHost, now)
        if (activeCooldown > now) {
            val remSec = ((activeCooldown - now + 999L) / 1_000L).coerceAtLeast(1L)
            return@withContext baseReport.copy(
                topicScanCooldownUntilEpochMillis = activeCooldown,
                topicScanNoticeMessage = "官网限流冷却中，请约 ${remSec} 秒后再继续扫描",
            )
        }

        val updated = topicScanSingleFlight.execute("topics:$effectiveUid") {
            executeScanMoreTopics(
                fallbackBase = baseReport,
                targetUid = effectiveUid,
            )
        }
        syncWithLatestNote(updated, effectiveUid)
    }

    override fun observeCachedReport(uid: Long): Flow<SellerTrustReport?> =
        cacheStore.observePositiveReportByUid(uid)

    override fun observeCachedReport(
        uid: Long,
        usernameHint: String,
    ): Flow<SellerTrustReport?> =
        cacheStore.observePositiveReport(uid = uid, usernameHint = sanitizeUsernameHint(usernameHint))

    override suspend fun getCachedReportOnly(
        uid: Long,
        usernameHint: String,
    ): SellerTrustReport? {
        val cleanHint = sanitizeUsernameHint(usernameHint)
        val keys = buildCandidateKeys(uid = uid, cleanHint = cleanHint)
        if (keys.isEmpty()) return null
        val now = clockMillis()
        val fresh = findFreshCachedEntry(keys, now) ?: return null
        if (fresh.isNegative || !fresh.report.anyRemoteSucceeded) return null
        return materializeCachedEntry(fresh)
    }

    override fun enqueueInspect(
        uid: Long,
        usernameHint: String,
    ) {
        val cleanHint = sanitizeUsernameHint(usernameHint)
        val key = buildCacheKey(uid = uid, username = cleanHint)
        if (key.isEmpty()) return

        backgroundScope.launch {
            queueMutex.withLock {
                if (pendingQueue.size >= MAX_PREFETCH_QUEUE_SIZE) {
                    return@withLock
                }
                pendingQueue.add(QueuedInspectTarget(key = key, uid = uid, usernameHint = usernameHint))
                if (queueWorkerJob?.isActive != true) {
                    queueWorkerJob = launchPrefetchWorker()
                }
            }
        }
    }

    override fun observeLocalNote(uid: Long): Flow<LocalSellerNote?> =
        noteStore.observeNote(uid)

    override suspend fun saveLocalNote(
        uid: Long,
        username: String,
        tag: TrustTag,
        noteText: String,
    ) {
        val savedNote = noteStore.saveNote(
            uid = uid,
            username = username,
            tag = tag,
            noteText = noteText,
        )
        cacheStore.updateReportsForUid(uid) { existing ->
            reEvaluateWithLocalNote(existing, savedNote)
        }
    }

    override suspend fun getAllNotes(): List<LocalSellerNote> =
        noteStore.getAllNotes()

    private fun launchPrefetchWorker(): Job = backgroundScope.launch {
        while (true) {
            val nextTarget = queueMutex.withLock {
                val iterator = pendingQueue.iterator()
                if (!iterator.hasNext()) {
                    queueWorkerJob = null
                    null
                } else {
                    iterator.next().also { iterator.remove() }
                }
            } ?: break

            val now = clockMillis()
            if (rateLimiter.getActiveCooldownUntil(remoteDataSource.siteHost, now) > now) {
                queueMutex.withLock {
                    pendingQueue.clear()
                    queueWorkerJob = null
                }
                break
            }

            val keys = buildCandidateKeys(nextTarget.uid, sanitizeUsernameHint(nextTarget.usernameHint))
            if (findFreshCachedEntry(keys, now) != null) {
                continue
            }

            runCatching {
                inspectSeller(
                    uid = nextTarget.uid,
                    usernameHint = nextTarget.usernameHint,
                    forceRefresh = false,
                )
            }
        }
    }

    private suspend fun executeRemoteInspection(
        uid: Long,
        cleanHint: String,
        rawUsernameHint: String,
        initialCacheKey: String,
        forceRefresh: Boolean,
    ): SellerTrustReport {
        val siteHost = remoteDataSource.siteHost
        val startNow = clockMillis()

        val preCooldown = rateLimiter.getActiveCooldownUntil(siteHost, startNow)
        if (preCooldown > startNow) {
            return resolveCooldownResponse(
                uid = uid,
                cleanHint = cleanHint,
                rawUsernameHint = rawUsernameHint,
                candidateKeys = buildCandidateKeys(uid, cleanHint),
                cooldownUntil = preCooldown,
                nowMillis = startNow,
            )
        }

        recordRemoteAttemptMillis(listOf(initialCacheKey), startNow)
        var maxObservedTimeoutCooldown = 0L

        suspend fun <T> governedCall(
            extraIntervalMs: Long = 0L,
            block: suspend () -> T,
        ): Result<T> = try {
            Result.success(
                rateLimiter.execute(
                    host = siteHost,
                    extraMinIntervalMillis = extraIntervalMs,
                    block = block,
                ),
            )
        } catch (c: CancellationException) {
            throw c
        } catch (timeoutErr: SellerTrustTimeoutException) {
            maxObservedTimeoutCooldown = maxOf(maxObservedTimeoutCooldown, timeoutErr.cooldownUntilEpochMillis)
            Result.failure(timeoutErr)
        } catch (t: Throwable) {
            Result.failure(t)
        }

        // Step A: Resolve UID via /api/account/find/{username} only when caller has no UID (> 0).
        var uidResolutionFailure: Throwable? = null
        val targetUid: Long = if (uid > 0L) {
            uid
        } else if (cleanHint.isNotEmpty()) {
            val lookupResult = governedCall { remoteDataSource.resolveUidByUsername(cleanHint) }
            uidResolutionFailure = lookupResult.exceptionOrNull()
            lookupResult.getOrNull()?.takeIf { it > 0L } ?: 0L
        } else {
            0L
        }

        if (targetUid > 0L && uid <= 0L && !forceRefresh) {
            val resolvedUidKey = buildCacheKey(uid = targetUid, username = cleanHint)
            cacheStore.getFresh(resolvedUidKey, clockMillis())?.let { cachedByResolvedUid ->
                return materializeCachedEntry(cachedByResolvedUid)
            }
        }

        val localNote = if (targetUid > 0L) noteStore.getNote(targetUid) else null

        // Step B: Fetch lightweight seller profile FIRST (/api/account/getInfo/{uid}).
        // Resolving canonical username upfront prevents speculative duplicate hint+canonical /search calls,
        // and lets us skip /api/content/list-discussions altogether when nPost == 0.
        val profileResult: Result<UserProfile> = if (targetUid > 0L) {
            governedCall { remoteDataSource.fetchSellerProfile(targetUid) }
        } else {
            Result.failure(uidResolutionFailure ?: IllegalArgumentException("未找到匹配的卖家账号"))
        }

        val profile = profileResult.getOrNull()
        val canonicalUsername = profile?.username?.trim()?.takeIf { it.isNotEmpty() }
            ?: cleanHint.ifEmpty { localNote?.username?.trim().orEmpty() }

        val userExplicitlyNotFound = isUserNotFoundFailure(profileResult.exceptionOrNull())
        val rateLimitFailure = (profileResult.exceptionOrNull() as? SellerTrustRateLimitedException)
            ?: (uidResolutionFailure as? SellerTrustRateLimitedException)
        val activeCooldownBeforeStepC = rateLimiter.getActiveCooldownUntil(siteHost, clockMillis())
        val abortedByRateLimit = profile == null && (
            rateLimitFailure != null ||
                activeCooldownBeforeStepC > clockMillis()
            )

        // Step C: Fetch paced user discussion pages (if needed) and exposure board snapshot via governed concurrency.
        val aggregated = coroutineScope {
            val topicsDeferred: Deferred<Result<TopicsScanOutcome>> = async {
                when {
                    abortedByRateLimit -> {
                        Result.failure(
                            rateLimitFailure ?: SellerTrustRateLimitedException(
                                host = siteHost,
                                cooldownUntilEpochMillis = activeCooldownBeforeStepC,
                                message = "请求触发限流",
                            ),
                        )
                    }

                    targetUid <= 0L -> {
                        if (uidResolutionFailure != null) {
                            Result.failure(uidResolutionFailure)
                        } else {
                            Result.failure(IllegalArgumentException("缺少有效的卖家 UID"))
                        }
                    }

                    userExplicitlyNotFound -> {
                        Result.failure(profileResult.exceptionOrNull()!!)
                    }

                    profile != null && profile.topicCount == 0 -> {
                        // Seller has 0 posted topics — skip list-discussions HTTP roundtrip completely.
                        Result.success(
                            TopicsScanOutcome(
                                topics = emptyList(),
                                pagesScanned = 0,
                                hasMorePages = false,
                            ),
                        )
                    }

                    else -> scanSellerTopicsInitial(
                        targetUid = targetUid,
                        totalTopicsHint = profile?.topicCount,
                        siteHost = siteHost,
                        governedCall = { page ->
                            governedCall { remoteDataSource.fetchSellerTopicsPage(targetUid, page) }
                        },
                    )
                }
            }

            val canRunExposureCheck = canonicalUsername.isNotEmpty() &&
                !(userExplicitlyNotFound && cleanHint.isEmpty()) &&
                !abortedByRateLimit

            val exposeBoardDeferred: Deferred<Result<FeedPageResult>>? = if (canRunExposureCheck) {
                async {
                    resolveExposeBoardPage(
                        siteHost = siteHost,
                        forceRefresh = forceRefresh,
                        governedCall = { governedCall { remoteDataSource.fetchExposeBoardPage1() } },
                    )
                }
            } else {
                null
            }

            AggregationResult(
                targetUid = profile?.uid?.takeIf { it > 0L } ?: targetUid,
                canonicalUsername = canonicalUsername,
                profileResult = profileResult,
                topicsResult = topicsDeferred.await(),
                exposeBoardResult = exposeBoardDeferred?.await(),
                localNote = localNote,
            )
        }

        val finishedNow = clockMillis()
        val activeCooldownAfter = rateLimiter.getActiveCooldownUntil(siteHost, finishedNow)
        val effectiveCooldown = maxOf(activeCooldownAfter, maxObservedTimeoutCooldown)

        val baseReport = buildReport(
            aggregated = aggregated,
            fallbackUsername = fallbackDisplayName(aggregated.targetUid, rawUsernameHint),
            nowEpochMillis = finishedNow,
        )
        if (baseReport.uid > 0L && baseReport.topicsSucceeded) {
            val initialTopics = aggregated.topicsResult.getOrNull()?.topics.orEmpty()
            recordInitialScannedTopicIds(
                uid = baseReport.uid,
                allTopicIds = initialTopics.mapNotNull { it.id.takeIf { id -> id > 0L } },
                tradeTopicIds = initialTopics.filter(TrustRiskEvaluator::isTradeTopic).mapNotNull { it.id.takeIf { id -> id > 0L } },
            )
        }

        val allKeys = buildList {
            add(initialCacheKey)
            val resolvedKey = buildCacheKey(uid = baseReport.uid, username = baseReport.username)
            if (resolvedKey.isNotEmpty()) add(resolvedKey)
            if (baseReport.username.isNotBlank()) add(buildCacheKey(uid = 0L, username = baseReport.username))
        }.distinct()
        recordRemoteAttemptMillis(allKeys, finishedNow)

        if (!baseReport.anyRemoteSucceeded) {
            val primaryError = firstRelevantException(aggregated) ?: uidResolutionFailure
            val hitRateLimit = activeCooldownAfter > finishedNow || primaryError is SellerTrustRateLimitedException

            if (hitRateLimit) {
                cacheStore.getStalePositiveFallback(allKeys, finishedNow)?.let { stale ->
                    val synced = reEvaluateWithLocalNote(stale.report, localNote ?: stale.report.localNote)
                    return synced.copy(
                        isFromCache = true,
                        fetchStatus = TrustFetchStatus.STALE_CACHE_FALLBACK,
                        cooldownUntilEpochMillis = effectiveCooldown,
                        isRefreshThrottled = false,
                        failureMessage = buildRateLimitFallbackMessage(effectiveCooldown, finishedNow, hasCache = true),
                    )
                }
                return baseReport.copy(
                    fetchStatus = TrustFetchStatus.RATE_LIMITED,
                    cooldownUntilEpochMillis = effectiveCooldown,
                    failureMessage = formatGeneralFailureReason(primaryError, effectiveCooldown, finishedNow),
                )
            }

            // Short-TTL negative cache for failed / empty / non-existent seller checks.
            val failureMsg = formatGeneralFailureReason(primaryError, effectiveCooldown, finishedNow)
            val failedReport = baseReport.copy(
                fetchStatus = TrustFetchStatus.FAILED_RETRYABLE,
                cooldownUntilEpochMillis = effectiveCooldown,
                failureMessage = failureMsg,
            )
            val effectiveNegTtl = minOf(negativeCacheTtlMillis, cacheTtlMillis).coerceAtLeast(1_000L)
            cacheStore.put(
                keys = allKeys,
                report = failedReport,
                ttlMillis = effectiveNegTtl,
                isNegative = true,
                nowMillis = finishedNow,
            )
            return failedReport
        }

        // Preserve any prior deep-search verification / hits when refreshing profile/topics manually.
        val priorPositive = cacheStore.getStalePositiveFallback(allKeys, finishedNow)
        val mergedReport = mergeWithPriorDeepSearchIfValid(
            freshReport = baseReport,
            priorEntry = priorPositive,
            nowMillis = finishedNow,
        )

        val isFullSuccess = mergedReport.profileSucceeded && mergedReport.topicsSucceeded && mergedReport.searchSucceeded
        val ttlMillis = if (isFullSuccess) {
            cacheTtlMillis
        } else {
            minOf(partialCacheTtlMillis, cacheTtlMillis)
        }

        val freshReport = mergedReport.copy(
            fetchStatus = TrustFetchStatus.FRESH,
            cooldownUntilEpochMillis = effectiveCooldown,
            failureMessage = if (!mergedReport.searchSucceeded) mergedReport.searchFailureReason else null,
        )

        cacheStore.put(
            keys = allKeys,
            report = freshReport,
            ttlMillis = ttlMillis,
            isNegative = false,
            nowMillis = finishedNow,
        )

        return freshReport
    }

    private suspend fun resolveExposeBoardPage(
        siteHost: String,
        forceRefresh: Boolean,
        governedCall: suspend () -> Result<FeedPageResult>,
    ): Result<FeedPageResult> {
        val now = clockMillis()

        // 1. Reuse ForumRepository's fresh `/categories/expose` feed cache if already browsed by user.
        if (!forceRefresh) {
            remoteDataSource.getCachedExposeBoardPage1()?.let { feedCached ->
                if (!feedCached.isFromRssFallback) {
                    exposeBoardStore.put(feedCached, now)
                }
                return Result.success(feedCached)
            }
            exposeBoardStore.getFresh(now)?.let { snapshot ->
                return Result.success(snapshot.page)
            }
        } else {
            // Even during manual refresh, avoid hammering `/categories/expose` more than once per minute.
            val lastFetched = exposeBoardStore.getLastFetchedAtMillis()
            if (lastFetched > 0L && now >= lastFetched && (now - lastFetched) < exposeBoardRefreshMinIntervalMillis) {
                exposeBoardStore.getFresh(now)?.let { snapshot ->
                    return Result.success(snapshot.page)
                }
            }
        }

        val fetchedResult = exposeBoardSingleFlight.execute("expose_board:$siteHost") {
            val innerNow = clockMillis()
            if (!forceRefresh) {
                exposeBoardStore.getFresh(innerNow)?.let { return@execute Result.success(it.page) }
            }
            val result = governedCall()
            val fetchedPage = result.getOrNull()
            if (fetchedPage != null) {
                if (!fetchedPage.isFromRssFallback) {
                    exposeBoardStore.put(fetchedPage, clockMillis())
                    return@execute result
                }
                // Never cache RSS fallback as a 30-minute `/categories/expose` snapshot; if a real
                // stale snapshot exists within retention, prefer it unless RSS has newer expose items.
                val staleReal = exposeBoardStore.getStaleFallback(clockMillis())
                if (staleReal != null && fetchedPage.pinnedTopics.isEmpty() && fetchedPage.topics.isEmpty()) {
                    return@execute Result.success(staleReal.page)
                }
                return@execute result
            }
            // Remote failure: fall back to a retained stale `/categories/expose` snapshot if available.
            exposeBoardStore.getStaleFallback(clockMillis())?.let { stale ->
                return@execute Result.success(stale.page)
            }
            result
        }
        return fetchedResult
    }

    private suspend fun executeDeepExposureSearch(
        baseReport: SellerTrustReport,
        targetUid: Long,
        targetUsername: String,
    ): SellerTrustReport {
        val siteHost = remoteDataSource.siteHost
        val now = clockMillis()
        val gateUntil = getDeepSearchBlockUntil(siteHost, now)
        if (gateUntil > now) {
            val remSec = ((gateUntil - now + 999L) / 1_000L).coerceAtLeast(1L)
            return baseReport.copy(
                deepSearchCooldownUntilEpochMillis = gateUntil,
                deepSearchNoticeMessage = "为避免触发官网搜索限流，请 ${remSec} 秒后再试（也可使用下方 Google 查曝光）",
            )
        }

        val (query, categorySlug) = TrustRiskEvaluator.buildDeepSearchQuery(targetUsername)
        recordDeepSearchAttempt(now)
        val searchResult: Result<FeedPageResult> = try {
            Result.success(
                searchRateLimiter.execute(host = siteHost) {
                    remoteDataSource.searchExposureTopicsPage1(
                        query = query,
                        categorySlug = categorySlug,
                    )
                },
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            recordDeepSearchAttempt(clockMillis())
        }

        val finishedNow = clockMillis()
        val searchPage = searchResult.getOrNull()
        if (searchPage == null) {
            val failure = searchResult.exceptionOrNull()
            val searchCooldownUntil = maxOf(
                searchRateLimiter.getActiveCooldownUntil(siteHost, finishedNow),
                (failure as? SellerTrustRateLimitedException)?.cooldownUntilEpochMillis ?: 0L,
                (failure as? SellerTrustTimeoutException)?.cooldownUntilEpochMillis ?: 0L,
            )
            return baseReport.copy(
                deepSearchCooldownUntilEpochMillis = searchCooldownUntil,
                deepSearchNoticeMessage = formatSearchFailureReason(failure),
            )
        }

        val deepHits = TrustRiskEvaluator.filterExposureHits(
            candidates = (searchPage.pinnedTopics + searchPage.topics).map { topic ->
                ExposureCandidate(topic = topic)
            },
            targetUid = targetUid,
            targetUsername = targetUsername,
        )
        val mergedHits = mergeExposureHits(baseReport.exposureSearchHits, deepHits)
        val latestNote = if (targetUid > 0L) noteStore.getNote(targetUid) else baseReport.localNote
        val updatedAssessment = TrustRiskEvaluator.evaluate(
            rankLevel = baseReport.rankLevel,
            registrationDays = baseReport.registrationDays,
            historicalTradeTopicCount = baseReport.historicalTradeTopicCount,
            exposureSearchHits = mergedHits,
            searchSucceeded = true,
            localNote = latestNote,
            profileSucceeded = baseReport.profileSucceeded,
            registrationDurationLabel = baseReport.registrationDurationLabel,
            exposureCheckScope = ExposureCheckScope.DEEP_SEARCH,
            scannedTopicsCount = baseReport.scannedTopicsCount,
            hasMoreTopicPages = baseReport.hasMoreTopicPages,
            topicsSucceeded = baseReport.topicsSucceeded,
        )

        val updatedReport = baseReport.copy(
            exposureSearchHits = mergedHits,
            searchSucceeded = true,
            searchFailureReason = null,
            exposureCheckScope = ExposureCheckScope.DEEP_SEARCH,
            localNote = latestNote,
            riskAssessment = updatedAssessment,
            fetchedAtEpochMillis = finishedNow,
            isFromCache = false,
            fetchStatus = if (baseReport.anyRemoteSucceeded) TrustFetchStatus.FRESH else baseReport.fetchStatus,
            deepSearchCooldownUntilEpochMillis = 0L,
            deepSearchNoticeMessage = null,
        )

        val keys = buildList {
            if (targetUid > 0L) add(buildCacheKey(uid = targetUid, username = targetUsername))
            if (targetUsername.isNotBlank()) add(buildCacheKey(uid = 0L, username = targetUsername))
        }.distinct()
        if (keys.isNotEmpty() && updatedReport.anyRemoteSucceeded) {
            val isFullSuccess = updatedReport.profileSucceeded && updatedReport.topicsSucceeded && updatedReport.searchSucceeded
            val ttlMillis = if (isFullSuccess) cacheTtlMillis else minOf(partialCacheTtlMillis, cacheTtlMillis)
            cacheStore.put(
                keys = keys,
                report = updatedReport,
                ttlMillis = ttlMillis,
                isNegative = false,
                nowMillis = finishedNow,
            )
        }

        return updatedReport
    }

    private suspend fun getDeepSearchBlockUntil(
        siteHost: String,
        nowMillis: Long,
    ): Long {
        val cooldownUntil = searchRateLimiter.getActiveCooldownUntil(siteHost, nowMillis)
        val minIntervalUntil = deepSearchGateMutex.withLock {
            if (lastDeepSearchAttemptAtMillis > 0L && nowMillis >= lastDeepSearchAttemptAtMillis) {
                val candidate = lastDeepSearchAttemptAtMillis + searchMinIntervalMillis
                if (candidate > nowMillis) candidate else 0L
            } else {
                0L
            }
        }
        return maxOf(cooldownUntil, minIntervalUntil)
    }

    private suspend fun recordDeepSearchAttempt(epochMillis: Long) {
        deepSearchGateMutex.withLock {
            if (epochMillis > lastDeepSearchAttemptAtMillis) {
                lastDeepSearchAttemptAtMillis = epochMillis
            }
        }
    }

    private fun mergeWithPriorDeepSearchIfValid(
        freshReport: SellerTrustReport,
        priorEntry: PersistedTrustCacheEntry?,
        nowMillis: Long,
    ): SellerTrustReport {
        val prior = priorEntry?.report ?: return freshReport
        val priorStillFresh = nowMillis < priorEntry.expiresAtEpochMillis
        val hasDeepExposureToMerge = prior.isDeepSearchVerified &&
            (priorStillFresh || prior.exposureSearchHits.isNotEmpty())
        val hasDeeperTopicsToMerge = priorStillFresh &&
            prior.topicsSucceeded &&
            freshReport.topicsSucceeded &&
            prior.scannedTopicPages > freshReport.scannedTopicPages

        if (!hasDeepExposureToMerge && !hasDeeperTopicsToMerge) return freshReport

        val mergedHits = if (hasDeepExposureToMerge) {
            mergeExposureHits(freshReport.exposureSearchHits, prior.exposureSearchHits)
        } else {
            freshReport.exposureSearchHits
        }
        val mergedScope = if (hasDeepExposureToMerge && priorStillFresh && prior.searchSucceeded) {
            ExposureCheckScope.DEEP_SEARCH
        } else {
            freshReport.exposureCheckScope
        }
        val mergedSearchSucceeded = freshReport.searchSucceeded ||
            (hasDeepExposureToMerge && priorStillFresh && prior.searchSucceeded)

        val mergedTradeTopics = if (hasDeeperTopicsToMerge) {
            (freshReport.recentTradeTopics + prior.recentTradeTopics)
                .distinctBy { it.id }
                .take(MAX_RECENT_TRADE_TOPICS)
        } else {
            freshReport.recentTradeTopics
        }
        val mergedTradeCount = if (hasDeeperTopicsToMerge) {
            maxOf(freshReport.historicalTradeTopicCount, prior.historicalTradeTopicCount, mergedTradeTopics.size)
        } else {
            freshReport.historicalTradeTopicCount
        }
        val mergedScannedCount = if (hasDeeperTopicsToMerge) {
            maxOf(freshReport.scannedTopicsCount, prior.scannedTopicsCount)
        } else {
            freshReport.scannedTopicsCount
        }
        val mergedScannedPages = if (hasDeeperTopicsToMerge) {
            prior.scannedTopicPages
        } else {
            freshReport.scannedTopicPages
        }
        val mergedHasMoreTopicPages = if (hasDeeperTopicsToMerge) {
            prior.hasMoreTopicPages
        } else {
            freshReport.hasMoreTopicPages
        }

        val assessment = TrustRiskEvaluator.evaluate(
            rankLevel = freshReport.rankLevel,
            registrationDays = freshReport.registrationDays,
            historicalTradeTopicCount = mergedTradeCount,
            exposureSearchHits = mergedHits,
            searchSucceeded = mergedSearchSucceeded,
            localNote = freshReport.localNote,
            profileSucceeded = freshReport.profileSucceeded,
            registrationDurationLabel = freshReport.registrationDurationLabel,
            exposureCheckScope = mergedScope,
            scannedTopicsCount = mergedScannedCount,
            hasMoreTopicPages = mergedHasMoreTopicPages,
            topicsSucceeded = freshReport.topicsSucceeded,
        )
        return freshReport.copy(
            historicalTradeTopicCount = mergedTradeCount,
            recentTradeTopics = mergedTradeTopics,
            exposureSearchHits = mergedHits,
            searchSucceeded = mergedSearchSucceeded,
            searchFailureReason = if (mergedSearchSucceeded) null else freshReport.searchFailureReason,
            exposureCheckScope = mergedScope,
            riskAssessment = assessment,
            scannedTopicsCount = mergedScannedCount,
            scannedTopicPages = mergedScannedPages,
            hasMoreTopicPages = mergedHasMoreTopicPages,
        )
    }

    private fun mergeExposureHits(
        primary: List<ExposureHitTopic>,
        secondary: List<ExposureHitTopic>,
    ): List<ExposureHitTopic> {
        if (secondary.isEmpty()) return primary
        if (primary.isEmpty()) return secondary
        val byId = LinkedHashMap<Long, ExposureHitTopic>()
        fun severity(term: String): Int = when (term) {
            TrustRiskEvaluator.TERM_SCAMMER -> 4
            TrustRiskEvaluator.TERM_RUNAWAY -> 3
            TrustRiskEvaluator.TERM_CALLOUT -> 2
            TrustRiskEvaluator.TERM_EXPOSE -> 1
            else -> 0
        }
        for (hit in primary + secondary) {
            val existing = byId[hit.topicId]
            if (existing == null || severity(hit.matchedTerm) > severity(existing.matchedTerm)) {
                byId[hit.topicId] = hit
            }
        }
        return byId.values.sortedWith(
            compareByDescending<ExposureHitTopic> { severity(it.matchedTerm) }
                .thenByDescending { it.topicId },
        )
    }

    private suspend fun resolveCooldownResponse(
        uid: Long,
        cleanHint: String,
        rawUsernameHint: String,
        candidateKeys: List<String>,
        cooldownUntil: Long,
        nowMillis: Long,
    ): SellerTrustReport {
        cacheStore.getStalePositiveFallback(candidateKeys, nowMillis)?.let { cached ->
            val synced = syncWithLatestNote(cached.report, cached.report.uid.takeIf { it > 0L } ?: uid)
            return synced.copy(
                isFromCache = true,
                fetchStatus = TrustFetchStatus.STALE_CACHE_FALLBACK,
                cooldownUntilEpochMillis = cooldownUntil,
                isRefreshThrottled = false,
                failureMessage = buildRateLimitFallbackMessage(cooldownUntil, nowMillis, hasCache = true),
            )
        }

        val note = if (uid > 0L) noteStore.getNote(uid) else null
        return buildUnavailableReport(
            uid = uid,
            username = cleanHint.ifEmpty { fallbackDisplayName(uid, rawUsernameHint) },
            localNote = note,
            nowEpochMillis = nowMillis,
            fetchStatus = TrustFetchStatus.RATE_LIMITED,
            cooldownUntilEpochMillis = cooldownUntil,
            failureMessage = buildRateLimitFallbackMessage(cooldownUntil, nowMillis, hasCache = false),
        )
    }

    private suspend fun findFreshCachedEntry(
        keys: List<String>,
        nowMillis: Long,
    ): PersistedTrustCacheEntry? {
        for (key in keys) {
            cacheStore.getFresh(key, nowMillis)?.let { return it }
        }
        return null
    }

    private suspend fun materializeCachedEntry(
        entry: PersistedTrustCacheEntry,
    ): SellerTrustReport {
        val synced = syncWithLatestNote(entry.report, entry.report.uid)
        return if (entry.isNegative) {
            synced.copy(
                isFromCache = true,
                isNegativeCache = true,
                fetchStatus = TrustFetchStatus.FAILED_RETRYABLE,
                isRefreshThrottled = false,
            )
        } else {
            synced.copy(
                isFromCache = true,
                isNegativeCache = false,
                fetchStatus = TrustFetchStatus.FROM_CACHE,
                isRefreshThrottled = false,
                cooldownUntilEpochMillis = 0L,
            )
        }
    }

    private suspend fun syncWithLatestNote(
        report: SellerTrustReport,
        fallbackUid: Long,
    ): SellerTrustReport {
        val targetUid = report.uid.takeIf { it > 0L } ?: fallbackUid
        if (targetUid <= 0L) return report
        val latestNote = noteStore.getNote(targetUid)
        return if (latestNote != report.localNote) {
            reEvaluateWithLocalNote(report, latestNote)
        } else {
            report
        }
    }

    private suspend fun getLastRemoteAttemptMillis(keys: List<String>): Long =
        attemptMutex.withLock {
            keys.maxOfOrNull { lastRemoteAttemptByKey[it] ?: 0L } ?: 0L
        }

    private suspend fun recordRemoteAttemptMillis(keys: List<String>, epochMillis: Long) {
        attemptMutex.withLock {
            for (key in keys) {
                if (key.isNotBlank()) {
                    lastRemoteAttemptByKey[key] = epochMillis
                }
            }
        }
    }

    private fun buildUnavailableReport(
        uid: Long,
        username: String,
        localNote: LocalSellerNote?,
        nowEpochMillis: Long,
        fetchStatus: TrustFetchStatus,
        cooldownUntilEpochMillis: Long,
        failureMessage: String,
        isRefreshThrottled: Boolean = false,
    ): SellerTrustReport {
        val resolvedAvatar = if (uid > 0L) "$NODESEEK_ORIGIN/avatar/$uid.png" else ""
        val riskAssessment = TrustRiskEvaluator.evaluate(
            rankLevel = 0,
            registrationDays = null,
            historicalTradeTopicCount = 0,
            exposureSearchHits = emptyList(),
            searchSucceeded = false,
            localNote = localNote,
            profileSucceeded = false,
            registrationDurationLabel = "注册时间未知",
            exposureCheckScope = ExposureCheckScope.UNVERIFIED,
            topicsSucceeded = false,
        )
        return SellerTrustReport(
            uid = uid,
            username = username,
            avatarUrl = resolvedAvatar,
            rankLevel = 0,
            localNote = localNote,
            riskAssessment = riskAssessment,
            profileSucceeded = false,
            topicsSucceeded = false,
            searchSucceeded = false,
            searchFailureReason = failureMessage,
            fetchedAtEpochMillis = nowEpochMillis,
            isFromCache = false,
            fetchStatus = fetchStatus,
            cooldownUntilEpochMillis = cooldownUntilEpochMillis,
            failureMessage = failureMessage,
            isRefreshThrottled = isRefreshThrottled,
            exposureCheckScope = ExposureCheckScope.UNVERIFIED,
        )
    }

    private fun buildReport(
        aggregated: AggregationResult,
        fallbackUsername: String,
        nowEpochMillis: Long,
    ): SellerTrustReport {
        val profile = aggregated.profileResult.getOrNull()
        val profileSucceeded = aggregated.profileResult.isSuccess && profile != null
        val resolvedUid = aggregated.targetUid
        val resolvedUsername = aggregated.canonicalUsername.ifBlank { fallbackUsername }

        val topicsOutcome = aggregated.topicsResult.getOrNull()
        val topicsSucceeded = aggregated.topicsResult.isSuccess
        val allUserTopics = topicsOutcome?.topics.orEmpty()
        val scannedTopicPages = topicsOutcome?.pagesScanned ?: 0
        val hasMoreTopicPages = topicsOutcome?.hasMorePages == true
        val tradeTopics = allUserTopics.filter(TrustRiskEvaluator::isTradeTopic)

        val exposePage = aggregated.exposeBoardResult?.getOrNull()
        val candidates = mutableListOf<ExposureCandidate>()
        exposePage?.let { page ->
            (page.pinnedTopics + page.topics).forEach { topic ->
                candidates += ExposureCandidate(
                    topic = topic,
                    queryTermHint = TrustRiskEvaluator.TERM_EXPOSE,
                )
            }
        }

        val exposeBoardIsRssFallback = exposePage?.isFromRssFallback == true
        val exposeBoardSucceeded = aggregated.exposeBoardResult?.isSuccess == true && !exposeBoardIsRssFallback
        val searchSucceeded = aggregated.canonicalUsername.isNotBlank() && exposeBoardSucceeded
        val exposureCheckScope = if (searchSucceeded) {
            ExposureCheckScope.BOARD_SNAPSHOT
        } else {
            ExposureCheckScope.UNVERIFIED
        }

        val searchFailureReason = when {
            searchSucceeded -> null
            aggregated.canonicalUsername.isBlank() -> "未获取到卖家用户名，无法执行站内曝光检索"
            exposeBoardIsRssFallback -> "曝光版需登录或完成人机验证，仅从 RSS 兜底排查，未完成完整曝光核验"
            else -> formatBoardCheckFailureReason(aggregated.exposeBoardResult?.exceptionOrNull())
        }

        val exposureHits = if (aggregated.canonicalUsername.isNotBlank()) {
            TrustRiskEvaluator.filterExposureHits(
                candidates = candidates,
                targetUid = resolvedUid,
                targetUsername = aggregated.canonicalUsername,
            )
        } else {
            emptyList()
        }

        val tenure = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = profile?.joinedAtIso,
            nowEpochMillis = nowEpochMillis,
            zoneId = zoneId,
        )

        val rankLevel = profile?.level ?: 0
        val resolvedAvatar = profile?.avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (resolvedUid > 0L) "$NODESEEK_ORIGIN/avatar/$resolvedUid.png" else ""

        val riskAssessment = TrustRiskEvaluator.evaluate(
            rankLevel = rankLevel,
            registrationDays = tenure.registrationDays,
            historicalTradeTopicCount = tradeTopics.size,
            exposureSearchHits = exposureHits,
            searchSucceeded = searchSucceeded,
            localNote = aggregated.localNote,
            profileSucceeded = profileSucceeded,
            registrationDurationLabel = tenure.durationLabel,
            exposureCheckScope = exposureCheckScope,
            scannedTopicsCount = allUserTopics.size,
            hasMoreTopicPages = hasMoreTopicPages,
            topicsSucceeded = topicsSucceeded,
        )

        return SellerTrustReport(
            uid = resolvedUid,
            username = resolvedUsername,
            avatarUrl = resolvedAvatar,
            rankLevel = rankLevel,
            coins = profile?.drumsticks,
            stardust = profile?.stardust,
            createdAtText = tenure.createdAtText,
            registrationDays = tenure.registrationDays,
            registrationDurationLabel = tenure.durationLabel,
            totalTopicsCount = profile?.topicCount,
            totalCommentsCount = profile?.replyCount,
            historicalTradeTopicCount = tradeTopics.size,
            recentTradeTopics = tradeTopics.take(MAX_RECENT_TRADE_TOPICS),
            exposureSearchHits = exposureHits,
            searchSucceeded = searchSucceeded,
            localNote = aggregated.localNote,
            riskAssessment = riskAssessment,
            profileSucceeded = profileSucceeded,
            topicsSucceeded = topicsSucceeded,
            searchFailureReason = searchFailureReason,
            fetchedAtEpochMillis = nowEpochMillis,
            isFromCache = false,
            fetchStatus = TrustFetchStatus.FRESH,
            exposureCheckScope = exposureCheckScope,
            scannedTopicsCount = allUserTopics.size,
            scannedTopicPages = scannedTopicPages,
            hasMoreTopicPages = hasMoreTopicPages,
        )
    }

    private fun reEvaluateWithLocalNote(
        report: SellerTrustReport,
        note: LocalSellerNote?,
    ): SellerTrustReport {
        val nextAssessment = TrustRiskEvaluator.evaluate(
            rankLevel = report.rankLevel,
            registrationDays = report.registrationDays,
            historicalTradeTopicCount = report.historicalTradeTopicCount,
            exposureSearchHits = report.exposureSearchHits,
            searchSucceeded = report.searchSucceeded,
            localNote = note,
            profileSucceeded = report.profileSucceeded,
            registrationDurationLabel = report.registrationDurationLabel,
            exposureCheckScope = report.exposureCheckScope,
            scannedTopicsCount = report.scannedTopicsCount,
            hasMoreTopicPages = report.hasMoreTopicPages,
            topicsSucceeded = report.topicsSucceeded,
        )
        return report.copy(
            localNote = note,
            riskAssessment = nextAssessment,
        )
    }

    private suspend fun scanSellerTopicsInitial(
        targetUid: Long,
        totalTopicsHint: Int?,
        siteHost: String,
        governedCall: suspend (Int) -> Result<SellerTopicsPage>,
    ): Result<TopicsScanOutcome> {
        val maxPagesBudget = maxAutoTopicPages.coerceAtLeast(1)
        val expectedMaxPages = totalTopicsHint
            ?.takeIf { it > 0 }
            ?.let { (it + USER_TOPICS_PAGE_SIZE - 1) / USER_TOPICS_PAGE_SIZE }
        val targetEndPage = if (expectedMaxPages != null) {
            minOf(maxPagesBudget, expectedMaxPages)
        } else {
            maxPagesBudget
        }.coerceAtLeast(1)

        val deduped = LinkedHashMap<Long, TopicSummary>()
        var pagesScanned = 0
        var hasMore = false

        for (page in 1..targetEndPage) {
            if (page > 1) {
                val now = clockMillis()
                if (rateLimiter.getActiveCooldownUntil(siteHost, now) > now) {
                    hasMore = true
                    break
                }
            }

            val pageResult = governedCall(page)
            val pageData = pageResult.getOrNull()
            if (pageData == null) {
                if (page == 1) {
                    return Result.failure(
                        pageResult.exceptionOrNull()
                            ?: IllegalStateException("加载卖家主题列表失败"),
                    )
                }
                // Preserve already-scanned pages gracefully on transient/rate-limit failure on page >= 2
                hasMore = true
                break
            }

            pagesScanned = page
            for (topic in pageData.topics) {
                if (topic.id > 0L && !deduped.containsKey(topic.id)) {
                    deduped[topic.id] = topic
                }
            }

            val moreAvailable = pageData.hasMore &&
                pageData.topics.isNotEmpty() &&
                (expectedMaxPages == null || page < expectedMaxPages)
            if (!moreAvailable) {
                hasMore = false
                break
            }
            hasMore = true
        }

        return Result.success(
            TopicsScanOutcome(
                topics = deduped.values.toList(),
                pagesScanned = pagesScanned,
                hasMorePages = hasMore,
            ),
        )
    }

    private suspend fun executeScanMoreTopics(
        fallbackBase: SellerTrustReport,
        targetUid: Long,
    ): SellerTrustReport {
        val latestBase = getCachedReportOnly(uid = targetUid, usernameHint = fallbackBase.username)
            ?: fallbackBase
        if (!latestBase.topicsSucceeded || !latestBase.hasMoreTopicPages) {
            return latestBase.copy(
                topicScanCooldownUntilEpochMillis = 0L,
                topicScanNoticeMessage = null,
            )
        }

        val siteHost = remoteDataSource.siteHost
        val startPage = (latestBase.scannedTopicPages + 1).coerceAtLeast(2)
        val batchPages = scanMoreTopicPages.coerceAtLeast(1)
        val expectedMaxPages = latestBase.totalTopicsCount
            ?.takeIf { it > 0 }
            ?.let { (it + USER_TOPICS_PAGE_SIZE - 1) / USER_TOPICS_PAGE_SIZE }

        if (expectedMaxPages != null && startPage > expectedMaxPages) {
            val doneReport = latestBase.copy(
                hasMoreTopicPages = false,
                topicScanCooldownUntilEpochMillis = 0L,
                topicScanNoticeMessage = null,
            )
            persistUpdatedTopicsReport(doneReport, clockMillis())
            return doneReport
        }

        val targetEndPage = if (expectedMaxPages != null) {
            minOf(startPage + batchPages - 1, expectedMaxPages)
        } else {
            startPage + batchPages - 1
        }

        val newlyFetched = LinkedHashMap<Long, TopicSummary>()
        var lastSucceededPage = latestBase.scannedTopicPages
        var stillHasMore = true
        var stopError: Throwable? = null

        for (page in startPage..targetEndPage) {
            val now = clockMillis()
            if (rateLimiter.getActiveCooldownUntil(siteHost, now) > now) {
                stillHasMore = true
                break
            }
            val pageData: SellerTopicsPage = try {
                rateLimiter.execute(host = siteHost) {
                    remoteDataSource.fetchSellerTopicsPage(targetUid, page)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                stopError = t
                stillHasMore = true
                break
            }

            lastSucceededPage = page
            for (topic in pageData.topics) {
                if (topic.id > 0L && !newlyFetched.containsKey(topic.id)) {
                    newlyFetched[topic.id] = topic
                }
            }

            val moreAvailable = pageData.hasMore &&
                pageData.topics.isNotEmpty() &&
                (expectedMaxPages == null || page < expectedMaxPages)
            if (!moreAvailable) {
                stillHasMore = false
                break
            }
            stillHasMore = true
        }

        val finishedNow = clockMillis()
        val cooldownUntil = maxOf(
            rateLimiter.getActiveCooldownUntil(siteHost, finishedNow),
            (stopError as? SellerTrustRateLimitedException)?.cooldownUntilEpochMillis ?: 0L,
            (stopError as? SellerTrustTimeoutException)?.cooldownUntilEpochMillis ?: 0L,
        )

        if (lastSucceededPage <= latestBase.scannedTopicPages && stopError != null) {
            return latestBase.copy(
                topicScanCooldownUntilEpochMillis = cooldownUntil,
                topicScanNoticeMessage = formatTopicScanFailureReason(stopError, cooldownUntil, finishedNow),
            )
        }

        val (newUniqueTopicsCount, newTradeTopics) = deduplicateNewlyScannedTopics(
            uid = targetUid,
            fallbackRecentTradeIds = latestBase.recentTradeTopics.map { it.id },
            newlyFetched = newlyFetched.values,
        )

        val mergedTradeCount = latestBase.historicalTradeTopicCount + newTradeTopics.size
        val mergedRecentTrades = (latestBase.recentTradeTopics + newTradeTopics)
            .distinctBy { it.id }
            .take(MAX_RECENT_TRADE_TOPICS)
        val rawMergedScanned = maxOf(
            latestBase.scannedTopicsCount + newUniqueTopicsCount,
            mergedTradeCount,
        )
        val mergedScannedCount = latestBase.totalTopicsCount
            ?.takeIf { it > 0 }
            ?.let { total -> rawMergedScanned.coerceAtMost(maxOf(total, mergedTradeCount)) }
            ?: rawMergedScanned
        val latestNote = if (targetUid > 0L) noteStore.getNote(targetUid) else latestBase.localNote
        val updatedAssessment = TrustRiskEvaluator.evaluate(
            rankLevel = latestBase.rankLevel,
            registrationDays = latestBase.registrationDays,
            historicalTradeTopicCount = mergedTradeCount,
            exposureSearchHits = latestBase.exposureSearchHits,
            searchSucceeded = latestBase.searchSucceeded,
            localNote = latestNote,
            profileSucceeded = latestBase.profileSucceeded,
            registrationDurationLabel = latestBase.registrationDurationLabel,
            exposureCheckScope = latestBase.exposureCheckScope,
            scannedTopicsCount = mergedScannedCount,
            hasMoreTopicPages = stillHasMore,
            topicsSucceeded = latestBase.topicsSucceeded,
        )

        val partialNotice = if (stopError != null) {
            formatTopicScanFailureReason(stopError, cooldownUntil, finishedNow)
        } else {
            null
        }

        val updatedReport = latestBase.copy(
            historicalTradeTopicCount = mergedTradeCount,
            recentTradeTopics = mergedRecentTrades,
            localNote = latestNote,
            riskAssessment = updatedAssessment,
            scannedTopicsCount = mergedScannedCount,
            scannedTopicPages = lastSucceededPage,
            hasMoreTopicPages = stillHasMore,
            topicScanCooldownUntilEpochMillis = cooldownUntil.takeIf { it > finishedNow } ?: 0L,
            topicScanNoticeMessage = partialNotice,
            fetchedAtEpochMillis = finishedNow,
            isFromCache = false,
            fetchStatus = TrustFetchStatus.FRESH,
        )

        persistUpdatedTopicsReport(updatedReport, finishedNow)
        return updatedReport
    }

    private suspend fun recordInitialScannedTopicIds(
        uid: Long,
        allTopicIds: Collection<Long>,
        tradeTopicIds: Collection<Long>,
    ) {
        if (uid <= 0L) return
        scannedTopicsMutex.withLock {
            scannedTopicIdsByUid[uid] = LinkedHashSet(allTopicIds)
            scannedTradeTopicIdsByUid[uid] = LinkedHashSet(tradeTopicIds)
        }
    }

    private suspend fun deduplicateNewlyScannedTopics(
        uid: Long,
        fallbackRecentTradeIds: Collection<Long>,
        newlyFetched: Collection<TopicSummary>,
    ): Pair<Int, List<TopicSummary>> = scannedTopicsMutex.withLock {
        val seenAll = if (uid > 0L) {
            scannedTopicIdsByUid.getOrPut(uid) { LinkedHashSet(fallbackRecentTradeIds) }
        } else {
            LinkedHashSet(fallbackRecentTradeIds)
        }
        val seenTrades = if (uid > 0L) {
            scannedTradeTopicIdsByUid.getOrPut(uid) { LinkedHashSet(fallbackRecentTradeIds) }
        } else {
            LinkedHashSet(fallbackRecentTradeIds)
        }

        var newlySeenCount = 0
        val newTradeTopics = ArrayList<TopicSummary>()
        for (topic in newlyFetched) {
            val id = topic.id
            if (id <= 0L) continue
            if (seenAll.add(id)) {
                newlySeenCount++
            }
            if (TrustRiskEvaluator.isTradeTopic(topic) && seenTrades.add(id)) {
                newTradeTopics += topic
            }
        }
        newlySeenCount to newTradeTopics
    }

    private suspend fun persistUpdatedTopicsReport(
        report: SellerTrustReport,
        nowMillis: Long,
    ) {
        val keys = buildList {
            if (report.uid > 0L) add(buildCacheKey(uid = report.uid, username = report.username))
            if (report.username.isNotBlank()) add(buildCacheKey(uid = 0L, username = report.username))
        }.distinct()
        if (keys.isNotEmpty() && report.anyRemoteSucceeded) {
            val isFullSuccess = report.profileSucceeded && report.topicsSucceeded && report.searchSucceeded
            val ttlMillis = if (isFullSuccess) cacheTtlMillis else minOf(partialCacheTtlMillis, cacheTtlMillis)
            cacheStore.put(
                keys = keys,
                report = report,
                ttlMillis = ttlMillis,
                isNegative = false,
                nowMillis = nowMillis,
            )
        }
    }

    private fun formatTopicScanFailureReason(
        failure: Throwable?,
        cooldownUntil: Long,
        nowMillis: Long,
    ): String {
        val remSec = ((cooldownUntil - nowMillis + 999L) / 1_000L).coerceAtLeast(1L)
        return when (failure) {
            is SellerTrustRateLimitedException ->
                "触发限流保护，已保存当前进度，请约 ${remSec} 秒后继续扫描"
            is SellerTrustTimeoutException, is SocketTimeoutException ->
                "加载更早主题超时，已保存当前进度，可稍后继续扫描"
            is TopicAccessException.CloudflareChallengeRequired ->
                "触发安全验证，更早主题暂未加载完成"
            is TopicAccessException.SignInRequired ->
                "请先登录账号后再扫描更早主题"
            else -> failure?.message?.takeIf { it.isNotBlank() } ?: "更早主题加载受阻，请稍后重试"
        }
    }

    private fun firstRelevantException(aggregated: AggregationResult): Throwable? {
        val allErrors = listOfNotNull(
            aggregated.profileResult.exceptionOrNull(),
            aggregated.topicsResult.exceptionOrNull(),
            aggregated.exposeBoardResult?.exceptionOrNull(),
        )
        allErrors.firstOrNull { it is SellerTrustRateLimitedException }?.let { return it }
        allErrors.firstOrNull { it is SellerTrustTimeoutException }?.let { return it }
        return allErrors.firstOrNull()
    }

    private fun isUserNotFoundFailure(error: Throwable?): Boolean {
        val apiErr = error as? NodeSeekApiException ?: return false
        return apiErr.statusCode == 404 ||
            apiErr.serverMessage?.equals("USER NOT FOUND", ignoreCase = true) == true ||
            apiErr.message?.contains("没有找到这位用户") == true
    }

    private fun formatBoardCheckFailureReason(failure: Throwable?): String = when (failure) {
        is TopicAccessException.SignInRequired -> "需要登录后才可拉取最新曝光版列表"
        is TopicAccessException.CloudflareChallengeRequired -> "触发安全验证，曝光版巡检暂未完成"
        is SellerTrustRateLimitedException -> {
            if (failure.statusCode == 503) {
                "官网服务繁忙（503），曝光版巡检进入冷却保护"
            } else {
                "官网请求频繁触发冷却保护，请稍后刷新核验"
            }
        }
        is SellerTrustTimeoutException, is SocketTimeoutException -> "曝光版巡检超时，请稍后重试"
        is NodeSeekApiException -> when (failure.statusCode) {
            429 -> "官网请求频繁触发冷却保护，请稍后刷新核验"
            503 -> "官网服务繁忙（503），曝光版列表暂时不可用"
            401, 403 -> "当前未登录或无访问权限，曝光版巡检未完成"
            else -> failure.message?.takeIf { it.isNotBlank() } ?: "曝光版巡检暂时不可用"
        }
        else -> failure?.message?.takeIf { it.isNotBlank() } ?: "曝光版巡检暂时不可用"
    }

    private fun formatSearchFailureReason(failure: Throwable?): String = when (failure) {
        is TopicAccessException.SignInRequired -> "需要登录后才可使用站内历史曝光深搜"
        is TopicAccessException.CloudflareChallengeRequired -> "触发安全验证，站内深搜暂不可用（可使用 Google 查曝光）"
        is SellerTrustRateLimitedException -> {
            if (failure.statusCode == 503) {
                "官网服务繁忙（503），搜索进入冷却保护（可使用下方 Google 查曝光）"
            } else {
                "官网搜索触发限流保护，请稍后重试（可使用下方 Google 查曝光）"
            }
        }
        is SellerTrustTimeoutException, is SocketTimeoutException -> "站内搜索请求超时，请稍后重试（可使用 Google 查曝光）"
        is NodeSeekApiException -> when (failure.statusCode) {
            429 -> "官网搜索触发限流保护，请稍后重试（可使用下方 Google 查曝光）"
            503 -> "官网服务繁忙（503），站内曝光搜索暂时不可用"
            401, 403 -> "当前未登录或无搜索权限，站内曝光深搜未完成"
            else -> failure.message?.takeIf { it.isNotBlank() } ?: "站内曝光搜索暂时不可用"
        }
        else -> failure?.message?.takeIf { it.isNotBlank() } ?: "站内曝光搜索暂时不可用"
    }

    private fun formatGeneralFailureReason(
        failure: Throwable?,
        cooldownUntil: Long,
        nowMillis: Long,
    ): String {
        val remainingSec = ((cooldownUntil - nowMillis + 999L) / 1_000L).coerceAtLeast(1L)
        return when (failure) {
            is SellerTrustRateLimitedException -> {
                if (failure.statusCode == 503) {
                    "官网服务繁忙（503），已进入冷却期，请约 ${remainingSec} 秒后重试"
                } else {
                    "请求过于频繁触发官网限流，请约 ${remainingSec} 秒后重试"
                }
            }
            is SellerTrustTimeoutException, is SocketTimeoutException ->
                "连接官网超时，网络较慢或服务繁忙，可稍后手动重试"
            is TopicAccessException.CloudflareChallengeRequired ->
                "官网需要完成人机验证后才可继续查询"
            is TopicAccessException.SignInRequired ->
                "请先登录官网账号后再重试核验"
            is NodeSeekApiException -> when (failure.statusCode) {
                429 -> "请求过于频繁触发官网限流，请稍后重试"
                503 -> "官网服务暂时不可用（503），请稍后重试"
                404 -> failure.message?.takeIf { it.isNotBlank() } ?: "未找到该卖家账号信息"
                else -> failure.message?.takeIf { it.isNotBlank() } ?: "请求官网失败，请手动重试"
            }
            else -> failure?.message?.takeIf { it.isNotBlank() } ?: "请求官网失败，请检查网络后手动重试"
        }
    }

    private fun buildRateLimitFallbackMessage(
        cooldownUntil: Long,
        nowMillis: Long,
        hasCache: Boolean,
    ): String {
        val remainingSec = ((cooldownUntil - nowMillis + 999L) / 1_000L).coerceAtLeast(1L)
        return if (hasCache) {
            "官网限流冷却中（约 ${remainingSec}s 后恢复），当前展示本地缓存快照"
        } else {
            "请求过于频繁触发官网限流保护，请约 ${remainingSec} 秒后再试"
        }
    }

    private fun fallbackDisplayName(uid: Long, rawUsernameHint: String): String =
        rawUsernameHint.trim().ifEmpty {
            if (uid > 0L) "用户 #$uid" else "未知卖家"
        }

    private fun sanitizeUsernameHint(raw: String): String {
        val trimmed = raw.trim().removePrefix("@")
        if (trimmed.isEmpty()) return ""
        if (PLACEHOLDER_USER_REGEX.matches(trimmed)) return ""
        return trimmed
    }

    private fun buildCandidateKeys(uid: Long, cleanHint: String): List<String> = buildList {
        val primary = buildCacheKey(uid = uid, username = cleanHint)
        if (primary.isNotEmpty()) add(primary)
        if (uid > 0L && cleanHint.isNotBlank()) {
            add(buildCacheKey(uid = 0L, username = cleanHint))
        }
    }.distinct()

    private fun buildCacheKey(uid: Long, username: String): String = when {
        uid > 0L -> "uid:$uid"
        username.isNotBlank() -> "name:${username.lowercase()}"
        else -> ""
    }

    private data class TopicsScanOutcome(
        val topics: List<TopicSummary>,
        val pagesScanned: Int,
        val hasMorePages: Boolean,
    )

    private data class AggregationResult(
        val targetUid: Long,
        val canonicalUsername: String,
        val profileResult: Result<UserProfile>,
        val topicsResult: Result<TopicsScanOutcome>,
        val exposeBoardResult: Result<FeedPageResult>?,
        val localNote: LocalSellerNote?,
    )

    companion object {
        const val NOTES_FILE_NAME: String = "seller_trust_notes.json"
        const val CACHE_FILE_NAME: String = SellerTrustCacheStore.CACHE_FILE_NAME

        /** Positive full-success cache TTL: 18 hours (within 12–24h window). */
        const val DEFAULT_CACHE_TTL_MILLIS: Long = 18L * 60L * 60L * 1000L

        /** Partial-success cache TTL (e.g. profile succeeded while board check was unauthenticated): 15 minutes. */
        const val DEFAULT_PARTIAL_CACHE_TTL_MILLIS: Long = 15L * 60L * 1000L

        /** Negative cache TTL for failed / empty / unresolvable checks: 3 minutes. */
        const val DEFAULT_NEGATIVE_CACHE_TTL_MILLIS: Long = 3L * 60L * 1000L

        /** Minimum interval between manual `forceRefresh` requests when positive cache is present: 15s. */
        const val DEFAULT_MANUAL_REFRESH_MIN_INTERVAL_MILLIS: Long = 15_000L

        /** Minimum debounce interval between manual retries on failed state: 3s. */
        const val DEFAULT_FAILED_RETRY_MIN_INTERVAL_MILLIS: Long = 3_000L

        /** Shared `/categories/expose` board snapshot TTL across all sellers: 30 minutes. */
        const val DEFAULT_EXPOSE_BOARD_TTL_MILLIS: Long = 30L * 60L * 1000L

        /** Minimum spacing between manual refreshes of `/categories/expose`: 60 seconds. */
        const val DEFAULT_EXPOSE_BOARD_REFRESH_MIN_INTERVAL_MILLIS: Long = 60_000L

        /** Stale retention for `/categories/expose` board snapshot: 6 hours. */
        const val DEFAULT_EXPOSE_BOARD_STALE_RETENTION_MILLIS: Long = 6L * 60L * 60L * 1000L

        /** Minimum interval between explicit on-demand `/search` deep-scan calls: 10 seconds. */
        const val DEFAULT_SEARCH_MIN_INTERVAL_MILLIS: Long = 10_000L

        /** Base cooldown on `/search` 429/503 rate limits: 30 seconds. */
        const val DEFAULT_SEARCH_BASE_COOLDOWN_MILLIS: Long = 30_000L

        /** Default automatic `list-discussions` pages scanned on initial open: 6 pages (up to 90 topics). */
        const val DEFAULT_MAX_AUTO_TOPIC_PAGES: Int = 6

        /** Additional `list-discussions` pages scanned per explicit "继续扫描更早主题" tap: 8 pages (+120 topics). */
        const val DEFAULT_SCAN_MORE_TOPIC_PAGES: Int = 8

        private const val USER_TOPICS_PAGE_SIZE: Int = 15
        const val MAX_RECENT_TRADE_TOPICS: Int = 10
        private const val MAX_PREFETCH_QUEUE_SIZE: Int = 16
        private const val NODESEEK_ORIGIN: String = "https://www.nodeseek.com"
        private val PLACEHOLDER_USER_REGEX = Regex("^(?:用户|成员|user)\\s*#?\\d+$", RegexOption.IGNORE_CASE)

        private val sharedInstances = ConcurrentHashMap<String, SellerTrustRepositoryImpl>()

        internal fun defaultCacheFileFor(notesFile: File): File {
            val dir = notesFile.parentFile ?: File(".")
            val prefix = notesFile.nameWithoutExtension.ifBlank { "seller_trust" }
            val fileName = if (notesFile.name == NOTES_FILE_NAME) {
                CACHE_FILE_NAME
            } else {
                "${prefix}_cache_v1.json"
            }
            return File(dir, fileName)
        }

        internal fun defaultExposeBoardFileFor(cacheFile: File): File {
            val dir = cacheFile.parentFile ?: File(".")
            val prefix = cacheFile.nameWithoutExtension.ifBlank { "seller_trust_cache" }
            return File(dir, "${prefix}_expose_board.json")
        }

        /**
         * Returns a process-scoped [SellerTrustRepositoryImpl] backed by `filesDir/seller_trust_notes.json`
         * and `filesDir/seller_trust_cache_v1.json`. When [baseHttpClient] is supplied, uses the dedicated
         * lightweight [HttpSellerTrustRemoteDataSource] with bounded timeouts and browser headers.
         */
        fun forContext(
            context: Context,
            userRepository: UserRepository,
            forumRepository: ForumRepository,
            baseHttpClient: OkHttpClient? = null,
            userAgentProvider: UserAgentProvider? = null,
            sameOriginTransport: SameOriginTransport? = null,
            onConfigObserved: ((ConfigPayloadDto) -> Unit)? = null,
        ): SellerTrustRepositoryImpl {
            val appCtx = context.applicationContext ?: context
            val notesFile = File(appCtx.filesDir, NOTES_FILE_NAME)
            val cacheFile = File(appCtx.filesDir, CACHE_FILE_NAME)
            val key = runCatching { notesFile.canonicalPath }.getOrDefault(notesFile.absolutePath)

            val existing = sharedInstances[key]
            if (existing != null && (baseHttpClient == null || existing.usesDedicatedHttpDataSource)) {
                return existing
            }

            val remote: SellerTrustRemoteDataSource = if (baseHttpClient != null) {
                HttpSellerTrustRemoteDataSource(
                    baseHttpClient = baseHttpClient,
                    userAgentProvider = userAgentProvider ?: UserAgentProvider().also { it.initFromContext(appCtx) },
                    sameOriginTransport = sameOriginTransport,
                    onConfigObserved = onConfigObserved,
                    forumRepository = forumRepository,
                )
            } else {
                RepositorySellerTrustRemoteDataSource(
                    userRepository = userRepository,
                    forumRepository = forumRepository,
                )
            }

            val created = SellerTrustRepositoryImpl(
                remoteDataSource = remote,
                notesFile = notesFile,
                cacheFile = cacheFile,
            )
            sharedInstances[key] = created
            return created
        }
    }
}

@Serializable
internal data class PersistedExposeBoardSnapshot(
    val fetchedAtEpochMillis: Long,
    val page: FeedPageResult,
)

/**
 * Shared memory + disk cache for page 1 of `/categories/expose`, enabling zero-`/search`
 * exposure screening across all inspected sellers within a 30-minute freshness window.
 */
internal class ExposeBoardSnapshotStore(
    private val snapshotFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val freshTtlMillis: Long = SellerTrustRepositoryImpl.DEFAULT_EXPOSE_BOARD_TTL_MILLIS,
    private val staleRetentionMillis: Long = SellerTrustRepositoryImpl.DEFAULT_EXPOSE_BOARD_STALE_RETENTION_MILLIS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()
    private var loadedFromDisk = false
    private var snapshot: PersistedExposeBoardSnapshot? = null

    suspend fun getFresh(nowMillis: Long = clockMillis()): PersistedExposeBoardSnapshot? =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(nowMillis)
                val current = snapshot ?: return@withLock null
                if (current.page.isFromRssFallback) return@withLock null
                val age = nowMillis - current.fetchedAtEpochMillis
                if (age in 0L until freshTtlMillis) current else null
            }
        }

    suspend fun getStaleFallback(nowMillis: Long = clockMillis()): PersistedExposeBoardSnapshot? =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(nowMillis)
                val current = snapshot ?: return@withLock null
                if (current.page.isFromRssFallback) return@withLock null
                val age = nowMillis - current.fetchedAtEpochMillis
                if (age in 0L until staleRetentionMillis) current else null
            }
        }

    suspend fun getLastFetchedAtMillis(): Long =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(clockMillis())
                snapshot?.fetchedAtEpochMillis ?: 0L
            }
        }

    suspend fun put(
        page: FeedPageResult,
        nowMillis: Long = clockMillis(),
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                loadedFromDisk = true
                val next = PersistedExposeBoardSnapshot(
                    fetchedAtEpochMillis = nowMillis,
                    page = page,
                )
                snapshot = next
                writeToDiskLocked(next)
            }
        }
    }

    private fun ensureLoadedLocked(nowMillis: Long) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        if (!snapshotFile.isFile || snapshotFile.length() <= 0L) return
        val loaded = runCatching {
            val raw = snapshotFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) null else json.decodeFromString<PersistedExposeBoardSnapshot>(raw)
        }.getOrNull() ?: run {
            runCatching { snapshotFile.delete() }
            return
        }
        val age = nowMillis - loaded.fetchedAtEpochMillis
        if (age in 0L until staleRetentionMillis) {
            snapshot = loaded
        } else {
            runCatching { snapshotFile.delete() }
        }
    }

    private fun writeToDiskLocked(item: PersistedExposeBoardSnapshot) {
        runCatching {
            snapshotFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(item)
            val tmpFile = File(snapshotFile.parentFile, "${snapshotFile.name}.tmp")
            tmpFile.writeText(encoded, Charsets.UTF_8)
            if (!tmpFile.renameTo(snapshotFile)) {
                snapshotFile.writeText(encoded, Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }
}
