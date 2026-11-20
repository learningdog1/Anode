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
import okhttp3.OkHttpClient
import java.io.File
import java.net.SocketTimeoutException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Resilient [SellerTrustRepository] aggregating seller profile, recent trade topics,
 * station exposure searches (`"曝光 <username>"` & `"骗子 <username>"`), and persistent
 * device-local seller notes (`filesDir/seller_trust_notes.json`).
 *
 * Stability & anti-rate-limit guarantees:
 * - **Two-tier cache**: Memory LRU + persistent disk JSON (`seller_trust_cache_v1.json`) with
 *   long positive TTL (default 18h), medium partial TTL (15m), short negative TTL (3m) for
 *   failed/empty results, and 7-day stale positive fallback during rate-limit cooldowns.
 * - **Single-flight deduplication**: Concurrent calls for the same seller key coalesce into one pipeline.
 * - **Global concurrency cap & per-site pacing**: Outbound requests are bounded (`maxGlobalConcurrency = 2`)
 *   and spaced (`minSiteIntervalMillis = 350ms` + extra search interval) via [SellerTrustRateLimiter].
 * - **429 / 503 / timeout backoff & cooldown**: Respects `Retry-After` (delta-seconds & HTTP-date),
 *   applies exponential backoff + jitter, enters cooldown on rate limits, and serves cached or
 *   structured retry-later states without hammering the origin server.
 * - **Minimal payload path**: Fetches lightweight `/api/account/getInfo/{uid}` (without `readme=1`
 *   or `/api/block-list/list`) first to resolve canonical username (preventing duplicate hint +
 *   canonical searches) and skips `/api/content/list-discussions` when `nPost == 0`.
 */
