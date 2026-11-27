package com.nodeseek.app.domain.mail

import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

sealed interface OtpWatchState {
    data object Idle : OtpWatchState

    data class Watching(
        val mailboxId: Long?,
        val mailboxAddress: String?,
        val startedAtMillis: Long,
        val expiresAtMillis: Long,
        val remainingSeconds: Int,
        val pollCount: Int = 0,
    ) : OtpWatchState

    data class Found(
        val code: String,
        val mailId: Long,
        val subject: String,
        val fromDisplay: String,
        val mailboxAddress: String?,
        val foundAtMillis: Long,
    ) : OtpWatchState

    data class TimedOut(
        val mailboxId: Long?,
        val expiredAtMillis: Long,
    ) : OtpWatchState

    data class Error(
        val message: String,
    ) : OtpWatchState
}

interface OtpWatchNotificationSink {
    fun ensureChannel()
    fun postWatchingNotification(state: OtpWatchState.Watching)
    fun postOtpFoundNotification(found: OtpWatchState.Found)
    fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut)
    fun cancelWatchingNotification()
}

object NoOpOtpWatchNotificationSink : OtpWatchNotificationSink {
    override fun ensureChannel() = Unit
    override fun postWatchingNotification(state: OtpWatchState.Watching) = Unit
    override fun postOtpFoundNotification(found: OtpWatchState.Found) = Unit
    override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) = Unit
    override fun cancelWatchingNotification() = Unit
}

interface SeekMailOtpWatcher {
    val state: StateFlow<OtpWatchState>

    fun startWatch(
        mailboxId: Long? = null,
        mailboxAddress: String? = null,
        durationMillis: Long = DEFAULT_DURATION_MS,
    )

    fun stopWatch()

    fun dismissResult() {}

    companion object {
        const val DEFAULT_DURATION_MS = 180_000L
        const val NORMAL_POLL_INTERVAL_MS = 4_000L
        const val LOW_QUOTA_POLL_INTERVAL_MS = 10_000L
        const val RATE_LIMITED_BACKOFF_MS = 15_000L
    }
}

object NoOpSeekMailOtpWatcher : SeekMailOtpWatcher {
    private val _state = MutableStateFlow<OtpWatchState>(OtpWatchState.Idle)
    override val state: StateFlow<OtpWatchState> = _state.asStateFlow()

    override fun startWatch(
        mailboxId: Long?,
        mailboxAddress: String?,
        durationMillis: Long,
    ) = Unit

    override fun stopWatch() = Unit
}

