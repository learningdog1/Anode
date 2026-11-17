package com.nodeseek.app.data.radar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.RadarWatermarkStore
import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.parser.FeedTopicDto
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.NodeSeekPostAccessException
import com.nodeseek.app.data.parser.ParsedFeedPageDto
import com.nodeseek.app.data.parser.PostCommentDto
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.InboxTargetResolver
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarMonitorSyncSupport
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.InboxRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

object RadarKeywordMatcher {
    private val EXCLUDE_SPLIT_REGEX = Regex("""[,，;；\n\r]+""")

    fun findMatchingRule(
        topic: FeedTopicDto,
        rules: List<KeywordWatchRule>,
    ): KeywordWatchRule? {
        val title = topic.title
        if (title.isBlank()) return null

        for (rule in rules) {
            if (!rule.isEnabled) continue
            if (!matchesCategory(topic, rule.categorySlug)) continue
            if (containsAnyExcludeWord(title, rule.excludeWords)) continue
            if (matchesKeyword(title, rule.keyword, rule.matchMode)) {
                return rule
            }
        }
        return null
    }

    internal fun matchesCategory(topic: FeedTopicDto, requiredSlug: String): Boolean {
        val cleanSlug = requiredSlug.trim()
        if (cleanSlug.isEmpty()) return true
        return topic.categoryKey.equals(cleanSlug, ignoreCase = true) ||
            topic.categoryName.equals(cleanSlug, ignoreCase = true)
    }

