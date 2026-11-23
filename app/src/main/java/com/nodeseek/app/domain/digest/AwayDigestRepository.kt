package com.nodeseek.app.domain.digest

import com.nodeseek.app.data.local.AwayDigestStore
import com.nodeseek.app.data.local.CachedTopicDrumstickMeta
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates away-window detection, dismissal persistence, multi-page away-window scanning,
 * bounded `getTopicDetail` drumstick enrichment, and reactive read-history skipping.
 */
interface AwayDigestRepository {
    val state: StateFlow<AwayDigestState>

    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis())
    fun onAppBackgrounded(nowMillis: Long = System.currentTimeMillis())
    fun onAppClosed(nowMillis: Long = System.currentTimeMillis()) = Unit
    fun dismissCurrentSession()
    fun refreshCurrentSessionDigest(forceReload: Boolean = false)
    fun markTopicVisited(topicId: Long)
}

object NoOpAwayDigestRepository : AwayDigestRepository {
    private val emptyState = MutableStateFlow(AwayDigestState())
    override val state: StateFlow<AwayDigestState> = emptyState.asStateFlow()

    override fun onAppForegrounded(nowMillis: Long) = Unit
    override fun onAppBackgrounded(nowMillis: Long) = Unit
    override fun onAppClosed(nowMillis: Long) = Unit
    override fun dismissCurrentSession() = Unit
    override fun refreshCurrentSessionDigest(forceReload: Boolean) = Unit
    override fun markTopicVisited(topicId: Long) = Unit
}

private data class ForegroundTransitionSnapshot(
    val sessionId: String,
    val awayStartMillis: Long,
    val reopenedAtMillis: Long,
    val awayDurationMillis: Long,
    val wasAppClosedReopen: Boolean,
)

private data class InternalAwayDigestRawState(
    val activeSession: AwaySession? = null,
    val isLoading: Boolean = false,
    val rawCandidates: List<TopicSummary> = emptyList(),
    val enrichedMetaById: Map<Long, CachedTopicDrumstickMeta> = emptyMap(),
    val curatedAwardIds: Set<Long> = emptySet(),
    val localVisitedIds: Set<Long> = emptySet(),
    val minAllowedTopicId: Long? = null,
    val unverifiedCreationTopicIds: Set<Long> = emptySet(),
    val pagesScanned: Int = 0,
    val errorMessage: String? = null,
)