class DefaultSeekMailOtpWatcher(
    private val seekMailRepository: SeekMailRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationSink: OtpWatchNotificationSink = NoOpOtpWatchNotificationSink,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : SeekMailOtpWatcher {

    private val _state = MutableStateFlow<OtpWatchState>(OtpWatchState.Idle)
    override val state: StateFlow<OtpWatchState> = _state.asStateFlow()

    private val lock = Any()
    private var watchJob: Job? = null
    private var watchGeneration: Long = 0L

    override fun startWatch(
        mailboxId: Long?,
        mailboxAddress: String?,
        durationMillis: Long,
    ) {
        val safeDuration = durationMillis.coerceAtLeast(1_000L)
        if (settingsRepository.settings.value.seekMailApiKey.trim().isEmpty()) {
            synchronized(lock) {
                watchGeneration++
                watchJob?.cancel()
                watchJob = null
                try {
                    runSinkSafely { notificationSink.cancelWatchingNotification() }
                } finally {
                    _state.value = OtpWatchState.Error("请先配置 Seek 邮箱 API Key")
                }
            }
            return
        }

        val generation: Long
        val startedAt = clockMillis()
        val expiresAt = startedAt + safeDuration
        val initialWatching = OtpWatchState.Watching(
            mailboxId = mailboxId,
            mailboxAddress = mailboxAddress?.trim()?.takeIf { it.isNotEmpty() },
            startedAtMillis = startedAt,
            expiresAtMillis = expiresAt,
            remainingSeconds = ((safeDuration + 999L) / 1000L).toInt().coerceAtLeast(1),
            pollCount = 0,
        )

        synchronized(lock) {
            generation = ++watchGeneration
            watchJob?.cancel()
            watchJob = null
            runSinkSafely { notificationSink.ensureChannel() }
            runSinkSafely { notificationSink.postWatchingNotification(initialWatching) }
            _state.value = initialWatching
            watchJob = scope.launch(ioDispatcher) {
                runWatchLoop(
                    generation = generation,
                    mailboxId = mailboxId,
                    mailboxAddress = initialWatching.mailboxAddress,
                    startedAtMillis = startedAt,
                    expiresAtMillis = expiresAt,
                )
            }
        }
    }

    override fun stopWatch() {
        synchronized(lock) {
            watchGeneration++
            watchJob?.cancel()
            watchJob = null
            try {
                runSinkSafely { notificationSink.cancelWatchingNotification() }
            } finally {
                _state.value = OtpWatchState.Idle
            }
        }
    }

    override fun dismissResult() {
        synchronized(lock) {
            val current = _state.value
            if (current !is OtpWatchState.Watching) {
                _state.value = OtpWatchState.Idle
            }
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lock) { watchGeneration == generation }

    private suspend fun runWatchLoop(
        generation: Long,
        mailboxId: Long?,
        mailboxAddress: String?,
        startedAtMillis: Long,
        expiresAtMillis: Long,
    ) {
        try {
            var baselineMaxId = 0L
            val baselineIds = HashSet<Long>()
            var baselineInitialized = false
            var rateLimitedBackoff = false
            var pollCount = 0
            var lastNotificationUpdateAt = startedAtMillis

            val baselineResult = seekMailRepository.getMails(
                mailboxId = mailboxId,
                limit = BASELINE_PAGE_SIZE,
                offset = 0,
                forceRefresh = true,
            )
            if (!isCurrentGeneration(generation)) return

            baselineResult.onSuccess { page ->
                baselineMaxId = page.items.maxOfOrNull { it.id } ?: 0L
                page.items.forEach { baselineIds.add(it.id) }
                baselineInitialized = true
            }.onFailure { err ->
                if (isFatalAuthFailure(err)) {
                    emitFatalError(generation, mapErrorMessage(err))
                    return
                }
                if ((err as? SeekMailException)?.kind == SeekMailErrorKind.RATE_LIMITED) {
                    rateLimitedBackoff = true
                }
            }

            while (isCurrentGeneration(generation) && clockMillis() < expiresAtMillis) {
                val intervalMs = computePollIntervalMs(
                    nowMillis = clockMillis(),
                    expiresAtMillis = expiresAtMillis,
                    rateLimitedBackoff = rateLimitedBackoff,
                )
                rateLimitedBackoff = false

                var waitRemaining = intervalMs
                while (waitRemaining > 0L && isCurrentGeneration(generation) && clockMillis() < expiresAtMillis) {
                    val stepMs = minOf(COUNTDOWN_TICK_MS, waitRemaining)
                    delayFn(stepMs)
                    waitRemaining -= stepMs
                    if (!isCurrentGeneration(generation)) return
                    val now = clockMillis()
                    if (now >= expiresAtMillis) break
                    val remainingSec = (((expiresAtMillis - now) + 999L) / 1000L).toInt().coerceAtLeast(1)
                    val watching = OtpWatchState.Watching(
                        mailboxId = mailboxId,
                        mailboxAddress = mailboxAddress,
                        startedAtMillis = startedAtMillis,
                        expiresAtMillis = expiresAtMillis,
                        remainingSeconds = remainingSec,
                        pollCount = pollCount,
                    )
                    val shouldRefreshNotification =
                        now - lastNotificationUpdateAt >= NOTIFICATION_REFRESH_INTERVAL_MS
                    synchronized(lock) {
                        if (watchGeneration != generation) return
                        if (shouldRefreshNotification) {
                            lastNotificationUpdateAt = now
                            runSinkSafely { notificationSink.postWatchingNotification(watching) }
                        }
                        _state.value = watching
                    }
                }

                if (!isCurrentGeneration(generation) || clockMillis() >= expiresAtMillis) break

                pollCount++
                val remainingSecNow = (((expiresAtMillis - clockMillis()) + 999L) / 1000L).toInt().coerceAtLeast(1)
                val watchingNow = OtpWatchState.Watching(
                    mailboxId = mailboxId,
                    mailboxAddress = mailboxAddress,
                    startedAtMillis = startedAtMillis,
                    expiresAtMillis = expiresAtMillis,
                    remainingSeconds = remainingSecNow,
                    pollCount = pollCount,
                )
                synchronized(lock) {
                    if (watchGeneration != generation) return
                    _state.value = watchingNow
                }

                val pageResult = seekMailRepository.getMails(
                    mailboxId = mailboxId,
                    limit = BASELINE_PAGE_SIZE,
                    offset = 0,
                    forceRefresh = true,
                )
                if (!isCurrentGeneration(generation)) return

                var authFatalMessage: String? = null
                pageResult.onSuccess { page ->
                    val pollNow = clockMillis()
                    val candidates: List<SeekMailSummary> = if (!baselineInitialized) {
                        val baselineItems = ArrayList<SeekMailSummary>(page.items.size)
                        val newlyArrived = ArrayList<SeekMailSummary>()
                        for (item in page.items) {
                            val itemTime = parseMailTimestampMillis(item)
                            if (itemTime != null && itemTime in startedAtMillis..(pollNow + 300_000L)) {
                                newlyArrived.add(item)
                            } else {
                                baselineItems.add(item)
                            }
                        }
                        baselineMaxId = baselineItems.maxOfOrNull { it.id } ?: 0L
                        baselineItems.forEach { baselineIds.add(it.id) }
                        baselineInitialized = true
                        newlyArrived.sortedWith(
                            compareByDescending<SeekMailSummary> { it.receivedAt.ifBlank { it.createdAt } }
                                .thenByDescending { it.id },
                        )
                    } else {
                        page.items
                            .filter { it.id > baselineMaxId && it.id !in baselineIds }
                            .sortedWith(
                                compareByDescending<SeekMailSummary> { it.receivedAt.ifBlank { it.createdAt } }
                                    .thenByDescending { it.id },
                            )
                    }

                    for (candidate in candidates) {
                        if (!isCurrentGeneration(generation)) return
                        val summaryCode = VerificationCodeExtractor.resolveCode(
                            serverCode = candidate.verificationCode,
                            subject = candidate.subject,
                            bodyText = candidate.snippet,
                        )
                        var resolvedCode = summaryCode
                        if (resolvedCode == null) {
                            val detailResult = seekMailRepository.getMailDetail(
                                mailId = candidate.id,
                                forceRefresh = true,
                            )
                            if (!isCurrentGeneration(generation)) return
                            val detail = detailResult.getOrNull()
                            if (detail != null) {
                                resolvedCode = VerificationCodeExtractor.resolveCode(
                                    serverCode = detail.summary.verificationCode,
                                    subject = detail.summary.subject,
                                    bodyText = detail.bodyText,
                                )
                                if (resolvedCode == null) {
                                    baselineIds.add(candidate.id)
                                }
                            } else {
                                val detailErr = detailResult.exceptionOrNull()
                                if (detailErr != null && isFatalAuthFailure(detailErr)) {
                                    authFatalMessage = mapErrorMessage(detailErr)
                                    break
                                }
                                if ((detailErr as? SeekMailException)?.kind == SeekMailErrorKind.RATE_LIMITED) {
                                    rateLimitedBackoff = true
                                }
                            }
                        }

                        if (resolvedCode != null) {
                            val found = OtpWatchState.Found(
                                code = resolvedCode,
                                mailId = candidate.id,
                                subject = candidate.subject,
                                fromDisplay = candidate.fromName.ifBlank { candidate.fromAddress },
                                mailboxAddress = mailboxAddress
                                    ?: candidate.toAddress.trim().takeIf { it.isNotEmpty() },
                                foundAtMillis = clockMillis(),
                            )
                            synchronized(lock) {
                                if (watchGeneration != generation) return
                                watchJob = null
                                try {
                                    runSinkSafely { notificationSink.cancelWatchingNotification() }
                                    runSinkSafely { notificationSink.postOtpFoundNotification(found) }
                                } finally {
                                    _state.value = found
                                }
                            }
                            return
                        }
                    }
                }.onFailure { err ->
                    if (isFatalAuthFailure(err)) {
                        authFatalMessage = mapErrorMessage(err)
                    } else if ((err as? SeekMailException)?.kind == SeekMailErrorKind.RATE_LIMITED) {
                        rateLimitedBackoff = true
                    }
                }

                if (authFatalMessage != null) {
                    emitFatalError(generation, authFatalMessage)
                    return
                }
            }

            if (isCurrentGeneration(generation)) {
                val timedOut = OtpWatchState.TimedOut(
                    mailboxId = mailboxId,
                    expiredAtMillis = clockMillis(),
                )
                synchronized(lock) {
                    if (watchGeneration != generation) return
                    watchJob = null
                    try {
                        runSinkSafely { notificationSink.cancelWatchingNotification() }
                        runSinkSafely { notificationSink.postTimedOutNotification(timedOut) }
                    } finally {
                        _state.value = timedOut
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (isCurrentGeneration(generation)) {
                emitFatalError(generation, t.message?.takeIf { it.isNotBlank() } ?: "验证码监听异常中止")
            }
        }
    }

    private fun emitFatalError(generation: Long, message: String) {
        synchronized(lock) {
            if (watchGeneration != generation) return
            watchJob = null
            try {
                runSinkSafely { notificationSink.cancelWatchingNotification() }
            } finally {
                _state.value = OtpWatchState.Error(message)
            }
        }
    }

    private inline fun runSinkSafely(block: () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Notification delivery failure must not corrupt watcher state transitions.
        }
    }

    private fun computePollIntervalMs(
        nowMillis: Long,
        expiresAtMillis: Long,
        rateLimitedBackoff: Boolean,
    ): Long {
        val remainingWindow = (expiresAtMillis - nowMillis).coerceAtLeast(0L)
        if (remainingWindow == 0L) return 0L

        val rl = seekMailRepository.latestRateLimit.value
        val nowSeconds = nowMillis / 1000L
        var interval = SeekMailOtpWatcher.NORMAL_POLL_INTERVAL_MS

        if (rl != null) {
            if (rl.remaining <= 2 && rl.resetEpochSeconds > nowSeconds) {
                val resetWait = (rl.resetEpochSeconds - nowSeconds) * 1000L + 500L
                interval = maxOf(interval, resetWait)
            } else if (rl.remaining <= 10) {
                interval = maxOf(interval, SeekMailOtpWatcher.LOW_QUOTA_POLL_INTERVAL_MS)
            }
        }
        if (rateLimitedBackoff) {
            val resetWait = if (rl != null && rl.resetEpochSeconds > nowSeconds) {
                (rl.resetEpochSeconds - nowSeconds) * 1000L + 500L
            } else {
                0L
            }
            interval = maxOf(interval, SeekMailOtpWatcher.RATE_LIMITED_BACKOFF_MS, resetWait)
        }
        return minOf(interval, remainingWindow)
    }

    private fun parseMailTimestampMillis(item: SeekMailSummary): Long? {
        val raw = item.receivedAt.ifBlank { item.createdAt }.trim()
        if (raw.isEmpty()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
    }

    private fun isFatalAuthFailure(err: Throwable): Boolean {
        val kind = (err as? SeekMailException)?.kind ?: return false
        return kind == SeekMailErrorKind.MISSING_API_KEY ||
            kind == SeekMailErrorKind.INVALID_API_KEY_FORMAT ||
            kind == SeekMailErrorKind.UNAUTHORIZED ||
            kind == SeekMailErrorKind.FORBIDDEN
    }

    private fun mapErrorMessage(err: Throwable): String {
        val ex = err as? SeekMailException
        return when (ex?.kind) {
            SeekMailErrorKind.MISSING_API_KEY -> "请先配置 Seek 邮箱 API Key"
            SeekMailErrorKind.INVALID_API_KEY_FORMAT -> "Seek 邮箱 API Key 格式无效"
            SeekMailErrorKind.UNAUTHORIZED,
            SeekMailErrorKind.FORBIDDEN,
            -> "Seek 邮箱 API Key 无效或无权访问"
            else -> err.message?.takeIf { it.isNotBlank() } ?: "验证码监听失败"
        }
    }

    companion object {
        private const val BASELINE_PAGE_SIZE = 20
        private const val COUNTDOWN_TICK_MS = 1_000L
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 10_000L
    }
}