    internal fun containsAnyExcludeWord(title: String, excludeWordsRaw: String): Boolean {
        if (excludeWordsRaw.isBlank()) return false
        val terms = excludeWordsRaw.split(EXCLUDE_SPLIT_REGEX)
        for (rawTerm in terms) {
            val term = rawTerm.trim()
            if (term.isNotEmpty() && title.contains(term, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    internal fun matchesKeyword(
        title: String,
        keyword: String,
        mode: KeywordMatchMode,
    ): Boolean {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isEmpty()) return false
        return when (mode) {
            KeywordMatchMode.CONTAINS -> title.contains(cleanKeyword, ignoreCase = true)
            KeywordMatchMode.REGEX -> runCatching {
                Regex(cleanKeyword, RegexOption.IGNORE_CASE).containsMatchIn(title)
            }.getOrDefault(false)
        }
    }
}

internal fun isWithinQuietHours(
    minuteOfDay: Int,
    settings: RadarPollingSettings,
): Boolean {
    if (!settings.quietHoursEnabled) return false
    val current = minuteOfDay.coerceIn(0, 1439)
    val start = settings.quietStartMinuteOfDay.coerceIn(0, 1439)
    val end = settings.quietEndMinuteOfDay.coerceIn(0, 1439)
    if (start == end) return false
    return if (start < end) {
        current in start until end
    } else {
        current >= start || current < end
    }
}

class PulseRadarMonitor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val apiClient: NodeSeekApiClient,
    private val inboxRepository: InboxRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val radarRepository: RadarRepository,
    private val notificationDispatcher: PulseNotificationDispatcher,
    private val watermarkStore: RadarWatermarkStore = RadarPreferencesStore(appContext),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val isUnmeteredNetwork: () -> Boolean = { defaultIsUnmeteredNetwork(appContext) },
    private val currentMinuteOfDay: () -> Int = { defaultCurrentMinuteOfDay() },
) : RadarMonitorController {

    private val _runtimeStatus = MutableStateFlow(RadarRuntimeStatus())
    override val runtimeStatus: StateFlow<RadarRuntimeStatus> = _runtimeStatus.asStateFlow()

    private enum class WakeKind {
        MANUAL,
        RECOMPUTE,
    }

    private val wakeFlow = MutableSharedFlow<WakeKind>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val lifecycleLock = Any()
    private val pollMutex = Mutex()
    private var monitorJob: Job? = null
    private var settingsObserverJob: Job? = null

    @Volatile
    private var backoffUntilEpochMillis: Long = 0L

    @Volatile
    private var rateLimit429UntilEpochMillis: Long = 0L

    @Volatile
    private var consecutiveCfFailures: Int = 0

    override fun start() {
        synchronized(lifecycleLock) {
            if (monitorJob?.isActive == true) return
            settingsObserverJob = scope.launch {
                radarRepository.pollingSettingsFlow
                    .drop(1)
                    .collect {
                        wakeFlow.tryEmit(WakeKind.RECOMPUTE)
                    }
            }
            monitorJob = scope.launch {
                runPollingLoop()
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            settingsObserverJob?.cancel()
            settingsObserverJob = null
            monitorJob?.cancel()
            monitorJob = null
            _runtimeStatus.update { it.copy(isPollingNow = false) }
        }
    }

    override fun onForegroundChanged(inForeground: Boolean) {
        val previous = _runtimeStatus.value.isRunningInForeground
        _runtimeStatus.update { it.copy(isRunningInForeground = inForeground) }
        if (previous != inForeground) {
            wakeFlow.tryEmit(WakeKind.RECOMPUTE)
        }
    }

    override fun triggerImmediateCheck() {
        if (monitorJob?.isActive != true) {
            start()
        }
        wakeFlow.tryEmit(WakeKind.MANUAL)
    }

    private suspend fun runPollingLoop() {
        var lastSkippedCheckAtMillis = 0L
        val earlyWake = withTimeoutOrNull(STARTUP_DELAY_MS) {
            wakeFlow.first { it == WakeKind.MANUAL }
        }
        val initialRan = runPollCycle(manualTrigger = earlyWake == WakeKind.MANUAL)
        if (!initialRan) {
            lastSkippedCheckAtMillis = clockMillis()
        }

        while (currentCoroutineContext().isActive) {
            val settings = radarRepository.getPollingSettings()
            if (!settings.isEnabled) {
                val signal = wakeFlow.first()
                if (signal == WakeKind.MANUAL) {
                    val ran = runPollCycle(manualTrigger = true)
                    if (!ran) lastSkippedCheckAtMillis = clockMillis()
                }
                continue
            }

            val inForeground = _runtimeStatus.value.isRunningInForeground
            val intervalMs = if (inForeground) {
                settings.foregroundIntervalMinutes.coerceIn(1, 60) * 60_000L
            } else {
                settings.backgroundIntervalMinutes.coerceIn(1, 180) * 60_000L
            }
            val now = clockMillis()
            val lastAttemptMs = maxOf(_runtimeStatus.value.lastCheckEpochMillis, lastSkippedCheckAtMillis)
            val nextScheduledMs = maxOf(
                lastAttemptMs + intervalMs,
                backoffUntilEpochMillis,
            )
            val waitMs = (nextScheduledMs - now).coerceAtLeast(MIN_WAIT_SLICE_MS)

            val signal = withTimeoutOrNull(waitMs) {
                wakeFlow.first()
            }
            when (signal) {
                WakeKind.MANUAL -> {
                    val ran = runPollCycle(manualTrigger = true)
                    if (!ran) lastSkippedCheckAtMillis = clockMillis()
                }

                WakeKind.RECOMPUTE -> {
                    val latestSettings = radarRepository.getPollingSettings()
                    val latestInForeground = _runtimeStatus.value.isRunningInForeground
                    val latestIntervalMs = if (latestInForeground) {
                        latestSettings.foregroundIntervalMinutes.coerceIn(1, 60) * 60_000L
                    } else {
                        latestSettings.backgroundIntervalMinutes.coerceIn(1, 180) * 60_000L
                    }
                    val elapsed = clockMillis() - maxOf(
                        _runtimeStatus.value.lastCheckEpochMillis,
                        lastSkippedCheckAtMillis,
                    )
                    if (latestSettings.isEnabled &&
                        elapsed >= latestIntervalMs &&
                        clockMillis() >= backoffUntilEpochMillis
                    ) {
                        val ran = runPollCycle(manualTrigger = false)
                        if (!ran) lastSkippedCheckAtMillis = clockMillis()
                    }
                }

                null -> {
                    val ran = runPollCycle(manualTrigger = false)
                    if (!ran) lastSkippedCheckAtMillis = clockMillis()
                }
            }
        }
    }

    /**
     * Executes a single end-to-end poll cycle (keyword alerts, tracked-topic reply diffs,
     * and signed-in inbox replies/mentions/DMs). Also exposed internally for deterministic tests.
     *
     * @return `true` if a poll cycle actually executed, `false` if skipped due to disabled state,
     *   active backoff, or background unmetered-network restriction.
     */
    internal suspend fun runPollCycle(manualTrigger: Boolean = false): Boolean {
        return pollMutex.withLock {
            val now = clockMillis()
            val settings = radarRepository.getPollingSettings()

            if (!manualTrigger) {
                if (!settings.isEnabled) return@withLock false
                if (now < backoffUntilEpochMillis) return@withLock false
                if (!_runtimeStatus.value.isRunningInForeground &&
                    settings.wifiOnlyInBackground &&
                    !isUnmeteredNetwork()
                ) {
                    return@withLock false
                }
            } else if (now < rateLimit429UntilEpochMillis) {
                val remainSec = ((rateLimit429UntilEpochMillis - now) / 1000L).coerceAtLeast(1L)
                _runtimeStatus.update {
                    it.copy(lastErrorSummary = "请求过于频繁，请等待 ${remainSec}s 后重试")
                }
                return@withLock false
            }

            _runtimeStatus.update {
                it.copy(isPollingNow = true, lastErrorSummary = null)
            }

            var cycleError: String? = null
            var cfBackoffNeeded = false
            var rateLimitRetrySeconds: Long? = null

            try {
                val appSettings = settingsRepository.settings.value
                val includeBlocked = appSettings.includeBlockedTopics
                val quietHoursActive = isWithinQuietHours(currentMinuteOfDay(), settings)

                // -----------------------------------------------------------------
                // 1. Keyword Watch Alerts (HTML newest feed with RSS fallback)
                // -----------------------------------------------------------------
                if (settings.keywordAlertsEnabled) {
                    val outcome = runKeywordScanPhase(
                        preferRss = appSettings.preferRssFallback,
                        includeBlocked = includeBlocked,
                        quietHoursActive = quietHoursActive,
                    )
                    outcome.errorMessage?.let { cycleError = it }
                    if (outcome.hitCloudflareWithoutRss) cfBackoffNeeded = true
                    outcome.rateLimitSeconds?.let { rateLimitRetrySeconds = it }
                }

                // -----------------------------------------------------------------
                // 2. Tracked Topic Reply Diffs
                // -----------------------------------------------------------------
                if (settings.replyTrackingEnabled && rateLimitRetrySeconds == null) {
                    val outcome = runTrackedRepliesPhase(
                        includeBlocked = includeBlocked,
                        quietHoursActive = quietHoursActive,
                    )
                    if (cycleError == null) cycleError = outcome.errorMessage
                    if (outcome.hitCloudflare) cfBackoffNeeded = true
                    outcome.rateLimitSeconds?.let { rateLimitRetrySeconds = it }
                }

                // -----------------------------------------------------------------
                // 3. Signed-in Inbox Replies, @Mentions & Direct Messages
                // -----------------------------------------------------------------
                val currentSession = sessionRepository.sessionState.value
                if (settings.inboxPushEnabled &&
                    currentSession != null &&
                    currentSession.uid > 0L &&
                    rateLimitRetrySeconds == null &&
                    !cfBackoffNeeded
                ) {
                    val outcome = runInboxPushPhase(
                        viewerUid = currentSession.uid,
                        quietHoursActive = quietHoursActive,
                    )
                    if (cycleError == null) cycleError = outcome.errorMessage
                    if (outcome.hitCloudflare) cfBackoffNeeded = true
                    outcome.rateLimitSeconds?.let { rateLimitRetrySeconds = it }
                }
            } finally {
                val finishedAt = clockMillis()
                if (rateLimitRetrySeconds != null) {
                    val safeSeconds = rateLimitRetrySeconds!!.coerceIn(15L, 600L)
                    rateLimit429UntilEpochMillis = finishedAt + safeSeconds * 1000L
                    backoffUntilEpochMillis = maxOf(backoffUntilEpochMillis, rateLimit429UntilEpochMillis)
                    cycleError = "请求过于频繁，将在 ${safeSeconds}s 后恢复检查"
                } else if (cfBackoffNeeded) {
                    consecutiveCfFailures = (consecutiveCfFailures + 1).coerceAtMost(4)
                    val backoffSec = (60L * (1 shl (consecutiveCfFailures - 1))).coerceAtMost(600L)
                    backoffUntilEpochMillis = maxOf(backoffUntilEpochMillis, finishedAt + backoffSec * 1000L)
                    if (cycleError == null) {
                        cycleError = "需要网页安全验证，已暂缓网页轮询"
                    }
                } else {
                    consecutiveCfFailures = 0
                }

                _runtimeStatus.update {
                    it.copy(
                        isPollingNow = false,
                        lastCheckEpochMillis = finishedAt,
                        lastErrorSummary = cycleError,
                    )
                }
            }
            true
        }
    }

    private data class PhaseOutcome(
        val errorMessage: String? = null,
        val hitCloudflare: Boolean = false,
        val hitCloudflareWithoutRss: Boolean = false,
        val rateLimitSeconds: Long? = null,
    )

    private suspend fun runKeywordScanPhase(
        preferRss: Boolean,
        includeBlocked: Boolean,
        quietHoursActive: Boolean,
    ): PhaseOutcome {
        val activeRules = radarRepository.keywordRulesFlow.first().filter { it.isEnabled }
        if (activeRules.isEmpty()) {
            return PhaseOutcome()
        }

        var usedRssFallback = false
        val parsedFeed: ParsedFeedPageDto = try {
            if (preferRss) {
                usedRssFallback = true
                apiClient.fetchRssFeed()
            } else {
                try {
                    apiClient.fetchFeedPage(
                        categorySlug = "",
                        sortBy = FeedSort.NEWEST_TOPIC.queryValue,
                        page = 1,
                        includeBlocked = includeBlocked,
                    )
                } catch (_: CloudflareChallengeException) {
                    usedRssFallback = true
                    apiClient.fetchRssFeed()
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: NodeSeekApiException) {
            return if (e.statusCode == 429) {
                PhaseOutcome(rateLimitSeconds = e.retryAfterSeconds ?: 60L)
            } else {
                PhaseOutcome(errorMessage = e.message)
            }
        } catch (e: CloudflareChallengeException) {
            return PhaseOutcome(
                errorMessage = "需要网页安全验证，RSS 备用源暂不可用",
                hitCloudflareWithoutRss = true,
            )
        } catch (t: Throwable) {
            return PhaseOutcome(errorMessage = t.message ?: "关键词新帖检查失败")
        }

        val candidates = (parsedFeed.topics + parsedFeed.pinnedTopics).distinctBy { it.id }
        if (candidates.isEmpty()) {
            return PhaseOutcome()
        }

        val feedMaxId = candidates.maxOf { it.id }
        val watermarks = watermarkStore.getWatermarks()
        if (watermarks.lastSeenMaxTopicId <= 0L) {
            watermarkStore.updateWatermarks { it.copy(lastSeenMaxTopicId = feedMaxId) }
            return PhaseOutcome()
        }

        val newTopics = candidates
            .filter { it.id > watermarks.lastSeenMaxTopicId }
            .sortedBy { it.id }

        if (feedMaxId > watermarks.lastSeenMaxTopicId) {
            watermarkStore.updateWatermarks { current ->
                if (feedMaxId > current.lastSeenMaxTopicId) {
                    current.copy(lastSeenMaxTopicId = feedMaxId)
                } else {
                    current
                }
            }
        }

        var emittedCount = 0
        for (topic in newTopics) {
            val matchedRule = RadarKeywordMatcher.findMatchingRule(topic, activeRules) ?: continue
            val record = LocalAlertRecord(
                dedupKey = "kw:${topic.id}",
                kind = LocalAlertKind.KEYWORD_HIT,
                topicId = topic.id,
                topicTitle = topic.title,
                targetFloor = 0,
                matchedKeyword = matchedRule.keyword,
                actorName = topic.author,
                summarySnippet = topic.excerpt.orEmpty().take(140),
                categoryName = topic.categoryName,
                isRead = false,
                createdAtEpochMillis = clockMillis(),
            )
            val inserted = radarRepository.recordAlertIfAbsent(record)
            if (inserted && !quietHoursActive && emittedCount < MAX_NOTIFICATIONS_PER_CYCLE) {
                notificationDispatcher.postKeywordHitNotification(record)
                emittedCount++
            }
        }

        return PhaseOutcome(
            errorMessage = if (usedRssFallback && !preferRss) {
                "网页触发安全验证，已自动切换 RSS 监测关键词"
            } else {
                null
            },
        )
    }

    private suspend fun runTrackedRepliesPhase(
        includeBlocked: Boolean,
        quietHoursActive: Boolean,
    ): PhaseOutcome {
        val trackedTopics = radarRepository.trackedTopicsFlow.first().filter { it.isActive }
        if (trackedTopics.isEmpty()) {
            return PhaseOutcome()
        }

        val recentlyBumpedIds = HashSet<Long>()
        try {
            val latestReplyFeed = apiClient.fetchFeedPage(
                categorySlug = "",
                sortBy = FeedSort.LATEST_REPLY.queryValue,
                page = 1,
                includeBlocked = includeBlocked,
            )
            for (topic in latestReplyFeed.topics) {
                recentlyBumpedIds.add(topic.id)
            }
            for (topic in latestReplyFeed.pinnedTopics) {
                recentlyBumpedIds.add(topic.id)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: CloudflareChallengeException) {
            return PhaseOutcome(
                errorMessage = "需要网页安全验证，已暂停追踪帖检查",
                hitCloudflare = true,
            )
        } catch (e: NodeSeekApiException) {
            if (e.statusCode == 429) {
                return PhaseOutcome(rateLimitSeconds = e.retryAfterSeconds ?: 60L)
            }
            // Non-CF/non-429 error on feed page still allows direct targeted thread checks below.
        } catch (_: Throwable) {
            // Proceed to targeted thread checks directly.
        }

        val prioritized = trackedTopics
            .sortedWith(
                compareBy<TrackedTopic>(
                    { if (it.lastKnownFloor < 0) 0 else 1 },
                    { if (it.topicId in recentlyBumpedIds) 0 else 1 },
                    { it.lastCheckedEpochMillis },
                ),
            )
            .take(MAX_TRACKED_CHECKS_PER_CYCLE)

        val session = sessionRepository.sessionState.value
        val currentUid = session?.uid?.takeIf { it > 0L }
        val currentUsername = session?.username?.takeIf { it.isNotBlank() }

        for (tracked in prioritized) {
            try {
                checkSingleTrackedTopic(
                    tracked = tracked,
                    currentUid = currentUid,
                    currentUsername = currentUsername,
                    includeBlocked = includeBlocked,
                    quietHoursActive = quietHoursActive,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (_: CloudflareChallengeException) {
                return PhaseOutcome(
                    errorMessage = "需要网页安全验证，已暂停追踪帖检查",
                    hitCloudflare = true,
                )
            } catch (e: NodeSeekApiException) {
                if (e.statusCode == 429) {
                    return PhaseOutcome(rateLimitSeconds = e.retryAfterSeconds ?: 60L)
                }
            } catch (_: NodeSeekPostAccessException) {
                // Topic became private / restricted — skip without aborting other tracked topics.
            } catch (_: Throwable) {
                // Transient per-topic failure — continue remaining topics.
            }
        }

        return PhaseOutcome()
    }

    private suspend fun checkSingleTrackedTopic(
        tracked: TrackedTopic,
        currentUid: Long?,
        currentUsername: String?,
        includeBlocked: Boolean,
        quietHoursActive: Boolean,
    ) {
        val now = clockMillis()
        val startPage = tracked.lastKnownTotalPages.coerceAtLeast(1)
        val firstPageDto = apiClient.fetchThreadPage(
            postId = tracked.topicId,
            page = startPage,
            includeBlocked = includeBlocked,
        )
        val actualTotalPages = firstPageDto.postData.postPageCount.coerceAtLeast(1)

        val collectedComments = ArrayList<PostCommentDto>(firstPageDto.replies)
        var latestPageDto = firstPageDto
        if (actualTotalPages > startPage) {
            if (tracked.lastKnownFloor >= 0 && actualTotalPages > startPage + 1) {
                val nextPageDto = apiClient.fetchThreadPage(
                    postId = tracked.topicId,
                    page = startPage + 1,
                    includeBlocked = includeBlocked,
                )
                collectedComments.addAll(nextPageDto.replies)
            }
            val tailDto = apiClient.fetchThreadPage(
                postId = tracked.topicId,
                page = actualTotalPages,
                includeBlocked = includeBlocked,
            )
            collectedComments.addAll(tailDto.replies)
            latestPageDto = tailDto
        }

        val resolvedTitle = latestPageDto.postData.title.ifBlank { tracked.title }
        val resolvedCategorySlug = latestPageDto.postData.category.ifBlank { tracked.categorySlug }
        val resolvedCategoryName = latestPageDto.postData.categoryWord.ifBlank { tracked.categoryName }
        val resolvedAuthorName = latestPageDto.postData.op?.name?.takeIf { it.isNotBlank() }
            ?: tracked.authorName

        if (tracked.lastKnownFloor < 0) {
            val baselineMaxFloor = latestPageDto.replies.maxOfOrNull { it.floorIndex } ?: 0
            updateTrackedTopicProgress(
                topicId = tracked.topicId,
                title = resolvedTitle,
                categorySlug = resolvedCategorySlug,
                categoryName = resolvedCategoryName,
                authorName = resolvedAuthorName,
                newMaxFloor = baselineMaxFloor,
                newTotalPages = actualTotalPages,
                unreadDelta = 0,
                checkedAtEpochMillis = now,
            )
            return
        }

        val newComments = collectedComments
            .asSequence()
            .filter { it.floorIndex > tracked.lastKnownFloor }
            .distinctBy { it.floorIndex }
            .sortedBy { it.floorIndex }
            .toList()

        if (newComments.isEmpty()) {
            markTopicCheckedWithoutChange(
                topicId = tracked.topicId,
                totalPages = actualTotalPages,
                checkedAtEpochMillis = now,
            )
            return
        }

        val newMaxFloor = newComments.maxOf { it.floorIndex }
        val peerComments = newComments.filterNot { comment ->
            isSelfComment(comment, currentUid = currentUid, currentUsername = currentUsername)
        }

        if (peerComments.isEmpty()) {
            updateTrackedTopicProgress(
                topicId = tracked.topicId,
                title = resolvedTitle,
                categorySlug = resolvedCategorySlug,
                categoryName = resolvedCategoryName,
                authorName = resolvedAuthorName,
                newMaxFloor = newMaxFloor,
                newTotalPages = actualTotalPages,
                unreadDelta = 0,
                checkedAtEpochMillis = now,
            )
            return
        }

        val firstPeer = peerComments.first()
        val firstNewFloor = firstPeer.floorIndex
        val snippet = NodeSeekHtmlParser.stripMarkdownToPlainPreview(firstPeer.markdown)
            .take(140)
            .ifBlank { "查看 #${firstNewFloor} 楼回复详情" }

        val record = LocalAlertRecord(
            dedupKey = "reply:${tracked.topicId}:$firstNewFloor",
            kind = LocalAlertKind.TRACKED_REPLY,
            topicId = tracked.topicId,
            topicTitle = resolvedTitle,
            targetFloor = firstNewFloor,
            matchedKeyword = "",
            actorName = firstPeer.poster.name,
            summarySnippet = snippet,
            categoryName = resolvedCategoryName,
            isRead = false,
            createdAtEpochMillis = now,
        )

        val inserted = radarRepository.recordAlertIfAbsent(record)
        updateTrackedTopicProgress(
            topicId = tracked.topicId,
            title = resolvedTitle,
            categorySlug = resolvedCategorySlug,
            categoryName = resolvedCategoryName,
            authorName = resolvedAuthorName,
            newMaxFloor = newMaxFloor,
            newTotalPages = actualTotalPages,
            unreadDelta = peerComments.size,
            checkedAtEpochMillis = now,
        )

        if (inserted && !quietHoursActive) {
            notificationDispatcher.postTrackedReplyNotification(
                record = record,
                newReplyCount = peerComments.size,
            )
        }
    }

    private suspend fun updateTrackedTopicProgress(
        topicId: Long,
        title: String,
        categorySlug: String,
        categoryName: String,
        authorName: String,
        newMaxFloor: Int,
        newTotalPages: Int,
        unreadDelta: Int,
        checkedAtEpochMillis: Long,
    ) {
        val syncSupport = radarRepository as? RadarMonitorSyncSupport
        if (syncSupport != null) {
            syncSupport.recordMonitorTopicProgress(
                topicId = topicId,
                title = title,
                categorySlug = categorySlug,
                categoryName = categoryName,
                authorName = authorName,
                newMaxFloor = newMaxFloor,
                newTotalPages = newTotalPages,
                unreadDelta = unreadDelta,
                checkedAtEpochMillis = checkedAtEpochMillis,
            )
        } else {
            radarRepository.trackTopic(
                topicId = topicId,
                title = title,
                categorySlug = categorySlug,
                categoryName = categoryName,
                authorName = authorName,
                currentMaxFloor = newMaxFloor,
                totalPages = newTotalPages,
            )
        }
    }

    private suspend fun markTopicCheckedWithoutChange(
        topicId: Long,
        totalPages: Int,
        checkedAtEpochMillis: Long,
    ) {
        val syncSupport = radarRepository as? RadarMonitorSyncSupport
        syncSupport?.markTopicCheckedWithoutNewReplies(
            topicId = topicId,
            totalPages = totalPages,
            checkedAtEpochMillis = checkedAtEpochMillis,
        )
    }

    private suspend fun runInboxPushPhase(
        viewerUid: Long,
        quietHoursActive: Boolean,
    ): PhaseOutcome {
        var watermarks = watermarkStore.getWatermarks()
        if (watermarks.inboxAccountUid != 0L && watermarks.inboxAccountUid != viewerUid) {
            watermarks = watermarkStore.updateWatermarks {
                it.copy(
                    inboxAccountUid = viewerUid,
                    lastNotifiedReplyId = -1L,
                    lastNotifiedMentionId = -1L,
                    dmInitialized = false,
                    lastNotifiedDmMap = emptyMap(),
                )
            }
        } else if (watermarks.inboxAccountUid == 0L) {
            watermarks = watermarkStore.updateWatermarks {
                it.copy(inboxAccountUid = viewerUid)
            }
        }

        val notifiedTopicFloorPairs = HashSet<Pair<Long, Int>>()
        val canAdvanceUnreadWatermark =
            quietHoursActive || notificationDispatcher.hasPostNotificationsPermission()

        // 1. Reply notifications
        val repliesResult = inboxRepository.getNotifications(NotificationKind.REPLIES)
        repliesResult.exceptionOrNull()?.let { err ->
            classifyInboxFailure(err)?.let { return it }
        }
        repliesResult.getOrNull()?.let { replyItems ->
            val maxReplyId = replyItems.maxOfOrNull { it.id } ?: 0L
            if (watermarks.lastNotifiedReplyId < 0L) {
                watermarks = watermarkStore.updateWatermarks {
                    it.copy(inboxAccountUid = viewerUid, lastNotifiedReplyId = maxReplyId)
                }
            } else {
                val freshReplies = replyItems
                    .filter { !it.isRead && it.id > watermarks.lastNotifiedReplyId && it.senderUid != viewerUid }
                    .sortedBy { it.id }
                val nextReplyWatermark = if (canAdvanceUnreadWatermark) {
                    maxOf(watermarks.lastNotifiedReplyId, maxReplyId)
                } else {
                    val minUnreadId = freshReplies.minOfOrNull { it.id }
                    val safeMaxReadId = replyItems
                        .asSequence()
                        .filter { it.isRead || it.senderUid == viewerUid }
                        .map { it.id }
                        .filter { id -> minUnreadId == null || id < minUnreadId }
                        .maxOrNull() ?: 0L
                    maxOf(watermarks.lastNotifiedReplyId, safeMaxReadId)
                }
                if (nextReplyWatermark > watermarks.lastNotifiedReplyId) {
                    watermarks = watermarkStore.updateWatermarks {
                        it.copy(lastNotifiedReplyId = nextReplyWatermark)
                    }
                }
                if (canAdvanceUnreadWatermark) {
                    for (item in freshReplies.take(MAX_NOTIFICATIONS_PER_CYCLE)) {
                        val resolved = InboxTargetResolver.resolveForNotificationItem(item)
                        val targetTopicId = resolved?.topicId ?: item.topicId
                        val targetFloor = resolved?.floor ?: item.floorNumber
                        if (targetTopicId > 0L && targetFloor > 0) {
                            notifiedTopicFloorPairs.add(targetTopicId to targetFloor)
                        }
                        if (!quietHoursActive) {
                            notificationDispatcher.postForumInteractionNotification(
                                itemId = item.id,
                                isMention = false,
                                senderName = item.senderName,
                                topicId = targetTopicId,
                                topicTitle = item.topicTitle,
                                floorNumber = targetFloor,
                                excerpt = item.excerpt.orEmpty(),
                            )
                        }
                    }
                }
            }
        }

        // 2. @Mention notifications (deduplicated by (topicId, floorNumber) against replies)
        val mentionsResult = inboxRepository.getNotifications(NotificationKind.MENTIONS)
        mentionsResult.exceptionOrNull()?.let { err ->
            classifyInboxFailure(err)?.let { return it }
        }
        mentionsResult.getOrNull()?.let { mentionItems ->
            val maxMentionId = mentionItems.maxOfOrNull { it.id } ?: 0L
            if (watermarks.lastNotifiedMentionId < 0L) {
                watermarks = watermarkStore.updateWatermarks {
                    it.copy(inboxAccountUid = viewerUid, lastNotifiedMentionId = maxMentionId)
                }
            } else {
                val freshMentions = mentionItems
                    .filter { !it.isRead && it.id > watermarks.lastNotifiedMentionId && it.senderUid != viewerUid }
                    .sortedBy { it.id }
                val nextMentionWatermark = if (canAdvanceUnreadWatermark) {
                    maxOf(watermarks.lastNotifiedMentionId, maxMentionId)
                } else {
                    val minUnreadId = freshMentions.minOfOrNull { it.id }
                    val safeMaxReadId = mentionItems
                        .asSequence()
                        .filter { it.isRead || it.senderUid == viewerUid }
                        .map { it.id }
                        .filter { id -> minUnreadId == null || id < minUnreadId }
                        .maxOrNull() ?: 0L
                    maxOf(watermarks.lastNotifiedMentionId, safeMaxReadId)
                }
                if (nextMentionWatermark > watermarks.lastNotifiedMentionId) {
                    watermarks = watermarkStore.updateWatermarks {
                        it.copy(lastNotifiedMentionId = nextMentionWatermark)
                    }
                }
                if (canAdvanceUnreadWatermark) {
                    var emitted = 0
                    for (item in freshMentions) {
                        val resolved = InboxTargetResolver.resolveForNotificationItem(item)
                        val targetTopicId = resolved?.topicId ?: item.topicId
                        val targetFloor = resolved?.floor ?: item.floorNumber
                        val key = targetTopicId to targetFloor
                        if (targetTopicId > 0L && targetFloor > 0 && !notifiedTopicFloorPairs.add(key)) {
                            continue
                        }
                        if (!quietHoursActive && emitted < MAX_NOTIFICATIONS_PER_CYCLE) {
                            notificationDispatcher.postForumInteractionNotification(
                                itemId = item.id,
                                isMention = true,
                                senderName = item.senderName,
                                topicId = targetTopicId,
                                topicTitle = item.topicTitle,
                                floorNumber = targetFloor,
                                excerpt = item.excerpt.orEmpty(),
                            )
                            emitted++
                        }
                    }
                }
            }
        }

        // 3. Direct Messages (PM threads)
        val dmResult = inboxRepository.getMessageThreads()
        dmResult.exceptionOrNull()?.let { err ->
            classifyInboxFailure(err)?.let { return it }
        }
        dmResult.getOrNull()?.let { threads ->
            if (!watermarks.dmInitialized) {
                val initialMap = threads.associate { it.peerUid to (it.latestMessageId ?: 0L) }
                watermarkStore.updateWatermarks {
                    it.copy(
                        inboxAccountUid = viewerUid,
                        dmInitialized = true,
                        lastNotifiedDmMap = initialMap,
                    )
                }
            } else {
                val updatedMap = watermarks.lastNotifiedDmMap.toMutableMap()
                var emitted = 0
                for (thread in threads) {
                    val latestId = thread.latestMessageId ?: 0L
                    val prevKnownId = updatedMap[thread.peerUid] ?: 0L
                    val isNewUnreadDm = !thread.isRead &&
                        !thread.isSystem &&
                        thread.peerUid > 0L &&
                        latestId > prevKnownId
                    if (isNewUnreadDm) {
                        if (canAdvanceUnreadWatermark) {
                            if (!quietHoursActive && emitted < MAX_NOTIFICATIONS_PER_CYCLE) {
                                val cleanPreview = if (thread.isMarkdown) {
                                    NodeSeekHtmlParser.stripMarkdownToPlainPreview(thread.previewText)
                                } else {
                                    thread.previewText.trim()
                                }
                                notificationDispatcher.postDirectMessageNotification(
                                    peerUid = thread.peerUid,
                                    peerName = thread.peerName,
                                    messageId = latestId,
                                    previewText = cleanPreview,
                                )
                                emitted++
                            }
                            updatedMap[thread.peerUid] = latestId
                        }
                    } else if (latestId > prevKnownId) {
                        updatedMap[thread.peerUid] = latestId
                    }
                }
                if (updatedMap != watermarks.lastNotifiedDmMap) {
                    watermarkStore.updateWatermarks {
                        it.copy(lastNotifiedDmMap = updatedMap)
                    }
                }
            }
        }

        return PhaseOutcome()
    }

    private fun classifyInboxFailure(err: Throwable): PhaseOutcome? {
        return when (err) {
            is CancellationException -> throw err
            is CloudflareChallengeException,
            TopicAccessException.CloudflareChallengeRequired,
            -> PhaseOutcome(
                errorMessage = "需要网页安全验证，已暂停收件箱轮询",
                hitCloudflare = true,
            )

            is NodeSeekApiException -> if (err.statusCode == 429) {
                PhaseOutcome(rateLimitSeconds = err.retryAfterSeconds ?: 60L)
            } else {
                null
            }

            else -> null
        }
    }

    internal fun isSelfComment(
        comment: PostCommentDto,
        currentUid: Long?,
        currentUsername: String?,
    ): Boolean {
        val posterUid = comment.poster.resolvedUid
        if (currentUid != null && currentUid > 0L && posterUid > 0L) {
            return posterUid == currentUid
        }
        if (currentUsername != null && posterUid <= 0L && comment.poster.name.isNotBlank()) {
            return comment.poster.name.equals(currentUsername, ignoreCase = true)
        }
        return false
    }

    companion object {
        private const val STARTUP_DELAY_MS = 1_500L
        private const val MIN_WAIT_SLICE_MS = 1_000L
        private const val MAX_NOTIFICATIONS_PER_CYCLE = 5
        private const val MAX_TRACKED_CHECKS_PER_CYCLE = 10

        internal fun defaultIsUnmeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        internal fun defaultCurrentMinuteOfDay(): Int {
            val now = LocalTime.now()
            return now.hour * 60 + now.minute
        }
    }
}