class SellerTrustRepositoryImpl internal constructor(
    private val remoteDataSource: SellerTrustRemoteDataSource,
    notesFile: File,
    cacheFile: File = defaultCacheFileFor(notesFile),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val partialCacheTtlMillis: Long = DEFAULT_PARTIAL_CACHE_TTL_MILLIS,
    private val negativeCacheTtlMillis: Long = DEFAULT_NEGATIVE_CACHE_TTL_MILLIS,
    private val manualRefreshMinIntervalMillis: Long = DEFAULT_MANUAL_REFRESH_MIN_INTERVAL_MILLIS,
    private val failedRetryMinIntervalMillis: Long = DEFAULT_FAILED_RETRY_MIN_INTERVAL_MILLIS,
    private val searchExtraIntervalMillis: Long = DEFAULT_SEARCH_EXTRA_INTERVAL_MILLIS,
    maxMemoryCacheEntries: Int = SellerTrustCacheStore.DEFAULT_MAX_MEMORY_ENTRIES,
    maxDiskCacheEntries: Int = SellerTrustCacheStore.DEFAULT_MAX_DISK_ENTRIES,
    internal val rateLimiter: SellerTrustRateLimiter = SellerTrustRateLimiter(
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

    private val singleFlight = KeyedSingleFlight<String, SellerTrustReport>(ioDispatcher)
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val attemptMutex = Mutex()
    private val lastRemoteAttemptByKey = HashMap<String, Long>()

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

    override fun observeCachedReport(uid: Long): Flow<SellerTrustReport?> =
        cacheStore.observePositiveReportByUid(uid)

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

        // Step C: Fetch page-1 user discussions (if needed) and exposure searches via governed concurrency.
        val aggregated = coroutineScope {
            val topicsDeferred: Deferred<Result<List<TopicSummary>>> = async {
                when {
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
                        Result.success(emptyList())
                    }

                    else -> governedCall { remoteDataSource.fetchSellerTopicsPage1(targetUid) }
                }
            }

            val canRunExposureSearch = canonicalUsername.isNotEmpty() &&
                !(userExplicitlyNotFound && cleanHint.isEmpty())

            val exposeDeferred: Deferred<Result<FeedPageResult>>? = if (canRunExposureSearch) {
                async {
                    governedCall(extraIntervalMs = searchExtraIntervalMillis) {
                        remoteDataSource.searchExposureTopicsPage1(
                            query = "${TrustRiskEvaluator.TERM_EXPOSE} $canonicalUsername",
                        )
                    }
                }
            } else {
                null
            }

            val scamDeferred: Deferred<Result<FeedPageResult>>? = if (canRunExposureSearch) {
                async {
                    governedCall(extraIntervalMs = searchExtraIntervalMillis) {
                        remoteDataSource.searchExposureTopicsPage1(
                            query = "${TrustRiskEvaluator.TERM_SCAMMER} $canonicalUsername",
                        )
                    }
                }
            } else {
                null
            }

            AggregationResult(
                targetUid = profile?.uid?.takeIf { it > 0L } ?: targetUid,
                canonicalUsername = canonicalUsername,
                profileResult = profileResult,
                topicsResult = topicsDeferred.await(),
                exposeSearchResult = exposeDeferred?.await(),
                scamSearchResult = scamDeferred?.await(),
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

        val isFullSuccess = baseReport.profileSucceeded && baseReport.topicsSucceeded && baseReport.searchSucceeded
        val ttlMillis = if (isFullSuccess) {
            cacheTtlMillis
        } else {
            minOf(partialCacheTtlMillis, cacheTtlMillis)
        }

        val freshReport = baseReport.copy(
            fetchStatus = TrustFetchStatus.FRESH,
            cooldownUntilEpochMillis = effectiveCooldown,
            failureMessage = if (!baseReport.searchSucceeded) baseReport.searchFailureReason else null,
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

        val topicsSucceeded = aggregated.topicsResult.isSuccess
        val allUserTopics = aggregated.topicsResult.getOrNull().orEmpty()
        val tradeTopics = allUserTopics.filter(TrustRiskEvaluator::isTradeTopic)

        val candidates = mutableListOf<ExposureCandidate>()
        aggregated.exposeSearchResult?.getOrNull()?.let { page ->
            (page.pinnedTopics + page.topics).forEach { topic ->
                candidates += ExposureCandidate(
                    topic = topic,
                    queryTermHint = TrustRiskEvaluator.TERM_EXPOSE,
                )
            }
        }
        aggregated.scamSearchResult?.getOrNull()?.let { page ->
            (page.pinnedTopics + page.topics).forEach { topic ->
                candidates += ExposureCandidate(
                    topic = topic,
                    queryTermHint = TrustRiskEvaluator.TERM_SCAMMER,
                )
            }
        }

        val exposeSucceeded = aggregated.exposeSearchResult?.isSuccess == true
        val scamSucceeded = aggregated.scamSearchResult?.isSuccess == true
        val searchSucceeded = aggregated.canonicalUsername.isNotBlank() && exposeSucceeded && scamSucceeded

        val searchFailureReason = when {
            searchSucceeded -> null
            aggregated.canonicalUsername.isBlank() -> "未获取到卖家用户名，无法执行站内曝光检索"
            else -> {
                val failure = aggregated.exposeSearchResult?.exceptionOrNull()
                    ?: aggregated.scamSearchResult?.exceptionOrNull()
                formatSearchFailureReason(failure)
            }
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
        )
        return report.copy(
            localNote = note,
            riskAssessment = nextAssessment,
        )
    }

    private fun firstRelevantException(aggregated: AggregationResult): Throwable? {
        val allErrors = listOfNotNull(
            aggregated.profileResult.exceptionOrNull(),
            aggregated.topicsResult.exceptionOrNull(),
            aggregated.exposeSearchResult?.exceptionOrNull(),
            aggregated.scamSearchResult?.exceptionOrNull(),
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

    private fun formatSearchFailureReason(failure: Throwable?): String = when (failure) {
        is TopicAccessException.SignInRequired -> "需要登录后才可使用站内曝光搜索"
        is TopicAccessException.CloudflareChallengeRequired -> "触发安全验证，站内曝光检索暂不可用"
        is SellerTrustRateLimitedException -> {
            if (failure.statusCode == 503) {
                "官网服务繁忙（503），站内曝光检索进入冷却保护"
            } else {
                "站内搜索请求过于频繁（限流），请稍后刷新核验"
            }
        }
        is SellerTrustTimeoutException, is SocketTimeoutException -> "站内搜索请求超时，请稍后重试"
        is NodeSeekApiException -> when (failure.statusCode) {
            429 -> "站内搜索请求过于频繁（限流），请稍后刷新核验"
            503 -> "官网服务繁忙（503），站内曝光搜索暂时不可用"
            401, 403 -> "当前未登录或无搜索权限，站内曝光检索未完成"
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

    private data class AggregationResult(
        val targetUid: Long,
        val canonicalUsername: String,
        val profileResult: Result<UserProfile>,
        val topicsResult: Result<List<TopicSummary>>,
        val exposeSearchResult: Result<FeedPageResult>?,
        val scamSearchResult: Result<FeedPageResult>?,
        val localNote: LocalSellerNote?,
    )

    companion object {
        const val NOTES_FILE_NAME: String = "seller_trust_notes.json"
        const val CACHE_FILE_NAME: String = SellerTrustCacheStore.CACHE_FILE_NAME

        /** Positive full-success cache TTL: 18 hours (within 12–24h window). */
        const val DEFAULT_CACHE_TTL_MILLIS: Long = 18L * 60L * 60L * 1000L

        /** Partial-success cache TTL (e.g. profile succeeded while search was unauthenticated): 15 minutes. */
        const val DEFAULT_PARTIAL_CACHE_TTL_MILLIS: Long = 15L * 60L * 1000L

        /** Negative cache TTL for failed / empty / unresolvable checks: 3 minutes. */
        const val DEFAULT_NEGATIVE_CACHE_TTL_MILLIS: Long = 3L * 60L * 1000L

        /** Minimum interval between manual `forceRefresh` requests when positive cache is present: 15s. */
        const val DEFAULT_MANUAL_REFRESH_MIN_INTERVAL_MILLIS: Long = 15_000L

        /** Minimum debounce interval between manual retries on failed state: 3s. */
        const val DEFAULT_FAILED_RETRY_MIN_INTERVAL_MILLIS: Long = 3_000L

        /** Extra spacing before HTML `/search` queries to avoid tripping search WAF limits. */
        const val DEFAULT_SEARCH_EXTRA_INTERVAL_MILLIS: Long = 250L

        const val MAX_RECENT_TRADE_TOPICS: Int = 5
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