class AwayDigestRepositoryImpl(
    private val store: AwayDigestStore,
    private val forumRepository: ForumRepository,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val minAwayDurationMillis: Long? = null,
    private val maxPagesToScan: Int? = null,
    private val maxDigestItems: Int = DEFAULT_MAX_DIGEST_ITEMS,
    private val maxDetailEnrichCandidates: Int = DEFAULT_MAX_DETAIL_ENRICH_CANDIDATES,
    private val detailConcurrency: Int = DEFAULT_DETAIL_CONCURRENCY,
) : AwayDigestRepository {

    private val lifecycleLock = Any()
    private var isInForeground: Boolean = false
    private var hasForegroundedInThisProcess: Boolean = false
    private var pendingAppClosedReopen: Boolean = store.wasAppClosed()
    private var lastForegroundTransition: ForegroundTransitionSnapshot? = null
    private var loadedSessionId: String? = null
    private var scanJob: Job? = null

    private fun isSessionExpired(session: AwaySession, nowMillis: Long = clockMillis()): Boolean =
        !session.isDismissed && (nowMillis - session.reopenedAtMillis) > STALE_ACTIVE_SESSION_TTL_MILLIS

    private fun isPersistedSessionFresh(session: AwaySession?, nowMillis: Long = clockMillis()): Boolean =
        session != null && !session.isDismissed && !isSessionExpired(session, nowMillis)

    private val _rawState = MutableStateFlow(
        InternalAwayDigestRawState(
            activeSession = store.getActiveSession()?.takeIf { isPersistedSessionFresh(it) },
        ),
    )

    private val _state = MutableStateFlow(
        AwayDigestState(
            activeSession = store.getActiveSession()?.takeIf {
                isPersistedSessionFresh(it) && settingsRepository.settings.value.awayDigestEnabled
            },
        ),
    )
    override val state: StateFlow<AwayDigestState> = _state.asStateFlow()

    private val localVisitedIdsFlow = _rawState
        .map { it.localVisitedIds }
        .distinctUntilChanged()

    private val readHistoryIdsFlow = bookmarkAndHistoryRepository.observeReadHistory()
        .combine(localVisitedIdsFlow) { entries, localVisited ->
            buildSet(entries.size + localVisited.size) {
                entries.forEach { add(it.topicId) }
                addAll(localVisited)
            }
        }
        .distinctUntilChanged()

    init {
        scope.launch {
            combine(
                _rawState,
                readHistoryIdsFlow,
                bookmarkAndHistoryRepository.observeBookmarkedIds(),
                contentFilterRepository.config,
                settingsRepository.settings,
            ) { raw, readIds, bookmarkedIds, filterConfig, settings ->
                if (!settings.awayDigestEnabled) {
                    return@combine AwayDigestState()
                }
                val session = raw.activeSession
                if (session == null || session.isDismissed) {
                    return@combine AwayDigestState(activeSession = session)
                }

                val ranked = AwayDigestEngine.rankUnreadDigest(
                    candidates = raw.rawCandidates,
                    readTopicIds = readIds,
                    bookmarkedTopicIds = bookmarkedIds,
                    filterConfig = filterConfig,
                    includeBlockedTopics = settings.includeBlockedTopics,
                    enrichedMetaById = raw.enrichedMetaById,
                    curatedAwardIds = raw.curatedAwardIds,
                    awayStartMillis = session.awayStartMillis,
                    reopenedAtMillis = session.reopenedAtMillis,
                    maxDigestItems = maxDigestItems,
                    minAllowedTopicId = raw.minAllowedTopicId,
                    unverifiedCreationTopicIds = raw.unverifiedCreationTopicIds,
                )

                AwayDigestState(
                    activeSession = session,
                    isLoading = raw.isLoading,
                    items = ranked.topItems,
                    totalUnreadInWindow = ranked.totalUnreadInWindow,
                    skippedReadCount = ranked.skippedReadCount,
                    pagesScanned = raw.pagesScanned,
                    errorMessage = raw.errorMessage,
                )
            }.collect { computed ->
                synchronized(lifecycleLock) {
                    if (!settingsRepository.settings.value.awayDigestEnabled) {
                        _state.value = AwayDigestState()
                    } else {
                        val latestRaw = _rawState.value
                        if (computed.activeSession?.sessionId == latestRaw.activeSession?.sessionId &&
                            computed.activeSession?.isDismissed == latestRaw.activeSession?.isDismissed &&
                            computed.isLoading == latestRaw.isLoading
                        ) {
                            _state.value = computed
                        }
                    }
                }
            }
        }

        scope.launch {
            settingsRepository.settings.collect { settings ->
                onSettingsChanged(settings)
            }
        }

        // If an un-dismissed session was persisted across process death before UI attaches, load it on demand,
        // and re-fetch if WebView verification completes after an initial challenge failure.
        scope.launch {
            sessionRepository.authVerifiedEpoch
                .drop(1)
                .collect { epoch ->
                    if (epoch > 0L && settingsRepository.settings.value.awayDigestEnabled) {
                        val current = _rawState.value
                        val session = current.activeSession
                        if (session != null &&
                            !session.isDismissed &&
                            current.rawCandidates.isEmpty() &&
                            !current.isLoading
                        ) {
                            startDigestScan(session = session, forceReload = true)
                        }
                    }
                }
        }
    }

    private fun onSettingsChanged(settings: AppSettings) {
        var sessionToScan: AwaySession? = null
        var clearActive = false

        synchronized(lifecycleLock) {
            if (!settings.awayDigestEnabled) {
                scanJob?.cancel()
                scanJob = null
                loadedSessionId = null
                _rawState.update { it.copy(isLoading = false) }
                _state.value = AwayDigestState()
                return
            }

            val transition = lastForegroundTransition
            val persisted = store.getActiveSession()?.takeUnless { isSessionExpired(it) }
            val currentSession = _rawState.value.activeSession?.takeUnless { isSessionExpired(it) } ?: persisted

            if (isInForeground && transition != null) {
                val qualifies = AwayDigestEngine.shouldTriggerSession(
                    settings = settings,
                    awayDurationMillis = transition.awayDurationMillis,
                    wasAppClosedReopen = transition.wasAppClosedReopen,
                    minAwayDurationMillisOverride = minAwayDurationMillis,
                )
                if (qualifies) {
                    val alreadyDismissed = (persisted?.sessionId == transition.sessionId && persisted.isDismissed) ||
                        (currentSession?.sessionId == transition.sessionId && currentSession.isDismissed)
                    if (!alreadyDismissed) {
                        if (currentSession?.sessionId == transition.sessionId) {
                            if (_rawState.value.rawCandidates.isEmpty() && loadedSessionId != transition.sessionId) {
                                sessionToScan = currentSession
                            }
                        } else {
                            val promoted = AwaySession(
                                sessionId = transition.sessionId,
                                awayStartMillis = transition.awayStartMillis,
                                reopenedAtMillis = transition.reopenedAtMillis,
                                awayDurationMillis = transition.awayDurationMillis,
                                isDismissed = false,
                            )
                            store.setActiveSession(promoted)
                            sessionToScan = promoted
                        }
                    }
                } else if (currentSession != null && !currentSession.isDismissed) {
                    val thresholdMillis = resolveEffectiveThresholdMillis(settings)
                    val invalidForCurrentSettings = currentSession.sessionId == transition.sessionId ||
                        (settings.awayDigestTriggerMode == AwayDigestTriggerMode.CUSTOM_DURATION &&
                            currentSession.awayDurationMillis < thresholdMillis)
                    if (invalidForCurrentSettings) {
                        store.setActiveSession(null)
                        scanJob?.cancel()
                        scanJob = null
                        loadedSessionId = null
                        clearActive = true
                    }
                }
            } else if (currentSession != null && !currentSession.isDismissed) {
                if (_rawState.value.rawCandidates.isEmpty() &&
                    !_rawState.value.isLoading &&
                    loadedSessionId != currentSession.sessionId
                ) {
                    sessionToScan = currentSession
                }
            }
        }

        if (clearActive) {
            _rawState.update {
                it.copy(
                    activeSession = null,
                    isLoading = false,
                    rawCandidates = emptyList(),
                    errorMessage = null,
                )
            }
            _state.value = AwayDigestState()
        } else if (sessionToScan != null) {
            startDigestScan(session = sessionToScan!!, forceReload = true)
        }
    }

    private fun resolveEffectiveThresholdMillis(settings: AppSettings): Long =
        minAwayDurationMillis
            ?: (
                settings.awayDigestThresholdMinutes.coerceIn(
                    AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
                    AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
                ) * 60_000L
                )

    override fun onAppForegrounded(nowMillis: Long) {
        var sessionToLoad: AwaySession? = null
        var forceReload = false

        synchronized(lifecycleLock) {
            if (isInForeground) {
                return
            }
            isInForeground = true

            val isColdProcessLaunch = !hasForegroundedInThisProcess
            hasForegroundedInThisProcess = true

            val wasExplicitlyClosed = pendingAppClosedReopen || store.wasAppClosed()
            pendingAppClosedReopen = false
            store.setAppClosed(false)

            val previousLastActive = store.getLastActiveTimestampMillis()
            val rawPersistedSession = store.getActiveSession()
            val persistedSession = if (rawPersistedSession != null &&
                !rawPersistedSession.isDismissed &&
                !isPersistedSessionFresh(rawPersistedSession, nowMillis)
            ) {
                store.setActiveSession(rawPersistedSession.copy(isDismissed = true))
                null
            } else {
                rawPersistedSession
            }
            val currentSettings = settingsRepository.settings.value

            if (previousLastActive <= 0L) {
                // First-ever app launch: record baseline timestamp without showing an away banner.
                lastForegroundTransition = null
                store.setLastActiveTimestampMillis(nowMillis)
                if (currentSettings.awayDigestEnabled && persistedSession != null && !persistedSession.isDismissed) {
                    sessionToLoad = persistedSession
                }
            } else {
                val awayDuration = (nowMillis - previousLastActive).coerceAtLeast(0L)
                val wasAppClosedReopen = isColdProcessLaunch || wasExplicitlyClosed
                val transition = ForegroundTransitionSnapshot(
                    sessionId = "${previousLastActive}_$nowMillis",
                    awayStartMillis = previousLastActive,
                    reopenedAtMillis = nowMillis,
                    awayDurationMillis = awayDuration,
                    wasAppClosedReopen = wasAppClosedReopen,
                )
                lastForegroundTransition = transition
                store.setLastActiveTimestampMillis(nowMillis)

                val shouldTrigger = AwayDigestEngine.shouldTriggerSession(
                    settings = currentSettings,
                    awayDurationMillis = awayDuration,
                    wasAppClosedReopen = wasAppClosedReopen,
                    minAwayDurationMillisOverride = minAwayDurationMillis,
                )
                if (shouldTrigger) {
                    val newSession = AwaySession(
                        sessionId = transition.sessionId,
                        awayStartMillis = previousLastActive,
                        reopenedAtMillis = nowMillis,
                        awayDurationMillis = awayDuration,
                        isDismissed = false,
                    )
                    store.setActiveSession(newSession)
                    sessionToLoad = newSession
                    forceReload = true
                } else if (currentSettings.awayDigestEnabled && persistedSession != null && !persistedSession.isDismissed) {
                    sessionToLoad = persistedSession
                    forceReload = false
                } else if (persistedSession == null && _rawState.value.activeSession != null) {
                    _rawState.update {
                        it.copy(
                            activeSession = null,
                            isLoading = false,
                            rawCandidates = emptyList(),
                            errorMessage = null,
                        )
                    }
                    _state.value = AwayDigestState()
                }
            }
        }

        val target = sessionToLoad ?: return
        if (forceReload || loadedSessionId != target.sessionId) {
            startDigestScan(session = target, forceReload = forceReload)
        }
    }

    override fun onAppBackgrounded(nowMillis: Long) {
        synchronized(lifecycleLock) {
            isInForeground = false
            lastForegroundTransition = null
            store.setLastActiveTimestampMillis(nowMillis)
        }
    }

    override fun onAppClosed(nowMillis: Long) {
        synchronized(lifecycleLock) {
            val wasForeground = isInForeground
            isInForeground = false
            lastForegroundTransition = null
            pendingAppClosedReopen = true
            store.setAppClosed(true)
            if (wasForeground) {
                store.setLastActiveTimestampMillis(nowMillis)
            }
        }
    }

    override fun dismissCurrentSession() {
        val dismissedSession: AwaySession?
        synchronized(lifecycleLock) {
            val current = _rawState.value.activeSession ?: store.getActiveSession()
            dismissedSession = current?.copy(isDismissed = true)
            if (dismissedSession != null) {
                store.setActiveSession(dismissedSession)
            }
            scanJob?.cancel()
            scanJob = null
        }
        _rawState.update {
            it.copy(
                activeSession = dismissedSession,
                isLoading = false,
                rawCandidates = emptyList(),
                errorMessage = null,
            )
        }
        _state.value = AwayDigestState(activeSession = dismissedSession)
    }

    override fun refreshCurrentSessionDigest(forceReload: Boolean) {
        if (!settingsRepository.settings.value.awayDigestEnabled) return
        val session = synchronized(lifecycleLock) {
            val s = _rawState.value.activeSession ?: store.getActiveSession()
            if (!isPersistedSessionFresh(s)) null else s
        } ?: return

        if (forceReload || loadedSessionId != session.sessionId) {
            startDigestScan(session = session, forceReload = forceReload)
        }
    }

    override fun markTopicVisited(topicId: Long) {
        if (topicId <= 0L) return
        synchronized(lifecycleLock) {
            if (isInForeground) {
                store.setLastActiveTimestampMillis(clockMillis())
            }
        }
        _rawState.update { state ->
            if (topicId in state.localVisitedIds) {
                state
            } else {
                state.copy(localVisitedIds = state.localVisitedIds + topicId)
            }
        }
    }

    private fun startDigestScan(
        session: AwaySession,
        forceReload: Boolean,
    ) {
        synchronized(lifecycleLock) {
            if (!forceReload && loadedSessionId == session.sessionId && scanJob?.isActive == true) {
                return
            }
            loadedSessionId = session.sessionId
            scanJob?.cancel()
            _rawState.update { prev ->
                val sameSession = prev.activeSession?.sessionId == session.sessionId
                InternalAwayDigestRawState(
                    activeSession = session,
                    isLoading = true,
                    rawCandidates = if (sameSession && !forceReload) prev.rawCandidates else emptyList(),
                    enrichedMetaById = prev.enrichedMetaById,
                    curatedAwardIds = if (sameSession && !forceReload) prev.curatedAwardIds else emptySet(),
                    localVisitedIds = if (sameSession) prev.localVisitedIds else emptySet(),
                    minAllowedTopicId = if (sameSession && !forceReload) prev.minAllowedTopicId else null,
                    unverifiedCreationTopicIds = if (sameSession && !forceReload) prev.unverifiedCreationTopicIds else emptySet(),
                    pagesScanned = 0,
                    errorMessage = null,
                )
            }
            _state.update { prev ->
                val sameSession = prev.activeSession?.sessionId == session.sessionId
                if (sameSession && !forceReload) {
                    prev.copy(activeSession = session, isLoading = true, errorMessage = null)
                } else {
                    AwayDigestState(activeSession = session, isLoading = true)
                }
            }
            scanJob = scope.launch {
                executeAwayWindowScan(session)
            }
        }
    }

    private suspend fun executeAwayWindowScan(session: AwaySession) {
        try {
            val includeBlocked = settingsRepository.settings.value.includeBlockedTopics
            val collectedById = LinkedHashMap<Long, TopicSummary>()
            val cachedMetaMap = LinkedHashMap<Long, CachedTopicDrumstickMeta>()
            val unverifiedOriginIds = HashSet<Long>()
            val lowerBound = AwayDigestEngine.resolveEffectiveLowerBound(session.awayStartMillis)
            val upperBound = session.reopenedAtMillis + AwayDigestEngine.CLOCK_SKEW_TOLERANCE_MILLIS
            var maxInWindowNewestId = 0L
            var maxConfirmedPreWindowId: Long? = null
            var consecutivePreWindowInNewest = 0
            var crossedNewestCreationBoundary = false
            var pagesScanned = 0
            var lastError: String? = null

            fun upsertCandidate(topic: TopicSummary) {
                if (topic.id <= 0L || topic.isPinned) return
                val prev = collectedById[topic.id]
                collectedById[topic.id] = if (prev == null) {
                    topic
                } else {
                    prev.copy(
                        viewCount = maxOf(prev.viewCount, topic.viewCount),
                        replyCount = maxOf(prev.replyCount, topic.replyCount),
                        drumstickCount = maxOf(prev.drumstickCount, topic.drumstickCount),
                        isRead = prev.isRead || topic.isRead,
                        isBookmarked = prev.isBookmarked || topic.isBookmarked,
                        categorySlug = prev.categorySlug.ifBlank { topic.categorySlug },
                        categoryName = prev.categoryName.ifBlank { topic.categoryName },
                        lastActivityIso = prev.lastActivityIso.ifBlank { topic.lastActivityIso },
                    )
                }
                if (!cachedMetaMap.containsKey(topic.id)) {
                    store.getCachedTopicMeta(topic.id)?.let { cachedMetaMap[topic.id] = it }
                }
            }

            /**
             * Enriches view/reply/drumstick counts for candidates ALREADY discovered in the
             * chronological `NEWEST_TOPIC` away window, without injecting old bumped threads from
             * `LATEST_REPLY` or historical `/award` archives into [collectedById].
             */
            fun enrichExistingCandidatesOnly(topics: List<TopicSummary>) {
                for (topic in topics) {
                    if (topic.id <= 0L || topic.isPinned) continue
                    val prev = collectedById[topic.id] ?: continue
                    collectedById[topic.id] = prev.copy(
                        viewCount = maxOf(prev.viewCount, topic.viewCount),
                        replyCount = maxOf(prev.replyCount, topic.replyCount),
                        drumstickCount = maxOf(prev.drumstickCount, topic.drumstickCount),
                        isRead = prev.isRead || topic.isRead,
                        isBookmarked = prev.isBookmarked || topic.isBookmarked,
                        categorySlug = prev.categorySlug.ifBlank { topic.categorySlug },
                        categoryName = prev.categoryName.ifBlank { topic.categoryName },
                    )
                }
            }

            // 1. Multi-page scan of chronological posts (`FeedSort.NEWEST_TOPIC`) across pages 1..effectiveMaxPages,
            //    stopping early once topic timestamps fall before `session.awayStartMillis`.
            val effectiveMaxPages = (
                maxPagesToScan ?: AwayDigestEngine.recommendedMaxPagesToScan(session.awayDurationMillis)
                ).coerceAtLeast(1)
            var usedFallbackSort = false
            for (page in 1..effectiveMaxPages) {
                val result = forumRepository.getFeed(
                    categorySlug = "",
                    sort = FeedSort.NEWEST_TOPIC,
                    page = page,
                    includeBlocked = includeBlocked,
                    forceRefresh = page == 1,
                )
                val pageData = result.getOrNull()
                if (pageData == null) {
                    lastError = result.exceptionOrNull()?.message
                    break
                }
                pagesScanned += 1
                if (page == 1 && pageData.topics.isEmpty() && !pageData.hasNextPage) {
                    usedFallbackSort = true
                    break
                }

                for (topic in pageData.topics) {
                    if (topic.id <= 0L || topic.isPinned) continue
                    val cachedMeta = cachedMetaMap[topic.id] ?: store.getCachedTopicMeta(topic.id)?.also {
                        cachedMetaMap[topic.id] = it
                    }
                    val createdMillis = AwayDigestEngine.parseTopicTimestampMillis(cachedMeta?.createdAtIso)
                    val activityMillis = AwayDigestEngine.parseTopicTimestampMillis(topic.lastActivityIso)

                    // `createdAt <= lastActivity` always holds, so `activityMillis < lowerBound` proves
                    // the topic was created before `lowerBound`.
                    val confirmedPreWindow = (createdMillis != null && createdMillis < lowerBound) ||
                        (activityMillis != null && activityMillis < lowerBound)
                    if (confirmedPreWindow) {
                        consecutivePreWindowInNewest += 1
                        if (!pageData.isFromRssFallback && consecutivePreWindowInNewest >= 2) {
                            crossedNewestCreationBoundary = true
                        }
                        maxConfirmedPreWindowId = maxOf(maxConfirmedPreWindowId ?: topic.id, topic.id)
                        continue
                    }

                    val inWindow = if (createdMillis != null) {
                        createdMillis in lowerBound..upperBound
                    } else {
                        activityMillis != null && activityMillis in lowerBound..upperBound
                    }
                    if (!inWindow) continue

                    if (!crossedNewestCreationBoundary) {
                        consecutivePreWindowInNewest = 0
                        upsertCandidate(topic)
                        maxInWindowNewestId = maxOf(maxInWindowNewestId, topic.id)
                    }
                }

                val stopEarly = !pageData.hasNextPage ||
                    pageData.isFromRssFallback ||
                    AwayDigestEngine.shouldStopMultiPageScan(
                        pageTopics = pageData.topics,
                        awayStartMillis = session.awayStartMillis,
                    )
                if (stopEarly) {
                    break
                }
            }

            // Fallback to `FeedSort.LATEST_REPLY` multi-page scan ONLY if `NEWEST_TOPIC` returned empty;
            // every fallback candidate requires `getTopicDetail` `createdAtIso` verification before display.
            if (usedFallbackSort) {
                pagesScanned = 0
                for (page in 1..effectiveMaxPages) {
                    val result = forumRepository.getFeed(
                        categorySlug = "",
                        sort = FeedSort.LATEST_REPLY,
                        page = page,
                        includeBlocked = includeBlocked,
                        forceRefresh = page == 1,
                    )
                    val pageData = result.getOrNull()
                    if (pageData == null) {
                        lastError = result.exceptionOrNull()?.message
                        break
                    }
                    pagesScanned += 1
                    for (topic in pageData.topics) {
                        if (topic.id <= 0L || topic.isPinned) continue
                        val activityMillis = AwayDigestEngine.parseTopicTimestampMillis(topic.lastActivityIso)
                        if (activityMillis != null && activityMillis in lowerBound..upperBound) {
                            upsertCandidate(topic)
                            unverifiedOriginIds.add(topic.id)
                        }
                    }
                    val stopEarly = !pageData.hasNextPage ||
                        pageData.isFromRssFallback ||
                        AwayDigestEngine.shouldStopMultiPageScan(
                            pageTopics = pageData.topics,
                            awayStartMillis = session.awayStartMillis,
                        )
                    if (stopEarly) {
                        break
                    }
                }
            } else {
                // Only enrich stats for candidates ALREADY found in `NEWEST_TOPIC` (never inject old bumped threads)
                val cachedLatest = forumRepository.getCachedFeed("", FeedSort.LATEST_REPLY)
                if (cachedLatest != null) {
                    enrichExistingCandidatesOnly(cachedLatest.topics)
                }
            }

            // 2. Best-effort check of Curated (`/award` 推荐阅读) page 1 to tag award badges on in-window topics
            //    without polluting the away digest with historical/ancient `/award` threads.
            val curatedIds = HashSet<Long>()
            if (!sessionRepository.challengeRequired.value) {
                forumRepository.getCuratedFeed(page = 1, forceRefresh = false)
                    .getOrNull()
                    ?.let { curatedPage ->
                        curatedPage.topics.forEach { topic ->
                            if (topic.id > 0L) {
                                curatedIds.add(topic.id)
                            }
                        }
                        enrichExistingCandidatesOnly(curatedPage.topics)
                    }
            }

            val minAllowedTopicId = AwayDigestEngine.computeMinPlausibleTopicId(
                maxInWindowTopicId = maxInWindowNewestId,
                maxConfirmedPreWindowTopicId = maxConfirmedPreWindowId,
                awayDurationMillis = session.awayDurationMillis,
            )

            val candidatesList = collectedById.values
                .filter { minAllowedTopicId == null || it.id >= minAllowedTopicId }
            if (candidatesList.isEmpty()) {
                _rawState.update { state ->
                    if (state.activeSession?.sessionId != session.sessionId) {
                        state
                    } else {
                        state.copy(
                            isLoading = false,
                            rawCandidates = emptyList(),
                            minAllowedTopicId = minAllowedTopicId,
                            unverifiedCreationTopicIds = unverifiedOriginIds,
                            pagesScanned = pagesScanned,
                            errorMessage = lastError,
                        )
                    }
                }
                return
            }

            // Publish intermediate feed-ranked candidates immediately while detail enrichment runs
            _rawState.update { state ->
                if (state.activeSession?.sessionId != session.sessionId || state.activeSession.isDismissed) {
                    state
                } else {
                    state.copy(
                        rawCandidates = candidatesList,
                        enrichedMetaById = state.enrichedMetaById + cachedMetaMap,
                        curatedAwardIds = curatedIds,
                        minAllowedTopicId = minAllowedTopicId,
                        unverifiedCreationTopicIds = unverifiedOriginIds,
                        pagesScanned = pagesScanned,
                    )
                }
            }

            // 3. Enrich shortlisted top unread away-window candidates via `getTopicDetail` with bounded concurrency
            //    so genuine OP 鸡腿数 (`openingFloor.drumstickCount`), `isCuratedAward`, and `createdAtIso` are factored in!
            val currentReadIds = runCatching {
                bookmarkAndHistoryRepository.observeReadHistory().first().mapTo(HashSet()) { it.topicId }
            }.getOrDefault(HashSet()).apply {
                addAll(_rawState.value.localVisitedIds)
            }

            val shortlist = if (!sessionRepository.challengeRequired.value && maxDetailEnrichCandidates > 0) {
                AwayDigestEngine.shortlistForDetailEnrichment(
                    candidates = candidatesList,
                    readTopicIds = currentReadIds,
                    filterConfig = contentFilterRepository.config.value,
                    includeBlockedTopics = includeBlocked,
                    enrichedMetaById = cachedMetaMap,
                    curatedAwardIds = curatedIds,
                    awayStartMillis = session.awayStartMillis,
                    reopenedAtMillis = session.reopenedAtMillis,
                    limit = maxDetailEnrichCandidates,
                    minAllowedTopicId = minAllowedTopicId,
                ).filter { candidate ->
                    val cached = cachedMetaMap[candidate.id]
                    cached == null ||
                        cached.createdAtIso.isNullOrBlank() ||
                        (clockMillis() - cached.cachedAtMillis) > DETAIL_META_TTL_MILLIS
                }
            } else {
                emptyList()
            }

            val freshlyEnriched = if (shortlist.isNotEmpty()) {
                val semaphore = Semaphore(detailConcurrency.coerceAtLeast(1))
                coroutineScope {
                    shortlist.map { candidate ->
                        async {
                            semaphore.withPermit {
                                withTimeoutOrNull(DETAIL_ENRICH_TIMEOUT_MILLIS) {
                                    forumRepository.getTopicDetail(
                                        topicId = candidate.id,
                                        page = 1,
                                        includeBlocked = includeBlocked,
                                    ).getOrNull()
                                }?.let { detail ->
                                    val opDrumsticks = detail.openingFloor?.drumstickCount
                                        ?: detail.replies.sumOf { it.drumstickCount.coerceAtLeast(0) }
                                    val resolvedDrumsticks = maxOf(candidate.drumstickCount, opDrumsticks)
                                    val resolvedCreatedAtIso = detail.createdAtIso?.takeIf { it.isNotBlank() }
                                        ?: detail.openingFloor?.createdAtIso?.takeIf { it.isNotBlank() }
                                    CachedTopicDrumstickMeta(
                                        topicId = candidate.id,
                                        drumstickCount = resolvedDrumsticks,
                                        viewCount = maxOf(candidate.viewCount, detail.viewCount),
                                        replyCount = maxOf(candidate.replyCount, detail.estimatedTotalFloors),
                                        isCuratedAward = detail.isCuratedAward || candidate.id in curatedIds,
                                        createdAtIso = resolvedCreatedAtIso,
                                        cachedAtMillis = clockMillis(),
                                    ).also { meta ->
                                        store.putCachedTopicMeta(meta)
                                    }
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            } else {
                emptyList()
            }

            val mergedMeta = buildMap<Long, CachedTopicDrumstickMeta> {
                putAll(cachedMetaMap)
                freshlyEnriched.forEach { put(it.topicId, it) }
            }

            _rawState.update { state ->
                if (state.activeSession?.sessionId != session.sessionId || state.activeSession.isDismissed) {
                    state
                } else {
                    state.copy(
                        isLoading = false,
                        rawCandidates = candidatesList,
                        enrichedMetaById = state.enrichedMetaById + mergedMeta,
                        curatedAwardIds = curatedIds,
                        minAllowedTopicId = minAllowedTopicId,
                        unverifiedCreationTopicIds = unverifiedOriginIds,
                        pagesScanned = pagesScanned,
                        errorMessage = null,
                    )
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            _rawState.update { state ->
                if (state.activeSession?.sessionId != session.sessionId) {
                    state
                } else {
                    state.copy(
                        isLoading = false,
                        errorMessage = t.message,
                    )
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_PAGES_TO_SCAN = 6
        const val DEFAULT_MAX_DIGEST_ITEMS = 10
        const val DEFAULT_MAX_DETAIL_ENRICH_CANDIDATES = 20
        const val DEFAULT_DETAIL_CONCURRENCY = 4
        private const val DETAIL_ENRICH_TIMEOUT_MILLIS = 4_500L
        private const val DETAIL_META_TTL_MILLIS = 10L * 60_000L
        internal const val STALE_ACTIVE_SESSION_TTL_MILLIS = 3L * 3_600_000L
    }
}
