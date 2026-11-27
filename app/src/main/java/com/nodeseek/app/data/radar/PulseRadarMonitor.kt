package com.nodeseek.app.data.radar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.RadarWatermarkStore
import com.nodeseek.app.data.local.RadarWatermarks
import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.parser.FeedTopicDto
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.NodeSeekPostAccessException
import com.nodeseek.app.data.parser.ParsedFeedPageDto
import com.nodeseek.app.data.parser.PostCommentDto
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.SuppressInAppPopupsElement
import com.nodeseek.app.domain.alerts.AlertForwarder
import com.nodeseek.app.domain.alerts.ForwardableAlertEvent
import com.nodeseek.app.domain.alerts.NoOpAlertForwarder
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.radar.InboxTargetResolver
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarBackgroundPollOutcome
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarMonitorSyncSupport
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuleCanonicalizer
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.InboxRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

object RadarKeywordMatcher {
    private val EXCLUDE_SPLIT_REGEX = Regex("""[,，;；\n\r]+""")
    private const val MAX_REGEX_CACHE_SIZE = 128
    private val regexCacheLock = Any()
    private val compiledRegexCache = object : LinkedHashMap<String, Regex?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>?): Boolean =
            size > MAX_REGEX_CACHE_SIZE
    }

    private fun getOrCompileRegex(pattern: String): Regex? {
        if (RadarRuleCanonicalizer.isPotentiallyUnsafeRegex(pattern)) return null
        synchronized(regexCacheLock) {
            if (compiledRegexCache.containsKey(pattern)) {
                return compiledRegexCache[pattern]
            }
        }
        val compiled = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        synchronized(regexCacheLock) {
            compiledRegexCache[pattern] = compiled
        }
        return compiled
    }

    internal fun clearRegexCacheForTesting() {
        synchronized(regexCacheLock) {
            compiledRegexCache.clear()
        }
    }

    internal fun regexCacheSizeForTesting(): Int =
        synchronized(regexCacheLock) { compiledRegexCache.size }

    fun findMatchingRule(
        topic: FeedTopicDto,
        rules: List<KeywordWatchRule>,
    ): KeywordWatchRule? {
        val title = topic.title
        val excerpt = topic.excerpt?.trim().orEmpty()
        if (title.isBlank() && excerpt.isBlank()) return null

        val searchableText = if (excerpt.isNotEmpty()) "$title\n$excerpt" else title

        for (rule in rules) {
            if (!rule.isEnabled) continue
            if (!matchesCategory(topic, rule.categorySlug)) continue
            if (containsAnyExcludeWord(searchableText, rule.excludeWords)) continue
            if (matchesKeyword(title, rule.keyword, rule.matchMode) ||
                (excerpt.isNotEmpty() && matchesKeyword(excerpt, rule.keyword, rule.matchMode))
            ) {
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

    internal fun containsAnyExcludeWord(text: String, excludeWordsRaw: String): Boolean {
        if (excludeWordsRaw.isBlank()) return false
        val terms = excludeWordsRaw.split(EXCLUDE_SPLIT_REGEX)
        for (rawTerm in terms) {
            val term = rawTerm.trim()
            if (term.isNotEmpty() && text.contains(term, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    internal fun matchesKeyword(
        text: String,
        keyword: String,
        mode: KeywordMatchMode,
    ): Boolean {
        val cleanKeyword = keyword.trim()
        if (cleanKeyword.isEmpty() || text.isBlank()) return false
        return when (mode) {
            KeywordMatchMode.CONTAINS -> text.contains(cleanKeyword, ignoreCase = true)
            KeywordMatchMode.REGEX -> getOrCompileRegex(cleanKeyword)?.containsMatchIn(text) == true
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
    private val alertForwarder: AlertForwarder = NoOpAlertForwarder,
) : RadarMonitorController {

    private val _runtimeStatus = MutableStateFlow(RadarRuntimeStatus())
    override val runtimeStatus: StateFlow<RadarRuntimeStatus> = _runtimeStatus.asStateFlow()

    private val pendingManualTrigger = AtomicBoolean(false)
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private val lifecycleLock = Any()
    private val backoffStateLock = Any()
    private val pollMutex = Mutex()
    private var isStarted: Boolean = false
    private var monitorJob: Job? = null
    private var settingsObserverJob: Job? = null
    private var sessionObserverJob: Job? = null

    @Volatile
    private var htmlCfBackoffUntilEpochMillis: Long = 0L

    @Volatile
    private var inboxCfBackoffUntilEpochMillis: Long = 0L

    @Volatile
    private var rateLimit429UntilEpochMillis: Long = 0L

    @Volatile
    private var consecutiveHtmlCfFailures: Int = 0

    @Volatile
    private var consecutiveInboxCfFailures: Int = 0

    @Volatile
    private var lastObservedSessionUid: Long? = sessionRepository.sessionState.value?.uid

    private fun resetInboxBackoffOnUidChange(newUid: Long?) {
        synchronized(backoffStateLock) {
            if (newUid != lastObservedSessionUid) {
                lastObservedSessionUid = newUid
                inboxCfBackoffUntilEpochMillis = 0L
                consecutiveInboxCfFailures = 0
            }
        }
    }

    private fun inboxBackoffUntilMillis(): Long = synchronized(backoffStateLock) {
        maxOf(rateLimit429UntilEpochMillis, inboxCfBackoffUntilEpochMillis)
    }

    private fun htmlBackoffUntilMillis(): Long = synchronized(backoffStateLock) {
        maxOf(rateLimit429UntilEpochMillis, htmlCfBackoffUntilEpochMillis)
    }

    private fun effectivePollBackoffUntilMillis(settings: RadarPollingSettings): Long {
        val currentSession = sessionRepository.sessionState.value
        resetInboxBackoffOnUidChange(currentSession?.uid)
        val hasHtmlPhase = settings.keywordAlertsEnabled || settings.replyTrackingEnabled
        val hasSessionReadyForJsonInbox = (currentSession?.uid ?: 0L) > 0L
        val inboxPhaseBackoff = if (hasSessionReadyForJsonInbox) {
            inboxBackoffUntilMillis()
        } else {
            maxOf(inboxBackoffUntilMillis(), htmlBackoffUntilMillis())
        }
        return when {
            hasHtmlPhase && settings.inboxPushEnabled -> minOf(htmlBackoffUntilMillis(), inboxPhaseBackoff)
            hasHtmlPhase -> htmlBackoffUntilMillis()
            settings.inboxPushEnabled -> inboxPhaseBackoff
            else -> synchronized(backoffStateLock) { rateLimit429UntilEpochMillis }
        }
    }

    override fun start() {
        synchronized(lifecycleLock) {
            isStarted = true
            if (settingsObserverJob?.isActive != true) {
                val observer = scope.launch(start = CoroutineStart.LAZY) {
                    radarRepository.pollingSettingsFlow
                        .drop(1)
                        .collect {
                            wakeSignal.trySend(Unit)
                        }
                }
                settingsObserverJob = observer
                observer.start()
            }
            if (sessionObserverJob?.isActive != true) {
                val sessionObserver = scope.launch(start = CoroutineStart.LAZY) {
                    sessionRepository.sessionState
                        .drop(1)
                        .collect { session ->
                            resetInboxBackoffOnUidChange(session?.uid)
                            wakeSignal.trySend(Unit)
                        }
                }
                sessionObserverJob = sessionObserver
                sessionObserver.start()
            }
            if (_runtimeStatus.value.isRunningInForeground || pendingManualTrigger.get()) {
                launchForegroundLoopLocked()
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            isStarted = false
            pendingManualTrigger.set(false)
            settingsObserverJob?.cancel()
            settingsObserverJob = null
            sessionObserverJob?.cancel()
            sessionObserverJob = null
            monitorJob?.cancel()
            monitorJob = null
            _runtimeStatus.update { it.copy(isPollingNow = false) }
        }
    }

    override fun onForegroundChanged(inForeground: Boolean) {
        synchronized(lifecycleLock) {
            val previous = _runtimeStatus.value.isRunningInForeground
            _runtimeStatus.update { it.copy(isRunningInForeground = inForeground) }
            if (inForeground) {
                if (isStarted && monitorJob?.isActive != true) {
                    launchForegroundLoopLocked()
                } else if (previous != inForeground) {
                    wakeSignal.trySend(Unit)
                }
            } else if (previous != inForeground) {
                // Wake the foreground sleep loop so it exits promptly; all background polling is
                // owned exclusively by WorkManager (RadarWorkScheduler) honoring user interval & constraints.
                wakeSignal.trySend(Unit)
            }
        }
    }

    override fun triggerImmediateCheck() {
        synchronized(lifecycleLock) {
            pendingManualTrigger.set(true)
            if (monitorJob?.isActive != true) {
                launchForegroundLoopLocked()
            }
            wakeSignal.trySend(Unit)
        }
    }

    internal fun isForegroundLoopActive(): Boolean =
        synchronized(lifecycleLock) { monitorJob?.isActive == true }

    internal fun isBackgroundInboxLoopActive(): Boolean = false

    private fun launchForegroundLoopLocked() {
        if (monitorJob?.isActive == true) return
        // Drain any stale non-manual wakeups before entering the fresh loop.
        wakeSignal.tryReceive()
        // Publish `monitorJob` under `lifecycleLock` via `CoroutineStart.LAZY` BEFORE starting, and
        // obtain `selfJob` from `currentCoroutineContext()[Job]` instead of a captured `lateinit var`:
        // when `scope` uses an eager multi-threaded dispatcher (`Dispatchers.IO` warmed up by cold-start
        // WorkManager / SystemJobService) or an immediate/unconfined dispatcher, `scope.launch` can begin
        // executing the coroutine body before returning to assign a local `lateinit var` or `monitorJob`.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val selfJob = currentCoroutineContext()[Job]
            try {
                runForegroundPollingLoop(selfJob = selfJob)
            } finally {
                if (selfJob != null) {
                    synchronized(lifecycleLock) {
                        if (monitorJob === selfJob) {
                            monitorJob = null
                        }
                    }
                }
            }
        }
        monitorJob = job
        job.start()
    }

    private suspend fun runForegroundPollingLoop(selfJob: Job?) {
        var lastSkippedCheckAtMillis = 0L
        var hasPerformedStartupPoll = _runtimeStatus.value.lastCheckEpochMillis > 0L

        while (currentCoroutineContext().isActive) {
            if (pendingManualTrigger.getAndSet(false)) {
                val ran = runPollCycle(manualTrigger = true)
                if (!ran) lastSkippedCheckAtMillis = clockMillis()
                hasPerformedStartupPoll = true
            }

            val shouldExitToBackground = synchronized(lifecycleLock) {
                if (!_runtimeStatus.value.isRunningInForeground && !pendingManualTrigger.get()) {
                    if (selfJob != null && monitorJob === selfJob) {
                        monitorJob = null
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldExitToBackground) {
                break
            }

            if (pendingManualTrigger.get()) {
                continue
            }

            if (!hasPerformedStartupPoll) {
                withTimeoutOrNull(STARTUP_DELAY_MS) {
                    wakeSignal.receive()
                }
                if (pendingManualTrigger.getAndSet(false)) {
                    hasPerformedStartupPoll = true
                    val ran = runPollCycle(manualTrigger = true)
                    if (!ran) lastSkippedCheckAtMillis = clockMillis()
                    continue
                }
                if (!_runtimeStatus.value.isRunningInForeground) {
                    continue
                }
                hasPerformedStartupPoll = true
                val initialRan = runPollCycle(manualTrigger = false)
                if (!initialRan) {
                    lastSkippedCheckAtMillis = clockMillis()
                }
                continue
            }

            val settings = radarRepository.getPollingSettings()
            if (!_runtimeStatus.value.isRunningInForeground && !pendingManualTrigger.get()) {
                continue
            }
            if (!settings.isEnabled) {
                wakeSignal.receive()
                continue
            }

            val intervalMs = settings.foregroundIntervalMinutes.coerceIn(
                RadarPollingSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
                RadarPollingSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
            ) * 60_000L
            val now = clockMillis()
            val lastAttemptMs = maxOf(_runtimeStatus.value.lastCheckEpochMillis, lastSkippedCheckAtMillis)
            val nextScheduledMs = maxOf(
                lastAttemptMs + intervalMs,
                effectivePollBackoffUntilMillis(settings),
            )
            val waitMs = (nextScheduledMs - now).coerceAtLeast(MIN_WAIT_SLICE_MS)

            val wokeEarly = withTimeoutOrNull(waitMs) {
                wakeSignal.receive()
            }
            if (pendingManualTrigger.getAndSet(false)) {
                val ran = runPollCycle(manualTrigger = true)
                if (!ran) lastSkippedCheckAtMillis = clockMillis()
                continue
            }
            if (!_runtimeStatus.value.isRunningInForeground) {
                continue
            }

            if (wokeEarly != null) {
                val latestSettings = radarRepository.getPollingSettings()
                val latestIntervalMs = latestSettings.foregroundIntervalMinutes.coerceIn(
                    RadarPollingSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
                    RadarPollingSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
                ) * 60_000L
                val elapsed = clockMillis() - maxOf(
                    _runtimeStatus.value.lastCheckEpochMillis,
                    lastSkippedCheckAtMillis,
                )
                if (latestSettings.isEnabled &&
                    elapsed >= latestIntervalMs &&
                    clockMillis() >= effectivePollBackoffUntilMillis(latestSettings)
                ) {
                    val ran = runPollCycle(manualTrigger = false)
                    if (!ran) lastSkippedCheckAtMillis = clockMillis()
                }
            } else {
                val ran = runPollCycle(manualTrigger = false)
                if (!ran) lastSkippedCheckAtMillis = clockMillis()
            }
        }
    }

    /**
     * Executes a single end-to-end background poll pass for WorkManager (or background triggers),
     * serialized by [pollMutex] so it never races with foreground checks or emits duplicate notifications.
     * In-app popup emission is always suppressed during background passes while preserving system notifications.
     */
    override suspend fun runBackgroundPollPass(): RadarBackgroundPollOutcome {
        return pollMutex.withLock {
            resetInboxBackoffOnUidChange(sessionRepository.sessionState.value?.uid)
            val now = clockMillis()
            val settings = radarRepository.getPollingSettings()
            val hasAnyFeatureEnabled = settings.keywordAlertsEnabled ||
                settings.replyTrackingEnabled ||
                settings.inboxPushEnabled

            if (!settings.isEnabled || !hasAnyFeatureEnabled) {
                return@withLock RadarBackgroundPollOutcome.Skipped(
                    RadarBackgroundPollOutcome.Skipped.SkipReason.DISABLED,
                )
            }
            if (now < effectivePollBackoffUntilMillis(settings)) {
                return@withLock RadarBackgroundPollOutcome.Skipped(
                    RadarBackgroundPollOutcome.Skipped.SkipReason.BACKOFF_ACTIVE,
                )
            }
            if (settings.wifiOnlyInBackground && !isUnmeteredNetwork()) {
                return@withLock RadarBackgroundPollOutcome.Skipped(
                    RadarBackgroundPollOutcome.Skipped.SkipReason.UNMETERED_NETWORK_REQUIRED,
                )
            }

            val result = executePollPassLocked(
                settings = settings,
                isBackgroundPass = true,
                refreshSessionIfMissingForInbox = true,
                bypassCfBackoff = false,
            )

            val transientError = result.transientNetworkError
            if (transientError != null) {
                RadarBackgroundPollOutcome.Retry(reason = transientError)
            } else {
                RadarBackgroundPollOutcome.Completed(
                    keywordAlertsTriggered = result.keywordAlertsTriggered,
                    trackedReplyAlertsTriggered = result.trackedReplyAlertsTriggered,
                    inboxNotificationsTriggered = result.inboxNotificationsTriggered,
                    skippedInboxUnauthenticated = result.skippedInboxUnauthenticated,
                    errorSummary = result.cycleError,
                )
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
            resetInboxBackoffOnUidChange(sessionRepository.sessionState.value?.uid)
            val now = clockMillis()
            val settings = radarRepository.getPollingSettings()

            if (!manualTrigger) {
                if (!settings.isEnabled) return@withLock false
                if (now < effectivePollBackoffUntilMillis(settings)) return@withLock false
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

            val shouldRefreshMissingSession =
                manualTrigger || (sessionRepository as? SessionRepositoryImpl)?.hasStoredSessionCookie() == true
            executePollPassLocked(
                settings = settings,
                isBackgroundPass = !_runtimeStatus.value.isRunningInForeground,
                refreshSessionIfMissingForInbox = shouldRefreshMissingSession,
                bypassCfBackoff = manualTrigger,
            )
            true
        }
    }

    private data class PollPassResult(
        val keywordAlertsTriggered: Int = 0,
        val trackedReplyAlertsTriggered: Int = 0,
        val inboxNotificationsTriggered: Int = 0,
        val skippedInboxUnauthenticated: Boolean = false,
        val cycleError: String? = null,
        val transientNetworkError: String? = null,
    )

    private suspend fun executePollPassLocked(
        settings: RadarPollingSettings,
        isBackgroundPass: Boolean,
        refreshSessionIfMissingForInbox: Boolean,
        bypassCfBackoff: Boolean = false,
    ): PollPassResult {
        val pollSessionUid = sessionRepository.sessionState.value?.uid
        resetInboxBackoffOnUidChange(pollSessionUid)
        _runtimeStatus.update {
            it.copy(isPollingNow = true, lastErrorSummary = null)
        }

        val pollStartMs = clockMillis()
        val (htmlAllowed, inboxAllowed) = synchronized(backoffStateLock) {
            (bypassCfBackoff || pollStartMs >= htmlCfBackoffUntilEpochMillis) to
                (bypassCfBackoff || pollStartMs >= inboxCfBackoffUntilEpochMillis)
        }

        var cycleError: String? = null
        var transientNetworkError: String? = null
        var htmlCfBackoffNeeded = false
        var inboxCfBackoffNeeded = false
        var ranHtmlPhase = false
        var ranInboxPhase = false
        var rateLimitRetrySeconds: Long? = null
        var keywordAlertsTriggered = 0
        var trackedReplyAlertsTriggered = 0
        var inboxNotificationsTriggered = 0
        var skippedInboxUnauthenticated = false

        try {
            val appSettings = settingsRepository.settings.value
            val includeBlocked = appSettings.includeBlockedTopics
            val quietHoursActive = isWithinQuietHours(currentMinuteOfDay(), settings)

            // -----------------------------------------------------------------
            // 1. Keyword Watch Alerts (HTML newest feed with RSS fallback)
            // -----------------------------------------------------------------
            if (settings.keywordAlertsEnabled && htmlAllowed) {
                ranHtmlPhase = true
                val outcome = runKeywordScanPhase(
                    preferRss = appSettings.preferRssFallback,
                    includeBlocked = includeBlocked,
                    quietHoursActive = quietHoursActive,
                )
                keywordAlertsTriggered += outcome.triggeredCount
                outcome.errorMessage?.let { cycleError = it }
                if (outcome.hitCloudflareWithoutRss) htmlCfBackoffNeeded = true
                outcome.rateLimitSeconds?.let { rateLimitRetrySeconds = it }
                if (outcome.transientNetworkError != null && transientNetworkError == null) {
                    transientNetworkError = outcome.transientNetworkError
                }
            }

            // -----------------------------------------------------------------
            // 2. Tracked Topic Reply Diffs
            // -----------------------------------------------------------------
            if (settings.replyTrackingEnabled && htmlAllowed && rateLimitRetrySeconds == null) {
                ranHtmlPhase = true
                val outcome = runTrackedRepliesPhase(
                    includeBlocked = includeBlocked,
                    quietHoursActive = quietHoursActive,
                )
                trackedReplyAlertsTriggered += outcome.triggeredCount
                if (cycleError == null) cycleError = outcome.errorMessage
                if (outcome.hitCloudflare) htmlCfBackoffNeeded = true
                outcome.rateLimitSeconds?.let { rateLimitRetrySeconds = it }
                if (outcome.transientNetworkError != null && transientNetworkError == null) {
                    transientNetworkError = outcome.transientNetworkError
                }
            }

            // -----------------------------------------------------------------
            // 3. Signed-in Inbox Replies, @Mentions & Direct Messages
            //    (Isolated from HTML Phase 1/2 Cloudflare document challenges so
            //    `/api/notification/*` JSON endpoints still deliver notifications
            //    whenever session state/cookies are available.)
            // -----------------------------------------------------------------
            if (settings.inboxPushEnabled && inboxAllowed && rateLimitRetrySeconds == null) {
                var currentSession = sessionRepository.sessionState.value
                var sessionRefreshFailed = false
                if (currentSession == null &&
                    refreshSessionIfMissingForInbox &&
                    htmlAllowed &&
                    !htmlCfBackoffNeeded
                ) {
                    ranHtmlPhase = true
                    val refreshResult = sessionRepository.refreshSession()
                    val refreshError = refreshResult.exceptionOrNull()
                    if (refreshError != null) {
                        sessionRefreshFailed = true
                        val classified = classifyInboxFailure(refreshError)
                        if (classified != null) {
                            if (cycleError == null) cycleError = classified.errorMessage
                            if (classified.hitCloudflare) htmlCfBackoffNeeded = true
                            classified.rateLimitSeconds?.let { rateLimitRetrySeconds = it }
                            if (classified.transientNetworkError != null && transientNetworkError == null) {
                                transientNetworkError = classified.transientNetworkError
                            }
                            if (classified.skippedUnauthenticated) {
                                skippedInboxUnauthenticated = true
                            }
                        } else {
                            skippedInboxUnauthenticated = true
                        }
                    } else {
                        currentSession = refreshResult.getOrNull()
                    }
                }

                if (currentSession != null && currentSession.uid > 0L && rateLimitRetrySeconds == null) {
                    ranInboxPhase = true
                    val outcome = if (isBackgroundPass) {
                        withContext(SuppressInAppPopupsElement) {
                            runInboxPushPhase(
                                viewerUid = currentSession.uid,
                                quietHoursActive = quietHoursActive,
                                isBackgroundPass = true,
                            )
                        }
                    } else {
                        runInboxPushPhase(
                            viewerUid = currentSession.uid,
                            quietHoursActive = quietHoursActive,
                            isBackgroundPass = false,
                        )
                    }
                    inboxNotificationsTriggered += outcome.triggeredCount
                    if (outcome.skippedUnauthenticated) {
                        skippedInboxUnauthenticated = true
                    }
                    if (cycleError == null) cycleError = outcome.errorMessage
                    if (outcome.hitCloudflare) inboxCfBackoffNeeded = true
                    outcome.rateLimitSeconds?.let { rateLimitRetrySeconds = it }
                    if (outcome.transientNetworkError != null && transientNetworkError == null) {
                        transientNetworkError = outcome.transientNetworkError
                    }
                } else if (!sessionRefreshFailed && currentSession == null) {
                    skippedInboxUnauthenticated = true
                }
            }
        } finally {
            val finishedAt = clockMillis()
            synchronized(backoffStateLock) {
                rateLimitRetrySeconds?.let { retrySec ->
                    val safeSeconds = retrySec.coerceIn(15L, 600L)
                    rateLimit429UntilEpochMillis = finishedAt + safeSeconds * 1000L
                    cycleError = "请求过于频繁，将在 ${safeSeconds}s 后恢复检查"
                }
                if (htmlCfBackoffNeeded) {
                    consecutiveHtmlCfFailures = (consecutiveHtmlCfFailures + 1).coerceAtMost(4)
                    val backoffSec = (60L * (1 shl (consecutiveHtmlCfFailures - 1))).coerceAtMost(600L)
                    htmlCfBackoffUntilEpochMillis = maxOf(htmlCfBackoffUntilEpochMillis, finishedAt + backoffSec * 1000L)
                    if (cycleError == null) {
                        cycleError = "需要网页安全验证，已暂缓网页轮询"
                    }
                } else if (ranHtmlPhase && transientNetworkError == null) {
                    consecutiveHtmlCfFailures = 0
                }
                if (inboxCfBackoffNeeded) {
                    if (sessionRepository.sessionState.value?.uid == pollSessionUid) {
                        consecutiveInboxCfFailures = (consecutiveInboxCfFailures + 1).coerceAtMost(4)
                        val backoffSec = (60L * (1 shl (consecutiveInboxCfFailures - 1))).coerceAtMost(600L)
                        inboxCfBackoffUntilEpochMillis = maxOf(inboxCfBackoffUntilEpochMillis, finishedAt + backoffSec * 1000L)
                        if (cycleError == null) {
                            cycleError = "需要网页安全验证，已暂停收件箱轮询"
                        }
                    }
                } else if (ranInboxPhase && transientNetworkError == null) {
                    consecutiveInboxCfFailures = 0
                }
            }

            val finalErrorSummary = cycleError ?: transientNetworkError
            _runtimeStatus.update {
                it.copy(
                    isPollingNow = false,
                    lastCheckEpochMillis = finishedAt,
                    lastErrorSummary = finalErrorSummary,
                )
            }
        }

        return PollPassResult(
            keywordAlertsTriggered = keywordAlertsTriggered,
            trackedReplyAlertsTriggered = trackedReplyAlertsTriggered,
            inboxNotificationsTriggered = inboxNotificationsTriggered,
            skippedInboxUnauthenticated = skippedInboxUnauthenticated,
            cycleError = cycleError,
            transientNetworkError = transientNetworkError,
        )
    }

    private data class PhaseOutcome(
        val errorMessage: String? = null,
        val hitCloudflare: Boolean = false,
        val hitCloudflareWithoutRss: Boolean = false,
        val rateLimitSeconds: Long? = null,
        val transientNetworkError: String? = null,
        val triggeredCount: Int = 0,
        val skippedUnauthenticated: Boolean = false,
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
        var htmlHitCloudflare = false
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
                    htmlHitCloudflare = true
                    usedRssFallback = true
                    apiClient.fetchRssFeed()
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: NodeSeekApiException) {
            return when {
                e.statusCode == 429 -> PhaseOutcome(rateLimitSeconds = e.retryAfterSeconds ?: 60L)
                isTransientNetworkFailure(e) -> PhaseOutcome(
                    errorMessage = e.message,
                    transientNetworkError = e.message ?: "关键词新帖检查网络异常",
                )
                htmlHitCloudflare -> PhaseOutcome(
                    errorMessage = "需要网页安全验证，RSS 备用源暂不可用",
                    hitCloudflareWithoutRss = true,
                )
                else -> PhaseOutcome(errorMessage = e.message)
            }
        } catch (e: CloudflareChallengeException) {
            return PhaseOutcome(
                errorMessage = "需要网页安全验证，RSS 备用源暂不可用",
                hitCloudflareWithoutRss = true,
            )
        } catch (t: Throwable) {
            val message = t.message ?: "关键词新帖检查失败"
            return PhaseOutcome(
                errorMessage = message,
                hitCloudflareWithoutRss = htmlHitCloudflare && !isTransientNetworkFailure(t),
                transientNetworkError = if (isTransientNetworkFailure(t)) message else null,
            )
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

        var insertedCount = 0
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
            if (inserted) {
                insertedCount++
                try {
                    alertForwarder.forward(
                        ForwardableAlertEvent.RadarKeywordHit(
                            topicId = record.topicId,
                            topicTitle = record.topicTitle,
                            matchedKeyword = record.matchedKeyword,
                            authorName = record.actorName,
                            categoryName = record.categoryName,
                            summarySnippet = record.summarySnippet,
                            topicUrl = "https://www.nodeseek.com/post-${record.topicId}-1",
                            occurredAtEpochMillis = record.createdAtEpochMillis,
                        ),
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // External alert forwarding failures must not block radar monitoring.
                }
            }
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
            triggeredCount = insertedCount,
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
        var feedTransientError: String? = null
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
            if (isTransientNetworkFailure(e)) {
                feedTransientError = e.message ?: "追踪帖列表检查网络异常"
            }
            // Non-CF/non-429 error on feed page still allows direct targeted thread checks below.
        } catch (t: Throwable) {
            if (isTransientNetworkFailure(t)) {
                feedTransientError = t.message ?: "追踪帖列表检查网络异常"
            }
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

        var succeededTopicChecks = 0
        var triggeredCount = 0
        var topicTransientError: String? = null

        for (tracked in prioritized) {
            try {
                val alerted = checkSingleTrackedTopic(
                    tracked = tracked,
                    currentUid = currentUid,
                    currentUsername = currentUsername,
                    includeBlocked = includeBlocked,
                    quietHoursActive = quietHoursActive,
                )
                succeededTopicChecks++
                if (alerted) triggeredCount++
            } catch (c: CancellationException) {
                throw c
            } catch (_: CloudflareChallengeException) {
                return PhaseOutcome(
                    errorMessage = "需要网页安全验证，已暂停追踪帖检查",
                    hitCloudflare = true,
                    triggeredCount = triggeredCount,
                )
            } catch (e: NodeSeekApiException) {
                if (e.statusCode == 429) {
                    return PhaseOutcome(
                        rateLimitSeconds = e.retryAfterSeconds ?: 60L,
                        triggeredCount = triggeredCount,
                    )
                }
                if (isTransientNetworkFailure(e) && topicTransientError == null) {
                    topicTransientError = e.message ?: "追踪帖检查网络异常"
                }
            } catch (_: NodeSeekPostAccessException) {
                // Topic became private / restricted — skip without aborting other tracked topics.
                succeededTopicChecks++
            } catch (t: Throwable) {
                if (isTransientNetworkFailure(t) && topicTransientError == null) {
                    topicTransientError = t.message ?: "追踪帖检查网络异常"
                }
            }
        }

        val resolvedTransientError = topicTransientError ?: if (succeededTopicChecks == 0) {
            feedTransientError
        } else {
            null
        }
        return PhaseOutcome(
            errorMessage = resolvedTransientError,
            transientNetworkError = resolvedTransientError,
            triggeredCount = triggeredCount,
        )
    }

    private suspend fun checkSingleTrackedTopic(
        tracked: TrackedTopic,
        currentUid: Long?,
        currentUsername: String?,
        includeBlocked: Boolean,
        quietHoursActive: Boolean,
    ): Boolean {
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
            return false
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
            return false
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
            return false
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

        if (inserted) {
            try {
                alertForwarder.forward(
                    ForwardableAlertEvent.RadarTrackedReply(
                        topicId = record.topicId,
                        topicTitle = record.topicTitle,
                        targetFloor = record.targetFloor,
                        newReplyCount = peerComments.size,
                        actorName = record.actorName,
                        categoryName = record.categoryName,
                        summarySnippet = record.summarySnippet,
                        topicUrl = "https://www.nodeseek.com/post-${record.topicId}-${record.targetFloor}",
                        occurredAtEpochMillis = record.createdAtEpochMillis,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // External alert forwarding failures must not block radar monitoring.
            }
        }

        if (inserted && !quietHoursActive) {
            notificationDispatcher.postTrackedReplyNotification(
                record = record,
                newReplyCount = peerComments.size,
            )
        }
        return inserted
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
        isBackgroundPass: Boolean = true,
    ): PhaseOutcome {
        var watermarks = ensureInboxWatermarkAccount(viewerUid)

        val notifiedTopicFloorPairs = HashSet<Pair<Long, Int>>()
        val canAdvanceUnreadWatermark =
            quietHoursActive || notificationDispatcher.hasPostNotificationsPermission()
        var totalNotifiedCount = 0

        // 1. Reply notifications
        val repliesResult = inboxRepository.getNotifications(NotificationKind.REPLIES)
        repliesResult.exceptionOrNull()?.let { err ->
            classifyInboxFailure(err)?.let { return it.copy(triggeredCount = totalNotifiedCount) }
        }
        repliesResult.getOrNull()?.let { replyItems ->
            if (!isBackgroundPass) {
                watermarks = watermarkStore.getWatermarks()
            }
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
                            totalNotifiedCount++
                        }
                    }
                }
            }
        }

        // 2. @Mention notifications (deduplicated by (topicId, floorNumber) against replies)
        val mentionsResult = inboxRepository.getNotifications(NotificationKind.MENTIONS)
        mentionsResult.exceptionOrNull()?.let { err ->
            classifyInboxFailure(err)?.let { return it.copy(triggeredCount = totalNotifiedCount) }
        }
        mentionsResult.getOrNull()?.let { mentionItems ->
            if (!isBackgroundPass) {
                watermarks = watermarkStore.getWatermarks()
            }
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
                            totalNotifiedCount++
                        }
                    }
                }
            }
        }

        // 3. Direct Messages (PM threads)
        val dmOutcome = processDirectMessageNotifications(
            viewerUid = viewerUid,
            quietHoursActive = quietHoursActive,
            canAdvanceUnreadWatermark = canAdvanceUnreadWatermark,
            reloadWatermarksAfterFetch = !isBackgroundPass,
            watermarksBeforeFetch = watermarks,
        )
        if (dmOutcome.errorMessage != null ||
            dmOutcome.hitCloudflare ||
            dmOutcome.rateLimitSeconds != null ||
            dmOutcome.transientNetworkError != null ||
            dmOutcome.skippedUnauthenticated
        ) {
            return dmOutcome.copy(triggeredCount = totalNotifiedCount + dmOutcome.triggeredCount)
        }
        totalNotifiedCount += dmOutcome.triggeredCount

        return PhaseOutcome(triggeredCount = totalNotifiedCount)
    }

    private suspend fun ensureInboxWatermarkAccount(viewerUid: Long): com.nodeseek.app.data.local.RadarWatermarks {
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
        return watermarks
    }

    private suspend fun processDirectMessageNotifications(
        viewerUid: Long,
        quietHoursActive: Boolean,
        canAdvanceUnreadWatermark: Boolean,
        reloadWatermarksAfterFetch: Boolean,
        watermarksBeforeFetch: com.nodeseek.app.data.local.RadarWatermarks,
    ): PhaseOutcome {
        val dmResult = inboxRepository.getMessageThreads()
        dmResult.exceptionOrNull()?.let { err ->
            classifyInboxFailure(err)?.let { return it }
        }
        var notifiedCount = 0
        dmResult.getOrNull()?.let { threads ->
            val watermarks = if (reloadWatermarksAfterFetch) {
                watermarkStore.getWatermarks()
            } else {
                watermarksBeforeFetch
            }
            if (!watermarks.dmInitialized) {
                val initialMap = threads
                    .filter { it.peerUid > 0L }
                    .associate { thread ->
                        thread.peerUid to resolveDmWatermarkId(thread)
                    }
                    .filterValues { it != 0L }
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
                    if (thread.peerUid <= 0L) continue
                    val explicitLatestId = thread.latestMessageId ?: 0L
                    val effectiveId = resolveDmWatermarkId(thread)
                    val prevKnownId = updatedMap[thread.peerUid] ?: 0L
                    val hasNewMarker = if (explicitLatestId > 0L) {
                        effectiveId > prevKnownId
                    } else {
                        effectiveId < 0L && effectiveId != prevKnownId
                    }
                    val isNewUnreadDm = !thread.isRead &&
                        !thread.isSystem &&
                        hasNewMarker
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
                                    messageId = effectiveId,
                                    previewText = cleanPreview,
                                )
                                emitted++
                                notifiedCount++
                            }
                            updatedMap[thread.peerUid] = effectiveId
                        }
                    } else if (hasNewMarker && (effectiveId > 0L || prevKnownId <= 0L)) {
                        updatedMap[thread.peerUid] = effectiveId
                    }
                }
                if (updatedMap != watermarks.lastNotifiedDmMap) {
                    watermarkStore.updateWatermarks {
                        it.copy(lastNotifiedDmMap = updatedMap)
                    }
                }
            }
        }
        return PhaseOutcome(triggeredCount = notifiedCount)
    }

    internal fun resolveDmWatermarkId(thread: DirectMessageSummary): Long {
        val explicitId = thread.latestMessageId ?: 0L
        if (explicitId > 0L) return explicitId
        return RadarWatermarks.encodeSyntheticDmWatermark(thread.updatedAtIso, thread.previewText)
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

            TopicAccessException.SignInRequired -> PhaseOutcome(
                skippedUnauthenticated = true,
            )

            is NodeSeekApiException -> when {
                err.statusCode == 429 -> PhaseOutcome(rateLimitSeconds = err.retryAfterSeconds ?: 60L)
                err.statusCode == 401 || err.statusCode == 403 -> PhaseOutcome(skippedUnauthenticated = true)
                isTransientNetworkFailure(err) -> PhaseOutcome(
                    errorMessage = err.message,
                    transientNetworkError = err.message ?: "收件箱同步网络异常",
                )
                else -> null
            }

            else -> if (isTransientNetworkFailure(err)) {
                PhaseOutcome(
                    errorMessage = err.message,
                    transientNetworkError = err.message ?: "收件箱同步网络异常",
                )
            } else {
                null
            }
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
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        internal fun defaultCurrentMinuteOfDay(): Int {
            val now = LocalTime.now()
            return now.hour * 60 + now.minute
        }

        internal fun isTransientNetworkFailure(t: Throwable): Boolean = when (t) {
            is CancellationException -> false
            is CloudflareChallengeException -> false
            is TopicAccessException -> false
            is NodeSeekPostAccessException -> false
            is NodeSeekApiException -> t.statusCode == 408 || t.statusCode in 500..599
            is IOException -> true
            else -> false
        }
    }
}
